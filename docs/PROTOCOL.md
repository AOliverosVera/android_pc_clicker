# Wire protocol (v2)

The phone and the PC talk over **UDP port 47820** on the local network. All
multi-byte integers are **little-endian**. The reasoning behind the security
design is in [SECURITY.md](SECURITY.md).

Implementations:
- PC: [`protocol.rs`](../pc-server/src/protocol.rs), [`crypto.rs`](../pc-server/src/crypto.rs), [`channel.rs`](../pc-server/src/channel.rs)
- Phone: [`Protocol.kt`](../android/app/src/main/java/dev/playground/touchpad/net/Protocol.kt), [`Crypto.kt`](../android/app/src/main/java/dev/playground/touchpad/crypto/Crypto.kt), [`SecureChannel.kt`](../android/app/src/main/java/dev/playground/touchpad/crypto/SecureChannel.kt), [`Handshakes.kt`](../android/app/src/main/java/dev/playground/touchpad/net/Handshakes.kt)

## Header

| Offset | Size | Value |
|---|---|---|
| 0 | 2 | Magic `'T' 'P'` (`54 50`) |
| 2 | 1 | Protocol version, `02` |
| 3 | 1 | Packet type |

Receivers silently drop anything with the wrong magic or version, an unknown
type, a short payload, a name over its limit, or a name that isn't valid UTF-8.
Version 1 packets are ignored.

## Packets

Field sizes in bytes. `id` = 8 bytes, `nonce` = 16 random bytes, `tag` =
HMAC-SHA256 truncated to 16 bytes, `point` = uncompressed P-256 public key
(`04 ‖ X ‖ Y`, 65 bytes), `name` = length byte + UTF-8.

### Discovery (unauthenticated)

| Type | Name | Direction | Payload |
|---|---|---|---|
| `01` | DISCOVER | phone → broadcast | zeros, padded so the whole packet is **64 bytes** |
| `02` | ANNOUNCE | PC → phone | `server_id(8)`, `flags(1)` (bit 0 = pairing open), `name(≤40)` |

### Pairing

`pair_id` is chosen at random by the phone and becomes the phone's id on the PC.

| Type | Name | Direction | Payload |
|---|---|---|---|
| `10` | PAIR_START | phone → PC | `pair_id(8)`, `PKa(65)`, `phone_name(≤32)` |
| `11` | PAIR_COMMIT | PC → phone | `pair_id(8)`, `PKb(65)`, `commit(32)` |
| `12` | PAIR_NONCE | phone → PC | `pair_id(8)`, `Na(16)` |
| `13` | PAIR_REVEAL | PC → phone | `pair_id(8)`, `Nb(16)` |
| `14` | PAIR_CONFIRM | phone → PC | `pair_id(8)`, `tag(16)` = phone confirmation |
| `15` | PAIR_RESULT | PC → phone | `pair_id(8)`, `status(1)`, `server_id(8)`, `tag(16)` |

PAIR_RESULT `status`: `0` waiting for the person at the PC, `1` accepted
(then `server_id` and the PC confirmation `tag` are set), `2` rejected, `3`
pairing closed, `4` busy with another phone, `5` unknown attempt. Except for
`1`, `server_id` and `tag` are zero.

The flow:

```
phone                                             PC (pairing open)
  PAIR_START   pair_id, PKa, name        ──►
               ◄──  PAIR_COMMIT  PKb, SHA-256(L_commit ‖ PKb ‖ PKa ‖ Nb)
  PAIR_NONCE   Na                        ──►      computes code, asks operator
               ◄──  PAIR_REVEAL  Nb
  checks commitment, computes code, shows it; user taps "The codes match"
  PAIR_CONFIRM HMAC(K, L_confirm_phone)  ──►      operator types y
               ◄──  PAIR_RESULT  status 0 (waiting) ... status 1, server_id,
                                 HMAC(K, L_confirm_pc ‖ server_id)
```

The phone retransmits its current message every 400 ms until it gets the
matching answer; the PC answers retransmissions with the same reply. A
different `Na` for the same attempt, an invalid point, or a wrong confirmation
tag aborts the attempt (status 2) and closes pairing. Attempts time out after
2 minutes.

### Session

| Type | Name | Direction | Payload |
|---|---|---|---|
| `20` | HELLO | phone → PC | `pair_id(8)`, `Nc(16)`, `tag(16)` = HMAC(K, L_hello ‖ pair_id ‖ Nc), zero-padded to **64 bytes** |
| `21` | WELCOME | PC → phone | `pair_id(8)`, `Nc(16)`, `Ns(16)`, `session_id(4)`, `tag(16)` = HMAC(K, L_welcome ‖ pair_id ‖ Nc ‖ Ns ‖ session_id) |

The PC doesn't answer a HELLO from an unknown `pair_id` or with a wrong tag.
A retransmitted HELLO (same `Nc`) gets the same WELCOME. The phone accepts a
WELCOME only if the tag verifies and `Nc` is its own.

### Secure envelope

| Type | Name | Layout |
|---|---|---|
| `30` | SECURE | `session_id(4)`, `counter(8)`, `ciphertext`, `gcm_tag(16)` |

- Cipher: AES-256-GCM with the session key.
- Associated data: the first 16 bytes (header, `session_id`, `counter`).
- Nonce (12 bytes): `direction(1) ‖ 00 00 00 ‖ counter(8)`. Direction `01` is
  phone → PC and `02` is PC → phone.
- Counters start at 1 for each direction and increase by one per packet.
- Receivers accept each counter once, within a 64-packet window (RFC 4303
  style), and only after the GCM tag verifies.

Messages inside (the plaintext):

| Type | Name | Direction | Payload |
|---|---|---|---|
| `01` | HEARTBEAT | phone → PC | `seq u32`, `last_event u16`, `buttons u8` (bit 0 left, bit 1 right) |
| `02` | MOVE | phone → PC | `dx i16`, `dy i16` (mouse counts; +x right, +y down) |
| `03` | BUTTON | phone → PC | `event u16`, `button u8` (0 left, 1 right), `pressed u8` (1 down, 0 up) |
| `04` | BYE | phone → PC | none: the app is disconnecting |
| `05` | FORGET | phone → PC | none: unpaired on the phone, so the PC forgets it too |
| `06` | KEYS | phone → PC | `first_event u16`, `count u8` (1–8), then `count` × (`mods u8`, `kind u8`, `code u32`) |
| `07` | SCROLL | phone → PC | `dx i16`, `dy i16`: mouse wheel in Windows units (120 = one notch); `dy > 0` scrolls up, `dx > 0` scrolls right |
| `81` | HEARTBEAT_ACK | PC → phone | `seq u32` (echoed) |

KEYS fields:

- `mods`: bit 0 Shift, bit 1 Ctrl, bit 2 Alt, bit 3 Win; other bits must be 0.
- `kind 0`, text: `code` is a Unicode scalar value, never a control character.
  Without Ctrl/Alt/Win the PC types the character itself (`KEYEVENTF_UNICODE`),
  so it comes out the same whatever keyboard layout the PC uses. With them,
  it presses the key that types that character on the PC's current layout
  (so Ctrl+C is a real Ctrl+C).
- `kind 1`, special key: `code` 0 modifiers only (Win alone opens Start),
  1 Enter, 2 Backspace, 3 Tab, 4 Escape, 5 Delete, 6 Left, 7 Up, 8 Right,
  9 Down, 10 Home, 11 End, 12 Page Up, 13 Page Down, 14 Insert, `0x31`–`0x3C`
  F1–F12.

Each stroke is a complete tap: the PC presses the modifiers, then the key,
and releases them in reverse order in one `SendInput` call, so no key is ever
left held down. Anything unknown makes the whole message invalid. Accents
(´ ` ^ ¨ as dead keys) are combined on the phone, which sends the finished
letter.

Example: `06 02 01 02` + `00 00 E1 00 00 00` (á) + `03 01 31 00 00 00`
(Ctrl+Shift+F1) are events 0x0102 and 0x0103.

## Key derivation

`L_x` is the ASCII label `touchpad/v2/x`. HKDF is HKDF-SHA256; HMAC tags are
truncated to 16 bytes.

| Value | Definition |
|---|---|
| `commit` | `SHA-256(L_commit ‖ PKb ‖ PKa ‖ Nb)` |
| code | first 4 bytes of `SHA-256(L_code ‖ PKa ‖ PKb ‖ Na ‖ Nb)` as big-endian u32, mod 1 000 000 |
| `Z` | ECDH shared secret (x coordinate, 32 bytes) |
| `K` (pairing key) | `HKDF(salt = Na ‖ Nb, ikm = Z, info = L_pairing-key ‖ pair_id ‖ PKa ‖ PKb)`, 32 bytes |
| phone confirmation | `HMAC(K, L_confirm-phone)` |
| PC confirmation | `HMAC(K, L_confirm-pc ‖ server_id)` |
| session key | `HKDF(salt = Nc ‖ Ns, ikm = K, info = L_session ‖ pair_id ‖ session_id)`, 32 bytes |

## Behavior

**Moves and scrolls are best effort.** A lost MOVE loses a few pixels (a
lost SCROLL a little scrolling) and never stalls later packets, which is why
UDP is used. SCROLL carries amounts smaller than a notch, so apps that
support it scroll smoothly; the phone sends at most ±1200 per message.

**Clicks are reliable.** The phone sends every BUTTON three times (now, +20 ms,
+60 ms), each sealed as a new packet. The PC applies a BUTTON only if `event`
is newer than the last applied event (`(a − b) as i16 > 0`, so it wraps
correctly). A HEARTBEAT whose `last_event` is at least as new as the PC's
makes the PC adopt its `buttons` state, which recovers an event whose three
copies were all lost. An older heartbeat is ignored.

**Keys are typed once, in order.** Keys have their own event counter
(separate from buttons). Every KEYS message repeats the phone's keys from the
last second, oldest first, up to 8, and is itself sent three times like a
BUTTON. The PC types each stroke whose event id is newer than the last one it
typed, in the order listed, so a lost packet is made up by the next one and
copies are ignored. The phone drops keys typed while disconnected and starts
each session with an empty history, so nothing is replayed into a new
session. A PC without keyboard support ignores KEYS (an unknown message), so
mixing versions is harmless.

**Timers**

| Who | What | Value |
|---|---|---|
| Phone | discovery and HELLO retransmission | every 400 ms (DISCOVER every 1 s while unpaired) |
| Phone | heartbeat | every 1 s |
| Phone | connection considered lost | no authentic packet for 3.5 s, then a new HELLO |
| PC | held buttons released | no packet for 3 s |
| PC | session closed | no packet for 10 s |
| PC | pairing window | 5 minutes |
| PC | pairing attempt | 2 minutes |

**Limits on the PC:** one pairing attempt at a time, 16 pending sessions
(oldest evicted), 16 paired phones (oldest forgotten). Requests that trigger
replies are at least 64 bytes (PAIR_START at least 78), so replies are never
more than ~1.4× the request.

## Test vectors

[`test-vectors.txt`](test-vectors.txt) holds the expected outputs for fixed
inputs: commitment, code, pairing key, every tag, the session key, and two
sealed SECURE packets. It was generated by an independent Python
implementation; `crypto.rs`, `channel.rs`, `CryptoTest.kt` and
`SecureChannelTest.kt` all check against it. The inputs (each byte repeated):
`PKa = 04 ‖ 11…`, `PKb = 04 ‖ 22…`, `Na = 33…`, `Nb = 44…`, `pair_id = 55…`,
`Z = 66…`, `server_id = 77…`, `Nc = 88…`, `Ns = 99…`, `session_id = 0x01020304`;
the sealed packets use key `AB…`, counter 1.

## Versioning

A breaking change bumps the version byte. Each side drops other versions, so
mismatched builds simply don't find each other. Rebuild both together.
