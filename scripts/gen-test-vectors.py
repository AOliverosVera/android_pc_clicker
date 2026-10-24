"""Regenerates docs/test-vectors.txt with an implementation independent of the
Rust and Kotlin code (Python's hashlib/hmac plus the `cryptography` package).

    python -m pip install cryptography
    python scripts/gen-test-vectors.py

Only needed if the protocol's cryptography changes; both test suites then
check the new file. See docs/PROTOCOL.md#test-vectors.
"""
import pathlib
import hashlib, hmac, struct

OUT = pathlib.Path(__file__).resolve().parent.parent / "docs" / "test-vectors.txt"
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
sha = lambda *p: hashlib.sha256(b"".join(p)).digest()
def hkdf(salt, ikm, info, n=32):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest(); t=b""; okm=b""; i=1
    while len(okm) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); okm += t; i += 1
    return okm[:n]
tag = lambda k, *p: hmac.new(k, b"".join(p), hashlib.sha256).digest()[:16]
# self-check against RFC 5869 test case 1
assert hkdf(bytes(range(13)), b"\x0b"*22, bytes(range(0xf0,0xfa))).hex() == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
L = lambda s: b"touchpad/v2/" + s
pka = b"\x04" + b"\x11"*64; pkb = b"\x04" + b"\x22"*64
na, nb, pid, z, sid_ = b"\x33"*16, b"\x44"*16, b"\x55"*8, b"\x66"*32, b"\x77"*8
nc, ns, sess = b"\x88"*16, b"\x99"*16, 0x01020304
S = struct.pack("<I", sess)
k = hkdf(na+nb, z, L(b"pairing-key") + pid + pka + pkb)
ks = hkdf(nc+ns, k, L(b"session") + pid + S)
code = int.from_bytes(sha(L(b"code"), pka, pkb, na, nb)[:4], "big") % 1000000
part1 = "\n".join([
  "commit=" + sha(L(b"commit"), pkb, pka, nb).hex(),
  "code=%d" % code,
  "k=" + k.hex(),
  "phone_confirm=" + tag(k, L(b"confirm-phone")).hex(),
  "pc_confirm=" + tag(k, L(b"confirm-pc"), sid_).hex(),
  "hello=" + tag(k, L(b"hello"), pid, nc).hex(),
  "welcome=" + tag(k, L(b"welcome"), pid, nc, ns, S).hex(),
  "ks=" + ks.hex(),
])
def seal(key, session, counter, direction, pt):
    aad = b"TP\x02\x30" + struct.pack("<IQ", session, counter)
    nonce = bytes([direction,0,0,0]) + struct.pack("<Q", counter)
    return aad + AESGCM(key).encrypt(nonce, pt, aad)
part2 = "\n".join([
  "phone_move=" + seal(b"\xab"*32, sess, 1, 1, bytes([0x02,0x05,0x00,0xFD,0xFF])).hex(),
  "pc_ack=" + seal(b"\xab"*32, sess, 1, 2, bytes([0x81,0x2A,0,0,0])).hex(),
])
OUT.write_text(part1 + "\n--\n" + part2 + "\n", newline="\n")
print(part1 + "\n--\n" + part2)
