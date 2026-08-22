# mp3lame-sys

[![Rust](https://github.com/DoumanAsh/mp3lame-sys/actions/workflows/rust.yml/badge.svg)](https://github.com/DoumanAsh/mp3lame-sys/actions/workflows/rust.yml)
[![Crates.io](https://img.shields.io/crates/v/mp3lame-sys.svg)](https://crates.io/crates/mp3lame-sys)
[![Documentation](https://docs.rs/mp3lame-sys/badge.svg)](https://docs.rs/crate/mp3lame-sys/)

Bindings to [LAME](https://lame.sourceforge.io/)

This is static only build with bundled version 3.100 (latest as of 2022/12/08)

There is safe and convenient wrapper over it: https://github.com/DoumanAsh/mp3lame-encoder

## Features

- `decoder` - Enables MPG library decoding under GPL;
- `target_host` - Overrides `host` option with `TARGET` for purpose of compiling C code in case of cross-compilation.

## Env variables

- `MP3LAME_SYS_OVERRIDE_HOST` - Specifies override for `host` option within configure.

## License

LAME library is under LGPL License.
Hence this crate is licensed under the same shitty license
