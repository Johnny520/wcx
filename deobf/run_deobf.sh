#!/bin/bash
# One-click script to decrypt and deobfuscate all WeChatX payloads.
set -e

# Resolve directory where the script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo "=========================================================="
echo "          WeChatX Payload Decryptor & Deobf               "
echo "=========================================================="
echo ""

# 1. Clean previous decrypted artifacts
echo "[1/4] Cleaning previous run artifacts..."
rm -rf output
rm -f moduledata/x7_3.0/*_decrypted.bin
rm -f moduledata/x7_3.0/*_decrypted.dex
rm -rf unidbg-harness/rootfs
echo "      Clean complete."
echo ""

# 2. Run Gradle decryption harness
echo "[2/4] Running dynamic decryption harness via Unidbg..."
chmod +x gradlew
./gradlew -p unidbg-harness run
echo "      Decryption harness finished."
echo ""

# 3. Post-process extracted files (msgpack decode & format)
echo "[3/4] Running postprocess script..."
chmod +x tools/postprocess.py
./tools/postprocess.py
echo "      Post-processing finished."
echo ""

# 4. Verification and listing
echo "[4/4] Verifying generated output files..."
if [ -d "output" ]; then
    echo ""
    echo "🎉 Success! All payloads decrypted and cleanly saved to:"
    echo "    $SCRIPT_DIR/output/"
    echo ""
    echo "Generated Files:"
    ls -lh output/
    echo ""
else
    echo "❌ Error: Decrypted output directory was not created."
    exit 1
fi
