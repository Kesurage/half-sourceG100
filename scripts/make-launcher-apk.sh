#!/usr/bin/env bash
# half-sourceG100 — APK packager (no gradle).
#
# Two flavors from the same source tree:
#   default            HalfSourceLauncher-<ver>.apk   (mod manager only)
#   --with-engine      HalfSourceG100-Engine-<ver>.apk (ONE APK: manager + engine)
#
# The engine flavor merges the prebuilt nillerusr old-port engine APK
# (docs/ENGINE-PACK.md): its classes.dex (SDLActivity + ValveActivity2 glue)
# is d8-merged with ours, its lib/armeabi-v7a/*.so and assets/ are bundled.
#
# Requirements: scripts/setup-android-build.sh (+ scripts/build-jni.sh for the
# tier2 classifier).
# Usage: scripts/make-launcher-apk.sh [--with-engine] [ENGINE_APK=path]
#   ENGINE_APK can also point at a locally cached copy; without it the script
#   downloads the pinned build once into engine-pack/.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APP="$ROOT/android-app"
BASE="${ANDROID_BUILD_BASE:-/home/z/android-build}"
BT="$BASE/build-tools/34.0.0"
PLATFORM="$BASE/platforms/android-34/android.jar"
[ -f "$PLATFORM" ] || { echo "ERROR: $PLATFORM missing — run scripts/setup-android-build.sh" >&2; exit 1; }
if [ -d "$BASE/jdk17" ]; then export PATH="$BASE/jdk17/bin:$PATH"; fi

WITH_ENGINE=0
for arg in "$@"; do
  case "$arg" in
    --with-engine) WITH_ENGINE=1 ;;
  esac
done
ENGINE_APK="${ENGINE_APK:-}"
ENGINE_URL="https://raw.githubusercontent.com/nillerusr/just-old-shit/srceng-old-port/srceng-debug.apk"
ENGINE_CACHE="$ROOT/engine-pack/srceng-debug.apk"

VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
STAGE="$APP/build"
if [ "$WITH_ENGINE" = "1" ]; then
  OUT="$ROOT/dist/HalfSourceG100-Engine-$VERSION.apk"
else
  OUT="$ROOT/dist/HalfSourceLauncher-$VERSION.apk"
fi
rm -rf "$STAGE"
mkdir -p "$STAGE/gen" "$STAGE/classes" "$STAGE/stub-classes" "$STAGE/apk" "$ROOT/dist"

# ---- 1. resources + R.java + link ------------------------------------------
"$BT/aapt2" compile --dir "$APP/res" -o "$STAGE/res.zip"
"$BT/aapt2" link -o "$STAGE/base.apk" -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" --java "$STAGE/gen" "$STAGE/res.zip" \
  --auto-add-overlay

# ---- 2. compile-only stubs (engine dex classes; NEVER dexed) ---------------
find "$APP/stubs" -name '*.java' > "$STAGE/stubs.txt"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLATFORM" -d "$STAGE/stub-classes" @"$STAGE/stubs.txt"

# ---- 3. java -> classes ------------------------------------------------------
find "$STAGE/gen" "$APP/src" -name '*.java' > "$STAGE/sources.txt"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLATFORM:$STAGE/stub-classes" -d "$STAGE/classes" @"$STAGE/sources.txt"

# ---- 4. dex ------------------------------------------------------------------
find "$STAGE/classes" -name '*.class' > "$STAGE/classes.txt"
if [ "$WITH_ENGINE" = "1" ]; then
  # fetch the pinned engine build if needed
  if [ -z "$ENGINE_APK" ]; then
    if [ ! -f "$ENGINE_CACHE" ]; then
      mkdir -p "$ROOT/engine-pack"
      echo "downloading engine pack (pinned old-port build) ..."
      curl -sL --fail -o "$ENGINE_CACHE" "$ENGINE_URL"
    fi
    ENGINE_APK="$ENGINE_CACHE"
  fi
  [ -f "$ENGINE_APK" ] || { echo "ERROR: engine APK not found: $ENGINE_APK" >&2; exit 1; }
  ENG="$STAGE/engine"
  mkdir -p "$ENG"
  unzip -q -o "$ENGINE_APK" 'classes.dex' 'lib/armeabi-v7a/*' 'assets/*' -d "$ENG"
  # d8 merge: engine dex + our classes -> single classes.dex
  "$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$STAGE/apk" \
    "$ENG/classes.dex" $(cat "$STAGE/classes.txt")
else
  "$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$STAGE/apk" \
    $(cat "$STAGE/classes.txt")
fi

# ---- 5. classes.dex + native libs + assets into the apk ----------------------
( cd "$STAGE/apk" && zip -qj "$STAGE/base.apk" classes.dex )

# Native libs MUST live at lib/<abi>/*.so inside the APK (PackageManager only
# scans that path — the v0.3.0 build shipped the classifier at a wrong root
# path and it never loaded; this staging fixes it for good).
NAT="$STAGE/nativelibs"
rm -rf "$NAT"
mkdir -p "$NAT/lib"
if [ "$WITH_ENGINE" = "1" ]; then
  # engine flavor: 32-bit ONLY (the engine .so set is armeabi-v7a; a stray
  # arm64 dir would flip the process to 64-bit and break loading)
  cp -r "$STAGE/engine/lib/armeabi-v7a" "$NAT/lib/"
  if [ -f "$APP/libs/armeabi-v7a/libtier2jni.so" ]; then
    cp "$APP/libs/armeabi-v7a/libtier2jni.so" "$NAT/lib/armeabi-v7a/"
  else
    echo "NOTE: no armeabi-v7a tier2 classifier (run build-jni.sh armv7a) — catalog degrades to raw listing"
  fi
  ( cd "$STAGE/engine" && zip -qr "$STAGE/base.apk" assets )
else
  if [ -d "$APP/libs/arm64-v8a" ]; then
    cp -r "$APP/libs/arm64-v8a" "$NAT/lib/"
  else
    echo "NOTE: no native libs under android-app/libs — building launcher-only APK"
  fi
fi
( cd "$NAT" && zip -qr "$STAGE/base.apk" lib )

# ---- 6. align + sign ----------------------------------------------------------
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
echo "BUILT: $OUT  ($(du -h "$OUT" | cut -f1))"
"$BT/aapt2" dump badging "$OUT" | head -3
echo "Install: adb install -r $OUT"
