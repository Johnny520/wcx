"""对 DexFinder 失效组找替代串：组内 OK 串定位方法 → 该方法里其它存活串 → 作为替换候选。"""
import io
import json
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, iter_methods, iter_insns

PATH = '/sandbox/workspace/_audit/h/Hchat/dexkit/DexFinder.java'
src = io.open(PATH, encoding='utf-8').read()
dp = sys.argv[1] if len(sys.argv) > 1 else '/tmp/wx79'
rep = json.load(open('/sandbox/workspace/dexfinder_groups.json'))

# 收集失效组里仍然 OK 的串（定位锚）
locate = {}
for k, v in rep.items():
    for g in v['groups']:
        if g['alive']:
            continue
        ok_strs = [s for s, st in zip(g['strings'], g['states']) if st == 'OK']
        if ok_strs:
            locate.setdefault(k, set()).update(ok_strs)
targets = set()
for v in locate.values():
    targets |= v
print(f'失效方法 {len(locate)}，定位串 {len(targets)}')

# 找 8.0.79 里引用定位串的方法 + 这些方法引用的全部串
ref_by_method = defaultdict(set)
owner = defaultdict(set)
for fn in sorted(os.listdir(dp)):
    if not fn.endswith('.dex'):
        continue
    b = open(os.path.join(dp, fn), 'rb').read()
    d = Dex(b)
    for cls, mname, midx, co in iter_methods(b, d):
        if co == 0:
            continue
        ss = set()
        for pp, op in iter_insns(b, co):
            if op == 0x1a:
                idx = u16(b, pp + 2)
            elif op == 0x1b:
                idx = u32(b, pp + 2)
            else:
                continue
            try:
                s = d.string(idx)
            except Exception:
                continue
            ss.add(s)
        if not ss:
            continue
        hit = ss & targets
        if hit:
            key = f'{cls}::{d.method_name(midx)}'
            ref_by_method[key] = ss
            for s in hit:
                owner[s].add(key)

print('\n=== 替代建议（每个失效方法列出「定位串所在方法」里的其它存活串）===')
for k, ok_strs in locate.items():
    methods_hit = defaultdict(int)
    for s in ok_strs:
        for m in owner.get(s, ()):
            methods_hit[m] += 1
    best = sorted(methods_hit.items(), key=lambda x: -x[1])[:2]
    print(f'\n{k}')
    for m, n in best:
        alive_strs = sorted(ref_by_method.get(m, set()), key=len, reverse=True)[:8]
        print(f'   目标方法(推测): {m[:80]}')
        for s in alive_strs:
            print(f'        {s!r}'[:100])
