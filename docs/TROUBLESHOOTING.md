# Troubleshooting

## The app doesn't list my PC

Check these in order:

1. **Is the server running?** Its window should say
   `Listening on 192.168.x.x:47820 (UDP).`
2. **Same network?** The phone must be on the same Wi-Fi as the PC (or the PC
   on Ethernet on the same router).
3. **Firewall.** Run `scripts\allow-firewall.ps1`. It also removes the Block
   rule Windows creates if its "allow access?" prompt was ever cancelled. The
   rule covers `dist\touchpad-server.exe` only: run the server from `dist`,
   and run the script again if you moved the folder.
4. **Network marked Public?** The rule only applies to Private networks.
   Windows Settings → Network & internet → Wi-Fi (or Ethernet) → your network
   → **Private network**.
5. **VPN** on the phone or the PC usually routes local traffic away. Pause it.
6. **Guest network / AP isolation.** Many routers stop devices on guest
   networks (and some on main networks) from seeing each other. Use the main
   network, or turn off "AP isolation" / "client isolation" in the router.

## Pairing

**"Not accepting phones"** next to the PC, or "The PC isn't accepting new
phones": pairing is closed. Type `pair` in the server window (or restart it
with `--pair`); it stays open for 5 minutes.

**The codes are different.** Tap **Cancel** on the phone and type `n` on the
PC. Different codes mean the two devices didn't talk to each other directly.
That can be a glitch, but it's also exactly what interference by another
device on the network looks like. Try again; if it keeps happening, pair on
another network.

**"The PC is pairing with another phone."** Only one phone can pair at a time.
If you didn't start that other attempt, type `n` in the server window.

**A pairing request appears that you didn't start.** Type `n`. Someone on the
network tried to pair while pairing was open; without your `y` they get
nothing, and pairing closes.

**"Pairing took too long."** Each attempt has 2 minutes from tapping Pair to
both confirmations. Start again.

## Paired, but it stays on "Looking for <PC>…"

- Everything in the first section (server running, same network, firewall).
- **The PC forgot this phone**, because someone ran `unpair` or `--unpair`, or
  `%APPDATA%\TouchpadServer` was deleted. The PC then ignores the phone
  completely. Tap **Unpair** on the phone and pair again.
- **The server says "Cannot read … pairings.dat"**: the file belongs to
  another Windows user or is damaged. Delete it as the server suggests and
  pair again.

## The pointer doesn't move in some windows

Windows blocks injected input to programs running as administrator (Task
Manager, installers, admin terminals). Running `touchpad-server.exe` as
administrator lifts that, but it also gives anyone with your paired phone that
level of control. The server warns when it runs elevated.

## The pointer is too fast or too slow

Change **Windows Settings → Bluetooth & devices → Mouse → Mouse pointer speed**
first; it applies to the touchpad like to any mouse. To change the feel of the
acceleration itself, edit the constants in
`android/app/src/main/java/dev/playground/touchpad/input/PointerAccelerator.kt`
and rebuild.

## It feels laggy or jittery

- Use the **release** APK (`scripts\build.ps1` builds it). Debug builds of
  Compose apps are much slower.
- Prefer **5 GHz** Wi-Fi over 2.4 GHz, and keep the PC on Ethernet if you can.
- The ping in the status line should be a few milliseconds. Tens of
  milliseconds or more means a congested or distant Wi-Fi link.
- Battery savers on some phones (Xiaomi, Huawei, Samsung "Deep sleep") throttle
  apps despite the foreground service. Exclude Touchpad from battery
  optimization: long-press the app icon → App info → Battery → Unrestricted.

## A button seems stuck

The PC releases held buttons 3 s after it stops hearing from the phone, and on
every heartbeat it re-syncs with the phone's real button state. If something
still looks stuck, tap the button once. Closing the app (swipe it away)
always releases everything.

## Installing the app fails with "signatures do not match" / INSTALL_FAILED_UPDATE_INCOMPATIBLE

The installed app was signed with a different key (an older build, or a build
from another PC). Uninstall Touchpad from the phone, then install again; you
will need to pair again. To avoid this, keep and back up
`%USERPROFILE%\.touchpad`.

## "Could not listen on UDP port 47820"

Another copy of the server is already running (look for the Touchpad icon by
the clock, under the **^** arrow, or for `touchpad-server.exe` in Task
Manager; "Start with Windows" runs it hidden), or another program
uses the port. `--port` changes the server's port, but the app only looks on
47820, so it's for testing only.

## The app disconnects when I switch away from it

It shouldn't: the foreground service keeps it connected and shows a
notification while it does. If the notification isn't there, the service
isn't running. Some phone makers kill background services aggressively; see
the battery tip above. Swiping the app away from Recents disconnects on
purpose.
