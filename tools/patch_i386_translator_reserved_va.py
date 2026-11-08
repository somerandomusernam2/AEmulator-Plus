"""Patches libqemu_i386.so (stock QEMU 8.2.7, see tools/qemu-zig) so that it runs with `-B 0` (guest address == host address).

Why: the native engine starts every 32-bit program of an x86 / x86-64 firmware as `libqemu_i386.so -B 0 -0 <argv0> <prog> ...`
(native/guest64/<api>/run64.c; guest pointers must equal host pointers so the binder / ashmem ioctls the runner serves work).
QEMU's linux-user main() reserves the guest's whole 32-bit address space by default (reserved_va = 0xffffffff on a 64-bit host
for a 32-bit target), and with `-B 0` that reservation is [0, 4 GiB) of the HOST. libqemu_i386.so is a non-PIE static
executable linked at 0x1000000, so its own image sits inside that range and pgb_fixed() fails:
    libqemu_i386.so: /system/bin/app_process32: requires virtual address space that is in use (omit the -B option ...)
That is the exit code 1 of zygote_secondary (and of every other 32-bit program: audio HAL, ...). `-R 0` does not help
(`reserved_va = val ? val-1 : 0`, and 0 is then replaced by the same default).

What: main() does
    if (reserved_va == 0 && HOST_LONG_BITS == 64 && TARGET_VIRT_ADDR_SPACE_BITS <= 32) reserved_va = max_reserved_va;
which is   mov w8,#-1 ; str x8,[x9,#0x8d0]   at 0x11d3dc8. The store becomes a nop: reserved_va stays 0, nothing is
reserved, guest mmap()s are placed by the host kernel below 4 GiB the way QEMU did before it reserved (mmap_find_vma, hint
based), and the guest-visible limit is still UINT32_MAX (GUEST_ADDR_MAX). The constants that the same block loads
(task_unmapped_base 0x40000000, elf_et_dyn_base 0x400000) are in registers that stay set, so nothing else changes.
libqemu_x86_64.so is not affected (a 64-bit target reserves nothing by default).
Run from the checkout root; the site is checked against the expected original bytes first. Idempotent.
"""
import struct
D = "app/src/main/jniLibs/arm64-v8a/"
NOP = 0xd503201f
STR_X8_X9_8D0 = 0xf9046928      # str x8, [x9, #0x8d0]   (reserved_va = max_reserved_va)

def vaddr_to_off(d, va):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, phoff + i * phsz)
        if t == 1 and vad <= va < vad + filesz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

path = D + "libqemu_i386.so"
d = bytearray(open(path, "rb").read())
o = vaddr_to_off(d, 0x11d3dc8)
w = struct.unpack_from("<I", d, o)[0]
if w == NOP:
    print("libqemu_i386.so: already patched")
else:
    assert w == STR_X8_X9_8D0, "libqemu_i386.so: unexpected instruction %#x at 0x11d3dc8 (another build of the translator?)" % w
    # the instruction before it must be 'mov w8, #-1' (the default being replaced)
    p = struct.unpack_from("<I", d, o - 4)[0]
    assert p == 0x12800008, "libqemu_i386.so: expected mov w8,#-1 before the store, found %#x" % p
    struct.pack_into("<I", d, o, NOP)
    open(path, "wb").write(d)
    print("libqemu_i386.so: patched at 0x11d3dc8 (reserved_va is no longer reserved)")
