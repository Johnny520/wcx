"""对 FAIL 委托给出「只保留存活串 + 原签名约束」后的唯一命中建议。"""
import json
import sys
from collections import defaultdict

sys.path.insert(0, '/sandbox/workspace')
from verify_props import build_targets, index_dex_dir, pkg_ok, match_signature

props = {p['key']: p for p in json.load(open('/sandbox/workspace/dex_props.json'))}
fc = json.load(open('/sandbox/workspace/fail_classes.json'))


def strip_mark(s):
    return s.split('~(contains in')[0]


def run(label, dirpath):
    rows = fc[label]
    sub = []
    allstr = set()
    for r in rows:
        p = props[r['key']]
        alive = [strip_mark(s) for s in r['alive']]
        if not alive:
            continue
        q = dict(p)
        q['eqStrings'] = []
        q['strings'] = alive
        q['_alive'] = alive
        sub.append(q)
        allstr |= set(alive)
    exact, substr = build_targets([{'eqStrings': [], 'strings': allstr}])
    print(f'[{label}] 索引 {len(substr)} 串 / {len(sub)} 个待定委托')
    m_hits, _ = index_dex_dir(dirpath, exact, substr)
    out = []
    for q in sub:
        alive = set(q['_alive'])
        cand = []
        cand_nosig = []
        for (cls, midx), (ex, sb, desc, mname) in m_hits.items():
            if not alive <= sb:
                continue
            if not pkg_ok(q, cls):
                continue
            if q['nameEq'] and mname != q['nameEq']:
                continue
            cand_nosig.append((cls, mname, desc))
            if match_signature(q, desc):
                cand.append((cls, mname, desc))
        out.append((q['key'], cand, cand_nosig, q['_alive']))
    uniq = [x for x in out if len(x[1]) == 1]
    zero = [x for x in out if not x[1] and not x[2]]
    multi = [x for x in out if len(x[1]) > 1]
    sigonly = [x for x in out if not x[1] and x[2]]
    print(f'  唯一命中(可直接修): {len(uniq)}   签名漂移: {len(sigonly)}   多命中: {len(multi)}   无命中: {len(zero)}')
    for k, c, _, alive in uniq:
        print(f'  ✅ {k}')
        print(f'        -> {c[0][0]}::{c[0][1]}{c[0][2]}')
        print(f'        锚点串={alive}')
    print('\n  --- 签名漂移 ---')
    for k, c, cn, alive in sigonly:
        print(f'  ⚠ {k}  串={alive}')
        print(f'        实际: {cn[0][0]}::{cn[0][1]}{cn[0][2]}')
    return uniq


if __name__ == '__main__':
    label = sys.argv[1]
    run(label, {'8.0.76': '/tmp/wx76', '8.0.79': '/tmp/wx79', '8.0.80': '/tmp/wx80'}[label])
