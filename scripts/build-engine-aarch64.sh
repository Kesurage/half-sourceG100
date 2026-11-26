#!/bin/bash
# half-sourceG100 — Phase 0 aarch64 engine build
# Builds the nillerusr-lineage engine for Android arm64 with the GLES (togles) renderer.
#
# waf android format (from scripts/waifulib/xcompile.py):
#   --android=<arch>,<toolchain>,<api>   e.g. aarch64,host,21
#   'host' toolchain = system clang with --target=aarch64-linux-android<api>
#   -8 / --64bits for 64-bit
#
# Output .so set lands under build/engine/lib/<arch>/ inside the engine tree
# (the launcher_main exe is intentionally skipped on android — we ship our own APK shell).
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
WORKDIR="${1:-$(dirname "$HERE")/build-env}"
ENGINE_DIR="$WORKDIR/source-engine"
BUILD_TYPE="${BUILD_TYPE:-release}"   # release | debug
JOBS="${JOBS:-$(nproc)}"

if [ ! -f "$ENGINE_DIR/waf" ]; then
  echo "ERROR: engine not found at $ENGINE_DIR — run setup-build-env.sh first" >&2
  exit 1
fi
if [ -z "$ANDROID_NDK_HOME" ]; then
  . "$WORKDIR/env.sh"
fi

cd "$ENGINE_DIR"

echo "== submodules =="
git submodule init && git submodule update

echo "== configure: android aarch64 + togles (GLES) =="
# shellcheck disable=SC2086
./waf configure \
  -T "$BUILD_TYPE" \
  --android=aarch64,host,21 \
  --togles \
  --disable-warns \
  -8

echo "== build ($JOBS jobs) =="
# shellcheck disable=SC2086
./waf build -j"$JOBS"

echo ""
echo "== artifacts =="
find build -name '*.so' -newer waf 2>/dev/null | sort || find . -path ./thirdparty -prune -o -name '*.so' -print | sort
echo ""
echo "Done. Copy the .so set + your HL2 content into the APK shell (see android-app/)."
