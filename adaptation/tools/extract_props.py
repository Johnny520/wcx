"""修正版提取器：区分「方法体内串」与「declaredClass/superClass 块内的类级串」。

DexKit 语义：
- matcher 顶层的 usingStrings/usingEqStrings → 方法体内引用的字符串（方法级 AND）
- declaredClass { usingStrings(...) } / superClass { usingStrings(...) } → 对**类**的约束
  （该类 / 其父类的方法里引用这些串），不能当方法级条件用。
"""
import io
import json
import os
import re
import sys

ROOT = '/sandbox/workspace/_audit'
DECL_RE = re.compile(
    r'(?:private\s+|internal\s+|public\s+)?val\s+(\w+)\s+by\s+dex(Class|Method|Field|Constructor)\b'
)
CLASS_BLOCK_RE = re.compile(r'\b(declaredClass|superClass|addInterface|className)\b')


def skip_string(src, i):
    i += 1
    while i < len(src):
        if src[i] == '\\':
            i += 2
            continue
        if src[i] == '"':
            return i + 1
        i += 1
    return i


def block_body(src, i):
    assert src[i] == '{'
    depth = 0
    start = i
    while i < len(src):
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return start + 1, i
        i += 1
    return start + 1, len(src)


def call_args(src, i):
    depth = 0
    start = i
    while i < len(src):
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return src[start + 1:i], i + 1
        i += 1
    return src[start + 1:], len(src)


def strings_in(text):
    out, i = [], 0
    while i < len(text):
        if text[i] == '"':
            j = skip_string(text, i)
            out.append(text[i + 1:j - 1])
            i = j
        else:
            i += 1
    return out


def find_class_scopes(body):
    """返回 [(start, end, kind)]：declaredClass/superClass 块的范围，这些块内的串是类级约束"""
    scopes = []
    for m in CLASS_BLOCK_RE.finditer(body):
        j = m.end()
        while j < len(body) and body[j] in ' \t\n':
            j += 1
        if j < len(body) and body[j] == '{':
            bs, be = block_body(body, j)
            scopes.append((m.start(), be + 1, m.group(1)))
    return scopes


def collect_calls(body):
    names = ['searchPackages', 'usingEqStrings', 'usingStrings', 'paramTypes',
             'paramCount', 'returnType', 'modifiers', 'name']
    found = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == '"':
            i = skip_string(body, i)
            continue
        if c == '/':
            if body[i:i + 2] == '//':
                i = body.find('\n', i)
                if i < 0:
                    break
                continue
            if body[i:i + 2] == '/*':
                i = body.find('*/', i)
                i = len(body) if i < 0 else i + 2
                continue
        hit = None
        for n in names:
            if body.startswith(n, i) and (i == 0 or not (body[i - 1].isalnum() or body[i - 1] == '_')):
                after = i + len(n)
                if after < len(body) and (body[after].isalnum() or body[after] == '_'):
                    continue
                j = after
                while j < len(body) and body[j] in ' \t\n':
                    j += 1
                if j < len(body) and body[j] == '(':
                    hit = (n, j, 'call')
                    break
                if j < len(body) and body[j] == '=' and body[j:j + 2] != '==':
                    k = j + 1
                    while k < len(body) and body[k] in ' \t\n':
                        k += 1
                    if k < len(body) and body[k] == '"':
                        e = skip_string(body, k)
                        found.append((n, body[k:e], k))
                        i = e
                        hit = ('', 0, 'done')
                        break
        if hit and hit[2] == 'call':
            n, j, _ = hit
            args, end = call_args(body, j)
            found.append((n, args, i))
            i = end
            continue
        i += 1
    return found


def parse_file(path):
    src = io.open(path, encoding='utf-8').read()
    out = []
    for m in DECL_RE.finditer(src):
        name, kind = m.group(1), m.group(2)
        i = m.end()
        while i < len(src) and src[i] in ' \t\n':
            i += 1
        if i < len(src) and src[i] == '(':
            _, i = call_args(src, i)
            while i < len(src) and src[i] in ' \t\n':
                i += 1
        if i >= len(src) or src[i] != '{':
            continue
        bs, be = block_body(src, i)
        body = src[bs:be]
        scopes = find_class_scopes(body)
        d = {'name': name, 'kind': kind, 'file': os.path.relpath(path, ROOT),
             'eqStrings': [], 'strings': [], 'searchPackages': [],
             'paramTypes': [], 'paramCount': None, 'returnType': None,
             'declaredClass': None, 'nameEq': None, 'modifiers': None,
             'classStrings': [], 'orGroups': [], 'hasInvoke': False}
        if re.search(r'\baddInvoke\s*\{', body):
            d['hasInvoke'] = True
        for n, args, pos in collect_calls(body):
            in_scope = any(s <= pos < e for s, e, _ in scopes)
            if n in ('usingEqStrings', 'usingStrings'):
                (d['classStrings'] if in_scope else
                 (d['eqStrings'] if n == 'usingEqStrings' else d['strings'])).extend(strings_in(args))
            elif n == 'searchPackages':
                d['searchPackages'] += [x.replace('${PackageNames.WECHAT}', 'com.tencent.mm')
                                         .replace('${PackageNames.MODULE}', 'com.Johnny.wcx')
                                        for x in strings_in(args)]
            elif n == 'paramTypes':
                d['paramTypes'] += [x.strip().strip('"') for x in strings_in(args)]
            elif n == 'paramCount':
                try:
                    d['paramCount'] = int(args.strip())
                except Exception:
                    pass
            elif n == 'returnType':
                d['returnType'] = args.strip().strip('"')
            elif n == 'name':
                d['nameEq'] = args.strip().strip('"')
            elif n == 'modifiers':
                d['modifiers'] = args.strip()
        # addMethod { ... } 之间是 OR 语义（类中存在任一满足条件的方法即可）
        for mm in re.finditer(r'\baddMethod\s*\{', body):
            try:
                bs2, be2 = block_body(body, body.index('{', mm.start()))
            except Exception:
                continue
            blk = body[bs2:be2]
            g_eq, g_sub = [], []
            for n, args, _pos in collect_calls(blk):
                if n == 'usingEqStrings':
                    g_eq += strings_in(args)
                elif n == 'usingStrings':
                    g_sub += strings_in(args)
            if g_eq or g_sub:
                nested = bool(re.search(r'\b(returnType|paramTypes|paramCount|declaredClass|superClass|addInterface)\b', blk))
                d['orGroups'].append({'eq': g_eq, 'sub': g_sub, 'nested': nested})
        # Kotlin 的 `${'$'}name` / `${"$"}name` 是字面量 $ 的转义写法，dex 里存的是真正的 $，
        # 不还原会让这一类锚点永远匹配不上（8.0.80 朋友圈菜单 3 项即因此被判 FAIL）。
        for _k in ('eqStrings', 'strings', 'classStrings'):
            d[_k] = [x.replace("${'$'}", '$').replace('${"$"}', '$') for x in d[_k]]
        out.append(d)
    return out


def main():
    items = []
    for dirpath, _, files in os.walk(ROOT):
        for f in files:
            if not f.endswith('.kt'):
                continue
            try:
                items += parse_file(os.path.join(dirpath, f))
            except Exception as e:
                print('ERR', f, e, file=sys.stderr)
    for it in items:
        obj = os.path.basename(it['file']).replace('.kt', '')
        it['key'] = f"{obj}:{it['name']}"
    json.dump(items, open('/sandbox/workspace/dex_props.json', 'w'), ensure_ascii=False, indent=1)
    print('委托:', len(items), '带类级串:', sum(1 for x in items if x['classStrings']),
          '无串:', sum(1 for x in items if not x['eqStrings'] and not x['strings']))


main()
