#!/usr/bin/env bash
# half-sourceG100 — build libtier2jni.so with the NDK.
# The tier2 C++ library + JNI bridge, statically linked libc++.
#
# Usage: scripts/build-jni.sh [aarch64|armv7a|all]
#   aarch64 -> android-app/libs/arm64-v8a   (launcher-only APK flavor)
#   armv7a  -> android-app/libs/armeabi-v7a (engine APK flavor — the bundled
#               engine is 32-bit, so the classifier must be 32-bit there too)
#   all     -> both
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
BASE="${ANDROID_BUILD_BASE:-/home/z/android-build}"

WHAT="${1:-all}"

# NDK layout differs by installer: sdkmanager puts it at $BASE/ndk/<version>/,
# a manual unpack at $BASE/ndk/. Find the clang++ wrappers either way.
find_toolchain() {
  find "$BASE"/ndk* -path '*prebuilt/linux-x86_64/bin' -type d 2>/dev/null | sort | tail -1
}
TC="$(find_toolchain)"
[ -n "$TC" ] && [ -d "$TC" ] || { echo "ERROR: NDK toolchain not found under $BASE — run scripts/setup-android-build.sh" >&2; exit 1; }

build_one() {
  local ABI="$1" CXX="$2" OUTDIR="$3"
  [ -x "$CXX" ] || { echo "ERROR: $CXX not found (wrong NDK layout?)" >&2; exit 1; }
  local OUT="$ROOT/android-app/libs/$OUTDIR"
  mkdir -p "$OUT"
  "$CXX" -std=c++17 -O2 -fPIC -fvisibility=hidden -shared \
    -Wall -Wextra \
    -I"$ROOT/tier2/include" \
    "$ROOT/android-app/jni/tier2jni.cpp" \
    "$ROOT/tier2/src/keyvalues.cpp" \
    "$ROOT/tier2/src/modclassifier.cpp" \
    "$ROOT/tier2/src/vpk.cpp" \
    "$ROOT/tier2/src/mountplanner.cpp" \
    -static-libstdc++ \
    -o "$OUT/libtier2jni.so"
  echo "built: $OUT/libtier2jni.so ($ABI)"
}

case "$WHAT" in
  aarch64)
    build_one aarch64 "$TC/aarch64-linux-android26-clang++" arm64-v8a ;;
  armv7a|armeabi-v7a)
    build_one armv7a "$TC/armv7a-linux-androideabi24-clang++" armeabi-v7a ;;
  all)
    build_one aarch64 "$TC/aarch64-linux-android26-clang++" arm64-v8a
    build_one armv7a "$TC/armv7a-linux-androideabi24-clang++" armeabi-v7a ;;
  *)
    echo "usage: $0 [aarch64|armv7a|all]" >&2; exit 1 ;;
esac
