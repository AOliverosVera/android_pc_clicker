mod channel;
mod crypto;
mod input;
mod protocol;
mod server;
mod store;
#[cfg(windows)]
mod tray;

use std::io::{BufRead, ErrorKind};
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::process::ExitCode;
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

use input::SystemInput;
use protocol::{Id, MAX_PC_NAME, sanitize_name};
use server::{Notice, Server};
use store::{Store, hex};

/// How often the loop wakes up when idle (timeouts, console commands).
const TICK: Duration = Duration::from_millis(100);
/// How long pairing stays open after `--pair`, the `pair` command, or a first run.
const PAIRING_WINDOW: Duration = Duration::from_secs(5 * 60);

const USAGE: &str = "\
Usage:
  touchpad-server [--port N] [--pair]   run the server (--pair: accept a new phone)
  touchpad-server --tray [--pair]       run with an icon by the clock instead of this window
                                        (touchpapp.exe starts it this way)
  touchpad-server --list                list paired phones
  touchpad-server --unpair <ID|all>     forget a phone (ID prefix from --list)
  --data-dir <DIR>                      keep pairings in DIR instead of %APPDATA%\\TouchpadServer

While running, type a command and press Enter:
  y / n          accept or reject the pairing request on screen
  pair           accept a new phone for the next 5 minutes
  list           list paired phones
  unpair <ID>    forget a phone (or 'unpair all')";

struct Options {
    mode: Mode,
    data_dir: Option<std::path::PathBuf>,
}

enum Mode {
    Run { port: u16, pair: bool, tray: bool },
    List,
    Unpair(String),
}

enum Command {
    Accept,
    Reject,
    Pair,
    List,
    Unpair(String),
    Help,
    Unknown(String),
}

fn main() -> ExitCode {
    let Options { mode, data_dir } = match parse_args() {
        Ok(options) => options,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::FAILURE;
        }
    };
    // In tray mode nobody sees the console, so errors go to a message box.
    let tray = matches!(mode, Mode::Run { tray: true, .. });
    let fail = |message: String| {
        report_error(tray, &message);
        ExitCode::FAILURE
    };

    let path = match data_dir.map(|d| Ok(d.join("pairings.dat"))).unwrap_or_else(Store::default_path) {
        Ok(p) => p,
        Err(e) => return fail(format!("Cannot locate the settings folder: {e}")),
    };
    let mut store = match Store::load(&path) {
        Ok(s) => s,
        Err(e) => {
            return fail(format!(
                "Cannot read {}: {e}
Delete that file to start over (every phone will need to pair again).",
                path.display()
            ));
        }
    };

    match mode {
        Mode::List => {
            print_phones(&store);
            ExitCode::SUCCESS
        }
        Mode::Unpair(prefix) => match store.remove(&prefix) {
            Ok(names) if names.is_empty() => {
                eprintln!("No paired phone matches \"{prefix}\". See --list.");
                ExitCode::FAILURE
            }
            Ok(names) => {
                println!("Forgot: {}", names.join(", "));
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("Could not save: {e}");
                ExitCode::FAILURE
            }
        },
        Mode::Run { port, pair, tray: false } => run(store, port, pair),
        #[cfg(windows)]
        Mode::Run { port, pair, tray: true } => tray::run(store, port, pair),
        #[cfg(not(windows))]
        Mode::Run { tray: true, .. } => fail("--tray needs Windows.".to_string()),
    }
}

fn report_error(tray: bool, message: &str) {
    #[cfg(windows)]
    if tray {
        tray::error_box(message);
        return;
    }
    let _ = tray;
    eprintln!("{message}");
}

/// Opens the server's UDP socket, waking up every [`TICK`] when idle.
fn bind(port: u16) -> Result<UdpSocket, String> {
    let socket = UdpSocket::bind(("0.0.0.0", port)).map_err(|e| format!("Could not listen on UDP port {port}: {e}"))?;
    socket.set_read_timeout(Some(TICK)).map_err(|e| format!("Could not set up the socket: {e}"))?;
    Ok(socket)
}

/// The name phones see in their list.
fn pc_name() -> String {
    let raw_name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".to_string());
    sanitize_name(&raw_name, MAX_PC_NAME, "PC")
}

/// Waits up to one [`TICK`] for a datagram and answers it.
fn receive(socket: &UdpSocket, server: &mut Server<SystemInput>, buf: &mut [u8]) {
    match socket.recv_from(buf) {
        Ok((len, from)) => {
            // Only devices on the local network may talk to the server.
            if is_local_network(from.ip())
                && let Some(reply) = server.handle(&buf[..len], from, Instant::now())
            {
                let _ = socket.send_to(&reply, from);
            }
        }
        Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
        // Windows reports ICMP "port unreachable" from a vanished phone as a
        // reset on the next receive; it is harmless.
        Err(e) if e.kind() == ErrorKind::ConnectionReset => {}
        Err(e) => eprintln!("receive error: {e}"),
    }
}

fn run(store: Store, port: u16, pair: bool) -> ExitCode {
    let socket = match bind(port) {
        Ok(socket) => socket,
        Err(message) => {
            eprintln!("{message}");
            eprintln!("Is another touchpad-server already running? (Check for the Touchpad icon by the clock.)");
            return ExitCode::FAILURE;
        }
    };

    let name = pc_name();
    println!("Touchpad server v{} — \"{name}\"", env!("CARGO_PKG_VERSION"));
    match primary_lan_ip() {
        Some(ip) => println!("Listening on {ip}:{port} (UDP)."),
        None => println!("Listening on UDP port {port}."),
    }
    if is_elevated() {
        println!("Warning: running as administrator. Only do this if you need to control admin windows;");
        println!("         otherwise run it as a normal user (least privilege).");
    }

    let first_run = store.phones().is_empty();
    let mut server = Server::new(SystemInput::new(), name, store);
    if pair || first_run {
        if first_run {
            println!("No phone is paired yet.");
        }
        server.open_pairing(Instant::now(), PAIRING_WINDOW);
    } else {
        println!("{} paired phone(s). Open the app to connect. Type 'help' for commands.", server.store().phones().len());
    }
    println!();

    let commands = spawn_console_reader();
    let mut prompt: Option<Id> = None;
    let mut buf = [0u8; 1500];

    loop {
        receive(&socket, &mut server, &mut buf);

        let now = Instant::now();
        while let Ok(command) = commands.try_recv() {
            handle_command(command, &mut server, &mut prompt, now);
        }
        server.tick(now);
        for notice in server.drain_notices() {
            print_notice(notice, &mut prompt);
        }
    }
}

fn handle_command(command: Command, server: &mut Server<SystemInput>, prompt: &mut Option<Id>, now: Instant) {
    match command {
        Command::Accept | Command::Reject => {
            let accept = matches!(command, Command::Accept);
            match prompt.take() {
                Some(id) if server.awaiting_operator() == Some(id) => {
                    server.operator_decision(id, accept, now);
                    if accept {
                        println!("Accepted on this PC. Now confirm on the phone, if you haven't yet.");
                    }
                }
                _ => println!("There is no pairing request to answer."),
            }
        }
        Command::Pair => server.open_pairing(now, PAIRING_WINDOW),
        Command::List => print_phones(server.store()),
        Command::Unpair(prefix) => match server.unpair(&prefix) {
            Ok(names) if names.is_empty() => println!("No paired phone matches \"{prefix}\". Type 'list'."),
            Ok(names) => println!("Forgot: {}", names.join(", ")),
            Err(e) => println!("Could not save: {e}"),
        },
        Command::Help => println!("{USAGE}"),
        Command::Unknown(text) => println!("Unknown command \"{text}\". Type 'help'."),
    }
}

fn print_notice(notice: Notice, prompt: &mut Option<Id>) {
    match notice {
        Notice::Connected { phone, addr } => println!("Connected: {phone} ({})", addr.ip()),
        Notice::Disconnected { phone, reason } => println!("Disconnected: {phone} ({reason})"),
        Notice::PairingOpened { until_secs } => {
            println!("Pairing is open for {} minutes: open the app on the new phone and tap Pair.", until_secs / 60)
        }
        Notice::PairingClosed => println!("Pairing closed. Type 'pair' to pair another phone."),
        Notice::PairingRequest { pair_id, phone, code } => {
            *prompt = Some(pair_id);
            println!();
            println!("  \"{phone}\" wants to pair with this PC.");
            println!("  Code: {:03} {:03}", code / 1000, code % 1000);
            println!("  Accept ONLY if your phone shows exactly this code.");
            println!("  Type y and press Enter to accept, or n to reject.");
            println!();
        }
        Notice::Paired { phone } => println!("Paired with {phone}. It will connect automatically from now on."),
        Notice::PairingFailed { reason } => {
            *prompt = None;
            println!("Pairing failed: {reason}.");
        }
    }
}

fn print_phones(store: &Store) {
    if store.phones().is_empty() {
        println!("No paired phones.");
        return;
    }
    println!("Paired phones:");
    for p in store.phones() {
        println!("  {}  {}", hex(&p.id), p.name);
    }
}

fn spawn_console_reader() -> Receiver<Command> {
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            let Ok(line) = line else { break };
            // Piped input may start with a byte-order mark or carry invisible
            // zero-width characters; they must not change what a command means.
            let line = line.replace(['\u{FEFF}', '\u{200B}', '\u{200C}', '\u{200D}'], "");
            let line = line.trim();
            let (word, arg) = line.split_once(' ').unwrap_or((line, ""));
            let command = match word.to_ascii_lowercase().as_str() {
                "" => continue,
                "y" | "yes" => Command::Accept,
                "n" | "no" => Command::Reject,
                "pair" => Command::Pair,
                "list" => Command::List,
                "unpair" if !arg.trim().is_empty() => Command::Unpair(arg.trim().to_string()),
                "help" | "?" => Command::Help,
                _ => Command::Unknown(line.chars().take(40).collect()),
            };
            if tx.send(command).is_err() {
                break;
            }
        }
    });
    rx
}

fn parse_args() -> Result<Options, String> {
    let mut args = std::env::args().skip(1);
    let mut port = protocol::PORT;
    let mut pair = false;
    let mut tray = false;
    let mut mode = None;
    let mut data_dir = None;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--port" | "-p" => {
                let value = args.next().ok_or("--port needs a value")?;
                port = value.parse().map_err(|_| format!("invalid port: {value}"))?;
            }
            "--pair" => pair = true,
            "--tray" => tray = true,
            "--list" => mode = Some(Mode::List),
            "--unpair" => mode = Some(Mode::Unpair(args.next().ok_or("--unpair needs an ID or 'all'")?)),
            "--data-dir" => data_dir = Some(args.next().ok_or("--data-dir needs a folder")?.into()),
            "--help" | "-h" => return Err(USAGE.to_string()),
            other => return Err(format!("unknown argument: {other}\n\n{USAGE}")),
        }
    }
    Ok(Options { mode: mode.unwrap_or(Mode::Run { port, pair, tray }), data_dir })
}

fn is_local_network(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_private() || v4.is_loopback() || v4.is_link_local(),
        IpAddr::V6(v6) => v6.is_loopback() || v6.is_unique_local() || v6.is_unicast_link_local(),
    }
}

/// The address other devices on the LAN use to reach this PC. Connecting a UDP
/// socket sends nothing; it only makes the OS pick the outgoing interface.
fn primary_lan_ip() -> Option<IpAddr> {
    let probe = UdpSocket::bind("0.0.0.0:0").ok()?;
    probe.connect(SocketAddr::from(([192, 0, 2, 1], 9))).ok()?;
    Some(probe.local_addr().ok()?.ip())
}

#[cfg(windows)]
fn is_elevated() -> bool {
    // SAFETY: takes no arguments and only reads the current process token.
    unsafe { windows_sys::Win32::UI::Shell::IsUserAnAdmin() != 0 }
}

#[cfg(not(windows))]
fn is_elevated() -> bool {
    false
}
