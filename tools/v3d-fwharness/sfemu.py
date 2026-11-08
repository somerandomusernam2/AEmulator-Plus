"""Run the real libsurfaceflinger V3D_Compose code under Unicorn, with /dev/v3d + /dev/gememalloc faked in Python.
Used to capture the exact command lists the firmware submits (ground truth for native/v3demu)."""
import struct, sys
from elftools.elf.elffile import ELFFile
from unicorn import *
from unicorn.arm_const import *

LIB = __import__('os').environ.get('V3D_SF_LIB', 'libsurfaceflinger.so')
BASE = 0xac500000   # the library is prelinked here: absolute pointers in its data already assume it
HOOK = 0xF0000000
HEAP = 0x30000000; HEAP_SZ = 0x1000000
POOL_V = 0x50000000                  # virtual view of the gememalloc pool
POOL_P = 0x40000000                  # "physical" base handed out by the fake gememalloc
POOL_SZ = 0x1000000
FB_P = 0x60000000                    # fake fb "physical" base
FB_V = 0x70000000
STACK = 0x20000000; STACK_SZ = 0x100000

class SF:
    def __init__(self, fbw=480, fbh=800, fbfmt=4, log=True):
        self.log = log
        self.fbw, self.fbh, self.fbfmt = fbw, fbh, fbfmt
        f = open(LIB, 'rb'); self.e = ELFFile(f)
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
        uc = self.uc
        top = 0
        for s in self.e.iter_segments():
            if s['p_type'] == 'PT_LOAD': top = max(top, s['p_vaddr'] + s['p_memsz'])
        uc.mem_map(BASE, (top + 0xfff) & ~0xfff)
        for s in self.e.iter_segments():
            if s['p_type'] == 'PT_LOAD': uc.mem_write(BASE + s['p_vaddr'], s.data())
        uc.mem_map(HOOK, 0x10000); uc.mem_map(HEAP, HEAP_SZ); uc.mem_map(STACK, STACK_SZ)
        uc.mem_map(POOL_V, POOL_SZ); uc.mem_map(FB_V, 0x400000)
        self.heap_ptr = HEAP; self.pool_ptr = 0x1000; self.fds = {}; self.nextfd = 3
        self.imports = {}; self.hookid = 0
        self.syms = {}
        for n in ('.dynsym',):
            for s in self.e.get_section_by_name(n).iter_symbols():
                if s['st_shndx'] != 'SHN_UNDEF' and s['st_value']: self.syms[s.name] = BASE + s['st_value']
        self._reloc()
        uc.hook_add(UC_HOOK_CODE, self._code, begin=HOOK, end=HOOK + 0x10000)
        self.ioctls = []
        self.submits = []
        self.errno = 0
        uc.reg_write(UC_ARM_REG_SP, STACK + STACK_SZ - 0x100)
        self.errno_addr = self.malloc(4)
    def _reloc(self):
        ds = self.e.get_section_by_name('.dynsym')
        for secname in ('.rel.plt', '.rel.dyn'):
            sec = self.e.get_section_by_name(secname)
            if not sec: continue
            for r in sec.iter_relocations():
                t = r['r_info_type']; off = BASE + r['r_offset']
                sym = ds.get_symbol(r['r_info_sym']) if r['r_info_sym'] else None
                if t == 23:  # RELATIVE: prelinked already
                    pass
                elif t in (21, 22, 2):  # GLOB_DAT, JUMP_SLOT, ABS32
                    if sym['st_shndx'] != 'SHN_UNDEF' and sym['st_value']:
                        a = BASE + sym['st_value']
                    else:
                        a = self._import(sym.name)
                    if t == 2:
                        v = struct.unpack('<I', self.uc.mem_read(off, 4))[0]; a += v
                    self.uc.mem_write(off, struct.pack('<I', a & 0xffffffff))
    def _import(self, name):
        if name in ('__page_size', '__stack_chk_guard'):
            if name not in self.imports:
                a = HOOK + 0x8000 + 4 * len(self.imports); self.imports[name] = (a, name)
                self.uc.mem_write(a, struct.pack('<I', 4096 if name == '__page_size' else 0x1234567))
            return self.imports[name][0]
        if name in self.imports: return self.imports[name][0]
        a = HOOK + 4 * self.hookid; self.hookid += 1
        self.imports[name] = (a, name); self.byaddr = getattr(self, 'byaddr', {}); self.byaddr[a] = name
        return a
    def malloc(self, n):
        n = (n + 15) & ~15; p = self.heap_ptr; self.heap_ptr += n; return p
    def reg(self, r): return self.uc.reg_read(r)
    def rd(self, a, n): return bytes(self.uc.mem_read(a, n))
    def wr(self, a, b): self.uc.mem_write(a, bytes(b))
    def u32(self, a): return struct.unpack('<I', self.rd(a, 4))[0]
    def cstr(self, a):
        s = b''
        while True:
            c = self.rd(a, 1)
            if c == b'\0': return s.decode('latin1')
            s += c; a += 1
    def arg(self, i):
        if i < 4: return self.reg([UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3][i])
        return self.u32(self.reg(UC_ARM_REG_SP) + 4 * (i - 4))
    def _code(self, uc, addr, size, ud):
        name = self.byaddr.get(addr)
        ret = self.handle(name)
        if ret is not None: uc.reg_write(UC_ARM_REG_R0, ret & 0xffffffff)
        lr = uc.reg_read(UC_ARM_REG_LR)
        uc.reg_write(UC_ARM_REG_PC, lr)   # bit0 selects thumb
    def handle(self, n):
        a = self.arg
        if n == '__android_log_print':
            if self.log: print('  [log]', self.cstr(a(2)), '|', [hex(a(i)) for i in range(3, 8)])
            return 0
        if n == 'malloc': return self.malloc(a(0))
        if n == '_Znwj' or n == '_Znaj': return self.malloc(a(0))
        if n in ('free', '_ZdlPv', '_ZdaPv'): return 0
        if n == 'memset':
            self.wr(a(0), bytes([a(1) & 255]) * a(2)); return a(0)
        if n in ('memcpy', 'memmove'):
            self.wr(a(0), self.rd(a(1), a(2))); return a(0)
        if n.startswith('pthread_') or n in ('android_atomic_inc', 'android_atomic_dec'):
            if n == 'android_atomic_inc':
                v = self.u32(a(0)); self.wr(a(0), struct.pack('<I', v + 1)); return v
            if n == 'android_atomic_dec':
                v = self.u32(a(0)); self.wr(a(0), struct.pack('<I', v - 1)); return v
            return 0
        if n == '__errno': return self.errno_addr
        if n == 'open':
            p = self.cstr(a(0))
            if p in ('/dev/v3d', '/dev/gememalloc', '/dev/cache-ops'):
                fd = self.nextfd; self.nextfd += 1; self.fds[fd] = p
                if self.log: print('  open', p, '->', fd)
                return fd
            return -1
        if n == 'close': return 0
        if n == 'ioctl': return self.ioctl(a(0), a(1), a(2))
        if n == 'mmap':
            addr, ln, prot, flags, fd, off = a(0), a(1), a(2), a(3), a(4), a(5)
            if fd in self.fds and self.fds[fd] == '/dev/gememalloc':
                if self.log: print('  mmap gememalloc off=0x%x len=0x%x' % (off, ln))
                return POOL_V + (off - POOL_P)
            return -1
        if n == 'munmap': return 0
        if n == 'property_get': return 0
        if n == '__stack_chk_fail': raise RuntimeError('stack chk fail')
        if n == 'usleep': return 0
        if n == 'gettid': return 1
        if self.log: print('  [unhandled import]', n)
        return 0
    # ---- fake devices
    def ioctl(self, fd, req, arg):
        dev = self.fds.get(fd)
        if dev == '/dev/gememalloc':
            if req == 0xc0044201:           # alloc: in {size@+4}, out phys@+0
                sz = self.u32(arg + 4)
                p = POOL_P + self.pool_ptr; self.pool_ptr += (sz + 0xfff) & ~0xfff
                self.wr(arg, struct.pack('<I', p)); return 0
            if req == 0x40044202: return 0  # free
        if dev == '/dev/cache-ops' and req == 0x6301: return 0
        if dev == '/dev/v3d':
            self.ioctls.append((req, bytes(self.rd(arg, 28 if req == 0x401c5687 else 12))))
            if req == 0x401c5687:
                self.submits.append(self.rd(arg, 28))
                if self.on_submit: self.on_submit(self, self.rd(arg, 28))
                return 0
            if req == 0xc00c5688:
                self.wr(arg + 4, struct.pack('<I', 3)); return 0
        print('  [unhandled ioctl]', dev, hex(req)); return -1
    on_submit = None
    def call(self, addr, *args, max_insn=50_000_000):
        uc = self.uc
        regs = [UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3]
        sp = STACK + STACK_SZ - 0x1000
        extra = args[4:]
        sp -= 4 * len(extra); sp &= ~7
        for i, v in enumerate(extra): self.wr(sp + 4 * i, struct.pack('<I', v & 0xffffffff))
        uc.reg_write(UC_ARM_REG_SP, sp)
        for i, v in enumerate(args[:4]): uc.reg_write(regs[i], v & 0xffffffff)
        RET = HOOK + 0xff00
        uc.reg_write(UC_ARM_REG_LR, RET)            # ARM-state return into hook space: stop there
        stop = []
        h = uc.hook_add(UC_HOOK_CODE, lambda u, a, s, d: u.emu_stop(), begin=RET, end=RET + 3)
        # thumb entry points have bit0 set
        uc.emu_start(addr, RET, count=max_insn)
        uc.hook_del(h)
        return uc.reg_read(UC_ARM_REG_R0)
