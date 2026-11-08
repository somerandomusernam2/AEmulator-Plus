"""Patches libqemu_i386.so and libqemu_x86_64.so (stock QEMU 8.2.7, see tools/qemu-zig) so that prctl(PR_SET_VMA, ...) succeeds.

Why: Android 10's allocators name their anonymous mappings with prctl(0x53564d41 /* PR_SET_VMA */, 0 /* ANON_NAME */, addr, len, name)
through a raw system call (Scudo in the media.swcodec APEX: sanitizer_posix.cc:371 CHECK(internal_prctl(...) == 0), so no LD_PRELOAD
shim sees it). QEMU's do_prctl() has a jump table for options 1..64 only; every other option (PR_SET_VMA is 0x53564d41) takes the
"Unsupported prctl" path and returns -EINVAL without reaching the host. The CHECK fails, the process aborts (exit 134):
    Scudo ERROR: CHECK failed at .../sanitizer_posix.cc:371 ((internal_prctl(0x53564d41, 0, addr, size, (uptr)name) == 0)) != (0)
    qemu: uncaught target signal 6 (Aborted) - core dumped
The name only labels a mapping in /proc/<pid>/maps, so accepting and dropping it is harmless.

What: the "unsupported" block of do_prctl (adrp/ldrb/tbnz LOG_UNIMP test, return -EINVAL, return -EFAULT, the log call) becomes
    A+0x00  mov  w9, #0x4d41 ; movk w9, #0x5356, lsl #16     ; (x9 in the x86_64 build, whose option is a 64-bit abi_long)
    A+0x08  cmp  w8, w9 ; b.eq L                              ; option == PR_SET_VMA -> return 0
    A+0x10  b    L+8                                          ; anything else -> return -EINVAL as before
    L       mov  w0, wzr ; b <ret> ; mov w0, #-22 ; b <ret>
The -EFAULT return at A+0x14 (used by other cases) is left alone; only the LOG_UNIMP "Unsupported prctl" log line is dropped.
Run from the checkout root; every site is checked against the expected original bytes first. Idempotent.
"""
import struct
D = "app/src/main/jniLibs/arm64-v8a/"
NOP = 0xd503201f

def vaddr_to_off(d, va):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, phoff + i * phsz)
        if t == 1 and vad <= va < vad + filesz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

def B(f, t):      return 0x14000000 | (((t - f) // 4) & 0x3ffffff)
def BEQ(f, t):    return 0x54000000 | ((((t - f) // 4) & 0x7ffff) << 5)       # b.eq
def b_target(pc, w):
    assert (w & 0xfc000000) == 0x14000000
    i = w & 0x3ffffff
    if i & 0x2000000: i -= 0x4000000
    return pc + i * 4

# name: (A = the block after the b.hi of the jump-table range check, wide = 64-bit option/return registers)
SITES = {"libqemu_i386.so": (0x11f4c18, False), "libqemu_x86_64.so": (0x11f1044, True)}

for name, (A, wide) in SITES.items():
    path = D + name
    d = bytearray(open(path, "rb").read())
    L = A + 0x1c
    o = vaddr_to_off(d, A)
    cur = struct.unpack_from("<10I", d, o + 0)[:5] + struct.unpack_from("<5I", d, vaddr_to_off(d, L))
    mov9, movk9, cmp8, mov0z, mov0m = ((0xd289a829, 0xf2aa6ac9, 0xeb09011f, 0xaa1f03e0, 0x928002a0) if wide else
                                      (0x5289a829, 0x72aa6ac9, 0x6b09011f, 0x2a1f03e0, 0x128002a0))
    # the return target is the branch at A+0x10 (original) or at L+4 (patched)
    if cur[0] == mov9:
        print(name, "already patched"); continue
    # expected original: adrp x9 / ldrb w9,[x9,#imm] / tbnz w9,#2,L / mov w0|x0,#-22 / b ret
    assert (cur[0] & 0x9f00001f) == 0x90000009, "%s: not adrp x9 at %#x" % (name, A)
    assert (cur[1] & 0xffc003ff) == 0x39400129, "%s: not ldrb w9,[x9,#imm] at %#x" % (name, A + 4)
    assert (cur[2] & 0xfff8001f) == 0x37100009, "%s: not tbnz w9,#2 at %#x" % (name, A + 8)
    assert cur[3] == mov0m, "%s: expected mov w0/x0,#-22 at %#x, found %#x" % (name, A + 12, cur[3])
    ret = b_target(A + 0x10, cur[4])
    assert (cur[5] & 0x9f00001f) == 0x90000000, "%s: log block does not start with adrp x0 at %#x" % (name, L)
    new_a = (mov9, movk9, cmp8, BEQ(A + 0x0c, L), B(A + 0x10, L + 8))
    new_l = (mov0z, B(L + 4, ret), mov0m, B(L + 12, ret), NOP)
    struct.pack_into("<5I", d, o, *new_a)
    struct.pack_into("<5I", d, vaddr_to_off(d, L), *new_l)
    open(path, "wb").write(d)
    print(name, "patched at %#x (PR_SET_VMA returns 0), unsupported options still -EINVAL, ret=%#x" % (A, ret))
