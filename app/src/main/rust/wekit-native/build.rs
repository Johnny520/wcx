use sha2::{Digest, Sha256};
use std::{
    env, fs,
    path::{Path, PathBuf},
    process::Command,
};

const LIBVPX_VERSION: &str = "1.16.0";
const LIBVPX_ARCHIVE_SHA256: &str =
    "7a479a3c66b9f5d5542a4c6a1b7d3768a983b1e5c14c60a9396edc9b649e015c";

fn main() {
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        println!("cargo:rustc-link-lib=log");
        println!("cargo:rustc-link-lib=dl");
        println!("cargo:rustc-link-lib=unwind");
    }

    build_vpx_decoder(&target_os);

    let bindings = bindgen::Builder::default()
        .header("include/native_hook.h")
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        .generate()
        .expect("Failed to generate bindings");

    let out_path = PathBuf::from(env::var("OUT_DIR").unwrap());
    bindings
        .write_to_file(out_path.join("bindings.rs"))
        .expect("Failed to write bindings");
}

fn build_vpx_decoder(target_os: &str) {
    println!("cargo:rerun-if-changed=include/wekit_vpx_decoder.c");
    let include_paths = if target_os == "android" {
        vec![build_android_libvpx()]
    } else {
        pkg_config::Config::new()
            .cargo_metadata(false)
            .probe("vpx")
            .expect("libvpx development files are required for host builds")
            .include_paths
    };

    let mut wrapper = cc::Build::new();
    wrapper.file("include/wekit_vpx_decoder.c");
    include_paths.iter().for_each(|path| {
        wrapper.include(path);
    });
    wrapper.compile("wekit_vpx_decoder");

    if target_os == "android" {
        println!("cargo:rustc-link-lib=static=vpx");
        println!("cargo:rustc-link-lib=m");
    } else {
        println!("cargo:rustc-link-lib=vpx");
    }
}

fn build_android_libvpx() -> PathBuf {
    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR is missing"));
    let archive = out_dir.join(format!("libvpx-{LIBVPX_VERSION}.tar.gz"));
    let source = out_dir.join(format!("libvpx-{LIBVPX_VERSION}"));
    let build = out_dir.join("libvpx-build");
    let library = build.join("libvpx.a");

    if !source.join("configure").is_file() {
        download_libvpx(&archive);
        let status = Command::new("tar")
            .args(["-xzf"])
            .arg(&archive)
            .arg("-C")
            .arg(&out_dir)
            .status()
            .expect("failed to run tar while extracting libvpx");
        assert!(status.success(), "failed to extract libvpx");
    }

    if !library.is_file() {
        let _ = fs::remove_dir_all(&build);
        fs::create_dir_all(&build).expect("failed to create libvpx build directory");
        configure_android_libvpx(&source, &build);
        let jobs = std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(2)
            .min(8);
        let status = Command::new("make")
            .arg(format!("-j{jobs}"))
            .current_dir(&build)
            .status()
            .expect("failed to run make while building libvpx");
        assert!(
            status.success() && library.is_file(),
            "failed to build libvpx"
        );
    }

    println!("cargo:rustc-link-search=native={}", build.display());
    source
}

fn download_libvpx(archive: &Path) {
    if archive.is_file() && file_sha256(archive) == LIBVPX_ARCHIVE_SHA256 {
        return;
    }
    let _ = fs::remove_file(archive);
    let url =
        format!("https://github.com/webmproject/libvpx/archive/refs/tags/v{LIBVPX_VERSION}.tar.gz");
    let status = Command::new("curl")
        .args(["--fail", "--location", "--silent", "--show-error"])
        .arg(&url)
        .arg("--output")
        .arg(archive)
        .status()
        .expect("failed to run curl while downloading libvpx");
    assert!(status.success(), "failed to download libvpx");
    assert_eq!(
        file_sha256(archive),
        LIBVPX_ARCHIVE_SHA256,
        "libvpx archive checksum mismatch",
    );
}

fn configure_android_libvpx(source: &Path, build: &Path) {
    let rust_target = env::var("TARGET").expect("TARGET is missing");
    let vpx_target = match rust_target.as_str() {
        "aarch64-linux-android" => "arm64-android-gcc",
        "armv7-linux-androideabi" => "armv7-android-gcc",
        "x86_64-linux-android" => "x86_64-android-gcc",
        "i686-linux-android" => "x86-android-gcc",
        other => panic!("unsupported Android target for libvpx: {other}"),
    };
    // On Windows, libvpx's Makefile runs $(CC) through /bin/sh, which cannot
    // cope with the NDK's `.cmd` wrappers (backslashes are eaten). Use the
    // extension-less triplet clang binaries instead: they are real PE files
    // with the Android target baked in and `sh` can exec them directly.
    #[cfg(windows)]
    let (cc, cxx, toolchain) = {
        let triplet = match rust_target.as_str() {
            "aarch64-linux-android" => "aarch64-linux-android28",
            "armv7-linux-androideabi" => "armv7a-linux-androideabi28",
            "x86_64-linux-android" => "x86_64-linux-android28",
            "i686-linux-android" => "i686-linux-android28",
            other => panic!("unsupported Android target for libvpx: {other}"),
        };
        let ndk_bin = target_tool("CC", &rust_target)
            .parent()
            .expect("Android compiler has no parent directory")
            .to_path_buf();
        (
            ndk_bin.join(format!("{triplet}-clang")),
            ndk_bin.join(format!("{triplet}-clang++")),
            ndk_bin,
        )
    };
    #[cfg(not(windows))]
    let (cc, cxx, toolchain) = {
        let cc = target_tool("CC", &rust_target);
        let cxx = target_tool("CXX", &rust_target);
        let toolchain = cc.parent().expect("Android compiler has no parent directory");
        (cc, cxx, toolchain)
    };
    let ar = toolchain.join("llvm-ar.exe");
    // MSYS `sh`/`dash` treat backslashes as escapes, so pass POSIX-style
    // tool paths to libvpx configure (it writes them verbatim into *.mk).
    #[cfg(windows)]
    let (cc, cxx, ar, nm, strip) = {
        let to_msys = |p: &std::path::Path| -> String {
            let s = p.to_string_lossy();
                if s.len() >= 2 && s.as_bytes()[1] == b':' {
                    format!("/{}{}", s[..1].to_lowercase(), &s[2..].replace('\\', "/"))
            } else {
                s.into_owned()
            }
        };
        (
            to_msys(&cc),
            to_msys(&cxx),
            to_msys(&ar),
            to_msys(&toolchain.join("llvm-nm.exe")),
            to_msys(&toolchain.join("llvm-strip.exe")),
        )
    };
    #[cfg(not(windows))]
    let (cc, cxx, ar, nm, strip) = (cc, cxx, ar, toolchain.join("llvm-nm"), toolchain.join("llvm-strip"));
    // executed via `sh` (e.g. from MSYS2 / Git Bash).
    #[cfg(windows)]
    let mut cmd = {
        let mut c = Command::new("sh");
        let cfg_path = source.join("configure");
        let cfg = cfg_path.to_string_lossy();
        // Convert C:\... to /c/... so MSYS `sh` sees a POSIX path.
        let msys_cfg = if cfg.len() >= 2 && cfg.as_bytes()[1] == b':' {
            format!("/{}{}", cfg[..1].to_lowercase(), &cfg[2..].replace("\\", "/"))
        } else {
            cfg.into_owned()
        };
        c.arg(msys_cfg);
        c
    };
    #[cfg(not(windows))]
    let mut cmd = Command::new(source.join("configure"));
    let status = cmd
        .arg(format!("--target={vpx_target}"))
        .args([
            "--disable-examples",
            "--disable-tools",
            "--disable-docs",
            "--disable-unit-tests",
            "--disable-install-bins",
            "--disable-install-docs",
            "--disable-webm-io",
            "--disable-libyuv",
            "--disable-vp8",
            "--disable-vp9-encoder",
            "--enable-vp9-decoder",
            "--enable-pic",
            "--enable-small",
        ])
        .env("CC", &cc)
        .env("CXX", &cxx)
        .env("LD", &cc)
        .env("AR", &ar)
        .env("AS", &cc)
        .env("NM", &nm)
        .env("STRIP", &strip)
        .current_dir(build)
        .status()
        .expect("failed to configure libvpx");
    assert!(status.success(), "failed to configure libvpx");
}

fn target_tool(prefix: &str, target: &str) -> PathBuf {
    let candidates = [
        format!("{prefix}_{target}"),
        format!("{prefix}_{}", target.replace('-', "_")),
        prefix.to_string(),
    ];
    candidates
        .iter()
        .find_map(|key| env::var_os(key))
        .map(PathBuf::from)
        .unwrap_or_else(|| panic!("{prefix} is not configured for {target}"))
}

fn file_sha256(path: &Path) -> String {
    let bytes = fs::read(path).expect("failed to read downloaded libvpx archive");
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
