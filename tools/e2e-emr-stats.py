# -*- coding: utf-8 -*-
"""住院病历/生命体征/统计驾驶舱 E2E 回归（自入院自出院，可重复执行）"""
import json
import sys
import urllib.request
from e2elib import ensure_not_admitted, BASE, call, login, ok, q  # noqa: E402



t = login()

ward = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING'][0]
free = next(b for b in ok(call('GET', f"/inpatient/beds?wardId={ward['id']}", token=t), '床位') if b['status'] == 'FREE')
ensure_not_admitted(t, 2)   # 1.1.0：同一患者只能一条在院记录，先清历史未收尾的
adm = ok(call('POST', '/inpatient/admissions', {'patientId': 2, 'deptId': 1, 'bedId': free['id'],
                                                'deposit': 500, 'payMethod': 'CASH'}, t), '入院')
aid = adm['id']

ok(call('POST', f'/inpatient/admissions/{aid}/records',
        {'recordType': 'ADMISSION', 'title': '入院记录', 'content': '患者因血糖控制不佳入院。'}, t), '入院记录')
ok(call('POST', f'/inpatient/admissions/{aid}/records',
        {'recordType': 'PROGRESS', 'content': '今日精神可。'}, t), '病程')
recs = ok(call('GET', f'/inpatient/admissions/{aid}/records', token=t), '病历列表')
assert len(recs) == 2
print('[1] 病历记录 OK')

ok(call('POST', f'/inpatient/admissions/{aid}/vitals',
        {'temperature': 36.8, 'pulse': 78, 'respiration': 18, 'sbp': 128, 'dbp': 82, 'spo2': 98}, t), '体征')
vs = ok(call('GET', f'/inpatient/admissions/{aid}/vitals', token=t), '体征列表')
assert len(vs) >= 1
print('[2] 生命体征 OK')

ov = ok(call('GET', '/stats/overview', token=t), '统计总览')
assert ov['inHospitalCount'] >= 1 and ov['bedTotal'] >= 10
daily = ok(call('GET', '/stats/daily?days=7', token=t), '日趋势')
assert len(daily) == 7
print(f"[3] 驾驶舱 OK: 在院{ov['inHospitalCount']} 床位{ov['bedOccupied']}/{ov['bedTotal']}")

s = ok(call('POST', f'/inpatient/admissions/{aid}/discharge', token=t), '出院清理')
print(f"[4] 测试患者已出院 {s['settleNo']}")
print('\n=== 病历/体征/驾驶舱 E2E 全部通过 ✔ ===')
