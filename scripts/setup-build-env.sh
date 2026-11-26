#!/bin/bash
# half-sourceG100 — Phase 0 build environment setup (Linux x86_64 host)
# Sets up the known-good toolchain for the nillerusr-lineage engine:
#   Android NDK r10e (sysroot + android_support + gnu-libstdc++ 4.9)
#   clang 11 from LLVM apt (used via the waf 'host' toolchain: --target=aarch64-linux-android21)
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
WORKDIR="${1:-$ROOT/build-env}"
ENGINE_DIR="$WORKDIR/source-engine"
NDK_DIR="$WORKDIR/android-ndk-r10e"

mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "== [1/4] Host packages =="
if command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y build-essential python3 unzip wget git \
    libtool libtool-bin automake autoconf cmake pkg-config
  # clang 11 from LLVM apt (known-good for this engine; do NOT modernize yet)
  if ! clang --version 2>/dev/null | grep -q 'version 11'; then
    wget -qO- https://apt.llvm.org/llvm-snapshot.gpg.key | sudo apt-key add -
    sudo add-apt-repository -y 'deb http://apt.llvm.org/focal/ llvm-toolchain-focal-11 main' \
      || sudo add-apt-repository -y 'deb http://apt.llvm.org/jammy/ llvm-toolchain-jammy-11 main'
    sudo apt-get update
    sudo apt-get install -y clang-11 llvm-11
  fi
else
  echo "WARNING: non-APT host — install python3, git, unzip, wget, clang-11 manually"
fi

echo "== [2/4] Android NDK r10e =="
if [ ! -d "$NDK_DIR" ]; then
  wget -c https://dl.google.com/android/repository/android-ndk-r10e-linux-x86_64.zip -O "$WORKDIR/android-ndk-r10e-linux-x86_64.zip"
  unzip -q "$WORKDIR/android-ndk-r10e-linux-x86_64.zip" -d "$WORKDIR"
fi

echo "== [3/4] Engine source (our fork) =="
if [ ! -d "$ENGINE_DIR" ]; then
  git clone --recursive https://github.com/Kesurage/source-engine.git "$ENGINE_DIR"
else
  # keep submodules in sync if the clone exists
  git -C "$ENGINE_DIR" submodule update --init --recursive
fi

echo "== [4/4] Environment file =="
cat > "$WORKDIR/env.sh" <<EOF
# Source this before building: . "$WORKDIR/env.sh"
export ANDROID_NDK_HOME="$NDK_DIR"
export NDK_HOME="$NDK_DIR"
# prefer clang-11 explicitly (apt installs it as clang-11)
export CC="$(command -v clang-11 || echo clang)"
export CXX="$(command -v clang++-11 || echo clang++)"
export PATH="\$PATH:\$ANDROID_NDK_HOME"
echo "Phase 0 env ready: NDK r10e + \$(\${CC} --version | head -1)"
EOF

. "$WORKDIR/env.sh"
echo ""
echo "Setup complete. Next: scripts/build-engine-aarch64.sh $WORKDIR"
