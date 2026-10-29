#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$ROOT_DIR/core/sing-box"
APP_LIBS="$ROOT_DIR/app/libs"
GO_BIN="$(go env GOPATH)/bin"

if [[ "$(go version)" != *"go1.25.5"* ]]; then
  echo "sing-box 1.14.4 requires Go 1.25.5; install the pinned Go toolchain first." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME must point to an installed Android SDK." >&2
  exit 1
fi
if [[ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]]; then
  echo "Accept the Android SDK licenses before building libbox." >&2
  exit 1
fi

export PATH="$GO_BIN:$PATH"
export ANDROID_SDK_HOME="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
export NDK="$ANDROID_NDK_HOME"
if [[ ! -f "$ANDROID_NDK_HOME/source.properties" ]]; then
  echo "Android NDK 30.0.16248370 is missing. Install it with sdkmanager first." >&2
  exit 1
fi
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

cd "$CORE_DIR"

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
gomobile init

go run ./cmd/internal/build_libbox -target=android
mkdir -p "$APP_LIBS"
test -s "$CORE_DIR/libbox.aar"
install -m 0644 "$CORE_DIR/libbox.aar" "$APP_LIBS/libbox.aar"
echo "Built Android sing-box core: $APP_LIBS/libbox.aar"
