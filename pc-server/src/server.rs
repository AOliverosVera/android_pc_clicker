//! The server's brain: pairing, session handshake and input handling. Pure
//! logic (no sockets, no real mouse), so the tests below drive it with a
//! simulated phone that speaks the real protocol.
//!
//! Security properties, in order of the code below:
//! - Only phones that completed pairing (confirmed on both screens) are known.
//! - A session starts only from a HELLO carrying a valid MAC under the phone's
//!   pairing key; anything else is dropped silently.
//! - Every input packet is AES-GCM authenticated and replay-checked before it
//!   is even parsed (`Channel::open`).
//! - State created by the network is bounded (one pairing at a time, a fixed
//!   number of pending sessions, a fixed number of paired phones).

use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use crate::channel::{Channel, Role};
use crate::crypto::{self, EcdhKeyPair, Key32};
use crate::input::InputSink;
use crate::protocol::{
    Button, EC_POINT_LEN, Id, Inner, MAX_PHONE_NAME, Nonce16, Packet, PairStatus, decode, encode, is_newer,
    sanitize_name,
};
use crate::store::{PairedPhone, Store};

/// A held button is released if the phone goes quiet this long, so a dropped
/// connection can never leave the PC stuck mid-drag.
pub const RELEASE_AFTER: Duration = Duration::from_secs(3);
/// A silent session is closed after this long.
pub const FORGET_AFTER: Duration = Duration::from_secs(10);
/// How long a pairing attempt may take from first packet to confirmation.
pub const PAIRING_TIMEOUT: Duration = Duration::from_secs(120);
/// Handshakes that never sent an authentic packet are kept at most this many.
pub const MAX_PENDING_SESSIONS: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Notice {
    Connected { phone: String, addr: SocketAddr },
    Disconnected { phone: String, reason: &'static str },
    PairingOpened { until_secs: u64 },
    PairingClosed,
    /// Ask the person at the PC to compare `code` with the phone's screen.
    PairingRequest { pair_id: Id, phone: String, code: u32 },
    Paired { phone: String },
    PairingFailed { reason: &'static str },
}

pub struct Server<S: InputSink> {
    sink: S,
    name: String,
    store: Store,
    pairing_open_until: Option<Instant>,
    pending_pairing: Option<PendingPairing>,
    /// The last finished pairing's final answer, re-sent if the phone's
    /// confirmation is retransmitted.
    finished_pairing: Option<(Id, Vec<u8>, Instant)>,
    sessions: HashMap<u32, Session>,
    notices: Vec<Notice>,
}

struct PendingPairing {
    pair_id: Id,
    phone: String,
    pka: [u8; EC_POINT_LEN],
    keypair: EcdhKeyPair,
    nb: Nonce16,
    commit_reply: Vec<u8>,
    na: Option<Nonce16>,
    key: Option<Key32>,
    phone_confirmed: bool,
    operator_accepted: bool,
    started: Instant,
}

struct Session {
    pair_id: Id,
    phone: String,
    channel: Channel,
    nc: Nonce16,
    welcome: Vec<u8>,
    /// False until the first authentic Secure packet arrives.
    active: bool,
    last_seen: Instant,
    last_event: Option<u16>,
    down: [bool; 2],
    /// Id of the last key typed; keys have their own counter.
    last_key: Option<u16>,
}

impl<S: InputSink> Server<S> {
    pub fn new(sink: S, name: String, store: Store) -> Self {
        Server {
            sink,
            name,
            store,
            pairing_open_until: None,
            pending_pairing: None,
            finished_pairing: None,
            sessions: HashMap::new(),
            notices: Vec::new(),
        }
    }

    pub fn store(&self) -> &Store {
        &self.store
    }

    pub fn drain_notices(&mut self) -> Vec<Notice> {
        std::mem::take(&mut self.notices)
    }

    // ------------------------------------------------------------ dispatch

    /// Handles one datagram and returns the reply for its sender, if any.
    pub fn handle(&mut self, datagram: &[u8], from: SocketAddr, now: Instant) -> Option<Vec<u8>> {
        match decode(datagram)? {
            Packet::Discover => Some(encode(&Packet::Announce {
                server_id: self.store.server_id,
                pairing_open: self.pairing_is_open(now),
                name: self.name.clone(),
            })),
            Packet::PairStart { pair_id, pka, name } => Some(self.pair_start(pair_id, pka, &name, now)),
            Packet::PairNonce { pair_id, na } => Some(self.pair_nonce(pair_id, na)),
            Packet::PairConfirm { pair_id, tag } => Some(self.pair_confirm(pair_id, &tag, now)),
            Packet::Hello { pair_id, nc, tag } => self.hello(pair_id, nc, &tag, now),
            Packet::Secure { session_id } => self.secure(session_id, datagram, from, now),
            // Packets only the PC sends are never accepted from the network.
            Packet::Announce { .. }
            | Packet::PairCommit { .. }
            | Packet::PairReveal { .. }
            | Packet::PairResult { .. }
            | Packet::Welcome { .. } => None,
        }
    }

    // ------------------------------------------------------------ pairing

    pub fn open_pairing(&mut self, now: Instant, duration: Duration) {
        self.pairing_open_until = Some(now + duration);
        self.notices.push(Notice::PairingOpened { until_secs: duration.as_secs() });
    }

    pub fn pairing_is_open(&self, now: Instant) -> bool {
        self.pairing_open_until.is_some_and(|until| now < until)
    }

    fn close_pairing(&mut self) {
        if self.pairing_open_until.take().is_some() {
            self.notices.push(Notice::PairingClosed);
        }
    }

    fn result(&self, pair_id: Id, status: PairStatus) -> Vec<u8> {
        encode(&Packet::PairResult { pair_id, status, server_id: [0; 8], tag: [0; 16] })
    }

    fn pair_start(&mut self, pair_id: Id, pka: [u8; EC_POINT_LEN], name: &str, now: Instant) -> Vec<u8> {
        if let Some(p) = &self.pending_pairing {
            return if p.pair_id == pair_id { p.commit_reply.clone() } else { self.result(pair_id, PairStatus::Busy) };
        }
        if !self.pairing_is_open(now) {
            return self.result(pair_id, PairStatus::Closed);
        }
        let keypair = EcdhKeyPair::generate();
        let nb = crypto::random();
        let commit = crypto::commitment(&keypair.public, &pka, &nb);
        let commit_reply = encode(&Packet::PairCommit { pair_id, pkb: keypair.public, commit });
        self.pending_pairing = Some(PendingPairing {
            pair_id,
            phone: sanitize_name(name, MAX_PHONE_NAME, "Phone"),
            pka,
            keypair,
            nb,
            commit_reply: commit_reply.clone(),
            na: None,
            key: None,
            phone_confirmed: false,
            operator_accepted: false,
            started: now,
        });
        commit_reply
    }

    fn pair_nonce(&mut self, pair_id: Id, na: Nonce16) -> Vec<u8> {
        let Some(p) = self.pending_pairing.as_mut().filter(|p| p.pair_id == pair_id) else {
            return self.result(pair_id, PairStatus::Unknown);
        };
        match p.na {
            // A retransmission: answer again.
            Some(existing) if existing == na => {}
            // A different nonce for the same attempt is never legitimate.
            Some(_) => return self.abort_pairing("the phone sent conflicting pairing data"),
            None => {
                let Some(shared) = p.keypair.shared_secret(&p.pka) else {
                    return self.abort_pairing("the phone sent an invalid key");
                };
                let key = crypto::pairing_key(&shared, &na, &p.nb, &p.pair_id, &p.pka, &p.keypair.public);
                let code = crypto::verification_code(&p.pka, &p.keypair.public, &na, &p.nb);
                p.na = Some(na);
                p.key = Some(key);
                self.notices.push(Notice::PairingRequest { pair_id, phone: p.phone.clone(), code });
            }
        }
        let nb = self.pending_pairing.as_ref().map(|p| p.nb).unwrap();
        encode(&Packet::PairReveal { pair_id, nb })
    }

    fn pair_confirm(&mut self, pair_id: Id, tag: &[u8; 16], now: Instant) -> Vec<u8> {
        if let Some((id, reply, _)) = &self.finished_pairing
            && *id == pair_id
        {
            return reply.clone();
        }
        let Some(p) = self.pending_pairing.as_mut().filter(|p| p.pair_id == pair_id) else {
            return self.result(pair_id, PairStatus::Unknown);
        };
        let Some(key) = &p.key else { return self.result(pair_id, PairStatus::Unknown) };
        if !crypto::verify_phone_confirm(key, tag) {
            return self.abort_pairing("the phone's key did not match (possible interference)");
        }
        p.phone_confirmed = true;
        if p.operator_accepted { self.finish_pairing(now) } else { self.result(pair_id, PairStatus::Waiting) }
    }

    /// The person at the PC answered the prompt for `pair_id`. The decision
    /// only applies to that exact attempt, so a prompt can never be answered
    /// on behalf of a different phone that showed up in the meantime.
    pub fn operator_decision(&mut self, pair_id: Id, accept: bool, now: Instant) {
        let Some(p) = self.pending_pairing.as_mut().filter(|p| p.pair_id == pair_id && p.key.is_some()) else {
            return;
        };
        if !accept {
            self.abort_pairing("rejected on this PC");
            return;
        }
        p.operator_accepted = true;
        if p.phone_confirmed {
            self.finish_pairing(now);
        }
    }

    /// Returns the request that is waiting for the operator, if any.
    pub fn awaiting_operator(&self) -> Option<Id> {
        self.pending_pairing.as_ref().filter(|p| p.key.is_some() && !p.operator_accepted).map(|p| p.pair_id)
    }

    fn finish_pairing(&mut self, now: Instant) -> Vec<u8> {
        let p = self.pending_pairing.take().expect("pending pairing");
        let key = p.key.expect("key derived");
        let tag = crypto::pc_confirm_tag(&key, &self.store.server_id);
        let reply = encode(&Packet::PairResult {
            pair_id: p.pair_id,
            status: PairStatus::Accepted,
            server_id: self.store.server_id,
            tag,
        });
        let saved = self.store.add(PairedPhone { id: p.pair_id, name: p.phone.clone(), key });
        let reply = match saved {
            Ok(()) => {
                self.notices.push(Notice::Paired { phone: p.phone });
                reply
            }
            Err(_) => {
                self.notices.push(Notice::PairingFailed { reason: "could not save the pairing to disk" });
                self.result(p.pair_id, PairStatus::Rejected)
            }
        };
        self.finished_pairing = Some((p.pair_id, reply.clone(), now));
        self.close_pairing();
        reply
    }

    fn abort_pairing(&mut self, reason: &'static str) -> Vec<u8> {
        let p = self.pending_pairing.take().expect("pending pairing");
        let reply = self.result(p.pair_id, PairStatus::Rejected);
        self.finished_pairing = Some((p.pair_id, reply.clone(), p.started));
        self.notices.push(Notice::PairingFailed { reason });
        // A failed attempt may be an attack in progress; require the operator
        // to open pairing again deliberately.
        self.close_pairing();
        reply
    }

    /// Forgets phones by id prefix (or "all") and ends their sessions.
    pub fn unpair(&mut self, prefix: &str) -> std::io::Result<Vec<String>> {
        let before: Vec<Id> = self.store.phones().iter().map(|p| p.id).collect();
        let removed = self.store.remove(prefix)?;
        let still: Vec<Id> = self.store.phones().iter().map(|p| p.id).collect();
        let gone: Vec<Id> = before.into_iter().filter(|id| !still.contains(id)).collect();
        let ids: Vec<u32> =
            self.sessions.iter().filter(|(_, s)| gone.contains(&s.pair_id)).map(|(id, _)| *id).collect();
        for id in ids {
            self.end_session(id, "unpaired");
        }
        Ok(removed)
    }

    // ------------------------------------------------------------ sessions

    fn hello(&mut self, pair_id: Id, nc: Nonce16, tag: &[u8; 16], now: Instant) -> Option<Vec<u8>> {
        let phone = self.store.find(&pair_id)?;
        if !crypto::verify_hello(&phone.key, &pair_id, &nc, tag) {
            return None; // not from a paired phone: no answer at all
        }
        // A retransmitted HELLO gets the same answer, not a second session.
        if let Some(s) = self.sessions.values().find(|s| !s.active && s.pair_id == pair_id && s.nc == nc) {
            return Some(s.welcome.clone());
        }
        let (key, name) = (phone.key.clone(), phone.name.clone());

        let pending: Vec<(u32, Instant)> =
            self.sessions.iter().filter(|(_, s)| !s.active).map(|(id, s)| (*id, s.last_seen)).collect();
        if pending.len() >= MAX_PENDING_SESSIONS
            && let Some((oldest, _)) = pending.iter().min_by_key(|(_, t)| *t)
        {
            self.sessions.remove(oldest);
        }

        let session_id = loop {
            let id = u32::from_le_bytes(crypto::random());
            if id != 0 && !self.sessions.contains_key(&id) {
                break id;
            }
        };
        let ns: Nonce16 = crypto::random();
        let session_key = crypto::session_key(&key, &nc, &ns, &pair_id, session_id);
        let welcome = encode(&Packet::Welcome {
            pair_id,
            nc,
            ns,
            session_id,
            tag: crypto::welcome_tag(&key, &pair_id, &nc, &ns, session_id),
        });
        self.sessions.insert(
            session_id,
            Session {
                pair_id,
                phone: name,
                channel: Channel::new(&session_key, session_id, Role::Pc),
                nc,
                welcome: welcome.clone(),
                active: false,
                last_seen: now,
                last_event: None,
                down: [false; 2],
                last_key: None,
            },
        );
        Some(welcome)
    }

    fn secure(&mut self, session_id: u32, datagram: &[u8], from: SocketAddr, now: Instant) -> Option<Vec<u8>> {
        let session = self.sessions.get_mut(&session_id)?;
        // Authenticate and replay-check before looking at the content at all.
        let plaintext = session.channel.open(datagram)?;
        let message = Inner::decode(&plaintext)?;
        session.last_seen = now;

        if !session.active {
            session.active = true;
            let (pair_id, phone) = (session.pair_id, session.phone.clone());
            // The phone reconnected: its previous session is obsolete.
            let old: Vec<u32> = self
                .sessions
                .iter()
                .filter(|(id, s)| **id != session_id && s.active && s.pair_id == pair_id)
                .map(|(id, _)| *id)
                .collect();
            for id in old {
                self.end_session(id, "reconnected");
            }
            self.notices.push(Notice::Connected { phone, addr: from });
        }

        let Server { sink, sessions, .. } = self;
        let session = sessions.get_mut(&session_id)?;
        match message {
            Inner::Heartbeat { seq, last_event, buttons } => {
                // The heartbeat carries the phone's button state as of
                // `last_event`; adopting it when it is current recovers button
                // packets lost on Wi-Fi.
                if session.last_event.is_none_or(|last| !is_newer(last, last_event)) {
                    session.last_event = Some(last_event);
                    for button in Button::ALL {
                        set_button(sink, session, button, buttons & (1 << button.index()) != 0);
                    }
                }
                Some(session.channel.seal(&Inner::HeartbeatAck { seq }.encode()))
            }
            Inner::Move { dx, dy } => {
                sink.move_relative(dx as i32, dy as i32);
                None
            }
            Inner::Button { event, button, pressed } => {
                // Each button event is sent several times; only the first copy counts.
                if session.last_event.is_none_or(|last| is_newer(event, last)) {
                    session.last_event = Some(event);
                    set_button(sink, session, button, pressed);
                }
                None
            }
            Inner::Keys { first_event, strokes } => {
                // Every KEYS packet repeats the phone's recent keys, oldest
                // first, so a lost packet is made up by the next one. Each key
                // is typed once and in order; older ids are copies.
                for (i, stroke) in strokes.into_iter().enumerate() {
                    let event = first_event.wrapping_add(i as u16);
                    if session.last_key.is_none_or(|last| is_newer(event, last)) {
                        session.last_key = Some(event);
                        sink.key(stroke);
                    }
                }
                None
            }
            Inner::Bye => {
                self.end_session(session_id, "app closed");
                None
            }
            Inner::Forget => {
                // Authenticated by the session, so only the phone itself can do this.
                let prefix = crate::store::hex(&session.pair_id);
                let _ = self.unpair(&prefix);
                None
            }
            Inner::HeartbeatAck { .. } => None,
        }
    }

    fn end_session(&mut self, session_id: u32, reason: &'static str) {
        if let Some(mut s) = self.sessions.remove(&session_id) {
            for button in Button::ALL {
                set_button(&mut self.sink, &mut s, button, false);
            }
            if s.active {
                self.notices.push(Notice::Disconnected { phone: s.phone, reason });
            }
        }
    }

    pub fn tick(&mut self, now: Instant) {
        if self.pairing_open_until.is_some_and(|until| now >= until) && self.pending_pairing.is_none() {
            self.close_pairing();
        }
        if self.pending_pairing.as_ref().is_some_and(|p| now.saturating_duration_since(p.started) >= PAIRING_TIMEOUT) {
            self.pending_pairing = None;
            self.notices.push(Notice::PairingFailed { reason: "timed out" });
            self.close_pairing();
        }
        if self.finished_pairing.as_ref().is_some_and(|(_, _, t)| now.saturating_duration_since(*t) >= PAIRING_TIMEOUT) {
            self.finished_pairing = None;
        }

        let mut expired = Vec::new();
        for (id, s) in &mut self.sessions {
            let idle = now.saturating_duration_since(s.last_seen);
            if idle >= RELEASE_AFTER {
                for button in Button::ALL {
                    set_button(&mut self.sink, s, button, false);
                }
            }
            if idle >= FORGET_AFTER {
                expired.push(*id);
            }
        }
        for id in expired {
            self.end_session(id, "timed out");
        }
    }

    #[cfg(test)]
    fn sink(&self) -> &S {
        &self.sink
    }
}

fn set_button<S: InputSink>(sink: &mut S, session: &mut Session, button: Button, pressed: bool) {
    if session.down[button.index()] != pressed {
        session.down[button.index()] = pressed;
        sink.set_button(button, pressed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{Key, KeyStroke, Special, modifiers};
    use zeroize::Zeroizing;

    #[derive(Default)]
    struct Recorder(Vec<String>);

    impl InputSink for Recorder {
        fn move_relative(&mut self, dx: i32, dy: i32) {
            self.0.push(format!("move {dx} {dy}"));
        }
        fn set_button(&mut self, button: Button, pressed: bool) {
            self.0.push(format!("{button:?} {}", if pressed { "down" } else { "up" }));
        }
        fn key(&mut self, stroke: KeyStroke) {
            let key = match stroke.key {
                Key::Text(c) => c.to_string(),
                Key::Special(s) => format!("{s:?}"),
            };
            self.0.push(if stroke.mods == 0 { key } else { format!("{}+{key}", stroke.mods) });
        }
    }

    fn addr() -> SocketAddr {
        "192.168.1.50:40000".parse().unwrap()
    }

    fn server() -> Server<Recorder> {
        Server::new(Recorder::default(), "DESKTOP".into(), Store::in_memory())
    }

    /// Plays the phone's side of the protocol, exactly as the Android app does.
    struct Phone {
        pair_id: Id,
        keypair: EcdhKeyPair,
        key: Option<Key32>,
        server_id: Option<Id>,
        channel: Option<Channel>,
        session_id: u32,
    }

    impl Phone {
        fn new() -> Self {
            Phone {
                pair_id: crypto::random(),
                keypair: EcdhKeyPair::generate(),
                key: None,
                server_id: None,
                channel: None,
                session_id: 0,
            }
        }

        fn start(&self, s: &mut Server<Recorder>, now: Instant) -> Packet {
            let p = Packet::PairStart { pair_id: self.pair_id, pka: self.keypair.public, name: "Pixel 8".into() };
            decode(&s.handle(&encode(&p), addr(), now).unwrap()).unwrap()
        }

        /// Runs pairing up to the code comparison; returns (phone code, commit-ok).
        fn exchange(&mut self, s: &mut Server<Recorder>, now: Instant) -> (u32, bool) {
            let Packet::PairCommit { pkb, commit, .. } = self.start(s, now) else { panic!("expected commit") };
            let na: Nonce16 = crypto::random();
            let reply = s.handle(&encode(&Packet::PairNonce { pair_id: self.pair_id, na }), addr(), now).unwrap();
            let Packet::PairReveal { nb, .. } = decode(&reply).unwrap() else { panic!("expected reveal") };
            let commit_ok = crypto::commitment(&pkb, &self.keypair.public, &nb) == commit;
            let shared = self.keypair.shared_secret(&pkb).unwrap();
            self.key = Some(crypto::pairing_key(&shared, &na, &nb, &self.pair_id, &self.keypair.public, &pkb));
            (crypto::verification_code(&self.keypair.public, &pkb, &na, &nb), commit_ok)
        }

        fn confirm(&mut self, s: &mut Server<Recorder>, now: Instant) -> PairStatus {
            let tag = crypto::phone_confirm_tag(self.key.as_ref().unwrap());
            let reply = s.handle(&encode(&Packet::PairConfirm { pair_id: self.pair_id, tag }), addr(), now).unwrap();
            let Packet::PairResult { status, server_id, tag, .. } = decode(&reply).unwrap() else { panic!() };
            if status == PairStatus::Accepted {
                assert_eq!(tag, crypto::pc_confirm_tag(self.key.as_ref().unwrap(), &server_id), "PC proof");
                self.server_id = Some(server_id);
            }
            status
        }

        fn pair(s: &mut Server<Recorder>, now: Instant) -> Phone {
            s.open_pairing(now, Duration::from_secs(300));
            let mut phone = Phone::new();
            let (code, commit_ok) = phone.exchange(s, now);
            assert!(commit_ok);
            let request = s.drain_notices().into_iter().find_map(|n| match n {
                Notice::PairingRequest { pair_id, code, .. } => Some((pair_id, code)),
                _ => None,
            });
            assert_eq!(request, Some((phone.pair_id, code)), "both screens show the same code");
            assert_eq!(phone.confirm(s, now), PairStatus::Waiting);
            s.operator_decision(phone.pair_id, true, now);
            assert_eq!(phone.confirm(s, now), PairStatus::Accepted);
            s.drain_notices();
            phone
        }

        fn hello_packet(&self, nc: Nonce16) -> Vec<u8> {
            let tag = crypto::hello_tag(self.key.as_ref().unwrap(), &self.pair_id, &nc);
            encode(&Packet::Hello { pair_id: self.pair_id, nc, tag })
        }

        fn connect(&mut self, s: &mut Server<Recorder>, now: Instant) {
            let nc: Nonce16 = crypto::random();
            let reply = s.handle(&self.hello_packet(nc), addr(), now).expect("welcome");
            let Packet::Welcome { ns, session_id, tag, .. } = decode(&reply).unwrap() else { panic!() };
            let key = self.key.as_ref().unwrap();
            assert_eq!(tag, crypto::welcome_tag(key, &self.pair_id, &nc, &ns, session_id), "PC proof");
            let ks = crypto::session_key(key, &nc, &ns, &self.pair_id, session_id);
            self.channel = Some(Channel::new(&ks, session_id, Role::Phone));
            self.session_id = session_id;
        }

        fn packet(&mut self, m: Inner) -> Vec<u8> {
            self.channel.as_mut().unwrap().seal(&m.encode())
        }

        fn send(&mut self, s: &mut Server<Recorder>, m: Inner, now: Instant) -> Option<Inner> {
            let p = self.packet(m);
            let reply = s.handle(&p, addr(), now)?;
            Inner::decode(&self.channel.as_mut().unwrap().open(&reply).expect("authentic reply"))
        }
    }

    fn connected() -> (Server<Recorder>, Phone, Instant) {
        let mut s = server();
        let now = Instant::now();
        let mut phone = Phone::pair(&mut s, now);
        phone.connect(&mut s, now);
        (s, phone, now)
    }

    fn button(event: u16, button: Button, pressed: bool) -> Inner {
        Inner::Button { event, button, pressed }
    }

    /// Plain text keys `first_event`, `first_event + 1`, ...
    fn keys(first_event: u16, text: &str) -> Inner {
        Inner::Keys { first_event, strokes: text.chars().map(|c| KeyStroke { mods: 0, key: Key::Text(c) }).collect() }
    }

    // --- discovery

    #[test]
    fn announce_reports_pairing_state() {
        let mut s = server();
        let now = Instant::now();
        let get = |s: &mut Server<Recorder>| decode(&s.handle(&encode(&Packet::Discover), addr(), now).unwrap());
        assert!(matches!(get(&mut s), Some(Packet::Announce { pairing_open: false, .. })));
        s.open_pairing(now, Duration::from_secs(60));
        assert!(matches!(get(&mut s), Some(Packet::Announce { pairing_open: true, .. })));
    }

    // --- pairing

    #[test]
    fn full_pairing_stores_the_phone_and_closes_pairing() {
        let mut s = server();
        let now = Instant::now();
        let phone = Phone::pair(&mut s, now);
        assert_eq!(s.store().find(&phone.pair_id).unwrap().name, "Pixel 8");
        assert_eq!(*s.store().find(&phone.pair_id).unwrap().key, **phone.key.as_ref().unwrap());
        assert!(!s.pairing_is_open(now));
    }

    #[test]
    fn operator_may_accept_before_the_phone_confirms() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let mut phone = Phone::new();
        phone.exchange(&mut s, now);
        s.operator_decision(phone.pair_id, true, now);
        assert_eq!(phone.confirm(&mut s, now), PairStatus::Accepted);
        // A retransmitted confirmation gets the same answer.
        assert_eq!(phone.confirm(&mut s, now), PairStatus::Accepted);
    }

    #[test]
    fn pairing_is_refused_when_closed() {
        let mut s = server();
        let phone = Phone::new();
        assert!(matches!(phone.start(&mut s, Instant::now()), Packet::PairResult { status: PairStatus::Closed, .. }));
    }

    #[test]
    fn only_one_pairing_at_a_time() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let first = Phone::new();
        assert!(matches!(first.start(&mut s, now), Packet::PairCommit { .. }));
        assert!(matches!(first.start(&mut s, now), Packet::PairCommit { .. }), "retransmit is fine");
        assert!(matches!(Phone::new().start(&mut s, now), Packet::PairResult { status: PairStatus::Busy, .. }));
    }

    #[test]
    fn rejected_on_the_pc_stores_nothing() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let mut phone = Phone::new();
        phone.exchange(&mut s, now);
        s.operator_decision(phone.pair_id, false, now);
        assert_eq!(phone.confirm(&mut s, now), PairStatus::Rejected);
        assert!(s.store().phones().is_empty());
        assert!(!s.pairing_is_open(now), "a rejection closes pairing");
    }

    #[test]
    fn a_decision_for_another_attempt_does_nothing() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let mut phone = Phone::new();
        phone.exchange(&mut s, now);
        s.operator_decision([0xEE; 8], true, now);
        assert_eq!(phone.confirm(&mut s, now), PairStatus::Waiting);
    }

    #[test]
    fn wrong_key_confirmation_aborts_pairing() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let mut phone = Phone::new();
        phone.exchange(&mut s, now);
        phone.key = Some(Zeroizing::new([0u8; 32])); // e.g. a man in the middle
        assert_eq!(phone.confirm(&mut s, now), PairStatus::Rejected);
        assert!(s.store().phones().is_empty());
    }

    #[test]
    fn conflicting_nonce_aborts_pairing() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let mut phone = Phone::new();
        phone.exchange(&mut s, now);
        let reply = s.handle(&encode(&Packet::PairNonce { pair_id: phone.pair_id, na: [9; 16] }), addr(), now);
        assert!(matches!(decode(&reply.unwrap()), Some(Packet::PairResult { status: PairStatus::Rejected, .. })));
    }

    #[test]
    fn invalid_phone_key_aborts_without_panicking() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let start = Packet::PairStart { pair_id: [1; 8], pka: [0x04; 65], name: "x".into() };
        s.handle(&encode(&start), addr(), now);
        let reply = s.handle(&encode(&Packet::PairNonce { pair_id: [1; 8], na: [2; 16] }), addr(), now);
        assert!(matches!(decode(&reply.unwrap()), Some(Packet::PairResult { status: PairStatus::Rejected, .. })));
    }

    #[test]
    fn pairing_times_out() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(600));
        let mut phone = Phone::new();
        phone.exchange(&mut s, now);
        s.tick(now + PAIRING_TIMEOUT);
        assert_eq!(phone.confirm(&mut s, now + PAIRING_TIMEOUT), PairStatus::Unknown);
    }

    #[test]
    fn hostile_phone_name_is_sanitized() {
        let mut s = server();
        let now = Instant::now();
        s.open_pairing(now, Duration::from_secs(60));
        let kp = EcdhKeyPair::generate();
        let start = Packet::PairStart { pair_id: [1; 8], pka: kp.public, name: "\x1b]0;pwned\x07".into() };
        s.handle(&encode(&start), addr(), now);
        s.handle(&encode(&Packet::PairNonce { pair_id: [1; 8], na: [2; 16] }), addr(), now);
        let phone = s.drain_notices().into_iter().find_map(|n| match n {
            Notice::PairingRequest { phone, .. } => Some(phone),
            _ => None,
        });
        assert_eq!(phone.as_deref(), Some("?]0;pwned?"));
    }

    // --- session handshake

    #[test]
    fn hello_from_unknown_or_forged_phone_gets_no_answer() {
        let (mut s, phone, now) = connected();
        let stranger = Packet::Hello { pair_id: [0xAA; 8], nc: [1; 16], tag: [0; 16] };
        assert!(s.handle(&encode(&stranger), addr(), now).is_none());
        let forged = Packet::Hello { pair_id: phone.pair_id, nc: [1; 16], tag: [0; 16] };
        assert!(s.handle(&encode(&forged), addr(), now).is_none());
    }

    #[test]
    fn retransmitted_hello_gets_the_same_welcome() {
        let (mut s, phone, now) = connected();
        let hello = phone.hello_packet([5; 16]);
        assert_eq!(s.handle(&hello, addr(), now), s.handle(&hello, addr(), now));
    }

    #[test]
    fn pending_sessions_are_bounded() {
        let (mut s, phone, now) = connected();
        for i in 0..(MAX_PENDING_SESSIONS as u8 + 10) {
            s.handle(&phone.hello_packet([i; 16]), addr(), now);
        }
        let pending = s.sessions.values().filter(|x| !x.active).count();
        assert!(pending <= MAX_PENDING_SESSIONS);
    }

    // --- secure input

    #[test]
    fn authentic_input_moves_the_mouse_and_heartbeats_are_acked() {
        let (mut s, mut phone, now) = connected();
        assert_eq!(phone.send(&mut s, Inner::Move { dx: 4, dy: -7 }, now), None);
        assert_eq!(
            phone.send(&mut s, Inner::Heartbeat { seq: 9, last_event: 0, buttons: 0 }, now),
            Some(Inner::HeartbeatAck { seq: 9 })
        );
        assert_eq!(s.sink().0, vec!["move 4 -7"]);
        assert!(s.drain_notices().contains(&Notice::Connected { phone: "Pixel 8".into(), addr: addr() }));
    }

    #[test]
    fn replayed_and_tampered_packets_do_nothing() {
        let (mut s, mut phone, now) = connected();
        let p = phone.packet(Inner::Move { dx: 1, dy: 1 });
        s.handle(&p, addr(), now);
        s.handle(&p, addr(), now); // replay
        let mut forged = phone.packet(Inner::Move { dx: 2, dy: 2 });
        let last = forged.len() - 1;
        forged[last] ^= 1;
        s.handle(&forged, addr(), now);
        assert_eq!(s.sink().0, vec!["move 1 1"]);
    }

    #[test]
    fn plaintext_v1_packets_are_ignored() {
        let (mut s, _, now) = connected();
        let v1_move = [0x54, 0x50, 0x01, 0x20, 0x05, 0x00, 0xFD, 0xFF];
        assert!(s.handle(&v1_move, addr(), now).is_none());
        assert!(s.sink().0.is_empty());
    }

    #[test]
    fn duplicate_button_copies_are_applied_once() {
        let (mut s, mut phone, now) = connected();
        for _ in 0..3 {
            phone.send(&mut s, button(1, Button::Left, true), now);
        }
        for _ in 0..3 {
            phone.send(&mut s, button(2, Button::Left, false), now);
        }
        assert_eq!(s.sink().0, vec!["Left down", "Left up"]);
    }

    #[test]
    fn heartbeat_recovers_a_lost_button_release() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(5, Button::Left, true), now);
        phone.send(&mut s, Inner::Heartbeat { seq: 1, last_event: 6, buttons: 0 }, now);
        assert_eq!(s.sink().0, vec!["Left down", "Left up"]);
    }

    #[test]
    fn stale_heartbeat_does_not_undo_a_newer_press() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(5, Button::Right, true), now);
        phone.send(&mut s, Inner::Heartbeat { seq: 1, last_event: 4, buttons: 0 }, now);
        assert_eq!(s.sink().0, vec!["Right down"]);
    }

    #[test]
    fn silent_phone_releases_held_buttons_then_is_disconnected() {
        let (mut s, mut phone, t0) = connected();
        phone.send(&mut s, button(1, Button::Left, true), t0);
        s.drain_notices();
        s.tick(t0 + RELEASE_AFTER);
        assert_eq!(s.sink().0, vec!["Left down", "Left up"]);
        s.tick(t0 + FORGET_AFTER);
        assert_eq!(s.drain_notices(), vec![Notice::Disconnected { phone: "Pixel 8".into(), reason: "timed out" }]);
    }

    #[test]
    fn bye_releases_buttons_and_ends_the_session() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(1, Button::Right, true), now);
        s.drain_notices();
        phone.send(&mut s, Inner::Bye, now);
        assert_eq!(s.sink().0, vec!["Right down", "Right up"]);
        assert_eq!(s.drain_notices(), vec![Notice::Disconnected { phone: "Pixel 8".into(), reason: "app closed" }]);
        assert!(phone.send(&mut s, Inner::Move { dx: 1, dy: 0 }, now).is_none());
        assert_eq!(s.sink().0.len(), 2, "session is gone");
    }

    #[test]
    fn reconnecting_replaces_the_old_session() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(1, Button::Left, true), now);
        // Sealed in the old session but never delivered.
        let old_session_packet = phone.packet(Inner::Move { dx: 9, dy: 9 });
        phone.connect(&mut s, now);
        phone.send(&mut s, Inner::Heartbeat { seq: 1, last_event: 1, buttons: 0 }, now);
        assert!(s.sink().0.contains(&"Left up".to_string()), "old session released its button");
        s.handle(&old_session_packet, addr(), now);
        assert!(!s.sink().0.contains(&"move 9 9".to_string()), "the old session no longer accepts input");
    }

    #[test]
    fn unpairing_ends_sessions_and_blocks_reconnection() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, Inner::Heartbeat { seq: 1, last_event: 0, buttons: 0 }, now);
        assert_eq!(s.unpair("all").unwrap(), vec!["Pixel 8".to_string()]);
        assert!(phone.send(&mut s, Inner::Move { dx: 1, dy: 1 }, now).is_none());
        assert!(s.handle(&phone.hello_packet([1; 16]), addr(), now).is_none());
        assert!(!s.sink().0.iter().any(|e| e.starts_with("move")));
    }

    #[test]
    fn phone_can_unpair_itself() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(1, Button::Left, true), now);
        phone.send(&mut s, Inner::Forget, now);
        assert!(s.store().phones().is_empty());
        assert!(s.sink().0.ends_with(&["Left up".to_string()]), "buttons released");
        assert!(s.handle(&phone.hello_packet([1; 16]), addr(), now).is_none());
    }

    // --- keyboard

    #[test]
    fn keys_are_typed_once_and_in_order() {
        let (mut s, mut phone, now) = connected();
        // As the app sends them: each packet repeats the recent keys, and is
        // itself sent several times.
        for _ in 0..3 {
            phone.send(&mut s, keys(10, "h"), now);
        }
        for _ in 0..3 {
            phone.send(&mut s, keys(10, "ho"), now);
        }
        phone.send(&mut s, keys(10, "hol"), now);
        phone.send(&mut s, keys(11, "ola"), now);
        assert_eq!(s.sink().0.concat(), "hola");
    }

    #[test]
    fn a_lost_key_packet_is_made_up_by_the_next_one() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, keys(1, "a"), now);
        let _lost = phone.packet(keys(1, "a\u{f1}"));
        phone.send(&mut s, keys(1, "a\u{f1}o"), now);
        assert_eq!(s.sink().0, vec!["a", "\u{f1}", "o"]);
    }

    #[test]
    fn late_copies_of_old_keys_are_ignored() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, keys(5, "xy"), now);
        let late = phone.packet(keys(5, "x"));
        s.handle(&late, addr(), now);
        assert_eq!(s.sink().0, vec!["x", "y"]);
    }

    #[test]
    fn key_ids_work_across_u16_wraparound() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, keys(u16::MAX, "ab"), now);
        phone.send(&mut s, keys(0, "bc"), now);
        assert_eq!(s.sink().0.concat(), "abc");
    }

    #[test]
    fn shortcuts_and_special_keys_reach_the_keyboard() {
        let (mut s, mut phone, now) = connected();
        let strokes = vec![
            KeyStroke { mods: modifiers::CTRL, key: Key::Text('c') },
            KeyStroke { mods: modifiers::SHIFT, key: Key::Special(Special::End) },
            KeyStroke { mods: modifiers::WIN, key: Key::Special(Special::ModifiersOnly) },
        ];
        phone.send(&mut s, Inner::Keys { first_event: 3, strokes }, now);
        assert_eq!(s.sink().0, vec!["2+c", "1+End", "8+ModifiersOnly"]);
    }

    #[test]
    fn keys_and_buttons_have_separate_event_ids() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(100, Button::Left, true), now);
        phone.send(&mut s, keys(1, "k"), now);
        phone.send(&mut s, button(101, Button::Left, false), now);
        assert_eq!(s.sink().0, vec!["Left down", "k", "Left up"]);
    }

    #[test]
    fn event_ids_work_across_u16_wraparound() {
        let (mut s, mut phone, now) = connected();
        phone.send(&mut s, button(u16::MAX, Button::Left, true), now);
        phone.send(&mut s, button(0, Button::Left, false), now);
        assert_eq!(s.sink().0, vec!["Left down", "Left up"]);
    }
}
