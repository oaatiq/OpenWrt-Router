#!/bin/sh
# Run the ucode unit tests. Usage: tests/unit/run.sh [ucode-binary]
UCODE="${1:-ucode}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$HERE/../../files/usr/share/ucode"
rc=0
WRTPILOT_RUN_DIR="$(mktemp -d)"
export WRTPILOT_RUN_DIR
for t in "$HERE"/test_*.uc; do
	TZ='CET-1CEST,M3.5.0,M10.5.0/3' "$UCODE" -S -L "$LIB/*.uc" -L "$HERE/*.uc" "$t" || rc=1
done
rm -rf "$WRTPILOT_RUN_DIR"
exit $rc
