//! The encrypted envelope for everything sent after the session handshake.
//!
//! Datagram: `header(4) | session_id u32 | counter u64 | ciphertext | tag(16)`.
//! The first 16 bytes are authenticated as associated data, so nothing in the
//! packet can be altered. The AES-GCM nonce is `direction(1) | 0 0 0 | counter`:
//! each session has its own key and each direction its own counter, so a
//! nonce is never reused under a key.

use aes_gcm::Aes256Gcm;
use aes_gcm::aead::{Aead, KeyInit, Payload};

use crate::protocol::{HEADER_LEN, TAG_LEN, secure_header};

const AAD_LEN: usize = HEADER_LEN + 4 + 8;
/// Smallest valid Secure datagram: AAD + 1 byte of message + tag.
pub const MIN_SECURE_LEN: usize = AAD_LEN + 1 + TAG_LEN;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Used by the tests, which play the phone's side.
    #[cfg_attr(not(test), allow(dead_code))]
    Phone,
    Pc,
}

const DIR_PHONE_TO_PC: u8 = 1;
const DIR_PC_TO_PHONE: u8 = 2;

pub struct Channel {
    cipher: Aes256Gcm,
    session_id: u32,
    send_dir: u8,
    recv_dir: u8,
    next_counter: u64,
    replay: ReplayWindow,
}

impl Channel {
    pub fn new(key: &[u8; 32], session_id: u32, role: Role) -> Self {
        let (send_dir, recv_dir) = match role {
            Role::Phone => (DIR_PHONE_TO_PC, DIR_PC_TO_PHONE),
            Role::Pc => (DIR_PC_TO_PHONE, DIR_PHONE_TO_PC),
        };
        Channel {
            cipher: Aes256Gcm::new_from_slice(key).expect("32-byte key"),
            session_id,
            send_dir,
            recv_dir,
            next_counter: 1,
            replay: ReplayWindow::default(),
        }
    }

    pub fn seal(&mut self, plaintext: &[u8]) -> Vec<u8> {
        let counter = self.next_counter;
        // 2^64 packets would take millions of years; refuse rather than wrap.
        self.next_counter = counter.checked_add(1).expect("message counter exhausted");
        let aad = aad(self.session_id, counter);
        let ciphertext = self
            .cipher
            .encrypt(&nonce(self.send_dir, counter).into(), Payload { msg: plaintext, aad: &aad })
            .expect("AES-GCM encryption of a short message cannot fail");
        let mut out = Vec::with_capacity(AAD_LEN + ciphertext.len());
        out.extend_from_slice(&aad);
        out.extend_from_slice(&ciphertext);
        out
    }

    /// Returns the plaintext only if the packet is authentic, belongs to this
    /// session and has not been received before.
    pub fn open(&mut self, datagram: &[u8]) -> Option<Vec<u8>> {
        if datagram.len() < MIN_SECURE_LEN || datagram[..HEADER_LEN] != secure_header() {
            return None;
        }
        let session_id = u32::from_le_bytes(datagram[4..8].try_into().ok()?);
        let counter = u64::from_le_bytes(datagram[8..16].try_into().ok()?);
        if session_id != self.session_id || !self.replay.would_accept(counter) {
            return None;
        }
        let plaintext = self
            .cipher
            .decrypt(&nonce(self.recv_dir, counter).into(), Payload { msg: &datagram[AAD_LEN..], aad: &datagram[..AAD_LEN] })
            .ok()?;
        // Only authentic packets may move the replay window.
        self.replay.mark(counter);
        Some(plaintext)
    }
}

fn aad(session_id: u32, counter: u64) -> [u8; AAD_LEN] {
    let mut aad = [0u8; AAD_LEN];
    aad[..HEADER_LEN].copy_from_slice(&secure_header());
    aad[4..8].copy_from_slice(&session_id.to_le_bytes());
    aad[8..16].copy_from_slice(&counter.to_le_bytes());
    aad
}

fn nonce(direction: u8, counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[0] = direction;
    n[4..].copy_from_slice(&counter.to_le_bytes());
    n
}

/// Sliding anti-replay window (as in IPsec, RFC 4303): accepts each counter
/// at most once and tolerates up to 63 packets of reordering.
#[derive(Default)]
struct ReplayWindow {
    highest: u64,
    /// Bit i set = counter `highest - i` already received.
    seen: u64,
}

impl ReplayWindow {
    fn would_accept(&self, counter: u64) -> bool {
        if counter == 0 {
            return false;
        }
        if counter > self.highest {
            return true;
        }
        let age = self.highest - counter;
        age < 64 && self.seen & (1 << age) == 0
    }

    fn mark(&mut self, counter: u64) {
        if counter > self.highest {
            let shift = counter - self.highest;
            self.seen = if shift >= 64 { 0 } else { self.seen << shift };
            self.seen |= 1;
            self.highest = counter;
        } else {
            self.seen |= 1 << (self.highest - counter);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::tests::hex;

    fn pair() -> (Channel, Channel) {
        let key = [7u8; 32];
        (Channel::new(&key, 9, Role::Phone), Channel::new(&key, 9, Role::Pc))
    }

    #[test]
    fn roundtrip_both_directions() {
        let (mut phone, mut pc) = pair();
        let p = phone.seal(b"hello pc");
        assert_eq!(pc.open(&p).as_deref(), Some(&b"hello pc"[..]));
        let r = pc.seal(b"hello phone");
        assert_eq!(phone.open(&r).as_deref(), Some(&b"hello phone"[..]));
    }

    #[test]
    fn replays_are_rejected() {
        let (mut phone, mut pc) = pair();
        let p = phone.seal(b"click");
        assert!(pc.open(&p).is_some());
        assert!(pc.open(&p).is_none());
    }

    #[test]
    fn reordering_within_the_window_is_accepted_once() {
        let (mut phone, mut pc) = pair();
        let packets: Vec<_> = (0..70).map(|_| phone.seal(b"m")).collect();
        assert!(pc.open(&packets[69]).is_some()); // counter 70 first
        assert!(pc.open(&packets[10]).is_some()); // counter 11: within 63 of 70
        assert!(pc.open(&packets[10]).is_none()); // ... but only once
        assert!(pc.open(&packets[5]).is_none()); // counter 6: too old
    }

    #[test]
    fn any_bit_flip_is_rejected() {
        let (mut phone, mut pc) = pair();
        let p = phone.seal(b"move");
        for i in 0..p.len() {
            for bit in 0..8 {
                let mut q = p.clone();
                q[i] ^= 1 << bit;
                assert!(pc.open(&q).is_none(), "accepted a flip at byte {i} bit {bit}");
            }
        }
        assert!(pc.open(&p).is_some(), "the untouched packet is still accepted");
    }

    #[test]
    fn wrong_key_session_or_reflected_packets_are_rejected() {
        let (mut phone, _) = pair();
        let p = phone.seal(b"x");
        assert!(Channel::new(&[8u8; 32], 9, Role::Pc).open(&p).is_none(), "wrong key");
        assert!(Channel::new(&[7u8; 32], 10, Role::Pc).open(&p).is_none(), "wrong session");
        // A packet the phone sent, bounced back at the phone, must not be accepted.
        assert!(Channel::new(&[7u8; 32], 9, Role::Phone).open(&p).is_none(), "reflected");
    }

    #[test]
    fn counter_zero_is_never_valid() {
        let w = ReplayWindow::default();
        assert!(!w.would_accept(0));
    }

    #[test]
    fn sealed_bytes_match_shared_vector() {
        // Shared with ChannelTest.kt via docs/test-vectors.txt.
        let mut phone = Channel::new(&[0xAB; 32], 0x01020304, Role::Phone);
        let first = phone.seal(&[0x02, 0x05, 0x00, 0xFD, 0xFF]); // MOVE dx=5 dy=-3
        let mut pc = Channel::new(&[0xAB; 32], 0x01020304, Role::Pc);
        let reply = pc.seal(&[0x81, 0x2A, 0x00, 0x00, 0x00]); // HEARTBEAT_ACK seq=42
        let report = format!("phone_move={}\npc_ack={}", hex(&first), hex(&reply));
        let vectors = include_str!("../../docs/test-vectors.txt");
        assert_eq!(report, vectors.trim_end().split("\n--\n").nth(1).unwrap());
    }
}
