import sys; import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from casegen import *; from check import *
import os
def lsb_diff(e,g):
    a=((e>>11)&31,(e>>5)&63,e&31); b=((g>>11)&31,(g>>5)&63,g&31)
    return max(abs(a[i]-b[i]) for i in range(3))
def check(path,expfn,label,tol,page=0):
    out=open(path,'rb').read(); pg=struct.unpack('<%dH'%(480*800),out[page*FBPAGE:(page+1)*FBPAGE])
    bad=0;mx=0
    for y in range(800):
        for x in range(480):
            d=lsb_diff(expfn(x,y),pg[y*480+x]); mx=max(mx,d)
            if d>tol: bad+=1
    print('%-34s max LSB diff %d (tolerance %d) -> %s'%(label,mx,tol,'PASS' if bad==0 else 'FAIL %d px'%bad))
    return bad==0
def scenario(fmt, alpha, rect, W=64,H=32, bg=None, orient=0, seed=3):
    random.seed(seed)
    S=Session(); sf=S.sf
    bpp=2 if fmt in (4,6,7) else 4
    src=S.buf(W*H*bpp); ge=S.buf(W*H*bpp)
    raw=bytes(random.randrange(256) for _ in range(W*H*bpp))
    if fmt==4: raw=struct.pack('<%dH'%(W*H),*[random.randrange(65536) for _ in range(W*H)])
    sf.wr(V(src),raw)
    sf.call(vt(sf,0),sf.obj,W*bpp//2,H,ge,src,fmt,0,0,0,0)
    r=sf.malloc(16); sf.wr(r,struct.pack('<4i',*rect))
    sf.call(vt(sf,2),sf.obj,fmt,ge,W,H,fl(1.0),fl(1.0),orient,r,0,alpha)
    sf.call(vt(sf,1),sf.obj,0)
    fb=bytearray(4*FBPAGE)
    page=struct.pack('<%dH'%(480*800),*([bg]*(480*800))) if bg is not None else bytes(FBPAGE)
    fb[0:FBPAGE]=page; fb[FBPAGE:2*FBPAGE]=page
    make_case(sf,S.snaps,S.prot,bytes(fb),'/tmp/c.bin')
    run('/tmp/c.bin','/tmp/o.bin')
    return raw
ok=True
# 1: alpha 200 565 over a gray background page 0 (job renders list C = clear, so background comes from the clear!) -> see below
raw=scenario(4,200,(10,20,74,52))
pix=struct.unpack('<2048H',raw)
def e1(x,y):
    if not(10<=x<74 and 20<=y<52): return 0
    r,g,b=unpack565(pix[(y-20)*64+(x-10)])
    f=lambda c:(c*200+127)//255
    return rgb565(f(r),f(g),f(b))
ok&=check('/tmp/o.bin',e1,'565 const alpha 200 over black',2)
# 2: RGBA8888 source, opaque
raw=scenario(1,255,(10,20,74,52))
def e2(x,y):
    if not(10<=x<74 and 20<=y<52): return 0
    o=((y-20)*64+(x-10))*4; r,g,b=raw[o],raw[o+1],raw[o+2]
    return rgb565(r,g,b)
ok&=check('/tmp/o.bin',e2,'RGBA8888 opaque (R,G,B byte order)',2)
def e2b(x,y):
    if not(10<=x<74 and 20<=y<52): return 0
    o=((y-20)*64+(x-10))*4; r,g,b=raw[o],raw[o+1],raw[o+2]
    return rgb565(b,g,r)


os.environ['V3D_MAX_DRAWS']='1'
raw=scenario(4,255,(10,20,74,52)); pix=struct.unpack('<2048H',raw)
ok&=check('/tmp/o.bin',lambda x,y: pix[(y-20)*64+(x-10)] if (10<=x<74 and 20<=y<52) else 0,'565 opaque, compose draw only (exact)',0)
raw=scenario(1,255,(10,20,74,52))
ok&=check('/tmp/o.bin',e2,'8888 opaque, compose draw only (exact)',0)
# rect that straddles several 32x32 tiles at an odd offset, 1:1
raw=scenario(4,255,(37,45,101,77)); pix=struct.unpack('<2048H',raw)
ok&=check('/tmp/o.bin',lambda x,y: pix[(y-45)*64+(x-37)] if (37<=x<101 and 45<=y<77) else 0,'565 opaque at odd offset (exact)',0)
# 2x upscale, bilinear: compare to python bilinear within 1 field LSB
raw=scenario(4,255,(100,100,228,164)); pix=struct.unpack('<2048H',raw)
def bil(x,y):
    if not(100<=x<228 and 100<=y<164): return 0
    u=(x+0.5-100)/2-0.5; v=(y+0.5-100)/2-0.5
    x0=int(u//1); y0=int(v//1); fx=u-x0; fy=v-y0
    def t(xx,yy):
        xx=min(max(xx,0),63); yy=min(max(yy,0),31); return unpack565(pix[yy*64+xx])
    c=[]
    for i in range(3):
        a=t(x0,y0)[i]*(1-fx)+t(x0+1,y0)[i]*fx; b=t(x0,y0+1)[i]*(1-fx)+t(x0+1,y0+1)[i]*fx
        c.append(int(a*(1-fy)+b*fy+0.5))
    return rgb565(*c)
ok&=check('/tmp/o.bin',bil,'565 2x bilinear upscale (exact-ish)',1)
print('ALL PASS' if ok else 'SOME FAILED')
sys.exit(0 if ok else 1)
