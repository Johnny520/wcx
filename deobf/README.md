# wechatx payload decryptor & deobfuscator

this repository contains the dynamic decryption and deobfuscation harness for the wechatx module. it uses a sandboxed unidbg arm64 emulator to dynamically run the native loader library and capture decrypted payload binaries.

this project was completely vibe-coded.

## folder structure

- `run_deobf.sh`: one-click runner script.
- `unidbg-harness/`: gradle project containing the java decryption harness using unidbg.
- `moduledata/`: raw encrypted payloads and native loader libraries.
- `tools/`: post-processing python tools for format cleanups and translation extraction.
- `output/`: destination folder where core-named decrypted files are stored.

## how to run

to run the deobfuscation pipeline, run:

```bash
./run_deobf.sh
```

## licensing

the code in this repository is licensed under the gnu general public license v3.0 (gpl-3.0). see the `license` file for details.

important note: this license does not apply to any proprietary resources located in the `moduledata` directory.
