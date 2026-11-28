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
  # trim to aarch64-only (saves ~2.5 GB; the build needs just the aarch64
  # gcc-4.9 toolchain, the android-21 platform, android/support and gnustl)
  ( cd "$WORKDIR/android-ndk-r10e" && rm -rf toolchains/llvm-3.6 toolchains/llvm-3.5 \
      toolchains/arm-linux-androideabi-4.9 toolchains/arm-linux-androideabi-4.8 \
      toolchains/x86_64-4.9 toolchains/x86-4.9 toolchains/x86-4.8 \
      toolchains/mips64el-linux-android-4.9 toolchains/mipsel-linux-android-4.9 \
      toolchains/mips64el-linux-android-4.8 toolchains/mipsel-linux-android-4.8 \
      docs samples tests platforms/android-3 platforms/android-4 platforms/android-5 \
      platforms/android-8 platforms/android-9 sources/cxx-stl/gabi++ \
      sources/cxx-stl/llvm-libc++ sources/cxx-stl/llvm-libc++abi \
      sources/cxx-stl/stlport sources/cxx-stl/system 2>/dev/null ) || true
fi
if [ ! -x "$WORKDIR/llvm16/bin/clang" ] && [ ! -x "$WORKDIR/llvm16/usr/lib/llvm-16/bin/clang" ]; then
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
           libclang-common-16-dev_16.0.6-15~deb12u1_all.deb \
           libz3-4_4.8.12-3.1_amd64.deb \
           libedit2_3.1-20221030-2_amd64.deb \
           libxml2_2.9.14+dfsg-1.3~deb12u6_amd64.deb \
           libicu72_72.1-3+deb12u1_amd64.deb; do
    [ -f "$f" ] || curl -sfO "$BASE/$f" \
      || curl -sfO "http://deb.debian.org/debian/pool/main/z/z3/$f" \
      || curl -sfO "http://deb.debian.org/debian/pool/main/libe/libedit/$f" \
      || curl -sfO "http://deb.debian.org/debian/pool/main/libx/libxml2/$f" \
      || curl -sfO "http://deb.debian.org/debian/pool/main/i/icu/$f"
    dpkg -x "$f" "$WORKDIR/llvm16"
  done
  # Ubuntu 24.04 runners: z3 ships soname libz3.so.4.11 (not .4), and
  # libedit.so.2 / libxml2.so.2 / libicuuc.so.72 may be absent entirely (and
  # libxml2 pulls ICU). The debs above land in the lib dir the
  # LD_LIBRARY_PATH below already covers, so the toolchain is self-contained
  # on any modern x86_64 host (verified: zero 'not found' in ldd).
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

# Fail LOUD and EARLY if the toolchain cannot run on this host (waf would
# otherwise only say "could not configure a C compiler").
clang --version >/dev/null 2>&1 || { echo "ERROR: clang from the extracted toolchain does not run on this host" >&2; ldd "$WORKDIR/llvm16/usr/lib/llvm-16/bin/clang-16" 2>/dev/null | grep "not found"; exit 1; }
ld.lld --version >/dev/null 2>&1 || { echo "ERROR: lld does not run on this host" >&2; exit 1; }

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
    git apply "$p" && git add -A && \
      git -c user.name=half-sourceG100 -c user.email=build@halfsource.local \
        commit -qm "apply $(basename "$p")"
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
