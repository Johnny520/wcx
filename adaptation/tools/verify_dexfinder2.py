"""按「matcher 调用组」分析 DexFinder：每个 findClass/findMethod 调用的串组是否全部存活。

DexFinder 的 resolve 普遍带多重回退 + isXxxClass 结构校验，坏串 ≠ 功能失效，
必须逐调用组判断：只要有一组串全存活（且结构校验兜底），该方法就还能工作。
"""
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

METHOD_RE = re.compile(r'\n    (?:public|private|protected)[^\n{;]*?\b(resolve\w+|ensure\w+)\s*\([^)]*\)\s*\{')
MATCHER_CALLS = ['mkMethodUsingStringsAndName', 'mkMethodUsingStrings', 'mkClassUsingStrings',
                 'usingEqStrings']


def skip_string(s, i):
    i += 1
    while i < len(s):
        if s[i] == '\\':
            i += 2
            continue
        if s[i] == '"':
            return i + 1
        i += 1
    return i


def block(s, i):
    depth = 0
    start = i
    while i < len(s):
        c = s[i]
        if c == '"':
            i = skip_string(s, i)
            continue
        if c == "'":
            i += 2
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return start + 1, i
        i += 1
    return start + 1, len(s)


def call_args(s, i):
    depth = 0
    start = i
    while i < len(s):
        c = s[i]
        if c == '"':
            i = skip_string(s, i)
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return s[start + 1:i], i + 1
        i += 1
    return s[start + 1:], len(s)


def strings_in(text):
    out, i = [], 0
    while i < len(text):
        if text[i] == '"':
            j = skip_string(text, i)
            raw = text[i + 1:j - 1]
            if len(raw) >= 3 and '\n' not in raw:
                out.append(raw.replace('\\"', '"'))
            i = j
        else:
            i += 1
    return out


def strip_comments(s):
    s = re.sub(r'//[^\n]*', '', s)
    return re.sub(r'/\*.*?\*/', '', s, flags=re.S)


# 1. 提取每个方法的 matcher 调用组
methods = {}
for m in METHOD_RE.finditer(src):
    name = m.group(1)
    i = m.end() - 1
    bs, be = block(src, i)
    clean = strip_comments(src[bs:be])
    varmap = {}
    for vm in re.finditer(r'String\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', clean):
        varmap[vm.group(1)] = vm.group(2)
    groups = []
    for cname in MATCHER_CALLS:
        for cm in re.finditer(re.escape(cname) + r'\s*\(', clean):
            i2 = cm.end() - 1
            args, _ = call_args(clean, i2)
            ss = strings_in(args)
            for tok in re.findall(r'\b(\w+)\b', args):
                if tok in varmap:
                    ss.append(varmap[tok])
            if ss:
                groups.append((cname, ss))
    if groups:
        methods[name] = groups

# 2. 在 dex 上检查每个串的存活
targets = {s for g in methods.values() for _, ss in g for s in ss}
print(f'方法 {len(methods)}，调用组 {sum(len(g) for g in methods.values())}，特征串 {len(targets)}')
ref = set()
alls = set()
for fn in sorted(os.listdir(dp)):
    if not fn.endswith('.dex'):
        continue
    b = open(os.path.join(dp, fn), 'rb').read()
    d = Dex(b)
    for i in range(d.sid_size):
        try:
            s = d.string(i)
        except Exception:
            continue
        if s in targets:
            alls.add(s)
    for cls, mname, midx, co in iter_methods(b, d):
        if co == 0:
            continue
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
                continue          # 单条指令级容错：一条坏串不能中断整个方法
            if s in targets:
                ref.add(s)

def status(s):
    if s in ref:
        return 'OK'
    return 'GONE' if s not in alls else 'ORPHAN'

# 3. 逐方法逐组判定
report = {}
broken_groups = []
for name, groups in methods.items():
    gstates = []
    for cname, ss in groups:
        st = [status(s) for s in ss]
        alive = all(x == 'OK' for x in st)
        gstates.append({'call': cname, 'strings': ss, 'states': st, 'alive': alive})
        if not alive:
            broken_groups.append((name, cname, ss, st))
    has_alive = any(g['alive'] for g in gstates)
    report[name] = {'groups': gstates, 'has_alive_group': has_alive}

dead = [k for k, v in report.items() if not v['has_alive_group']]
partial = [k for k, v in report.items() if v['has_alive_group'] and any(not g['alive'] for g in v['groups'])]
print(f'\n全部调用组都死（功能失效）: {len(dead)}')
for k in dead:
    print(f'  ❌ {k}')
    for g in report[k]['groups']:
        print(f"      {g['call']}: {[(s[:40], st) for s, st in zip(g['strings'], g['states'])][:3]}")
print(f'\n部分组死（有回退，仍可用）: {len(partial)}')
for k in partial:
    print(f'  ⚠ {k}')
json.dump(report, open('/sandbox/workspace/dexfinder_groups.json', 'w'), ensure_ascii=False, indent=1)
