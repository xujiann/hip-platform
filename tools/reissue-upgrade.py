# -*- coding: utf-8 -*-
"""v3 偏离表「上调」补丁：只改目标行的 响应结论/响应说明，其余每一行字节级不变。

用法：python tools/reissue-upgrade.py <rewrite_waveN.json> [--apply]
与 tools/reissue-assemble.py（装配）、tools/reissue-qa.py（四维校验）同一套工具链：
上调走「新一波 audit_r2_waveN.json（判定改为符合）+ rewrite_waveN.json（needs_human=true）→ reissue-qa.py 零问题 → 本脚本 --apply」。
已自检：用无变化波真 --apply 后 git diff 为空（2026-09-08）。
  不带 --apply 只做 dry-run：打印将改哪些行、并跑完整性断言，不写文件。

纪律（与 2026-09-01 重出时同一套）：
  · 输入 UTF-8 带 BOM、**CRLF** 换行（实测 4063 个 CRLF、零裸 CR。此前 grep -c $'\r$' 报 0 是
    Git Bash 在 Windows 上吃掉 $'\r$' 的假象，主控曾据此误判为 LF）—— 写回必须保住 BOM 与 CRLF；
  · 非目标行必须**逐字节**与原文件相同（不是「内容相同」）——csv 重新序列化会改引号策略，
    所以不用 csv.writer 写整表，而是按原始行文本逐行替换；
  · 表头与总行数不变；目标行的 序号/★/▲/参数摘要 四列不动，只换后两列；
  · 目标行的 new_note 须已过 tools/reissue-qa.py 的 A/B 维（本脚本再跑一遍同款正则兜底）。
"""
import csv, io, json, re, sys

V3 = 'docs/验收/技术偏离表-v3.csv'
BOM = '﻿'

# 与 tools/reissue-qa.py 同款（对外文本禁用内部词与交付承诺）
INTERNAL = re.compile(
    r'(\.java\b|\.vue\b|\.sql\b|\.tsx?\b'
    r'|\b[A-Z][A-Za-z]*(?:Controller|Repository|Service|Adapter)\s*[.:]\s*[A-Za-z_]\w*'
    r'|\b[A-Z][A-Za-z]*(?:Controller|Repository)\b'
    r'|\bV\d{1,3}__|\bV\d{1,3}:\d+'
    r'|grep|零命中|file:line|全仓(?:库)?(?:无|零|仅|只)|代码(?:里|中)(?:无|不存在|查不到)'
    r'|\b[a-z]+_[a-z]+_[a-z]+\b'
    r'|审计判定|判定为(?:不符合|部分符合|符合)|本条判定)', re.I)
COMMIT = re.compile(
    r'((?:\d+\s*(?:个)?(?:工作日|天|周|月)|下一?版本|近期|年底|季度)\s*(?:内|前)?\s*'
    r'(?:完成|交付|上线|发布)'
    r'|承诺(?:于|在)?\s*\d+\s*(?:个)?(?:工作日|天|周|月)|承诺(?:于|在)\s*下一?版本'
    r'|\bv\d+\.\d+\b|版本号)', re.I)


def csv_field(s: str) -> str:
    """按原表习惯给字段加引号：含逗号/引号/换行才加，引号内双写。"""
    if any(c in s for c in ',"\n\r'):
        return '"' + s.replace('"', '""') + '"'
    return s


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    wave = json.load(io.open(sys.argv[1], encoding='utf-8'))
    apply = '--apply' in sys.argv
    targets = {}
    for x in wave:
        no = str(x['no']).strip()
        nc, nn = x['new_conclusion'].strip(), x['new_note'].strip()
        m = INTERNAL.search(nn) or COMMIT.search(nn)
        assert not m, f'{no} 的新说明含内部词/越权承诺：{m.group(0)!r}'
        assert len(nn) >= 18, f'{no} 新说明过短'
        assert x.get('needs_human') is True, f'{no} 上调 ★ 必须 needs_human=true 进签认'
        targets[no] = (nc, nn)

    raw = io.open(V3, encoding='utf-8', newline='').read()
    assert raw.startswith(BOM), 'v3 应以 BOM 开头'
    assert raw.count('\r') == raw.count('\r\n'), 'v3 里出现了不成对的裸 CR'
    assert raw.endswith('\r\n'), 'v3 应以 CRLF 结尾'
    body = raw[len(BOM):]
    lines = body.split('\r\n')
    assert lines[-1] == '', 'v3 应以 CRLF 结尾'
    header = lines[0]
    assert header == '序号,实质性★,重要▲,参数摘要,响应结论,响应说明', f'表头变了：{header}'

    # 逐行：用 csv 解析取序号与列，但**保留原始行文本**用于非目标行的原样写回
    out, changed = [header], []
    for ln in lines[1:-1]:
        row = next(csv.reader([ln]))
        no = row[0].strip() if row else ''
        if no in targets:
            assert len(row) == 6, f'{no} 列数异常：{len(row)}'
            nc, nn = targets[no]
            new_ln = ','.join(csv_field(c) for c in row[:4]) + ',' + csv_field(nc) + ',' + csv_field(nn)
            # 自检：重新解析后前四列逐字不变、后两列等于目标
            back = next(csv.reader([new_ln]))
            assert back[:4] == row[:4] and back[4] == nc and back[5] == nn, f'{no} 重序列化自检失败'
            out.append(new_ln); changed.append((no, row[4], nc))
        else:
            out.append(ln)   # 非目标行：原始文本原样，不经 csv 重写
    out.append('')
    new_body = '\r\n'.join(out)

    # 完整性断言：行数不变；非目标行逐字节相同
    assert len(out) == len(lines), '总行数变了'
    for a, b in zip(lines, out):
        na = (next(csv.reader([a]))[0].strip() if a else '')
        if na not in targets:
            assert a == b, f'非目标行被改动：{na}'
    missing = [n for n in targets if n not in {c[0] for c in changed}]
    assert not missing, f'目标行在 v3 里找不到：{missing}'

    print(f'将改 {len(changed)} 行：')
    for no, old, new in changed:
        print(f'  {no}: {old} → {new}')
    if apply:
        io.open(V3, 'w', encoding='utf-8', newline='').write(BOM + new_body)
        print('已写回 v3（BOM+CRLF 保持，非目标行逐字节不变）')
    else:
        print('dry-run，未写文件（加 --apply 才写）')


if __name__ == '__main__':
    main()
