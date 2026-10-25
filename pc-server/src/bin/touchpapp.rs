//! Starts `touchpad-server.exe --tray` with no console window, then exits.
//!
//! This is what "Start with Windows" runs. The server itself stays a console
//! program (so its commands keep working in a terminal, and the firewall rule
//! for it still applies); this launcher is a window-less program, so nothing
//! flashes on screen at login. Any arguments are passed on (e.g. `--pair`).

#![cfg_attr(windows, windows_subsystem = "windows")]

use std::process::ExitCode;

#[cfg(windows)]
fn main() -> ExitCode {
    use std::os::windows::process::CommandExt;
    use std::process::{Command, Stdio};

    /// Gives the console program a console with no window.
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    let server = match std::env::current_exe() {
        Ok(exe) => exe.with_file_name("touchpad-server.exe"),
        Err(e) => return fail(&format!("Cannot find where Touchpad is installed: {e}")),
    };
    let started = Command::new(&server)
        .arg("--tray")
        .args(std::env::args_os().skip(1))
        .current_dir(server.parent().unwrap_or(std::path::Path::new(".")))
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(CREATE_NO_WINDOW)
        .spawn();
    match started {
        Ok(_) => ExitCode::SUCCESS,
        Err(e) => fail(&format!(
            "Could not start {}: {e}\n\nKeep touchpapp.exe in the same folder as touchpad-server.exe.",
            server.display()
        )),
    }
}

#[cfg(windows)]
fn fail(text: &str) -> ExitCode {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MessageBoxW};

    let text: Vec<u16> = text.encode_utf16().chain(Some(0)).collect();
    let caption: Vec<u16> = "Touchpad".encode_utf16().chain(Some(0)).collect();
    // SAFETY: both strings are NUL-terminated and outlive the call.
    unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), caption.as_ptr(), MB_ICONERROR) };
    ExitCode::FAILURE
}

#[cfg(not(windows))]
fn main() -> ExitCode {
    eprintln!("touchpapp is for Windows. Run touchpad-server instead.");
    ExitCode::FAILURE
}
