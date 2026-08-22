$p = "C:\Users\Dell\MonkeyCode\WXPRO\app\src\main\rust\wekit-native\build.rs"
$lines = [System.IO.File]::ReadAllLines($p)
$out = New-Object System.Collections.Generic.List[string]
$skipNext = $false
foreach ($line in $lines) {
    # Replace the target_tool block with cfg'd toolchain resolution
    if ($line -match '^\s*let cc = target_tool\("CC", &rust_target\);') {
        $out.Add('    // On Windows, libvpx''s Makefile runs $(CC) through /bin/sh, which cannot')
        $out.Add('    // cope with the NDK''s `.cmd` wrappers (backslashes are eaten). Use the')
        $out.Add('    // extension-less triplet clang binaries instead: they are real PE files')
        $out.Add('    // with the Android target baked in and `sh` can exec them directly.')
        $out.Add('    #[cfg(windows)]')
        $out.Add('    let (cc, cxx, toolchain) = {')
        $out.Add('        let triplet = match rust_target.as_str() {')
        $out.Add('            "aarch64-linux-android" => "aarch64-linux-android28",')
        $out.Add('            "armv7-linux-androideabi" => "armv7a-linux-androideabi28",')
        $out.Add('            "x86_64-linux-android" => "x86_64-linux-android28",')
        $out.Add('            "i686-linux-android" => "i686-linux-android28",')
        $out.Add('            other => panic!("unsupported Android target for libvpx: {other}"),')
        $out.Add('        };')
        $out.Add('        let ndk_bin = target_tool("CC", &rust_target)')
        $out.Add('            .parent()')
        $out.Add('            .expect("Android compiler has no parent directory")')
        $out.Add('            .to_path_buf();')
        $out.Add('        (')
        $out.Add('            ndk_bin.join(format!("{triplet}-clang")),')
        $out.Add('            ndk_bin.join(format!("{triplet}-clang++")),')
        $out.Add('            ndk_bin,')
        $out.Add('        )')
        $out.Add('    };')
        $out.Add('    #[cfg(not(windows))]')
        $out.Add('    let (cc, cxx, toolchain) = {')
        $out.Add('        let cc = target_tool("CC", &rust_target);')
        $out.Add('        let cxx = target_tool("CXX", &rust_target);')
        $out.Add('        let toolchain = cc.parent().expect("Android compiler has no parent directory");')
        $out.Add('        (cc, cxx, toolchain)')
        $out.Add('    };')
        $out.Add('    let ar = toolchain.join("llvm-ar.exe");')
        $skipNext = 6
        continue
    }
    if ($skipNext -gt 0) {
        $skipNext--
        continue
    }
    if ($line -match '\.env\("NM", toolchain\.join\("llvm-nm"\)\)') {
        $out.Add('        .env("NM", toolchain.join("llvm-nm.exe"))')
        continue
    }
    if ($line -match '\.env\("STRIP", toolchain\.join\("llvm-strip"\)\)') {
        $out.Add('        .env("STRIP", toolchain.join("llvm-strip.exe"))')
        continue
    }
    $out.Add($line)
}
[System.IO.File]::WriteAllLines($p, $out, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "DONE"
