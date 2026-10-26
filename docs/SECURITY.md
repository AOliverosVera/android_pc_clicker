# Security

Touchpad lets a phone control a PC's mouse, so a flaw here can mean "someone
else clicks on my computer". This document records the threat model, the
security review of the project, how each finding was fixed, and what risk
remains.

## Threat model

**What is protected**
- Control of the PC's pointer and clicks (the main asset: clicks can open,
  approve or run things).
- The pairing keys on both devices.
- The integrity of the software you build and install.

**Who we defend against**
- **Anyone on the same network**, passive or active: they can read every
  packet, inject packets with forged source addresses, replay, drop, delay and
  reorder traffic, answer broadcasts, and run their own copy of the app or
  server.
- **Another user account on the same PC**, or someone who copies the pairing
  file off it.
- **Supply-chain substitution** of a build tool or library.

**Out of scope**
- An attacker who already controls the PC's user account or the phone's OS
  (malware, root). They can move the mouse without us.
- Someone holding the unlocked phone: the app is a mouse by design.
- Jamming or flooding the Wi-Fi. Anyone on the network can make it unusable;
  we only make sure that can't lead to wrong input.

## Review findings and fixes

The first version (protocol v1) was reviewed on 2026-10-23. Every finding is
fixed in protocol v2.

| # | Severity | Finding in v1 | Fix in v2 |
|---|---|---|---|
| C1 | Critical | No authentication: any device on the network could move the pointer and click. | Pairing with code confirmation on both screens; input is accepted only inside a session keyed by the pairing ([Pairing](#pairing), [Sessions](#sessions)). |
| H1 | High | No integrity or replay protection: source IPs are trivially forged and captured packets could be replayed. | Every input packet is AES-256-GCM authenticated and checked against a 64-packet replay window before it is parsed ([Transport](#transport)). |
| H2 | High | The phone trusted any announcement, so a rogue device could pose as the PC. | The PC proves it holds the pairing key (pairing confirmation tag, then the WELCOME tag on every connection). Announcements only say where to look. |
| M1 | Medium | Any sender created server state, unbounded (memory exhaustion via forged sources). | No state for unauthenticated senders; one pairing at a time; at most 16 pending sessions and 16 paired phones. |
| M2 | Medium | The release APK was signed with the shared debug key. | A private RSA-3072 key, created on first build in `%USERPROFILE%\.touchpad` with owner-only permissions, never in the repo. |
| M3 | Medium | The firewall rule allowed any program on UDP 47820 from any address. | The rule is limited to `dist\touchpad-server.exe`, the local subnet, and Private networks. |
| M4 | Medium | Supply chain: unpinned Gradle download, no dependency verification, unscoped repositories. | Wrapper JAR verified against Gradle's official checksum; distribution pinned by SHA-256; all 328 Android artifacts pinned in `verification-metadata.xml`; Google's repository limited to Google groups. |
| L1 | Low | Plaintext traffic revealed movement and clicks. | Encrypted with AES-256-GCM. |
| L2 | Low | A 4-byte discovery request triggered a ~70-byte reply (reflection amplifier). | Requests that trigger replies are padded to 64 bytes; replies are never more than ~1.4× the request. |
| L3 | Low | Two Android permissions weren't needed. | Removed `ACCESS_WIFI_STATE` and `CHANGE_NETWORK_STATE`. |
| L4 | Low | Docs suggested running the server as administrator without the trade-off. | The server warns when elevated; docs explain the risk. |
| — | Hardening | Names from the network were printed to the console. | Control characters and bidirectional overrides are replaced, so a phone name can't inject terminal escape sequences or disguise itself. |

Already sound in v1 and kept: the connection service isn't exported,
PendingIntents are explicit and immutable, app backups are disabled, packet
parsing is bounds-checked, the server ignores traffic from outside private
address ranges, `unsafe` Rust is limited to the two Win32 calls, and no
secrets were ever in the repository.

### Follow-up review (2026-10-25)

This review covered everything added after protocol v2: the keyboard,
scrolling, tap-to-click, the tray app with its autostart, and click-and-hold
dragging.

| Area | Checked | Result |
|---|---|---|
| Dependencies | `cargo audit` flagged `crypto-bigint 0.7.6` (under the P-256 code) as **yanked** by its authors. | Updated to `0.7.9`, the only non-yanked 0.7 release; all crypto tests and the shared vectors still pass. 0 known vulnerabilities. |
| Keyboard | Parsing of KEYS messages; how keys are injected. | Strict: 1–8 strokes, unknown modifier bits, control characters and unknown key codes reject the whole message. Modifiers go down and up inside one `SendInput` call, so none can stay stuck and no other input lands in between. Only accepted inside an authenticated session. The extra power this gives a paired phone is listed under [Remaining risks](#remaining-risks). |
| Scrolling | Parsing and injection. | Bounded `i16` values, best effort like moves, inside the session. |
| Tray app | The pairing dialog, the launcher, "Start with Windows". | The dialog shows the sanitized phone name and the code, **No** is the default button, and the answer is bound to that pairing attempt (a stale dialog is closed as No). The launcher starts the server by absolute path, not via `PATH`. Autostart writes a quoted path under the current user's `Run` key only. |
| Dragging | New gestures (tap-and-drag, drag lock, three-finger drag). | No protocol change: uses the existing authenticated, de-duplicated BUTTON messages. The phone-side state machine is tested so that every press is matched by a release, and leaving the touchpad (rotating, closing) always releases. The PC still releases held buttons after 3 s of silence, so a held drag can never outlive the connection. |

## Design

All cryptography comes from vetted libraries: RustCrypto on the PC
(`p256`, `aes-gcm`, `hkdf`, `hmac`, `sha2`) and the platform's `java.security`
/ `javax.crypto` on Android. Nothing is hand-rolled except the choice of
inputs. Every derivation starts with its own `touchpad/v2/...` label, so no
key or tag can be reused for a different purpose. Byte-level details are in
[PROTOCOL.md](PROTOCOL.md).

### Pairing

Pairing happens once per phone. It is ECDH on P-256 with **numeric
comparison**, the same idea as Bluetooth Secure Simple Pairing:

1. The phone sends a fresh public key `PKa`.
2. The PC sends its fresh public key `PKb` and a **commitment**
   `SHA-256(PKb, PKa, Nb)` to a random nonce `Nb` it keeps secret.
3. The phone sends its random nonce `Na`.
4. The PC reveals `Nb`; the phone checks it against the commitment.
5. Both compute a 6-digit code from `PKa, PKb, Na, Nb` and show it. **The
   person compares the two screens** and confirms on both.
6. Both derive the pairing key `K` with HKDF from the ECDH secret and the
   nonces, and prove it to each other with confirmation tags. Only then is `K`
   stored.

A man in the middle has to complete a separate exchange with each side. The
commitment forces the PC side's contribution to be fixed before the phone's
nonce is known, so the attacker can't search for keys that make both codes
equal. Each attempt succeeds with probability one in a million, and a failed
attempt is visible as different codes. That is why the screens tell you to
accept **only** when the codes match.

Other details:
- The PC accepts pairing only while pairing is open: for 5 minutes on first
  run, with `--pair`, or after typing `pair`. One attempt at a time.
- The operator's answer is bound to that specific attempt, so it can't apply
  to another phone that showed up in the meantime.
- A rejection, a bad confirmation or an inconsistent message aborts the
  attempt and closes pairing.
- Peer public keys are checked to be valid points on P-256, which blocks
  invalid-curve attacks.

### Sessions

Each connection starts a new session:
- **HELLO** (phone): a random `Nc` and an HMAC under `K`. A PC that doesn't
  know the phone, or sees a bad tag, **doesn't answer at all**.
- **WELCOME** (PC): a random `Ns`, a session id, and an HMAC under `K`. This
  is how the phone knows it's talking to its PC.
- Session key = HKDF(`K`, `Nc`, `Ns`, ids). Both sides contribute randomness,
  so every session key is new.

A session only becomes active with its first authentic encrypted packet, and
it then replaces the phone's previous session (whose held buttons are
released). A replayed HELLO therefore can't disturb a working connection.

### Transport

After the handshake everything is AES-256-GCM:
- The header, session id and counter are authenticated as associated data.
- The nonce is `direction ‖ counter`. Because there is a fresh key per session
  and a counter per direction, a nonce is never reused under a key.
- The receiver keeps an IPsec-style 64-packet window: each counter is
  accepted once and up to 63 packets of reordering are tolerated. Only
  authentic packets move the window.
- Clicks are sent three times (re-encrypted each time). The PC applies each
  click event once, and heartbeats re-sync the button state if all copies
  were lost.

### Resource limits

Nothing an unauthenticated sender does creates lasting state on the PC:
discovery is stateless, and a HELLO without a valid tag is dropped. What
remains is bounded: one pairing attempt (2-minute timeout), 16 pending
sessions (oldest evicted), 16 paired phones (oldest forgotten), and the
phone's receive queue holds 64 packets, dropping the oldest.

## Keys at rest

| Where | What | Protection |
|---|---|---|
| PC | Pairing keys, `%APPDATA%\TouchpadServer\pairings.dat` | Encrypted with Windows **DPAPI** under your account (other accounts and other PCs can't decrypt it); written atomically; Windows' plaintext copy is wiped after use; keys are zeroed in memory when dropped. |
| Phone | Pairing key | Encrypted with an AES-256-GCM key held in the **Android Keystore** (hardware-backed on most phones, never extractable), bound to the pairing record; backups and device transfer disabled. |
| Build PC | APK signing key, `%USERPROFILE%\.touchpad` | Outside the repository, folder readable only by your account; `.gitignore` also blocks key files. **Back it up**: an APK signed with another key can't update the installed app. |

## Revoking access

- **On the phone:** tap **Unpair**. The phone tells the PC over the
  authenticated session, and the PC forgets it too.
- **On the PC:** type `unpair <ID>` in the server window, or run
  `touchpad-server --unpair <ID>` (IDs from `--list`). Use `all` to forget
  every phone.
- **Lost phone:** unpair it on the PC. Its key stops working immediately.

## Supply chain

- **Rust:** `Cargo.lock` pins every crate. `cargo audit` checks them against
  the RustSec advisory database (0 known vulnerabilities at the last check).
- **Gradle:** `gradle-wrapper.jar` matches Gradle's official checksum, and
  `distributionSha256Sum` pins the Gradle download.
- **Android libraries:** `gradle/verification-metadata.xml` pins the SHA-256
  of every artifact; the build fails if any changes (tested by corrupting one
  checksum). Repository content filters stop look-alike packages from other
  repositories (dependency confusion).
- When updating dependencies, see
  [BUILDING.md](BUILDING.md#updating-dependencies-safely).

## Using it safely

- Accept a pairing **only** when you started it yourself and the codes are
  identical.
- Keep the server on your home network. The firewall rule only opens the port
  on networks Windows marks as Private.
- Run the server as a normal user. As administrator it can also control
  elevated windows, which is more power than it usually needs.
- Back up `%USERPROFILE%\.touchpad`.

## Verification

| Check | Result |
|---|---|
| Server unit tests (`cargo test`) | 63 pass: pairing (success, rejection, wrong key, conflicting data, invalid point, timeout, busy, closed, decision binding), sessions (forged, unknown, retransmitted, bounded), transport (replay, every single-bit flip, wrong key/session, reflection), revocation, timeouts, name sanitizing, DPAPI store (opaque on disk, tamper detection) |
| App unit tests (`gradlew test`) | 84 pass: the same protocol layouts, crypto and transport properties, plus input logic (every drag press matched by a release, cancel always releases) |
| Independent test vectors | `docs/test-vectors.txt` was computed with Python's `hashlib`/`hmac` and the `cryptography` package; both the Rust and the Kotlin implementations must reproduce it byte for byte |
| End-to-end (`scripts/interop-test.ps1`) | The app's protocol code pairs with the real server binary over UDP, both sides compute the same code, then: forged HELLO ignored, replayed packet ignored, input delivered, unpair from the phone honored |
| `cargo clippy`, Android Lint | 0 warnings, 0 issues |
| `cargo audit` | 0 vulnerabilities |

## Remaining risks

These are accepted, with the reason:

- **Discovery is public.** Anyone on the network can learn that a PC runs the
  server, its computer name, and whether pairing is open; during pairing, the
  phone's model name. That's similar to what Windows network discovery already
  shares.
- **Traffic analysis.** Encryption hides content, not timing or packet sizes,
  so an observer can tell when the touchpad is in use.
- **No forward secrecy for sessions.** Session keys come from the long-term
  pairing key plus nonces sent in the clear. Someone who records traffic and
  *later* steals a pairing key (from the phone or the PC) could decrypt those
  recordings. With the keyboard this includes everything typed, passwords
  too, so it matters more than it did for pointer movement alone. A
  per-session ECDH in HELLO/WELCOME would remove it.
- **Keystroke timing.** Each key is its own packet, so an observer on the
  network sees when keys are pressed (not which). Timing alone has been used
  to guess typed text in research settings.
- **A paired phone can type.** Keyboard input is far more powerful than a
  pointer (Win+R and a command). It is accepted only from paired phones
  through the authenticated session, like the pointer; unpair any phone you
  no longer control. Windows doesn't let injected input press Ctrl+Alt+Del or
  type into elevated windows unless the server is elevated.
- **The code check depends on the person.** Accepting different codes defeats
  the man-in-the-middle protection; the screens say so explicitly.
- **Denial of service on the LAN** (flooding, Wi-Fi jamming) can interrupt the
  touchpad but can't produce input.
