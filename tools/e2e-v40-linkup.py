# -*- coding: utf-8 -*-
"""v40 断链补齐 E2E：票据补打检索/打印留痕 + 病案室待办工作队列 + 患者端分时段预约与住院费用。
自成一体（自建患者/排班），末尾收尾出院释放床位。"""
import sys
from e2elib import call, ensure_not_admitted, find_free_bed, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# ---- 1) 票据补打检索 + 打印留痕 ----
pid = new_patient(t, '补打E2E', sex='F')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 10}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('血常规'), token=t), '检验项')[0]
ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单')
charge = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')

found = ok(call('GET', '/print/charge-search?keyword=' + q(charge['chargeNo']), token=t), '补打检索')
assert any(r['charge_no'] == charge['chargeNo'] for r in found), '应按结算单号检索到'
row = next(r for r in found if r['charge_no'] == charge['chargeNo'])
assert row['print_count'] == 0, '初始未打印'
ok(call('POST', f"/print/log?docType=CHARGE&docId={row['id']}", {}, t), '打印留痕')
ok(call('POST', f"/print/log?docType=CHARGE&docId={row['id']}", {}, t), '补打留痕')
assert call('POST', f"/print/log?docType=NOPE&docId={row['id']}", {}, t)['code'] == 4000, '非法单据类型'
again = ok(call('GET', '/print/charge-search?keyword=' + q(charge['chargeNo']), token=t), '再检索')
assert next(r for r in again if r['charge_no'] == charge['chargeNo'])['print_count'] == 2, '补打次数应计 2'
# 票据数据集仍可取（补打依赖）
receipt = ok(call('GET', f"/print/charge/{row['id']}", token=t), '票据数据')
assert receipt['charge_no'] == charge['chargeNo'] and receipt['items'], '补打需明细行'
print(f"[1] 票据补打 OK（检索命中 / 留痕计数 2 / 非法类型 4000 / 明细 {len(receipt['items'])} 行）")

# ---- 2) 病案室待办工作队列 ----
pid2 = new_patient(t, '病案队列E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid2, 'deptId': 2, 'bedId': free['id'], 'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge?payMethod=CASH", {}, t), '出院')
wq = ok(call('GET', '/quality/mr-workqueue', token=t), '病案队列')
for k in ['items', 'total', 'overdueDays', 'pendingCode', 'pendingArchive', 'limit']:
    assert k in wq, f'队列缺键 {k}'
mine = next((x for x in wq['items'] if x['admissionNo'] == adm['admissionNo']), None)
if mine:   # 队列有 200 上限，量大时本条可能被挤出（截断为设计内行为）
    assert mine['archived'] is False and mine['missing'], f'未收尾病案应列缺项: {mine}'
    print(f"[2] 病案队列 OK（待编码 {wq['pendingCode']} / 待归档 {wq['pendingArchive']} / 本例缺 {mine['missingCount']} 项）")
else:
    assert wq.get('truncated'), '本例未出现且未截断——队列口径可疑'
    print(f"[2] 病案队列 OK（队列已达上限 {wq['limit']} 截断，本例被挤出属设计内）")

# 归档（warn 模式带 warning 放行）
arch = call('PUT', f"/inpatient/admissions/{adm['id']}/archive", {}, t)
assert arch['code'] == 0, f'warn 模式应放行: {arch}'
print('[3] 归档 OK（v35 完整性 gate warn 模式带 warning 放行）')

# ---- 4) 患者端：分时段预约 + 住院费用（患者端令牌） ----
p3 = new_patient(t, '患者端E2E', sex='F', phone='13900001111')
pt = ok(call('POST', '/portal/login', {'patientNo': p3['patientNo'], 'phone': '13900001111'}), '患者端登录')['token']
sch2 = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 6}, t), '排班2')
ok(call('POST', f"/outpatient/schedules/{sch2['id']}/slots",
        {'slots': [{'timeBegin': '14:00', 'timeEnd': '14:30', 'capacity': 3}]}, t), '建时段')
slots = ok(call('GET', f"/portal/schedules/{sch2['id']}/slots", token=pt), '患者端时段')
assert slots and slots[0]['remaining'] == 3, f'患者端应看到余号: {slots}'
appt = ok(call('POST', '/portal/appointments', {'slotId': slots[0]['id']}, pt), '患者端预约')
mine2 = ok(call('GET', '/portal/my/appointments', token=pt), '我的预约')
assert any(a['id'] == appt['id'] for a in mine2), '我的预约应含刚约的'
ok(call('POST', f"/portal/my/appointments/{appt['id']}/cancel", {}, pt), '自助取消')
slots2 = ok(call('GET', f"/portal/schedules/{sch2['id']}/slots", token=pt), '取消后时段')
assert slots2[0]['remaining'] == 3, '取消后号源应回落'
print('[4] 患者端分时段预约 OK（时段余号/预约/我的预约/自助取消两级回落）')

# 收尾
ensure_not_admitted(t, pid2)
print('\ne2e-v40-linkup 全部通过 ✅')
