# -*- coding: utf-8 -*-
"""偏离表重出 · 改写质量机械校验（四维，不含人工判断）。

用法：python tools/reissue-qa.py

维度：
  A 内部词      —— 对外说明不得出现 file:line / 类名.方法 / 迁移号 / 蛇形表名 / 审计口吻
  B 越权承诺    —— 不得出现交付时限与版本号（「列入后续开发计划」是允许的，「90 天内完成」不是）
  C 档位一致    —— new_conclusion 必须与审计判定相容；「不符合→正偏离」仅限法规冲突澄清且须标人
  D ★降级必标人 —— ★ 实质性条款一旦不再是「正偏离/无偏离」，必须 needs_human=true

正则经三轮实测校准（合法 12 / 内部 10 / 承诺 4，零误伤零漏检）。曾踩的三个坑：
  裸 `Service` 误伤 WebService；裸 `审计` 误伤「审计日志/审计员/审计网专线」；
  裸 `N 天内` 误伤「证照到期前 90 天预警」这类产品功能。
"""
import json, io, os, re, glob, sys, collections
sys.stdout.reconfigure(encoding='utf-8')
SCR = r'C:\Users\drxuj\AppData\Local\Temp\claude\C--Users-drxuj------\3a2f452a-4a0f-4bd5-aad4-9c88087196f0\scratchpad'
# 对外文本扫描规则。经三轮实测校准（合法 12 / 内部 10 / 承诺 4，零误伤零漏检）：
#  - 不用裸类名后缀：会误伤 WebService；不用裸「审计」：审计日志/审计员是真实产品功能；
#  - 不用裸「N 天内」：证照到期前 90 天预警是产品功能，不是交付承诺；承诺须带时间量词。
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

ALLOWED = {
 '符合': {'正偏离/无偏离'},
 '部分符合': {'部分响应'},
 '不符合': {'部分响应', '正偏离/无偏离'},   # 正偏离仅限法规冲突澄清（1341 类），且须 needs_human
 '属外部边界/配套产品': {'配套产品响应', '外部条件'},
 '非软件功能条款': {'部分响应', '正偏离/无偏离'},
}
au = {}
for x in json.load(io.open(os.path.join(SCR,'audit_r1.json'),encoding='utf-8'))['all']: au[x['no']] = x
for w in range(1,9):
    p = os.path.join(SCR,'audit_r2_wave%d.json'%w)
    if os.path.exists(p):
        for x in json.load(io.open(p,encoding='utf-8')): au[x['no']] = x
star = set()
import csv
for r in list(csv.reader(io.open('docs/验收/技术偏离表-v2.csv',encoding='utf-8-sig')))[1:]:
    if r and r[0].strip().isdigit() and len(r)>1 and r[1].strip(): star.add(int(r[0]))

files = sorted(glob.glob(os.path.join(SCR,'rewrite_wave*.json')))
if not files: print('尚无改写结果'); sys.exit(0)
tot = 0; fail = collections.Counter(); bad_rows = collections.defaultdict(list)
for p in files:
    for x in json.load(io.open(p,encoding='utf-8')):
        tot += 1; no = x['no']; v = au.get(no,{}).get('verdict'); nc = x['new_conclusion']; nn = x['new_note']
        if INTERNAL.search(nn): fail['A内部词'] += 1; bad_rows['A内部词'].append((no, INTERNAL.search(nn).group(0)))
        if COMMIT.search(nn): fail['B越权承诺'] += 1; bad_rows['B越权承诺'].append((no, COMMIT.search(nn).group(0)))
        if v and nc not in ALLOWED.get(v, set()): fail['C档位'] += 1; bad_rows['C档位'].append((no, f'{v}→{nc}'))
        if v == '不符合' and nc == '正偏离/无偏离' and not x['needs_human']:
            fail['C档位(不符合升正偏离未标人)'] += 1; bad_rows['C档位(不符合升正偏离未标人)'].append((no, nc))
        if no in star and nc != '正偏离/无偏离' and not x['needs_human']:
            fail['D★降级未标人'] += 1; bad_rows['D★降级未标人'].append((no, nc))
        if len(nn) < 18: fail['E说明过短'] += 1; bad_rows['E说明过短'].append((no, nn))
print('已改写 %d 条' % tot)
for k, n in fail.most_common(): print('  %-28s %5d' % (k, n))
if not fail: print('  四维机械校验：零问题')
for k, rows in bad_rows.items():
    print('\n[%s] 样例（最多 8 条）:' % k)
    for no, s in rows[:8]: print('   %d  %s' % (no, str(s)[:90]))
