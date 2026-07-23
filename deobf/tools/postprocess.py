#!/usr/bin/env -S uv run --script

# /// script
# dependencies = ["msgpack"]
# ///

import json
from pathlib import Path

import msgpack

X7_DIR = Path(__file__).resolve().parent.parent / "moduledata" / "x7_3.0"
OUT_DIR = Path(__file__).resolve().parent.parent / "output"
OUT_DIR.mkdir(exist_ok=True)

# 1. Process H_p JSON string
hp_path = X7_DIR / "H_p_decrypted.bin"
if hp_path.exists():
    data = hp_path.read_bytes()
    # Strip msgpack string header if present
    if data.startswith(b"\xda\xb9-"):
        json_data = data[3:]
    else:
        # Find first '{'
        idx = data.find(b"{")
        json_data = data[idx:] if idx != -1 else data
    try:
        parsed = json.loads(json_data.decode("utf-8"))
        out_hp = OUT_DIR / "H_p.json"
        out_hp.write_text(
            json.dumps(parsed, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print(f"Saved clean JSON to {out_hp}")
    except Exception as e:
        print(f"Failed to parse H_p JSON: {e}")

# 2. Process Msgpack maps (M, S, P)
for name, filename in [
    ("M", "M_decrypted.bin"),
    ("S", "S_decrypted.bin"),
    ("P", "P_decrypted.bin"),
]:
    path = X7_DIR / filename
    if path.exists():
        data = path.read_bytes()
        try:
            # Msgpack decode (with raw=False to get strings where possible, or use strict_map_key=False)
            unpacked = msgpack.unpackb(data, raw=False, strict_map_key=False)

            # Convert bytes keys/values to string recursively to dump as JSON
            def clean(obj):
                if isinstance(obj, dict):
                    return {clean(k): clean(v) for k, v in obj.items()}
                elif isinstance(obj, list):
                    return [clean(x) for x in obj]
                elif isinstance(obj, bytes):
                    return obj.decode("utf-8", errors="ignore")
                return obj

            cleaned = clean(unpacked)
            out_file = OUT_DIR / f"{name}.json"
            out_file.write_text(
                json.dumps(cleaned, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            print(f"Saved clean msgpack-decoded JSON to {out_file}")
        except Exception as e:
            print(f"Failed to unpack {name}: {e}")

# 3. Copy DEX files and rename them to their "core" names
dex_mappings = [
    ("C_decrypted.dex", "C.dex"),
    ("14e777717682cce8a53cfcb34a22d65_decrypted.dex", "big.dex"),
    ("802766a4a4bd401c145a57463274d4dd_decrypted.dex", "m1.dex"),
    ("a89eaf67dc796cb3af54d94fac0198b_decrypted.dex", "m2.dex"),
    ("df4e6fd144fab2389ece8cb79eaa8f_decrypted.dex", "m3.dex"),
]

for src, dest in dex_mappings:
    src_path = X7_DIR / src
    if src_path.exists():
        dest_path = OUT_DIR / dest
        dest_path.write_bytes(src_path.read_bytes())
        print(f"Saved DEX to {dest_path}")

# 4. Process APK file (9816b5dfd637d3ce1473a9951b9e05)
apk_path = X7_DIR / "9816b5dfd637d3ce1473a9951b9e05_decrypted.bin"
if apk_path.exists():
    out_apk = OUT_DIR / "load2.apk"
    out_apk.write_bytes(apk_path.read_bytes())
    print(f"Saved APK to {out_apk}")

    # Extract classes.dex from the APK
    import zipfile

    try:
        with zipfile.ZipFile(out_apk) as z:
            dex_data = z.read("classes.dex")
            out_apk_dex = OUT_DIR / "load2_classes.dex"
            out_apk_dex.write_bytes(dex_data)
            print(f"Extracted classes.dex from APK to {out_apk_dex}")
    except Exception as e:
        print(f"Failed to extract classes.dex from APK: {e}")
