#!/usr/bin/env bash
set -euo pipefail

LIBFFI_VERSION="${LIBFFI_VERSION:-3.3}"
ANDROID_API="${ANDROID_API:-24}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_SO="$ROOT_DIR/ytdlib/src/main/jniLibs/arm64-v8a/libffi.so"

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_DIR="$ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
  NDK_DIR="$ANDROID_NDK_ROOT"
elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
  NDK_DIR="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/ndk" ]]; then
  NDK_DIR="$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"
else
  echo "Unable to find Android NDK. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux) HOST_TAG="linux-x86_64" ;;
  *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -x "$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang" ]]; then
  echo "Unable to find NDK clang in $TOOLCHAIN" >&2
  exit 1
fi

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

curl -L --fail \
  -o "$BUILD_DIR/libffi-$LIBFFI_VERSION.tar.gz" \
  "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz"
tar -xzf "$BUILD_DIR/libffi-$LIBFFI_VERSION.tar.gz" -C "$BUILD_DIR"

(
  cd "$BUILD_DIR/libffi-$LIBFFI_VERSION"
  PATH="$TOOLCHAIN/bin:$PATH" \
  CC="aarch64-linux-android${ANDROID_API}-clang" \
  AR="llvm-ar" \
  RANLIB="llvm-ranlib" \
  STRIP="llvm-strip" \
  ./configure \
    --host=aarch64-linux-android \
    --disable-static \
    --enable-shared \
    --prefix="$BUILD_DIR/out" \
    CFLAGS="-fPIC -O2" \
    LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-soname,libffi.so"

  cd aarch64-unknown-linux-android
  make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)"
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded .libs/libffi.so
  cp .libs/libffi.so "$OUTPUT_SO"
)

if "$TOOLCHAIN/bin/llvm-readelf" -l "$OUTPUT_SO" | awk '/LOAD/ && $NF != "0x4000" { bad = 1 } END { exit bad }'; then
  echo "Built 16 KB-aligned libffi.so at $OUTPUT_SO"
else
  echo "Built libffi.so does not have 16 KB LOAD alignment." >&2
  exit 1
fi
