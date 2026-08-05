# -*- coding: utf-8 -*-
"""第三期收官 E2E：CDR 同步/患者360 + 患者端 H5 全流程 + 越权防护
前提：后端运行于 localhost:8080
"""
import json
import sys
import datetime
import urllib.error
import urllib.parse
import urllib.request
from e2elib import BASE, call, login, ok, q  # noqa: E402



t = login()
today = datetime.date.today().isoformat()

# 1 CDR 全量同步
s = ok(call('POST', '/cdr/sync', {}, t), 'CDR同步')
assert s['outpEncounters'] > 0 and s['labReports'] > 0 and s['inpSummaries'] > 0, s
print(f"[1] CDR 同步：门诊 {s['outpEncounters']}，检验 {s['labReports']}，住院 {s['inpSummaries']}")

# 2 患者360：张三(id=2) 应有三类文档
docs = ok(call('GET', '/cdr/patients/2/documents', token=t), '360文档')
types = {d['docType'] for d in docs}
assert {'OUTP_ENCOUNTER', 'LAB_REPORT', 'INP_SUMMARY'} <= types, types
lab_doc = next(d for d in docs if d['docType'] == 'LAB_REPORT' and '血红蛋白' in d['content'])
content = json.loads(lab_doc['content'])
assert any(r['abnormal_flag'] == 'LL' for r in content['results'])
print(f"[2] 患者360：{len(docs)} 份文档覆盖三类，检验文档含 LL 结果 ✓")

# 3 重复同步幂等（数量不翻倍）
docs2 = ok(call('POST', '/cdr/sync', {}, t), '再同步') and ok(call('GET', '/cdr/patients/2/documents', token=t), '文档2')
assert len(docs2) == len(docs), f'幂等失败: {len(docs)} -> {len(docs2)}'
print('[3] 重复同步幂等 OK')

# 4 患者端登录（正确/错误手机号）
status, bad = call('POST', '/portal/login', {'patientNo': 'P00000002', 'phone': '13900000000'})
assert bad['code'] == 9501
pt = ok(call('POST', '/portal/login', {'patientNo': 'P00000002', 'phone': '13800138000'}), '患者登录')
ptoken = pt['token']
print(f"[4] 患者端登录 OK（{pt['patientName']}），错误手机号拒绝(9501) OK")

# 5 患者端数据：我的挂号/报告/费用
regs = ok(call('GET', '/portal/my/registrations', token=ptoken), '我的挂号')
labs = ok(call('GET', '/portal/my/lab-reports', token=ptoken), '我的报告')
charges = ok(call('GET', '/portal/my/charges', token=ptoken), '我的费用')
assert regs and labs and charges
# 二十九期起危急值不外显：LL/HH 在患者端遮蔽为 CRIT 回院提示
assert any(any(r['abnormalFlag'] == 'CRIT' for r in l['results']) for l in labs)
print(f"[5] 患者端：挂号 {len(regs)} 条，报告 {len(labs)} 份（危急值已遮蔽为回院提示），费用 {len(charges)} 笔")

# 6 在线预约挂号
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 2, 'scheduleDate': today, 'fee': 15, 'capacity': 5}, t), '放号')
booking = ok(call('POST', '/portal/register', {'scheduleId': sch['id']}, ptoken), '在线预约')
print(f"[6] 在线预约成功：{booking['deptName']} {booking['visitDate']} 第 {booking['regNo']} 号")
wl = ok(call('GET', f'/outpatient/registrations?date={today}', token=t), '院内核对')
assert any(w['patientNo'] == 'P00000002' and w['deptName'] == booking['deptName']
           and w['regNo'] == booking['regNo'] for w in wl)
print('    院内挂号队列同步可见 ✓')

# 7 越权防护：患者令牌访问院内接口应 401/403
status, _ = call('GET', '/system/users', token=ptoken)
assert status in (401, 403), f'患者令牌不应访问院内接口: {status}'
status, _ = call('GET', '/stats/overview', token=ptoken)
assert status in (401, 403)
print(f"[7] 患者令牌访问院内接口被拒（{status}）OK")

print('\n=== 第三期收官 E2E（CDR + 患者端）全部通过 ✔ ===')
