import sys, struct, subprocess, random
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drive import *
FBPAGE=480*800*2
def make_case(sf, snaps, prot, fb_init, path, fb_len=4*FBPAGE):
    out=bytearray()
    pool_len=sf.pool_ptr+0x1000
    out+=struct.pack('<5I',POOL_P,pool_len,FB_P,fb_len,len(prot))
    for a,l in prot: out+=struct.pack('<2I',a,l)
    out+=struct.pack('<I',len(snaps))
    out+=fb_init.ljust(fb_len,b'\0')
    for job,pool in snaps:
        out+=struct.pack('<7I',*job)+struct.pack('<I',len(pool))+pool
    open(path,'wb').write(out)
def run(path, outfb):
    r=subprocess.run([__import__('os').environ.get('V3D_REPLAY', 'replay'),path,outfb],capture_output=True,text=True)
    print(r.stderr.strip()); return r.returncode
def rgb565(r,g,b): return ((r>>3)<<11)|((g>>2)<<5)|(b>>3)
def unpack565(c):
    r=(c>>11)&31;g=(c>>5)&63;b=c&31
    return ((r<<3)|(r>>2),(g<<2)|(g>>4),(b<<3)|(b>>2))
class Session:
    def __init__(self):
        self.sf=setup(); self.snaps=[]
        def cb(sf,b):
            job=struct.unpack('<7I',b)
            self.snaps.append((job, sf.rd(POOL_V+0x1000, sf.pool_ptr-0x1000+0x1000-0x1000+0)[:] if False else sf.rd(POOL_V, sf.pool_ptr)))
        self.sf.on_submit=cb
        self.prot=[]
    def buf(self,size):
        p=galloc(self.sf,size); self.prot.append((p,(size+0xfff)&~0xfff)); return p
