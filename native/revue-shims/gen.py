import subprocess,sys
def funcs(path):
    out=subprocess.run(['readelf','-Ws','--dyn-syms',path],capture_output=True,text=True).stdout.splitlines()
    r=[]
    for l in out:
        p=l.split()
        if len(p)>=8 and p[6]!='UND' and p[3]=='FUNC':
            n=p[7].split('@')[0]
            if n in('_init','_fini','call_gmon_start','frame_dummy') or n.startswith('__'): continue
            if n not in r: r.append(n)
    return r
lib=sys.argv[1]
hdr='/* AEmulator Plus 2026-10-27: stand-in for Intel CE4100 %s (Logitech Revue): no ISMD hardware here, every call reports success (0). Freestanding i386. */\n'%lib
s=hdr
for n in funcs('/home/claude/w/rev/lib/%s.so'%lib): s+='int %s(void){ return 0; }\n'%n
open('%s_stub.c'%lib,'w').write(s)
