# -*- coding: utf-8 -*-
"""门诊闭环 E2E 回归：
挂号(含挂号费并单)→收费→退费→再收费→接诊→病历/诊断→开单→收费→医技执行→退费拦截→发药→库存核减
前提：后端运行于 localhost:8080
"""
import json
import sys
import urllib.parse
import urllib.request
import datetime
from e2elib import BASE, call, login, new_patient, ok, q  # noqa: E402



token = login()
today = datetime.date.today().isoformat()

drugs = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品查询')
amx = drugs[0]
stock0 = amx['stock']
labs = ok(call('GET', '/masterdata/charge-items?keyword=' + q('血常规'), token=token), '项目查询')
lab = labs[0]

# 1 排班 + 挂号（自动生成挂号费订单行）。患者一律本套件自建：
# patientId 1/2 只在 CI 处女库存在，实施替换种子/本地复跑都会 404（种子契约，1.2.0 消除）
pa = new_patient(token, '门诊E2E甲', sex='M')
pb = new_patient(token, '门诊E2E乙')
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 10, 'capacity': 30}, token), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': pa['id'], 'scheduleId': sch['id']}, token), '挂号')
rid = reg['id']
unpaid = ok(call('GET', f'/outpatient/charges/unpaid?registrationId={rid}', token=token), '待收明细')
assert any(o['orderType'] == 'REG' for o in unpaid['orders']), '应有挂号费订单行'
assert float(unpaid['total']) == 10.0
print(f"[1] 挂号 regId={rid}，挂号费已并入待收（¥{unpaid['total']}）")

# 2 收挂号费 → 退费 → 再收
c1 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, token), '收挂号费')
r = ok(call('POST', f"/outpatient/charges/{c1['id']}/refund", token=token), '退费')
assert r['status'] == 'REFUNDED'
c2 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'WECHAT'}, token), '重新收费')
print(f"[2] 收费 {c1['chargeNo']} → 退费 → 重新收费 {c2['chargeNo']} OK")

# 3 已收费的挂号退号应被拦截
reg2 = ok(call('POST', '/outpatient/registrations', {'patientId': pb['id'], 'scheduleId': sch['id']}, token), '第二个挂号')
c3 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg2['id'], 'payMethod': 'CASH'}, token), '收第二单挂号费')
r = call('PUT', f"/outpatient/registrations/{reg2['id']}/cancel", token=token)
assert r['code'] == 3006, f'已收费退号应被拦截: {r}'
ok(call('POST', f"/outpatient/charges/{c3['id']}/refund", token=token), '退第二单费')
ok(call('PUT', f"/outpatient/registrations/{reg2['id']}/cancel", token=token), '退号')
print('[3] 已收费退号拦截 → 退费后退号成功 OK')

# 4 接诊 + 病历/诊断 + 开单
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, token), '接诊')
icds = ok(call('GET', '/masterdata/icd10?keyword=' + q('上呼吸道'), token=token), 'ICD查询')
ok(call('PUT', f'/outpatient/doctor/{rid}/emr', {
    'emr': {'chiefComplaint': '咽痛发热2天', 'presentIllness': '受凉后咽痛发热', 'pastHistory': '无',
            'physicalExam': '咽充血', 'advice': '口服药+复查血常规'},
    'diagnoses': [{'icdCode': icds[0]['code'], 'icdName': icds[0]['name']}],
}, token), '病历')
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'tid',
     'dosePerTime': '1粒', 'days': 3},
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
]}, token), '开单')
print(f"[4] 接诊+病历+开单 {len(orders)} 行 OK")

# 5 收诊疗费用
c4 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'YB'}, token), '收诊疗费')
expected = round(float(amx['price']) + float(lab['price']), 2)
assert float(c4['totalAmount']) == expected, f"金额应为 {expected}: {c4}"
print(f"[5] 诊疗费结算 {c4['chargeNo']} ¥{c4['totalAmount']} OK")

# 6 医技执行（出报告）——用本次开单的 LAB 订单 id 精确执行
lab_order_id = next(o['id'] for o in orders if o['orderType'] == 'LAB')
wl = ok(call('GET', '/outpatient/exec/worklist', token=token), '执行队列')
assert any(w['orderId'] == lab_order_id for w in wl), f'执行队列应含本次血常规订单 {lab_order_id}'
ok(call('POST', f"/outpatient/exec/{lab_order_id}", {'resultText': 'WBC 12.3×10^9/L 偏高，NEUT% 82%'}, token), '执行')
reports = ok(call('GET', f'/outpatient/exec/reports?registrationId={rid}', token=token), '报告查询')
assert len(reports) >= 1 and reports[0]['resultText'].startswith('WBC'), '应能查到报告'
print(f"[6] 医技执行+报告 OK: {reports[0]['resultText'][:20]}...")

# 7 已执行项目所在结算单退费应被拦截
r = call('POST', f"/outpatient/charges/{c4['id']}/refund", token=token)
assert r['code'] == 5005, f'已执行退费应被拦截: {r}'
print('[7] 已执行项目退费拦截 OK')

# 8 发药 + 库存核减
ok(call('POST', f'/outpatient/dispense/{rid}', {}, token), '发药')
drugs2 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品复查')
assert drugs2[0]['stock'] == stock0 - 1, f"库存应减1: {stock0} -> {drugs2[0]['stock']}"
print(f"[8] 发药+库存核减 OK: {stock0} -> {drugs2[0]['stock']}")

# 9 重复操作防护
assert call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, token)['code'] == 5001
assert call('POST', f'/outpatient/dispense/{rid}', {}, token)['code'] == 6001
print('[9] 重复结算/发药防护 OK')

print('\n=== 门诊闭环 E2E（含退费/医技执行）全部通过 ✔ ===')
