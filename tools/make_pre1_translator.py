"""Builds app/src/main/jniLibs/arm64-v8a/libqemu_arm_pre1.0.so from libqemu_kk.so (QEMU 8.2.4, aarch64 static-pie).

READ_IMPLIES_EXEC for instruction fetch (same effect as qemu-senses patch 0098, which the kk build lacks). In
get_page_addr_code_hostp (page_get_flags inlined) the fetch test is, at file offset == vaddr 0x164678:
    tbnz w21, #2, <fetch ok 0x1646b4>          (PAGE_EXEC set?)
Replaced by a jump to a 16-byte padding cave (0x2bf690) that accepts PAGE_READ or PAGE_EXEC (the ARM kernel-helper
page at 0xffff0000 is execute-only):
    tbnz w21,#0,ok ; tbnz w21,#2,ok ; b <fault 0x16467c> ; ok: b <fetch ok 0x1646b4>
"""
import struct
d = "app/src/main/jniLibs/arm64-v8a/"
b = bytearray(open(d + "libqemu_kk.so", "rb").read())
C, SITE, OK, FAIL = 0x2bf690, 0x164678, 0x1646b4, 0x16467c
assert b[SITE:SITE+4] == bytes.fromhex("f5011037") and b[C:C+16] == bytes.fromhex("1f2003d5") * 4, "libqemu_kk.so is not the expected build"
B = lambda f, t: 0x14000000 | (((t - f) // 4) & 0x3ffffff)
TBNZ = lambda f, t, bit, rt: 0x37000000 | (bit << 19) | ((((t - f) // 4) & 0x3fff) << 5) | rt
code = [TBNZ(C, C+12, 0, 21), TBNZ(C+4, C+12, 2, 21), B(C+8, FAIL), B(C+12, OK)]
b[C:C+16] = b"".join(struct.pack("<I", w) for w in code)
b[SITE:SITE+4] = struct.pack("<I", B(SITE, C))
open(d + "libqemu_arm_pre1.0.so", "wb").write(b)
print("ok")
