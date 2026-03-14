#!/usr/bin/env bash
set -euo pipefail

usage() {
    local exit_code="${1:-1}"
    cat <<'EOF' >&2
Usage:
  connect_remote_adb.sh HOST[:PORT]

Example:
  connect_remote_adb.sh 10.0.0.5:5555
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

if [[ ! -x "$ADB_BIN" ]]; then
    echo "Missing host adb at $ADB_BIN" >&2
    echo "Run ./android-tools/scripts/build-host-adb.sh first." >&2
    exit 1
fi

TARGET="$1"
if [[ "$TARGET" != *:* ]]; then
    TARGET="$TARGET:5555"
fi

printf '==> adb connect %s\n' "$TARGET"
exec "$ADB_BIN" connect "$TARGET"
