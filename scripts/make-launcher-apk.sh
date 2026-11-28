#!/usr/bin/env bash
# half-sourceG100 — APK packager (no gradle).
#
# Two flavors from the same source tree:
#   default            HalfSourceLauncher-<ver>.apk   (mod manager only)
#   --with-engine      HalfSourceG100-Engine-<ver>.apk (ONE APK: manager + engine)
#
# Since v0.5.0 the engine flavor bundles the modern nillerusr-lineage engine
# v1.17.0025 (aarch64, togl/GLES3, liblauncher.so — the engine generation of
# AruMoon's "Source Engine for Android"). Its Java glue (SDLActivity,
# ValveActivity2, ...) is vendored in android-app/engine-src and compiled
# FROM SOURCE against our resources — no d8 dex-merge, no broken resource
# IDs. Only lib/arm64-v8a/*.so and assets/ are taken from the pinned engine
# APK (fetched from this repo's own "engine-pack" release, so the URL never
# expires and CI builds are reproducible). See docs/ENGINE-PACK.md.
#
# Requirements: scripts/setup-local-sdk.sh (SDK build-tools 35 + platform 34
# + NDK) and scripts/build-jni.sh for the tier2 classifier + crash guard.
# Usage: scripts/make-launcher-apk.sh [--with-engine] [ENGINE_APK=path]
#
# ENGINE_DIR (env, optional): directory with a SELF-BUILT engine .so set
# (the output of scripts/build-engine-aarch64.sh — engine/build). When set,
# lib/arm64-v8a comes from that build instead of the pinned v1.17.0025
# pack; assets/ (extras_dir.vpk touch UI + fonts) still come from the
# pinned pack — they are content, not code. Since v0.7.0 the shipped engine
# is OUR OWN build of the open-source tree (AruMoon/source-engine @
# 87150cc028 + engine-patches/), so engine-level fixes are possible.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APP="$ROOT/android-app"
BASE="${ANDROID_BUILD_BASE:-/home/z/android-build}"
BT="$BASE/build-tools/34.0.0"
PLATFORM="$BASE/platforms/android-34/android.jar"
[ -f "$PLATFORM" ] || { echo "ERROR: $PLATFORM missing — run scripts/setup-local-sdk.sh" >&2; exit 1; }
if [ -d "$BASE/jdk17" ]; then export PATH="$BASE/jdk17/bin:$PATH"; fi

WITH_ENGINE=0
for arg in "$@"; do
  case "$arg" in
    --with-engine) WITH_ENGINE=1 ;;
  esac
done
ENGINE_APK="${ENGINE_APK:-}"
# Pinned engine build, hosted as a permanent asset of this repo's
# "engine-pack" release (uploaded once; stable URL, no expiry, no mirror
# trust issues — signature verified against nillerusr's public debug key).
ENGINE_URL="https://github.com/Kesurage/half-sourceG100/releases/download/engine-pack/srceng-engine-v1.17.0025.apk"
ENGINE_SHA256="6b82a5c93753650dd2f22065a4040c722256bf9570f6f64c6dca3841db2899bb"
ENGINE_CACHE="$ROOT/engine-pack/srceng-engine-v1.17.0025.apk"

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

# ---- 2. compile-only stubs (engine classes for the LAUNCHER-ONLY flavor;
#         never dexed — the engine flavor compiles the real engine-src) ------
find "$APP/stubs" -name '*.java' > "$STAGE/stubs.txt"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLATFORM" -d "$STAGE/stub-classes" @"$STAGE/stubs.txt"

# ---- 3. java -> classes ------------------------------------------------------
if [ "$WITH_ENGINE" = "1" ]; then
  # engine flavor: our launcher + the REAL engine glue compiled from source.
  # R references resolve against OUR resources at compile time — that is the
  # whole point of vendoring engine-src (no inlined-foreign-R-id crashes).
  find "$STAGE/gen" "$APP/src" "$APP/engine-src" -name '*.java' > "$STAGE/sources.txt"
  javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
    -classpath "$PLATFORM" -d "$STAGE/classes" @"$STAGE/sources.txt"
else
  find "$STAGE/gen" "$APP/src" -name '*.java' > "$STAGE/sources.txt"
  javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
    -classpath "$PLATFORM:$STAGE/stub-classes" -d "$STAGE/classes" @"$STAGE/sources.txt"
fi

# ---- 4. dex ------------------------------------------------------------------
find "$STAGE/classes" -name '*.class' > "$STAGE/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM" --output "$STAGE/apk" \
  $(cat "$STAGE/classes.txt")

# ---- 5. classes.dex + native libs + assets into the apk ----------------------
( cd "$STAGE/apk" && zip -qj "$STAGE/base.apk" classes.dex )

# Native libs MUST live at lib/<abi>/*.so inside the APK (PackageManager only
# scans that path — the v0.3.0 build shipped the classifier at a wrong root
# path and it never loaded; this staging fixes it for good).
NAT="$STAGE/nativelibs"
rm -rf "$NAT"
mkdir -p "$NAT/lib"
if [ "$WITH_ENGINE" = "1" ]; then
  ENG="$STAGE/engine"
  mkdir -p "$ENG"
  if [ -n "$ENGINE_DIR" ]; then
    # SELF-BUILT engine: our patches, our build (see engine-patches/ and
    # scripts/build-engine-aarch64.sh). SDL is prebuilt from the engine
    # tree's lib submodule; everything else is compiled from source.
    echo "engine: self-built set from $ENGINE_DIR"
    mkdir -p "$NAT/lib/arm64-v8a"
    find "$ENGINE_DIR" -name '*.so' -exec cp '{}' "$NAT/lib/arm64-v8a/" \;
    SDL_SRC="$(dirname "$ENGINE_DIR")/lib/android/aarch64/libSDL2.so"
    [ -f "$SDL_SRC" ] || { echo "ERROR: $SDL_SRC missing (engine lib submodule)" >&2; exit 1; }
    cp "$SDL_SRC" "$NAT/lib/arm64-v8a/"
    # assets (touch-UI VPK + fonts) from the pinned pack — content, not code
    if [ -z "$ENGINE_APK" ]; then
      if [ ! -f "$ENGINE_CACHE" ]; then
        mkdir -p "$ROOT/engine-pack"
        echo "downloading engine pack (pinned v1.17.0025, aarch64) for assets ..."
        curl -sL --fail -o "$ENGINE_CACHE" "$ENGINE_URL"
      fi
      ENGINE_APK="$ENGINE_CACHE"
    fi
    [ -f "$ENGINE_APK" ] || { echo "ERROR: engine APK not found: $ENGINE_APK" >&2; exit 1; }
    unzip -q -o "$ENGINE_APK" 'assets/*' -d "$ENG"
  else
    # fetch the pinned engine build if needed
    if [ -z "$ENGINE_APK" ]; then
      if [ ! -f "$ENGINE_CACHE" ]; then
        mkdir -p "$ROOT/engine-pack"
        echo "downloading engine pack (pinned v1.17.0025, aarch64) ..."
        curl -sL --fail -o "$ENGINE_CACHE" "$ENGINE_URL"
      fi
      ENGINE_APK="$ENGINE_CACHE"
    fi
    [ -f "$ENGINE_APK" ] || { echo "ERROR: engine APK not found: $ENGINE_APK" >&2; exit 1; }
    if [ "$ENGINE_SHA256" != "SET_AT_UPLOAD" ]; then
      echo "$ENGINE_SHA256  $ENGINE_APK" | sha256sum -c - || {
        echo "ERROR: engine pack checksum mismatch — refusing to bundle" >&2; exit 1; }
    fi
    unzip -q -o "$ENGINE_APK" 'lib/arm64-v8a/*' 'assets/*' -d "$ENG"
    cp -r "$STAGE/engine/lib/arm64-v8a" "$NAT/lib/"
  fi
  if [ -f "$APP/libs/arm64-v8a/libtier2jni.so" ]; then
    cp "$APP/libs/arm64-v8a/libtier2jni.so" "$NAT/lib/arm64-v8a/"
  else
    echo "NOTE: no arm64-v8a tier2 classifier (run build-jni.sh aarch64) — catalog degrades to raw listing"
  fi
  # engine assets: touch-UI VPK (extras_dir.vpk) + fonts, mounted via
  # AAssetManager / extracted by ExtractAssets at first run
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
# The release keystore is COMMITTED (keystore/halfsource.keystore) so local
# builds and CI sign identically — users can update over an existing install.
# (Same practice as the upstream srceng-android repo, which ships its
# debug.keystore.) Without this, every CI run would generate a fresh key and
# force an uninstall/reinstall on each release.
KEY="$ROOT/keystore/halfsource.keystore"
if [ ! -f "$KEY" ]; then
  mkdir -p "$ROOT/keystore"
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
