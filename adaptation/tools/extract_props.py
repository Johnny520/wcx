"""从 WCX 源码提取全部 DexKit 委托特征，输出 JSON 供离线验证。

支持形态：
  val|private val NAME by dexClass|dexMethod|dexField|dexConstructor[(opts)] { ... }
块内提取 searchPackages / usingEqStrings / usingStrings / paramTypes / paramCount /
returnType / declaredClass / name = / modifiers 等。
串列表用括号平衡扫描（串里可能含 "(" 或 ")"）。
"""
import io, json, os, re, sys

ROOT = '/sandbox/workspace/_audit'

DECL_RE = re.compile(
    r'(?:private\s+|internal\s+|public\s+)?val\s+(\w+)\s+by\s+dex(Class|Method|Field|Constructor)\b'
)


def skip_string(src, i):
    """i 指向开引号，返回结尾引号后一位"""
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
    """i 指向 '{'，返回 (body, end_index)"""
    assert src[i] == '{', src[i:i + 20]
    depth = 0
    start = i
    while i < len(src):
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == "'":
            i = skip_string(src, i).__int__() if False else i + 1
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[start + 1:i], i + 1
        i += 1
    return src[start + 1:], len(src)


def call_args(src, i):
    """i 指向 '('，返回 (args_text, end_index)"""
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
    out = []
    i = 0
    while i < len(text):
        if text[i] == '"':
            j = skip_string(text, i)
            raw = text[i + 1:j - 1]
            out.append(raw.replace('\\"', '"').replace('\\\\', '\\'))
            i = j
        else:
            i += 1
    return out


def collect_calls(body, fname):
    """返回 [(callname, args_text, extra:{} )...]，只收顶层（含 matcher 内一层）"""
    found = []
    i = 0
    names = ['searchPackages', 'usingEqStrings', 'usingStrings', 'paramTypes',
             'paramCount', 'returnType', 'declaredClass', 'modifiers',
             'name', 'paramCountMin', 'paramCountMax']
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
        m = None
        for n in names:
            if body.startswith(n, i):
                after = i + len(n)
                # 边界
                if after < len(body) and (body[after].isalnum() or body[after] == '_'):
                    continue
                # 跳过空白，看是否 '(' 或 '='
                j = after
                while j < len(body) and body[j] in ' \t\n':
                    j += 1
                if j < len(body) and body[j] == '(':
                    m = (n, j)
                    break
                if j < len(body) and body[j] == '=' and not body[j:j + 2] == '==':
                    k = j + 1
                    while k < len(body) and body[k] in ' \t\n':
                        k += 1
                    if k < len(body) and body[k] == '"':
                        e = skip_string(body, k)
                        found.append((n, body[k:e], True))
                        i = e
                        break
                    # 裸值（true/false/数字/常量）
                    e = k
                    while e < len(body) and body[e] not in '\n,)}':
                        e += 1
                    found.append((n, body[k:e].strip(), True))
                    i = e
                    break
        if m:
            n, j = m
            args, end = call_args(body, j)
            found.append((n, args, False))
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
        # 跳过 opt 括号，找块起始 '{'
        while i < len(src) and src[i] in ' \t\n':
            i += 1
        if i < len(src) and src[i] == '(':
            _, i = call_args(src, i)
            while i < len(src) and src[i] in ' \t\n':
                i += 1
        if i >= len(src) or src[i] != '{':
            continue
        body, _ = block_body(src, i)
        calls = collect_calls(body, path)
        d = {'name': name, 'kind': kind, 'file': os.path.relpath(path, ROOT),
             'eqStrings': [], 'strings': [], 'searchPackages': [],
             'paramTypes': [], 'paramCount': None, 'returnType': None,
             'declaredClass': None, 'nameEq': None, 'modifiers': None}
        for n, args, is_assign in calls:
            if n == 'usingEqStrings':
                d['eqStrings'] += strings_in(args)
            elif n == 'usingStrings':
                d['strings'] += strings_in(args)
            elif n == 'searchPackages':
                d['searchPackages'] += strings_in(args)
            elif n == 'paramTypes':
                d['paramTypes'] += [x.strip().strip('"') for x in strings_in(args)]
            elif n == 'paramCount':
                try:
                    d['paramCount'] = int(args.strip())
                except Exception:
                    pass
            elif n == 'returnType':
                d['returnType'] = args.strip().strip('"')
            elif n == 'declaredClass':
                d['declaredClass'] = args.strip()
            elif n == 'name':
                d['nameEq'] = args.strip().strip('"')
        out.append(d)
    return out


def main():
    all_items = []
    for dirpath, _, files in os.walk(ROOT):
        for f in files:
            if not f.endswith('.kt'):
                continue
            p = os.path.join(dirpath, f)
            try:
                all_items += parse_file(p)
            except Exception as e:
                print('ERR', p, e, file=sys.stderr)
    # key = 文件末段 + 属性名，便于对照日志
    for it in all_items:
        obj = os.path.basename(it['file']).replace('.kt', '')
        it['key'] = f"{obj}:{it['name']}"
    json.dump(all_items, open('/sandbox/workspace/dex_props.json', 'w'),
              ensure_ascii=False, indent=1)
    print('提取委托:', len(all_items))
    kinds = {}
    for it in all_items:
        kinds[it['kind']] = kinds.get(it['kind'], 0) + 1
    print('类型分布:', kinds)
    empty = [it for it in all_items if not it['eqStrings'] and not it['strings']]
    print('无串锚点(需靠签名/declaredClass):', len(empty))


main()
