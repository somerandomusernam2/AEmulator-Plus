#!/bin/sh
# Builds power_service.c, the AIDL android.hardware.power.IPower/default service of the native arm64 engine (kernels 35, 36, 37:
# Android 15 / 16 / 17 images whose system_server has HintManagerService), with zig:
#   sh build-service-zig.sh [output file]
#       (default: app/src/main/assets/engines/n64-{35,36,37}/vendor#bin#hw#android.hardware.power-service.aemu)
# Needs zig (pip install ziglang, or ziglang.org); python3 is used only when "zig" is not on PATH. No NDK, no bionic headers:
# the program declares what it uses itself and brings its own _start (what crtbegin_dynamic.o would), like
# native/guest64/37/build-hwsm.sh. It is linked against empty stand-ins of libc.so / libbinder_ndk.so that only give the dynamic
# section its DT_NEEDED entries and its undefined symbols a home; at run time the guest's own bionic and libbinder_ndk are used.
# The file is installed in the guest as /vendor/bin/hw/android.hardware.power-service.aemu (rc file and VINTF entry beside it
# in the same engine directories: vendor#etc#init#android.hardware.power-service.aemu.rc, vendor#manifest.xml).
set -e
ZIG=${ZIG:-zig}
command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
cd "$(dirname "$0")"
ENGINES=../../app/src/main/assets/engines
NAME='vendor#bin#hw#android.hardware.power-service.aemu'
OUT=${1:-$ENGINES/n64-37/$NAME}
mkdir -p "$(dirname "$OUT")"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TARGET="-target aarch64-linux-android"

# every function power_service.c declares extern (the link fails if one is missing here)
LIBC_FUNCS="write __libc_init"
BINDER_FUNCS="AIBinder_Class_define AIBinder_new AIBinder_markVintfStability AServiceManager_addService
  ABinderProcess_setThreadPoolMaxThreadCount ABinderProcess_joinThreadPool AParcel_writeInt32 AParcel_writeInt64
  AParcel_writeBool AParcel_writeString AParcel_getDataPosition AParcel_getDataSize AParcel_setDataPosition AParcel_writeStatusHeader
  AStatus_newOk AStatus_fromExceptionCode AStatus_delete"
for f in $LIBC_FUNCS; do echo "void $f(void) { for (;;) {} }"; done > "$T/libc_stub.c"
for f in $BINDER_FUNCS; do echo "void $f(void) {}"; done > "$T/libbinder_ndk_stub.c"
for n in c binder_ndk; do
  $ZIG cc $TARGET -w -fno-builtin -fno-sanitize=undefined -nostdlib -shared -fPIC -Wl,-soname,lib$n.so -Wl,--build-id=none \
      -o "$T/lib$n.so" "$T/lib${n}_stub.c"
done

$ZIG cc $TARGET -O2 -fPIE -fno-sanitize=undefined -ffreestanding -nostdlib -fno-stack-protector -fno-unwind-tables \
  -fno-asynchronous-unwind-tables -Wall -Wextra -Wno-unused-parameter -pie -Wl,--dynamic-linker=/system/bin/linker64 \
  -Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,now -Wl,-z,relro -Wl,-z,max-page-size=16384 -Wl,--gc-sections \
  -Wl,-e,_start -Wl,-s \
  -o "$OUT" power_service.c "$T/libbinder_ndk.so" "$T/libc.so"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
# the same binary serves kernels 35, 36 and 37 (their manifests declare IPower/default, version 6)
if [ -z "$1" ]; then
  for k in 35 36; do
    D="$ENGINES/n64-$k"; mkdir -p "$D"; cp "$OUT" "$D/$NAME"
    echo "copied to $D/$NAME"
  done
fi
