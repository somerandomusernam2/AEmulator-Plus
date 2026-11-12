#!/bin/sh
set -eu
# libaemubinderguard.so, the guest-side containment of the binder daemon's bare-object fault (see binderguard.c).
# Main path: the NDK, like build.sh. Fallback without it: zig cc (set ZIG, default "python3 -m ziglang"), with stub
# libc.so/libdl.so carrying only the three imports; the stubs are never shipped, the guest's real libraries are used.
OUT=${1:-../../app/src/main/assets/engines/common/libaemubinderguard.so}
mkdir -p "$(dirname "$OUT")"
NOE="-fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables"
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -z "${ZIG:-}" ]; then
  CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
  [ -x "$CC" ] || CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/clang"
  "$CC" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin \
    -fvisibility=hidden -fno-stack-protector $NOE -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--no-undefined \
    -Wl,-soname,libaemubinderguard.so -Wl,--build-id=none -o "$OUT" binderguard.c -lc -ldl
  "$(dirname "$CC")/llvm-strip" "$OUT"
else
  ZIG=${ZIG:-python3 -m ziglang}
  STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
  T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp"
  echo 'char **environ;' > "$STUB/c.c"
  echo 'void *dlopen(const char *p, int f) { return 0; } void *dlsym(void *h, const char *n) { return 0; }' > "$STUB/d.c"
  $ZIG cc $T -fPIC -shared -nostdlib -ffreestanding -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/c.c"
  $ZIG cc $T -fPIC -shared -nostdlib -ffreestanding -Wl,-soname,libdl.so -o "$STUB/libdl.so" "$STUB/d.c"
  $ZIG cc $T -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector $NOE \
    -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-z,max-page-size=4096 -Wl,-s \
    -Wl,-soname,libaemubinderguard.so -L"$STUB" -o "$OUT" binderguard.c -l:libc.so -l:libdl.so
fi
if grep -q __aeabi_ "$OUT"; then echo "error: $OUT imports __aeabi_* (libgcc helper)" >&2; exit 1; fi
ls -l "$OUT"
