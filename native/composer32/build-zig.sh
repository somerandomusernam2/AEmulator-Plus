#!/bin/sh
# Builds the 32-bit android.hardware.graphics.composer@2.1-service (composer_service.cpp) with zig, without the NDK:
#   sh build-zig.sh [output file]       (default: engines/n64-33/arm32/vendor#bin#hw#android.hardware.graphics.composer@2.1-service)
# zig has no bionic, so the program is built -nostdlib: it declares the five libc functions it uses itself
# (AEMU_NO_BIONIC_HEADERS), brings its own _start (what crtbegin_dynamic.o would), and is linked against empty stand-ins of
# libc.so / libdl.so / liblog.so that only give the dynamic section its DT_NEEDED entries. At run time the guest's own
# bionic libraries are used. Needs: zig (pip install ziglang, or ziglang.org), python3 only if "zig" is not on PATH.
set -e
ZIG=${ZIG:-zig}
command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
cd "$(dirname "$0")"
OUT=${1:-../../app/src/main/assets/engines/n64-33/arm32/vendor#bin#hw#android.hardware.graphics.composer@2.1-service}
mkdir -p "$(dirname "$OUT")"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TARGET="-target arm-linux-androideabi -mcpu=cortex_a9 -marm -mfloat-abi=softfp"

cat > "$T/libc_stub.c"   <<'S'
void __libc_init(void) {} void abort(void) { for (;;) {} } void _exit(void) { for (;;) {} }
S
cat > "$T/libdl_stub.c"  <<'S'
void *dlopen(void) { return 0; } void *dlsym(void) { return 0; } char *dlerror(void) { return 0; }
S
cat > "$T/liblog_stub.c" <<'S'
int __android_log_print(void) { return 0; }
S
for n in c dl log; do
  $ZIG cc $TARGET -nostdlib -shared -fPIC -Wl,-soname,lib$n.so -Wl,--build-id=none \
      -o "$T/lib$n.so" "$T/lib${n}_stub.c"
done

$ZIG cc -x c++ $TARGET -DAEMU_NO_BIONIC_HEADERS -Os -fPIE -ffreestanding -nostdinc++ -nostdlib \
  -fno-exceptions -fno-rtti -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables -Wall \
  -pie -Wl,--dynamic-linker=/system/bin/linker -Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,now -Wl,-z,relro \
  -rdynamic -fvisibility=hidden -Wl,--gc-sections -Wl,-e,_start \
  -o "$OUT" composer_service.cpp -x none "$T/libdl.so" "$T/liblog.so" "$T/libc.so"
echo "built $OUT"
