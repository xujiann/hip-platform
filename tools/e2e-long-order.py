# -*- coding: utf-8 -*-
"""v39 住院长期医嘱 E2E：开长嘱(bid)→当日 2 执行行→逐行执行费用累计→停嘱固化→
中间结算排除未停嘱 LONG→出院汇总含累计。自成一体（自建患者收尾出院）。"""
import sys
from e2elib import call, ensure_not_admitted, find_free_bed, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()

pid = new_patient(t, '长嘱E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid, 'deptId': 2, 'bedId': free['id'], 'deposit': 500, 'payMethod': 'CASH'}, t), '入院')
admId = adm['id']

# 开长期医嘱（bid）+ 一条临时医嘱
drug = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
lng = ok(call('POST', f'/inpatient/admissions/{admId}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1粒', 'orderNature': 'LONG'}]}, t), '开长嘱')[0]
temp = ok(call('POST', f'/inpatient/admissions/{admId}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'st',
     'dosePerTime': '1粒'}]}, t), '开临嘱')[0]
assert float(lng['amount']) == 0, f'LONG 开立不计费: {lng}'
assert lng['orderNature'] == 'LONG'

# 当日执行行 bid=2
today = today_bj().isoformat()
lines = [l for l in ok(call('GET', f'/inpatient/exec-lines?date={today}', token=t), '执行行队列')
         if l['order_id'] == lng['id']]
assert len(lines) == 2, f'bid 应 2 行: {len(lines)}'
print('[1] 长嘱开立 OK（amount=0 / bid 当日 2 执行行）')

# 单次执行路径拒 LONG（9125）
assert call('PUT', f"/inpatient/orders/{lng['id']}/execute", token=t)['code'] == 9125, 'LONG 拒单次执行'
# 逐行执行费用累计
unit = float(lng['unitPrice'])
ok(call('PUT', f"/inpatient/exec-lines/{lines[0]['id']}/execute", token=t), '执行行1')
assert call('PUT', f"/inpatient/exec-lines/{lines[0]['id']}/execute", token=t)['code'] == 9126, '重复执行 9126'
ok(call('PUT', f"/inpatient/exec-lines/{lines[1]['id']}/execute", token=t), '执行行2')
ws = ok(call('GET', f'/inpatient/admissions/{admId}/workspace', token=t), '工作区')
row = next(o for o in ws['orders'] if o['id'] == lng['id'])
assert abs(float(row['amount']) - unit * 2) < 0.01, f'两次执行累计 {unit*2}: {row["amount"]}'
assert row['status'] == 'EXECUTED'
print(f"[2] 按执行行计费 OK（两次执行累计 ¥{row['amount']} / 9125 / 9126）")

# 临嘱执行 + 中间结算：未停嘱 LONG 不认领
ok(call('PUT', f"/inpatient/orders/{temp['id']}/execute", token=t), '执行临嘱')
interim = ok(call('POST', f'/inpatient/admissions/{admId}/interim-settle', token=t), '中间结算')
assert abs(float(interim['totalAmount']) - float(temp['amount'])) < 0.01, \
    f'中间结算只含临嘱 {temp["amount"]}: {interim["totalAmount"]}'
print(f"[3] 中间结算排除未停嘱长嘱 OK（只结临嘱 ¥{interim['totalAmount']}）")

# 停嘱固化：PENDING→SKIPPED、重复停嘱 9127、停嘱仅限 LONG 9128
assert call('POST', f"/inpatient/orders/{temp['id']}/stop", token=t)['code'] == 9128, '临嘱不能停嘱'
ok(call('POST', f"/inpatient/orders/{lng['id']}/stop", token=t), '停嘱')
assert call('POST', f"/inpatient/orders/{lng['id']}/stop", token=t)['code'] == 9127, '重复停嘱 9127'
print('[4] 停嘱 OK（费用固化 / 9127 / 9128）')

# 出院：汇总含长嘱累计 + 临嘱
settle = ok(call('POST', f'/inpatient/admissions/{admId}/discharge?payMethod=CASH', {}, t), '出院')
expect = unit * 2 + float(temp['amount'])
assert abs(float(settle['totalAmount']) - expect) < 0.01, f'出院总额应 {expect}: {settle["totalAmount"]}'
print(f"[5] 出院汇总 OK（含长嘱累计，总额 ¥{settle['totalAmount']}——下游口径零改动实证）")

print('\ne2e-long-order 全部通过 ✅')
