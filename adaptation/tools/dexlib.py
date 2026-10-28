import struct

WIDTH={op:1 for op in range(256)}
for _op in [0x02,0x05,0x08,0x09]+list(range(0x0a,0x13))+[0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23]+\
           list(range(0x2d,0x3e))+list(range(0x44,0x6e))+list(range(0x90,0xb0))+list(range(0xd0,0xe3))+[0xfe,0xff]:
    WIDTH[_op]=2
for _op in [0x03,0x04,0x06,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c]+\
           list(range(0x6e,0x73))+list(range(0x74,0x79))+[0xfc,0xfd]:
    WIDTH[_op]=3
for _op in [0xfa,0xfb]: WIDTH[_op]=4
WIDTH[0x18]=5

def u32(b,o): return struct.unpack_from("<I",b,o)[0]
def u16(b,o): return struct.unpack_from("<H",b,o)[0]

def uleb(b,p):
    r=0;s=0
    while True:
        x=b[p];p+=1
        r|=(x&0x7f)<<s
        if not x&0x80: return r,p
        s+=7

class Dex:
    def __init__(self,b):
        self.b=b
        self.sid_size=u32(b,0x38); self.sid_off=u32(b,0x3C)
        self.tid_off=u32(b,0x44)
        self.mid_off=u32(b,0x5C)
        self.proto_size=u32(b,0x48); self.proto_off=u32(b,0x4C)
        self.cd_size=u32(b,0x60); self.cd_off=u32(b,0x64)
    def string(self,i):
        off=u32(self.b,self.sid_off+i*4); p=off
        while self.b[p]&0x80: p+=1
        p+=1
        e=self.b.index(b"\x00",p)
        return self.b[p:e].decode("utf-8","replace")
    def type_str(self,i): return self.string(u32(self.b,self.tid_off+i*4))
    def all_strings(self):
        out=[]
        for i in range(self.sid_size):
            try: out.append(self.string(i))
            except Exception: out.append("")
        return out
    def method_name(self,i): return self.string(u32(self.b,self.mid_off+i*8+4))
    def method_class(self,i): return self.type_str(u16(self.b,self.mid_off+i*8))
    def proto_str(self,i):
        # proto_id_item: u4 shorty_idx, u4 return_type_idx, u4 parameters_off
        base=self.proto_off+i*12
        ret=self.type_str(u32(self.b,base+4))
        poff=u32(self.b,base+8)
        if poff==0: return "()"+ret
        n=u32(self.b,poff)
        # type_list: u4 size; u2 type_idx[size]
        ps=[self.type_str(u16(self.b,poff+4+j*2)) for j in range(n)]
        return "("+",".join(ps)+")"+ret
    def method_proto_idx(self, i): return u16(self.b, self.mid_off + i * 8 + 2)
    def method_ref(self,i):
        try:
            return self.method_class(i)+"->"+self.method_name(i)+self.proto_str(self.method_proto_idx(i))
        except Exception:
            return self.method_class(i)+"->"+self.method_name(i)+"(?)"

def iter_methods(b,d):
    """yield (class, method_name, method_idx, code_off)"""
    for ci in range(d.cd_size):
        base=d.cd_off+ci*32
        cls=d.type_str(u32(b,base))
        cd=u32(b,base+24)
        if cd==0: continue
        p=cd
        sf,p=uleb(b,p); inf,p=uleb(b,p); dm,p=uleb(b,p); vm,p=uleb(b,p)
        for _ in range(sf): _,p=uleb(b,p); _,p=uleb(b,p)
        for _ in range(inf): _,p=uleb(b,p); _,p=uleb(b,p)
        for cnt in (dm,vm):
            idx=0
            for _ in range(cnt):
                diff,p=uleb(b,p); acc,p=uleb(b,p); code,p=uleb(b,p)
                idx+=diff
                yield cls,d.method_name(idx),idx,code

def iter_insns(b,code):
    """遍历指令。必须正确跳过 payload 伪指令（packed/sparse-switch、fill-array-data），
    否则指令流错位会把数据当指令解释，导致 const-string 批量漏检/误检。"""
    insns=u32(b,code+12); p=code+16; end=p+insns*2
    while p<end:
        op=b[p]
        if op==0x00:
            ident=u16(b,p)
            if ident==0x0100:                      # packed-switch-payload
                size=u16(b,p+2)
                p+=8+size*4                        # ident,size + first_key + targets
                continue
            if ident==0x0200:                      # sparse-switch-payload
                size=u16(b,p+2)
                p+=4+size*8                        # ident,size + keys[] + targets[]
                continue
            if ident==0x0300:                      # fill-array-data-payload
                width=u16(b,p+2); size=u32(b,p+4)
                total=8+width*size
                if total%2: total+=1               # 4 字节对齐补齐
                p+=total
                continue
        yield p,op
        p+=WIDTH[op]
