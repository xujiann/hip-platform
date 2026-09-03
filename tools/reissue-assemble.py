# -*- coding: utf-8 -*-
"""偏离表重出装配：合并各波改写结果 → 技术偏离表-v3.csv + 签认清单。

用法：python tools/reissue-assemble.py
输入：scratchpad/rewrite_wave*.json（各波 agent 输出：no/new_conclusion/new_note/needs_human/human_reason）
输出：
  docs/验收/技术偏离表-v3.csv        —— 逐条改写后的正式表（列结构与 v2 完全一致，可直接替换）
  docs/验收/偏离表重出-签认清单.md    —— 按域分组的 needs_human 条目 + 全表变更统计，供项目负责人逐条签认
  docs/验收/偏离表重出-变更对照.csv   —— 每条：序号/★/原结论/新结论/原说明/新说明，审阅用

纪律：
  - 内部词（file:line / grep / 零命中 / 类名 / 表名）在对外说明里零容忍，装配时扫描并列出违规行
  - ★ 条款降级、法规冲突澄清、非软件条款、审计存疑 —— 一律进签认清单，不得静默过
"""
import csv, io, json, os, re, collections, glob

SCR = os.environ.get('HIP_AUDIT_DIR') or (
    r'C:\Users\drxuj\AppData\Local\Temp\claude\C--Users-drxuj------'
    r'\3a2f452a-4a0f-4bd5-aad4-9c88087196f0\scratchpad')

# 对外文本内部词扫描。不含裸 ":\d+"——那会误伤 "00:00" 这类时间；file:line 由扩展名子句与
# "V25:43" 迁移引用子句覆盖，裸类名由 Controller/Service/Repository 覆盖。
INTERNAL = re.compile(r'(\.java\b|\.vue\b|\.sql\b|\.ts\b|\bV\d{1,3}:\d+|grep|零命中|file:line|Controller|Service|'
                      r'Repository|\b[a-z]+_[a-z]+_[a-z]+\b|V\d{2,3}__|\bagent\b|审计判定|判定为)', re.I)

def load_rewrites():
    rw = {}
    for p in sorted(glob.glob(os.path.join(SCR, 'rewrite_wave*.json'))):
        for x in json.load(io.open(p, encoding='utf-8')):
            rw[x['no']] = x
    return rw

def load_audit():
    v = {}
    p1 = os.path.join(SCR, 'audit_r1.json')
    if os.path.exists(p1):
        for x in json.load(io.open(p1, encoding='utf-8'))['all']: v[x['no']] = x
    for w in range(1, 9):
        p = os.path.join(SCR, 'audit_r2_wave%d.json' % w)
        if os.path.exists(p):
            for x in json.load(io.open(p, encoding='utf-8')): v[x['no']] = x
    return v

def clean(t):
    t = re.sub(r'<!--.*?-->', '', t); t = re.sub(r'[+\-]{6,}', '', t); return ' '.join(t.split()).strip()

def main():
    rw = load_rewrites(); au = load_audit()
    rows = list(csv.reader(io.open('docs/验收/技术偏离表-v2.csv', encoding='utf-8-sig')))
    head, body = rows[0], rows[1:]
    claimed = [int(r[0]) for r in body if len(r) > 4 and '正偏离' in r[4] and r[0].strip().isdigit()]
    missing = [n for n in claimed if n not in rw]
    print('答已实现 %d 条，已改写 %d 条，未改写 %d 条' % (len(claimed), len(claimed) - len(missing), len(missing)))
    if missing:
        print('  未改写序号（前 30）:', missing[:30])

    out = [head]; diff = [['序号', '★', '▲', '参数摘要', '原结论', '新结论', '原说明', '新说明', '需签认', '签认原因']]
    violations = []; changed = collections.Counter(); sign = []
    for r in body:
        r = list(r)
        no = int(r[0]) if r[0].strip().isdigit() else None
        x = rw.get(no)
        if x and len(r) > 5 and '正偏离' in r[4]:
            oc, on = r[4], r[5]
            r[4], r[5] = x['new_conclusion'], x['new_note']
            changed[(oc, r[4])] += 1
            if INTERNAL.search(x['new_note']):
                violations.append((no, x['new_note'][:120]))
            diff.append([no, '★' if r[1].strip() else '', '▲' if r[2].strip() else '', clean(r[3])[:80],
                         oc, r[4], on[:80], x['new_note'], '是' if x['needs_human'] else '', x['human_reason']])
            if x['needs_human']:
                sign.append({'no': no, 'star': bool(r[1].strip()), 'param': clean(r[3])[:90],
                             'from': oc, 'to': r[4], 'note': x['new_note'], 'why': x['human_reason'],
                             'verdict': au.get(no, {}).get('verdict', '')})
        out.append(r)

    with io.open('docs/验收/技术偏离表-v3.csv', 'w', encoding='utf-8-sig', newline='') as f:
        csv.writer(f).writerows(out)
    with io.open('docs/验收/偏离表重出-变更对照.csv', 'w', encoding='utf-8-sig', newline='') as f:
        csv.writer(f).writerows(diff)

    L = []; w = L.append
    w('# 技术偏离表重出 · 签认清单'); w('')
    w('> 依据：`docs/验收/应答核对-全量报告.md`（3510 条逐条核对）。本清单列出**必须由项目负责人逐条签认**的条目。')
    w('> 其余条目为机器按审计判定改写，已通过对外语言扫描；建议抽查，但不阻塞。'); w('')
    w('## 一、全表变更统计'); w('')
    w('| 原结论 → 新结论 | 条数 |'); w('|---|---:|')
    for (a, b), n in sorted(changed.items(), key=lambda kv: -kv[1]):
        w('| %s → %s | %d |' % (a, b, n))
    w(''); w('对外语言违规扫描：**%d** 条（含内部词，须人工改写，见文末）' % len(violations)); w('')
    w('## 二、须签认条目（%d 条）' % len(sign)); w('')
    w('签认原因分四类：★ 实质性条款降级 / 参数与法规或设计冲突（宜澄清而非降级）/ 非软件条款证据缺失 / 审计判定存疑。'); w('')
    star_sign = [s for s in sign if s['star']]
    w('### 2.1 ★ 实质性条款降级（%d 条）—— 每一条都可能影响评标结论' % len(star_sign)); w('')
    w('| 序号 | 参数 | 原→新 | 新说明 | 原因 |'); w('|---|---|---|---|---|')
    for s in star_sign:
        w('| %d | %s | %s → %s | %s | %s |' % (s['no'], s['param'].replace('|', '/'), s['from'], s['to'],
                                              s['note'][:140].replace('|', '/'), s['why'][:80].replace('|', '/')))
    other = [s for s in sign if not s['star']]
    w(''); w('### 2.2 其他须签认（%d 条）' % len(other)); w('')
    w('| 序号 | 参数 | 原→新 | 新说明 | 原因 |'); w('|---|---|---|---|---|')
    for s in other:
        w('| %d | %s | %s → %s | %s | %s |' % (s['no'], s['param'].replace('|', '/'), s['from'], s['to'],
                                              s['note'][:140].replace('|', '/'), s['why'][:80].replace('|', '/')))
    w(''); w('## 三、对外语言违规（%d 条，须人工改写后方可对外）' % len(violations)); w('')
    for no, t in violations:
        w('- **%d**：%s' % (no, t.replace('|', '/')))
    w(''); w('## 四、纪律'); w('')
    w('- 本表**不含任何交付时限与版本承诺**，「列入后续开发计划」的具体承诺由商务决定后另行补入。')
    w('- 签认完成前，v3 不得对外；v2 不得继续使用。')
    io.open('docs/验收/偏离表重出-签认清单.md', 'w', encoding='utf-8', newline='\n').write('\n'.join(L))
    print('已生成 技术偏离表-v3.csv / 偏离表重出-变更对照.csv / 偏离表重出-签认清单.md')
    print('须签认 %d 条（★ %d）；对外语言违规 %d 条' % (len(sign), len(star_sign), len(violations)))

main()
