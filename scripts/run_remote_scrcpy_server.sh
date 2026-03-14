#!/usr/bin/env bash
set -euo pipefail

usage() {
    local exit_code="${1:-1}"
    cat <<'EOF' >&2
Usage:
  run_remote_scrcpy_server.sh HOST[:PORT] [--local-port PORT] [server key=value ...]

Examples:
  run_remote_scrcpy_server.sh 192.168.0.10:5555
  run_remote_scrcpy_server.sh 192.168.0.10 --local-port 27183 max_size=1920 video_bit_rate=8000000
EOF
    exit "$exit_code"
}

if [[ $# -lt 1 ]]; then
    usage
fi

case "$1" in
    --help|-h)
        usage 0
        ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_BIN="$ROOT_DIR/android-tools/out/host/adb/adb"
SCRCPY_SERVER_BIN="$ROOT_DIR/build/outputs/host-tools/scrcpy/scrcpy-server"
SCRCPY_SERVER_DEST="/data/local/tmp/scrcpy-server.jar"
SCRCPY_LOCAL_PORT=27183

if [[ ! -x "$ADB_BIN" ]]; then
    echo "Missing host adb at $ADB_BIN" >&2
    echo "Run ./android-tools/scripts/build-host-adb.sh first." >&2
    exit 1
fi

if [[ ! -f "$SCRCPY_SERVER_BIN" ]]; then
    echo "Missing scrcpy-server at $SCRCPY_SERVER_BIN" >&2
    echo "Run ./gradlew stageScrcpyServerBinary first." >&2
    exit 1
fi

TARGET="$1"
shift

if [[ "$TARGET" != *:* ]]; then
    TARGET="$TARGET:5555"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --local-port)
            [[ $# -ge 2 ]] || usage
            SCRCPY_LOCAL_PORT="$2"
            shift 2
            ;;
        --help|-h)
            usage 0
            ;;
        *)
            break
            ;;
    esac
done

SCRCPY_VERSION="$(
    sed -n 's/.*versionName \"\([^\"]\+\)\".*/\1/p' "$ROOT_DIR/scrcpy/server/build.gradle" | head -n 1
)"
if [[ -z "$SCRCPY_VERSION" ]]; then
    echo "Unable to determine scrcpy server version." >&2
    exit 1
fi

SCID="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"

server_args=(
    "$SCRCPY_VERSION"
    "scid=$SCID"
    "log_level=info"
    "tunnel_forward=true"
    "audio=false"
    "cleanup=true"
)

if [[ $# -gt 0 ]]; then
    server_args+=("$@")
fi

printf '==> adb connect %s\n' "$TARGET"
"$ADB_BIN" connect "$TARGET"

printf '==> adb wait-for-device %s\n' "$TARGET"
"$ADB_BIN" -s "$TARGET" wait-for-device

printf '==> adb push %s -> %s\n' "$SCRCPY_SERVER_BIN" "$SCRCPY_SERVER_DEST"
"$ADB_BIN" -s "$TARGET" push "$SCRCPY_SERVER_BIN" "$SCRCPY_SERVER_DEST"

printf '==> adb forward tcp:%s localabstract:scrcpy\n' "$SCRCPY_LOCAL_PORT"
"$ADB_BIN" -s "$TARGET" forward --remove "tcp:$SCRCPY_LOCAL_PORT" >/dev/null 2>&1 || true
"$ADB_BIN" -s "$TARGET" forward "tcp:$SCRCPY_LOCAL_PORT" localabstract:scrcpy

printf -v escaped_server_args ' %q' "${server_args[@]}"
remote_cmd="CLASSPATH=$SCRCPY_SERVER_DEST app_process / com.genymobile.scrcpy.Server${escaped_server_args}"

printf '==> adb shell %s\n' "$remote_cmd"
exec "$ADB_BIN" -s "$TARGET" shell "$remote_cmd"
