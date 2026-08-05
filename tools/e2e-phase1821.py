# -*- coding: utf-8 -*-
"""十八至二十一期 E2E：机构参数化 / 模块下沉回归 / CDA / 检索 / 增量同步 / AI 子服务 / PACS 配置 / 迁移演练"""
import json
import subprocess
import sys
import urllib.parse
import urllib.request



t = login()

# 十八期：机构参数化（公开配置匿名可读，院名通用化）
cfg = ok(call('GET', '/config/public'), '公开配置')
assert cfg['hospital_name'] == '示范医院', cfg
assert '峨眉山' not in json.dumps(cfg, ensure_ascii=False)
ok(call('PUT', '/config/hospital_name?value=' + q('示范医院'), token=t), '配置更新')
print(f"[十八-1] 机构参数化 OK（院名走配置：{cfg['hospital_name']}，无硬编码地域）")

# 十八期：下沉后的四域接口回归（护理质控/患者管理/HRP/数据治理/医技）
for path, name in [('/inpatient/nursing/board', '护理白板'), ('/patientcare/satisfaction/stats', '满意度'),
                   ('/hrp/materials', '物资'), ('/datagov/quality-checks', '数据质量'),
                   ('/lis/samples', 'LIS')]:
    ok(call('GET', path, token=t), name)
print('[十八-2] 五个下沉模块接口全部可用（架构还债回归 OK）')

# 十九期：全量+增量同步 / 检索 / CDA
ok(call('POST', '/cdr/sync', {}, t), '全量同步')
inc1 = ok(call('POST', '/cdr/sync-incremental', {}, t), '增量1')
inc2 = ok(call('POST', '/cdr/sync-incremental', {}, t), '增量2')
assert sum(inc2.values()) == 0, f'水位推进后再次增量应为 0: {inc2}'
print(f"[十九-1] 增量同步 OK（首次 {sum(inc1.values())} 篇，水位后 0 篇）")

hits = ok(call('GET', '/cdr/search?keyword=' + q('血红蛋白'), token=t), '检索')
assert len(hits) > 0
doc_id = hits[0]['id']
print(f"[十九-2] 病历检索 OK（『血红蛋白』命中 {len(hits)} 篇）")

xml = call('GET', f'/cdr/documents/{doc_id}/cda', token=t, raw=True)
assert '<ClinicalDocument' in xml and 'urn:hl7-org:v3' in xml and '<recordTarget>' in xml
print('[十九-3] CDA XML 输出 OK（ClinicalDocument 结构完整）')

# 二十期：AI 子服务 + PACS 配置
labs = ok(call('GET', '/cdr/patients/2/documents?docType=LAB_REPORT', token=t), '检验文档')
order_id = labs[0]['refId']
ai = ok(call('GET', f'/api/ai/lab-advice?orderId={order_id}'.replace('/api', '', 1), token=t), 'AI解读')
assert ai['source'] == 'ai-service', ai
assert len(ai['advice']) > 0
print(f"[二十-1] AI 子服务 OK（{ai['model']}，建议 {len(ai['advice'])} 条：{ai['advice'][0][:24]}…）")

pacs = ok(call('GET', '/ris/pacs-info', token=t), 'PACS配置')
assert pacs['configured'] and '8042' in pacs['viewerUrl']
print(f"[二十-2] PACS 配置接入 OK（viewer: {pacs['viewerUrl']}）")

# 二十一期：迁移演练（模板导入，幂等）
import os
from e2elib import BASE, call, login, ok, q  # noqa: E402
env = dict(os.environ, PYTHONIOENCODING='utf-8')
r = subprocess.run([sys.executable, 'tools/import-patients.py', 'tools/migrate-templates/patients.csv'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
assert '成功 2' in r.stdout, r.stdout + r.stderr
print(f"[二十一] 迁移演练 OK（{r.stdout.strip().splitlines()[-1]}）")

print('\n=== 十八至二十一期 E2E 全部通过 ✔ ===')
