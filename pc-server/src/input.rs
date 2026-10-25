//! Mouse and keyboard injection. The server logic talks to [`InputSink`] so it
//! can be tested without touching the real cursor or keyboard.

use crate::protocol::{Button, KeyStroke};

pub trait InputSink {
    fn move_relative(&mut self, dx: i32, dy: i32);
    fn set_button(&mut self, button: Button, pressed: bool);
    /// Presses the modifiers, taps the key, and releases everything again.
    fn key(&mut self, stroke: KeyStroke);
    /// Turns the wheel: `dy > 0` up, `dx > 0` right, 120 per notch.
    fn scroll(&mut self, dx: i32, dy: i32);
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
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::{INPUT, INPUT_0, INPUT_MOUSE, MOUSEINPUT};

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
        send_inputs(&[input]);
    }
}

/// SendInput returns 0 when the input is blocked (e.g. UIPI when an elevated
/// window has focus). Nothing useful can be done about it here.
#[cfg(windows)]
fn send_inputs(inputs: &[windows_sys::Win32::UI::Input::KeyboardAndMouse::INPUT]) {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{INPUT, SendInput};
    if inputs.is_empty() {
        return;
    }
    // SAFETY: the slice is valid for its length, and one call inserts all of
    // it at once, so other input can't land between a modifier and its key.
    unsafe { SendInput(inputs.len() as u32, inputs.as_ptr(), size_of::<INPUT>() as i32) };
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

    fn key(&mut self, stroke: KeyStroke) {
        send_inputs(&keyboard::inputs_for(stroke));
    }

    fn scroll(&mut self, dx: i32, dy: i32) {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
            INPUT, INPUT_0, INPUT_MOUSE, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_WHEEL, MOUSEINPUT,
        };
        let wheel = |amount: i32, flags: u32| INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 {
                // The wheel amount is a signed value carried in mouseData.
                mi: MOUSEINPUT { dx: 0, dy: 0, mouseData: amount as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 },
            },
        };
        let mut inputs = Vec::with_capacity(2);
        if dy != 0 {
            inputs.push(wheel(dy, MOUSEEVENTF_WHEEL));
        }
        if dx != 0 {
            inputs.push(wheel(dx, MOUSEEVENTF_HWHEEL));
        }
        send_inputs(&inputs);
    }
}

#[cfg(windows)]
mod keyboard {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        GetKeyboardLayout, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP,
        KEYEVENTF_UNICODE, MAPVK_VK_TO_VSC, MapVirtualKeyW, VIRTUAL_KEY, VK_BACK, VK_DELETE, VK_DOWN, VK_END,
        VK_ESCAPE, VK_F1, VK_HOME, VK_INSERT, VK_LCONTROL, VK_LEFT, VK_LMENU, VK_LSHIFT, VK_LWIN, VK_NEXT,
        VK_PRIOR, VK_RETURN, VK_RIGHT, VK_TAB, VK_UP, VkKeyScanExW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};

    use crate::protocol::{Key, KeyStroke, Special, modifiers};

    pub fn inputs_for(stroke: KeyStroke) -> Vec<INPUT> {
        let command = stroke.mods & (modifiers::CTRL | modifiers::ALT | modifiers::WIN) != 0;
        match stroke.key {
            // Plain text is typed as the character itself, so it comes out the
            // same whatever keyboard layout the PC uses (ñ, á, ¿, €...).
            Key::Text(c) if !command => {
                let mut units = [0u16; 2];
                c.encode_utf16(&mut units)
                    .iter()
                    .flat_map(|&unit| {
                        [
                            input(0, unit, KEYEVENTF_UNICODE),
                            input(0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP),
                        ]
                    })
                    .collect()
            }
            // A shortcut (Ctrl+C, Win+E...) needs the real key that types this
            // character on the PC's current layout.
            Key::Text(c) => match key_for_char(c) {
                Some((vk, extra_mods)) => tap(stroke.mods | extra_mods, vk, false),
                None => Vec::new(),
            },
            Key::Special(Special::ModifiersOnly) => tap(stroke.mods, 0, false),
            Key::Special(special) => {
                let (vk, extended) = special_key(special);
                tap(stroke.mods, vk, extended)
            }
        }
    }

    /// The modifiers go down first and come up last, around one key tap.
    fn tap(mods: u8, vk: VIRTUAL_KEY, extended: bool) -> Vec<INPUT> {
        let held: Vec<(VIRTUAL_KEY, bool)> = [
            (modifiers::CTRL, VK_LCONTROL, false),
            (modifiers::ALT, VK_LMENU, false),
            (modifiers::SHIFT, VK_LSHIFT, false),
            (modifiers::WIN, VK_LWIN, true),
        ]
        .into_iter()
        .filter(|(bit, _, _)| mods & bit != 0)
        .map(|(_, vk, extended)| (vk, extended))
        .collect();

        let mut out = Vec::with_capacity(held.len() * 2 + 2);
        out.extend(held.iter().map(|&(vk, ext)| virtual_key(vk, ext, false)));
        if vk != 0 {
            out.push(virtual_key(vk, extended, false));
            out.push(virtual_key(vk, extended, true));
        }
        out.extend(held.iter().rev().map(|&(vk, ext)| virtual_key(vk, ext, true)));
        out
    }

    fn special_key(special: Special) -> (VIRTUAL_KEY, bool) {
        match special {
            Special::ModifiersOnly => (0, false),
            Special::Enter => (VK_RETURN, false),
            Special::Backspace => (VK_BACK, false),
            Special::Tab => (VK_TAB, false),
            Special::Escape => (VK_ESCAPE, false),
            // The "extended" flag picks the dedicated keys, not the numeric
            // keypad's versions of them.
            Special::Delete => (VK_DELETE, true),
            Special::Left => (VK_LEFT, true),
            Special::Up => (VK_UP, true),
            Special::Right => (VK_RIGHT, true),
            Special::Down => (VK_DOWN, true),
            Special::Home => (VK_HOME, true),
            Special::End => (VK_END, true),
            Special::PageUp => (VK_PRIOR, true),
            Special::PageDown => (VK_NEXT, true),
            Special::Insert => (VK_INSERT, true),
            Special::F(n) => (VK_F1 + VIRTUAL_KEY::from(n.clamp(1, 12)) - 1, false),
        }
    }

    /// The key (and any Shift/Ctrl/Alt it needs) that types `c` in the
    /// keyboard layout of the window in front.
    fn key_for_char(c: char) -> Option<(VIRTUAL_KEY, u8)> {
        let unit = u16::try_from(c as u32).ok()?;
        // SAFETY: plain value arguments; a null process-id pointer is allowed.
        let scan = unsafe {
            let thread = GetWindowThreadProcessId(GetForegroundWindow(), std::ptr::null_mut());
            VkKeyScanExW(unit, GetKeyboardLayout(thread))
        };
        if scan == -1 {
            return None;
        }
        let vk = (scan & 0xFF) as VIRTUAL_KEY;
        let state = (scan >> 8) as u8;
        let mut mods = 0;
        if state & 1 != 0 {
            mods |= modifiers::SHIFT;
        }
        if state & 2 != 0 {
            mods |= modifiers::CTRL;
        }
        if state & 4 != 0 {
            mods |= modifiers::ALT;
        }
        Some((vk, mods))
    }

    fn virtual_key(vk: VIRTUAL_KEY, extended: bool, up: bool) -> INPUT {
        // SAFETY: a pure lookup.
        let scan = unsafe { MapVirtualKeyW(vk.into(), MAPVK_VK_TO_VSC) } as u16;
        let mut flags = if extended { KEYEVENTF_EXTENDEDKEY } else { 0 };
        if up {
            flags |= KEYEVENTF_KEYUP;
        }
        input(vk, scan, flags)
    }

    fn input(vk: VIRTUAL_KEY, scan: u16, flags: u32) -> INPUT {
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// (virtual key, flags) of each generated event; nothing is sent.
        fn events(stroke: KeyStroke) -> Vec<(VIRTUAL_KEY, u16, u32)> {
            inputs_for(stroke)
                .iter()
                // SAFETY: inputs_for only builds keyboard inputs.
                .map(|i| unsafe { (i.Anonymous.ki.wVk, i.Anonymous.ki.wScan, i.Anonymous.ki.dwFlags) })
                .map(|(vk, scan, flags)| (vk, if vk == 0 { scan } else { 0 }, flags))
                .collect()
        }

        #[test]
        fn text_is_typed_as_unicode_including_surrogate_pairs() {
            let down = KEYEVENTF_UNICODE;
            let up = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
            assert_eq!(events(KeyStroke { mods: 0, key: Key::Text('\u{f1}') }), vec![(0, 0xF1, down), (0, 0xF1, up)]);
            assert_eq!(
                events(KeyStroke { mods: 0, key: Key::Text('\u{1F600}') }),
                vec![(0, 0xD83D, down), (0, 0xD83D, up), (0, 0xDE00, down), (0, 0xDE00, up)]
            );
        }

        #[test]
        fn modifiers_wrap_the_key_and_are_always_released() {
            let stroke = KeyStroke { mods: modifiers::CTRL | modifiers::SHIFT, key: Key::Special(Special::Left) };
            let ext = KEYEVENTF_EXTENDEDKEY;
            assert_eq!(
                events(stroke),
                vec![
                    (VK_LCONTROL, 0, 0),
                    (VK_LSHIFT, 0, 0),
                    (VK_LEFT, 0, ext),
                    (VK_LEFT, 0, ext | KEYEVENTF_KEYUP),
                    (VK_LSHIFT, 0, KEYEVENTF_KEYUP),
                    (VK_LCONTROL, 0, KEYEVENTF_KEYUP),
                ]
            );
            let win_alone = KeyStroke { mods: modifiers::WIN, key: Key::Special(Special::ModifiersOnly) };
            assert_eq!(events(win_alone), vec![(VK_LWIN, 0, ext), (VK_LWIN, 0, ext | KEYEVENTF_KEYUP)]);
            let f12 = KeyStroke { mods: 0, key: Key::Special(Special::F(12)) };
            assert_eq!(events(f12)[0].0, VK_F1 + 11);
        }
    }
}

/// Non-Windows builds compile (so `cargo test` works anywhere) but only log.
#[cfg(not(windows))]
pub struct SystemInput;

#[cfg(not(windows))]
impl SystemInput {
    pub fn new() -> Self {
        eprintln!("warning: input injection is only implemented on Windows; input will be logged");
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

    fn key(&mut self, stroke: KeyStroke) {
        println!("key {:#04x} {:?}", stroke.mods, stroke.key);
    }

    fn scroll(&mut self, dx: i32, dy: i32) {
        println!("scroll {dx} {dy}");
    }
}
