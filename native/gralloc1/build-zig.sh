#!/bin/sh
# gralloc.default.so (gralloc1_aemu.c) for 32-bit firmware without the NDK: zig cc (LLVM), thumb-linux-musleabi
# (ARMv7-A, Thumb-2). Same idea as native/hwchal/build-zig.sh: the imports are resolved against stand-in
# libc/liblog that carry no code; the stand-ins are not shipped, the guest's bionic provides the real ones.
# musl's off_t is always 64-bit while the guest's bionic ftruncate()/mmap() take a 32-bit one, which is why
# gralloc1_aemu.c goes to the kernel for those two calls (aemu_ftruncate, aemu_map_shared).
#   sh build-zig.sh [output]     (default: gralloc.default.so next to this script)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/gralloc.default.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'EOT'
void *mmap(void *a, unsigned long n, int p, int f, int fd, long o) { (void)a; (void)n; (void)p; (void)f; (void)fd; (void)o; return 0; }
int munmap(void *a, unsigned long n) { (void)a; (void)n; return -1; }
int pthread_mutex_lock(void *m) { (void)m; return 0; }
int pthread_mutex_unlock(void *m) { (void)m; return 0; }
int strcmp(const char *a, const char *b) { (void)a; (void)b; return 0; }
void *memset(void *d, int c, unsigned long n) { (void)c; (void)n; return d; }
void *calloc(unsigned long a, unsigned long b) { (void)a; (void)b; return 0; }
void free(void *p) { (void)p; }
void *memcpy(void *d, const void *s, unsigned long n) { (void)s; (void)n; return d; }
unsigned long strlen(const char *s) { (void)s; return 0; }
char *strncat(char *d, const char *s, unsigned long n) { (void)s; (void)n; return d; }
long syscall(long n, ...) { (void)n; return -1; }
int ftruncate(int fd, long n) { (void)fd; (void)n; return -1; }
int getpid(void) { return 0; }
int close(int fd) { (void)fd; return 0; }
int poll(void *f, unsigned long n, int t) { (void)f; (void)n; (void)t; return 0; }
void *malloc(unsigned long n) { (void)n; return 0; }
int dup(int fd) { (void)fd; return -1; }
char *strncpy(char *d, const char *s, unsigned long n) { (void)s; (void)n; return d; }
EOT
echo 'int __android_log_print(int p, const char *t, const char *f, ...) { (void)p; (void)t; (void)f; return 0; }' > "$STUB/log.c"
T="--target=thumb-linux-musleabi -mcpu=cortex_a9 -mfloat-abi=soft -fPIC -shared -nostdlib -ffreestanding -fno-builtin"
$ZIG cc $T -Os -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
$ZIG cc $T -Os -Wl,-soname,liblog.so -o "$STUB/liblog.so" "$STUB/log.c"
$ZIG cc $T -Os -fvisibility=hidden -fno-stack-protector -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,norelro -Wl,-z,now -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s -Wl,-soname,gralloc.default.so \
  -L"$STUB" -o "$OUT" "$HERE/gralloc1_aemu.c" -Wl,--no-as-needed -lc -llog
if grep -q -E '__aeabi_(u?idiv|uldiv|ldiv|uidivmod)|__aeabi_unwind|__stack_chk' "$OUT"; then echo "error: $OUT imports a helper the guest libc lacks" >&2; exit 1; fi
ls -l "$OUT"
