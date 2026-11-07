#!/bin/sh
# Fallback build of the guest shim without the NDK: zig cc (LLVM), target thumb-linux-musleabi (ARMv7-A, Thumb-2).
# Produces the same libaemushim.so / librecshim.so as build.sh. The shim is freestanding (raw syscalls); the only
# libc imports are __errno, environ and fdopen, which come from a stub libc.so with LIBC-versioned symbols, the same
# names the NDK build imports (the guest's real libc.so is used at run time; the stub is never shipped).
# Main build path: build.sh with the NDK (docs/build-source.md).
set -eu
ZIG=${ZIG:-python3 -m ziglang}
OUT=${1:-../../app/src/main/assets/engines/common/libaemushim.so}
DIR=$(dirname "$OUT")
mkdir -p "$DIR"
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT

cat > "$STUB/libc.c" <<'C'
/* Stub of the NDK libc.so: only the symbols the shim imports. */
char **environ;
int *__errno(void) { static int e; return &e; }
void *fdopen(int fd, const char *mode) { (void)fd; (void)mode; return 0; }
C
echo 'LIBC { global: __errno; environ; fdopen; };' > "$STUB/libc.map"

T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp"
$ZIG cc $T -fPIC -shared -nostdlib -ffreestanding -fno-builtin -Os \
    -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc_stub.so" "$STUB/libc.c"

NOE="-fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables"
F="$T -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE
   -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-z,max-page-size=4096 -Wl,-s -L$STUB"
$ZIG cc $F -Wl,-soname,libaemushim.so -o "$OUT" aemushim.c crash.c -lc_stub
$ZIG cc $F -Wl,-soname,librecshim.so -o "$DIR/librecshim.so" recshim.c -lc_stub
# an empty program in place of iptables/ip6tables
$ZIG cc --target=thumb-linux-musleabi -mcpu=cortex_a8 -nostdlib -static -Wl,--build-id=none -Wl,-s -o "$DIR/aemu_true.so" true.S

for lib in "$OUT" "$DIR/librecshim.so"; do
    if grep -q __aeabi_ "$lib"; then echo "error: $lib imports __aeabi_* (libgcc helper)" >&2; exit 1; fi
    ls -l "$lib"
done
