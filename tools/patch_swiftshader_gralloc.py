#!/usr/bin/env python3
"""Make SwiftShader's GrallocModule accept the gralloc module of this guest (API 1.0 with gralloc0 lock/unlock).

GrallocModule::GrallocModule() (ARM) does   module = hw_get_module("gralloc");
                                            if ((module->module_api_version >> 8) == 0) this->module = module;
                                            this->failed = module->module_api_version >> 8;
and lock()/unlock() return -1 (without touching *vaddr) when failed != 0.  Two instructions are changed so the
module is always taken and failed is always 0:
    movs r1, r1, lsr #8   ->  mov  r1, #0
    streq r0, [r4, #4]    ->  str  r0, [r4, #4]
usage: patch_swiftshader_gralloc.py in.so [out.so]   (every SwiftShader library with its own GrallocModule: EGL, GLESv1_CM, GLESv2)
"""
import struct, sys
W = lambda *w: b"".join(struct.pack("<I", x) for x in w)
OLD = W(0xe1d010b4, 0xe1b01421, 0x05840004, 0xe1a00004, 0xe5c41000)   # ldrh r1,[r0,#4]; movs r1,r1,lsr#8; streq r0,[r4,#4]; mov r0,r4; strb r1,[r4]
NEW = W(0xe1d010b4, 0xe3a01000, 0xe5840004, 0xe1a00004, 0xe5c41000)   # ldrh r1,[r0,#4]; mov r1,#0;       str   r0,[r4,#4]; mov r0,r4; strb r1,[r4]
src = sys.argv[1]; dst = sys.argv[2] if len(sys.argv) > 2 else src + ".patched"
d = bytearray(open(src, "rb").read())
n = d.count(OLD)
if n == 0 and d.count(NEW) == 1: sys.exit(src + ": already patched")
if n != 1: sys.exit("%s: expected the GrallocModule constructor once, found %d" % (src, n))
i = d.index(OLD); d[i:i + len(OLD)] = NEW
open(dst, "wb").write(d)
print("patched at file offset 0x%x -> %s" % (i, dst))
