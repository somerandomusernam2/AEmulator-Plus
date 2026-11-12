#!/bin/sh
# hwcomposer.default.so for 32-bit 4.2 - 4.4 firmware that has no composer of its own (see hwc1fb.c): zig cc (LLVM),
# thumb-linux-musleabi (ARMv7-A, Thumb-2). Same idea as native/hwchal/build-zig.sh: the imports are resolved against a
# stand-in libc that is not shipped. The guest's bionic of that time has no versioned symbols, no clock_nanosleep and
# only DT_HASH, hence the plain imports and --hash-style=sysv.
#   sh build-zig.sh [output]     (default: ../../app/src/main/assets/engines/kk/hwcomposer.default.so)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(dirname "$0")
OUT=${1:-$HERE/../../app/src/main/assets/engines/kk/hwcomposer.default.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'EOT'
int open(const char *p, int f, ...) { (void)p; (void)f; return -1; }
int close(int fd) { (void)fd; return 0; }
int ioctl(int fd, unsigned long r, ...) { (void)fd; (void)r; return -1; }
int pthread_create(long *t, const void *a, void *(*fn)(void *), void *g) { (void)t; (void)a; (void)fn; (void)g; return -1; }
int clock_gettime(int c, void *t) { (void)c; (void)t; return -1; }
int nanosleep(const void *r, void *m) { (void)r; (void)m; return -1; }
EOT
T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -fPIC -shared -nostdlib -ffreestanding -fno-builtin"
$ZIG cc $T -Os -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
$ZIG cc $T -Os -fvisibility=hidden -fno-stack-protector -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,norelro -Wl,--build-id=none -Wl,--hash-style=sysv -Wl,--no-undefined -Wl,-s -Wl,-soname,hwcomposer.default.so \
  -L"$STUB" -o "$OUT" "$HERE/hwc1fb.c" -lc
# nothing but the six libc calls may be imported (a libgcc helper such as __aeabi_uldivmod does not exist in the guest)
if command -v readelf >/dev/null 2>&1; then
  BAD=$(readelf --dyn-syms "$OUT" | awk '$7 == "UND" && $8 != "" {print $8}' | grep -v -x -E 'open|close|ioctl|pthread_create|clock_gettime|nanosleep' || true)
  [ -z "$BAD" ] || { echo "error: $OUT imports $BAD, which the guest libc may not have" >&2; exit 1; }
fi
ls -l "$OUT"
