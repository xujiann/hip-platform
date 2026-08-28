# -*- coding: utf-8 -*-
"""进销存 + 住院线 E2E 回归：
入库→入院占床→重复占床拦截→开医嘱→护士执行(扣库存+流水)→补押金→出院结算(押金冲抵)→床位释放→防护
前提：后端运行于 localhost:8080
"""
import json
import sys
import urllib.parse
import urllib.request
from e2elib import ensure_not_admitted, BASE, call, login, new_patient, ok, q  # noqa: E402



token = login()

amx = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
stock0 = amx['stock']

# 1 入库 +100（1.2.13 起入库先待验收、验收通过才加库存）
si = ok(call('POST', '/inventory/stock-in', {'drugId': amx['id'], 'qty': 100, 'batchNo': 'B20260801',
                                             'expireDate': '2028-06-30', 'supplier': '四川医药股份'}, token), '入库')
ok(call('POST', f"/inventory/stock-in/{si['id']}/accept", token=token), '入库验收')
amx2 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
assert amx2['stock'] == stock0 + 100, f"验收后库存应 +100: {stock0} -> {amx2['stock']}"
txns = ok(call('GET', '/inventory/transactions?drugId=' + str(amx['id']), token=token), '流水')
assert txns[0]['type'] == 'IN' and txns[0]['qty'] == 100
print(f"[1] 入库 {si['inNo']} +100：{stock0} -> {amx2['stock']}，流水 OK")

# 2 入院占床（内科病区找空床）
wards = [d for d in ok(call('GET', '/system/depts', token=token), '科室') if d['type'] == 'NURSING']
ward = next(w for w in wards if w['name'] == '内科病区')
beds = ok(call('GET', f"/inpatient/beds?wardId={ward['id']}", token=token), '床位')
free = next(b for b in beds if b['status'] == 'FREE')
icds = ok(call('GET', '/masterdata/icd10?keyword=' + q('糖尿病'), token=token), 'ICD')
pa = new_patient(token, '住院E2E甲', sex='M')   # 自建患者（1.2.0 消除种子 id 硬编码）
pb = new_patient(token, '住院E2E乙')
adm = ok(call('POST', '/inpatient/admissions', {
    'patientId': pa['id'], 'deptId': 1, 'bedId': free['id'], 'diagIcd': icds[0]['code'], 'diagName': icds[0]['name'],
    'deposit': 1000, 'payMethod': 'CASH'}, token), '入院')
aid = adm['id']
print(f"[2] 入院 {adm['admissionNo']}：{adm['patientName']} {adm['wardName']}{adm['bedNo']}床，押金 ¥1000")

# 3 同床再入院应被拦截
r = call('POST', '/inpatient/admissions', {'patientId': pb['id'], 'deptId': 1, 'bedId': free['id'],
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

# === 收尾环补充（1.2.14）：预交款不足提醒 / 转科带原因 / 打印数据集 ===
from e2elib import find_free_bed, today_bj  # noqa: E402

# 10 预交款不足提醒：入院不缴押金，执行一条药嘱 → 账户判欠费
bedC = find_free_bed(token)
pc = new_patient(token, '欠费提醒E2E', sex='M')
admC = ok(call('POST', '/inpatient/admissions', {
    'patientId': pc['id'], 'deptId': 1, 'bedId': bedC['id'],
    'diagIcd': icds[0]['code'], 'diagName': icds[0]['name'], 'deposit': 0, 'payMethod': 'CASH'}, token), '入院(无押金)')
cid = admC['id']
oc = ok(call('POST', f'/inpatient/admissions/{cid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'qd', 'dosePerTime': '1粒'},
]}, token), '开药嘱')[0]
ok(call('PUT', f"/inpatient/orders/{oc['id']}/execute", token=token), '执行药嘱')
acc = ok(call('GET', f'/inpatient/admissions/{cid}/account', token=token), '账户状态')
assert acc['owed'] is True, f'已执行费用>押金必须判欠费: {acc}'
assert float(acc['balance']) < 0, f'余额应为负: {acc}'
assert abs(float(acc['executedAmount']) - float(amx['price'])) < 0.001, f'已发生费用应=药价: {acc}'
print('[10] 预交款不足提醒 OK：欠费 ¥%.2f（已发生 ¥%s / 押金 0）' % (abs(float(acc['balance'])), acc['executedAmount']))

# 11 转科（带原因）：转到另一张空床，历史可读回原因与目标床
bedD = find_free_bed(token)
assert bedD['id'] != bedC['id'], '目标床应不同于当前床'
ok(call('POST', f'/inpatient/admissions/{cid}/transfer', {
    'toDeptId': 1, 'toBedId': bedD['id'], 'reason': '病情变化需专科处理'}, token), '转科')
hist = ok(call('GET', f'/inpatient/admissions/{cid}/transfers', token=token), '转科历史')
assert len(hist) >= 1 and hist[0]['reason'] == '病情变化需专科处理', f'转科原因须留痕: {hist}'
assert hist[0]['to_bed_no'] == bedD['bedNo'], f'历史应含目标床号: {hist[0]}'
print('[11] 转科(带原因)留痕 OK：-> %s床，原因「%s」' % (hist[0]['to_bed_no'], hist[0]['reason']))

# 12 打印数据集：每日费用清单 + 出院小结
today = str(today_bj())
dfp = ok(call('GET', f'/inpatient/admissions/{cid}/print/daily-fee?date={today}', token=token), '日清单打印')
assert dfp['patient_name'] == '欠费提醒E2E' and dfp['admission_no'] == admC['admissionNo']
assert abs(float(dfp['executedTotal']) - float(amx['price'])) < 0.001, f'日清单应带账户已发生费用: {dfp}'
assert float(dfp['balance']) < 0 and dfp['owed'] is True, f'日清单应带欠费余额: {dfp}'
assert len(dfp['rows']) >= 1, f'当日应有已执行费用行: {dfp}'
print('[12a] 日清单打印数据集 OK：当日合计 ¥%s，押金余额 ¥%s' % (dfp['dayTotal'], dfp['balance']))

ok(call('PUT', f'/inpatient/admissions/{cid}/discharge-diag',
        {'icd': icds[0]['code'], 'name': icds[0]['name']}, token), '出院诊断补录')
ok(call('POST', f'/inpatient/admissions/{cid}/records',
        {'recordType': 'DISCHARGE', 'title': '出院小结', 'content': '经治疗好转，准予出院，一周后复诊。'}, token), '出院小结病历')
dsp = ok(call('GET', f'/inpatient/admissions/{cid}/print/discharge-summary', token=token), '出院小结打印')
assert dsp['admission_no'] == admC['admissionNo'] and dsp['admit_diag_name']
assert any(r['record_type'] == 'DISCHARGE' for r in dsp['records']), f'应含出院小结病历: {dsp["records"]}'
assert len(dsp['meds']) >= 1, f'出院带药应含已执行药嘱: {dsp}'
print('[12b] 出院小结打印数据集 OK：诊疗经过 %d 条，带药 %d 项' % (len(dsp['records']), len(dsp['meds'])))

# 收尾：欠费出院不硬拦（允许欠费出院），释放床位
sc = ok(call('POST', f'/inpatient/admissions/{cid}/discharge', {}, token), '欠费出院')
assert float(sc['balance']) < 0, f'欠费出院结算单应标注负余额: {sc}'
print('[12c] 欠费出院不硬拦 OK：结算单 %s 欠费 ¥%.2f' % (sc['settleNo'], abs(float(sc['balance']))))

print('\n=== 进销存 + 住院线 E2E 全部通过 ✔ ===')
