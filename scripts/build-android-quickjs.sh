#!/usr/bin/env bash

set -Eeuo pipefail
trap 'status=$?; echo "QuickJS build failed at line ${LINENO} (exit ${status})" >&2; exit ${status}' ERR

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <android-ndk-dir> <output-file> <work-dir>" >&2
    exit 2
fi

NDK_DIR=$1
OUTPUT_FILE=$2
WORK_DIR=$3

QUICKJS_VERSION=0.15.1
QUICKJS_ARCHIVE_SHA256=c4e813951b7c46845096a948e978c620b11ab4cf5fd622ca09c727ec31f42623
QUICKJS_URL="https://github.com/quickjs-ng/quickjs/archive/refs/tags/v${QUICKJS_VERSION}.tar.gz"
ARCHIVE_FILE="${WORK_DIR}/quickjs-ng-v${QUICKJS_VERSION}.tar.gz"
SOURCE_DIR="${WORK_DIR}/quickjs-${QUICKJS_VERSION}"
BUILD_DIR="${WORK_DIR}/android-arm64-v8a"

if [[ ! -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ]]; then
    echo "Android NDK not found at ${NDK_DIR}" >&2
    exit 1
fi

SDK_DIR=$(cd "${NDK_DIR}/../.." && pwd)
CMAKE_BIN=$(find "${SDK_DIR}/cmake" -path '*/bin/cmake' -type f 2>/dev/null | sort -V | tail -n 1 || true)
if [[ -z "${CMAKE_BIN}" || ! -x "${CMAKE_BIN}" ]]; then
    CMAKE_BIN=$(command -v cmake || true)
fi
if [[ -z "${CMAKE_BIN}" || ! -x "${CMAKE_BIN}" ]]; then
    echo "CMake was not found in the Android SDK or PATH" >&2
    exit 1
fi

PREBUILT_DIR=$(find "${NDK_DIR}/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)
STRIP_BIN="${PREBUILT_DIR}/bin/llvm-strip"
if [[ ! -x "${STRIP_BIN}" ]]; then
    echo "llvm-strip was not found in ${NDK_DIR}" >&2
    exit 1
fi

calculate_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

mkdir -p "${WORK_DIR}"
if [[ ! -f "${ARCHIVE_FILE}" ]] || [[ $(calculate_sha256 "${ARCHIVE_FILE}") != "${QUICKJS_ARCHIVE_SHA256}" ]]; then
    curl --fail --location --retry 3 --output "${ARCHIVE_FILE}" "${QUICKJS_URL}"
fi

ACTUAL_SHA256=$(calculate_sha256 "${ARCHIVE_FILE}")
if [[ "${ACTUAL_SHA256}" != "${QUICKJS_ARCHIVE_SHA256}" ]]; then
    echo "QuickJS archive checksum mismatch: ${ACTUAL_SHA256}" >&2
    exit 1
fi

if [[ ! -f "${SOURCE_DIR}/CMakeLists.txt" ]]; then
    mkdir -p "${SOURCE_DIR}"
    tar -xzf "${ARCHIVE_FILE}" --strip-components=1 -C "${SOURCE_DIR}"
fi

"${CMAKE_BIN}" \
    -S "${SOURCE_DIR}" \
    -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    -DQJS_ENABLE_INSTALL=OFF \
    -DQJS_BUILD_EXAMPLES=OFF
"${CMAKE_BIN}" --build "${BUILD_DIR}" --target qjs_exe --parallel

mkdir -p "$(dirname "${OUTPUT_FILE}")"
cp "${BUILD_DIR}/qjs" "${OUTPUT_FILE}"
"${STRIP_BIN}" "${OUTPUT_FILE}"
chmod 755 "${OUTPUT_FILE}"
