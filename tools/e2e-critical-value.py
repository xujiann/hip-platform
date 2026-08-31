# -*- coding: utf-8 -*-
"""v33 危急值双通道闭环 E2E：
参考区间自动判危 → 检验危急值通知开单医师 → 越权确认拦截 → 本人确认留痕 →
影像危急值复用同闭环 → 参考区间 CRUD。自成一体（自建患者/医师）。"""
import sys
from e2elib import call, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# 两位门诊医师：doc 为开单医师（危急值应通知他），other 为无关医师（不得替确认）
doc = provision_user(t, 'doc_crit_e2e', 'DOCTOR_OUTP', '开单医师危急')
other = provision_user(t, 'doc_crit_other', 'DOCTOR_OUTP', '无关医师')

# 造患者 + 挂号（admin 挂号），医师接诊并开检验+检查（医嘱归属该医师）
pid = new_patient(t, '危急闭环E2E', sex='M')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 5}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, doc), '接诊')   # doctor_id=doc
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('肝功能'), token=t), '检验项')[0]
exam = ok(call('GET', '/masterdata/charge-items?keyword=' + q('彩超'), token=t), '检查项')[0]
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1}]}, doc), '开单')  # order.doctorId=doc
lab_order = next(o for o in orders if o['orderType'] == 'LAB')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')

# ---- 1) 参考区间自动判危：结果不带 flag，血钾 2.0 由种子区间判为 LL → 触发危急值 ----
bc = ok(call('POST', f"/lis/samples?orderId={lab_order['id']}", {}, t), '采样')['barcode']
ok(call('PUT', f'/lis/samples/{bc}/receive', token=t), '核收')
ok(call('POST', f'/lis/samples/{bc}/publish', {'results': [
    {'code': 'K', 'name': '血钾', 'value': '2.0', 'unit': 'mmol/L', 'refRange': '3.5-5.5'}]}, t), '发布')  # 无 flag
print('[1] 参考区间自动判危 OK（血钾 2.0 无 flag → 判 LL）')

# ---- 2) 危急值通知开单医师：doc 的"我的待确认"应出现该危急值 ----
mine = ok(call('GET', '/outpatient/critical-alerts/my-pending', token=doc), '我的待确认')
alert = next((a for a in mine if a['source'] == 'LAB' and '血钾' in a['content']), None)
assert alert, f'开单医师应收到检验危急值: {mine}'
aid = alert['id']
print(f"[2] 检验危急值通知开单医师 OK（alert {aid}，患者 {alert['patient_name']}）")

# ---- 3) 越权确认拦截：无关医师不得替确认（7103）、处置必填（7102） ----
assert call('PUT', f'/outpatient/critical-alerts/{aid}/acknowledge', {'disposition': '替确认'}, other)['code'] == 7103, '无关医师应被拒'
assert call('PUT', f'/outpatient/critical-alerts/{aid}/acknowledge', {'disposition': '  '}, doc)['code'] == 7102, '处置必填'
assert next((a for a in ok(call('GET', '/outpatient/critical-alerts/my-pending', token=other), '无关待确认')
             if a['id'] == aid), None) is None, '无关医师不应看到该危急值'
print('[3] 越权确认拦截 OK（他人 7103 / 处置必填 7102 / 不串患者）')

# ---- 4) 开单医师本人接收确认 + 处置留痕 → 闭环，重复确认被拦 ----
ok(call('PUT', f'/outpatient/critical-alerts/{aid}/acknowledge', {'disposition': '已电话通知返院复查并补钾'}, doc), '确认')
assert next((a for a in ok(call('GET', '/outpatient/critical-alerts/my-pending', token=doc), '确认后') if a['id'] == aid), None) is None
assert call('PUT', f'/outpatient/critical-alerts/{aid}/acknowledge', {'disposition': '再确认'}, doc)['code'] == 7102, '已确认不可重复'
print('[4] 开单医师确认+处置留痕 OK（闭环，重复确认 7102）')

# ---- 5) 影像危急值复用同闭环：写报告→标危急→通知开单医师 ----
wl = ok(call('GET', '/ris/worklist', token=t), 'RIS队列')
exam_row = next(w for w in wl if w['group_no'] == next(o['groupNo'] for o in orders if o['orderType'] == 'EXAM'))
eid = exam_row['id']
assert call('PUT', f'/ris/exams/{eid}/critical', {'note': '气胸'}, t)['code'] == 9945, '未报告不能标危急'
radiologist = provision_user(t, 'radiologist_crit', 'TECHNICIAN', '放射医师')
ok(call('PUT', f'/ris/exams/{eid}/report', {'findings': '右肺野透亮度增高', 'impression': '右侧气胸'}, radiologist), '写报告')
assert call('PUT', f'/ris/exams/{eid}/critical', {'note': '  '}, t)['code'] == 9948, '危急描述必填'
ok(call('PUT', f'/ris/exams/{eid}/critical', {'note': '右侧气胸，肺压缩约 40%'}, t), '标危急')
assert call('PUT', f'/ris/exams/{eid}/critical', {'note': '再标'}, t)['code'] == 9949, '不可重复标记'
mine2 = ok(call('GET', '/outpatient/critical-alerts/my-pending', token=doc), '影像待确认')
assert next((a for a in mine2 if a['source'] == 'RIS'), None), '开单医师应收到影像危急值'
print('[5] 影像危急值复用同闭环 OK（写报告→标危急→通知开单医师；重复/未报告/空描述均拦）')

# ---- 6) 参考区间 CRUD ----
assert call('POST', '/lab-ref-ranges', {'itemCode': ''}, t)['code'] == 7104, '项目代码必填'
ok(call('POST', '/lab-ref-ranges', {'itemCode': 'E2ETROP', 'itemName': '肌钙蛋白', 'refLow': 0, 'refHigh': 0.03,
                                     'critHigh': 0.5, 'unit': 'ng/mL', 'enabled': True}, t), '建区间')
rows = ok(call('GET', '/lab-ref-ranges?itemCode=E2ETROP', token=t), '查区间')
assert len(rows) == 1
rid_rr = rows[0]['id']
ok(call('DELETE', f'/lab-ref-ranges/{rid_rr}', token=t), '删区间')
assert call('DELETE', f'/lab-ref-ranges/{rid_rr}', token=t)['code'] == 7105, '删后不存在'
mins = ok(call('GET', '/lab-ref-ranges/ack-deadline-minutes', token=t), '时限')
assert isinstance(mins, int) and mins > 0
print(f'[6] 参考区间 CRUD OK（确认时限 {mins} 分钟）')

print('\ne2e-critical-value 全部通过 ✅')
