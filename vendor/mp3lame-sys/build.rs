#![allow(clippy::single_match)]

const LAME_DIR: &str = "lame-3.100";

#[cfg(unix)]
fn build() {
    let mut config = autotools::Config::new(LAME_DIR);

    #[cfg(feature = "decoder")]
    config.enable("decoder", None);
    #[cfg(not(feature = "decoder"))]
    config.disable("decoder", None);

    let host = std::env::var("HOST").expect("To have env:HOST");
    let target = std::env::var("TARGET").expect("To have env:TARGET");
    if let Ok(override_host) = std::env::var("MP3LAME_SYS_OVERRIDE_HOST") {
        config.config_option("host", Some(override_host.as_str()));
    } else if host != target {
        #[cfg(not(feature = "target_host"))]
        {
            if target.contains("android") {
                //Assume cross-compilation for android target
                config.config_option("host", Some(target.as_str()));
            } else if target.contains("apple") {
                if target.starts_with("aarch64") {
                    config.config_option("host", Some("arm-apple-darwin"));
                } else if target.starts_with("x86_64") {
                    config.config_option("host", Some("x86_64-apple-darwin"));
                } else {
                    println!("cargo:warning=Unsupported Apple target");
                }
            } else {
                println!("cargo:warning=Cross-compilation may not be supported");
            }
        }
        #[cfg(feature = "target_host")]
        {
            config.config_option("host", Some(target.as_str()));
        }
    }

    //Android cross compilation may require this flag
    if target.contains("android") || target.contains("ios") {
        config.cflag("-DSTDC_HEADERS");
    }

    let res = config.disable_shared()
                    .enable_static()
                    .disable("rpath", None)
                    .disable("frontend", None)
                    .disable("gtktest", None)
                    .with("pic", None)
                    .fast_build(true)
                    .build();

    //libraries are installed in <out>/lib
    println!("cargo:rustc-link-search=native={}/lib", res.display());
    println!("cargo:rustc-link-lib=static=mp3lame");
}

//On windows we cannot just use `nmake` as VS solution there doesn't have x64 files
//so instead just directly compile it.
//On unix targets we just rely on autotools to figure shit out
#[cfg(windows)]
fn build() {
    const INCLUDE_MSVC: &str = ".include_msvc";

    //fucking cargo and its annoying treatment of file modifications of crate
    let out_dir = std::env::var("OUT_DIR").expect("OUT_DIR is not set by retarded cargo");
    let lame_dir = std::path::Path::new(LAME_DIR);
    let include_msvc = std::path::Path::new(&out_dir).join(INCLUDE_MSVC);
    // On Android/iOS the integer-type macros in configMS.h clash with the NDK
    // sysroot stdint.h typedefs (typedef __int8_t int8_t vs #define int8_t signed
    // char), so strip them while keeping the ieee754_* and HAVE_* defines.
    let target = std::env::var("TARGET").expect("To have env:TARGET");
    let is_android_like = target.contains("android") || target.contains("ios");
    let _ = std::fs::create_dir(&include_msvc);
    let ms = std::fs::read_to_string(lame_dir.join("configMS.h")).expect("Read configMS.h");
    let config_h: String = if is_android_like {
        ms.lines()
            .filter(|l| {
                !(l.starts_with("#define __int8_t_defined")
                    || (l.starts_with("#define ")
                        && ["int8_t ", "int16_t ", "int32_t ", "int64_t ", "uint8_t ", "uint16_t ", "uint32_t ", "uint64_t "]
                            .iter()
                            .any(|t| l.contains(t))))
            })
            .collect::<Vec<_>>()
            .join("\n")
    } else {
        ms
    };
    std::fs::write(include_msvc.join("config.h"), config_h).expect("Write config.h");

    let mut cc = cc::Build::new();
    cc.warnings(false)
      .extra_warnings(false)
      .file(lame_dir.join("libmp3lame/bitstream.c"))
      .file(lame_dir.join("libmp3lame/encoder.c"))
      .file(lame_dir.join("libmp3lame/fft.c"))
      .file(lame_dir.join("libmp3lame/gain_analysis.c"))
      .file(lame_dir.join("libmp3lame/id3tag.c"))
      .file(lame_dir.join("libmp3lame/lame.c"))
      .file(lame_dir.join("libmp3lame/newmdct.c"))
      .file(lame_dir.join("libmp3lame/presets.c"))
      .file(lame_dir.join("libmp3lame/psymodel.c"))
      .file(lame_dir.join("libmp3lame/quantize_pvt.c"))
      .file(lame_dir.join("libmp3lame/vector/xmm_quantize_sub.c"))
      .file(lame_dir.join("libmp3lame/quantize.c"))
      .file(lame_dir.join("libmp3lame/reservoir.c"))
      .file(lame_dir.join("libmp3lame/set_get.c"))
      .file(lame_dir.join("libmp3lame/tables.c"))
      .file(lame_dir.join("libmp3lame/takehiro.c"))
      .file(lame_dir.join("libmp3lame/util.c"))
      .file(lame_dir.join("libmp3lame/vbrquantize.c"))
      .file(lame_dir.join("libmp3lame/VbrTag.c"))
      .file(lame_dir.join("libmp3lame/version.c"))
      .include(lame_dir.join("include"))
      .include(&include_msvc)
      .include(lame_dir.join("libmp3lame"))
      .define("TAKEHIRO_IEEE754_HACK", None)
      .define("FLOAT8", Some("float"))
      .define("REAL_IS_FLOAT", Some("1"))
      .define("BS_FORMAT", Some("BINARY"))
      .define("HAVE_CONFIG_H", None)
      .shared_flag(false)
      .pic(false)
      .warnings(false);

    if is_android_like {
        // lame 3.100 relies on implicit function declarations (bcopy/strlen/memset),
        // which clang 21 treats as errors by default.
        cc.flag("-Wno-implicit-function-declaration");
    }

    #[cfg(feature = "decoder")]
    {
        cc.define("HAVE_MPGLIB", None)
          .include(lame_dir.join("mpglib"))
          .file(lame_dir.join("mpglib/common.c"))
          .file(lame_dir.join("mpglib/dct64_i386.c"))
          .file(lame_dir.join("mpglib/decode_i386.c"))
          .file(lame_dir.join("mpglib/interface.c"))
          .file(lame_dir.join("mpglib/layer1.c"))
          .file(lame_dir.join("mpglib/layer2.c"))
          .file(lame_dir.join("mpglib/layer3.c"))
          .file(lame_dir.join("mpglib/tabinit.c"))
          .file(lame_dir.join("libmp3lame/mpglib_interface.c"));
    }

    if let Ok(compiler) = std::env::var("CC") {
        let compiler = std::path::Path::new(&compiler);
        let compiler = compiler.file_stem().expect("To have file name in CC").to_str().unwrap();
        match compiler {
            //because `cc` crate is retarded and cannot handle clang-cl correctly
            "clang-cl" => {
                cc.flag("/W0");
            },
            _ => (),
        }
    }

    cc.compile("mp3lame")
}

fn main() {
    if std::env::var("DOCS_RS").map(|docs| docs == "1").unwrap_or(false) {
        //skip docs.rs build
        return;
    }

    build();
}
