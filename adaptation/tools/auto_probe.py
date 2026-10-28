"""自动探查：对每个 FAIL 委托，尝试「存活串组合 + 原签名」，找出三版本都唯一命中的新锚点。"""
import json
import sys
from collections import defaultdict

sys.path.insert(0, '/sandbox/workspace')
from verify_props import build_targets, index_dex_dir, pkg_ok, match_signature

props = {p['key']: p for p in json.load(open('/sandbox/workspace/dex_props.json'))}
fc = json.load(open('/sandbox/workspace/fail_classes.json'))


def strip_mark(s):
    return s.split('~(contains in')[0]


def gen_candidates(key, row):
    """返回 [(label, eq, sub)] 候选组合"""
    p = props[key]
    alive = [strip_mark(s) for s in row['alive']]
    out = []
    if not alive:
        return out
    out.append(('all', alive, []))
    if len(alive) > 1:
        for s in alive:
            out.append(('one:' + s[:24], [s], []))
    return out


def main():
    labels = ['8.0.76', '8.0.79', '8.0.80']
    dirs = {'8.0.76': '/tmp/wx76', '8.0.79': '/tmp/wx79', '8.0.80': '/tmp/wx80'}
    # 以 8.0.80 的 FAIL 为主（含回归），也带上 76/79 的 FAIL
    keys = set()
    for lb in labels:
        keys |= {x['key'] for x in fc[lb]}
    rows = {}
    for lb in labels:
        for x in fc[lb]:
            rows.setdefault(x['key'], {})[lb] = x
    cands = defaultdict(list)
    for k in keys:
        row = rows[k].get('8.0.80') or list(rows[k].values())[0]
        cands[k] = gen_candidates(k, row)
    allstr = set()
    for v in cands.values():
        for _, eq, sub in v:
            allstr |= set(eq) | set(sub)
    print(f'待探查 {len(cands)} 个委托，候选串 {len(allstr)} 个')

    idx = {}
    exact, substr = build_targets([{'eqStrings': [], 'strings': list(allstr)}])
    for lb in labels:
        print(f'[{lb}] 建索引...')
        idx[lb] = index_dex_dir(dirs[lb], exact, substr)

    def query(lb, p, eq, sub):
        m_hits = idx[lb][0]
        want_eq, want_sub = set(eq), set(sub)
        cand = []
        for (cls, midx), (ex, sb, desc, mname) in m_hits.items():
            if not want_eq <= sb:
                continue
            if not want_sub <= sb:
                continue
            if not pkg_ok(p, cls):
                continue
            if p['nameEq'] and mname != p['nameEq']:
                continue
            if not match_signature(p, desc):
                continue
            cand.append((cls, mname, desc))
        return cand

    good = []
    for k, cl in cands.items():
        if not cl:
            continue
        p = props[k]
        for label, eq, sub in cl:
            hits = {}
            ok = True
            for lb in labels:
                c = query(lb, p, eq, sub)
                if len(c) != 1:
                    ok = False
                    break
                hits[lb] = c[0]
            if ok:
                good.append((k, label, eq + sub, hits))
                break
    print(f'\n=== 三版本都唯一命中：{len(good)} 项 ===')
    for k, label, strings, hits in good:
        print(f'\n{k}')
        print(f'  锚点={strings}')
        for lb in labels:
            c = hits[lb]
            print(f'    {lb}: {c[0]}::{c[1]}{c[2]}')
    json.dump([{'key': k, 'anchor': s, 'hits': {lb: list(v) for lb, v in h.items()}}
               for k, _, s, h in good],
              open('/sandbox/workspace/probe_result.json', 'w'), ensure_ascii=False, indent=1)


main()
