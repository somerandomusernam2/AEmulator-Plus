#!/bin/sh
# hwcomposer.default.so (HWC2 framebuffer composer, native/hwcomposer/hwcomposer.c) for an x86-64 guest:
#   sh build-hwcomposer.sh [out]    (default: engines/n64-x86_64/vendor#lib64#hw#hwcomposer.default.so)
set -eu
H=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$H/../../app/src/main/assets/engines/n64-x86_64/vendor#lib64#hw#hwcomposer.default.so}
sh "$H/zig-bionic.sh" "$OUT" hwcomposer.default.so "$H/../hwcomposer/hwcomposer.c"
