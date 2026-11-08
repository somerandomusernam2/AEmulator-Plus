import sys,struct
from elftools.elf.elffile import ELFFile
from capstone import *
def load(path):
    f=open(path,'rb'); e=ELFFile(f)
    segs=[(s['p_vaddr'],s['p_filesz'],s.data()) for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    syms={}
    for secname in ('.dynsym','.symtab'):
        sec=e.get_section_by_name(secname)
        if sec:
            for s in sec.iter_symbols():
                if s['st_value']: syms[s.name]=(s['st_value'],s['st_size'])
    # plt/relocs: map got addr -> name
    rel={}
    for secname in ('.rel.plt','.rel.dyn'):
        sec=e.get_section_by_name(secname)
        if sec:
            ds=e.get_section_by_name('.dynsym')
            for r in sec.iter_relocations():
                if r['r_info_sym']:
                    rel[r['r_offset']]=ds.get_symbol(r['r_info_sym']).name
    return e,segs,syms,rel
def rd(segs,a,n):
    for v,sz,d in segs:
        if v<=a<v+sz: return d[a-v:a-v+n]
    return b''
def cstr(segs,a):
    b=rd(segs,a,200)
    i=b.find(b'\0'); 
    return b[:i] if i>=0 else b[:60]
def dis(path,start,end,thumb=True):
    e,segs,syms,rel=load(path)
    md=Cs(CS_ARCH_ARM, CS_MODE_THUMB if thumb else CS_MODE_ARM); md.detail=False
    data=rd(segs,start,end-start)
    rsym={a&~1:n for n,(a,s) in syms.items()}
    for i in md.disasm(data,start):
        extra=''
        if i.mnemonic=='ldr' and 'pc' in i.op_str and '[' in i.op_str:
            try:
                off=int(i.op_str.split('#')[1].rstrip(']'),16)
                a=((i.address+4)&~3)+off
                v=struct.unpack('<I',rd(segs,a,4))[0]
                extra=' ; [%x]=%08x'%(a,v)
                # pc-relative add later; show raw
            except Exception as ex: pass
        if i.mnemonic in('bl','blx','b') and i.op_str.startswith('#'):
            t=int(i.op_str[1:],16)
            extra=' ; '+rsym.get(t,rsym.get(t&~1,''))
        print("%x: %s %s%s"%(i.address,i.mnemonic,i.op_str,extra))
if __name__=='__main__':
    dis(sys.argv[1],int(sys.argv[2],16),int(sys.argv[3],16))
