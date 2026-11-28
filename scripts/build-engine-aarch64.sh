#!/bin/bash
# half-sourceG100 — self-built aarch64 engine (v0.7.0+)
#
# Builds OUR OWN engine from the open-source tree instead of bundling the
# closed pinned v1.17.0025 binary pack. Base: AruMoon/source-engine @
# 87150cc028a77d4a043835613d44bac68466ddba (the exact commit the shipped
# v1.17.0025 binaries report) + the patches in engine-patches/.
#
# Proven toolchain (matches the v1.17.0025 binary's own build metadata:
# "Clang 16.0.6", NDK r10e sysroot, lld, GLES3/togles):
#   - Android NDK r10e  (https://dl.google.com/android/repository/android-ndk-r10e-linux-x86_64.zip)
#   - Clang/lld 16.0.6  (any LLVM 16; we extract the Debian bookworm
#                        clang-16/lld-16/libllvm16 debs without root)
#   - ccache            (killed builds lose waf's task cache; ccache makes
#                        re-runs resume at full speed)
#
# Usage:
#   scripts/build-engine-aarch64.sh [workdir]     # default: ../engine-build
#
# Output: <workdir>/source-engine/build/**/*.so  — feed to the APK packager:
#   ANDROID_BUILD_BASE=... ENGINE_DIR=<workdir>/source-engine/build \
#     scripts/make-launcher-apk.sh --with-engine
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORKDIR="${1:-$(dirname "$ROOT")/engine-build}"
ENGINE_DIR="$WORKDIR/source-engine"
BUILD_TYPE="${BUILD_TYPE:-release}"   # release | debug
JOBS="${JOBS:-$(nproc)}"
ENGINE_REPO="https://github.com/AruMoon/source-engine.git"
ENGINE_COMMIT="87150cc028a77d4a043835613d44bac68466ddba"

mkdir -p "$WORKDIR"
cd "$WORKDIR"

# ---- toolchain (skipped when already present) --------------------------------
if [ ! -d "$WORKDIR/android-ndk-r10e" ]; then
  echo "== NDK r10e =="
  wget -q "https://dl.google.com/android/repository/android-ndk-r10e-linux-x86_64.zip" -O ndk.zip
  unzip -q ndk.zip && rm -f ndk.zip
fi
if [ ! -x "$WORKDIR/llvm16/bin/clang" ]; then
  echo "== clang 16.0.6 (Debian pool, no root needed) =="
  mkdir -p "$WORKDIR/llvm16" "$WORKDIR/debs" && cd "$WORKDIR/debs"
  BASE="http://deb.debian.org/debian/pool/main/l/llvm-toolchain-16"
  for f in clang-16_16.0.6-15~deb12u1_amd64.deb \
           libclang-cpp16_16.0.6-15~deb12u1_amd64.deb \
           lld-16_16.0.6-15~deb12u1_amd64.deb \
           liblld-16_16.0.6-15~deb12u1_amd64.deb \
           libllvm16_16.0.6-15~deb12u1_amd64.deb \
           llvm-16_16.0.6-15~deb12u1_amd64.deb \
           llvm-16-linker-tools_16.0.6-15~deb12u1_amd64.deb \
           libclang-common-16-dev_16.0.6-15~deb12u1_all.deb; do
    [ -f "$f" ] || curl -sO "$BASE/$f"
    dpkg -x "$f" "$WORKDIR/llvm16"
  done
  cd "$WORKDIR"
fi
if [ ! -x "$WORKDIR/ccache-root/usr/bin/ccache" ]; then
  echo "== ccache =="
  mkdir -p "$WORKDIR/ccache-root" && cd "$WORKDIR/debs" 2>/dev/null || mkdir -p "$WORKDIR/debs" && cd "$WORKDIR/debs"
  curl -sO "http://deb.debian.org/debian/pool/main/c/ccache/ccache_4.8+really4.7.5-1_amd64.deb"
  curl -sO "http://deb.debian.org/debian/pool/main/h/hiredis/libhiredis0.14_0.14.1-3_amd64.deb"
  dpkg -x ccache_4.8+really4.7.5-1_amd64.deb "$WORKDIR/ccache-root"
  dpkg -x libhiredis0.14_0.14.1-3_amd64.deb "$WORKDIR/ccache-root"
  cd "$WORKDIR"
fi

export ANDROID_NDK_HOME="$WORKDIR/android-ndk-r10e"
export NDK_HOME="$WORKDIR/android-ndk-r10e"
export PATH="$WORKDIR/ccache-root/usr/bin:$WORKDIR/llvm16/usr/lib/llvm-16/bin:$PATH"
export LD_LIBRARY_PATH="$WORKDIR/ccache-root/usr/lib/x86_64-linux-gnu:$WORKDIR/llvm16/usr/lib/x86_64-linux-gnu:$WORKDIR/llvm16/usr/lib/llvm-16/lib"

# ---- engine source (pinned commit + our patches) ------------------------------
if [ ! -d "$ENGINE_DIR" ]; then
  echo "== engine source @ $ENGINE_COMMIT =="
  git init -q "$ENGINE_DIR"
  cd "$ENGINE_DIR"
  git remote add origin "$ENGINE_REPO"
  git fetch -q --depth 1 origin "$ENGINE_COMMIT"
  git checkout -q FETCH_HEAD
  git submodule update --init --recursive --depth 1
  git checkout -q -b half-source-g100
  for p in "$ROOT"/engine-patches/*.patch; do
    echo "applying $(basename "$p")"
    git apply "$p" && git add -A && git commit -qm "apply $(basename "$p")"
  done
  cd "$WORKDIR"
fi

cd "$ENGINE_DIR"

echo "== configure: android aarch64 + togles (GLES3), $BUILD_TYPE =="
# NOTE: no -8 flag on this waf — 64-bit is the default; -4 would force 32-bit.
# shellcheck disable=SC2086
./waf configure -T "$BUILD_TYPE" --android=aarch64,host,21 --togles --disable-warns --use-ccache

echo "== build ($JOBS jobs) =="
# A killed build loses waf's task cache; just re-run this script — ccache
# replays finished translation units instantly and the frontier advances.
# shellcheck disable=SC2086
./waf build -j"$JOBS"

echo "== strip =="
find build -name '*.so' -exec llvm-strip --strip-unneeded {} \;

echo ""
echo "== artifacts =="
find build -name '*.so' | sort
echo ""
echo "Done. Packager:  ENGINE_DIR=$ENGINE_DIR/build $ROOT/scripts/make-launcher-apk.sh --with-engine"
