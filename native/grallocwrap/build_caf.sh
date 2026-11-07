#!/bin/sh
# gralloc of the 4.4 engine for Qualcomm-built libui (gralloc_caf.c): engines/kk/gralloc.caf.so. TreeFixer puts it in
# as system/lib/hw/gralloc.default.so over the module that was there, kept as system/lib/libgralloc_aosp.so.
# sysv hash: the linker of 4.x/5.x does not read GNU hash tables. sh test_caf.sh checks its logic on the build machine.
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$(dirname "$0")" || exit 1
OUT=../../app/src/main/assets/engines/kk/gralloc.caf.so
"$BIN/clang" --target=armv7a-linux-androideabi21 -march=armv7-a -mthumb -Os -fPIC -shared \
  -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector \
  -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,gralloc.default.so \
  -o "$OUT" gralloc_caf.c -ldl -lc || exit 1
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
ls -la "$OUT"
