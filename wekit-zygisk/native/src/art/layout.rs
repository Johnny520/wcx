#![allow(dead_code)]
// art/layout.rs — ArtMethod layout probing and access-flags constants
//
// Detects the `ArtMethod` struct size and field offsets for the running Android
// version, and attempts to read the `kAccPreCompiled` flag value from
// `libart.so` at runtime.  These values are required before any hook can be
// installed.

use crate::art::elf::find_symbol_in_file;
use crate::{logi, logw};
use std::sync::atomic::{AtomicU32, Ordering};

// acc_flags bit constants (match ART source)
pub const ACC_PUBLIC: u32 = 0x0001;
pub const ACC_PRIVATE: u32 = 0x0002;
pub const ACC_PROTECTED: u32 = 0x0004;
pub const ACC_COMPILE_DONT_BOTHER: u32 = 0x02000000;
pub const ACC_FAST_INTERPRETER: u32 = 0x40000000; // kAccFastInterpreterToInterpreterInvoke
pub static G_ACC_PRECOMPILED: AtomicU32 = AtomicU32::new(0x00800000);

#[derive(Clone, Copy, Debug)]
pub struct ArtLayout {
    pub method_size: usize,
    pub entry_point_offset: usize,
    pub access_flags_offset: usize,
}

pub fn android_api_level() -> u32 {
    #[cfg(target_os = "android")]
    {
        let mut value = [0 as libc::c_char; libc::PROP_VALUE_MAX as usize];
        let name = b"ro.build.version.sdk\0";
        let len = unsafe {
            libc::__system_property_get(name.as_ptr().cast::<libc::c_char>(), value.as_mut_ptr())
        };
        if len > 0 {
            let bytes =
                unsafe { std::slice::from_raw_parts(value.as_ptr().cast::<u8>(), len as usize) };
            if let Ok(s) = std::str::from_utf8(bytes)
                && let Ok(api) = s.parse()
            {
                return api;
            }
        }
    }

    // Keep a filesystem fallback for unusual Android environments and host tests.
    if let Ok(s) = std::fs::read_to_string("/system/build.prop") {
        for line in s.lines() {
            if let Some(val) = line.strip_prefix("ro.build.version.sdk=")
                && let Ok(n) = val.trim().parse()
            {
                return n;
            }
        }
    }

    // All API levels >= 31 use the same masks below. Prefer those current masks
    // if both standard detection paths are unavailable.
    logw!("Zygisk: unable to detect Android API level, assuming API 35");
    35
}

fn method_size_for_api(_api: u32) -> usize {
    // ArtMethod is 40 bytes on all Android versions we target (API 28+, both LP32 and LP64)
    40
}

pub fn detect(art_base: usize, art_path: &str) -> Option<ArtLayout> {
    let api = android_api_level();
    let method_size = method_size_for_api(api);

    // entry_point_offset: offset of quick_code entry_point within ArtMethod
    // LP64: 32 bytes, LP32: 24 bytes
    #[cfg(target_pointer_width = "64")]
    let entry_point_offset = 32usize;
    #[cfg(target_pointer_width = "32")]
    let entry_point_offset = 24usize;

    // access_flags is always at offset 4
    let access_flags_offset = 4usize;

    // Try to detect G_ACC_PRECOMPILED from libart symbols
    if let Some(sym_off) = find_symbol_in_file(art_path, "kAccPreCompiled") {
        let addr = (art_base + sym_off) as *const u32;
        let val = unsafe { addr.read_volatile() };
        G_ACC_PRECOMPILED.store(val, Ordering::Relaxed);
        logi!("Zygisk: kAccPreCompiled = {val:#010x}");
    }

    logi!("Zygisk: ArtLayout API={api} method_size={method_size} ep_off={entry_point_offset}");
    Some(ArtLayout {
        method_size,
        entry_point_offset,
        access_flags_offset,
    })
}
