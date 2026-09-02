# -*- coding: utf-8 -*-
"""投标应答核对结果汇总：合并各轮判定 → 统计 + 生成偏离表重出草案。

用法：python tools/audit-rollup.py [--reissue]
  不带参数：只出统计
  --reissue：额外生成 docs/验收/技术偏离表-v3草案.csv（按判定改写响应结论与说明）

重出规则（与 v29/v41/v42 同一条诚信纪律）：
  符合              → 维持「正偏离/无偏离」
  部分符合          → 改「部分响应」，说明里写明缺哪几点（不得含糊）
  不符合            → 改「部分响应」并注明后续版本，或「不响应」
  属外部边界/配套产品 → 改「配套产品响应」/「外部条件」
  非软件功能条款     → 改「正偏离/无偏离」但说明改为承诺函/测试报告类证据
"""
import csv, io, json, os, sys, collections

SCR = os.environ.get('HIP_AUDIT_DIR') or (
    r'C:\Users\drxuj\AppData\Local\Temp\claude\C--Users-drxuj------'
    r'\3a2f452a-4a0f-4bd5-aad4-9c88087196f0\scratchpad')

def load():
    v = {}
    p1 = os.path.join(SCR, 'audit_r1.json')
    if os.path.exists(p1):
        for x in json.load(io.open(p1, encoding='utf-8'))['all']:
            v[x['no']] = x
    for w in range(1, 9):
        p = os.path.join(SCR, 'audit_r2_wave%d.json' % w)
        if os.path.exists(p):
            for x in json.load(io.open(p, encoding='utf-8')):
                v[x['no']] = x
    return v

REMAP = {
    '符合': ('正偏离/无偏离', None),
    '部分符合': ('部分响应', '核心能力已实现，以下要求尚缺：'),
    '不符合': ('部分响应', '本条对应功能尚未开发，列入后续版本：'),
    '属外部边界/配套产品': ('配套产品响应', '属配套产品/外部条件，平台已留标准接入点：'),
    '非软件功能条款': ('正偏离/无偏离', '本条以承诺函/兼容性测试报告为证据，非软件功能点：'),
}

def main():
    v = load()
    rows = list(csv.reader(io.open('docs/验收/技术偏离表-v2.csv', encoding='utf-8-sig')))
    head, body = rows[0], rows[1:]
    claimed = [r for r in body if len(r) > 4 and '正偏离' in r[4]]
    done = [r for r in claimed if int(r[0]) in v]
    t = collections.Counter(v[int(r[0])]['verdict'] for r in done)
    print('答「已实现」总数 %d，已核对 %d（%.1f%%），未核对 %d'
          % (len(claimed), len(done), 100.0 * len(done) / len(claimed), len(claimed) - len(done)))
    for k, n in t.most_common():
        print('  %-22s %5d  %5.1f%%' % (k, n, 100.0 * n / len(done)))
    keep = t.get('符合', 0)
    print('\n可原样维持 %d 条（%.1f%%）；须改写 %d 条（%.1f%%）'
          % (keep, 100.0 * keep / len(done), len(done) - keep, 100.0 * (len(done) - keep) / len(done)))

    if '--reissue' not in sys.argv:
        return
    out = [head]
    changed = 0
    for r in body:
        r = list(r)
        no = int(r[0]) if r[0].strip().isdigit() else None
        a = v.get(no)
        if a and len(r) > 5 and '正偏离' in r[4]:
            concl, prefix = REMAP[a['verdict']]
            if a['verdict'] != '符合':
                r[4] = concl
                r[5] = (prefix + a['note'])[:900]
                changed += 1
        out.append(r)
    p = 'docs/验收/技术偏离表-v3草案.csv'
    with io.open(p, 'w', encoding='utf-8-sig', newline='') as f:
        csv.writer(f).writerows(out)
    print('\n已生成 %s：改写 %d 条（**草案，须项目组逐条签认后方可对外**）' % (p, changed))

main()
