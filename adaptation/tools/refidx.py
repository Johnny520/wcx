"""构建「字符串 -> 引用方法」倒排索引（供锚点诊断复用）。"""
import sys, os, pickle
sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, uleb, iter_methods, iter_insns


def build(D='/tmp/wx80', CACHE='/tmp/refidx.pkl'):
    if os.path.exists(CACHE):
        return pickle.load(open(CACHE, 'rb'))
    idx = {}
    for fn in sorted(os.listdir(D)):
        b = open(os.path.join(D, fn), 'rb').read()
        d = Dex(b)
        for cls, mn, mi, co in iter_methods(b, d):
            if not co:
                continue
            try:
                ins = list(iter_insns(b, co))
            except Exception:
                continue
            for p, op in ins:
                try:
                    if op == 0x1a:
                        s = d.string(u16(b, p + 2))
                    elif op == 0x1b:
                        s = d.string(u32(b, p + 2))
                    else:
                        continue
                except Exception:
                    continue
                idx.setdefault(s, set()).add(f'{cls}::{mn}')
    pickle.dump(idx, open(CACHE, 'wb'))
    return idx


def cls_counter(idx, s, sub=False):
    from collections import Counter
    ms = set()
    if sub:
        for k, v in idx.items():
            if s in k:
                ms |= v
    else:
        ms = idx.get(s, set())
    return Counter(m.split('::')[0] for m in ms), ms
