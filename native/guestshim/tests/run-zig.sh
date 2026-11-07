#!/bin/sh
# Same smoke tests as run.sh, but built with zig cc instead of the NDK (needs: pip install ziglang, qemu-arm).
set -eu
ZIG=${ZIG:-python3 -m ziglang}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
SRC=$(cd "$(dirname "$0")" && pwd)
for t in fopen crash-maps trackball netmgr vibration property; do
    $ZIG cc --target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -Os -nostdlib -static \
        -ffreestanding -fno-builtin -fno-stack-protector -Wl,-e,_start -o "$T/$t-smoke" "$SRC/$t-smoke.c"
    (cd "$T" && qemu-arm "./$t-smoke")
done
