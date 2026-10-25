# How to set up and use Touchpad

Touchpad turns your Android phone into a wireless touchpad for your Windows
computer. You slide your finger on the phone and the mouse pointer moves on
the computer. Two buttons on the phone do left-click and right-click.

This guide takes you through everything, step by step. No technical knowledge
needed. Setting it up takes about 10 minutes, and you only do it once.

---

## What you need

- A **Windows computer** (Windows 10 or 11).
- An **Android phone** (Android 8 or newer, which is almost any phone from
  2017 onwards).
- Both connected to the **same Wi-Fi network**, ideally your home Wi-Fi.
- Two files:
  - **`touchpad-server.exe`**, the small program that runs on the computer;
  - **`Touchpad.apk`**, the app for the phone.

### Getting the two files

- **The easy way:** on the project's GitHub page, look for **Releases** on the
  right side. Open the latest release and download both files.
- **If there is no release:** someone has to build the files from the source
  code. That's a technical process, described in
  [docs/BUILDING.md](docs/BUILDING.md). Once built, both files are in the
  `dist` folder.

> **Only use files from a source you trust.** This program can control your
> mouse, so a modified copy could do harm.

---

## Step 1: Set up the computer

1. **Make a folder for the program**, for example `Documents\Touchpad`, and put
   `touchpad-server.exe` in it.

2. **Double-click `touchpad-server.exe`.**
   Windows may show a blue box saying **"Windows protected your PC"**. That
   happens with programs from small, independent projects. If you trust where
   you got the file, click **More info**, then **Run anyway**.

3. **Allow it through the firewall.** Windows asks whether the program may use
   the network. Tick **Private networks** only (leave **Public networks**
   unticked) and click **Allow**.

   > If you clicked Cancel by mistake, see
   > [It doesn't find my computer](#it-doesnt-find-my-computer) below.

4. **A black window opens.** It says something like:

   ```
   Touchpad server v1.0.0 — "MY-PC"
   Listening on 192.168.1.20:47820 (UDP).
   No phone is paired yet.
   Pairing is open for 5 minutes: open the app on the new phone and tap Pair.
   ```

   **Leave this window open.** The touchpad only works while it is running.
   You can minimize it.

---

## Step 2: Install the app on your phone

1. **Copy `Touchpad.apk` to your phone.** Any way works: a USB cable, Google
   Drive, or emailing it to yourself and opening the email on the phone.

2. **Tap the file on the phone to open it.**
   Android asks whether to allow installing apps from this source (for example
   from "Files" or "Drive"). Tap **Settings**, turn on **Allow from this
   source**, and go back.

3. **Tap Install.** Google Play Protect may warn that the app is from an
   unknown developer; if you trust where you got it, choose **Install anyway**.

4. *(Recommended)* Once installed, you can turn **Allow from this source** off
   again, under **Settings → Apps → Special app access → Install unknown apps**.

---

## Step 3: Connect the phone to the computer (only once)

This step, called **pairing**, makes sure that **only your phone** can control
your computer.

1. Make sure the phone is on the **same Wi-Fi** as the computer, and that the
   black window from Step 1 is still open.

2. **Open the Touchpad app.** If it asks to show notifications, tap **Allow**.
   It lets you see whether you're connected while using other apps.

3. Your computer's name appears in a list. Tap **Pair** next to it.

4. **Compare the two codes.** The phone shows a 6-digit number, and the black
   window on the computer shows one too:

   ```
     "Pixel 8" wants to pair with this PC.
     Code: 482 913
     Accept ONLY if your phone shows exactly this code.
   ```

   - **If the numbers are the same:** tap **The codes match** on the phone,
     then click on the black window, type **`y`** and press **Enter**.
   - **If the numbers are different:** tap **Cancel** on the phone and type
     **`n`** + **Enter** on the computer. Then try again. Different numbers
     mean something got between your phone and your computer.

5. **Done!** The phone now shows the touchpad, with a **green dot** and
   "Connected to (your computer)".

You never have to do this again for this phone.

---

## Everyday use

### Using the touchpad

| To… | Do this |
|---|---|
| Move the pointer | Slide one finger on the big rectangle |
| Left-click | Tap the big rectangle with one finger, or tap the **Left** button |
| Right-click | Tap the big rectangle with two fingers at once, or tap the **Right** button |
| Drag something | Hold **Left** with your thumb and slide another finger on the big rectangle |

Slow movements are precise; quick swipes travel further.

### Using the keyboard

Turn the phone **sideways** and the screen becomes a Spanish keyboard. Turn it
upright again for the touchpad. (This works even if auto-rotate is off.)

| To… | Do this |
|---|---|
| Type | Tap the keys. Hold a key to repeat it |
| Capital letter | Tap **⇧** then the letter (only the next key is capital) |
| All capitals | Tap **⇪**, tap it again to turn it off |
| á é í ó ú, ü, à, ê… | Tap the accent key (**´**, or **⇧** + **´** for ¨), then the letter |
| @ # € [ ] { } \ \| ~ | Tap **AltGr**, then the key with that small symbol in its corner |
| A shortcut (Ctrl+C, Alt+Tab, Win+E…) | Tap **Ctrl**, **Alt** or **⊞**, then the key. To use it for several keys, keep holding it with one finger |
| Open the Start menu | Tap **⊞** twice |

Keys only reach the computer while the dot at the top is green.

### Next time

1. Start `touchpad-server.exe` on the computer.
2. Open the Touchpad app on the phone.

It connects by itself within a second.

### Switching to other apps

You can leave the Touchpad app and use your phone normally. It stays
connected in the background, and a notification shows **Connected to (your
computer)**. To disconnect, tap **Disconnect** on that notification, or swipe
Touchpad away from your recent apps.

### Run it as an icon by the clock, and start it with Windows *(optional)*

Keep `touchpapp.exe` in the same folder as `touchpad-server.exe`.

1. Close the black window if it is open.
2. Double-click **`touchpapp.exe`**. No window opens: a small green
   touchpad icon appears next to the clock. If you don't see it, click the
   **^** arrow there (hidden icons).
3. Click the icon and choose **Start with Windows**. From now on Touchpad
   starts by itself, hidden, every time you log in.

Click the icon at any time to see whether a phone is connected, pair a new
phone, or **Quit Touchpad**. When a phone asks to pair, a box with the code
pops up instead: click **Yes** only if the phone shows the same code.

### Adding another phone

Click the Touchpad icon by the clock and choose **Pair a new phone**. If you
use the black window instead, click on it, type **`pair`** and press **Enter**.
For the next 5 minutes a new phone can be paired, exactly as in Step 3.

---

## Staying safe

- **Only accept a pairing when you started it yourself and the two codes are
  identical.** If a pairing request shows up in the black window that you
  didn't start, type **`n`**.
- **Use it on your home Wi-Fi**, not on public Wi-Fi in cafés, hotels or
  airports.
- **If you lose your phone**, remove it from the computer. In the black
  window, type `list` and press Enter to see the paired phones and their IDs,
  then type `unpair` followed by the ID (for example `unpair 3fa9`) and press
  Enter. To remove all phones, type `unpair all`.
- **Don't run the program "as administrator"** unless you have a specific
  reason; the black window warns you if it is.

---

## Something isn't working?

### It doesn't find my computer

- Is the black window open on the computer?
- Are the phone and the computer on the **same Wi-Fi**? (A computer on a cable
  to the same router is fine.)
- Is a **VPN** turned on on either device? Turn it off and try again.
- **Firewall:** open **Start → "Allow an app through Windows Firewall"**, click
  **Change settings**, find **touchpad-server** and make sure **Private** is
  ticked. If it isn't listed, click **Allow another app…** and choose the
  file.
- Your network might be set to **Public**. Open **Settings → Network &
  internet → Wi-Fi** (or Ethernet) → your network, and choose **Private
  network**.
- Some Wi-Fi networks (guest networks, many public ones) stop devices from
  seeing each other. Use your main home network.

### It says "Not accepting phones"

Pairing is only open for 5 minutes. Type **`pair`** in the black window and
press **Enter**, then tap **Pair** again.

### It was working, and now it keeps saying "Looking for…"

- Make sure the black window is open and both devices are on the same Wi-Fi.
- If the phone was removed on the computer (with `unpair`), tap **Unpair** on
  the phone and pair again.

### The pointer doesn't move over some windows

Windows protects programs that run "as administrator" (like Task Manager) from
being controlled this way. That's expected.

### The pointer is too fast or too slow

Change it in Windows: **Settings → Bluetooth & devices → Mouse → Mouse
pointer speed**.

### It feels slow or jumpy

- A 5 GHz Wi-Fi network works better than 2.4 GHz.
- Some phones limit apps to save battery. Long-press the Touchpad icon → **App
  info → Battery → Unrestricted**.

More fixes are in [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

---

## Removing Touchpad

**From the phone:** in the app, tap **Unpair**, then uninstall it like any app
(long-press the icon → **Uninstall**).

**From the computer:**
1. Close the black window.
2. Delete your Touchpad folder.
3. Delete the saved pairings: press **Windows key + R**, type
   `%APPDATA%\TouchpadServer` and press **Enter**, then delete that folder.
4. If you set up automatic start: press **Windows key + R**, type
   `shell:startup`, and delete the shortcut.
5. Remove the firewall permission: **Start → "Allow an app through Windows
   Firewall" → Change settings**, select **touchpad-server** and click
   **Remove**.
