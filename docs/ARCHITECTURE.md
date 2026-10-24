# Architecture

Two programs talk over UDP on the local network:

```
 Android phone                                          Windows PC
┌───────────────────────────────────────┐            ┌────────────────────────────────┐
│ MainActivity → AppRoot (Compose)      │            │ touchpad-server.exe (Rust)     │
│  ├─ PairScreen / PairingScreen        │            │                                │
│  └─ TouchpadScreen ─ PointerAccelerator│           │  UdpSocket :47820              │
│        │ move() / setButton() / pair()│            │      │                         │
│        ▼                              │   UDP      │      ▼                         │
│ TouchpadClient (actor)                │◄──────────►│  Server                        │
│  ├─ PairingAttempt / SessionHandshake │  :47820    │   ├─ pairing (ECDH + code)     │
│  ├─ SecureChannel (AES-256-GCM)       │            │   ├─ sessions → Channel (GCM)  │
│  └─ PairingStore (Android Keystore)   │            │   ├─ Store (DPAPI file)        │
│ ConnectionService (foreground)        │            │   └─ SystemInput → SendInput() │
│  ├─ notification "Connected to PC"    │            │  console: y / n / pair / list  │
│  └─ Wi-Fi low-latency lock            │            └────────────────────────────────┘
└───────────────────────────────────────┘
```

## Technology choices

**Android app: Kotlin + Jetpack Compose.** Native Android gives direct access
to what this app depends on: raw UDP sockets, broadcast, a typed foreground
service, Wi-Fi locks, and the hardware-backed Keystore, with no bridge between
the touch handler and the network code. Cryptography uses the platform's own
`java.security` / `javax.crypto`, so the app has no crypto dependencies.

**PC server: Rust.** One ~450 KB `.exe` with no runtime to install, memory
safety for code that parses network input, and direct calls to Win32
`SendInput` and DPAPI. Cryptography comes from the RustCrypto crates.

**Transport: UDP.** Pointer movement is a stream where only fresh data
matters. With TCP, one lost segment stalls every later packet until it's
retransmitted, which shows up as the pointer freezing and then jumping. With
UDP a lost move is a few missing pixels; clicks get their own reliability on
top.

**Discovery: broadcast probe and reply, plus memory.** The phone broadcasts
DISCOVER and the PC answers right away, so finding it takes one round trip.
Once paired, the phone also sends HELLO straight to the PC's last known
address, so reconnecting doesn't even wait for discovery.

## Security model in one paragraph

A phone must **pair** once: an ECDH key exchange where both screens show a
6-digit code and you confirm they match, which defeats man-in-the-middle
attacks. Every connection then runs a short **session handshake** that proves
both sides hold the pairing key and derives a fresh session key. All input
travels **encrypted and authenticated** (AES-256-GCM) with replay protection.
Keys are stored with DPAPI on the PC and in the Android Keystore on the phone.
The full threat model, review findings and remaining risks are in
[SECURITY.md](SECURITY.md); the byte layouts are in [PROTOCOL.md](PROTOCOL.md).

## How a connection works

**First time (pairing):**
1. The server opens pairing (on first run, with `--pair`, or after typing
   `pair`).
2. The app lists the PCs that answer DISCOVER. You tap **Pair**.
3. Key exchange; both screens show the same code.
4. You tap **The codes match** on the phone and type **y** on the PC.
5. Both store the pairing key. Pairing closes on the PC.

**Every time after that:**
1. `MainActivity.onStart` starts `ConnectionService`, which starts the client.
2. The client sends HELLO to the remembered address (and broadcasts DISCOVER
   in case the PC's address changed).
3. The PC answers WELCOME; the session is up in one round trip.
4. Heartbeats every second keep it alive; 3.5 s of silence starts a new
   handshake.
5. Touch input becomes encrypted MOVE and BUTTON messages.

## Background behavior

Android stops the network for apps in the background, so the connection lives
in a **foreground service** (type `connectedDevice`, required on Android 14+).
It shows an ongoing "Connected to <PC>" notification while it runs, and holds a
**Wi-Fi low-latency lock**, since Wi-Fi power saving otherwise adds 100 ms or
more of lag.

The connection ends when the app is swiped away from Recents, or when you tap
**Disconnect** on the notification. Both send BYE, so the PC releases any held
button immediately.

With the screen off, Android may let the CPU sleep and heartbeats pause. The
PC releases held buttons after 3 s, and the phone reconnects in one round trip
when the screen comes back. The app holds no CPU wake lock, which would drain
the battery.

## Threading

**Phone.** `TouchpadClient` is an *actor*: one coroutine owns all protocol
state (pairing, session, counters), so there are no data races.
- The UI and the socket reader only post messages to it.
- User actions go to an unbounded channel, since they're limited by touch rate.
- Received datagrams go to a 64-entry channel that drops the oldest when full,
  so a packet flood can't grow memory.
- A ticker drives retransmissions and heartbeats.
- Sub-count movement is accumulated on the UI thread, so slow, precise finger
  movement isn't rounded away.

**PC.** One main thread runs the socket with a 100 ms timeout, so the same
loop handles packets, timeouts and console commands. A second thread only
reads console lines and forwards them. `Server` is pure logic behind an
`InputSink` trait, which lets the tests drive the real protocol against a fake
mouse.

## Pointer feel

`PointerAccelerator` maps finger speed (dp/ms, smoothed) to a gain between
1.4× and 4.0× with a smoothstep curve: slow movement is precise, fast flicks
cross the screen. Windows then applies its own pointer speed and **Enhance
pointer precision**, as for any mouse. Adjust the Windows mouse speed first,
then the constants in `PointerAccelerator.kt`.

Release builds are noticeably smoother than debug builds with Compose, which
is why `scripts/build.ps1` produces a release APK.

## Known limits

- **Windows only** on the PC side. The server compiles elsewhere but only logs
  input instead of moving the pointer.
- **Elevated windows.** Windows blocks injected input to programs running as
  administrator (UIPI) unless the server also runs elevated, which the server
  warns about.
- **One PC per phone.** Unpair to switch PCs. A PC can have up to 16 phones.
- **IPv4 only**, since discovery relies on IPv4 broadcast.
- Guest networks and routers with "AP isolation" stop devices from seeing each
  other; no LAN app can connect there.
