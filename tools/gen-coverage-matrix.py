# -*- coding: utf-8 -*-
"""从采购需求 markdown 提取全部编号技术参数，生成验收覆盖矩阵 CSV。
用法：python tools/gen-coverage-matrix.py <采购需求.md路径> [输出csv]
输出列：序号,实质性(★),重要(▲),参数摘要,覆盖状态,对应功能/说明
覆盖状态默认「待评估」，由项目组逐条评定为：符合 / 部分符合 / 未覆盖。
"""
import csv
import re
import sys

src = sys.argv[1] if len(sys.argv) > 1 else '采购需求.md'
dst = sys.argv[2] if len(sys.argv) > 2 else 'docs/验收/参数覆盖矩阵.csv'

text = open(src, encoding='utf-8').read()
# 匹配形如 “123. 参数内容”（pandoc 有序列表），跨行取到下一条编号或段落边界
pattern = re.compile(r'(?m)^\|?[\s|]*(\d{1,4})\\?\.\s+(.+?)(?=(?:^\|?[\s|]*\d{1,4}\\?\.\s)|\Z)', re.S)

rows = []
seen = set()
for m in pattern.finditer(text):
    no = int(m.group(1))
    if no in seen or no < 1 or no > 4400:
        continue
    seen.add(no)
    body = re.sub(r'[|\s]+', ' ', m.group(2)).strip()
    star = '★' if '★' in body else ''
    tri = '▲' if '▲' in body else ''
    summary = body.replace('★', '').replace('▲', '').strip()[:200]
    rows.append([no, star, tri, summary, '待评估', ''])

rows.sort(key=lambda r: r[0])

import os
os.makedirs(os.path.dirname(dst), exist_ok=True)
with open(dst, 'w', newline='', encoding='utf-8-sig') as f:
    w = csv.writer(f)
    w.writerow(['序号', '实质性★', '重要▲', '参数摘要', '覆盖状态', '对应功能/说明'])
    w.writerows(rows)

print(f'提取参数 {len(rows)} 条 → {dst}')
print(f'其中 ★ {sum(1 for r in rows if r[1])} 条，▲ {sum(1 for r in rows if r[2])} 条')
