# -*- coding: utf-8 -*-
"""v36 LIS 质控轮 E2E：微生物药敏结构化 + 室内质控 IQC(Westgard) + TAT 周转。自成一体。"""
import sys
from e2elib import call, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# 建一个已核收标本（微生物录入前置）
pid = new_patient(t, 'LIS质控E2E', sex='M')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 5}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('血常规'), token=t), '检验项')[0]
order = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单')[0]
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')
bc = ok(call('POST', f"/lis/samples?orderId={order['id']}", {}, t), '采样')['barcode']
ok(call('PUT', f'/lis/samples/{bc}/receive', token=t), '核收')

# ---- 1) 微生物药敏 ----
assert call('POST', '/lis/micro/NOPEBC', {'organism': '大肠埃希菌'}, t)['code'] == 7106, '标本不存在'
assert call('POST', f'/lis/micro/{bc}', {'organism': '  '}, t)['code'] == 7107, '菌种必填'
assert call('POST', f'/lis/micro/{bc}', {'organism': '大肠埃希菌', 'ast': [{'antibiotic': '头孢', 'sir': 'X'}]}, t)['code'] == 7108, '药敏非法'
ok(call('POST', f'/lis/micro/{bc}', {'specimen': '痰', 'organism': '大肠埃希菌', 'gram': 'NEG', 'colonyCount': '+++',
        'ast': [{'antibiotic': '头孢曲松', 'method': 'MIC', 'micValue': '1', 'sir': 'S'},
                {'antibiotic': '氨苄西林', 'method': 'MIC', 'micValue': '32', 'sir': 'R'}]}, t), '录微生物')
micro = ok(call('GET', f"/lis/micro?orderId={order['id']}", token=t), '查微生物')
assert len(micro) == 1 and micro[0]['organism'] == '大肠埃希菌' and len(micro[0]['ast']) == 2
print('[1] 微生物药敏 OK（录入 1 菌 2 药敏 / 7106/7107/7108）')

# ---- 2) 室内质控 IQC（Westgard）----
assert call('POST', '/lis/qc', {'itemCode': '', 'level': 'L1', 'lotNo': 'X', 'targetValue': 5, 'sd': 0.2, 'measuredValue': 5}, t)['code'] == 7109
assert call('POST', '/lis/qc', {'itemCode': 'GLU', 'level': 'L1', 'lotNo': 'X', 'targetValue': 5, 'sd': 0, 'measuredValue': 5}, t)['code'] == 7110
item = 'GLUE2E'
r1 = ok(call('POST', '/lis/qc', {'itemCode': item, 'level': 'L1', 'lotNo': 'L001', 'targetValue': 5.0, 'sd': 0.2, 'measuredValue': 5.2}, t), '质控在控')
assert r1['inControl'] is True, r1
r2 = ok(call('POST', '/lis/qc', {'itemCode': item, 'level': 'L2', 'lotNo': 'L001', 'targetValue': 5.0, 'sd': 0.2, 'measuredValue': 5.8}, t), '质控失控')
assert r2['inControl'] is False and r2['rule'] == '1-3s', r2
latest = ok(call('GET', '/lis/qc/latest', token=t), '质控最新')
assert any(x['item_code'] == item for x in latest)
print('[2] 室内质控 IQC OK（z 计算 / 在控 / 1-3s 失控 / 7109/7110）')

# ---- 3) TAT 周转 ----
tat = ok(call('GET', '/lis/tat', token=t), 'TAT')
assert 'total' in tat and 'limitMinutes' in tat
ok(call('GET', '/lis/tat/outliers', token=t), 'TAT超时')
print(f"[3] TAT 周转 OK（已发布 {tat['total']} 件，阈值 {tat['limitMinutes']} 分）")

print('\ne2e-lis-qc 全部通过 ✅')
