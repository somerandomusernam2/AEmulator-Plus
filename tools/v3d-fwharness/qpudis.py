import struct,sys
ADD={0:'nop',1:'fadd',2:'fsub',3:'fmin',4:'fmax',5:'fminabs',6:'fmaxabs',7:'ftoi',8:'itof',12:'add',13:'sub',14:'shr',15:'asr',16:'ror',17:'shl',18:'min',19:'max',20:'and',21:'or',22:'xor',23:'not',24:'clz',29:'v8adds',30:'v8subs'}
MUL={0:'nop',1:'fmul',2:'mul24',3:'v8muls',4:'v8min',5:'v8max',6:'v8adds',7:'v8subs'}
CC=['never','always','zs','zc','ns','nc','cs','cc']
WA={32:'r0',33:'r1',34:'r2',35:'r3',36:'tmu_noswap',37:'r5q',38:'host_int',39:'nop',40:'unif_addr',41:'quad_x|y',42:'ms_flags|rev',43:'tlb_stencil',44:'tlb_z',45:'tlb_color_ms',46:'tlb_color_all',47:'tlb_alpha_mask',48:'vpm',49:'vpmvcd',50:'vpm_addr',51:'mutex_rel',52:'sfu_recip',53:'sfu_rsq',54:'sfu_exp',55:'sfu_log',56:'tmu0_s',57:'tmu0_t',58:'tmu0_r',59:'tmu0_b',60:'tmu1_s',61:'tmu1_t',62:'tmu1_r',63:'tmu1_b'}
RA={32:'UNIF',35:'VARY',36:'mutex_acq',38:'elem|qpu',39:'nop',41:'xy_pix',42:'ms|rev',48:'VPM'}
RB={32:'UNIF',35:'VARY',36:'mutex_acq',38:'elem|qpu',39:'nop',41:'xy_pix',42:'ms|rev',48:'VPM'}
PACK_A={0:'',1:'16a',2:'16b',3:'8888',4:'8a',5:'8b',6:'8c',7:'8d',8:'32s',9:'16a.s',10:'16b.s',11:'8888.s',12:'8a.s',13:'8b.s',14:'8c.s',15:'8d.s'}
UNPACK_A={0:'',1:'16a',2:'16b',3:'8d.rep',4:'8a',5:'8b',6:'8c',7:'8d'}
def wname(w,ws,file_a):
    if w<32: return ('rb%d' if (file_a==ws) else 'ra%d')%w  # ws=0: add writes regfile A
    return WA.get(w,'w%d'%w)
SMALL={i:str(i) for i in range(16)}
for i in range(16,32): SMALL[i]=str(i-32)
for i in range(32,40): SMALL[i]=str(2.0**(i-32)) if False else '%g'%(2.0**(i-32))
for i in range(40,48): SMALL[i]='%g'%(2.0**(i-48))
for i in range(48,56): SMALL[i]='ror%d'%(i-48)  # approx
SMALL[48]='<<r5?'
def mux(m,ra,rb,sig):
    if m<4: return 'r%d'%m
    if m==4: return 'r4'
    if m==5: return 'r5'
    if m==6: return ('ra%d'%ra) if ra<32 else RA.get(ra,'ra%d'%ra)
    if sig==13: return 'imm(%s)'%SMALL.get(rb,'?%d'%rb)
    return ('rb%d'%rb) if rb<32 else RB.get(rb,'rb%d'%rb)
def dis(b):
    lo,hi=struct.unpack('<II',b)
    sig=hi>>28
    if sig==14:
        w=(hi>>... if False else 0)
    unpack=(hi>>25)&7; pm=(hi>>24)&1; pack=(hi>>20)&15; cadd=(hi>>17)&7; cmul=(hi>>14)&7; sf=(hi>>13)&1; ws=(hi>>12)&1
    wadd=(hi>>6)&63; wmul=hi&63
    if sig==14:
        imm=lo
        return 'ldi  %s=%s, %s=%s  imm=0x%08x (%g)%s'%(wn(wadd,ws,0),'',wn(wmul,ws,1),'',imm,struct.unpack('<f',struct.pack('<I',imm))[0],' sf' if sf else '')
    opm=(lo>>29)&7; opa=(lo>>24)&31; ra=(lo>>18)&63; rb=(lo>>12)&63
    am=(lo>>9)&7; bm=(lo>>6)&7; mam=(lo>>3)&7; mbm=lo&7
    sn={0:'break',1:'',2:'thrsw',3:'THREND',4:'wait_sb',5:'unlock_sb',6:'lthrsw',7:'cov',8:'colload',9:'colload+END',10:'tmu0load',11:'tmu1load',12:'alphamask',13:'smallimm'}[sig] if sig!=15 else 'BRANCH'
    if sig==15: return 'branch cond=%d  raw=%08x %08x'%((hi>>20)&15,lo,hi)
    # ADD pipe: writes to regfile A if ws==0 else B; MUL opposite
    dst_add=wn(wadd,ws,0); dst_mul=wn(wmul,ws,1)
    a=mux(am,ra,rb,sig); bb=mux(bm,ra,rb,sig); ma=mux(mam,ra,rb,sig); mb=mux(mbm,ra,rb,sig)
    s=''
    if opa==0 and wadd==39: s+='add:nop '
    else:
        un=''
        s+='%s%s %s, %s, %s [%s] '%(ADD.get(opa,'op%d'%opa),'.'+CC[cadd] if cadd!=1 else '',dst_add,a,bb,'sf' if sf else '')
    if opm==0 and wmul==39: s+='| mul:nop '
    else:
        s+='| %s%s %s, %s, %s '%(MUL.get(opm,'mop%d'%opm),'.'+CC[cmul] if cmul!=1 else '',dst_mul,ma,mb)
    ex=[]
    if ra<32 or True: pass
    if pack: ex.append('pack=%s%s'%(('a:' if not pm else 'mul:'),PACK_A.get(pack,str(pack))))
    if unpack: ex.append('unpack%s=%s'%('(r4)' if pm else '(a)',UNPACK_A.get(unpack,str(unpack))))
    return (sn+' ' if sn else '')+s+(' ; '+' '.join(ex) if ex else '')+('  [ra=%d rb=%d]'%(ra,rb))
def wn(w,ws,mulpipe):
    # add pipe: ws=0 -> regfile A ; mul pipe: ws=0 -> regfile B
    if w<32:
        fileB = (ws==1) if not mulpipe else (ws==0)
        return ('rb%d' if fileB else 'ra%d')%w
    return WA.get(w,'w%d'%w)
if __name__=='__main__':
    d=open(sys.argv[1],'rb').read()
    for i in range(0,len(d)-7,8):
        print('%03x: %s'%(i,dis(d[i:i+8])))
