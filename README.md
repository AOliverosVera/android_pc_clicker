# Touchpad

An Android app that turns a phone into a low-latency wireless touchpad for a
Windows PC, paired with a small Rust server that injects the input. The phone
and PC talk directly over UDP on the local network, with no cloud service, no
account, and no third-party runtime. Every input packet is authenticated and
encrypted.

> **Just want to use it?** Follow [instructive.md](instructive.md), a
> step-by-step guide for any user. This README is the technical overview.

## Features

- **Fast discovery and reconnection.** A broadcast probe-and-reply finds the
  PC in one round trip. Once paired, the phone sends its session handshake
  straight to the last known address, so reconnecting doesn't wait for
  discovery.
- **Responsive pointer.** Movement goes over UDP, so a lost packet costs a few
  pixels instead of stalling the stream the way TCP head-of-line blocking
  would. A smoothstep acceleration curve on the phone keeps slow motion
  precise and lets fast flicks cross the screen.
- **Reliable clicks on a lossy link.** Each button event is sent three times,
  spread over 60 ms. The PC applies it once (wrapping 16-bit event ids), and
  heartbeats carry the authoritative button state to recover an event whose
  copies were all lost.
- **Tap to click.** A quick one-finger tap is a left click, a two-finger tap a
  right click; any slide or a longer touch only moves the pointer.
- **Click and hold without a button**, the three ways desktop touchpads do it:
  - *Tap-and-drag* (Windows' "tap twice and drag", libinput, macOS; on by
    default): tap, then touch again and move. As in libinput, the tap presses
    the button at once but releases it 200 ms later, so the second touch
    continues one press instead of becoming a double-click (which would open
    the icon or maximize the window you meant to drag). Two quick taps still
    give a normal double-click, three a triple-click.
  - *Drag lock* (off by default): lifting mid-drag keeps the button held,
    either for 600 ms or until the next tap, so long drags survive
    repositioning the finger.
  - *Three-finger drag* (macOS; off by default): three fingers moving together
    drag without a tap.
  - Or hold the on-screen **Left** button and move with another finger. Both
    sources are arbitrated, so the PC sees exactly one press and one release.
  The logic is a pure state machine (`TapDrag`) with explicit time; leaving
  the touchpad mid-drag always releases the button.
- **Scrolling like a laptop.** Two fingers scroll in any direction; a 48 dp
  strip on the right edge (about libinput's 7 mm) scrolls with one finger, and
  turns back into pointer movement if the finger slides sideways first.
  Scroll amounts are finer than one wheel notch, so supporting apps scroll
  smoothly.
- **Settings.** Dark or light theme, pointer speed and acceleration, scroll
  speed, scroll direction per gesture (two-finger defaults to "down
  scrolls up" like Windows precision touchpads, the edge to "down scrolls
  down" like a scroll bar), and the dragging options above.
- **Keyboard in landscape.** Turn the phone sideways for a Spanish (Spain)
  keyboard: ñ, ç, dead-key accents, AltGr symbols, Ctrl/Alt/Win shortcuts,
  arrows and F1–F12. Text arrives as characters, so it is right whatever the
  PC's layout; every key packet repeats the recent keys, so none are lost or
  doubled, and modifiers are released within the same message.
- **Stays connected in the background.** A typed (`connectedDevice`)
  foreground service with a low-latency Wi-Fi lock. Swiping the app away or
  tapping Disconnect sends a goodbye that releases any held button.
- **Fails safe.** Held buttons are released after 3 s of silence, so a dropped
  connection can't leave the PC mid-drag.

## Security

Designed against an active attacker on the same network: someone who can
read, forge, replay, drop and reorder packets. The full threat model, the
review that shaped the design, and the residual risks are in
[docs/SECURITY.md](docs/SECURITY.md).

| Layer | Mechanism |
|---|---|
| Pairing (once per phone) | Ephemeral ECDH P-256 with numeric comparison and a hash commitment (as in Bluetooth SSP). Both screens show a 6-digit code; a man in the middle succeeds with probability 10⁻⁶ per visible attempt. Mutual key confirmation before anything is stored. |
| Session (every connection) | HELLO/WELCOME authenticated with HMAC-SHA256 under the pairing key; unpaired or forged requests get no reply. The session key comes from HKDF-SHA256 over nonces from both sides. |
| Transport | AES-256-GCM. Header, session id and counter are associated data; the nonce is direction ‖ counter; a 64-packet anti-replay window (RFC 4303 style) is updated only by authentic packets. |
| Keys at rest | Windows DPAPI (current user) on the PC; an Android Keystore AES-GCM key wraps the pairing key on the phone; backups disabled. |
| Exposure | No state for unauthenticated senders; bounded pairing and session tables; replies never larger than ~1.4× the request; the firewall rule is scoped to the executable, the local subnet and Private networks. |
| Supply chain | `Cargo.lock` plus `cargo audit`; the Gradle distribution and wrapper are checksum-pinned; SHA-256 dependency verification for all 328 Android artifacts; repository content filtering. |

All cryptography comes from RustCrypto (PC) and the platform's
`java.security`/`javax.crypto` (Android). Every derivation is domain-separated
with its own `touchpad/v2/…` label.

## Architecture

```
 Android (Kotlin, Compose)                                 Windows (Rust)
┌──────────────────────────────────────┐   UDP 47820   ┌──────────────────────────────┐
│ UI ─► TouchpadClient (single actor)  │◄─────────────►│ Server (pure state machine)  │
│        ├─ PairingAttempt / Handshake │               │  ├─ pairing  ─ ECDH, code    │
│        ├─ SecureChannel (AES-GCM)    │               │  ├─ sessions ─ Channel (GCM) │
│        └─ PairingStore (Keystore)    │               │  ├─ Store    ─ DPAPI file    │
│ ConnectionService (foreground)       │               │  └─ InputSink ─ SendInput()  │
└──────────────────────────────────────┘               └──────────────────────────────┘
```

- **Phone.** All protocol state belongs to one actor coroutine. UI events go
  through an unbounded channel (rate-limited by touch input); datagrams go
  through a 64-entry drop-oldest channel, so a flood can't grow memory. The
  handshake and crypto classes are free of Android APIs and run unchanged in
  JVM tests.
- **PC.** A single-threaded event loop (100 ms socket timeout) plus a console
  reader thread. `Server` is pure logic behind an `InputSink` trait; the tests
  drive it with a simulated phone that speaks the real protocol.

Details are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the wire format,
byte by byte, is in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Tech stack

| Component | Stack |
|---|---|
| Android app | Kotlin 2.4, Jetpack Compose (BOM 2026.09), AGP 9.4, Gradle 9.8, compileSdk 37.2, minSdk 26 (Android 8.0) |
| PC server | Rust 2024 edition, `windows-sys` (Win32 input, DPAPI), RustCrypto `p256` / `aes-gcm` / `hkdf` / `hmac` / `sha2`, `zeroize` |
| Tooling | PowerShell build, install and test scripts; Android Lint; Clippy; cargo-audit |

## Repository layout

```
.
├── android/                 Android app (Gradle project)
│   ├── app/src/main/…       crypto/, net/ (protocol, client, store), ui/, input/
│   ├── app/src/test/…       JVM unit tests and the interop test
│   └── gradle/              version catalog, dependency verification metadata, wrapper
├── pc-server/src/           protocol.rs, crypto.rs, channel.rs, server.rs, store.rs, input.rs, main.rs
├── scripts/                 build, install, firewall, autostart, interop test, test-vector generator
├── docs/                    SECURITY, ARCHITECTURE, PROTOCOL, BUILDING, TROUBLESHOOTING, test vectors
└── instructive.md           step-by-step setup and usage guide
```

## Building from source

Requirements: Windows, Rust (MSVC toolchain), JDK 17–21, Android SDK
(platform 37.2, build-tools 36.1). Exact versions and how to install them
without Android Studio are in [docs/BUILDING.md](docs/BUILDING.md).

```powershell
.\scripts\build.ps1            # tests, lint, then dist\touchpad-server.exe and dist\Touchpad.apk
.\scripts\allow-firewall.ps1   # scoped inbound rule (needs administrator)
.\scripts\install-apk.ps1      # install over adb
```

The first Android build creates a private RSA-3072 signing key in
`%USERPROFILE%\.touchpad`, outside the repository. Release builds refuse to
use the shared debug key.

## Testing and verification

| What | How |
|---|---|
| Server: 63 unit tests | `cargo test`: protocol, crypto against RFC 5869/4231 and shared vectors, the AES-GCM channel (replay, every single-bit flip, reflection), the pairing state machine, sessions, input semantics, keyboard ordering and Win32 key sequences, DPAPI store |
| App: 84 JVM tests | `gradlew test`: the same layouts, vectors and channel properties, the Spanish keyboard (modifiers, Caps Lock, AltGr, accents), tap detection, gestures (two-finger and edge scrolling, three-finger drag), the tap-and-drag / drag-lock state machine, button arbitration, and pointer settings |
| Cross-implementation vectors | [`docs/test-vectors.txt`](docs/test-vectors.txt), generated by an independent Python implementation ([`scripts/gen-test-vectors.py`](scripts/gen-test-vectors.py)); Rust and Kotlin must match byte for byte |
| End to end | [`scripts/interop-test.ps1`](scripts/interop-test.ps1): the app's protocol code pairs with the real server binary over UDP; the harness confirms the pairing only if both sides computed the same code, then checks forged handshakes, replay, input and unpairing |
| Static analysis | `cargo clippy` (0 warnings), Android Lint (no issues), `cargo audit` (0 advisories) |

## Limitations

- The server is Windows-only; it compiles elsewhere but only logs input.
- IPv4 only, since discovery uses IPv4 broadcast.
- Windows blocks injected input into elevated windows unless the server runs
  elevated, which it warns against.
- One PC per phone (unpair to switch); up to 16 phones per PC.
- Session keys have no forward secrecy with respect to the long-term pairing
  key. This is documented and judged acceptable for pointer data.

## Documentation

| Document | For |
|---|---|
| [instructive.md](instructive.md) | Anyone: setup and everyday use, step by step |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, review findings, key management, residual risks |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, connection lifecycle, threading, design choices |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Wire format, key derivation, timers, test vectors |
| [docs/BUILDING.md](docs/BUILDING.md) | Toolchain, building, signing, testing, updating dependencies safely |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Discovery, pairing, latency, installation problems |
