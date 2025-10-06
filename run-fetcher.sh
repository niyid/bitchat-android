#!/bin/bash
# Save as: run-fetcher.sh in your project root

# Compile first
./gradlew compileDebugJavaWithJavac compileDebugKotlin

# Find the compiled class
CLASS_FILE="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/com/bitchat/android/monero/utils/MoneroTransactionFetcher.class"

if [ ! -f "$CLASS_FILE" ]; then
    echo "Error: Class not compiled. Looking in:"
    find app/build -name "MoneroTransactionFetcher.class"
    exit 1
fi

# Get the classes directory
CLASSES_DIR="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"

# Get org.json from Gradle cache
JSON_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.json -name "json-*.jar" | head -1)

if [ -z "$JSON_JAR" ]; then
    echo "Error: org.json not found. Run: ./gradlew build"
    exit 1
fi

echo "Running MoneroTransactionFetcher..."
echo ""

# Run it
java -cp "${CLASSES_DIR}:${JSON_JAR}" \
    com.bitchat.android.monero.utils.MoneroTransactionFetcher "$@"
