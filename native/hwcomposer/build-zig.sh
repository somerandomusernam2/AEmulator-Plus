#!/bin/sh
# hwcomposer.default.so (HWC2 "hwc2fb", with the GLES bridge's direct mode) for 32-bit firmware, without the NDK:
# zig cc, thumb-linux-musleabi (ARMv7-A, Thumb-2, NEON), against stand-in libc/liblog that carry the LIBC / LIBC_N
# version tags the guest's bionic expects (the stand-ins are not shipped). Same idea as native/hwchal/build-zig.sh.
#   sh build-zig.sh [output]     (default: hwcomposer.default.so next to this script)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/hwcomposer.default.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -mfpu=neon -fPIC -shared -nostdlib"
{
  for f in calloc close connect free ioctl lseek malloc mmap munmap open pipe poll pthread_create pthread_detach \
           pthread_mutex_lock pthread_mutex_unlock pwrite read snprintf socket strchr strcmp strncpy vsnprintf write \
           clock_gettime clock_nanosleep __system_property_get memcpy memset; do echo "void $f(void) {}"; done
  echo "void __aeabi_memclr4(void) {}"; echo "void __aeabi_memclr8(void) {}"; echo "void __aeabi_memcpy(void) {}"
} > "$STUB/libc.c"
cat > "$STUB/libc.map" <<'EOM'
LIBC { global: calloc; close; connect; free; ioctl; lseek; malloc; mmap; munmap; open; pipe; poll; pthread_create; pthread_detach; pthread_mutex_lock; pthread_mutex_unlock; pwrite; read; snprintf; socket; strchr; strcmp; strncpy; vsnprintf; write; clock_gettime; clock_nanosleep; __system_property_get; memcpy; memset; };
LIBC_N { global: __aeabi_memclr4; __aeabi_memclr8; __aeabi_memcpy; } LIBC;
EOM
echo 'void __android_log_print(void) {}' > "$STUB/liblog.c"
$ZIG cc $T -ffreestanding -Os -Wl,-soname,liblog.so -o "$STUB/liblog.so" "$STUB/liblog.c"
$ZIG cc $T -ffreestanding -Os -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc_stub.so" "$STUB/libc.c"
# (compiled with zig's musl headers, linked without musl: -nostdlib hides the headers, so two steps)
$ZIG cc --target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -mfpu=neon -fPIC -O2 -fvisibility=hidden -fno-stack-protector \
  -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables -I"$HERE/../x86_64/inc" -c "$HERE/hwcomposer.c" -o "$STUB/hwc.o"
$ZIG cc $T -Wl,-z,norelro -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s \
  -Wl,-soname,hwcomposer.default.so -L"$STUB" -o "$OUT" "$STUB/hwc.o" -llog -lc_stub
if readelf -d "$OUT" | grep -q 'libc_stub'; then echo "error: NEEDED libc_stub" >&2; exit 1; fi
ls -l "$OUT"
