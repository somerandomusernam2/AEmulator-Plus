# Stock QEMU 8.2.7 user-mode translators (libqemu_i386.so, libqemu_x86_64.so)

Unpatched upstream QEMU v8.2.7 `i386-linux-user` / `x86_64-linux-user`, cross-built with Zig (`zig cc -target aarch64-linux-musl`)
as fully static, non-PIE aarch64 executables with no interpreter, so Android can exec them from nativeLibraryDir.
(`libqemu_kk_i386.so` is the SENSES-patched i386 build that used to be called `libqemu_i386.so`.)

Build order, all static, sysroot `$P`: zlib 1.3.1, libffi 3.4.6 (release tarball), pcre2 10.42 (cmake), glib 2.78.6 (meson, `--default-library=static`,
libmount/selinux/xattr/nls disabled), then QEMU:

    ../configure --cc=zcc --cxx=zcxx --host-cc=gcc --cpu=aarch64 --static --disable-pie --disable-system --disable-tools \
      --disable-docs --disable-plugins --disable-werror --disable-capstone --disable-fdt \
      --target-list=i386-linux-user,x86_64-linux-user --extra-cflags=-I$P/include --extra-ldflags=-L$P/lib

Workarounds: `zcc` (this directory) adds `-UNDEBUG` (QEMU refuses NDEBUG), `-D_LARGEFILE64_SOURCE` (musl hides flock64/lseek64/...) and
copies QEMU's thin `*.fa` archives to `*.fa.a` so zig's linker accepts them; `tests/meson.build` had the `subdir('fp')` block removed
(needs a gitlab subproject, tests only). Strip by linking with `-Wl,-s` (zig objcopy cannot strip).
