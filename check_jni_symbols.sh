#!/bin/bash
# verify_native_methods.sh
# Run this script from the root of your Android project.

set -euo pipefail

PROJECT_ROOT=$(pwd)
SRC_DIR="$PROJECT_ROOT/app/src/main/java"
JNI_LIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

# Validate directories
if [ ! -d "$SRC_DIR" ]; then
    echo "❌ Java source directory not found: $SRC_DIR"
    exit 1
fi

if [ ! -d "$JNI_LIBS_DIR" ]; then
    echo "❌ jniLibs directory not found: $JNI_LIBS_DIR"
    exit 1
fi

SO_FILES=$(find "$JNI_LIBS_DIR" -type f -name "*.so")
if [ -z "$SO_FILES" ]; then
    echo "❌ No .so files found in $JNI_LIBS_DIR"
    exit 1
fi

echo "🔍 Scanning Java source files for native methods..."
echo "Java Source Directory: $SRC_DIR"
echo "Shared Libraries Directory: $JNI_LIBS_DIR"
echo "-------------------------------------------------------------"

# Helper: Convert package/class into JNI-style name (dots -> underscores)
encode_jni_name() {
    echo "$1" | sed 's/\./_/g'
}

# Search symbol in SO files
search_symbol() {
    local symbol="$1"
    for so in $SO_FILES; do
        if readelf -Ws "$so" | grep -q "$symbol"; then
            echo "$so"
            return 0
        fi
    done
    return 1
}

# Main loop: scan all Java files for native methods
find "$SRC_DIR" -type f -name "*.java" | while read -r file; do
    package=$(grep -E "^package " "$file" | sed 's/package //; s/;//' | tr -d '[:space:]')
    class=$(basename "$file" .java)

    # Skip files without native methods
    if ! grep -q "native " "$file"; then
        continue
    fi

    echo "📂 Checking file: $file"
    echo "   Package: $package"
    echo "   Class: $class"

    # Extract all native method lines
    grep -E "native " "$file" | sed 's/^[[:space:]]*//' | while read -r line; do
        # Extract method name
        method_name=$(echo "$line" | grep -oE '[A-Za-z0-9_]+[[:space:]]*\(' | sed 's/[( ]//g')

        full_class="${package}.${class}"
        jni_class=$(encode_jni_name "$full_class")

        # Expected symbol without trailing J
        jni_symbol="Java_${jni_class}_${method_name}"

        echo "   → Method: $method_name"
        echo "     Expected JNI Symbol: $jni_symbol"

        # Step 1: Try to find the exact symbol
        so_found=$(search_symbol "$jni_symbol" || true)

        if [ -n "$so_found" ]; then
            echo "       ✅ Found in $so_found"
            continue
        fi

        # Step 2: Try adding trailing J
        jni_symbol_with_j="${jni_symbol}J"
        so_found_j=$(search_symbol "$jni_symbol_with_j" || true)

        if [ -n "$so_found_j" ]; then
            echo "       ❗ Found with trailing J in $so_found_j"
            echo "          Suggestion: Rename Java method to '${method_name}J' or wrap it."
            continue
        fi

        # Step 3: Completely missing
        echo "       ❌ MISSING: Neither '${jni_symbol}' nor '${jni_symbol_with_j}' found in any .so file"
    done

    echo
done

echo "-------------------------------------------------------------"
echo "Verification complete."

