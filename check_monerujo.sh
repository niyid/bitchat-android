#!/bin/bash
set -euo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"
SO="app/src/main/jniLibs/arm64-v8a/libmonerujo.so"
TMP="/tmp/monerujo_check"

echo "=== Monerujo Preflight Check ==="
echo "APK: $APK"
echo "SO:  $SO"
echo "TMP: $TMP"

mkdir -p "$TMP"

# Step 1: Extract classes.dex
echo "[1/5] Extracting classes from APK..."
if ! unzip -o "$APK" "classes*.dex" -d "$TMP" >/dev/null 2>&1; then
    echo "ERROR: Failed to extract classes.dex from $APK"
    exit 1
fi

if [ ! -f "$TMP/classes.dex" ]; then
    echo "ERROR: No classes.dex found after extraction!"
    exit 1
fi
echo "  -> Extracted successfully."

# Step 2: Verify .so file exists
echo "[2/5] Checking native library..."
if [ ! -f "$SO" ]; then
    echo "ERROR: Missing $SO"
    exit 1
fi
echo "  -> Found $SO"

# Step 3: List dependencies
echo "[3/5] Checking native dependencies..."
readelf -d "$SO" | grep NEEDED || echo "No dependencies found."

# Step 4: Check for missing Java classes
echo "[4/5] Checking for required Java classes..."

REQUIRED_CLASSES=("com/m2049r/xmrwallet/model/Transfer.class")

missing_classes=()
for class in "${REQUIRED_CLASSES[@]}"; do
    if ! unzip -l "$APK" | grep -q "$class"; then
        missing_classes+=("$class")
    fi
done

if [ ${#missing_classes[@]} -ne 0 ]; then
    echo "ERROR: Missing required classes:"
    for cls in "${missing_classes[@]}"; do
        echo "  - $cls"
    done
    exit 1
else
    echo "  -> All required classes found."
fi

# Step 5: Success
echo "[5/5] All checks passed. You can run System.loadLibrary(\"monerujo\") safely."

