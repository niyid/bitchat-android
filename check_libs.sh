#!/bin/bash

# Script to validate .so libraries in jniLib directories
# Ensures each library matches the expected architecture format

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Counters
TOTAL=0
VALID=0
INVALID=0

# Check if readelf is available
if ! command -v readelf &> /dev/null; then
    echo -e "${RED}Error: readelf command not found. Please install binutils.${NC}"
    exit 1
fi

# Architecture mapping
declare -A ARCH_MAP
ARCH_MAP["armeabi-v7a"]="ARM"
ARCH_MAP["arm64-v8a"]="AArch64"
ARCH_MAP["x86_64"]="Advanced Micro Devices X86-64"

# Function to get architecture from .so file
get_so_arch() {
    local file="$1"
    readelf -h "$file" 2>/dev/null | grep "Machine:" | awk -F: '{print $2}' | xargs
}

# Function to validate a single .so file
validate_so() {
    local file="$1"
    local expected_arch="$2"
    local container="$3"
    
    TOTAL=$((TOTAL + 1))
    
    local actual_arch=$(get_so_arch "$file")
    
    if [ -z "$actual_arch" ]; then
        echo -e "${RED}[FAIL]${NC} $file - Could not read architecture"
        INVALID=$((INVALID + 1))
        return 1
    fi
    
    if [ "$actual_arch" == "$expected_arch" ]; then
        echo -e "${GREEN}[PASS]${NC} $file - $actual_arch"
        VALID=$((VALID + 1))
        return 0
    else
        echo -e "${RED}[FAIL]${NC} $file"
        echo -e "       Expected: $expected_arch (for $container)"
        echo -e "       Got: $actual_arch"
        INVALID=$((INVALID + 1))
        return 1
    fi
}

# Main validation logic
echo "Starting validation of .so libraries in ./jniLibs..."
echo "=================================================="

# Check if jniLib directory exists
if [ ! -d "./jniLibs" ]; then
    echo -e "${RED}Error: ./jniLibs directory not found${NC}"
    exit 1
fi

# Iterate through architecture directories
for arch_dir in ./jniLibs/*/; do
    if [ ! -d "$arch_dir" ]; then
        continue
    fi
    
    # Extract architecture name from path
    arch_name=$(basename "$arch_dir")
    
    # Get expected architecture
    expected_arch="${ARCH_MAP[$arch_name]}"
    
    if [ -z "$expected_arch" ]; then
        echo -e "${YELLOW}[WARN]${NC} Unknown architecture directory: $arch_name (skipping)"
        continue
    fi
    
    echo ""
    echo "Checking $arch_name (expecting: $expected_arch)"
    echo "----------------------------------------"
    
    # Find all .so files in this directory
    so_count=0
    while IFS= read -r -d '' so_file; do
        validate_so "$so_file" "$expected_arch" "$arch_name"
        so_count=$((so_count + 1))
    done < <(find "$arch_dir" -name "*.so" -print0)
    
    if [ $so_count -eq 0 ]; then
        echo -e "${YELLOW}No .so files found in $arch_name${NC}"
    fi
done

# Print summary
echo ""
echo "=================================================="
echo "Validation Summary"
echo "=================================================="
echo -e "Total libraries checked: $TOTAL"
echo -e "${GREEN}Valid: $VALID${NC}"
echo -e "${RED}Invalid: $INVALID${NC}"

# Exit with appropriate code
if [ $INVALID -gt 0 ]; then
    echo ""
    echo -e "${RED}Validation failed!
