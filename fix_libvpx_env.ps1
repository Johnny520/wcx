$p = "C:\Users\Dell\MonkeyCode\WXPRO\app\src\main\rust\wekit-native\build.rs"
$lines = [System.IO.File]::ReadAllLines($p)
$out = New-Object System.Collections.Generic.List[string]
$doneAr = $false
foreach ($line in $lines) {
    if (-not $doneAr -and $line -match '^\s*let ar = toolchain\.join\("llvm-ar\.exe"\);') {
        $out.Add($line)
        $out.Add('    // MSYS `sh`/`dash` treat backslashes as escapes, so pass POSIX-style')
        $out.Add('    // tool paths to libvpx configure (it writes them verbatim into *.mk).')
        $out.Add('    #[cfg(windows)]')
        $out.Add('    let (cc, cxx, ar, nm, strip) = {')
        $out.Add('        let to_msys = |p: &std::path::Path| -> String {')
        $out.Add('            let s = p.to_string_lossy();')
        $out.Add("                if s.len() >= 2 && s.as_bytes()[1] == b':' {")
        $out.Add("                    format!(""/{}{}"", s[..1].to_lowercase(), &s[2..].replace('\\', ""/""))")
        $out.Add('            } else {')
        $out.Add('                s.into_owned()')
        $out.Add('            }')
        $out.Add('        };')
        $out.Add('        (')
        $out.Add('            to_msys(&cc),')
        $out.Add('            to_msys(&cxx),')
        $out.Add('            to_msys(&ar),')
        $out.Add('            to_msys(&toolchain.join("llvm-nm.exe")),')
        $out.Add('            to_msys(&toolchain.join("llvm-strip.exe")),')
        $out.Add('        )')
        $out.Add('    };')
        $out.Add('    #[cfg(not(windows))]')
        $out.Add('    let (cc, cxx, ar, nm, strip) = (cc, cxx, ar, toolchain.join("llvm-nm"), toolchain.join("llvm-strip"));')
        $doneAr = $true
        continue
    }
    if ($line -match '\.env\("NM", toolchain\.join\("llvm-nm\.exe"\)\)') {
        $out.Add('        .env("NM", &nm)')
        continue
    }
    if ($line -match '\.env\("STRIP", toolchain\.join\("llvm-strip\.exe"\)\)') {
        $out.Add('        .env("STRIP", &strip)')
        continue
    }
    $out.Add($line)
}
[System.IO.File]::WriteAllLines($p, $out, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "DONE"
