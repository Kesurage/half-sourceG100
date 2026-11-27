#!/usr/bin/env bash
# Download Android SDK pieces for local APK builds (mirrors CI: build-tools 35, platform 34)
set -e
BASE=/home/z/android-build
mkdir -p "$BASE"
cd "$BASE"
if [ ! -d cmdline-tools ]; then
  echo "== cmdline-tools =="
  curl -sL --fail -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q cmdtools.zip
  mkdir -p cmdline-tools/latest
  mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/ 2>/dev/null || true
fi
SDKM="$BASE/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" "platforms;android-34" "build-tools;35.0.0" >/dev/null
echo "== done =="
ls "$BASE/build-tools/35.0.0" | head -5
ls "$BASE/platforms/android-34/android.jar"
