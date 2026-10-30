import sys, os
sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, iter_methods, iter_insns


def method_string_idx(b, code):
    """返回方法内引用的 string_ids 索引集合"""
    out = set()
    try:
        for p, op in iter_insns(b, code):
            if op == 0x1a:
                out.add(u16(b, p + 2))
            elif op == 0x1b:
                out.add(u32(b, p + 2))
    except Exception:
        pass
    return out


def scan_dir(dirpath, targets, pkg_filter=None, limit=40, show_others=0):
    tset = set(targets)
    hits = []
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith('.dex'):
            continue
        b = open(os.path.join(dirpath, fn), 'rb').read()
        d = Dex(b)
        idxmap = {}
        for i in range(d.sid_size):
            try:
                s = d.string(i)
            except Exception:
                continue
            if s in tset:
                idxmap[i] = s
        if len(idxmap) < len(tset):
            continue
        for cls, mname, midx, codeoff in iter_methods(b, d):
            if codeoff == 0:
                continue
            if pkg_filter:
                key = cls[1:] if cls.startswith('L') else cls
                if not key.startswith(pkg_filter):
                    continue
            used = method_string_idx(b, codeoff)
            if not used:
                continue
            got = {idxmap[i] for i in used if i in idxmap}
            if got == tset:
                sig = d.method_ref(midx)
                hits.append((fn, cls, sig))
                if len(hits) >= limit:
                    return hits
    return hits


if __name__ == '__main__':
    d = sys.argv[1]
    targets = sys.argv[2].split('|')
    pkg = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != '-' else None
    res = scan_dir(d, targets, pkg)
    print(f"targets={targets} pkg={pkg} -> {len(res)} hit(s)")
    for fn, cls, sig in res:
        print(f"  [{fn}] {sig}")
