# -*- coding: utf-8 -*-
"""矩阵终评导出：参数覆盖矩阵 → 技术偏离表（投标响应底稿）。
四栏：响应（初评符合）/ 配套产品响应 / 外部条件 / 待人工复核。
用法：python tools/export-deviation.py
"""
import csv
import collections
import sys

sys.stdout.reconfigure(encoding='utf-8')

SRC = 'docs/验收/参数覆盖矩阵.csv'
DST = 'docs/验收/技术偏离表.csv'

VERDICT_MAP = {
    '初评符合': ('正偏离/无偏离', '平台已实现（详见对应功能模块）'),
    '属配套产品参数': ('配套产品响应', '属设备配套软件/商用知识库/CA 等产品参数，平台已留标准接入点'),
    '涉外部条件': ('外部条件', '需医保局/省平台等外部环境开通后联调'),
    '待人工评估': ('待复核', '初评未匹配，须项目组逐条人工确认'),
}

with open(SRC, encoding='utf-8-sig') as f:
    rows = list(csv.DictReader(f))

stats = collections.Counter(r['初评'] for r in rows)
with open(DST, 'w', newline='', encoding='utf-8-sig') as f:
    w = csv.writer(f)
    w.writerow(['序号', '实质性★', '重要▲', '参数摘要', '响应结论', '响应说明'])
    for r in rows:
        concl, note = VERDICT_MAP.get(r['初评'], ('待复核', ''))
        w.writerow([r['序号'], r['实质性★'], r['重要▲'], r['参数摘要'], concl, note])

total = len(rows)
print(f'技术偏离表已导出：{DST}（{total} 条）')
for k, v in stats.most_common():
    concl = VERDICT_MAP.get(k, ('?',))[0]
    print(f'  {concl}: {v} ({v * 100 // total}%)')
print('注意：终版须项目组人工复核「待复核」项并对★条款逐条签认。')
