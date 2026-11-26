#!/usr/bin/env bash
# half-sourceG100 — Phase 3 launcher APK packager (no gradle).
# Builds the launcher (catalog + tier2 classifier + import + settings).
# The engine .so set is optional: launcher-only builds explain the state
# in-app until the Phase 0 engine build lands.
#
# Requirements: scripts/setup-android-build.sh + scripts/build-jni.sh
# Usage: scripts/make-launcher-apk.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APP="$ROOT/android-app"
BASE="${ANDROID_BUILD_BASE:-/home/z/android-build}"
BT="$BASE/build-tools/34.0.0"
PLATFORM="$BASE/platforms/android-34/android.jar"
[ -f "$PLATFORM" ] || { echo "ERROR: $PLATFORM missing — run scripts/setup-android-build.sh" >&2; exit 1; }
if [ -d "$BASE/jdk17" ]; then export PATH="$BASE/jdk17/bin:$PATH"; fi

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
STAGE="$APP/build"
OUT="$ROOT/dist/HalfSourceLauncher-$VERSION.apk"
rm -rf "$STAGE"
mkdir -p "$STAGE/gen" "$STAGE/classes" "$STAGE/apk" "$ROOT/dist"

# ---- 1. resources + R.java + link ------------------------------------------
"$BT/aapt2" compile --dir "$APP/res" -o "$STAGE/res.zip"
"$BT/aapt2" link -o "$STAGE/base.apk" -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" --java "$STAGE/gen" "$STAGE/res.zip" \
  --auto-add-overlay

# ---- 2. java -> classes ------------------------------------------------------
find "$STAGE/gen" "$APP/src" -name '*.java' > "$STAGE/sources.txt"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLATFORM" -d "$STAGE/classes" @"$STAGE/sources.txt"

# ---- 3. dex ------------------------------------------------------------------
find "$STAGE/classes" -name '*.class' > "$STAGE/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$STAGE/apk" \
  $(cat "$STAGE/classes.txt")

# ---- 4. classes.dex + native libs into the apk --------------------------------
( cd "$STAGE/apk" && zip -qj "$STAGE/base.apk" classes.dex )
if [ -d "$APP/libs/arm64-v8a" ]; then
  ( cd "$APP/libs" && zip -q "$STAGE/base.apk" arm64-v8a/*.so )
else
  echo "NOTE: no native libs under android-app/libs — building launcher-only APK"
fi

# ---- 5. align + sign ----------------------------------------------------------
"$BT/zipalign" -f 4 "$STAGE/base.apk" "$STAGE/aligned.apk"
KEY="$ROOT/dist/debug.keystore"
if [ ! -f "$KEY" ]; then
  keytool -genkeypair -keystore "$KEY" -alias halfsource -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass halfsource -keypass halfsource \
    -dname 'CN=half-sourceG100'
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:halfsource --key-pass pass:halfsource \
  --out "$OUT" "$STAGE/aligned.apk"
"$BT/apksigner" verify "$OUT"

echo ""
echo "BUILT: $OUT"
"$BT/aapt2" dump badging "$OUT" | head -3
echo "Install: adb install -r $OUT"
