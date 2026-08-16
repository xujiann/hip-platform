# -*- coding: utf-8 -*-
"""进销存 + 住院线 E2E 回归：
入库→入院占床→重复占床拦截→开医嘱→护士执行(扣库存+流水)→补押金→出院结算(押金冲抵)→床位释放→防护
前提：后端运行于 localhost:8080
"""
import json
import sys
import urllib.parse
import urllib.request
from e2elib import ensure_not_admitted, BASE, call, login, ok, q  # noqa: E402



token = login()

amx = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
stock0 = amx['stock']

# 1 入库 +100
si = ok(call('POST', '/inventory/stock-in', {'drugId': amx['id'], 'qty': 100, 'batchNo': 'B20260801',
                                             'expireDate': '2028-06-30', 'supplier': '四川医药股份'}, token), '入库')
amx2 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
assert amx2['stock'] == stock0 + 100, f"入库后库存应 +100: {stock0} -> {amx2['stock']}"
txns = ok(call('GET', '/inventory/transactions?drugId=' + str(amx['id']), token=token), '流水')
assert txns[0]['type'] == 'IN' and txns[0]['qty'] == 100
print(f"[1] 入库 {si['inNo']} +100：{stock0} -> {amx2['stock']}，流水 OK")

# 2 入院占床（内科病区找空床）
wards = [d for d in ok(call('GET', '/system/depts', token=token), '科室') if d['type'] == 'NURSING']
ward = next(w for w in wards if w['name'] == '内科病区')
beds = ok(call('GET', f"/inpatient/beds?wardId={ward['id']}", token=token), '床位')
free = next(b for b in beds if b['status'] == 'FREE')
icds = ok(call('GET', '/masterdata/icd10?keyword=' + q('糖尿病'), token=token), 'ICD')
ensure_not_admitted(token, 2)   # 1.1.0：同一患者只能一条在院记录，先清历史未收尾的
adm = ok(call('POST', '/inpatient/admissions', {
    'patientId': 2, 'deptId': 1, 'bedId': free['id'], 'diagIcd': icds[0]['code'], 'diagName': icds[0]['name'],
    'deposit': 1000, 'payMethod': 'CASH'}, token), '入院')
aid = adm['id']
print(f"[2] 入院 {adm['admissionNo']}：{adm['patientName']} {adm['wardName']}{adm['bedNo']}床，押金 ¥1000")

# 3 同床再入院应被拦截
r = call('POST', '/inpatient/admissions', {'patientId': 1, 'deptId': 1, 'bedId': free['id'],
                                           'deposit': 0, 'payMethod': 'CASH'}, token)
assert r['code'] == 9002, f'重复占床应被拦截: {r}'
print('[3] 重复占床拦截 OK')

# 4 开医嘱：药 x2 + 输液
infusion = ok(call('GET', '/masterdata/charge-items?keyword=' + q('静脉输液'), token=token), '项目')[0]
orders = ok(call('POST', f'/inpatient/admissions/{aid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 2, 'usageRoute': '口服', 'frequency': 'bid', 'dosePerTime': '1粒'},
    {'orderType': 'TREAT', 'itemId': infusion['id'], 'qty': 1},
]}, token), '开医嘱')
print(f"[4] 开医嘱 {len(orders)} 行 OK")

# 5 未执行医嘱时出院应被拦截
r = call('POST', f'/inpatient/admissions/{aid}/discharge', token=token)
assert r['code'] == 9012, f'未执行医嘱出院应被拦截: {r}'
print('[5] 未执行医嘱出院拦截 OK')

# 6 护士执行（药品执行扣库存）
pending = ok(call('GET', '/inpatient/orders/pending', token=token), '执行队列')
my_orders = [p for p in pending if p['admissionNo'] == adm['admissionNo']]
assert len(my_orders) == 2
for p in my_orders:
    ok(call('PUT', f"/inpatient/orders/{p['orderId']}/execute", token=token), f"执行{p['itemName']}")
amx3 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
assert amx3['stock'] == stock0 + 100 - 2, f"执行后库存应 -2: {amx3['stock']}"
txns = ok(call('GET', '/inventory/transactions?drugId=' + str(amx['id']), token=token), '流水')
assert txns[0]['type'] == 'OUT' and txns[0]['qty'] == -2
print(f"[6] 护士执行 2 条 OK，库存 {amx2['stock']} -> {amx3['stock']}，OUT 流水 OK")

# 7 补押金 + 出院结算：费用 = 2*12.5 + 12 = 37；押金 1000+200=1200；应退 1163
ok(call('POST', f'/inpatient/admissions/{aid}/deposits', {'amount': 200, 'payMethod': 'WECHAT'}, token), '补押金')
s = ok(call('POST', f'/inpatient/admissions/{aid}/discharge', token=token), '出院结算')
expected_total = round(float(amx['price']) * 2 + float(infusion['price']), 2)
assert float(s['totalAmount']) == expected_total, f"费用应 {expected_total}: {s}"
assert float(s['depositAmount']) == 1200.0
assert abs(float(s['balance']) - (1200.0 - expected_total)) < 0.001
print(f"[7] 出院结算 {s['settleNo']}：费用 ¥{s['totalAmount']}，押金 ¥{s['depositAmount']}，应退 ¥{s['balance']}")

# 8 床位释放 + 出院后防护
beds2 = ok(call('GET', f"/inpatient/beds?wardId={ward['id']}", token=token), '床位')
assert next(b for b in beds2 if b['id'] == free['id'])['status'] == 'FREE', '床位应释放'
assert call('POST', f'/inpatient/admissions/{aid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1}]}, token)['code'] == 9005
assert call('POST', f'/inpatient/admissions/{aid}/discharge', token=token)['code'] == 9011
assert call('POST', f'/inpatient/admissions/{aid}/deposits', {'amount': 1, 'payMethod': 'CASH'}, token)['code'] == 9004
print('[8] 床位释放 + 出院后开医嘱/重复结算/缴押金防护 OK')

# 9 盘点调整
ok(call('POST', '/inventory/adjust', {'drugId': amx['id'], 'newStock': amx3['stock'] - 1, 'reason': '盘亏1盒'}, token), '盘点')
amx4 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
assert amx4['stock'] == amx3['stock'] - 1
print(f"[9] 盘点调整 OK：{amx3['stock']} -> {amx4['stock']}")

print('\n=== 进销存 + 住院线 E2E 全部通过 ✔ ===')
