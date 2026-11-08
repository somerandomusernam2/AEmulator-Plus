#!/bin/sh
# needs zig (or gcc) on an x86-64 Linux host; builds hwcomposer.c for glibc and runs the check above
set -eu
H=$(cd "$(dirname "$0")/.." && pwd)
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
$ZIG cc -target x86_64-linux-gnu -Os -fPIC -shared -I"$H/inc" -o "$T/hwc.so" "$H/../hwcomposer/hwcomposer.c" -lpthread
$ZIG cc -target x86_64-linux-gnu -I"$H/inc" -o "$T/smoke" "$H/tests/hwc-host-smoke.c" -ldl
cd "$T" && ./smoke "$T/hwc.so"
