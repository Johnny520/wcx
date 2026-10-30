"""对 FAIL 的委托做根因分类：
A = 所有串都在 dex 中被某方法引用，但没有单方法共现（串分散）
B = 有串在 dex 里完全不存在
C = 孤儿串（存在于 string_ids 但无任何 const-string 引用）
输出 JSON 供后续批量修复。
"""
import json
import os
import sys
from collections import Counter

sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, iter_methods, iter_insns

props = {p['key']: p for p in json.load(open('/sandbox/workspace/dex_props.json'))}
report = json.load(open('/sandbox/workspace/verify_report.json'))


def scan_refs(dirpath):
    ref, alls, owner = set(), set(), {}
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith('.dex'):
            continue
        b = open(os.path.join(dirpath, fn), 'rb').read()
        d = Dex(b)
        for i in range(d.sid_size):
            try:
                alls.add(d.string(i))
            except Exception:
                pass
        for cls, mname, midx, co in iter_methods(b, d):
            if co == 0:
                continue
            try:
                for pp, op in iter_insns(b, co):
                    if op == 0x1a:
                        idx = u16(b, pp + 2)
                    elif op == 0x1b:
                        idx = u32(b, pp + 2)
                    else:
                        continue
                    s = d.string(idx)
                    ref.add(s)
                    owner.setdefault(s, set()).add(cls)
            except Exception:
                pass
    return ref, alls, owner


def main():
    out = {}
    for dirpath, label in [('/tmp/wx76', '8.0.76'), ('/tmp/wx79', '8.0.79'), ('/tmp/wx80', '8.0.80')]:
        print(f'[{label}] 扫描引用表...')
        ref, alls, owner = scan_refs(dirpath)
        print(f'[{label}] 被引用串 {len(ref)}, 全部串 {len(alls)}')
        rows = []
        for key, st, detail in report[label]:
            if st != 'FAIL':
                continue
            p = props[key]
            targets = list(p['eqStrings']) + list(p['strings'])
            absent, orphan, alive = [], [], []
            for t in targets:
                if t in ref:
                    alive.append(t)
                elif t in alls:
                    orphan.append(t)
                else:
                    cnt = sum(1 for s in ref if t in s)
                    if cnt:
                        alive.append(t + f'~(contains in {cnt})')
                    else:
                        cnt2 = sum(1 for s in alls if t in s)
                        (orphan if cnt2 else absent).append(t)
            if not absent and not orphan:
                kind = 'A_分散'
            elif absent:
                kind = 'B_串消失'
            else:
                kind = 'C_孤儿串'
            rows.append({'key': key, 'kind': kind, 'absent': absent,
                         'orphan': orphan, 'alive': alive,
                         'file': p['file'], 'dt': p['kind'],
                         'pkg': p['searchPackages'],
                         'dc': bool(p['declaredClass']),
                         'pc': p['paramCount'], 'rt': p['returnType'],
                         'pte': p['paramTypes']})
        out[label] = rows
        print(f'[{label}] FAIL 分类: {Counter(r["kind"] for r in rows)}')
    json.dump(out, open('/sandbox/workspace/fail_classes.json', 'w'),
              ensure_ascii=False, indent=1)


main()
