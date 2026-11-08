import sys; import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfemu import *; from cldump import *
def setup(log=False):
    sf=SF(log=log)
    fb=sf.malloc(0x100)
    for o,v in ((0x44,480),(0x48,800),(0x50,4),(0x68,FB_P),(0x6c,FB_V)): sf.wr(fb+o,struct.pack('<I',v))
    sf.obj=sf.call(sf.syms['_ZN7android11V3D_Compose6CreateEPK20framebuffer_device_t'],fb)
    return sf
def V(p): return POOL_V+(p-POOL_P)
def galloc(sf,size):
    p=POOL_P+sf.pool_ptr; sf.pool_ptr+=(size+0xfff)&~0xfff; return p
def vt(sf,idx):  # vtable slot -> function
    return sf.u32(sf.u32(sf.obj)+4*idx)
def fl(x): return struct.unpack('<I',struct.pack('<f',x))[0]
def dumpcl(sf,s,e,maxn=40):
    for i,x in enumerate(walk(lambda a,n: sf.rd(V(a),n),s,e)):
        if i<maxn: print('   ',fmt(x))
