"""放宽版锚点迁移：从 8.0.79 真目标类提串 → 在 8.0.80 里用原签名筛选，允许引用者 ≤3。"""
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, '/sandbox/workspace')
from verify_props import index_dex_dir, pkg_ok, match_signature, build_targets
from dexlib import Dex, u32, u16, iter_methods, iter_insns

props = {p['key']: p for p in json.load(open('/sandbox/workspace/dex_props.json'))}
report = json.load(open('/sandbox/workspace/verify_report.json'))


def class_strings(dirpath):
    out = defaultdict(set)
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith('.dex'):
            continue
        b = open(os.path.join(dirpath, fn), 'rb').read()
        d = Dex(b)
        for cls, mname, midx, co in iter_methods(b, d):
            if co == 0:
                continue
            try:
                for pp, op in iter_insns(b, co):
                    if op == 0x1a:
                        out[cls].add(d.string(u16(b, pp + 2)))
                    elif op == 0x1b:
                        out[cls].add(d.string(u32(b, pp + 2)))
            except Exception:
                pass
    return out


def parse_target(det):
    import re
    if '|' not in det:
        return []
    return re.findall(r'L[^;:\s]+;', det)


def main():
    d79 = {k: (s, t) for k, s, t in report['8.0.79']}
    d80 = {k: (s, t) for k, s, t in report['8.0.80']}
    reg = [k for k, (st, _) in d80.items()
           if st in ('FAIL', 'SIG', 'WARN') and d79.get(k, ('?',))[0] == 'OK']
    print(f'回归项 {len(reg)}')
    print('索引 79 类串...')
    old = class_strings('/tmp/wx79')
    cand_str = set()
    targets = {}
    for k in reg:
        tc = parse_target(d79[k][1])
        if not tc:
            continue
        targets[k] = tc
        for t in tc:
            cand_str |= old.get(t, set())
    cand_str = {s for s in cand_str if s and 6 <= len(s) <= 200}
    print(f'候选串 {len(cand_str)}')
    exact, substr = build_targets([{'eqStrings': [], 'strings': list(cand_str)}])
    print('索引 8.0.80...')
    m80, _, _ = index_dex_dir('/tmp/wx80', exact, substr)
    print('筛选...')
    found = []
    for k, tcs in targets.items():
        p = props[k]
        want = set()
        for t in tcs:
            want |= old.get(t, set())
        best = []
        for s in sorted(want):
            if not s or len(s) < 6:
                continue
            hits = []
            for (cls, midx), (ex, sb, desc, mname) in m80.items():
                if s not in sb:
                    continue
                if not pkg_ok(p, cls):
                    continue
                if p['nameEq'] and mname != p['nameEq']:
                    continue
                if not match_signature(p, desc):
                    continue
                hits.append((cls, mname, desc))
            if len(hits) == 1:
                best.append((s, hits[0]))
        if best:
            found.append((k, best[:3]))
    print(f'\n=== 有唯一解：{len(found)} 项 ===')
    for k, b in found:
        print(f'\n{k}')
        for s, h in b:
            print(f'   锚点={s!r}  ->  8.0.80: {h[0]}::{h[1]}{h[2]}')
    json.dump([{'key': k, 'cands': [{'anchor': s, 'target': '/'.join(h)} for s, h in b]}
               for k, b in found],
              open('/sandbox/workspace/migrate2_result.json', 'w'), ensure_ascii=False, indent=1)


main()
