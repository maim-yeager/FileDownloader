#!/usr/bin/env bash
# =============================================================
# FileDownloader - Automated Build Script
# Usage: bash build.sh [debug|release]
# =============================================================

set -e

BUILD_TYPE=${1:-debug}
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_OUTPUT="$PROJECT_DIR/app/build/outputs/apk/$BUILD_TYPE"

echo "============================================="
echo "  File Downloader - Auto Build Script"
echo "  Build type: $BUILD_TYPE"
echo "============================================="

# ── Step 1: Check Java ────────────────────────────────────────
echo ""
echo "[1/5] Checking Java installation..."
if ! command -v java &>/dev/null; then
  echo "❌ ERROR: Java not found."
  echo "   Install JDK 17: sudo apt install openjdk-17-jdk"
  echo "   Or download from: https://adoptium.net"
  exit 1
fi
JAVA_VER=$(java -version 2>&1 | grep -oP '(?<=version ")[0-9]+' | head -1)
echo "✅ Java found (version $JAVA_VER)"

# ── Step 2: Check Android SDK ─────────────────────────────────
echo ""
echo "[2/5] Checking Android SDK..."
if [ -z "$ANDROID_HOME" ]; then
  # Common SDK locations
  for candidate in \
    "$HOME/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "/usr/local/android-sdk" \
    "/opt/android-sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
  echo "❌ ERROR: Android SDK not found."
  echo "   Set ANDROID_HOME: export ANDROID_HOME=~/Android/Sdk"
  echo "   Install via Android Studio: https://developer.android.com/studio"
  exit 1
fi
echo "✅ Android SDK found at: $ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools/bin"

# ── Step 3: Fix gradle-wrapper.jar if stub ────────────────────
echo ""
echo "[3/5] Checking Gradle wrapper..."
WRAPPER_JAR="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar"
JAR_SIZE=$(stat -c%s "$WRAPPER_JAR" 2>/dev/null || echo "0")

if [ "$JAR_SIZE" -lt "10000" ]; then
  echo "⚠  Wrapper JAR is a stub ($JAR_SIZE bytes). Downloading real wrapper..."

  # Try to download from Gradle CDN
  WRAPPER_URL="https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
  if command -v curl &>/dev/null; then
    curl -L --silent --fail --max-time 60 "$WRAPPER_URL" -o "$WRAPPER_JAR" || true
  elif command -v wget &>/dev/null; then
    wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR" || true
  fi

  JAR_SIZE_AFTER=$(stat -c%s "$WRAPPER_JAR" 2>/dev/null || echo "0")
  if [ "$JAR_SIZE_AFTER" -lt "10000" ]; then
    echo ""
    echo "❌ Could not download gradle-wrapper.jar automatically."
    echo "   Please do ONE of the following:"
    echo ""
    echo "   Option A - Open in Android Studio (it will fix this automatically)"
    echo "   Option B - Copy the real wrapper from another Android project:"
    echo "     cp /path/to/other/project/gradle/wrapper/gradle-wrapper.jar \\"
    echo "        $WRAPPER_JAR"
    echo "   Option C - Install Gradle and generate the wrapper:"
    echo "     sudo apt install gradle"
    echo "     cd $PROJECT_DIR && gradle wrapper --gradle-version 8.2"
    exit 1
  else
    echo "✅ Gradle wrapper downloaded ($JAR_SIZE_AFTER bytes)"
  fi
else
  echo "✅ Gradle wrapper OK ($JAR_SIZE bytes)"
fi

# ── Step 4: Make gradlew executable ──────────────────────────
chmod +x "$PROJECT_DIR/gradlew"

# ── Step 5: Build ─────────────────────────────────────────────
echo ""
echo "[4/5] Running Gradle build (this may take a few minutes on first run)..."
echo "   Downloading dependencies from internet..."
echo ""

cd "$PROJECT_DIR"

if [ "$BUILD_TYPE" = "release" ]; then
  ./gradlew assembleRelease --stacktrace
else
  ./gradlew assembleDebug --stacktrace
fi

BUILD_EXIT=$?

# ── Results ───────────────────────────────────────────────────
echo ""
echo "[5/5] Build results:"

if [ $BUILD_EXIT -eq 0 ]; then
  echo ""
  echo "✅ ════════════════════════════════════════"
  echo "   BUILD SUCCESSFUL!"
  echo "   ════════════════════════════════════════"
  echo ""

  # Find and display APK
  if [ "$BUILD_TYPE" = "debug" ]; then
    APK_FILE="$APK_OUTPUT/app-debug.apk"
  else
    APK_FILE="$APK_OUTPUT/app-release-unsigned.apk"
  fi

  if [ -f "$APK_FILE" ]; then
    APK_SIZE=$(du -sh "$APK_FILE" | cut -f1)
    echo "📦 APK File: $APK_FILE"
    echo "📏 APK Size: $APK_SIZE"
    echo ""

    # Ask to install on connected device
    if command -v adb &>/dev/null; then
      DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)
      if [ "$DEVICES" -gt "0" ]; then
        echo "📱 Connected device detected!"
        read -p "   Install APK on device? (y/n) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
          adb install -r "$APK_FILE"
          echo "✅ App installed! Look for 'File Downloader' on your device."
        fi
      fi
    fi

    # Copy APK to project root for easy access
    DEST_APK="$PROJECT_DIR/FileDownloader-$BUILD_TYPE.apk"
    cp "$APK_FILE" "$DEST_APK"
    echo ""
    echo "🎯 APK copied to: $DEST_APK"
  fi
else
  echo ""
  echo "❌ BUILD FAILED (exit code: $BUILD_EXIT)"
  echo ""
  echo "Common fixes:"
  echo "  1. Make sure Android SDK is installed and ANDROID_HOME is set"
  echo "  2. Make sure you have internet (to download dependencies)"
  echo "  3. Try: ./gradlew clean assembleDebug"
  echo "  4. Open in Android Studio for detailed error messages"
  exit $BUILD_EXIT
fi
