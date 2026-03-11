#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ANDROID_TOOLS_DIR="${ANDROID_TOOLS_DIR_OVERRIDE:-${ROOT_DIR}/android-tools}"
PATCH_ROOT="${ROOT_DIR}/patches/android-tools"

apply_patch_to_repo() {
    local repo_dir=$1
    local patch_path=$2
    local strip_components=$3

    if git -C "${repo_dir}" apply --check "-p${strip_components}" "${patch_path}" >/dev/null 2>&1; then
        git -C "${repo_dir}" apply "-p${strip_components}" "${patch_path}"
        printf 'Applied %s\n' "${patch_path}"
        return
    fi

    if git -C "${repo_dir}" apply -R --check "-p${strip_components}" "${patch_path}" >/dev/null 2>&1; then
        printf 'Skipping %s: already applied\n' "${patch_path}"
        return
    fi

    printf 'Failed to apply %s in %s\n' "${patch_path}" "${repo_dir}" >&2
    exit 1
}

require_repo_paths() {
    local repo_dir=$1
    shift

    local path
    for path in "$@"; do
        if [[ ! -e "${repo_dir}/${path}" ]]; then
            printf 'Expected patched path is missing in %s: %s\n' "${repo_dir}" "${path}" >&2
            exit 1
        fi
    done
}

reset_repo_to_head() {
    local repo_dir=$1

    git -C "${repo_dir}" reset --hard HEAD >/dev/null
    git -C "${repo_dir}" clean -fd >/dev/null
}

git -C "${ANDROID_TOOLS_DIR}" submodule update --init vendor/adb vendor/core vendor/libbase vendor/libziparchive vendor/logging

reset_repo_to_head "${ANDROID_TOOLS_DIR}/vendor/adb"
reset_repo_to_head "${ANDROID_TOOLS_DIR}/vendor/core"
reset_repo_to_head "${ANDROID_TOOLS_DIR}/vendor/libbase"
reset_repo_to_head "${ANDROID_TOOLS_DIR}/vendor/libziparchive"
reset_repo_to_head "${ANDROID_TOOLS_DIR}/vendor/logging"

apply_patch_to_repo "${ANDROID_TOOLS_DIR}" \
    "${PATCH_ROOT}/0001-termux-adb-build.patch" \
    1

apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/adb" \
    "${PATCH_ROOT}/vendor-adb/0001-termux-adb-tcp-only.patch" \
    1
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/adb" \
    "${ANDROID_TOOLS_DIR}/patches/adb/1000-termux-adb.patch" \
    3
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/adb" \
    "${PATCH_ROOT}/termux/vendor_adb_sysdeps.h.patch" \
    3
require_repo_paths "${ANDROID_TOOLS_DIR}/vendor/adb" \
    "client/adb_wifi_termux_stub.cpp" \
    "client/usb_termux_stub.cpp" \
    "client/fastdeploy_termux_stub.cpp" \
    "client/mdns_disabled.cpp" \
    "client/termux_adb.h"
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/core" \
    "${ANDROID_TOOLS_DIR}/patches/core/1000-termux-fastboot.patch" \
    3

apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/libbase" \
    "${PATCH_ROOT}/vendor-libbase/0001-guard-fdsan-for-api-29.patch" \
    1
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/libziparchive" \
    "${PATCH_ROOT}/vendor-libziparchive/0001-guard-fdsan-for-api-29.patch" \
    1
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/core" \
    "${PATCH_ROOT}/vendor-core/0001-guard-fdsan-for-api-29.patch" \
    1
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/logging" \
    "${PATCH_ROOT}/vendor-logging/0001-lower-liblog-api-gates.patch" \
    1
apply_patch_to_repo "${ANDROID_TOOLS_DIR}/vendor/logging" \
    "${PATCH_ROOT}/vendor-logging/0002-disable-android-logd-backend.patch" \
    1
