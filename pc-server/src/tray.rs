//! Tray mode (`--tray`): the same server with no console window. An icon in
//! the notification area (the "hidden icons" next to the clock) shows the
//! status, and its menu opens pairing, turns "Start with Windows" on or off,
//! and quits. Pairing codes are compared in a Yes/No box instead of the
//! console.
//!
//! The UI thread owns the icon and its hidden window. The network loop runs
//! on its own thread, exactly as in console mode; the two only exchange
//! [`Notice`]s and [`Command`]s over channels.

use std::cell::RefCell;
use std::iter::once;
use std::net::UdpSocket;
use std::process::ExitCode;
use std::ptr::{null, null_mut};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::time::Instant;

use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::Graphics::Gdi::{CreateBitmap, DeleteObject};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Threading::GetCurrentThreadId;
use windows_sys::Win32::UI::HiDpi::{DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, SetProcessDpiAwarenessContext};
use windows_sys::Win32::UI::Shell::{
    NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_SHOWTIP, NIF_TIP, NIIF_INFO, NIIF_WARNING, NIM_ADD, NIM_DELETE, NIM_MODIFY,
    NIM_SETVERSION, NIN_SELECT, NOTIFYICON_VERSION_4, NOTIFYICONDATAW, Shell_NotifyIconW,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreateIconIndirect, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyMenu, DestroyWindow,
    DispatchMessageW, EnumThreadWindows, GetMessageW, GetSystemMetrics, HICON, ICONINFO, IDNO, IDYES, MB_DEFBUTTON2,
    MB_ICONERROR, MB_ICONQUESTION, MB_SETFOREGROUND, MB_TOPMOST, MB_YESNO, MF_CHECKED, MF_GRAYED, MF_SEPARATOR,
    MF_STRING, MSG, MessageBoxW, PostMessageW, PostQuitMessage, RegisterClassW, RegisterWindowMessageW,
    SM_CXSMICON, SetForegroundWindow, TPM_NONOTIFY, TPM_RETURNCMD, TPM_RIGHTBUTTON, TrackPopupMenu,
    TranslateMessage, WM_APP, WM_COMMAND, WM_CONTEXTMENU, WM_DESTROY, WM_NULL, WNDCLASSW,
};

use crate::input::SystemInput;
use crate::protocol::Id;
use crate::server::{Notice, Server};
use crate::store::Store;
use crate::{PAIRING_WINDOW, pc_name, primary_lan_ip, receive};

const TITLE: &str = "Touchpad";
/// Messages from the tray icon (clicks, keyboard).
const WM_TRAY: u32 = WM_APP + 1;
/// The network thread queued new notices.
const WM_NOTICES: u32 = WM_APP + 2;
const ICON_ID: u32 = 1;
/// NIN_SELECT | NINF_KEY: the icon was chosen with the keyboard.
const NIN_KEYSELECT: u32 = NIN_SELECT | 1;

const MENU_PAIR: usize = 1;
const MENU_AUTOSTART: usize = 2;
const MENU_QUIT: usize = 3;

/// What the UI asks of the network thread.
enum Command {
    Pair,
    Decide { pair_id: Id, accept: bool },
}

struct App {
    hwnd: HWND,
    icon: HICON,
    commands: Sender<Command>,
    notices: Receiver<Notice>,
    /// "Touchpad on NAME (address)", the first line of the menu.
    heading: String,
    /// Names of the phones with an open session, one entry per session.
    connected: Vec<String>,
    pairing_open: bool,
    prompt: Option<Prompt>,
}

/// An open "accept this pairing?" box, shown by its own thread.
struct Prompt {
    /// The box's thread id, or 0 once it has closed.
    thread: Arc<AtomicU32>,
}

thread_local! {
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
}

/// Explorer broadcasts this when the taskbar is (re)created, for example after
/// Explorer restarts or when it starts after us at login: the icon must be
/// added again.
static TASKBAR_CREATED: AtomicU32 = AtomicU32::new(0);

pub fn run(store: Store, port: u16, pair: bool) -> ExitCode {
    // SAFETY: a process-wide setting with no pointers; failure (older Windows)
    // only means a blurrier icon.
    unsafe { SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) };

    let socket = match crate::bind(port) {
        Ok(socket) => socket,
        Err(message) => {
            error_box(&format!(
                "{message}\n\nTouchpad may already be running: look for its icon next to the clock \
                 (click the ^ arrow to see hidden icons)."
            ));
            return ExitCode::FAILURE;
        }
    };

    let name = pc_name();
    let heading = match primary_lan_ip() {
        Some(ip) => format!("{TITLE} on {name} ({ip})"),
        None => format!("{TITLE} on {name}"),
    };

    let Some(hwnd) = create_window() else {
        error_box("Could not create the tray icon window.");
        return ExitCode::FAILURE;
    };
    let (command_tx, command_rx) = mpsc::channel();
    let (notice_tx, notice_rx) = mpsc::channel();
    let hwnd_bits = hwnd as usize;
    std::thread::spawn(move || network_loop(socket, store, name, pair, command_rx, notice_tx, hwnd_bits));

    let app = App {
        hwnd,
        icon: make_icon(),
        commands: command_tx,
        notices: notice_rx,
        heading,
        connected: Vec::new(),
        pairing_open: false,
        prompt: None,
    };
    app.add_icon();
    APP.with(|cell| *cell.borrow_mut() = Some(app));

    // SAFETY: a standard message loop on the thread that owns the window.
    unsafe {
        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    ExitCode::SUCCESS
}

/// The console mode's loop, minus the console: commands come from the menu
/// and the pairing box, notices go to the UI thread.
fn network_loop(
    socket: UdpSocket,
    store: Store,
    name: String,
    pair: bool,
    commands: Receiver<Command>,
    notices: Sender<Notice>,
    hwnd_bits: usize,
) {
    let first_run = store.phones().is_empty();
    let mut server = Server::new(SystemInput::new(), name, store);
    if pair || first_run {
        server.open_pairing(Instant::now(), PAIRING_WINDOW);
    }
    let mut buf = [0u8; 1500];
    loop {
        receive(&socket, &mut server, &mut buf);
        let now = Instant::now();
        while let Ok(command) = commands.try_recv() {
            match command {
                Command::Pair => server.open_pairing(now, PAIRING_WINDOW),
                // Only the exact attempt the box was shown for can be answered.
                Command::Decide { pair_id, accept } => {
                    if server.awaiting_operator() == Some(pair_id) {
                        server.operator_decision(pair_id, accept, now);
                    }
                }
            }
        }
        server.tick(now);
        let fresh = server.drain_notices();
        if !fresh.is_empty() {
            for notice in fresh {
                let _ = notices.send(notice);
            }
            // SAFETY: posting to a window handle is safe even if the window
            // is gone; the call then just fails.
            unsafe { PostMessageW(hwnd_bits as HWND, WM_NOTICES, 0, 0) };
        }
    }
}

impl App {
    fn status(&self) -> String {
        if !self.connected.is_empty() {
            format!("Connected to {}", self.connected.join(", "))
        } else if self.pairing_open {
            "Ready to pair a new phone".to_string()
        } else {
            "Waiting for your phone".to_string()
        }
    }

    fn icon_data(&self) -> NOTIFYICONDATAW {
        let mut data = NOTIFYICONDATAW {
            cbSize: size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: self.hwnd,
            uID: ICON_ID,
            ..Default::default()
        };
        data.Anonymous.uVersion = NOTIFYICON_VERSION_4;
        data
    }

    fn add_icon(&self) {
        let mut data = self.icon_data();
        data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
        data.uCallbackMessage = WM_TRAY;
        data.hIcon = self.icon;
        copy_wide(&mut data.szTip, &self.tooltip());
        // SAFETY: `data` is fully initialised and outlives both calls. If the
        // taskbar isn't up yet this fails, and TaskbarCreated retries it.
        unsafe {
            Shell_NotifyIconW(NIM_ADD, &data);
            Shell_NotifyIconW(NIM_SETVERSION, &data);
        }
    }

    fn tooltip(&self) -> String {
        format!("{TITLE}\n{}", self.status())
    }

    fn refresh_tooltip(&self) {
        let mut data = self.icon_data();
        data.uFlags = NIF_TIP | NIF_SHOWTIP;
        copy_wide(&mut data.szTip, &self.tooltip());
        // SAFETY: as in `add_icon`.
        unsafe { Shell_NotifyIconW(NIM_MODIFY, &data) };
    }

    fn balloon(&self, title: &str, text: &str, warning: bool) {
        let mut data = self.icon_data();
        data.uFlags = NIF_INFO;
        data.dwInfoFlags = if warning { NIIF_WARNING } else { NIIF_INFO };
        copy_wide(&mut data.szInfoTitle, title);
        copy_wide(&mut data.szInfo, text);
        // SAFETY: as in `add_icon`.
        unsafe { Shell_NotifyIconW(NIM_MODIFY, &data) };
    }

    fn remove_icon(&self) {
        let data = self.icon_data();
        // SAFETY: as in `add_icon`.
        unsafe { Shell_NotifyIconW(NIM_DELETE, &data) };
    }

    fn handle_notices(&mut self) {
        while let Ok(notice) = self.notices.try_recv() {
            match notice {
                Notice::Connected { phone, .. } => self.connected.push(phone),
                Notice::Disconnected { phone, .. } => {
                    if let Some(i) = self.connected.iter().position(|p| *p == phone) {
                        self.connected.remove(i);
                    }
                }
                Notice::PairingOpened { until_secs } => {
                    self.pairing_open = true;
                    self.balloon(
                        "Ready to pair a new phone",
                        &format!(
                            "Open the Touchpad app on the phone and tap Pair. Pairing stays open for {} minutes.",
                            until_secs / 60
                        ),
                        false,
                    );
                }
                Notice::PairingClosed => self.pairing_open = false,
                Notice::PairingRequest { pair_id, phone, code } => {
                    self.dismiss_prompt();
                    self.prompt = Some(ask(pair_id, &phone, code, self.commands.clone()));
                }
                Notice::Paired { phone } => {
                    self.prompt = None;
                    self.balloon(&format!("Paired with {phone}"), "It will connect automatically from now on.", false);
                }
                Notice::PairingFailed { reason } => {
                    self.dismiss_prompt();
                    self.balloon("Pairing failed", &format!("Pairing failed: {reason}."), true);
                }
            }
        }
        self.refresh_tooltip();
    }

    /// Closes a pairing box that is no longer relevant, as if "No" was
    /// clicked. Its answer is then ignored by the network thread, which only
    /// accepts answers for the attempt still waiting.
    fn dismiss_prompt(&mut self) {
        if let Some(prompt) = self.prompt.take() {
            let thread = prompt.thread.load(Ordering::SeqCst);
            if thread != 0 {
                // SAFETY: the callback only posts a message to each window.
                unsafe { EnumThreadWindows(thread, Some(press_no), 0) };
            }
        }
    }
}

unsafe extern "system" fn press_no(hwnd: HWND, _: LPARAM) -> windows_sys::core::BOOL {
    // SAFETY: posting to a window of the box's thread; harmless if it closed.
    unsafe { PostMessageW(hwnd, WM_COMMAND, IDNO as WPARAM, 0) };
    1
}

/// Shows the code comparison box on its own thread, so the icon keeps working
/// while it is open, and sends the answer to the network thread.
fn ask(pair_id: Id, phone: &str, code: u32, commands: Sender<Command>) -> Prompt {
    let thread = Arc::new(AtomicU32::new(0));
    let shared = Arc::clone(&thread);
    let text = format!(
        "\"{phone}\" wants to pair with this PC.\n\n        Code:  {:03} {:03}\n\n\
         Click Yes ONLY if your phone shows exactly this code.",
        code / 1000,
        code % 1000
    );
    std::thread::spawn(move || {
        // SAFETY: no arguments.
        shared.store(unsafe { GetCurrentThreadId() }, Ordering::SeqCst);
        let text = wide(&text);
        let caption = wide("Touchpad: pairing request");
        // SAFETY: both strings are NUL-terminated and outlive the call.
        let answer = unsafe {
            MessageBoxW(
                null_mut(),
                text.as_ptr(),
                caption.as_ptr(),
                MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2 | MB_TOPMOST | MB_SETFOREGROUND,
            )
        };
        shared.store(0, Ordering::SeqCst);
        let _ = commands.send(Command::Decide { pair_id, accept: answer == IDYES });
    });
    Prompt { thread }
}

/// Runs `f` on the app state. Returns `None` if it is already in use further
/// up the stack (a re-entrant message), instead of panicking.
fn with_app<T>(f: impl FnOnce(&mut App) -> T) -> Option<T> {
    APP.with(|cell| cell.try_borrow_mut().ok()?.as_mut().map(f))
}

unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match msg {
        WM_TRAY => {
            // With NOTIFYICON_VERSION_4 the event is in the low word of
            // lparam and the click position in wparam.
            let event = (lparam & 0xFFFF) as u32;
            if event == WM_CONTEXTMENU || event == NIN_SELECT || event == NIN_KEYSELECT {
                let x = (wparam & 0xFFFF) as i16 as i32;
                let y = ((wparam >> 16) & 0xFFFF) as i16 as i32;
                show_menu(hwnd, x, y);
            }
            0
        }
        WM_NOTICES => {
            if with_app(App::handle_notices).is_none() {
                // Busy (inside the menu, say): try again shortly.
                // SAFETY: posting to our own window.
                unsafe { PostMessageW(hwnd, WM_NOTICES, 0, 0) };
            }
            0
        }
        WM_DESTROY => {
            // SAFETY: no pointers.
            unsafe { PostQuitMessage(0) };
            0
        }
        _ if msg != 0 && msg == TASKBAR_CREATED.load(Ordering::Relaxed) => {
            with_app(|app| app.add_icon());
            0
        }
        // SAFETY: forwarding the arguments we were given.
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

fn show_menu(hwnd: HWND, x: i32, y: i32) {
    let Some((heading, status)) = with_app(|app| (app.heading.clone(), app.status())) else { return };
    let autostart = autostart::state();

    // SAFETY: the menu is created, shown and destroyed here, and every string
    // passed to it outlives the call that uses it.
    let choice = unsafe {
        let menu = CreatePopupMenu();
        let item = |flags, id, text: &str| {
            let text = wide(text);
            AppendMenuW(menu, flags, id, text.as_ptr());
        };
        item(MF_STRING | MF_GRAYED, 0, &heading);
        item(MF_STRING | MF_GRAYED, 0, &status);
        AppendMenuW(menu, MF_SEPARATOR, 0, null());
        item(MF_STRING, MENU_PAIR, "Pair a new phone");
        match autostart {
            autostart::State::On => item(MF_STRING | MF_CHECKED, MENU_AUTOSTART, "Start with Windows"),
            autostart::State::Off => item(MF_STRING, MENU_AUTOSTART, "Start with Windows"),
            autostart::State::Unavailable => {
                item(MF_STRING | MF_GRAYED, 0, "Start with Windows (touchpapp.exe is missing)")
            }
        }
        AppendMenuW(menu, MF_SEPARATOR, 0, null());
        item(MF_STRING, MENU_QUIT, "Quit Touchpad");

        // Without this the menu doesn't close when you click elsewhere.
        SetForegroundWindow(hwnd);
        let choice = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON, x, y, 0, hwnd, null());
        PostMessageW(hwnd, WM_NULL, 0, 0);
        DestroyMenu(menu);
        choice as usize
    };

    match choice {
        MENU_PAIR => {
            with_app(|app| app.commands.send(Command::Pair));
        }
        MENU_AUTOSTART => {
            let enable = !matches!(autostart, autostart::State::On);
            if let Err(e) = autostart::set(enable) {
                error_box(&format!("Could not change \"Start with Windows\": {e}"));
            }
        }
        MENU_QUIT => {
            with_app(|app| {
                app.dismiss_prompt();
                app.remove_icon();
            });
            // SAFETY: our own window, on its thread.
            unsafe { DestroyWindow(hwnd) };
        }
        _ => {}
    }
}

fn create_window() -> Option<HWND> {
    let class = wide("TouchpadTrayWindow");
    // SAFETY: the class and window name strings outlive the calls; the
    // window is never shown, it only receives the icon's messages.
    unsafe {
        TASKBAR_CREATED.store(RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()), Ordering::Relaxed);
        let instance = GetModuleHandleW(null());
        let wc = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance,
            lpszClassName: class.as_ptr(),
            ..std::mem::zeroed()
        };
        RegisterClassW(&wc);
        let hwnd = CreateWindowExW(
            0,
            class.as_ptr(),
            wide(TITLE).as_ptr(),
            0,
            0,
            0,
            0,
            0,
            null_mut(),
            null_mut(),
            instance,
            null(),
        );
        (!hwnd.is_null()).then_some(hwnd)
    }
}

/// Draws the icon in code (a rounded touchpad with two buttons, in the app's
/// accent green), so no image file or resource compiler is needed.
fn make_icon() -> HICON {
    // SAFETY: no pointers.
    let size = unsafe { GetSystemMetrics(SM_CXSMICON) }.clamp(16, 64);
    let s = size as f32;
    let accent: u32 = 0x3DD68C;
    let dark: u32 = 0x0E1116;
    let line = (s / 16.0).round().max(1.0);
    let divider_y = (s * 0.64).round();

    let mut pixels = vec![0u32; (size * size) as usize];
    for y in 0..size {
        for x in 0..size {
            let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
            // Signed distance to a rounded rectangle, in pixels.
            let (half_w, half_h, radius) = (s * 0.46, s * 0.40, s * 0.16);
            let qx = (px - s / 2.0).abs() - (half_w - radius);
            let qy = (py - s / 2.0).abs() - (half_h - radius);
            let distance = qx.max(0.0).hypot(qy.max(0.0)) + qx.max(qy).min(0.0) - radius;
            let coverage = (0.5 - distance).clamp(0.0, 1.0);
            if coverage == 0.0 {
                continue;
            }
            let on_divider = (py - 0.5 - divider_y).abs() < line / 2.0
                || (py > divider_y && (px - 0.5 - (s / 2.0).floor()).abs() < line / 2.0);
            let rgb = if on_divider && distance < -line { dark } else { accent };
            let alpha = (coverage * 255.0).round() as u32;
            pixels[(y * size + x) as usize] = (alpha << 24) | rgb;
        }
    }

    let mask = vec![0u8; ((size as usize).div_ceil(16) * 2) * size as usize];
    // SAFETY: both buffers match the sizes and bit depths passed, and
    // CreateIconIndirect copies the bitmaps, so they are freed right after.
    unsafe {
        let color = CreateBitmap(size, size, 1, 32, pixels.as_ptr().cast());
        let mask = CreateBitmap(size, size, 1, 1, mask.as_ptr().cast());
        let info = ICONINFO { fIcon: 1, xHotspot: 0, yHotspot: 0, hbmMask: mask, hbmColor: color };
        let icon = CreateIconIndirect(&info);
        DeleteObject(color);
        DeleteObject(mask);
        icon
    }
}

pub fn error_box(text: &str) {
    let text = wide(text);
    let caption = wide(TITLE);
    // SAFETY: both strings are NUL-terminated and outlive the call.
    unsafe { MessageBoxW(null_mut(), text.as_ptr(), caption.as_ptr(), MB_ICONERROR | MB_TOPMOST) };
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(once(0)).collect()
}

/// Copies `s` into a fixed Win32 text field, truncated, always NUL-terminated.
fn copy_wide(dst: &mut [u16], s: &str) {
    let mut n = 0;
    let room = dst.len() - 1;
    for (slot, unit) in dst.iter_mut().take(room).zip(s.encode_utf16()) {
        *slot = unit;
        n += 1;
    }
    dst[n] = 0;
}

/// "Start with Windows", stored where Windows looks for per-user startup
/// programs: HKCU\Software\Microsoft\Windows\CurrentVersion\Run. It points at
/// touchpapp.exe, which starts this server without a console window.
pub mod autostart {
    use std::path::PathBuf;
    use std::ptr::null_mut;

    use windows_sys::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_SUCCESS};
    use windows_sys::Win32::System::Registry::{
        HKEY_CURRENT_USER, REG_SZ, RRF_RT_REG_SZ, RegDeleteKeyValueW, RegGetValueW, RegSetKeyValueW,
    };

    use super::wide;

    const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
    const VALUE: &str = "Touchpad";

    pub enum State {
        On,
        Off,
        /// touchpapp.exe isn't next to this program.
        Unavailable,
    }

    fn launcher() -> Option<PathBuf> {
        let path = std::env::current_exe().ok()?.with_file_name("touchpapp.exe");
        path.is_file().then_some(path)
    }

    fn command() -> Option<String> {
        launcher().map(|path| format!("\"{}\"", path.display()))
    }

    /// On only if the entry starts this copy; an entry left by a copy in
    /// another folder shows as off, and turning it on replaces it.
    pub fn state() -> State {
        let Some(expected) = command() else { return State::Unavailable };
        let mut buf = [0u16; 1024];
        let mut bytes = size_of_val(&buf) as u32;
        // SAFETY: `buf` holds `bytes` bytes; the strings are NUL-terminated.
        let status = unsafe {
            RegGetValueW(
                HKEY_CURRENT_USER,
                wide(RUN_KEY).as_ptr(),
                wide(VALUE).as_ptr(),
                RRF_RT_REG_SZ,
                null_mut(),
                buf.as_mut_ptr().cast(),
                &mut bytes,
            )
        };
        if status != ERROR_SUCCESS {
            return State::Off;
        }
        let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        if String::from_utf16_lossy(&buf[..len]).eq_ignore_ascii_case(&expected) { State::On } else { State::Off }
    }

    pub fn set(enable: bool) -> Result<(), String> {
        let status = if enable {
            let command = command().ok_or("touchpapp.exe is not next to touchpad-server.exe")?;
            let data = wide(&command);
            // SAFETY: `data` is a NUL-terminated UTF-16 string of the given size.
            unsafe {
                RegSetKeyValueW(
                    HKEY_CURRENT_USER,
                    wide(RUN_KEY).as_ptr(),
                    wide(VALUE).as_ptr(),
                    REG_SZ,
                    data.as_ptr().cast(),
                    size_of_val(data.as_slice()) as u32,
                )
            }
        } else {
            // SAFETY: NUL-terminated strings only.
            match unsafe { RegDeleteKeyValueW(HKEY_CURRENT_USER, wide(RUN_KEY).as_ptr(), wide(VALUE).as_ptr()) } {
                ERROR_FILE_NOT_FOUND => ERROR_SUCCESS,
                other => other,
            }
        };
        if status == ERROR_SUCCESS { Ok(()) } else { Err(std::io::Error::from_raw_os_error(status as i32).to_string()) }
    }
}
