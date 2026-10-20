#!/usr/bin/env bash
# Re-provision Android build toolchain after a sandbox reset.
# Creates: /home/z/jdk/jdk-21.0.5+11  (Temurin JDK 21)
#          /home/z/android-cmdline/sdk (platform-tools, platforms;android-35, build-tools;35.0.0)
# Idempotent: skips parts that already exist.
set -euo pipefail

JDK_DIR="/home/z/jdk"
JDK_HOME="$JDK_DIR/jdk-21.0.5+11"
SDK_ROOT="/home/z/android-cmdline/sdk"
CMDTOOLS_ZIP="/tmp/cmdline-tools.zip"

mkdir -p "$JDK_DIR"

# ---------- JDK 21 (Temurin 21.0.5+11) ----------
if [ ! -x "$JDK_HOME/bin/javac" ]; then
  echo "[*] Downloading Temurin JDK 21.0.5+11..."
  TARBALL="$JDK_DIR/jdk21.tar.gz"
  curl -fsSL -o "$TARBALL" \
    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz"
  echo "[*] Extracting JDK..."
  tar -xzf "$TARBALL" -C "$JDK_DIR"
  rm -f "$TARBALL"
  test -x "$JDK_HOME/bin/javac" || { echo "JDK extract failed"; exit 1; }
  "$JDK_HOME/bin/java" -version
else
  echo "[=] JDK already present at $JDK_HOME"
fi

# ---------- Android SDK ----------
mkdir -p "$(dirname "$SDK_ROOT")"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[*] Downloading Android cmdline-tools..."
  curl -fsSL -o "$CMDTOOLS_ZIP" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf /tmp/cmdline-tools-tmp && mkdir -p /tmp/cmdline-tools-tmp
  unzip -q "$CMDTOOLS_ZIP" -d /tmp/cmdline-tools-tmp
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv /tmp/cmdline-tools-tmp/cmdline-tools "$SDK_ROOT/cmdline-tools/latest"
  rm -f "$CMDTOOLS_ZIP"
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -d "$SDK_ROOT/platforms/android-35" ] || [ ! -d "$SDK_ROOT/build-tools/35.0.0" ]; then
  echo "[*] Accepting licenses..."
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null 2>&1 || true
  echo "[*] Installing platform-tools, platforms;android-35, build-tools;35.0.0..."
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

echo "[*] Verifying..."
test -x "$SDK_ROOT/build-tools/35.0.0/apksigner" && echo "apksigner: OK"
test -x "$SDK_ROOT/build-tools/35.0.0/aapt2" && echo "aapt2: OK"
test -d "$SDK_ROOT/platforms/android-35" && echo "android-35: OK"
echo "[OK] Toolchain ready."
