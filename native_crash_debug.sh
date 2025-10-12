#!/bin/bash

# BitChat Native Crash Debugging Toolkit
# This script helps analyze native crashes in Android apps using Monero JNI

set -e

echo "=== BitChat Native Crash Debugging Toolkit ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
APP_PACKAGE="com.bitchat.droid"
OUTPUT_DIR="./crash_analysis"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo -e "${GREEN}[1/7] Checking ADB connection...${NC}"
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected via ADB${NC}"
    exit 1
fi
echo "✓ Device connected"
echo ""

echo -e "${GREEN}[2/7] Pulling tombstone files...${NC}"
TOMBSTONE_DIR="$OUTPUT_DIR/tombstones_$TIMESTAMP"
mkdir -p "$TOMBSTONE_DIR"

# Try different tombstone locations (varies by Android version)
adb pull /data/tombstones/ "$TOMBSTONE_DIR/" 2>/dev/null || \
adb shell "ls /data/tombstones/" 2>/dev/null | while read file; do
    adb shell "cat /data/tombstones/$file" > "$TOMBSTONE_DIR/$file" 2>/dev/null
done || echo "Note: Some tombstones may require root access"

# Find the most recent tombstone
LATEST_TOMBSTONE=$(ls -t "$TOMBSTONE_DIR"/* 2>/dev/null | head -1)
if [ -n "$LATEST_TOMBSTONE" ]; then
    echo "✓ Tombstone files pulled to: $TOMBSTONE_DIR"
    echo "  Latest: $(basename $LATEST_TOMBSTONE)"
else
    echo -e "${YELLOW}⚠ No tombstone files found${NC}"
fi
echo ""

echo -e "${GREEN}[3/7] Capturing full logcat...${NC}"
LOGCAT_FILE="$OUTPUT_DIR/logcat_full_$TIMESTAMP.log"
adb logcat -d > "$LOGCAT_FILE"
echo "✓ Full logcat saved to: $LOGCAT_FILE"
echo ""

echo -e "${GREEN}[4/7] Extracting crash-related logs...${NC}"
CRASH_LOG="$OUTPUT_DIR/crash_extract_$TIMESTAMP.log"

# Extract logs around the crash time
echo "=== Native Crash Logs ===" > "$CRASH_LOG"
echo "" >> "$CRASH_LOG"

# Look for DEBUG/FATAL logs
grep -E "(DEBUG|FATAL|libc|SIGSEGV|SIGABRT)" "$LOGCAT_FILE" >> "$CRASH_LOG" 2>/dev/null || true

# Extract WalletSuite specific logs
echo "" >> "$CRASH_LOG"
echo "=== WalletSuite Logs ===" >> "$CRASH_LOG"
grep "WalletSuite" "$LOGCAT_FILE" >> "$CRASH_LOG" 2>/dev/null || true

echo "✓ Crash logs extracted to: $CRASH_LOG"
echo ""

echo -e "${GREEN}[5/7] Pulling native libraries from device...${NC}"
LIBS_DIR="$OUTPUT_DIR/native_libs_$TIMESTAMP"
mkdir -p "$LIBS_DIR"

# Get the app's native library directory
APP_LIB_PATH=$(adb shell pm path "$APP_PACKAGE" | grep base | cut -d: -f2)
if [ -n "$APP_LIB_PATH" ]; then
    # Extract lib directory from APK path
    LIB_BASE=$(dirname "$APP_LIB_PATH")
    
    # Try to pull native libraries
    adb pull /data/app/*${APP_PACKAGE}*/lib/ "$LIBS_DIR/" 2>/dev/null || \
    echo -e "${YELLOW}⚠ Could not pull native libraries (may need root)${NC}"
    
    if [ -d "$LIBS_DIR" ] && [ "$(ls -A $LIBS_DIR)" ]; then
        echo "✓ Native libraries pulled to: $LIBS_DIR"
        ls "$LIBS_DIR"/*/*.so 2>/dev/null | head -5
    fi
else
    echo -e "${YELLOW}⚠ Could not locate app installation${NC}"
fi
echo ""

echo -e "${GREEN}[6/7] Extracting stack trace...${NC}"
STACK_FILE="$OUTPUT_DIR/stack_trace_$TIMESTAMP.txt"

if [ -n "$LATEST_TOMBSTONE" ]; then
    echo "=== Stack Trace from Tombstone ===" > "$STACK_FILE"
    echo "" >> "$STACK_FILE"
    
    # Extract backtrace section
    sed -n '/backtrace:/,/^$/p' "$LATEST_TOMBSTONE" >> "$STACK_FILE" 2>/dev/null || \
    cat "$LATEST_TOMBSTONE" >> "$STACK_FILE"
    
    echo "✓ Stack trace saved to: $STACK_FILE"
    echo ""
    echo "Preview:"
    head -20 "$STACK_FILE"
else
    echo -e "${YELLOW}⚠ No tombstone available for stack trace${NC}"
fi
echo ""

echo -e "${GREEN}[7/7] Generating crash report...${NC}"
REPORT_FILE="$OUTPUT_DIR/crash_report_$TIMESTAMP.md"

cat > "$REPORT_FILE" << EOF
# BitChat Native Crash Report
Generated: $(date)

## Summary
- Package: $APP_PACKAGE
- Crash Type: Native crash in Monero JNI layer
- Location: WalletSuite.sendTransaction

## Crash Context
From logs, the crash occurred during:
- **Operation**: Sending 0.01 XMR transaction
- **Stage**: [3/4] CREATING TRANSACTION
- **Thread**: pool-3-thread-1 (background thread)

## Files Generated
- Full logcat: $LOGCAT_FILE
- Crash extract: $CRASH_LOG
- Stack trace: $STACK_FILE
- Tombstones: $TOMBSTONE_DIR
- Native libs: $LIBS_DIR

## Next Steps

### 1. Analyze the Stack Trace
\`\`\`bash
cat $STACK_FILE
\`\`\`

### 2. Symbolicate with NDK (if you have debug symbols)
\`\`\`bash
ndk-stack -sym path/to/your/obj/local/armeabi-v7a -dump $LATEST_TOMBSTONE
\`\`\`

### 3. Check for Common Issues
- [ ] Memory corruption in JNI layer
- [ ] Invalid pointer dereference
- [ ] Wallet state corruption during reinitialization
- [ ] Transaction creation buffer overflow
- [ ] Thread safety issues in wallet operations

### 4. Look for Patterns
Search for:
- SIGSEGV (segmentation fault)
- SIGABRT (abort signal)
- "Fatal signal" messages
- JNI errors

### 5. Add Defensive Code
In WalletSuite.sendTransaction around line creating transaction:
- Add null checks before JNI calls
- Validate wallet state before transaction
- Add try-catch around native calls
- Implement timeout for transaction creation

## Suspected Root Cause
Based on the logs, the crash happens during:
\`\`\`
[TX_EXEC] [3/4] CREATING TRANSACTION
\`\`\`

This suggests:
1. The wallet reinitialization succeeded
2. Balance checks passed
3. Crash occurred in the native createTransaction call

Likely culprits:
- Monero library JNI binding issue
- Invalid memory access in native code
- Unhandled exception in native layer
EOF

echo "✓ Crash report generated: $REPORT_FILE"
echo ""

echo -e "${GREEN}=== Analysis Complete ===${NC}"
echo ""
echo "All files saved in: $OUTPUT_DIR"
echo ""
echo -e "${YELLOW}Quick Actions:${NC}"
echo "1. View crash report:  cat $REPORT_FILE"
echo "2. View stack trace:   cat $STACK_FILE"
echo "3. Search for signals: grep -i 'signal' $CRASH_LOG"
echo ""

# Check if NDK is available
if command -v ndk-stack &> /dev/null; then
    echo -e "${GREEN}✓ ndk-stack is available${NC}"
    if [ -n "$LATEST_TOMBSTONE" ]; then
        echo "Run: ndk-stack -sym path/to/symbols -dump $LATEST_TOMBSTONE"
    fi
else
    echo -e "${YELLOW}⚠ ndk-stack not found in PATH${NC}"
    echo "Install Android NDK to symbolicate crashes"
fi
