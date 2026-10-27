#!/bin/sh
# hwcomposer.default.so for 32-bit firmware without the NDK: zig cc (LLVM), thumb-linux-musleabi (ARMv7-A, Thumb-2, NEON).
# Same idea as native/audiohal/build-zig.sh: the imports are resolved against stand-in libc/libhardware that carry the
# LIBC / LIBC_N version tags the guest's bionic expects; the stand-ins are not shipped.
#   sh build-zig.sh [output]     (default: hwcomposer.default.so next to this script)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
OUT=${1:-$(dirname "$0")/hwcomposer.default.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
echo 'int hw_get_module(const char *id, const void **m) { (void)id; (void)m; return -1; }' > "$STUB/hw.c"
cat > "$STUB/libc.c" <<'EOF'
int __system_property_get(const char *n, char *v) { (void)n; (void)v; return 0; }
int open(const char *p, int f, ...) { (void)p; (void)f; return -1; }
void __aeabi_memclr8(void *d, unsigned long n) { (void)d; (void)n; }
int ioctl(int fd, unsigned long r, ...) { (void)fd; (void)r; return -1; }
long lseek(int fd, long o, int w) { (void)fd; (void)o; (void)w; return -1; }
void *mmap(void *a, unsigned long n, int p, int f, int fd, long o) { (void)a; (void)n; (void)p; (void)f; (void)fd; (void)o; return 0; }
int close(int fd) { (void)fd; return 0; }
void *malloc(unsigned long n) { (void)n; return 0; }
int pthread_create(long *t, const void *a, void *(*fn)(void *), void *g) { (void)t; (void)a; (void)fn; (void)g; return -1; }
void *memcpy(void *d, const void *s, unsigned long n) { (void)s; (void)n; return d; }
void *memset(void *d, int c, unsigned long n) { (void)c; (void)n; return d; }
long write(int fd, const void *b, unsigned long n) { (void)fd; (void)b; (void)n; return -1; }
long read(int fd, void *b, unsigned long n) { (void)fd; (void)b; (void)n; return -1; }
int socket(int d, int t, int p) { (void)d; (void)t; (void)p; return -1; }
int connect(int fd, const void *a, unsigned l) { (void)fd; (void)a; (void)l; return -1; }
int clock_gettime(int c, void *t) { (void)c; (void)t; return -1; }
int clock_nanosleep(int c, int f, const void *r, void *m) { (void)c; (void)f; (void)r; (void)m; return -1; }
EOF
cat > "$STUB/libc.map" <<'EOF'
LIBC { global: __system_property_get; open; ioctl; lseek; mmap; close; malloc; pthread_create; memcpy; memset; write; read; socket; connect; clock_gettime; clock_nanosleep; };
LIBC_N { global: __aeabi_memclr8; } LIBC;
EOF
T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -mfpu=neon -fPIC -shared -nostdlib -ffreestanding -fno-builtin"
$ZIG cc $T -Os -Wl,-soname,libhardware.so -o "$STUB/libhardware.so" "$STUB/hw.c"
$ZIG cc $T -Os -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc_stub.so" "$STUB/libc.c"
$ZIG cc $T -O2 -fvisibility=hidden -fno-stack-protector -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,norelro -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s -Wl,-soname,hwcomposer.default.so \
  -L"$STUB" -o "$OUT" "$(dirname "$0")/hwc.c" -lhardware -lc_stub
if grep -q -E '__aeabi_(u?idiv|uldiv|ldiv)|__aeabi_unwind' "$OUT"; then echo "error: $OUT imports a libgcc helper the guest libc lacks" >&2; exit 1; fi
ls -l "$OUT"
