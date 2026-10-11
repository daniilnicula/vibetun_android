#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$ROOT_DIR/core/amneziawg-android"
JNI_LIBS="$ROOT_DIR/app/src/main/jniLibs"
NDK_VERSION="26.2.11394342"
ANDROID_API="26"

if [[ "$(go env GOVERSION)" != go1.25.* ]]; then
  echo "AmneziaWG's pinned Go module requires Go 1.25.x." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME must point to an installed Android SDK." >&2
  exit 1
fi

NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -f "$NDK/source.properties" ]]; then
  echo "Android NDK $NDK_VERSION is missing. Install it with sdkmanager first." >&2
  exit 1
fi

cd "$CORE_DIR"
go mod download

build_one() {
  local abi="$1"
  local goarch="$2"
  local goarm="$3"
  local clang="$4"
  local out_dir="$JNI_LIBS/$abi"
  mkdir -p "$out_dir"

  echo "Building AmneziaWG userspace runtime for $abi"
  env \
    GOOS=android \
    GOARCH="$goarch" \
    GOARM="$goarm" \
    CGO_ENABLED=1 \
    CC="$TOOLCHAIN/$clang" \
    CGO_CFLAGS="-O2" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
    go build \
      -tags=linux \
      -trimpath \
      -buildvcs=false \
      -ldflags="-s -w -buildid=" \
      -buildmode=c-shared \
      -o "$out_dir/libwg-go.so" \
      .
  rm -f "$out_dir/libwg-go.h"
}

build_one arm64-v8a arm64 "" "aarch64-linux-android${ANDROID_API}-clang"
build_one armeabi-v7a arm 7 "armv7a-linux-androideabi${ANDROID_API}-clang"
build_one x86 386 "" "i686-linux-android${ANDROID_API}-clang"
build_one x86_64 amd64 "" "x86_64-linux-android${ANDROID_API}-clang"

echo "Built AmneziaWG Android runtime in $JNI_LIBS"
