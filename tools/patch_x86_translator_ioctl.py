"""Patches libqemu_x86_64.so and libqemu_i386.so (stock QEMU 8.2.7, see tools/qemu-zig) so that an ioctl QEMU has no table
entry for goes to the host as it is, the way the ARM translator already does it.

Why: the guest's libbinder talks to /dev/binder with ioctl(BINDER_VERSION / BINDER_WRITE_READ / ...). Stock QEMU
(linux-user/syscall.c do_ioctl) answers every command that is not in ioctl_entries[] with -ENOTTY and never makes the host
call, so the guest runner (native/guest64/<api>/run64.c), which catches the host call in its seccomp filter and serves the
binder, never sees it: servicemanager says "Binder driver '/dev/binder' could not be opened. Terminating." and every
service dies with 139 (the abort handler itself then faults under the translator). The same goes for the ashmem, ion, fb
and input ioctls. libqemu_arm.so already forwards the unknown command (TYPE_INT-style: cmd and the raw argument).

What: do_ioctl's table-miss path (3 instructions: adrp/ldrb/tbnz that look at the LOG_UNIMP bit, then the caller returns
-ENOTTY) becomes
    mov  w3, w1            ; host cmd = the guest's cmd
    b    <the generic TYPE_INT/PTRVOID passthrough of the same function>   ; safe_syscall(ioctl, fd, cmd, arg)
    nop
The jump target is the block QEMU itself uses for an entry with no argument conversion, so the signal-safe syscall wrapper,
errno handling and stack-protector epilogue are the function's own. Entries that exist but have host_cmd == 0 (cbz w3 -> ENOTTY)
are left alone. Guest and host addresses are the same (-B 0), so the pointer argument needs no translation.
Run from the checkout root; every site is checked against the expected original bytes first. Idempotent.
"""
import struct, sys
D = "app/src/main/jniLibs/arm64-v8a/"
NOP = 0xd503201f
MOV_W3_W1 = 0x2a0103e3
B = lambda f, t: 0x14000000 | (((t - f) // 4) & 0x3ffffff)
def vaddr_to_off(d, va):
    # program headers of a 64-bit LE ELF
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        p = phoff + i * phsz
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, p)
        if t == 1 and vad <= va < vad + filesz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

def patch(name, miss, target):
    path = D + name
    d = bytearray(open(path, "rb").read())
    o = vaddr_to_off(d, miss)
    w = struct.unpack_from("<III", d, o)
    new = (MOV_W3_W1, B(miss + 4, target), NOP)
    if w == new:
        print(name, "already patched"); return
    # expected original: adrp x8,..  /  ldrb w8,[x8,#imm]  /  tbnz w8,#2,<log>
    assert (w[0] & 0x9f00001f) == 0x90000008, "%s: not an adrp x8 at %#x" % (name, miss)
    assert (w[1] & 0xffc003ff) == 0x39400108, "%s: not ldrb w8,[x8,#imm] at %#x" % (name, miss + 4)
    assert (w[2] & 0xfff8001f) == 0x37100008, "%s: not tbnz w8,#2 at %#x" % (name, miss + 8)
    # the target must be 'ldr w10/mov w10,#imm' of the passthrough block: movz x8,#0 comes first
    t = struct.unpack_from("<I", d, vaddr_to_off(d, target))[0]
    assert t == 0xd2a00008 or t == 0xd2800008 or (t & 0xff800000) == 0xd2800000, "%s: unexpected passthrough block head %#x" % (name, t)
    struct.pack_into("<III", d, o, *new)
    open(path, "wb").write(d)
    print(name, "patched at %#x -> %#x" % (miss, target))

patch("libqemu_x86_64.so", 0x11ec090, 0x11ec110)
patch("libqemu_i386.so", 0x11eec94, 0x11eed14)
