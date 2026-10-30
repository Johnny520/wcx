"""锚点迁移：8.0.79 的真目标类 → 找「8.0.80 仍被引用且引用者唯一」的该类独有串。

思路：类名是混淆的、跨版本不可迁移；但类里的**日志 TAG / 协议串**通常保留。
所以从上个版本的真目标类里提取它用过的串，筛出在新版本仍存活、且只被一个类引用的，
就是新版本可用的锚点。
"""
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, '/sandbox/workspace')
from dexlib import Dex, u32, u16, iter_methods, iter_insns

props = {p['key']: p for p in json.load(open('/sandbox/workspace/dex_props.json'))}
report = json.load(open('/sandbox/workspace/verify_report.json'))
fc = json.load(open('/sandbox/workspace/fail_classes.json'))

OLD = '/tmp/wx79'
NEW = '/tmp/wx80'


def class_strings(dirpath):
    """cls -> set(该类方法引用的串)"""
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


def string_owners(dirpath):
    """串 -> set(引用它的类)"""
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
                        out[d.string(u16(b, pp + 2))].add(cls)
                    elif op == 0x1b:
                        out[d.string(u32(b, pp + 2))].add(cls)
            except Exception:
                pass
    return out


def parse_target_cls(detail):
    """从 '1 cand | Lcom/x/y;::m' 提取类名列表"""
    if '|' not in detail:
        return []
    part = detail.split('|', 1)[1]
    out = []
    for seg in part.split(';'):
        seg = seg.strip()
        if seg.startswith('L') and '/' in seg:
            out.append(seg)
    return out


def main():
    d79 = {k: (s, t) for k, s, t in report['8.0.79']}
    d80 = {k: (s, t) for k, s, t in report['8.0.80']}
    reg = [k for k, (st, _) in d80.items()
           if st == 'FAIL' and d79.get(k, ('?',))[0] in ('OK', 'SIG', 'WARN')]
    print(f'回归项 {len(reg)}')
    print('[1/3] 索引 8.0.79 类串...')
    old_cls = class_strings(OLD)
    print('[2/3] 索引 8.0.80 串→引用者...')
    new_own = string_owners(NEW)
    print('[3/3] 生成建议...')
    out = []
    for k in reg:
        st79, det79 = d79.get(k, ('', ''))
        targets = parse_target_cls(det79)
        if not targets:
            continue
        suggest = []
        for tcls in targets:
            strs = old_cls.get(tcls, set())
            for s in strs:
                if not s or len(s) < 6:
                    continue
                owners = new_own.get(s)
                if not owners:
                    continue
                # 只保留「新版本里唯一类引用」的串，跨版本最稳
                if len(owners) == 1:
                    suggest.append((s, list(owners)[0]))
        # 去重
        seen = set()
        uniq = []
        for s, o in suggest:
            if s in seen:
                continue
            seen.add(s)
            uniq.append((s, o))
        out.append((k, targets, uniq[:4]))
        print(f'\n{k}')
        print(f'   8.0.79 目标类: {targets[:2]}')
        if uniq:
            for s, o in uniq[:4]:
                print(f'   建议串 {s!r}')
                print(f'       8.0.80 引用类(唯一): {o}')
        else:
            print('   ❌ 该类没有「新版本仍唯一引用」的串')
    json.dump([{'key': k, 'old': t, 'suggest': s} for k, t, s in out],
              open('/sandbox/workspace/migrate_plan.json', 'w'), ensure_ascii=False, indent=1)
    print(f'\n可迁移 {sum(1 for _,_,u in out if u)} / {len(out)}')


main()
