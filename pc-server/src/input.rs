//! Mouse injection. The server logic talks to [`InputSink`] so it can be tested
//! without touching the real cursor.

use crate::protocol::Button;

pub trait InputSink {
    fn move_relative(&mut self, dx: i32, dy: i32);
    fn set_button(&mut self, button: Button, pressed: bool);
}

/// Injects input through the Win32 `SendInput` API, the same path a physical
/// mouse driver's events take, so Windows applies the user's pointer speed and
/// "Enhance pointer precision" settings to relative moves.
#[cfg(windows)]
pub struct SystemInput;

#[cfg(windows)]
impl SystemInput {
    pub fn new() -> Self {
        SystemInput
    }

    fn send(dx: i32, dy: i32, flags: u32) {
        use std::mem::size_of;
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
            INPUT, INPUT_0, INPUT_MOUSE, MOUSEINPUT, SendInput,
        };

        let input = INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 {
                mi: MOUSEINPUT {
                    dx,
                    dy,
                    mouseData: 0,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        // SendInput returns 0 when the input is blocked (e.g. UIPI when an
        // elevated window has focus). Nothing useful can be done about it here.
        unsafe { SendInput(1, &input, size_of::<INPUT>() as i32) };
    }
}

#[cfg(windows)]
impl InputSink for SystemInput {
    fn move_relative(&mut self, dx: i32, dy: i32) {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::MOUSEEVENTF_MOVE;
        Self::send(dx, dy, MOUSEEVENTF_MOVE);
    }

    fn set_button(&mut self, button: Button, pressed: bool) {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
            MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP,
        };
        let flags = match (button, pressed) {
            (Button::Left, true) => MOUSEEVENTF_LEFTDOWN,
            (Button::Left, false) => MOUSEEVENTF_LEFTUP,
            (Button::Right, true) => MOUSEEVENTF_RIGHTDOWN,
            (Button::Right, false) => MOUSEEVENTF_RIGHTUP,
        };
        Self::send(0, 0, flags);
    }
}

/// Non-Windows builds compile (so `cargo test` works anywhere) but only log.
#[cfg(not(windows))]
pub struct SystemInput;

#[cfg(not(windows))]
impl SystemInput {
    pub fn new() -> Self {
        eprintln!("warning: mouse injection is only implemented on Windows; input will be logged");
        SystemInput
    }
}

#[cfg(not(windows))]
impl InputSink for SystemInput {
    fn move_relative(&mut self, dx: i32, dy: i32) {
        println!("move {dx} {dy}");
    }

    fn set_button(&mut self, button: Button, pressed: bool) {
        println!("{button:?} {}", if pressed { "down" } else { "up" });
    }
}
