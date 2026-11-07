#!/bin/sh
# Builds v3dsoft.bin: position-independent Thumb code linked at CAVE (the padding after libsurfaceflinger.so's text segment).
# usage: build.sh <cave vaddr, hex> <unused> <out dir>   (the Create() stub is assembled by tools/patch_sf_v3d.py)
set -e
OUT_ARG="$3"
cd "$(dirname "$0")"
CAVE=${1:-0x29600}; ORIG=${2:-0x262e9}; OUT=${OUT_ARG:-out}
CC=${CROSS:-arm-linux-gnueabi}-gcc; LD=${CROSS:-arm-linux-gnueabi}-ld; OC=${CROSS:-arm-linux-gnueabi}-objcopy
mkdir -p "$OUT"
CF="-mthumb -march=armv5te -mfloat-abi=soft -Os -fPIC -fvisibility=hidden -ffreestanding -fno-builtin -fno-jump-tables -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables -nostdlib -Wall -Wextra"
$CC $CF -c v3dsoft.c -o "$OUT/v3dsoft.o"
$LD -static -nostdlib -e 0 -Ttext=$CAVE -o "$OUT/v3dsoft.elf" "$OUT/v3dsoft.o"
$OC -O binary "$OUT/v3dsoft.elf" "$OUT/v3dsoft.bin"
${CROSS:-arm-linux-gnueabi}-nm "$OUT/v3dsoft.elf" | sort > "$OUT/v3dsoft.sym"
