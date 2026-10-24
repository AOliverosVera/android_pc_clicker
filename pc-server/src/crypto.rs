//! Cryptographic building blocks. Everything is a thin wrapper over the
//! RustCrypto crates; the only "design" here is which inputs go into which
//! function. Every derivation starts with a distinct `touchpad/v2/...` label
//! (domain separation), so no key or tag can be reused for another purpose.
//!
//! The Android app implements the same functions with `javax.crypto`; the
//! test vectors at the bottom are shared with it (see `docs/PROTOCOL.md`).

use hkdf::Hkdf;
use hmac::{Hmac, KeyInit, Mac};
use p256::PublicKey;
use p256::ecdh::EphemeralSecret;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::protocol::{EC_POINT_LEN, Id, Nonce16, Tag};

pub type Key32 = Zeroizing<[u8; 32]>;

const L_COMMIT: &[u8] = b"touchpad/v2/commit";
const L_CODE: &[u8] = b"touchpad/v2/code";
const L_PAIRING_KEY: &[u8] = b"touchpad/v2/pairing-key";
const L_CONFIRM_PHONE: &[u8] = b"touchpad/v2/confirm-phone";
const L_CONFIRM_PC: &[u8] = b"touchpad/v2/confirm-pc";
const L_HELLO: &[u8] = b"touchpad/v2/hello";
const L_WELCOME: &[u8] = b"touchpad/v2/welcome";
const L_SESSION: &[u8] = b"touchpad/v2/session";

/// Bytes from the operating system's CSPRNG. If the OS cannot provide
/// randomness nothing here can be secure, so failing loudly is correct.
pub fn random<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    getrandom::fill(&mut out).expect("operating system random number generator failed");
    out
}

pub fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

fn mac(key: &[u8], parts: &[&[u8]]) -> Hmac<Sha256> {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(key).expect("HMAC accepts any key length");
    for p in parts {
        m.update(p);
    }
    m
}

/// HMAC-SHA256 truncated to 128 bits.
pub fn hmac_tag(key: &[u8], parts: &[&[u8]]) -> Tag {
    let full = mac(key, parts).finalize().into_bytes();
    full[..16].try_into().unwrap()
}

/// Constant-time check of a truncated HMAC-SHA256 tag.
pub fn verify_tag(key: &[u8], parts: &[&[u8]], tag: &Tag) -> bool {
    mac(key, parts).verify_truncated_left(tag).is_ok()
}

fn hkdf32(salt: &[u8], ikm: &[u8], info: &[&[u8]]) -> Key32 {
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut okm = Zeroizing::new([0u8; 32]);
    hk.expand_multi_info(info, okm.as_mut()).expect("32 bytes is a valid HKDF-SHA256 length");
    okm
}

/// An ephemeral P-256 key pair used for exactly one pairing attempt.
pub struct EcdhKeyPair {
    secret: EphemeralSecret,
    pub public: [u8; EC_POINT_LEN],
}

impl EcdhKeyPair {
    pub fn generate() -> Self {
        use p256::elliptic_curve::Generate;
        let secret = EphemeralSecret::try_generate_from_rng(&mut getrandom::SysRng)
            .expect("operating system random number generator failed");
        let encoded = secret.public_key().to_sec1_bytes();
        let public = encoded.as_ref().try_into().expect("uncompressed P-256 point is 65 bytes");
        EcdhKeyPair { secret, public }
    }

    /// ECDH with the peer's uncompressed public key. Returns `None` if the
    /// bytes are not a valid point on P-256, which also rules out
    /// invalid-curve and identity-point attacks.
    pub fn shared_secret(&self, peer: &[u8; EC_POINT_LEN]) -> Option<Key32> {
        if peer[0] != 0x04 {
            return None; // only the uncompressed encoding is allowed
        }
        let peer = PublicKey::from_sec1_bytes(peer).ok()?;
        let shared = self.secret.diffie_hellman(&peer);
        let mut out = Zeroizing::new([0u8; 32]);
        out.copy_from_slice(shared.raw_secret_bytes());
        Some(out)
    }
}

// ---------------------------------------------------------------- pairing

/// The PC's commitment to its nonce, sent before it sees the phone's nonce.
/// This is what stops a man-in-the-middle from searching for keys that make
/// the two 6-digit codes match.
pub fn commitment(pkb: &[u8; EC_POINT_LEN], pka: &[u8; EC_POINT_LEN], nb: &Nonce16) -> [u8; 32] {
    sha256(&[L_COMMIT, pkb, pka, nb])
}

/// The 6-digit number both screens show during pairing.
pub fn verification_code(pka: &[u8; EC_POINT_LEN], pkb: &[u8; EC_POINT_LEN], na: &Nonce16, nb: &Nonce16) -> u32 {
    let h = sha256(&[L_CODE, pka, pkb, na, nb]);
    u32::from_be_bytes([h[0], h[1], h[2], h[3]]) % 1_000_000
}

/// The long-term key shared by one phone and this PC.
pub fn pairing_key(
    shared_secret: &[u8; 32],
    na: &Nonce16,
    nb: &Nonce16,
    pair_id: &Id,
    pka: &[u8; EC_POINT_LEN],
    pkb: &[u8; EC_POINT_LEN],
) -> Key32 {
    let salt = [&na[..], &nb[..]].concat();
    hkdf32(&salt, shared_secret, &[L_PAIRING_KEY, pair_id, pka, pkb])
}

/// Proves the phone derived the same key (key confirmation).
#[cfg(test)]
pub fn phone_confirm_tag(key: &[u8; 32]) -> Tag {
    hmac_tag(key, &[L_CONFIRM_PHONE])
}

pub fn verify_phone_confirm(key: &[u8; 32], tag: &Tag) -> bool {
    verify_tag(key, &[L_CONFIRM_PHONE], tag)
}

/// Proves to the phone that this PC holds the key and binds the PC's id.
pub fn pc_confirm_tag(key: &[u8; 32], server_id: &Id) -> Tag {
    hmac_tag(key, &[L_CONFIRM_PC, server_id])
}

// ---------------------------------------------------------------- sessions

#[cfg(test)]
pub fn hello_tag(key: &[u8; 32], pair_id: &Id, nc: &Nonce16) -> Tag {
    hmac_tag(key, &[L_HELLO, pair_id, nc])
}

pub fn verify_hello(key: &[u8; 32], pair_id: &Id, nc: &Nonce16, tag: &Tag) -> bool {
    verify_tag(key, &[L_HELLO, pair_id, nc], tag)
}

pub fn welcome_tag(key: &[u8; 32], pair_id: &Id, nc: &Nonce16, ns: &Nonce16, session_id: u32) -> Tag {
    hmac_tag(key, &[L_WELCOME, pair_id, nc, ns, &session_id.to_le_bytes()])
}

/// A fresh key for one connection. Both sides contribute a random nonce, so
/// every session key is new even though the pairing key never changes, and
/// AES-GCM nonces (counters) can never repeat under the same key.
pub fn session_key(key: &[u8; 32], nc: &Nonce16, ns: &Nonce16, pair_id: &Id, session_id: u32) -> Key32 {
    let salt = [&nc[..], &ns[..]].concat();
    hkdf32(&salt, key, &[L_SESSION, pair_id, &session_id.to_le_bytes()])
}

#[cfg(test)]
pub mod tests {
    use super::*;

    pub fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    #[test]
    fn hkdf_matches_rfc5869_test_case_1() {
        let ikm = [0x0b; 22];
        let salt: Vec<u8> = (0x00..=0x0c).collect();
        let info: Vec<u8> = (0xf0..=0xf9).collect();
        let okm = hkdf32(&salt, &ikm, &[&info]);
        assert_eq!(hex(&okm[..]), "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf");
    }

    #[test]
    fn hmac_matches_rfc4231_test_case_2_truncated() {
        let tag = hmac_tag(b"Jefe", &[b"what do ya want ", b"for nothing?"]);
        assert_eq!(hex(&tag), "5bdcc146bf60754e6a042426089575c7");
        assert!(verify_tag(b"Jefe", &[b"what do ya want for nothing?"], &tag));
        let mut wrong = tag;
        wrong[15] ^= 1;
        assert!(!verify_tag(b"Jefe", &[b"what do ya want for nothing?"], &wrong));
    }

    #[test]
    fn ecdh_agrees_and_rejects_invalid_points() {
        let a = EcdhKeyPair::generate();
        let b = EcdhKeyPair::generate();
        assert_eq!(a.public[0], 0x04);
        assert_eq!(*a.shared_secret(&b.public).unwrap(), *b.shared_secret(&a.public).unwrap());

        let mut off_curve = b.public;
        off_curve[64] ^= 1;
        assert!(a.shared_secret(&off_curve).is_none());
        assert!(a.shared_secret(&[0u8; 65]).is_none());
        let mut compressed_tag = b.public;
        compressed_tag[0] = 0x02;
        assert!(a.shared_secret(&compressed_tag).is_none());
    }

    // Fixed-input vectors shared with the Android tests (CryptoTest.kt) and
    // docs/PROTOCOL.md. Inputs: pka = 04 || 11*64, pkb = 04 || 22*64,
    // na = 33*16, nb = 44*16, pair_id = 55*8, z = 66*32, server_id = 77*8,
    // nc = 88*16, ns = 99*16, session_id = 0x01020304.
    pub type VectorInputs = ([u8; 65], [u8; 65], Nonce16, Nonce16, Id, [u8; 32], Id, Nonce16, Nonce16, u32);

    pub fn vector_inputs() -> VectorInputs {
        let mut pka = [0x11; 65];
        pka[0] = 4;
        let mut pkb = [0x22; 65];
        pkb[0] = 4;
        (pka, pkb, [0x33; 16], [0x44; 16], [0x55; 8], [0x66; 32], [0x77; 8], [0x88; 16], [0x99; 16], 0x01020304)
    }

    #[test]
    fn derivations_match_shared_vectors() {
        let (pka, pkb, na, nb, pair_id, z, server_id, nc, ns, sid) = vector_inputs();
        let k = pairing_key(&z, &na, &nb, &pair_id, &pka, &pkb);
        let ks = session_key(&k, &nc, &ns, &pair_id, sid);
        let report = format!(
            "commit={}\ncode={}\nk={}\nphone_confirm={}\npc_confirm={}\nhello={}\nwelcome={}\nks={}",
            hex(&commitment(&pkb, &pka, &nb)),
            verification_code(&pka, &pkb, &na, &nb),
            hex(&k[..]),
            hex(&phone_confirm_tag(&k)),
            hex(&pc_confirm_tag(&k, &server_id)),
            hex(&hello_tag(&k, &pair_id, &nc)),
            hex(&welcome_tag(&k, &pair_id, &nc, &ns, sid)),
            hex(&ks[..]),
        );
        assert_eq!(report, include_str!("../../docs/test-vectors.txt").trim_end().split("\n--\n").next().unwrap());
    }
}
