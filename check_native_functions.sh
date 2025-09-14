#!/bin/bash

# Script to check native functions in Java classes against libmonerujo.so
# Usage: ./check_native_functions.sh [package_name] [lib_path]
# Example: ./check_native_functions.sh com.m2049r.xmrwallet app/src/main/jniLibs/arm64-v8a/libmonerujo.so

set -e

# Default values
DEFAULT_PACKAGE="com.m2049r.xmrwallet"
DEFAULT_LIB_PATH="app/src/main/jniLibs/arm64-v8a/libmonerujo.so"

# Parse command line arguments
PACKAGE_NAME="${1:-$DEFAULT_PACKAGE}"
LIB_PATH="${2:-$DEFAULT_LIB_PATH}"

# Convert package name to directory path
PACKAGE_PATH=$(echo "$PACKAGE_NAME" | tr '.' '/')
JAVA_SOURCE_DIR="app/src/main/java/$PACKAGE_PATH"

echo "Analyzing native functions in package: $PACKAGE_NAME"
echo "Looking for Java files in: $JAVA_SOURCE_DIR"
echo "Checking against library: $LIB_PATH"
echo ""

# Check if required tools are available
if ! command -v nm &> /dev/null && ! command -v objdump &> /dev/null && ! command -v readelf &> /dev/null; then
    echo "Error: Neither 'nm', 'objdump', nor 'readelf' found. Please install binutils."
    exit 1
fi

# Check if the library file exists
if [[ ! -f "$LIB_PATH" ]]; then
    echo "Error: Library file $LIB_PATH not found"
    
    # Try to find libmonerujo.so in common locations
    echo "Searching for libmonerujo.so in project..."
    find . -name "libmonerujo.so" -type f 2>/dev/null | head -5 | while read -r lib; do
        echo "  Found: $lib"
    done
    exit 1
fi

# Function to get symbols from the shared library
get_library_symbols() {
    local lib_file="$1"
    
    # Try different tools to extract symbols
    if command -v nm &> /dev/null; then
        nm -D "$lib_file" 2>/dev/null | grep -E "^[0-9a-fA-F]+ T " | awk '{print $3}' || \
        nm "$lib_file" 2>/dev/null | grep -E "^[0-9a-fA-F]+ T " | awk '{print $3}' || true
    elif command -v objdump &> /dev/null; then
        objdump -T "$lib_file" 2>/dev/null | grep -E "^\S+ .* DF \*UND\*|\S+ g.*DF \.text" | awk '{print $NF}' || true
    elif command -v readelf &> /dev/null; then
        readelf -Ws "$lib_file" 2>/dev/null | grep -E "FUNC.*GLOBAL" | awk '{print $8}' || true
    fi
}

# Function to convert Java method name to JNI format
java_to_jni_name() {
    local class_name="$1"
    local method_name="$2"
    local package_name="$3"
    
    # Replace dots with underscores and create JNI function name
    local jni_package=$(echo "$package_name" | tr '.' '_')
    local jni_class=$(echo "$class_name" | sed 's/\.java$//')
    echo "Java_${jni_package}_${jni_class}_${method_name}"
}

# Function to extract native method declarations from Java file
extract_native_methods() {
    local java_file="$1"
    
    # Extract native method declarations using grep and sed
    grep -n "native.*(" "$java_file" | \
    sed -E 's/^([0-9]+):.*native[[:space:]]+[^[:space:]]+[[:space:]]+([a-zA-Z_][a-zA-Z0-9_]*)[[:space:]]*\(.*/\1:\2/'
}

# Get all symbols from the library
echo "Extracting symbols from $LIB_PATH..."
LIBRARY_SYMBOLS=$(get_library_symbols "$LIB_PATH")

if [[ -z "$LIBRARY_SYMBOLS" ]]; then
    echo "Error: Could not extract symbols from library"
    exit 1
fi

echo "Found $(echo "$LIBRARY_SYMBOLS" | wc -l) symbols in library"
echo ""

# Check if the package directory exists
if [[ ! -d "$JAVA_SOURCE_DIR" ]]; then
    echo "Error: Package directory $JAVA_SOURCE_DIR not found"
    exit 1
fi

# Initialize counters
total_methods=0
found_methods=0
found_with_j=0
missing_methods=0

# Find all Java files in the package directory and process them
while IFS= read -r -d '' java_file; do
    
    # Extract class name from file path
    class_name=$(basename "$java_file")
    class_display_name=$(echo "$class_name" | sed 's/\.java$//')
    
    echo "Processing: $class_display_name"
    
    # Extract native methods from this Java file
    native_methods=$(extract_native_methods "$java_file")
    
    if [[ -z "$native_methods" ]]; then
        echo "  [INFO] No native methods found"
        echo ""
        continue
    fi
    
    # Process each native method
    while IFS= read -r method_line; do
        if [[ -n "$method_line" ]]; then
            line_number=$(echo "$method_line" | cut -d: -f1)
            method_name=$(echo "$method_line" | cut -d: -f2)
            
            total_methods=$((total_methods + 1))
            
            # Generate JNI function name
            jni_function=$(java_to_jni_name "$class_name" "$method_name" "$PACKAGE_NAME")
            jni_function_with_j="${jni_function}J"
            
            echo "  Method: $method_name (line $line_number)"
            echo "    JNI name: $jni_function"
            
            # Check if the function exists in the library (exact match)
            if echo "$LIBRARY_SYMBOLS" | grep -q "^$jni_function$"; then
                echo "    [FOUND] Found exact JNI match"
                found_methods=$((found_methods + 1))
            elif echo "$LIBRARY_SYMBOLS" | grep -q "^$jni_function_with_j$"; then
                echo "    [FOUND-J] Found with 'J' suffix"
                echo "    [FOUND-J]   Library has: $jni_function_with_j"
                echo "    [FOUND-J]   Consider adding 'J' to method or renaming library function"
                found_with_j=$((found_with_j + 1))
            else
                # Check for C++ mangled names containing the method name
                cpp_matches=$(echo "$LIBRARY_SYMBOLS" | grep "$method_name" | head -3)
                if [[ -n "$cpp_matches" ]]; then
                    echo "    [FOUND-CPP] Found C++ implementation (mangled name)"
                    echo "$cpp_matches" | while read -r cpp_match; do
                        echo "      $cpp_match"
                    done
                    found_methods=$((found_methods + 1))
                else
                    echo "    [MISSING] Not found in library"
                    echo "    [MISSING]   Expected JNI: $jni_function"
                    echo "    [MISSING]   Or with J: $jni_function_with_j"
                    echo "    [MISSING]   Or C++ method containing: $method_name"
                    missing_methods=$((missing_methods + 1))
                fi
            fi
            echo ""
        fi
    done <<< "$native_methods"
done < <(find "$JAVA_SOURCE_DIR" -name "*.java" -print0)

# Summary report
echo "=== SUMMARY ==="
echo "Total native methods found: $total_methods"
echo "[FOUND] Exact matches in library: $found_methods"
echo "[FOUND-J] Found with 'J' suffix: $found_with_j"
echo "[MISSING] Missing from library: $missing_methods"

if [[ $missing_methods -gt 0 ]]; then
    echo ""
    echo "[WARNING] Some native methods are not implemented in the library!"
    exit 1
elif [[ $found_with_j -gt 0 ]]; then
    echo ""
    echo "[NOTE] Some functions found with 'J' suffix - naming inconsistency detected"
    exit 0
else
    echo ""
    echo "[SUCCESS] All native methods have corresponding implementations in the library!"
    exit 0
fi
