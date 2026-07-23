//! WeKit xtask — build automation for the WeKit Android project.
//!
//! Usage: cargo xtask <COMMAND>
//!
//!   configure            Regenerate wekit-native/.cargo/config.toml from the local NDK.
//!   build [OPTIONS]      Build the project (default: full Android debug build via Gradle).
//!   check [OPTIONS]      Run `cargo check` on the native library.
//!   clippy [OPTIONS]     Run `cargo clippy` on the native library.
//!
//! Run `cargo xtask <COMMAND> --help` for per-command options.

use anyhow::{Context, Result, bail};
use clap::{Args, Parser, Subcommand, ValueEnum};
use std::{
    env, fs,
    path::{Path, PathBuf},
    process::Command,
};

// ── Project constants (mirror app/build.gradle.kts / libs.versions.toml) ──────

/// Matches `minSdk` in libs.versions.toml.
const MIN_SDK: u32 = 28;

/// Minimum NDK major version accepted by `configure`.  Mirrors the check in
/// ConfigureCargoTask.kt (`minNdk = 29`).
const MIN_NDK_MAJOR: u32 = 29;

// ── ABI table ─────────────────────────────────────────────────────────────────

struct AbiSpec {
    /// Directory name in `jniLibs/` and Android ABI split filter.
    android_name: &'static str,
    /// Cargo target triple passed to `--target`.
    cargo_triple: &'static str,
    /// Clang binary prefix inside the NDK `bin/` dir (the part before
    /// `{MIN_SDK}-clang`).  Note: armv7 uses `armv7a-` not `armv7-`.
    clang_prefix: &'static str,
    /// Prefix used for `CC_`, `CXX_`, `AR_` keys in `.cargo/config.toml`.
    /// Matches the hardcoded strings in `ConfigureCargoTask.kt`.
    env_key: &'static str,
}

// Order matches the template in ConfigureCargoTask.kt so that
// `cargo xtask configure` and the Gradle task produce identical output.
static ABI_TABLE: &[AbiSpec] = &[
    AbiSpec {
        android_name: "arm64-v8a",
        cargo_triple: "aarch64-linux-android",
        clang_prefix: "aarch64-linux-android",
        env_key: "aarch64_linux_android",
    },
    AbiSpec {
        android_name: "x86_64",
        cargo_triple: "x86_64-linux-android",
        clang_prefix: "x86_64-linux-android",
        env_key: "x86_64_linux_android",
    },
    AbiSpec {
        android_name: "armeabi-v7a",
        cargo_triple: "armv7-linux-androideabi",
        clang_prefix: "armv7a-linux-androideabi",
        // Kept with hyphens to match ConfigureCargoTask.kt's template verbatim.
        env_key: "armv7-linux-androideabi",
    },
    AbiSpec {
        android_name: "x86",
        cargo_triple: "i686-linux-android",
        clang_prefix: "i686-linux-android",
        // Kept with hyphens to match ConfigureCargoTask.kt's template verbatim.
        env_key: "i686-linux-android",
    },
];

/// ABIs included in Gradle's ABI splits (the default build targets).
static RELEASE_ABIS: &[&str] = &["arm64-v8a", "armeabi-v7a"];

// ── CLI ────────────────────────────────────────────────────────────────────────

#[derive(Parser)]
#[command(
    name = "cargo xtask",
    about = "WeKit build automation",
    long_about = None,
    disable_help_subcommand = true,
)]
struct Cli {
    #[command(subcommand)]
    command: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Regenerate wekit-native/.cargo/config.toml from the local NDK.
    Configure,

    /// Build the project.
    ///
    /// Default: runs `./gradlew assembleDebug` (full Android + Rust via Gradle).
    /// Pass --native-only to compile only the Rust .so and copy it to jniLibs/.
    Build(BuildArgs),

    /// Install and launch the app on a connected device or emulator.
    ///
    /// Runs `./gradlew install<Flavor><Type>` (default: `installDebug`).
    Run(RunArgs),

    /// Run `cargo check` on the native library for each target ABI.
    Check(NativeArgs),

    /// Run `cargo clippy` on the native library for each target ABI.
    Clippy(NativeArgs),
}

#[derive(Args)]
struct BuildArgs {
    /// Build only the Rust native library (.so) and copy it to jniLibs/.
    /// Skips the Gradle Android build entirely.
    #[arg(long)]
    native_only: bool,

    /// Build a specific app flavor (standard or legacy).
    /// Defaults to both (`assembleDebug` / `assembleRelease`).
    /// Ignored with --native-only.
    #[arg(short, long, value_enum)]
    flavor: Option<Flavor>,

    /// Build a release build instead of debug.
    /// Ignored with --native-only.
    #[arg(long)]
    release: bool,

    #[command(flatten)]
    native: NativeArgs,
}

/// Arguments for `run` (install + launch via Gradle).
#[derive(Args)]
struct RunArgs {
    /// App flavor to install (standard or legacy).
    /// Defaults to standard — both flavors cannot be installed side-by-side.
    #[arg(short, long, value_enum, default_value = "standard")]
    flavor: Flavor,

    /// Install the release build instead of debug.
    #[arg(long)]
    release: bool,
}

/// Arguments shared by --native-only builds, `check`, and `clippy`.
#[derive(Args)]
struct NativeArgs {
    /// Target ABI(s) to build.  May be repeated.  Defaults to arm64-v8a and armeabi-v7a.
    ///
    /// Valid values: arm64-v8a, armeabi-v7a, x86_64, x86
    #[arg(long = "abi", value_name = "ABI")]
    abis: Vec<String>,
}

#[derive(ValueEnum, Clone, Debug)]
enum Flavor {
    Standard,
    Legacy,
}

// ── Entry point ────────────────────────────────────────────────────────────────

fn print_banner() {
    println!(
        r#"
     _       __     __ __ _ __
    | |     / /__  / //_/(_) /_
    | | /| / / _ \/ ,<  / / __/
    | |/ |/ /  __/ /| |/ / /_
    |__/|__/\___/_/ |_/_/\__/

[WeKit] WeChat, now with superpowers
"#
    );
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    print_banner();
    match cli.command {
        Cmd::Configure => task_configure()?,
        Cmd::Build(args) => task_build(args)?,
        Cmd::Run(args) => task_run(args)?,
        Cmd::Check(args) => task_cargo_cmd("check", &args.abis, &[])?,
        Cmd::Clippy(args) => task_cargo_cmd("clippy", &args.abis, &["--", "-D", "warnings"])?,
    }
    Ok(())
}

// ── Workspace / path helpers ───────────────────────────────────────────────────

/// Walk up from `cwd` until we find a `Cargo.toml` that declares `[workspace]`.
fn workspace_root() -> PathBuf {
    let mut dir = env::current_dir().expect("could not read cwd");
    loop {
        let toml = dir.join("Cargo.toml");
        if toml.exists() {
            let text = fs::read_to_string(&toml).unwrap_or_default();
            if text.contains("[workspace]") {
                return dir;
            }
        }
        dir = dir
            .parent()
            .unwrap_or_else(|| panic!("workspace root not found; run from inside the WeKit repo"))
            .to_owned();
    }
}

fn native_crate_dir(root: &Path) -> PathBuf {
    root.join("app/src/main/rust/wekit-native")
}

fn jni_libs_dir(root: &Path) -> PathBuf {
    root.join("app/src/main/jniLibs")
}

// ── ABI resolution ─────────────────────────────────────────────────────────────

fn resolve_abis<'a>(names: &[String]) -> Result<Vec<&'a AbiSpec>> {
    let names_to_use: Vec<&str> = if names.is_empty() {
        RELEASE_ABIS.to_vec()
    } else {
        names.iter().map(String::as_str).collect()
    };

    names_to_use
        .iter()
        .map(|name| {
            ABI_TABLE
                .iter()
                .find(|a| a.android_name == *name)
                .with_context(|| {
                    format!(
                        "unknown ABI `{name}`; valid values: {}",
                        ABI_TABLE
                            .iter()
                            .map(|a| a.android_name)
                            .collect::<Vec<_>>()
                            .join(", ")
                    )
                })
        })
        .collect()
}

// ── Android SDK / NDK discovery ────────────────────────────────────────────────

/// Return `ANDROID_HOME`, falling back to `sdk.dir` in `local.properties`.
fn find_android_home(workspace_root: &Path) -> Result<String> {
    if let Ok(home) = env::var("ANDROID_HOME") {
        if !home.is_empty() {
            return Ok(home);
        }
    }

    let props_path = workspace_root.join("local.properties");
    let props = fs::read_to_string(&props_path).with_context(|| {
        format!(
            "ANDROID_HOME not set and could not read {}",
            props_path.display()
        )
    })?;

    for line in props.lines() {
        if let Some(rest) = line.strip_prefix("sdk.dir=") {
            let dir = rest.trim().replace("\\:", ":"); // unescape Windows paths
            if !dir.is_empty() {
                return Ok(dir);
            }
        }
    }

    bail!("ANDROID_HOME env var not set and sdk.dir not found in local.properties");
}

/// Return the `bin/` path inside the highest qualifying NDK's prebuilt llvm dir.
///
/// Mirrors `findNdkClang` in `buildSrc/src/main/kotlin/ConfigureCargoTask.kt`.
fn find_ndk_bin_dir(android_home: &str) -> Result<String> {
    let ndk_root = PathBuf::from(android_home).join("ndk");
    if !ndk_root.exists() {
        bail!("NDK directory not found: {}", ndk_root.display());
    }

    // Collect NDK dirs whose major version >= MIN_NDK_MAJOR.
    let mut candidates: Vec<(Vec<u32>, PathBuf)> = fs::read_dir(&ndk_root)
        .with_context(|| format!("could not list {}", ndk_root.display()))?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let name = e.file_name();
            let parts: Vec<u32> = name
                .to_string_lossy()
                .split('.')
                .filter_map(|p| p.parse::<u32>().ok())
                .collect();
            if parts.first().copied().unwrap_or(0) >= MIN_NDK_MAJOR {
                Some((parts, e.path()))
            } else {
                None
            }
        })
        .collect();

    if candidates.is_empty() {
        bail!(
            "no NDK >= {MIN_NDK_MAJOR} found under {}",
            ndk_root.display()
        );
    }

    // Pick the highest version (lexicographic on version part tuples).
    candidates.sort_by(|a, b| a.0.cmp(&b.0));
    let (_, ndk_dir) = candidates.pop().unwrap();

    let host = host_prebuilt_tag()?;
    let bin_dir = ndk_dir
        .join("toolchains/llvm/prebuilt")
        .join(host)
        .join("bin");

    if !bin_dir.exists() {
        bail!("expected NDK bin dir not found: {}", bin_dir.display());
    }

    Ok(bin_dir.to_string_lossy().replace('\\', "/"))
}

/// Return the prebuilt host tag used by the NDK (e.g. `linux-x86_64`).
fn host_prebuilt_tag() -> Result<&'static str> {
    match (env::consts::OS, env::consts::ARCH) {
        ("linux", "x86_64") => Ok("linux-x86_64"),
        ("linux", "aarch64") => Ok("linux-aarch64"),
        ("macos", "x86_64") => Ok("darwin-x86_64"),
        ("macos", "aarch64") => Ok("darwin-arm64"),
        ("windows", "x86_64") => Ok("windows-x86_64"),
        (os, arch) => bail!("unsupported host OS/arch: {os}/{arch}"),
    }
}

// ── Task: configure ────────────────────────────────────────────────────────────

fn task_configure() -> Result<()> {
    let root = workspace_root();
    let android_home = find_android_home(&root)?;
    let ndk_bin_dir = find_ndk_bin_dir(&android_home)?;

    // On Windows the NDK ships `.cmd` wrappers for the clang binaries.
    let ext = if cfg!(target_os = "windows") {
        ".cmd"
    } else {
        ""
    };
    let ar = format!("{ndk_bin_dir}/llvm-ar");

    let mut out = String::new();

    // [target.*] sections — one per ABI.
    for spec in ABI_TABLE {
        let linker = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang{ext}", spec.clang_prefix);
        out.push_str(&format!(
            "[target.{}]\nar = \"{ar}\"\nlinker = \"{linker}\"\n\n",
            spec.cargo_triple
        ));
    }

    // [env] section — CC/CXX/AR vars consumed by `cc-rs` and `bindgen`.
    out.push_str("[env]\n");
    for spec in ABI_TABLE {
        let cc = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang{ext}", spec.clang_prefix);
        let cxx = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang++{ext}", spec.clang_prefix);
        out.push_str(&format!("CC_{k} = \"{cc}\"\n", k = spec.env_key));
        out.push_str(&format!("CXX_{k} = \"{cxx}\"\n", k = spec.env_key));
        out.push_str(&format!("AR_{k} = \"{ar}\"\n\n", k = spec.env_key));
    }

    let out = out.trim_end_matches('\n').to_owned() + "\n";

    let config_path = native_crate_dir(&root).join(".cargo/config.toml");
    fs::create_dir_all(config_path.parent().unwrap())?;
    fs::write(&config_path, &out)
        .with_context(|| format!("failed to write {}", config_path.display()))?;

    println!("configure: wrote {}", config_path.display());
    Ok(())
}

// ── Task: build ────────────────────────────────────────────────────────────────

fn task_build(args: BuildArgs) -> Result<()> {
    if args.native_only {
        task_build_native(&args.native.abis)
    } else {
        task_build_android(&args)
    }
}

/// Compose a Gradle task name from a verb, optional flavor, and profile.
///
/// Examples: `assemble` + `Standard` + `Release` → `assembleStandardRelease`
fn gradle_variant_task(verb: &str, flavor: Option<&Flavor>, release: bool) -> String {
    let profile = if release { "Release" } else { "Debug" };
    match flavor {
        None => format!("{verb}{profile}"),
        Some(Flavor::Standard) => format!("{verb}Standard{profile}"),
        Some(Flavor::Legacy) => format!("{verb}Legacy{profile}"),
    }
}

/// Full Android build via the Gradle wrapper (native lib compiled first).
fn task_build_android(args: &BuildArgs) -> Result<()> {
    task_configure()?;
    task_build_native(&args.native.abis)?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("assemble", args.flavor.as_ref(), args.release);
    println!("build: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

/// Install the app on a connected device or emulator via the Gradle wrapper (native lib compiled first).
fn task_run(args: RunArgs) -> Result<()> {
    task_configure()?;
    task_build_native(&[])?;
    let root = workspace_root();
    let gradle_task = gradle_variant_task("install", Some(&args.flavor), args.release);
    println!("run: ./gradlew {gradle_task}");
    run_gradlew(&[&gradle_task], &root)
}

/// Native-only build: cargo build + copy .so to jniLibs/.
fn task_build_native(abi_args: &[String]) -> Result<()> {
    let root = workspace_root();
    let native_dir = native_crate_dir(&root);
    let abis = resolve_abis(abi_args)?;

    for spec in &abis {
        println!(
            "build(native): {} ({})",
            spec.android_name, spec.cargo_triple
        );

        run_cargo(
            &["build", "--release", "--target", spec.cargo_triple],
            &native_dir,
        )?;

        let so_src = root
            .join("target")
            .join(spec.cargo_triple)
            .join("release/libwekit_native.so");
        let so_dst_dir = jni_libs_dir(&root).join(spec.android_name);
        let so_dst = so_dst_dir.join("libwekit_native.so");

        fs::create_dir_all(&so_dst_dir)
            .with_context(|| format!("could not create {}", so_dst_dir.display()))?;
        fs::copy(&so_src, &so_dst).with_context(|| {
            format!("could not copy {} → {}", so_src.display(), so_dst.display())
        })?;

        println!(
            "build(native):  {} → {}",
            so_src.display(),
            so_dst.display()
        );
    }

    Ok(())
}

// ── Task: check / clippy ───────────────────────────────────────────────────────

fn task_cargo_cmd(subcommand: &str, abi_args: &[String], extra_args: &[&str]) -> Result<()> {
    let root = workspace_root();
    let native_dir = native_crate_dir(&root);
    let abis = resolve_abis(abi_args)?;

    for spec in &abis {
        println!(
            "{subcommand}: {} ({})",
            spec.android_name, spec.cargo_triple
        );

        let mut cmd_args = vec![subcommand, "--target", spec.cargo_triple];
        cmd_args.extend_from_slice(extra_args);
        run_cargo(&cmd_args, &native_dir)?;
    }

    Ok(())
}

// ── Process runners ────────────────────────────────────────────────────────────

fn run_cargo(args: &[&str], cwd: &Path) -> Result<()> {
    // Prefer the same `cargo` that invoked xtask (set by Cargo as $CARGO).
    let cargo = env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    run_cmd(&cargo, args, cwd)
}

fn run_gradlew(args: &[&str], cwd: &Path) -> Result<()> {
    let gradlew = if cfg!(target_os = "windows") {
        "gradlew.bat"
    } else {
        "./gradlew"
    };
    run_cmd(gradlew, args, cwd)
}

fn run_cmd(program: &str, args: &[&str], cwd: &Path) -> Result<()> {
    let status = Command::new(program)
        .args(args)
        .current_dir(cwd)
        .status()
        .with_context(|| format!("failed to spawn `{program} {}`", args.join(" ")))?;

    if !status.success() {
        bail!("`{program} {}` exited with {status}", args.join(" "));
    }

    Ok(())
}
