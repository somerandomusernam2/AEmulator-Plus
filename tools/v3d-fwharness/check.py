import struct
FBPAGE=480*800*2
def unpack565(c):
    r=(c>>11)&31;g=(c>>5)&63;b=c&31
    return ((r<<3)|(r>>2),(g<<2)|(g>>4),(b<<3)|(b>>2))
def compare(path, expfn, page=0, label='', show=6):
    out=open(path,'rb').read()
    pg=struct.unpack('<%dH'%(480*800),out[page*FBPAGE:(page+1)*FBPAGE])
    bad=0; maxd=0; hist={}
    for y in range(800):
        for x in range(480):
            e=expfn(x,y); g=pg[y*480+x]
            if e!=g:
                bad+=1
                a=unpack565(e); b=unpack565(g); d=max(abs(a[i]-b[i]) for i in range(3)); maxd=max(maxd,d)
                if bad<=show: print('  mismatch',x,y,hex(e),hex(g))
    print(label,'-> mismatching pixels',bad,'max channel diff',maxd)
    return bad,maxd
