"""离线验证 DexKit 委托在指定 APK dex 上能否解析。

语义（按 2026-10-29 双版本实测校准）：
- usingEqStrings: 方法内引用的字符串**精确等于**其中任一，且全部都要命中（AND）
- usingStrings:   方法内引用的字符串**包含**目标串（substring），AND
- Class 匹配:     存在某个方法同时满足全部串条件（方法级 AND）；类级聚合仅作 WARN 参考
- Method/Constructor/Field: 方法级 AND + 可选签名/包过滤
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, iter_methods, iter_insns


def build_targets(props):
    exact, substr = set(), set()
    for p in props:
        exact |= set(p['eqStrings'])
        substr |= set(p['strings'])
    return exact, substr


def descriptor_for(java_type):
    prim = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
            'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}
    t = java_type.strip()
    if t in prim:
        return prim[t]
    if t.startswith('['):
        return t
    return 'L' + t.replace('.', '/') + ';'


def sig_params(desc):
    if not desc or not desc.startswith('('):
        return None
    inner = desc[1:desc.rindex(')')]
    out, i = [], 0
    while i < len(inner):
        c = inner[i]
        if c == 'L':
            j = inner.index(';', i)
            out.append(inner[i:j + 1])
            i = j + 1
        elif c == '[':
            j = i
            while inner[j] == '[':
                j += 1
            if inner[j] == 'L':
                k = inner.index(';', j)
                out.append(inner[i:k + 1])
                i = k + 1
            else:
                out.append(inner[i:j + 1])
                i = j + 1
        else:
            out.append(c)
            i += 1
    return out


def match_signature(p, desc):
    if not desc:
        return True
    ps = sig_params(desc)
    if ps is None:
        return True
    ret = desc[desc.rindex(')') + 1:]
    if p['paramCount'] is not None and len(ps) != p['paramCount']:
        return False
    if p['paramTypes']:
        want = [descriptor_for(x) for x in p['paramTypes']]
        if len(want) != len(ps):
            return False
        for a, b in zip(want, ps):
            if a != b:
                return False
    rt = p['returnType']
    if rt:
        if rt.startswith('"'):
            rt = rt.strip('"')
        mapping = {'bool': 'Z', 'boolean': 'Z', 'String': 'Ljava/lang/String;',
                   'int': 'I', 'long': 'J', 'void': 'V', 'float': 'F'}
        if rt in mapping and ret != mapping[rt]:
            return False
    return True


def pkg_ok(p, cls):
    if not p['searchPackages']:
        return True
    k = cls[1:] if cls.startswith('L') else cls
    k = k.replace('/', '.')
    return any(k.startswith(pk) for pk in p['searchPackages'])


def index_dex_dir(dirpath, exact, substr):
    sub_re = None
    if substr:
        pat = '|'.join(re.escape(s) for s in sorted(substr, key=len, reverse=True))
        sub_re = re.compile(pat)
    m_hits, c_hits = {}, {}
    t0 = time.time()
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith('.dex'):
            continue
        b = open(os.path.join(dirpath, fn), 'rb').read()
        d = Dex(b)
        idx_exact, idx_sub = {}, {}
        for i in range(d.sid_size):
            try:
                s = d.string(i)
            except Exception:
                continue
            if s in exact:
                idx_exact[i] = s
            # 注意：同一串可能既是别人的 eqStrings 目标、又是本委托的 usingStrings 目标，
            # 必须两个桶都记（早前用 elif 会漏掉包含桶，造成假 FAIL）。
            if sub_re is not None and len(s) <= 512:
                if sub_re.search(s):
                    for t in substr:
                        if t in s:
                            idx_sub.setdefault(i, set()).add(t)
        if not idx_exact and not idx_sub:
            print(f'  [{fn}] 目标串缺失，跳过 ({time.time() - t0:.1f}s)')
            continue
        for cls, mname, midx, co in iter_methods(b, d):
            if co == 0:
                continue
            used = set()
            try:
                for pp, op in iter_insns(b, co):
                    if op == 0x1a:
                        used.add(u16(b, pp + 2))
                    elif op == 0x1b:
                        used.add(u32(b, pp + 2))
            except Exception:
                pass
            if not used:
                continue
            ex = {idx_exact[i] for i in used if i in idx_exact}
            sb = set()
            for i in used:
                if i in idx_sub:
                    sb |= idx_sub[i]
            if not ex and not sb:
                continue
            try:
                desc = d.proto_str(d.method_proto_idx(midx))
            except Exception:
                desc = None
            m_hits[(cls, midx)] = (ex, sb, desc, d.method_name(midx))
            ce, cs = c_hits.get(cls, (set(), set()))
            c_hits[cls] = (ce | ex, cs | sb)
        print(f'  [{fn}] done ({time.time() - t0:.1f}s)')
    return m_hits, c_hits


def verify(props, dirpath, label):
    exact, substr = build_targets(props)
    print(f'[{label}] 目标串: exact={len(exact)} substr={len(substr)}')
    m_hits, c_hits = index_dex_dir(dirpath, exact, substr)
    print(f'[{label}] 命中方法 {len(m_hits)}, 命中类 {len(c_hits)}')
    res = []
    for p in props:
        if not p['eqStrings'] and not p['strings']:
            res.append((p['key'], 'SKIP', '无串锚点'))
            continue
        cand = []
        cand_nosig = []
        for (cls, midx), (ex, sb, desc, mname) in m_hits.items():
            if not set(p['eqStrings']) <= ex:
                continue
            if not set(p['strings']) <= sb:
                continue
            if not pkg_ok(p, cls):
                continue
            if p['nameEq'] and mname != p['nameEq']:
                continue
            cand_nosig.append((cls, mname, desc))
            if not match_signature(p, desc):
                continue
            cand.append((cls, mname, desc))
        if cand:
            res.append((p['key'], 'OK', f'{len(cand)} cand'))
            continue
        if cand_nosig:
            c = cand_nosig[0]
            res.append((p['key'], 'SIG', f'串命中但签名不符: {c[0].split("/")[-1]}.{c[1]}{c[2]}'))
            continue
        cls_ok = []
        for cls, (ce, cs) in c_hits.items():
            if not set(p['eqStrings']) <= ce:
                continue
            if not set(p['strings']) <= cs:
                continue
            if not pkg_ok(p, cls):
                continue
            cls_ok.append(cls)
        if cls_ok:
            res.append((p['key'], 'WARN', f'类级命中但无单方法满足: {cls_ok[:2]}'))
        else:
            res.append((p['key'], 'FAIL', '无任何候选'))
    return res


if __name__ == '__main__':
    props = json.load(open('/sandbox/workspace/dex_props.json'))
    out = {}
    pairs = [('/tmp/wx76', '8.0.76'), ('/tmp/wx79', '8.0.79'), ('/tmp/wx80', '8.0.80')]
    if len(sys.argv) > 1:
        pairs = [p for p in pairs if p[1] in sys.argv[1:]]
    for dirpath, label in pairs:
        out[label] = verify(props, dirpath, label)
        n = {}
        for _, st, _ in out[label]:
            n[st] = n.get(st, 0) + 1
        print(f'[{label}] 结果统计: {n}')
    json.dump(out, open('/sandbox/workspace/verify_report.json', 'w'),
              ensure_ascii=False, indent=1)
    print('报告已写入 verify_report.json')
