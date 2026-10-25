# Building, installing and testing

## Toolchain

These are installed on the development PC and verified to work:

| Tool | Version | Used for |
|---|---|---|
| Rust (rustup, MSVC target) | 1.99 | PC server |
| Visual Studio Build Tools (C++ workload) | 2019+ | Rust's MSVC linker |
| JDK | 21 | Gradle, Android build, `keytool` |
| Android SDK command-line tools | latest | `sdkmanager` |
| Android SDK Platform | 37.2 | `compileSdk` |
| Android SDK Build-Tools | 36.1.0 | packaging, `apksigner` |
| Android SDK Platform-Tools | 37.x | `adb` |
| `cargo-audit` (optional) | latest | dependency vulnerability scan |
| Python + `cryptography` (optional) | 3.13 | only to regenerate the crypto test vectors |

The Gradle wrapper (Gradle 9.8.2, checksum-pinned) and all libraries download
automatically on the first build. Library versions live in
[`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml):
AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00.

### Setting it up on another PC

```powershell
winget install Rustlang.Rustup
winget install EclipseAdoptium.Temurin.21.JDK
cargo install cargo-audit --locked     # optional
# Rust also needs the MSVC linker: Visual Studio Build Tools with the
# "Desktop development with C++" workload.
```

Android SDK without Android Studio:

1. Download "Command line tools only" for Windows from
   <https://developer.android.com/studio#command-line-tools-only>.
2. Unzip so the tools end up in `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin`.
3. In Git Bash (PowerShell can't pipe answers to `sdkmanager`):

   ```bash
   SDK="$LOCALAPPDATA/Android/Sdk"
   yes | "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" --licenses
   "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" \
       "platform-tools" "platforms;android-37.2" "build-tools;36.1.0"
   ```

Installing Android Studio instead also works; `build.ps1` finds its bundled
JDK and SDK.

## Building

```powershell
.\scripts\build.ps1              # both
.\scripts\build.ps1 -Only server
.\scripts\build.ps1 -Only android
```

The script runs the tests (and Android Lint), then builds:
- `dist\touchpad-server.exe`, an optimized release build
- `dist\touchpapp.exe`, a window-less launcher that runs
  `touchpad-server.exe --tray` (an icon by the clock, no console window)
- `dist\Touchpad.apk`, a release build (R8-minified, signed with **your**
  private key)

It finds the JDK and Android SDK on its own and writes the machine-specific
`android\local.properties`.

### The signing key

On the first Android build, `build.ps1` creates a private RSA-3072 signing key
in `%USERPROFILE%\.touchpad`:
- `release.p12`, the key, with a random password;
- `signing.properties`, which tells Gradle where the key is.

The folder is readable only by your Windows account and is outside the
repository. **Back it up.** Android only installs an update if it's signed
with the same key, so losing it means uninstalling the app (and pairing again).

To use a key stored elsewhere, pass `-Ptouchpad.signing=<path to a
signing.properties>` to Gradle. Without a signing file, the release APK is
left unsigned and `build.ps1` stops with an error.

## Installing the app

### Option A: USB (recommended while developing)

1. On the phone: **Settings → About phone**, tap **Build number** seven times
   to unlock Developer options.
2. **Settings → System → Developer options → USB debugging**: on.
3. Plug the phone in and accept **Allow USB debugging?**.
4. Run `.\scripts\install-apk.ps1`. It installs and launches the app.

Wireless instead of a cable: in Developer options, open **Wireless debugging →
Pair device with pairing code**, then run `adb pair <ip>:<port>` and
`adb connect <ip>:<port>` with the values shown, then `install-apk.ps1`.
(`adb` is in `%LOCALAPPDATA%\Android\Sdk\platform-tools`.)

Turn USB debugging off again when you're done; it's a powerful way into the phone.

### Option B: copy the APK

Copy `dist\Touchpad.apk` to the phone and open it. Android asks to allow
installing from that app the first time; you can revoke that permission
afterwards.

### First launch

The app asks permission to show notifications; allow it to see the connection
status while the app is in the background. The app works without it. Then
pair as described in [instructive.md](../instructive.md#step-3-connect-the-phone-to-the-computer-only-once).

## Running the PC server

```powershell
.\dist\touchpad-server.exe               # run (pairing opens on first run)
.\dist\touchpad-server.exe --pair        # run and accept a new phone for 5 minutes
.\dist\touchpad-server.exe --list        # list paired phones
.\dist\touchpad-server.exe --unpair ID   # forget a phone (ID prefix from --list, or "all")
.\dist\touchpad-server.exe --help
```

While it runs, type `y`/`n` (answer a pairing request), `pair`, `list`,
`unpair <ID>` or `help`, then Enter.

Pairings are stored, DPAPI-encrypted, in `%APPDATA%\TouchpadServer\pairings.dat`.
`--data-dir <folder>` uses another folder (the interop test uses this, so it
never touches your real pairings).

`.\scripts\start-on-login.ps1` starts Touchpad as a tray icon at every login,
through `touchpapp.exe` and the per-user `Run` registry key
(`-Remove` undoes it; the icon's "Start with Windows" item does the same).
The launcher exists so nothing flashes at login: the server stays a console
program, so its commands keep working and the firewall rule still covers it.
Quit the tray icon before using `--list`/`--unpair`, and before rebuilding. Run `.\scripts\allow-firewall.ps1` again if you move
the project folder, because the firewall rule names the exact `.exe` path.

## Tests

| Suite | Command | Covers |
|---|---|---|
| Server, 52 tests | `cd pc-server; cargo test` | protocol layouts and malformed input; crypto against RFC and shared vectors; ECDH point validation; AES-GCM channel (replay, every single-bit flip, wrong key/session, reflection); pairing (success, rejection, wrong key, conflicting data, timeout, busy, closed); sessions (forged/unknown HELLO, retransmission, limits, replacement); click de-duplication and recovery; timeouts; revocation; DPAPI store |
| App, 26 tests | `cd android; .\gradlew.bat test` | the same layouts, crypto vectors, ECDH point validation, AES-GCM channel properties, acceleration curve |
| End to end | `.\scripts\interop-test.ps1` | the app's protocol code pairs with the real server binary over UDP, confirming the pairing only if both sides computed the same code; then a forged HELLO, a replay, input and unpairing |
| Lint | `cargo clippy --all-targets`, `.\gradlew.bat lintRelease` | 0 warnings / no issues |
| Vulnerabilities | `cd pc-server; cargo audit` | Rust crates against the RustSec database |

The interop test nudges the pointer a few pixels and back; it never clicks.

### Crypto test vectors

`docs/test-vectors.txt` holds expected outputs for fixed inputs. It was
generated by `scripts/gen-test-vectors.py`, an independent Python
implementation, and the Rust and Kotlin tests must reproduce it exactly. Only
regenerate it if the protocol's cryptography changes on purpose:

```powershell
python -m pip install cryptography
python scripts\gen-test-vectors.py
```

## Updating dependencies safely

**Rust crates**

```powershell
cd pc-server
cargo update
cargo audit
cargo test
```

Commit the updated `Cargo.lock`.

**Android libraries.** Edit the versions in `gradle/libs.versions.toml`, then
re-record the checksums and review what changed:

```powershell
cd android
.\gradlew.bat --write-verification-metadata sha256 test lintRelease assembleRelease
git diff gradle/verification-metadata.xml
```

Every new or changed entry should be a dependency you meant to change. A build
that fails with "Dependency verification failed" means an artifact doesn't
match its recorded checksum: investigate before re-recording.

The one exception: `*-sources.jar`, `*-javadoc.jar` and `gradle-*-src.zip`
are trusted without a checksum (`<trusted-artifacts>` in the same file).
Android Studio downloads them so you can read library code; they are never
compiled or run.

**Gradle itself.** Use the checksum Gradle publishes next to each release:

```powershell
.\gradlew.bat wrapper --gradle-version <X.Y> --gradle-distribution-sha256-sum <sha256 from services.gradle.org>
```

Then compare `gradle\wrapper\gradle-wrapper.jar` with the official
`gradle-<X.Y>-wrapper.jar.sha256`.
