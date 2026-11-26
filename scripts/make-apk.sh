#!/bin/bash
# half-sourceG100 — Phase 0 minimal APK packager (no gradle needed)
# Packs: engine .so set (from build-engine-aarch64.sh) + SDLActivity-style shell
#        into an installable arm64-v8a APK with native-resolution GLES surface.
#
# Requirements: Android SDK build-tools (aapt, d8, zipalign, apksigner) + java 11
# Usage: scripts/make-apk.sh <workdir> <path-to-hl2-content> [keystore]
#   <path-to-hl2-content> = directory containing hl2/, platform/ (your own Steam copy)
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORKDIR="${1:?usage: make-apk.sh <workdir> <hl2-content> [keystore]}"
CONTENT="${2:?usage: make-apk.sh <workdir> <hl2-content> [keystore]}"
KEYSTORE="${3:-$WORKDIR/debug.keystore}"
ENGINE_DIR="$WORKDIR/source-engine"
APP_SRC="$ROOT/android-app"
STAGE="$WORKDIR/apk-stage"
OUT="$WORKDIR/half-sourceG100.apk"

BT="${ANDROID_BUILD_TOOLS:?set ANDROID_BUILD_TOOLS to your SDK build-tools dir}"
PLATFORM="${ANDROID_PLATFORM_JAR:?set ANDROID_PLATFORM_JAR to android.jar (API 21+)}"

# ---- 1. collect native libs ----------------------------------------------
SO_SRC="$(find "$ENGINE_DIR/build" -type d -name 'lib' | head -1)"
[ -n "$SO_SRC" ] || { echo "ERROR: no build output under $ENGINE_DIR/build — run build-engine-aarch64.sh first" >&2; exit 1; }

rm -rf "$STAGE" && mkdir -p "$STAGE/lib/arm64-v8a" "$STAGE/assets" "$STAGE/obj" "$STAGE/classes"
cp -v "$SO_SRC"/*.so "$STAGE/lib/arm64-v8a/" 2>/dev/null || cp -v "$SO_SRC"/aarch64/*.so "$STAGE/lib/arm64-v8a/"

# ---- 2. game content goes to assets as a zip (extracted to filesDir on first run) ----
# Assets are limited to 2GB total and each entry <500MB (AGL); large VPKs exceed this,
# so for Phase 0 we instead document sideloading: content lives in
#   /sdcard/Android/data/com.z.halfsourceg100/files/srceng/
# and this step is a no-op unless the dir holds a small test set.
if [ -d "$CONTENT/hl2" ]; then
  echo "NOTE: content will be read from app external files dir; skipping asset packing"
fi

# ---- 3. compile the shell -------------------------------------------------
"$BT/aapt" package -f -m \
  -S "$APP_SRC/res" -J "$STAGE/obj" -M "$APP_SRC/AndroidManifest.xml" \
  -I "$PLATFORM"

javac -source 11 -target 11 -classpath "$PLATFORM" \
  -d "$STAGE/classes" $(find "$STAGE/obj" "$APP_SRC/src" -name '*.java')

"$BT/d8" --release --lib "$PLATFORM" --output "$STAGE" \
  $(find "$STAGE/classes" -name '*.class')

# ---- 4. package ------------------------------------------------------------
"$BT/aapt" package -f \
  -S "$APP_SRC/res" -M "$APP_SRC/AndroidManifest.xml" \
  -I "$PLATFORM" -F "$STAGE/base.apk"

cd "$STAGE" && zip -qj base.apk classes.dex && cd "$ROOT"
(cd "$STAGE" && aapt add base.apk lib/arm64-v8a/*.so) || \
  (cd "$STAGE" && zip -q base.apk lib/arm64-v8a/*.so)

# ---- 5. sign ---------------------------------------------------------------
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -dname 'CN=Android Debug' \
    -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/zipalign" -f 4 "$STAGE/base.apk" "$STAGE/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --out "$OUT" "$STAGE/aligned.apk"

echo ""
echo "Built: $OUT"
echo "Install: adb install -r $OUT"
echo "Then push HL2 content to:"
echo "  /sdcard/Android/data/com.z.halfsourceg100/files/srceng/{hl2,platform,sourceengine}"
