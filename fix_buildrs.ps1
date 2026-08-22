$p = "C:\Users\Dell\MonkeyCode\WXPRO\app\src\main\rust\wekit-native\build.rs"
$lines = [System.IO.File]::ReadAllLines($p)
$out = New-Object System.Collections.Generic.List[string]
foreach ($line in $lines) {
    if ($line -match 'c\.arg\(source\.join\("configure"\)\);') {
        $out.Add('        let cfg = source.join("configure").to_string_lossy();')
        $out.Add('        // Convert C:\... to /c/... so MSYS `sh` sees a POSIX path.')
        $out.Add("        let msys_cfg = if cfg.len() >= 2 && cfg.as_bytes()[1] == b':' {")
        $out.Add('            format!("/{}{}", cfg[..1].to_lowercase(), &cfg[2..].replace("\\", "/"))')
        $out.Add('        } else {')
        $out.Add('            cfg.into_owned()')
        $out.Add('        };')
        $out.Add('        c.arg(msys_cfg);')
    } else {
        $out.Add($line)
    }
}
[System.IO.File]::WriteAllLines($p, $out, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "DONE"
