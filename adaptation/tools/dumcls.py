import sys, os
sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, iter_methods, iter_insns


def find_dex(dirpath, cls):
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith('.dex'):
            continue
        b = open(os.path.join(dirpath, fn), 'rb').read()
        d = Dex(b)
        for ci in range(d.cd_size):
            off = u32(b, d.cd_off + ci * 32)
            if d.type_str(off) == cls:
                return fn, b, d
    return None, None, None


def dump(dirpath, cls, str_filter=None):
    fn, b, d = find_dex(dirpath, cls)
    if not b:
        print('class not found:', cls)
        return
    print(f'=== {cls} in {fn}')
    rows = []
    for c, mname, midx, co in iter_methods(b, d):
        if c != cls:
            continue
        try:
            sig = d.method_ref(midx)
        except Exception:
            sig = mname + '(?)'
        strs = []
        if co:
            try:
                for p, op in iter_insns(b, co):
                    if op == 0x1a:
                        strs.append(d.string(u16(b, p + 2)))
                    elif op == 0x1b:
                        strs.append(d.string(u32(b, p + 2)))
            except Exception:
                pass
        rows.append((sig, strs))
    for sig, strs in rows:
        show = [s for s in strs if (not str_filter or any(f in s for f in str_filter))]
        print(f'\n{sig}')
        if show:
            for s in show[:12]:
                print('     ', repr(s[:90]))
        elif strs:
            print(f'      ({len(strs)} strs, filtered)')
    print(f'\n-- total {len(rows)} methods')


if __name__ == '__main__':
    dp = sys.argv[1]
    cls = sys.argv[2]
    flt = sys.argv[3].split('|') if len(sys.argv) > 3 else None
    dump(dp, cls, flt)
