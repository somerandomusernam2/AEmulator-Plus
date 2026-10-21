#!/bin/sh
# android.hardware.graphics.allocator@2.0-service of the native arm64 engine (see allocator_service.cpp), for 32-bit
# firmware of Android 8.x whose tree has the allocator's passthrough implementation but not the service:
# sh build.sh <kernel tag> (26 or 27) builds it into that kernel's guest files as vendor/bin/hw/android.hardware.graphics.allocator@2.0-service,
# where the firmware's own rc file (service gralloc-2-0) runs it and TreeFixer's manifest check finds the program.
TAG=${1:?usage: sh build.sh <kernel tag>   (26 or 27)}
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$(dirname "$0")" || exit 1
D="../../app/src/main/assets/engines/n64-$TAG"
mkdir -p "$D"
OUT="$D/vendor#bin#hw#android.hardware.graphics.allocator@2.0-service"
RT32="$(ls "$BIN"/../lib/clang/*/lib/linux/libclang_rt.builtins-arm-android.a | head -1)"
# no libc++ of the NDK: the program only calls into the firmware's own libraries (dlopen), and needs libdl, liblog and libc
"$BIN/clang" --target=armv7a-linux-androideabi26 -mthumb -Os -fPIE -pie -fno-exceptions -fno-rtti -nostdlib++ \
  -fno-stack-protector -Wall -Wl,--build-id=none -Wl,--hash-style=both \
  -o "$OUT" allocator_service.cpp -ldl -llog -lc "$RT32" || exit 1
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
