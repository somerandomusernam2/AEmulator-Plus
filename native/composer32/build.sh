#!/bin/sh
# android.hardware.graphics.composer@2.1-service of the native arm64 engine, 32-bit (see composer_service.cpp), for kernel 33:
# sh build.sh builds it into engines/n64-33/arm32/ as vendor#bin#hw#android.hardware.graphics.composer@2.1-service.
# (composer_impl.cpp needs an AOSP tree, see README.md / Android.bp; this program only needs the NDK.)
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -x "$BIN/clang" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$(dirname "$0")" || exit 1
D="../../app/src/main/assets/engines/n64-33/arm32"
mkdir -p "$D"
OUT="$D/vendor#bin#hw#android.hardware.graphics.composer@2.1-service"
RT32="$(ls "$BIN"/../lib/clang/*/lib/linux/libclang_rt.builtins-arm-android.a | head -1)"
# no libc++ of the NDK: the program only calls into the firmware's own libraries (dlopen), and needs libdl, liblog and libc
"$BIN/clang" --target=armv7a-linux-androideabi26 -mthumb -Os -fPIE -pie -fno-exceptions -fno-rtti -nostdlib++ \
  -fno-stack-protector -Wall -Wl,--build-id=none -Wl,--hash-style=both \
  -o "$OUT" composer_service.cpp -ldl -llog -lc "$RT32" || exit 1
"$BIN/llvm-strip" "$OUT" 2>/dev/null || true
