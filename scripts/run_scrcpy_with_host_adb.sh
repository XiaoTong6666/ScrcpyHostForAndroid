#!/usr/bin/env bash
set -euo pipefail

usage() {
    local exit_code="${1:-1}"
    cat <<'EOF' >&2
Usage:
  run_scrcpy_with_host_adb.sh HOST[:PORT] [--scrcpy /path/to/scrcpy] [scrcpy args...]

Examples:
  run_scrcpy_with_host_adb.sh 10.0.0.5:5555
  run_scrcpy_with_host_adb.sh 10.0.0.5 --scrcpy /opt/scrcpy/bin/scrcpy --no-audio
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
SCRCPY_BIN="${SCRCPY_BIN:-scrcpy}"

if [[ ! -x "$ADB_BIN" ]]; then
    echo "Missing host adb at $ADB_BIN" >&2
    echo "Run ./android-tools/scripts/build-host-adb.sh first." >&2
    exit 1
fi

TARGET="$1"
shift

if [[ "$TARGET" != *:* ]]; then
    TARGET="$TARGET:5555"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scrcpy)
            [[ $# -ge 2 ]] || usage
            SCRCPY_BIN="$2"
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

if ! command -v "$SCRCPY_BIN" >/dev/null 2>&1; then
    echo "Unable to find scrcpy executable: $SCRCPY_BIN" >&2
    exit 1
fi

"$ROOT_DIR/scripts/connect_remote_adb.sh" "$TARGET"

printf '==> ADB=%s %s -s %s' "$ADB_BIN" "$SCRCPY_BIN" "$TARGET"
for arg in "$@"; do
    printf ' %q' "$arg"
done
printf '\n'

exec env ADB="$ADB_BIN" "$SCRCPY_BIN" -s "$TARGET" "$@"
