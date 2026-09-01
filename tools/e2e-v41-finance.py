# -*- coding: utf-8 -*-
"""v41 经营闭环 E2E：欠费台账（挂账→补缴→催缴→核销）+ 收费员班结缴款单 + 科室月报 +
医保基金监测 + 床位效率趋势。自成一体（自建患者/账号），末尾收尾。"""
import sys
from e2elib import call, ensure_not_admitted, find_free_bed, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# ---- 1) 欠费台账：押金不足出院 → 自动挂账 → 补缴 → 催缴 ----
pid = new_patient(t, '欠费E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid, 'deptId': 2, 'bedId': free['id'], 'deposit': 0, 'payMethod': 'CASH'}, t), '入院(0押金)')
drug = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
o = ok(call('POST', f"/inpatient/admissions/{adm['id']}/orders", {'lines': [
    {'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 2, 'usageRoute': '口服', 'frequency': 'st', 'dosePerTime': '1粒'}]}, t), '开嘱')[0]
ok(call('PUT', f"/inpatient/orders/{o['id']}/execute", token=t), '执行')
settle = ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge?payMethod=CASH", {}, t), '出院(欠费)')
owed = -float(settle['balance'])
assert owed > 0, f'应欠费: {settle}'

arrears = ok(call('GET', '/inpatient/arrears?status=OPEN', token=t), '欠费清单')
mine = next((a for a in arrears if a['admission_no'] == adm['admissionNo']), None)
assert mine, f'出院欠费应自动挂账: {adm["admissionNo"]} / 队列 {[a.get("admission_no") for a in arrears][:5]}'
aid = mine['id']
# 补缴一半 → PARTIAL
half = round(owed / 2, 2)
r1 = ok(call('POST', f'/inpatient/arrears/{aid}/payments', {'amount': half, 'payMethod': 'CASH'}, t), '补缴一半')
assert r1['status'] == 'PARTIAL', r1
# 超额被拒
assert call('POST', f'/inpatient/arrears/{aid}/payments', {'amount': owed, 'payMethod': 'CASH'}, t)['code'] == 9037, '超额应拒'
# 催缴登记
ok(call('POST', f'/inpatient/arrears/{aid}/dunnings', {'method': 'PHONE', 'note': '电话通知家属'}, t), '催缴')
# 补足 → CLEARED
r2 = ok(call('POST', f'/inpatient/arrears/{aid}/payments', {'amount': owed - half, 'payMethod': 'CASH'}, t), '补足')
assert r2['status'] == 'CLEARED', r2
detail = ok(call('GET', f'/inpatient/arrears/{aid}', token=t), '欠费详情')
assert len(detail['payments']) == 2 and len(detail['dunnings']) == 1
print(f"[1] 欠费台账 OK（自动挂账 ¥{owed} → 补缴两笔 CLEARED / 超额 9037 / 催缴留痕）")

# ---- 2) 收费员班结缴款单 ----
cashier = provision_user(t, 'cashier_v41_e2e', 'CASHIER', '班结收费员E2E')
p2 = new_patient(t, '班结E2E', sex='F')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 8, 'capacity': 5}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': p2, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, cashier), '收费(收费员)')
pv = ok(call('GET', f'/finance/shift-close/preview?date={today}', token=cashier), '班结预览')
assert float(pv['sysPaid']) >= 8, f'预览应含本人收款: {pv}'
sub = ok(call('POST', '/finance/shift-close', {'date': today, 'declaredCash': float(pv['sysNet']) + 2, 'note': 'E2E'}, cashier), '提交班结')
assert abs(float(sub['diff']) - 2) < 0.01, f'长款 2 元: {sub}'
assert call('POST', '/finance/shift-close', {'date': today, 'declaredCash': 1}, cashier)['code'] == 5020, '重复提交 5020'
ok(call('PUT', f"/finance/shift-close/{sub['id']}/confirm", token=t), '财务确认(ADMIN)')
assert call('PUT', f"/finance/shift-close/{sub['id']}/confirm", token=t)['code'] == 5023, '重复确认 5023'
own = ok(call('GET', '/finance/shift-close', token=cashier), '收费员看自己')
assert all(x['cashier_id'] == pv['cashierId'] for x in own), '收费员只应看到自己的班结'
print(f"[2] 班结缴款单 OK（预览口径含本人 / 长款 ¥2 / 5020 重复提交 / 5023 重复确认 / 只见自己）")

# ---- 3) 科室月报 + CSV ----
month = today[:7]
rep = ok(call('GET', f'/stats/dept-monthly?month={month}', token=t), '科室月报')
for k in ['month', 'depts', 'byDoctor', 'totals']:
    assert k in rep, f'月报缺键 {k}'
csv_text = call('GET', f'/stats/dept-monthly.csv?month={month}', token=t, raw=True)
assert '科室' in csv_text or 'dept' in csv_text.lower(), 'CSV 应有表头'
print(f"[3] 科室月报 OK（{len(rep['depts'])} 科室 / 医生排行 {len(rep['byDoctor'])} 行 / CSV 可导出）")

# ---- 4) 医保基金监测 ----
fm = ok(call('GET', '/insurance/fund-monitor', token=t), '基金监测')
for k in ['monthly', 'caps', 'capAlerts', 'capAlertNote']:
    assert k in fm, f'基金监测缺键 {k}'
# 默认 cap=0（未启用）→ capAlerts 为 null 且注明
if fm['caps'].get('staff') in (None, 0):
    assert fm['capAlerts'] is None and '未启用' in str(fm['capAlertNote']), f'未启用封顶线应明确标注: {fm}'
print(f"[4] 医保基金监测 OK（近 12 月 {len(fm['monthly'])} 行 / 封顶线口径正确标注）")

# ---- 5) 床位效率趋势（补验收已承诺项）----
bed = ok(call('GET', '/mrstats/dept-bed-trend?months=12', token=t), '床位效率')
assert isinstance(bed, list), '应返回趋势行'
if bed:
    row = bed[0]
    for k in ['month', 'dept_name', 'discharges', 'avg_stay_days', 'bed_days', 'bed_count']:
        assert k in row, f'床位效率缺列 {k}'
print(f"[5] 床位效率趋势 OK（{len(bed)} 行：出院人次/平均住院日/占用床日/周转/使用率）")

print('\ne2e-v41-finance 全部通过 ✅')
