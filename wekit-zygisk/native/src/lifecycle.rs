// lifecycle — Zygisk module lifecycle callbacks
//
// Implements the three specialization hooks called by the Zygisk framework:
// `preAppSpecialize` (allow-list + companion IPC + resource acquisition),
// `postAppSpecialize` (APK/DEX copy, classloader bootstrap), and
// `preServerSpecialize` (dlclose — module does not inject into system_server).

use crate::protocol::{
    COMPANION_ENABLED, COMPANION_REQUEST_ENABLED, COMPANION_REQUEST_TELEGRAM_SESSION,
    read_u8_from_fd, write_string_to_fd, write_u8_to_fd,
};
use crate::zygisk::{ApiTable, AppSpecializeArgs, DLCLOSE_MODULE_LIBRARY, ServerSpecializeArgs};
use crate::{loge, logi};
use jni::sys::{JNIEnv as RawJNIEnv, jobject, jstring};
use libc::{gid_t, uid_t};
use std::{
    ffi::{CStr, c_char},
    os::{
        fd::{FromRawFd, OwnedFd},
        unix::io::{AsRawFd, RawFd},
    },
};

pub struct WeKitModule {
    pub api: *mut ApiTable,
    pub env: *mut RawJNIEnv,
    // filled in preAppSpecialize
    pub module_dir_fd: Option<OwnedFd>,
    pub app_uid: uid_t,
    pub app_gid: gid_t,
    pub data_dir: String,
    pub process_name: String,
    pub dex_names: Vec<String>,
    pub telegram_socket_name: Option<String>,
    pub enabled: bool,
    // filled in postAppSpecialize
    pub module_classloader: Option<jobject>,
}

impl WeKitModule {
    pub fn new(api: *mut ApiTable, env: *mut RawJNIEnv) -> Self {
        Self {
            api,
            env,
            module_dir_fd: None,
            app_uid: 0,
            app_gid: 0,
            data_dir: String::new(),
            process_name: String::new(),
            dex_names: Vec::new(),
            telegram_socket_name: None,
            enabled: false,
            module_classloader: None,
        }
    }
}

// Helper: dereference a C++ reference-field (stored as *mut T) and read the jstring.
unsafe fn read_jstring(env: *mut RawJNIEnv, field_ptr: *mut jstring) -> Option<String> {
    if field_ptr.is_null() {
        return None;
    }
    let jstr = *field_ptr;
    if jstr.is_null() {
        return None;
    }
    let fns = *env;
    let chars = ((*fns).v1_6.GetStringUTFChars)(env, jstr, std::ptr::null_mut());
    if chars.is_null() {
        return None;
    }
    let s = CStr::from_ptr(chars as *const c_char)
        .to_string_lossy()
        .into_owned();
    ((*fns).v1_6.ReleaseStringUTFChars)(env, jstr, chars);
    Some(s)
}

fn send_check_request(api: *mut ApiTable, uid: i32, process_name: &str) -> u8 {
    let fd = unsafe { (*api).connect_companion() };
    if fd < 0 {
        return 2; // COMPANION_ERROR
    }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_ENABLED);
    // Write uid as i32 little-endian
    let uid_bytes = uid.to_ne_bytes();
    unsafe {
        libc::write(fd, uid_bytes.as_ptr().cast(), 4);
    }
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(2);
    unsafe { libc::close(fd) };
    status
}

fn negotiate_telegram_socket(api: *mut ApiTable, uid: i32, process_name: &str) -> Option<String> {
    let fd = unsafe { (*api).connect_companion() };
    if fd < 0 {
        return None;
    }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_TELEGRAM_SESSION);
    let uid_bytes = uid.to_ne_bytes();
    unsafe {
        libc::write(fd, uid_bytes.as_ptr().cast(), 4);
    }
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(2);
    if status != COMPANION_ENABLED {
        unsafe { libc::close(fd) };
        return None;
    }
    let name = crate::protocol::read_string_from_fd(fd).ok();
    unsafe { libc::close(fd) };
    name
}

/// Read dex.list and validate sequential ordering (classes.dex, classes2.dex, ...).
fn read_dex_list(mod_fd: RawFd, rel_path: &str) -> Vec<String> {
    let path_c = match std::ffi::CString::new(rel_path) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let fd = unsafe { libc::openat(mod_fd, path_c.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return Vec::new();
    }
    let mut bytes = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n <= 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n as usize]);
    }
    unsafe { libc::close(fd) };
    let names: Vec<String> = String::from_utf8_lossy(&bytes)
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(str::to_owned)
        .collect();
    // Verify names are classes.dex, classes2.dex, ... in contiguous order
    for (i, name) in names.iter().enumerate() {
        let expected = (i + 1) as u32;
        let order = dex_name_order(name);
        if order != Some(expected) {
            loge!(
                "Zygisk: dex.list entry '{}' is out of order (expected {})",
                name,
                expected
            );
            return Vec::new();
        }
    }
    names
}

/// Map a dex file name to its numeric order (classes.dex → 1, classes2.dex → 2, …).
fn dex_name_order(name: &str) -> Option<u32> {
    if name == "classes.dex" {
        return Some(1);
    }
    let prefix = "classes";
    let suffix = ".dex";
    if !name.starts_with(prefix) || !name.ends_with(suffix) {
        return None;
    }
    let middle = &name[prefix.len()..name.len() - suffix.len()];
    if middle.is_empty() {
        return None;
    }
    middle.parse::<u32>().ok().filter(|&n| n >= 2)
}

// ── Lifecycle callbacks ───────────────────────────────────────────────────────

pub unsafe fn do_pre_app_specialize(module: &mut WeKitModule, args: *mut AppSpecializeArgs) {
    let nice_name = match read_jstring(module.env, (*args).nice_name) {
        Some(s) if !s.is_empty() && s.len() <= 255 => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let app_data_dir = match read_jstring(module.env, (*args).app_data_dir) {
        Some(s) if !s.is_empty() => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let uid = *(*args).uid;
    let gid = *(*args).gid;

    let status = send_check_request(module.api, uid, &nice_name);
    if status != COMPANION_ENABLED {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    let mod_fd = (*module.api).get_module_dir();
    if mod_fd < 0 {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }
    // SAFETY: mod_fd is a valid fd returned by Zygisk API
    module.module_dir_fd = Some(OwnedFd::from_raw_fd(mod_fd));
    module.app_uid = uid as uid_t;
    module.app_gid = gid as gid_t;
    module.data_dir = app_data_dir;

    module.process_name = nice_name.clone();
    module.dex_names = read_dex_list(mod_fd, "payload/dex.list");
    if module.dex_names.is_empty() {
        loge!("Zygisk: empty or missing payload/dex.list");
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // Non-isolated processes: negotiate Telegram socket, write to global
    if !nice_name.contains(':')
        && let Some(name) = negotiate_telegram_socket(module.api, uid, &nice_name)
    {
        *crate::TELEGRAM_SOCKET_NAME.lock().unwrap() = name.clone();
        module.telegram_socket_name = Some(name);
        logi!("Zygisk: retained Telegram root companion socket for {nice_name}");
    }

    module.enabled = true;
    logi!("Zygisk: preAppSpecialize OK for {nice_name}");
}

pub unsafe fn do_post_app_specialize(module: &mut WeKitModule, _args: *const AppSpecializeArgs) {
    if !module.enabled {
        return;
    }
    let mod_fd = match module.module_dir_fd.as_ref() {
        Some(f) => f.as_raw_fd(),
        None => return,
    };
    let data_dir = module.data_dir.clone();
    let uid = module.app_uid;
    let gid = module.app_gid;
    crate::payload::ensure_dir(&format!("{data_dir}/files"), uid, gid);
    crate::payload::ensure_dir(&format!("{data_dir}/files/mmkv"), uid, gid);

    // Copy APK
    let apk_dst = format!("{data_dir}/files/mmkv/.wekit-bootstrap.apk");
    if !crate::payload::copy_module_file(
        mod_fd,
        "payload/wekit.apk",
        &apk_dst,
        uid,
        gid,
        256 * 1024 * 1024,
    ) {
        loge!("Zygisk: failed to copy wekit.apk");
        return;
    }

    // Copy DEX files and read them into memory
    // dex_names already includes the .dex extension.
    let mut dex_bufs: Vec<Vec<u8>> = Vec::new();
    for name in module.dex_names.clone() {
        let dst = format!("{data_dir}/files/mmkv/.wekit-bootstrap-{name}");
        if !crate::payload::copy_module_file(
            mod_fd,
            &format!("payload/{name}"),
            &dst,
            uid,
            gid,
            64 * 1024 * 1024,
        ) {
            loge!("Zygisk: failed to copy DEX payload {name}");
            return;
        }
        if let Some(b) = crate::payload::read_file(&dst) {
            dex_bufs.push(b);
        }
    }

    // Build InMemoryDexClassLoader
    let fns = *module.env;
    let sys_cl_class = ((*fns).v1_6.FindClass)(module.env, c"java/lang/ClassLoader".as_ptr());
    let get_sys_id = ((*fns).v1_6.GetStaticMethodID)(
        module.env,
        sys_cl_class,
        c"getSystemClassLoader".as_ptr(),
        c"()Ljava/lang/ClassLoader;".as_ptr(),
    );
    let parent = ((*fns).v1_6.CallStaticObjectMethod)(module.env, sys_cl_class, get_sys_id);
    let cl = crate::payload::build_dex_classloader(module.env, &dex_bufs, parent);
    if cl.is_null() {
        loge!("Zygisk: failed to build InMemoryDexClassLoader");
        return;
    }

    // Close module dir fd
    module.module_dir_fd = None;

    // Load ZygiskEntry class
    let entry_name = "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry";
    let entry_cls = crate::natives::load_class_from_loader(module.env, cl, entry_name);
    if entry_cls.is_null() {
        loge!("Zygisk: ZygiskEntry class not found");
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Register ZygiskEntry native methods
    if !crate::natives::register_entry_natives(module.env, cl) {
        loge!("Zygisk: failed to register ZygiskEntry bootstrap JNI");
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }

    // Call ZygiskEntry.init(processName, dataDir, apkPath)
    let init_mid = ((*fns).v1_6.GetStaticMethodID)(
        module.env,
        entry_cls,
        c"init".as_ptr(),
        c"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V".as_ptr(),
    );
    if init_mid.is_null() {
        loge!("Zygisk: ZygiskEntry.init method not found");
        ((*fns).v1_6.ExceptionClear)(module.env);
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    let process_name_c = std::ffi::CString::new(module.process_name.as_str()).unwrap_or_default();
    let data_dir_c = std::ffi::CString::new(data_dir.as_str()).unwrap_or_default();
    let apk_path_c = std::ffi::CString::new(apk_dst.as_str()).unwrap_or_default();
    let j_process = ((*fns).v1_6.NewStringUTF)(module.env, process_name_c.as_ptr());
    let j_data = ((*fns).v1_6.NewStringUTF)(module.env, data_dir_c.as_ptr());
    let j_apk = ((*fns).v1_6.NewStringUTF)(module.env, apk_path_c.as_ptr());
    if j_process.is_null()
        || j_data.is_null()
        || j_apk.is_null()
        || ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE
    {
        ((*fns).v1_6.ExceptionClear)(module.env);
        loge!("Zygisk: ZygiskEntry.init argument allocation failed");
        for j in [j_process, j_data, j_apk].iter().filter(|&&p| !p.is_null()) {
            ((*fns).v1_6.DeleteLocalRef)(module.env, *j);
        }
        ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    ((*fns).v1_6.CallStaticVoidMethod)(module.env, entry_cls, init_mid, j_process, j_data, j_apk);
    let failed = ((*fns).v1_6.ExceptionCheck)(module.env) != jni::sys::JNI_FALSE;
    if failed {
        ((*fns).v1_6.ExceptionDescribe)(module.env);
        ((*fns).v1_6.ExceptionClear)(module.env);
        loge!("Zygisk: ZygiskEntry.init failed");
    } else {
        logi!(
            "Zygisk: ZygiskEntry.init completed for {}",
            module.process_name
        );
    }
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_process);
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_data);
    ((*fns).v1_6.DeleteLocalRef)(module.env, j_apk);
    ((*fns).v1_6.DeleteLocalRef)(module.env, entry_cls);
    if failed {
        ((*fns).v1_6.DeleteGlobalRef)(module.env, cl);
        return;
    }
    // Keep classloader alive for hook bridge class resolution
    module.module_classloader = Some(cl);
    logi!("Zygisk: postAppSpecialize complete");
}

pub unsafe fn do_pre_server_specialize(module: &mut WeKitModule, _args: *mut ServerSpecializeArgs) {
    (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
}
