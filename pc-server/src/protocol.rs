//! Wire protocol v2, shared with the Android app. See `docs/PROTOCOL.md`.
//!
//! Every datagram starts with `'T' 'P' <version> <type>`. Discovery and the
//! pairing/session handshakes travel in the clear (they carry only public
//! values and MACs); all input travels inside [`Packet::Secure`], encrypted
//! and authenticated with AES-256-GCM (see `channel.rs`).
//!
//! The codec is symmetric (every packet can be encoded and decoded) so the
//! tests can play the phone's side against the real server logic.

pub const PORT: u16 = 47820;
pub const VERSION: u8 = 2;
const MAGIC: [u8; 2] = *b"TP";
pub const HEADER_LEN: usize = 4;

/// Requests that can trigger a reply are padded to this size, so the server
/// never answers with more bytes than it received (no reflection amplifier).
pub const PADDED_LEN: usize = 64;
pub const MAX_PC_NAME: usize = 40;
pub const MAX_PHONE_NAME: usize = 32;
pub const EC_POINT_LEN: usize = 65; // uncompressed SEC1 P-256 point
pub const TAG_LEN: usize = 16;

pub type Id = [u8; 8];
pub type Nonce16 = [u8; 16];
pub type Tag = [u8; TAG_LEN];

mod t {
    pub const DISCOVER: u8 = 0x01;
    pub const ANNOUNCE: u8 = 0x02;
    pub const PAIR_START: u8 = 0x10;
    pub const PAIR_COMMIT: u8 = 0x11;
    pub const PAIR_NONCE: u8 = 0x12;
    pub const PAIR_REVEAL: u8 = 0x13;
    pub const PAIR_CONFIRM: u8 = 0x14;
    pub const PAIR_RESULT: u8 = 0x15;
    pub const HELLO: u8 = 0x20;
    pub const WELCOME: u8 = 0x21;
    pub const SECURE: u8 = 0x30;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PairStatus {
    /// Phone confirmed; waiting for the person at the PC.
    Waiting = 0,
    Accepted = 1,
    Rejected = 2,
    /// The PC is not accepting new phones right now.
    Closed = 3,
    /// Another phone is pairing at the moment.
    Busy = 4,
    /// The PC has no record of this pairing attempt (e.g. it restarted).
    Unknown = 5,
}

impl PairStatus {
    fn from_wire(b: u8) -> Option<Self> {
        use PairStatus::*;
        [Waiting, Accepted, Rejected, Closed, Busy, Unknown].into_iter().find(|s| *s as u8 == b)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Packet {
    // phone -> PC
    Discover,
    PairStart { pair_id: Id, pka: [u8; EC_POINT_LEN], name: String },
    PairNonce { pair_id: Id, na: Nonce16 },
    PairConfirm { pair_id: Id, tag: Tag },
    Hello { pair_id: Id, nc: Nonce16, tag: Tag },
    // PC -> phone
    Announce { server_id: Id, pairing_open: bool, name: String },
    PairCommit { pair_id: Id, pkb: [u8; EC_POINT_LEN], commit: [u8; 32] },
    PairReveal { pair_id: Id, nb: Nonce16 },
    PairResult { pair_id: Id, status: PairStatus, server_id: Id, tag: Tag },
    Welcome { pair_id: Id, nc: Nonce16, ns: Nonce16, session_id: u32, tag: Tag },
    // both directions; the body is decrypted by `channel::Channel`
    Secure { session_id: u32 },
}

pub fn decode(buf: &[u8]) -> Option<Packet> {
    if buf.len() < HEADER_LEN || buf[0..2] != MAGIC || buf[2] != VERSION {
        return None;
    }
    let mut r = Reader { buf: &buf[HEADER_LEN..] };
    let packet = match buf[3] {
        t::DISCOVER if buf.len() >= PADDED_LEN => Packet::Discover,
        t::ANNOUNCE => {
            let server_id = r.array()?;
            let pairing_open = r.u8()? & 1 != 0;
            let name = r.string(MAX_PC_NAME)?;
            Packet::Announce { server_id, pairing_open, name }
        }
        t::PAIR_START => {
            let pair_id = r.array()?;
            let pka = r.array()?;
            let name = r.string(MAX_PHONE_NAME)?;
            Packet::PairStart { pair_id, pka, name }
        }
        t::PAIR_COMMIT => Packet::PairCommit { pair_id: r.array()?, pkb: r.array()?, commit: r.array()? },
        t::PAIR_NONCE => Packet::PairNonce { pair_id: r.array()?, na: r.array()? },
        t::PAIR_REVEAL => Packet::PairReveal { pair_id: r.array()?, nb: r.array()? },
        t::PAIR_CONFIRM => Packet::PairConfirm { pair_id: r.array()?, tag: r.array()? },
        t::PAIR_RESULT => Packet::PairResult {
            pair_id: r.array()?,
            status: PairStatus::from_wire(r.u8()?)?,
            server_id: r.array()?,
            tag: r.array()?,
        },
        t::HELLO if buf.len() >= PADDED_LEN => {
            Packet::Hello { pair_id: r.array()?, nc: r.array()?, tag: r.array()? }
        }
        t::WELCOME => Packet::Welcome {
            pair_id: r.array()?,
            nc: r.array()?,
            ns: r.array()?,
            session_id: u32::from_le_bytes(r.array()?),
            tag: r.array()?,
        },
        t::SECURE if buf.len() >= crate::channel::MIN_SECURE_LEN => {
            Packet::Secure { session_id: u32::from_le_bytes(r.array()?) }
        }
        _ => return None,
    };
    Some(packet)
}

pub fn encode(packet: &Packet) -> Vec<u8> {
    let mut w = Writer::new();
    match packet {
        Packet::Discover => {
            w.header(t::DISCOVER);
            w.pad_to(PADDED_LEN);
        }
        Packet::Announce { server_id, pairing_open, name } => {
            w.header(t::ANNOUNCE);
            w.bytes(server_id);
            w.u8(*pairing_open as u8);
            w.string(name, MAX_PC_NAME);
        }
        Packet::PairStart { pair_id, pka, name } => {
            w.header(t::PAIR_START);
            w.bytes(pair_id);
            w.bytes(pka);
            w.string(name, MAX_PHONE_NAME);
        }
        Packet::PairCommit { pair_id, pkb, commit } => {
            w.header(t::PAIR_COMMIT);
            w.bytes(pair_id);
            w.bytes(pkb);
            w.bytes(commit);
        }
        Packet::PairNonce { pair_id, na } => {
            w.header(t::PAIR_NONCE);
            w.bytes(pair_id);
            w.bytes(na);
        }
        Packet::PairReveal { pair_id, nb } => {
            w.header(t::PAIR_REVEAL);
            w.bytes(pair_id);
            w.bytes(nb);
        }
        Packet::PairConfirm { pair_id, tag } => {
            w.header(t::PAIR_CONFIRM);
            w.bytes(pair_id);
            w.bytes(tag);
        }
        Packet::PairResult { pair_id, status, server_id, tag } => {
            w.header(t::PAIR_RESULT);
            w.bytes(pair_id);
            w.u8(*status as u8);
            w.bytes(server_id);
            w.bytes(tag);
        }
        Packet::Hello { pair_id, nc, tag } => {
            w.header(t::HELLO);
            w.bytes(pair_id);
            w.bytes(nc);
            w.bytes(tag);
            w.pad_to(PADDED_LEN);
        }
        Packet::Welcome { pair_id, nc, ns, session_id, tag } => {
            w.header(t::WELCOME);
            w.bytes(pair_id);
            w.bytes(nc);
            w.bytes(ns);
            w.bytes(&session_id.to_le_bytes());
            w.bytes(tag);
        }
        Packet::Secure { .. } => unreachable!("built by channel::Channel::seal"),
    }
    w.0
}

/// Header byte for a Secure packet; `channel.rs` builds the rest.
pub fn secure_header() -> [u8; HEADER_LEN] {
    [MAGIC[0], MAGIC[1], VERSION, t::SECURE]
}

// ---------------------------------------------------------------- inner messages

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Button {
    Left,
    Right,
}

impl Button {
    pub const ALL: [Button; 2] = [Button::Left, Button::Right];

    pub fn index(self) -> usize {
        self as usize
    }
}

/// Messages carried encrypted inside a Secure packet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Inner {
    /// `last_event` is the id of the newest button event the phone sent and
    /// `buttons` its current button state (bit 0 = left, bit 1 = right).
    Heartbeat { seq: u32, last_event: u16, buttons: u8 },
    Move { dx: i16, dy: i16 },
    Button { event: u16, button: Button, pressed: bool },
    Bye,
    /// The user removed the pairing on the phone; the PC forgets it too.
    Forget,
    HeartbeatAck { seq: u32 },
}

mod it {
    pub const HEARTBEAT: u8 = 0x01;
    pub const MOVE: u8 = 0x02;
    pub const BUTTON: u8 = 0x03;
    pub const BYE: u8 = 0x04;
    pub const FORGET: u8 = 0x05;
    pub const HEARTBEAT_ACK: u8 = 0x81;
}

impl Inner {
    pub fn decode(buf: &[u8]) -> Option<Inner> {
        let (&kind, rest) = buf.split_first()?;
        let mut r = Reader { buf: rest };
        Some(match kind {
            it::HEARTBEAT => Inner::Heartbeat {
                seq: u32::from_le_bytes(r.array()?),
                last_event: u16::from_le_bytes(r.array()?),
                buttons: r.u8()?,
            },
            it::MOVE => Inner::Move {
                dx: i16::from_le_bytes(r.array()?),
                dy: i16::from_le_bytes(r.array()?),
            },
            it::BUTTON => Inner::Button {
                event: u16::from_le_bytes(r.array()?),
                button: match r.u8()? {
                    0 => Button::Left,
                    1 => Button::Right,
                    _ => return None,
                },
                pressed: r.u8()? != 0,
            },
            it::BYE => Inner::Bye,
            it::FORGET => Inner::Forget,
            it::HEARTBEAT_ACK => Inner::HeartbeatAck { seq: u32::from_le_bytes(r.array()?) },
            _ => return None,
        })
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(8);
        match self {
            Inner::Heartbeat { seq, last_event, buttons } => {
                out.push(it::HEARTBEAT);
                out.extend_from_slice(&seq.to_le_bytes());
                out.extend_from_slice(&last_event.to_le_bytes());
                out.push(*buttons);
            }
            Inner::Move { dx, dy } => {
                out.push(it::MOVE);
                out.extend_from_slice(&dx.to_le_bytes());
                out.extend_from_slice(&dy.to_le_bytes());
            }
            Inner::Button { event, button, pressed } => {
                out.push(it::BUTTON);
                out.extend_from_slice(&event.to_le_bytes());
                out.push(button.index() as u8);
                out.push(*pressed as u8);
            }
            Inner::Bye => out.push(it::BYE),
            Inner::Forget => out.push(it::FORGET),
            Inner::HeartbeatAck { seq } => {
                out.push(it::HEARTBEAT_ACK);
                out.extend_from_slice(&seq.to_le_bytes());
            }
        }
        out
    }
}

/// True when event id `a` is newer than `b`, treating ids as a wrapping u16 counter.
pub fn is_newer(a: u16, b: u16) -> bool {
    (a.wrapping_sub(b) as i16) > 0
}

/// Makes a name from the network safe to print: no control characters (which
/// could inject terminal escape sequences) and no bidirectional overrides
/// (which could make text display differently from what it is).
pub fn sanitize_name(raw: &str, max_bytes: usize, fallback: &str) -> String {
    let mut out = String::new();
    for c in raw.chars() {
        let bidi = matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}' | '\u{200E}' | '\u{200F}');
        let c = if c.is_control() || bidi { '?' } else { c };
        if out.len() + c.len_utf8() > max_bytes {
            break;
        }
        out.push(c);
    }
    let trimmed = out.trim();
    if trimmed.is_empty() { fallback.to_string() } else { trimmed.to_string() }
}

// ---------------------------------------------------------------- helpers

struct Reader<'a> {
    buf: &'a [u8],
}

impl Reader<'_> {
    fn u8(&mut self) -> Option<u8> {
        let (&b, rest) = self.buf.split_first()?;
        self.buf = rest;
        Some(b)
    }

    fn array<const N: usize>(&mut self) -> Option<[u8; N]> {
        if self.buf.len() < N {
            return None;
        }
        let (head, rest) = self.buf.split_at(N);
        self.buf = rest;
        head.try_into().ok()
    }

    fn string(&mut self, max: usize) -> Option<String> {
        let len = self.u8()? as usize;
        if len > max || self.buf.len() < len {
            return None;
        }
        let (head, rest) = self.buf.split_at(len);
        self.buf = rest;
        String::from_utf8(head.to_vec()).ok()
    }
}

struct Writer(Vec<u8>);

impl Writer {
    fn new() -> Self {
        Writer(Vec::with_capacity(PADDED_LEN * 2))
    }
    fn header(&mut self, kind: u8) {
        self.0.extend_from_slice(&[MAGIC[0], MAGIC[1], VERSION, kind]);
    }
    fn u8(&mut self, b: u8) {
        self.0.push(b);
    }
    fn bytes(&mut self, b: &[u8]) {
        self.0.extend_from_slice(b);
    }
    fn string(&mut self, s: &str, max: usize) {
        let s = truncate_utf8(s, max);
        self.0.push(s.len() as u8);
        self.0.extend_from_slice(s.as_bytes());
    }
    fn pad_to(&mut self, len: usize) {
        if self.0.len() < len {
            self.0.resize(len, 0);
        }
    }
}

fn truncate_utf8(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(p: Packet) {
        assert_eq!(decode(&encode(&p)), Some(p));
    }

    #[test]
    fn every_packet_roundtrips() {
        roundtrip(Packet::Discover);
        roundtrip(Packet::Announce { server_id: [1; 8], pairing_open: true, name: "DESKTOP".into() });
        roundtrip(Packet::PairStart { pair_id: [2; 8], pka: [4; 65], name: "Pixel".into() });
        roundtrip(Packet::PairCommit { pair_id: [2; 8], pkb: [4; 65], commit: [9; 32] });
        roundtrip(Packet::PairNonce { pair_id: [2; 8], na: [5; 16] });
        roundtrip(Packet::PairReveal { pair_id: [2; 8], nb: [6; 16] });
        roundtrip(Packet::PairConfirm { pair_id: [2; 8], tag: [7; 16] });
        roundtrip(Packet::PairResult { pair_id: [2; 8], status: PairStatus::Accepted, server_id: [1; 8], tag: [8; 16] });
        roundtrip(Packet::Hello { pair_id: [2; 8], nc: [3; 16], tag: [7; 16] });
        roundtrip(Packet::Welcome { pair_id: [2; 8], nc: [3; 16], ns: [4; 16], session_id: 77, tag: [7; 16] });
    }

    #[test]
    fn discover_and_hello_are_padded_against_amplification() {
        assert_eq!(encode(&Packet::Discover).len(), PADDED_LEN);
        assert_eq!(encode(&Packet::Hello { pair_id: [0; 8], nc: [0; 16], tag: [0; 16] }).len(), PADDED_LEN);
        // An unpadded discover is ignored, so it can't trigger a larger reply.
        assert_eq!(decode(&encode(&Packet::Discover)[..20]), None);
        let announce = encode(&Packet::Announce { server_id: [0; 8], pairing_open: true, name: "x".repeat(99) });
        assert!(announce.len() <= PADDED_LEN);
    }

    #[test]
    fn welcome_is_no_larger_than_hello() {
        let welcome = encode(&Packet::Welcome { pair_id: [0; 8], nc: [0; 16], ns: [0; 16], session_id: 0, tag: [0; 16] });
        assert!(welcome.len() <= PADDED_LEN);
    }

    #[test]
    fn discover_matches_the_documented_bytes() {
        let mut expected = vec![0x54, 0x50, 0x02, 0x01];
        expected.resize(64, 0);
        assert_eq!(encode(&Packet::Discover), expected);
    }

    #[test]
    fn rejects_bad_magic_version_type_and_truncation() {
        let good = encode(&Packet::PairNonce { pair_id: [2; 8], na: [5; 16] });
        let mut bad_magic = good.clone();
        bad_magic[0] = b'X';
        let mut old_version = good.clone();
        old_version[2] = 1;
        let mut bad_type = good.clone();
        bad_type[3] = 0x7F;
        assert_eq!(decode(&bad_magic), None);
        assert_eq!(decode(&old_version), None);
        assert_eq!(decode(&bad_type), None);
        for len in 0..good.len() {
            assert_eq!(decode(&good[..len]), None, "accepted a {len}-byte prefix");
        }
    }

    #[test]
    fn rejects_overlong_or_invalid_names() {
        let mut p = encode(&Packet::PairStart { pair_id: [2; 8], pka: [4; 65], name: String::new() });
        let len_at = p.len() - 1;
        p[len_at] = (MAX_PHONE_NAME + 1) as u8;
        p.extend(std::iter::repeat_n(b'a', MAX_PHONE_NAME + 1));
        assert_eq!(decode(&p), None);
        let mut invalid_utf8 = encode(&Packet::PairStart { pair_id: [2; 8], pka: [4; 65], name: String::new() });
        let at = invalid_utf8.len() - 1;
        invalid_utf8[at] = 2;
        invalid_utf8.extend([0xC3, 0x28]);
        assert_eq!(decode(&invalid_utf8), None);
    }

    #[test]
    fn inner_messages_roundtrip_and_match_documented_bytes() {
        for m in [
            Inner::Heartbeat { seq: 42, last_event: 7, buttons: 1 },
            Inner::Move { dx: 5, dy: -3 },
            Inner::Button { event: 7, button: Button::Right, pressed: true },
            Inner::Bye,
            Inner::Forget,
            Inner::HeartbeatAck { seq: 42 },
        ] {
            assert_eq!(Inner::decode(&m.encode()), Some(m));
        }
        assert_eq!(Inner::Move { dx: 5, dy: -3 }.encode(), vec![0x02, 0x05, 0x00, 0xFD, 0xFF]);
        assert_eq!(
            Inner::Heartbeat { seq: 42, last_event: 7, buttons: 1 }.encode(),
            vec![0x01, 0x2A, 0x00, 0x00, 0x00, 0x07, 0x00, 0x01]
        );
        assert_eq!(Inner::decode(&[0x03, 0x07, 0x00, 0x09, 0x01]), None); // bad button
        assert_eq!(Inner::decode(&[]), None);
    }

    #[test]
    fn names_are_sanitized_for_the_terminal() {
        assert_eq!(sanitize_name("Pixel 8", 32, "Phone"), "Pixel 8");
        assert_eq!(sanitize_name("evil\x1b[2Jname", 32, "Phone"), "evil?[2Jname");
        assert_eq!(sanitize_name("abc\u{202E}def", 32, "Phone"), "abc?def");
        assert_eq!(sanitize_name("   ", 32, "Phone"), "Phone");
        assert_eq!(sanitize_name(&"ñ".repeat(40), 9, "Phone"), "ññññ");
    }

    #[test]
    fn event_ids_compare_across_wraparound() {
        assert!(is_newer(1, 0));
        assert!(!is_newer(0, 1));
        assert!(!is_newer(5, 5));
        assert!(is_newer(0, u16::MAX));
    }
}
