#!/usr/bin/env python3
"""Make libqemu_arm.so answer prctl(PR_SET_VMA) with 0 instead of EINVAL.

Usage: python3 patch_qemu_arm_pr_set_vma.py libqemu_arm.so [out.so]

qemu's do_prctl falls into a default branch for options it doesn't list: it logs "Unsupported prctl: %d" and
returns -TARGET_EINVAL. bionic names its mappings with prctl(PR_SET_VMA, ...) (0x53564d41), so every one of those
calls leaves errno = 22, and Android 11's libvintf returns -errno after a good read (hwservicemanager: -22).
Patch (file offset == vaddr 0x150450, 44 bytes): the default branch returns 0 for option 0x53564d41, -22 for the rest.
The "Unsupported prctl" log line of that branch is dropped.
"""
import sys, hashlib
SRC_SHA256 = "2f64c773fac03f8a588d05aa7cdcec98721083759ea62a91a367fd855990779b"
OFF = 0x150450
ORIG_HEAD, ORIG_TAIL = bytes.fromhex("e0170090"), bytes.fromhex("28dcff17")   # adrp x0,...  /  b 0x147518
NEW = bytes.fromhex(
    "20a88952"   # movz w0, #0x4d41
    "c06aaa72"   # movk w0, #0x5356, lsl #16     (w0 = PR_SET_VMA)
    "9f02006b"   # cmp  w20, w0                  (w20 = option)
    "b3028012"   # mov  w19, #-22
    "f303931a"   # csel w19, wzr, w19, eq        (w19 = result: 0 for PR_SET_VMA)
    "2ddcff17"   # b    0x147518                 (the branch's own exit)
) + bytes.fromhex("1f2003d5") * 5                 # nop
d = bytearray(open(sys.argv[1], "rb").read())
if hashlib.sha256(d).hexdigest() != SRC_SHA256:
    if bytes(d[OFF:OFF+4]) != ORIG_HEAD or bytes(d[OFF+40:OFF+44]) != ORIG_TAIL:
        sys.exit("this is not the libqemu_arm.so this patch was made for")
    print("warning: file differs from the one tested, but the code at the patch site matches")
d[OFF:OFF+44] = NEW
open(sys.argv[2] if len(sys.argv) > 2 else sys.argv[1], "wb").write(d)
print("patched")
