#!/usr/bin/env python3
"""
Detect a firmware whose android_native_base_t is one word longer than AOSP's (Spreadtrum "MocorDroid" 2.3.5 / 4.0.3 libui),
and make a copy of the shipped GLES bridge that calls the window's query/perform at the firmware's slots.

  python3 patch_gles_bridge_mocordroid.py detect  <firmware libui.so>
  python3 patch_gles_bridge_mocordroid.py patch   <libui.so> <engines/kk/libGLES.so> <out libGLES_mocordroid.so>
  python3 patch_gles_bridge_mocordroid.py force   <shift> <engines/kk/libGLES.so> <out>      (skip the probe)

Why it exists (all measured on the Sprd GT-N9300 image, libui.so 208088 bytes):
  AOSP (ICS..KK)            this firmware
  window->query     +0x54   +0x58        FramebufferNativeWindow ctor stores setSwapInterval/dequeue/lock/queue/query/perform
  window->perform   +0x58   +0x5c        at +0x48..+0x5c instead of +0x44..+0x58 (one word later)
  buffer->handle    +0x3c   +0x40        NativeBuffer ctor: width at +0x24 (not +0x20), version word 0x64 (not 0x60)
The extra word sits at +0x20, right after incRef/decRef: it is in native_base itself, so every window AND every buffer
is shifted. FramebufferNativeWindow::queueBuffer reads buffer->handle at +0x40, so the bridge's "query(win, 0, &w)"
(= queueBuffer(win, NULL)) faults at 0x40. The window's `version` word (0x6c) equals KK's sizeof(ANativeWindow), so the
version field can NOT tell the two layouts apart: the probe reads the code instead.

The shipped bridge (assets/engines/kk/libGLES.so, installed as libGLES_bridge.so) calls a window slot at exactly three
places: eglCreateWindowSurface (+0x54 query, +0x58 perform) and eglDestroySurface (+0x58 perform).
"""
import struct, sys


# ---------------------------------------------------------------- ELF helpers
def u16(d, o): return struct.unpack_from('<H', d, o)[0]
def u32(d, o): return struct.unpack_from('<I', d, o)[0]


def elf_symbols(d):
    """(name -> (value, size)) from the dynamic segment of a 32-bit LE ARM ELF (same walk as NativeWindowProbe.kt)."""
    assert d[:4] == b'\x7fELF' and d[4] == 1 and d[5] == 1 and u16(d, 18) == 40, 'not an ARM32 LE ELF'
    phoff, phent, phnum = u32(d, 0x1c), u16(d, 0x2a), u16(d, 0x2c)
    dyn = None
    for i in range(phnum):
        p = struct.unpack_from('<8I', d, phoff + i * phent)
        if p[0] == 2: dyn = (p[1], p[5])
    tags = {}
    for k in range(0, dyn[1], 8):
        t, v = struct.unpack_from('<II', d, dyn[0] + k)
        if t == 0: break
        tags.setdefault(t, v)
    sym, st = vaddr_to_off(d, tags[6]), vaddr_to_off(d, tags[5])
    nsym = u32(d, vaddr_to_off(d, tags[4]) + 4) if 4 in tags else (st - sym) // 16
    out = {}
    for k in range(nsym):
        n, v, sz = struct.unpack_from('<III', d, sym + k * 16)
        if not v: continue
        e = st + n
        out[d[e:d.index(b'\0', e)].decode('latin1')] = (v, sz)
    return out


def vaddr_to_off(d, v):
    phoff, phentsize, phnum = u32(d, 0x1c), u16(d, 0x2a), u16(d, 0x2c)
    for i in range(phnum):
        p = struct.unpack_from('<8I', d, phoff + i * phentsize)
        if p[0] == 1 and p[3] <= v < p[3] + p[5]:
            return v - p[3] + p[1]
    raise ValueError(hex(v))


# ---------------------------------------------------------------- the probe (Thumb-2, FramebufferNativeWindow::queueBuffer)
def thumb_iter(d, off, size):
    p, end = off, off + size
    while p + 2 <= end:
        h1 = u16(d, p)
        if (h1 >> 11) in (0b11101, 0b11110, 0b11111) and p + 4 <= end:
            yield p, h1, u16(d, p + 2)
            p += 4
        else:
            yield p, h1, None
            p += 2


def buffer_handle_offset(libui):
    """Offset of buffer->handle as FramebufferNativeWindow::queueBuffer reads it (AOSP: 0x3c), or None if the
    function is not Thumb-2 code of the expected shape. Nothing is guessed: no hit -> None -> no variant."""
    syms = elf_symbols(libui)
    cand = [(n, v) for n, v in syms.items()
            if n.startswith('_ZN7android23FramebufferNativeWindow11queueBufferE')]
    if not cand: return None
    v, sz = cand[0][1]
    if not (v & 1): return None                       # ARM-mode build: not handled, fail safe
    off, size = vaddr_to_off(libui, v & ~1), sz
    bufregs = {1}                                      # r1 = the buffer argument; `mov rX, r1` adds rX
    for p, h1, h2 in thumb_iter(libui, off, size):
        if h2 is None:
            if (h1 & 0xff00) == 0x4600 and ((h1 >> 3) & 0xf) in bufregs:          # mov rd, rm (T1)
                bufregs.add(((h1 >> 4) & 8) | (h1 & 7))
            elif (h1 & 0xf800) == 0x6800 and ((h1 >> 3) & 7) in bufregs:          # ldr rt,[rn,#imm5*4] (T1)
                return ((h1 >> 6) & 0x1f) * 4
        else:
            if (h1 & 0xfff0) == 0xf8d0 and (h1 & 0xf) in bufregs:                 # ldr.w rt,[rn,#imm12] (T3)
                return h2 & 0xfff
    return None


def detect(libui_bytes):
    h = buffer_handle_offset(libui_bytes)
    if h is None: return None, 'queueBuffer not recognised'
    shift = h - 0x3c
    if shift not in (0, 4): return None, 'unexpected handle offset 0x%x' % h
    return shift, 'buffer->handle at +0x%x' % h


# ---------------------------------------------------------------- the patch (ARM-mode bridge)
def patch_bridge(bridge, shift):
    """Add `shift` to the displacement of every window-slot call in eglCreateWindowSurface / eglDestroySurface."""
    out = bytearray(bridge)
    syms = elf_symbols(bridge)
    sites = []
    for fn in ('eglCreateWindowSurface', 'eglDestroySurface'):
        v, sz = syms[fn]
        assert not (v & 1), fn + ' is not ARM code'
        base = vaddr_to_off(bridge, v)
        for q in range(base, base + sz - 4, 4):
            w = u32(bridge, q)
            # LDR Rt,[Rn,#+imm12]  (P=1,U=1,B=0,W=0,L=1)
            if (w & 0x0f700000) == 0x05100000 and (w & 0xfff) in (0x54, 0x58):
                rt = (w >> 12) & 0xf
                for k in range(1, 12):                 # followed by BLX rt before the register is reused
                    n = u32(bridge, q + 4 * k)
                    if (n & 0x0ffffff0) == 0x012fff30 and (n & 0xf) == rt:
                        sites.append((fn, q, w & 0xfff)); break
    assert len(sites) == 3, 'expected 3 window-slot calls, found %d: %r' % (len(sites), sites)
    for fn, q, imm in sites:
        w = u32(bridge, q)
        struct.pack_into('<I', out, q, (w & ~0xfff) | (imm + shift))
    return bytes(out), sites


def main(a):
    if len(a) >= 2 and a[0] == 'detect':
        s, why = detect(open(a[1], 'rb').read())
        print('shift=%s  (%s)' % (s, why)); return 0 if s is not None else 1
    if len(a) == 4 and a[0] == 'patch':
        libui = open(a[1], 'rb').read()
        s, why = detect(libui)
        print('probe:', s, why)
        if not s: print('no shift: nothing to patch'); return 1
        data, sites = patch_bridge(open(a[2], 'rb').read(), s)
    elif len(a) == 4 and a[0] == 'force':
        s = int(a[1], 0); data, sites = patch_bridge(open(a[2], 'rb').read(), s)
    else:
        print(__doc__); return 2
    open(a[3], 'wb').write(data)
    for fn, q, imm in sites: print('  %-24s file+0x%x  [rN,#0x%x] -> [rN,#0x%x]' % (fn, q, imm, imm + s))
    print('wrote', a[3], len(data), 'bytes')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
