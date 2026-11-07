#!/bin/sh
# x86-32 guest shims. Need gcc/clang able to emit i386 code (gcc -m32); no libc, no NDK.
#   ashmem_x86.c   -> kk-x86/libashmemshim.so    (ashmem on memfd, see the file)
#   aemushim_x86.c -> common-x86/libaemushim.so  (host paths hidden from zygote's fd table, see the file)
#   true_x86.S     -> common-x86/aemu_true.so    (exit(0) stand-in for iptables/ip6tables, see the file)
set -eu
ASSETS=${1:-../../app/src/main/assets/engines}
CC=${CC:-gcc}
# The shims are freestanding (raw int 0x80, nothing from libgcc or any libc linked in); the one import is bionic's
# __errno, which has to reach the guest's own errno. Without a DT_NEEDED entry the guest's linker cannot find it when it
# loads the shim as an LD_PRELOAD ("cannot locate symbol __errno referenced by libaemushim.so", every service dies at
# start). Same as the ARM build (build-zig.sh): link against a throw-away stub named libc.so, never shipped.
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT
printf 'int *__errno(void) { static int e; return &e; }\n' > "$STUB/libc.c"
$CC -m32 -Os -fPIC -shared -nostdlib -ffreestanding -fno-stack-protector -Wl,--hash-style=sysv -Wl,--build-id=none \
    -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
build() {   # src out soname
    mkdir -p "$(dirname "$2")"
    $CC -m32 -Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -fvisibility=hidden \
        -fno-tree-loop-distribute-patterns -fno-asynchronous-unwind-tables -fno-unwind-tables \
        -Wl,--hash-style=sysv -Wl,-z,norelro -Wl,-z,noexecstack -Wl,--build-id=none -Wl,-soname,"$3" -Wl,--no-undefined -o "$2" "$1" "$STUB/libc.so"
    ${STRIP:-strip} --strip-unneeded "$2"
}
build ashmem_x86.c   "$ASSETS/kk-x86/libashmemshim.so"      libashmemshim.so
build aemushim_x86.c "$ASSETS/common-x86/libaemushim.so"    libaemushim.so
# aemu_true.so: exit(0) program installed in place of iptables/ip6tables (no netfilter in the emulator; see true_x86.S)
mkdir -p "$ASSETS/common-x86"
${AS:-as} --32 true_x86.S -o /tmp/aemu_true_x86.o
${LD:-ld} -m elf_i386 -static -nostdlib --build-id=none -z noexecstack -o "$ASSETS/common-x86/aemu_true.so" /tmp/aemu_true_x86.o
${STRIP:-strip} "$ASSETS/common-x86/aemu_true.so"
rm -f /tmp/aemu_true_x86.o
