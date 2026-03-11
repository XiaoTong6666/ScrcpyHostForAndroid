#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ANDROID_TOOLS_DIR="${ROOT_DIR}/android-tools"
TERMUX_LIB_DIR="${ANDROID_TOOLS_DIR}/libtermuxadb"
OUTPUT_DIR="${ROOT_DIR}/build/outputs/termux-adb"
if [[ -d "${ROOT_DIR}/android-deps" ]]; then
    DEPS_ROOT="${ROOT_DIR}/android-deps"
else
    DEPS_ROOT="${ROOT_DIR}/third_party/android-deps"
fi
API_LEVEL="${API_LEVEL:-28}"
ABI="${1:-arm64-v8a}"

case "${ABI}" in
    arm64-v8a)
        RUST_TARGET="aarch64-linux-android"
        CLANG_TRIPLE="aarch64-linux-android"
        ;;
    armeabi-v7a)
        RUST_TARGET="armv7-linux-androideabi"
        CLANG_TRIPLE="armv7a-linux-androideabi"
        ;;
    *)
        printf 'Unsupported ABI: %s\n' "${ABI}" >&2
        exit 1
        ;;
esac

ABSL_SRC="${DEPS_ROOT}/abseil-cpp"
PROTOBUF_SRC="${DEPS_ROOT}/protobuf"
BROTLI_SRC="${DEPS_ROOT}/brotli"
LZ4_SRC="${DEPS_ROOT}/lz4"
ZSTD_SRC="${DEPS_ROOT}/zstd"

ensure_dir() {
    local path=$1
    local hint=$2
    if [[ ! -d "${path}" ]]; then
        printf 'Missing required source directory: %s\n%s\n' "${path}" "${hint}" >&2
        exit 1
    fi
}

find_latest_ndk() {
    local sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
    local ndk_root="${sdk_root}/ndk"

    if [[ -n "${ANDROID_NDK_HOME:-}" && -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
        printf '%s\n' "${ANDROID_NDK_HOME}"
        return
    fi

    if [[ -n "${ANDROID_NDK_ROOT:-}" && -f "${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" ]]; then
        printf '%s\n' "${ANDROID_NDK_ROOT}"
        return
    fi

    if [[ ! -d "${ndk_root}" ]]; then
        printf 'Android NDK directory not found under %s\n' "${ndk_root}" >&2
        exit 1
    fi

    find "${ndk_root}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
        | grep -E '^[0-9]+(\.[0-9]+)*$' \
        | sort -V \
        | tail -n1 \
        | sed "s#^#${ndk_root}/#"
}

NDK_DIR=$(find_latest_ndk)
if [[ -z "${NDK_DIR}" ]]; then
    printf 'No versioned Android NDK installation was found.\n' >&2
    exit 1
fi
TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
LLVM_PREBUILT="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64"
LINKER="${LLVM_PREBUILT}/bin/${CLANG_TRIPLE}${API_LEVEL}-clang"
AR="${LLVM_PREBUILT}/bin/llvm-ar"
STRIP="${LLVM_PREBUILT}/bin/llvm-strip"
TERMUX_STATIC_LIB="${TERMUX_LIB_DIR}/target/${RUST_TARGET}/release/libtermuxadb.a"
BUILD_DIR="${ROOT_DIR}/build/android-tools-termux-${ABI}"
HOST_DEPS_PREFIX="${ROOT_DIR}/build/termux-deps/${ABI}/host/prefix"
HOST_DEPS_BUILD_DIR="${ROOT_DIR}/build/termux-deps/${ABI}/host/build"
TARGET_DEPS_PREFIX="${ROOT_DIR}/build/termux-deps/${ABI}/prefix"
TARGET_DEPS_BUILD_DIR="${ROOT_DIR}/build/termux-deps/${ABI}/build"
CMAKE_LAUNCHER_ARGS=()

cleanup_vendor_submodules() {
    local repo_dir

    for repo_dir in \
        "${ANDROID_TOOLS_DIR}/vendor/adb" \
        "${ANDROID_TOOLS_DIR}/vendor/core" \
        "${ANDROID_TOOLS_DIR}/vendor/libbase" \
        "${ANDROID_TOOLS_DIR}/vendor/libziparchive" \
        "${ANDROID_TOOLS_DIR}/vendor/logging"; do
        git -C "${repo_dir}" reset --hard HEAD >/dev/null 2>&1 || true
        git -C "${repo_dir}" clean -fd >/dev/null 2>&1 || true
    done
}

trap cleanup_vendor_submodules EXIT

if [[ "${USE_CCACHE:-1}" != "0" ]] && command -v ccache >/dev/null 2>&1; then
    export CCACHE_BASEDIR="${ROOT_DIR}"
    export CCACHE_DIR="${CCACHE_DIR:-${ROOT_DIR}/.ccache}"
    export CCACHE_COMPILERCHECK="${CCACHE_COMPILERCHECK:-content}"
    mkdir -p "${CCACHE_DIR}"
    CMAKE_LAUNCHER_ARGS=(
        -DCMAKE_C_COMPILER_LAUNCHER=ccache
        -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
    )
    printf 'ccache enabled: dir=%s\n' "${CCACHE_DIR}"
else
    printf 'ccache disabled (set USE_CCACHE=1 and install ccache to enable)\n'
fi

if [[ ! -f "${TOOLCHAIN_FILE}" ]]; then
    printf 'Missing Android toolchain file: %s\n' "${TOOLCHAIN_FILE}" >&2
    exit 1
fi

if [[ ! -x "${LINKER}" ]]; then
    printf 'Missing Android linker: %s\n' "${LINKER}" >&2
    exit 1
fi

if [[ ! -x "${STRIP}" ]]; then
    printf 'Missing llvm-strip: %s\n' "${STRIP}" >&2
    exit 1
fi

ensure_dir "${ABSL_SRC}" "Clone it with: git clone --depth=1 --branch 20250127.0 https://github.com/abseil/abseil-cpp.git ${ABSL_SRC}"
ensure_dir "${PROTOBUF_SRC}" "Clone it under ${DEPS_ROOT}"
ensure_dir "${BROTLI_SRC}" "Clone it under ${DEPS_ROOT}"
ensure_dir "${LZ4_SRC}" "Clone it under ${DEPS_ROOT}"
ensure_dir "${ZSTD_SRC}" "Clone it under ${DEPS_ROOT}"

common_target_cmake_args() {
    printf '%s\n' \
        "-DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE}" \
        "-DANDROID_ABI=${ABI}" \
        "-DANDROID_PLATFORM=android-${API_LEVEL}" \
        "-DCMAKE_BUILD_TYPE=Release" \
        "-DCMAKE_INSTALL_PREFIX=${TARGET_DEPS_PREFIX}"
}

run_cmake_install() {
    local source_dir=$1
    local build_dir=$2
    shift 2

    rm -rf "${build_dir}"
    cmake -S "${source_dir}" -B "${build_dir}" "$@" "${CMAKE_LAUNCHER_ARGS[@]}"
    cmake --build "${build_dir}" -j"$(nproc)"
    cmake --build "${build_dir}" --target install -j"$(nproc)"
}

build_absl_host() {
    run_cmake_install \
        "${ABSL_SRC}" \
        "${HOST_DEPS_BUILD_DIR}/absl" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="${HOST_DEPS_PREFIX}" \
        -DABSL_BUILD_TESTING=OFF \
        -DABSL_PROPAGATE_CXX_STD=ON \
        -DCMAKE_CXX_STANDARD=17
}

build_absl_target() {
    mapfile -t target_args < <(common_target_cmake_args)
    run_cmake_install \
        "${ABSL_SRC}" \
        "${TARGET_DEPS_BUILD_DIR}/absl" \
        "${target_args[@]}" \
        -DABSL_BUILD_TESTING=OFF \
        -DABSL_PROPAGATE_CXX_STD=ON \
        -DCMAKE_CXX_STANDARD=17
}

build_brotli_target() {
    mapfile -t target_args < <(common_target_cmake_args)
    run_cmake_install \
        "${BROTLI_SRC}" \
        "${TARGET_DEPS_BUILD_DIR}/brotli" \
        "${target_args[@]}" \
        -DBUILD_SHARED_LIBS=OFF \
        -DBROTLI_BUILD_TOOLS=OFF \
        -DBROTLI_DISABLE_TESTS=ON
}

build_lz4_target() {
    mapfile -t target_args < <(common_target_cmake_args)
    local build_dir="${TARGET_DEPS_BUILD_DIR}/lz4"

    rm -rf "${build_dir}"
    cmake \
        -S "${LZ4_SRC}/build/cmake" \
        -B "${build_dir}" \
        "${target_args[@]}" \
        "${CMAKE_LAUNCHER_ARGS[@]}" \
        -DLZ4_BUILD_CLI=OFF \
        -DLZ4_BUNDLED_MODE=ON \
        -DBUILD_SHARED_LIBS=OFF \
        -DBUILD_STATIC_LIBS=ON
    cmake --build "${build_dir}" -j"$(nproc)"

    install -Dm644 "${build_dir}/liblz4.a" "${TARGET_DEPS_PREFIX}/lib/liblz4.a"
    install -Dm644 "${LZ4_SRC}/lib/lz4.h" "${TARGET_DEPS_PREFIX}/include/lz4.h"
    install -Dm644 "${LZ4_SRC}/lib/lz4frame.h" "${TARGET_DEPS_PREFIX}/include/lz4frame.h"
    install -Dm644 "${LZ4_SRC}/lib/lz4file.h" "${TARGET_DEPS_PREFIX}/include/lz4file.h"
    install -Dm644 "${LZ4_SRC}/lib/lz4hc.h" "${TARGET_DEPS_PREFIX}/include/lz4hc.h"
    mkdir -p "${TARGET_DEPS_PREFIX}/lib/pkgconfig"
    cat > "${TARGET_DEPS_PREFIX}/lib/pkgconfig/liblz4.pc" <<EOF
prefix=${TARGET_DEPS_PREFIX}
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: liblz4
Description: LZ4 compression library
Version: 1.10.0
Libs: -L\${libdir} -llz4
Cflags: -I\${includedir}
EOF
}

build_zstd_target() {
    mapfile -t target_args < <(common_target_cmake_args)
    run_cmake_install \
        "${ZSTD_SRC}/build/cmake" \
        "${TARGET_DEPS_BUILD_DIR}/zstd" \
        "${target_args[@]}" \
        -DZSTD_BUILD_PROGRAMS=OFF \
        -DZSTD_BUILD_TESTS=OFF \
        -DZSTD_BUILD_CONTRIB=OFF \
        -DZSTD_BUILD_SHARED=OFF \
        -DZSTD_BUILD_STATIC=ON
}

build_protobuf_host() {
    run_cmake_install \
        "${PROTOBUF_SRC}" \
        "${HOST_DEPS_BUILD_DIR}/protobuf" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="${HOST_DEPS_PREFIX}" \
        -DCMAKE_PREFIX_PATH="${HOST_DEPS_PREFIX}" \
        -Dabsl_DIR="${HOST_DEPS_PREFIX}/lib/cmake/absl" \
        -Dprotobuf_BUILD_TESTS=OFF \
        -Dprotobuf_BUILD_CONFORMANCE=OFF \
        -Dprotobuf_BUILD_EXAMPLES=OFF \
        -Dprotobuf_BUILD_SHARED_LIBS=OFF \
        -Dprotobuf_LOCAL_DEPENDENCIES_ONLY=ON
}

build_protobuf_target() {
    mapfile -t target_args < <(common_target_cmake_args)
    run_cmake_install \
        "${PROTOBUF_SRC}" \
        "${TARGET_DEPS_BUILD_DIR}/protobuf" \
        "${target_args[@]}" \
        -DCMAKE_PREFIX_PATH="${TARGET_DEPS_PREFIX}" \
        -Dabsl_DIR="${TARGET_DEPS_PREFIX}/lib/cmake/absl" \
        -Dprotobuf_BUILD_TESTS=OFF \
        -Dprotobuf_BUILD_CONFORMANCE=OFF \
        -Dprotobuf_BUILD_EXAMPLES=OFF \
        -Dprotobuf_BUILD_SHARED_LIBS=OFF \
        -Dprotobuf_LOCAL_DEPENDENCIES_ONLY=ON \
        -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
        -DWITH_PROTOC="${HOST_DEPS_PREFIX}/bin/protoc"
}

"${ROOT_DIR}/scripts/apply_android_tools_termux_patches.sh"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${LLVM_PREBUILT}/bin/aarch64-linux-android${API_LEVEL}-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="${LLVM_PREBUILT}/bin/armv7a-linux-androideabi${API_LEVEL}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="${AR}"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR="${AR}"

build_absl_host
build_absl_target
build_brotli_target
build_lz4_target
build_zstd_target
build_protobuf_host
build_protobuf_target

cargo build --manifest-path "${TERMUX_LIB_DIR}/Cargo.toml" --release --target "${RUST_TARGET}"

rm -rf "${BUILD_DIR}"

export PKG_CONFIG_DIR=
export PKG_CONFIG_LIBDIR="${TARGET_DEPS_PREFIX}/lib/pkgconfig:${TARGET_DEPS_PREFIX}/share/pkgconfig"
export PKG_CONFIG_PATH="${PKG_CONFIG_LIBDIR}"

cmake \
    -S "${ANDROID_TOOLS_DIR}" \
    -B "${BUILD_DIR}" \
    "${CMAKE_LAUNCHER_ARGS[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DANDROID_TOOLS_PATCH_VENDOR=OFF \
    -DANDROID_TOOLS_USE_BUNDLED_FMT=ON \
    -DANDROID_TOOLS_USE_BUNDLED_LIBUSB=ON \
    -DANDROID_TOOLS_TERMUX_ADB=ON \
    -DANDROID_TOOLS_BUILD_ONLY_ADB=ON \
    -DANDROID_TOOLS_TERMUX_LINK_DIR="${TARGET_DEPS_PREFIX}/lib" \
    -DCMAKE_PREFIX_PATH="${TARGET_DEPS_PREFIX};${HOST_DEPS_PREFIX}" \
    -Dabsl_DIR="${TARGET_DEPS_PREFIX}/lib/cmake/absl" \
    -Dutf8_range_DIR="${TARGET_DEPS_PREFIX}/lib/cmake/utf8_range" \
    -DProtobuf_DIR="${TARGET_DEPS_PREFIX}/lib/cmake/protobuf" \
    -DProtobuf_PROTOC_EXECUTABLE="${HOST_DEPS_PREFIX}/bin/protoc" \
    -DTERMUXADB_STATIC_LIB="${TERMUX_STATIC_LIB}"

cmake --build "${BUILD_DIR}" --target adb -j"$(nproc)"

install -Dm755 "${BUILD_DIR}/vendor/adb" "${OUTPUT_DIR}/${ABI}/adb"

if [[ "${STRIP_EMBEDDED_ADB:-1}" != "0" ]]; then
    "${STRIP}" "${OUTPUT_DIR}/${ABI}/adb"
    printf 'Stripped adb output: %s\n' "${OUTPUT_DIR}/${ABI}/adb"
else
    printf 'Skipping strip for adb output: %s\n' "${OUTPUT_DIR}/${ABI}/adb"
fi
