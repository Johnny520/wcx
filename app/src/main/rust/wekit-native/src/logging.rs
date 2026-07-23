#[cfg(target_os = "android")]
use std::ffi::CString;

#[cfg(target_os = "android")]
use libc::c_char;
use libc::c_int;

// ─────────────────────────────────────────────────────────────────────────────
// Android logging
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(target_os = "android")]
unsafe extern "C" {
    /// Non-variadic Android log function — safe to call from a signal handler.
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

pub const ANDROID_LOG_INFO: c_int = 4;
pub const ANDROID_LOG_WARN: c_int = 5;
pub const ANDROID_LOG_ERROR: c_int = 6;

#[cfg(target_os = "android")]
static LOG_TAG: &std::ffi::CStr = c"WeKit";

#[cfg(target_os = "android")]
pub fn android_log(prio: c_int, msg: &str) {
    // Prepend "NativeLib: " to the message
    let full_msg = format!("NativeLib: {}", msg);
    // CString handles null termination and interior-null sanitisation
    let Ok(cmsg) = CString::new(full_msg) else {
        return;
    };
    unsafe {
        __android_log_write(prio, LOG_TAG.as_ptr(), cmsg.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
pub fn android_log(_prio: c_int, msg: &str) {
    eprintln!("NativeLib: {msg}");
}

#[macro_export]
macro_rules! logi {
    ($($t:tt)*) => { $crate::logging::android_log($crate::logging::ANDROID_LOG_INFO, &format!($($t)*)) };
}

#[macro_export]
macro_rules! logw {
    ($($t:tt)*) => { $crate::logging::android_log($crate::logging::ANDROID_LOG_WARN, &format!($($t)*)) };
}

#[macro_export]
macro_rules! loge {
    ($($t:tt)*) => { $crate::logging::android_log($crate::logging::ANDROID_LOG_ERROR, &format!($($t)*)) };
}
