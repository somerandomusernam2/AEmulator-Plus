#!/bin/sh
# Builds libaemu_hidlcompat.so (aarch64, no dependencies) with zig cc. Output: the engine's audio directory for 15-17.
set -eu
ZIG=${ZIG:-python3 -m ziglang}
OUT=${1:-../../app/src/main/assets/engines/n64-35-own/vendor#lib64#aemu-audio#libaemu_hidlcompat.so}
$ZIG cc -target aarch64-linux-musl -shared -nostdlib -fno-stack-protector -fno-builtin -O2 -Wl,-soname,libaemu_hidlcompat.so \
    -Wl,--hash-style=gnu -Wl,-z,max-page-size=4096 -Wl,--strip-all -o "$OUT" hidlcompat.c
