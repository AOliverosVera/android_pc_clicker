//! Paired phones, persisted in `%APPDATA%\TouchpadServer\pairings.dat`.
//!
//! On Windows the file is encrypted with DPAPI under the current user's
//! credentials, so copying it to another account or machine yields nothing
//! usable. The format inside is plain text, one record per line, with every
//! field hex-encoded so no name can break the parsing.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use zeroize::{Zeroize, Zeroizing};

use crate::crypto::{Key32, random};
use crate::protocol::Id;

const MAGIC_LINE: &str = "touchpad-pairings v1";
pub const MAX_PHONES: usize = 16;

pub struct PairedPhone {
    pub id: Id,
    pub name: String,
    pub key: Key32,
}

pub struct Store {
    pub server_id: Id,
    phones: Vec<PairedPhone>,
    path: Option<PathBuf>,
}

impl Store {
    /// A store that lives only in memory (tests).
    #[cfg(test)]
    pub fn in_memory() -> Self {
        Store { server_id: random(), phones: Vec::new(), path: None }
    }

    pub fn default_path() -> io::Result<PathBuf> {
        let base = std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
            .ok_or_else(|| io::Error::other("neither APPDATA nor HOME is set"))?;
        Ok(base.join("TouchpadServer").join("pairings.dat"))
    }

    /// Loads the store, creating a new one (with a new server id) if the file
    /// does not exist yet.
    pub fn load(path: &Path) -> io::Result<Self> {
        let blob = match fs::read(path) {
            Ok(blob) => blob,
            Err(e) if e.kind() == io::ErrorKind::NotFound => {
                let store = Store { server_id: random(), phones: Vec::new(), path: Some(path.to_path_buf()) };
                store.save()?;
                return Ok(store);
            }
            Err(e) => return Err(e),
        };
        let text = Zeroizing::new(unprotect(&blob)?);
        let text = std::str::from_utf8(&text).map_err(|_| corrupt())?;
        let mut lines = text.lines();
        if lines.next() != Some(MAGIC_LINE) {
            return Err(corrupt());
        }
        let server_id = parse_server(lines.next()).ok_or_else(corrupt)?;
        let mut phones = Vec::new();
        for line in lines {
            phones.push(parse_phone(line).ok_or_else(corrupt)?);
        }
        Ok(Store { server_id, phones, path: Some(path.to_path_buf()) })
    }

    pub fn phones(&self) -> &[PairedPhone] {
        &self.phones
    }

    pub fn find(&self, id: &Id) -> Option<&PairedPhone> {
        self.phones.iter().find(|p| &p.id == id)
    }

    pub fn add(&mut self, phone: PairedPhone) -> io::Result<()> {
        self.phones.retain(|p| p.id != phone.id);
        if self.phones.len() >= MAX_PHONES {
            self.phones.remove(0); // forget the oldest
        }
        self.phones.push(phone);
        self.save()
    }

    /// Removes phones whose id starts with `prefix` (hex), or all with "all".
    /// Returns the names removed.
    pub fn remove(&mut self, prefix: &str) -> io::Result<Vec<String>> {
        let prefix = prefix.to_ascii_lowercase();
        let mut removed = Vec::new();
        self.phones.retain(|p| {
            let hit = prefix == "all" || (!prefix.is_empty() && hex(&p.id).starts_with(&prefix));
            if hit {
                removed.push(p.name.clone());
            }
            !hit
        });
        if !removed.is_empty() {
            self.save()?;
        }
        Ok(removed)
    }

    fn save(&self) -> io::Result<()> {
        let Some(path) = &self.path else { return Ok(()) };
        let mut text = Zeroizing::new(String::new());
        text.push_str(MAGIC_LINE);
        text.push_str("\nserver ");
        text.push_str(&hex(&self.server_id));
        for p in &self.phones {
            let mut key_hex = hex(&p.key[..]);
            text.push_str(&format!("\nphone {} {} {}", hex(&p.id), key_hex, hex(p.name.as_bytes())));
            key_hex.zeroize();
        }
        let blob = protect(text.as_bytes())?;
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir)?;
        }
        // Write-then-rename so a crash never leaves a half-written file.
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, &blob)?;
        restrict_permissions(&tmp)?;
        fs::rename(&tmp, path)
    }
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok()).collect()
}

fn corrupt() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "the pairings file is corrupt or belongs to another Windows user")
}

fn parse_server(line: Option<&str>) -> Option<Id> {
    unhex(line?.strip_prefix("server ")?)?.try_into().ok()
}

fn parse_phone(line: &str) -> Option<PairedPhone> {
    let mut parts = line.strip_prefix("phone ")?.split(' ');
    let id: Id = unhex(parts.next()?)?.try_into().ok()?;
    let mut key_bytes = Zeroizing::new(unhex(parts.next()?)?);
    let name = String::from_utf8(unhex(parts.next()?)?).ok()?;
    if parts.next().is_some() || key_bytes.len() != 32 {
        return None;
    }
    let mut key = Zeroizing::new([0u8; 32]);
    key.copy_from_slice(&key_bytes);
    key_bytes.zeroize();
    Some(PairedPhone { id, name, key })
}

// ---------------------------------------------------------------- DPAPI

#[cfg(windows)]
const ENTROPY: &[u8] = b"touchpad-server/pairings/v1";

#[cfg(windows)]
fn protect(data: &[u8]) -> io::Result<Vec<u8>> {
    dpapi(data, true)
}

#[cfg(windows)]
fn unprotect(data: &[u8]) -> io::Result<Vec<u8>> {
    dpapi(data, false).map_err(|_| corrupt())
}

#[cfg(windows)]
fn dpapi(data: &[u8], encrypt: bool) -> io::Result<Vec<u8>> {
    use windows_sys::Win32::Foundation::{HLOCAL, LocalFree};
    use windows_sys::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };

    let input = CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr() as *mut u8 };
    let entropy = CRYPT_INTEGER_BLOB { cbData: ENTROPY.len() as u32, pbData: ENTROPY.as_ptr() as *mut u8 };
    let mut output = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
    // SAFETY: all blobs point to live buffers of the stated length; on
    // success Windows allocates `output`, which is copied and then freed.
    let ok = unsafe {
        if encrypt {
            CryptProtectData(&input, std::ptr::null(), &entropy, std::ptr::null(), std::ptr::null(), CRYPTPROTECT_UI_FORBIDDEN, &mut output)
        } else {
            CryptUnprotectData(&input, std::ptr::null_mut(), &entropy, std::ptr::null(), std::ptr::null(), CRYPTPROTECT_UI_FORBIDDEN, &mut output)
        }
    };
    if ok == 0 {
        return Err(io::Error::last_os_error());
    }
    let result = unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe {
        // Wipe Windows' copy of the plaintext before freeing it.
        std::ptr::write_bytes(output.pbData, 0, output.cbData as usize);
        LocalFree(output.pbData as HLOCAL);
    }
    Ok(result)
}

#[cfg(windows)]
fn restrict_permissions(_path: &Path) -> io::Result<()> {
    // %APPDATA% is already private to the user, and the content is DPAPI-encrypted.
    Ok(())
}

#[cfg(not(windows))]
fn protect(data: &[u8]) -> io::Result<Vec<u8>> {
    Ok(data.to_vec())
}

#[cfg(not(windows))]
fn unprotect(data: &[u8]) -> io::Result<Vec<u8>> {
    Ok(data.to_vec())
}

#[cfg(not(windows))]
fn restrict_permissions(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("touchpad-store-test-{}-{}", name, hex(&random::<4>())));
        dir.join("pairings.dat")
    }

    fn phone(id: u8, name: &str) -> PairedPhone {
        PairedPhone { id: [id; 8], name: name.into(), key: Zeroizing::new([id; 32]) }
    }

    #[test]
    fn persists_and_reloads() {
        let path = temp_path("reload");
        let mut store = Store::load(&path).unwrap();
        let server_id = store.server_id;
        store.add(phone(1, "Pixel 8")).unwrap();
        store.add(phone(2, "nombre con espacios ñ")).unwrap();

        let again = Store::load(&path).unwrap();
        assert_eq!(again.server_id, server_id);
        assert_eq!(again.phones().len(), 2);
        assert_eq!(again.find(&[2; 8]).unwrap().name, "nombre con espacios ñ");
        assert_eq!(*again.find(&[1; 8]).unwrap().key, [1u8; 32]);
        fs::remove_dir_all(path.parent().unwrap()).ok();
    }

    #[test]
    fn file_does_not_contain_the_key_in_the_clear() {
        let path = temp_path("opaque");
        let mut store = Store::load(&path).unwrap();
        store.add(phone(0x5A, "x")).unwrap();
        let raw = fs::read(&path).unwrap();
        let key_hex = hex(&[0x5A; 32]);
        assert!(!String::from_utf8_lossy(&raw).contains(&key_hex), "key is readable on disk");
        fs::remove_dir_all(path.parent().unwrap()).ok();
    }

    #[test]
    fn remove_by_prefix_and_all() {
        let path = temp_path("remove");
        let mut store = Store::load(&path).unwrap();
        store.add(phone(0xAB, "a")).unwrap();
        store.add(phone(0xCD, "b")).unwrap();
        assert_eq!(store.remove("ab").unwrap(), vec!["a".to_string()]);
        assert!(store.remove("").unwrap().is_empty(), "empty prefix must not match everything");
        assert_eq!(store.remove("all").unwrap(), vec!["b".to_string()]);
        assert!(Store::load(&path).unwrap().phones().is_empty());
        fs::remove_dir_all(path.parent().unwrap()).ok();
    }

    #[test]
    fn tampered_file_is_rejected() {
        let path = temp_path("tamper");
        let mut store = Store::load(&path).unwrap();
        store.add(phone(1, "a")).unwrap();
        let mut raw = fs::read(&path).unwrap();
        let last = raw.len() - 1;
        raw[last] ^= 0xFF;
        fs::write(&path, raw).unwrap();
        assert!(Store::load(&path).is_err());
        fs::remove_dir_all(path.parent().unwrap()).ok();
    }

    #[test]
    fn keeps_at_most_max_phones() {
        let mut store = Store::in_memory();
        for i in 0..(MAX_PHONES as u8 + 3) {
            store.add(phone(i, "p")).unwrap();
        }
        assert_eq!(store.phones().len(), MAX_PHONES);
        assert!(store.find(&[0; 8]).is_none(), "oldest was dropped");
    }
}
