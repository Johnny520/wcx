"""精确提取 DexFinder 中真正传给 matcher 的特征串（排除日志输出串）。"""
import io
import json
import re

PATH = '/sandbox/workspace/_audit/h/Hchat/dexkit/DexFinder.java'
src = io.open(PATH, encoding='utf-8').read()

METHOD_RE = re.compile(r'\n    (?:public|private|protected)[^\n{;]*?\b(resolve\w+|ensure\w+)\s*\([^)]*\)\s*\{')
MATCHER_CALLS = ['mkMethodUsingStrings', 'mkClassUsingStrings', 'mkMethodUsingStringsAndName',
                 'usingEqStrings', 'addUsingString', 'usingStrings']


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
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return s


def method_matchers(body):
    """返回 [(kind, strings)]，只含真正传给 matcher 的串"""
    out = []
    clean = strip_comments(body)
    # 变量赋值 map：String anchor = "..."
    varmap = {}
    for m in re.finditer(r'String\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', clean):
        varmap[m.group(1)] = m.group(2)
    for name in MATCHER_CALLS:
        for m in re.finditer(re.escape(name) + r'\s*\(', clean):
            i = m.end() - 1
            args, _ = call_args(clean, i)
            ss = strings_in(args)
            # 变量引用
            for tok in re.findall(r'\b(\w+)\b', args):
                if tok in varmap:
                    ss.append(varmap[tok])
            if ss:
                out.append((name, ss))
    return out


defs = {}
for m in METHOD_RE.finditer(src):
    name = m.group(1)
    i = m.end() - 1
    bs, be = block(src, i)
    body = src[bs:be]
    calls = method_matchers(body)
    if calls:
        lst = []
        for kind, ss in calls:
            for s in ss:
                if s not in lst:
                    lst.append(s)
        if lst:
            defs.setdefault(name, [])
            for s in lst:
                if s not in defs[name]:
                    defs[name].append(s)

json.dump(defs, open('/sandbox/workspace/dexfinder_props.json', 'w'), ensure_ascii=False, indent=1)
tot = sum(len(v) for v in defs.values())
print(f'resolve 方法 {len(defs)} 个，matcher 特征串 {tot} 个，去重 {len({s for v in defs.values() for s in v})}')
for k in list(defs)[:4]:
    print(f'  {k}: {defs[k][:3]}')
