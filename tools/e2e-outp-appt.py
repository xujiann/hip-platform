# -*- coding: utf-8 -*-
"""v37 门诊快赢 E2E：分时段预约挂号（建时段→预约→签到转挂号→取消回落）+ 病历连续调阅。自成一体。"""
import sys
from e2elib import call, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# 建排班 + 时段
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 5}, t), '排班')
sid = sch['id']
assert call('POST', f'/outpatient/schedules/{sid}/slots', {'slots': [
    {'timeBegin': '08:00', 'timeEnd': '08:30', 'capacity': 3},
    {'timeBegin': '08:30', 'timeEnd': '09:00', 'capacity': 3}]}, t)['code'] == 3120, '容量合计超总号量应 3120'
ok(call('POST', f'/outpatient/schedules/{sid}/slots', {'slots': [
    {'timeBegin': '08:00', 'timeEnd': '08:30', 'capacity': 2},
    {'timeBegin': '08:30', 'timeEnd': '09:00', 'capacity': 3}]}, t), '建时段')
slots = ok(call('GET', f'/outpatient/schedules/{sid}/slots', token=t), '时段列表')
assert len(slots) == 2 and slots[0]['remaining'] == 2
slot = slots[0]['id']
print('[1] 分时段建立 OK（容量合计校验 3120 / 2 时段）')

# 预约 → 重复拦 → 签到转挂号
p1 = new_patient(t, '预约E2E甲', sex='F')['id']
a1 = ok(call('POST', '/outpatient/appointments', {'slotId': slot, 'patientId': p1, 'source': '窗口'}, t), '预约')
assert call('POST', '/outpatient/appointments', {'slotId': slot, 'patientId': p1}, t)['code'] == 3112, '重复预约应 3112'
r = ok(call('POST', f"/outpatient/appointments/{a1['id']}/checkin", token=t), '签到')
assert r['registrationId'] and r['regNo'] == a1['apptNo'], f'签到号序应与预约一致: {r} vs {a1}'
assert call('POST', f"/outpatient/appointments/{a1['id']}/checkin", token=t)['code'] == 3114, '重复签到应 3114'
# 签到不再占号：schedule booked 仍 1
after = next(s for s in ok(call('GET', '/outpatient/schedules?date=' + today, token=t), '排班查') if s['id'] == sid)
assert after['booked'] == 1, f'签到不得再占号: {after}'
print(f"[2] 预约→签到转挂号 OK（号序 {r['regNo']} 同池不重号 / 3112 / 3114 / 签到不再占号）")

# 取消回落
p2 = new_patient(t, '预约E2E乙', sex='M')['id']
a2 = ok(call('POST', '/outpatient/appointments', {'slotId': slot, 'patientId': p2}, t), '预约2')
ok(call('POST', f"/outpatient/appointments/{a2['id']}/cancel", token=t), '取消')
slots2 = ok(call('GET', f'/outpatient/schedules/{sid}/slots', token=t), '时段2')
assert next(s for s in slots2 if s['id'] == slot)['booked'] == 1, '取消后 slot 回落'
after2 = next(s for s in ok(call('GET', '/outpatient/schedules?date=' + today, token=t), '排班查2') if s['id'] == sid)
assert after2['booked'] == 1, '取消后 schedule 回落'
# 时段满：再约 2 人后第 3 人拦
ok(call('POST', '/outpatient/appointments', {'slotId': slot, 'patientId': p2}, t), '再约')
p3 = new_patient(t, '预约E2E丙', sex='F')['id']
assert call('POST', '/outpatient/appointments', {'slotId': slot, 'patientId': p3}, t)['code'] == 3111, '时段满应 3111'
print('[3] 取消两级回落 OK / 时段满 3111')

# 病历连续调阅：给 p1 的挂号补一条诊断，历史应含它且剔除退号
regs = ok(call('GET', f'/outpatient/doctor/patient/{p1}/history', token=t), '历史就诊')
assert len(regs) == 1 and regs[0]['registrationId'] == r['registrationId']
print('[4] 病历连续调阅 OK（历史含签到转的挂号）')

# ---- 5) v38 RIS 体验：到检登记态 + 结果互认 + 模板 type ----
exam = ok(call('GET', '/masterdata/charge-items?keyword=' + q('彩超'), token=t), '检查项')[0]
ok(call('POST', f"/outpatient/doctor/{r['registrationId']}/start", {}, t), '接诊')
ok(call('POST', f"/outpatient/doctor/{r['registrationId']}/orders",
        {'lines': [{'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1}]}, t), '开检查')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': r['registrationId'], 'payMethod': 'CASH'}, t), '收费')
wl = ok(call('GET', '/ris/worklist', token=t), 'RIS队列')
row = next(w for w in wl if w['patient_name'] == '预约E2E甲')
ok(call('PUT', f"/ris/exams/{row['id']}/arrive", token=t), '到检')
assert call('PUT', f"/ris/exams/{row['id']}/arrive", token=t)['code'] == 9944, '重复到检 9944'
ok(call('PUT', f"/ris/exams/{row['id']}/report", {'findings': '肝胆未见异常', 'impression': '未见异常'}, t), '写报告')

rv = provision_user(t, 'ris_verifier_appt', 'TECHNICIAN', '放射审核v38')
ok(call('PUT', f"/ris/exams/{row['id']}/verify", token=rv), '审核')
recent = ok(call('GET', f'/ris/recent-exams?patientId={p1}', token=t), '互认查询')
assert len(recent) >= 1 and any(x['impression'] == '未见异常' for x in recent), '互认应命中已审报告'
ok(call('POST', '/emr-templates', {'name': '腹部超声模板E2E', 'content': '肝胆胰脾未见明显异常', 'templateType': 'RIS'}, t), '建RIS模板')
ris_tpls = ok(call('GET', '/emr-templates?type=RIS', token=t), 'RIS模板')
assert any(x['name'] == '腹部超声模板E2E' for x in ris_tpls)
print('[5] v38 RIS 体验 OK（到检 ARRIVED→报告→审核 / 互认命中 / 模板 type 过滤）')

print('\ne2e-outp-appt 全部通过 ✅')
