#!/bin/bash
set -e

REQUIRED_JAVA_MAJOR=21

echo "=== AmpelPilot Android 14+ Build Script ==="

# Check current Java version
get_java_major() {
    java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/'
}

CURRENT_JAVA=$(get_java_major 2>/dev/null || echo "0")
echo "Current Java version: $CURRENT_JAVA"

if [ "$CURRENT_JAVA" != "$REQUIRED_JAVA_MAJOR" ]; then
    echo "Java $REQUIRED_JAVA_MAJOR required, found Java $CURRENT_JAVA."

    # Try switching via SDKMAN
    if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        echo "SDKMAN detected. Switching to Java $REQUIRED_JAVA_MAJOR..."
        source "$HOME/.sdkman/bin/sdkman-init.sh"

        # Check if Java 21 is already installed via sdkman
        JAVA21_CANDIDATE=$(sdk list java 2>/dev/null | grep -oP "21\.\S+" | grep -i "tem" | head -1)
        if [ -z "$JAVA21_CANDIDATE" ]; then
            JAVA21_CANDIDATE=$(sdk list java 2>/dev/null | grep -oP "21\.\S+" | head -1)
        fi

        if [ -n "$JAVA21_CANDIDATE" ]; then
            sdk use java "$JAVA21_CANDIDATE"
        else
            echo "No Java 21 candidate found in SDKMAN. Installing..."
            sdk install java 21.0.11-tem
            sdk use java 21.0.11-tem
        fi

        # Verify switch worked
        CURRENT_JAVA=$(get_java_major)
        if [ "$CURRENT_JAVA" != "$REQUIRED_JAVA_MAJOR" ]; then
            echo "ERROR: Failed to switch to Java $REQUIRED_JAVA_MAJOR. Current: $CURRENT_JAVA"
            exit 1
        fi
        echo "Switched to Java $REQUIRED_JAVA_MAJOR successfully."
    else
        echo "ERROR: SDKMAN not found. Please install Java $REQUIRED_JAVA_MAJOR manually."
        echo "  Install SDKMAN: curl -s https://get.sdkman.io | bash"
        echo "  Then: sdk install java 21.0.11-tem"
        exit 1
    fi
else
    echo "Java $REQUIRED_JAVA_MAJOR already active. Good."
fi

echo ""
echo "Building AmpelPilot..."
./gradlew assembleDebug

echo ""
echo "=== Build complete ==="
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
