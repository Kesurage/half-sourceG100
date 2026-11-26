#!/usr/bin/env bash
# half-sourceG100 — build libtier2jni.so (aarch64) with the NDK.
# The tier2 C++ library + JNI bridge, statically linked libc++.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
BASE="${ANDROID_BUILD_BASE:-/home/z/android-build}"

NDK="$(ls -d "$BASE"/ndk* 2>/dev/null | head -1)"
[ -n "$NDK" ] || { echo "ERROR: no NDK under $BASE — run scripts/setup-android-build.sh" >&2; exit 1; }
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"

OUT="$ROOT/android-app/libs/arm64-v8a"
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

echo "built: $OUT/libtier2jni.so"
