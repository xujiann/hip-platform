# -*- coding: utf-8 -*-
"""1.2.13 门诊/病历侧收尾环 E2E：
  阻塞6 断货医生开单预警（开单不拦，返回值带库存预警标志）
  阻塞4 病历签名后合规补正（门诊 + 住院；签名冻结不改原文，追加留痕补正）
  阻塞7 病案首页组装（患者/入出院/诊断主+其他/手术/按类费用）
自建患者、自入自出，可重复执行。前提：后端运行于 localhost:8080。"""
from e2elib import call, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# ============ 阻塞6：断货医生开单预警 ============
amx = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
blf = ok(call('GET', '/masterdata/drugs?keyword=' + q('布洛芬'), token=t), '药品')[0]
pa = new_patient(t, '收尾环甲', sex='M')
sch = ok(call('POST', '/outpatient/schedules',
             {'deptId': 1, 'scheduleDate': today, 'fee': 10, 'capacity': 30}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': pa['id'], 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')

# amx 开量远超库存 → 预警；分两次开避开同方 CDSS 交互
warn_orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': amx['stock'] + 10,
     'usageRoute': '口服', 'frequency': 'tid', 'dosePerTime': '1粒', 'days': 3}]}, t), '开断货药')
amx_line = next(o for o in warn_orders if o['itemId'] == amx['id'])
assert amx_line.get('stockWarnAvailable') == amx['stock'], \
    f'断货药应带库存预警，余量应={amx["stock"]}: {amx_line}'

# blf 库存充足、开量 1 → 不预警（未硬拦、无预警标志）
if blf['stock'] >= 1:
    ok_orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
        {'orderType': 'DRUG', 'itemId': blf['id'], 'qty': 1,
         'usageRoute': '口服', 'frequency': 'bid', 'dosePerTime': '1片', 'days': 2}]}, t), '开充足药')
    blf_line = next(o for o in ok_orders if o['itemId'] == blf['id'])
    assert blf_line.get('stockWarnAvailable') is None, f'库存充足不应预警: {blf_line}'
print(f"[阻塞6] 断货开单预警 OK：{amx['name']} 开{amx['stock'] + 10} 余{amx['stock']} 已预警，开单未被拦截")

# ============ 阻塞4：门诊病历签名后补正 ============
icds = ok(call('GET', '/masterdata/icd10?keyword=' + q('上呼吸道'), token=t), 'ICD')
ok(call('PUT', f'/outpatient/doctor/{rid}/emr', {
    'emr': {'chiefComplaint': '咽痛发热2天', 'presentIllness': '受凉后咽痛', 'pastHistory': '无',
            'physicalExam': '咽充血', 'advice': '口服药'},
    'diagnoses': [{'icdCode': icds[0]['code'], 'icdName': icds[0]['name']}]}, t), '病历')
# 未签名时补正应被拒（4018）——未签名请直接改
r = call('POST', f'/outpatient/doctor/{rid}/emr/amend', {'amendText': 'x', 'reason': 'y'}, t)
assert r['code'] == 4018, f'未签名不应允许补正: {r}'
ok(call('POST', f'/outpatient/doctor/{rid}/emr/sign', {}, t), '签名')
# 签名后原文冻结（saveEmr 4008），但可追加补正
r = call('PUT', f'/outpatient/doctor/{rid}/emr', {'emr': {'chiefComplaint': '改'}, 'diagnoses': []}, t)
assert r['code'] == 4008, f'签名后原文应冻结: {r}'
ok(call('POST', f'/outpatient/doctor/{rid}/emr/amend',
        {'amendText': '主诉应为"咽痛发热3天"', 'reason': '录入笔误'}, t), '补正')
ams = ok(call('GET', f'/outpatient/doctor/{rid}/emr/amendments', token=t), '补正历史')
assert len(ams) == 1 and ams[0]['reason'] == '录入笔误', f'补正应留痕: {ams}'
print(f"[阻塞4·门诊] 签名冻结+补正留痕 OK（补正 {len(ams)} 条，补正人={ams[0].get('amended_by_name')}）")

# ============ 阻塞4 住院补正 + 阻塞7 病案首页 ============
ward = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING'][0]
free = next(b for b in ok(call('GET', f"/inpatient/beds?wardId={ward['id']}", token=t), '床位')
            if b['status'] == 'FREE')
pb = new_patient(t, '收尾环乙', sex='F')
adm = ok(call('POST', '/inpatient/admissions',
             {'patientId': pb['id'], 'deptId': 1, 'bedId': free['id'],
              'deposit': 2000, 'payMethod': 'CASH'}, t), '入院')
aid = adm['id']

rcid = ok(call('POST', f'/inpatient/admissions/{aid}/records',
              {'recordType': 'ADMISSION', 'title': '入院记录', 'content': '患者空腹血糖偏高。'}, t), '病历')['id']
r = call('POST', f'/inpatient/admissions/{aid}/records/{rcid}/amend', {'amendText': 'x', 'reason': 'y'}, t)
assert r['code'] == 9108, f'未签名不应允许补正: {r}'
ok(call('POST', f'/inpatient/admissions/{aid}/records/{rcid}/sign', {}, t), '签名')
ok(call('POST', f'/inpatient/admissions/{aid}/records/{rcid}/amend',
        {'amendText': '应为"空腹血糖 9.8mmol/L"', 'reason': '数值补充'}, t), '补正')
iams = ok(call('GET', f'/inpatient/admissions/{aid}/records/{rcid}/amendments', token=t), '住院补正历史')
assert len(iams) == 1, f'住院补正应留痕: {iams}'
print(f"[阻塞4·住院] 签名冻结+补正留痕 OK（补正 {len(iams)} 条）")

# 其他诊断 + 出院主诊断 + 药嘱执行（产生费用）
ok(call('POST', '/inpatient/diagnoses', {'admissionId': aid, 'icd': 'E11.9', 'name': '2型糖尿病'}, t), '其他诊断')
ok(call('PUT', f'/inpatient/admissions/{aid}/discharge-diag',
        {'icd': 'J06.9', 'name': '急性上呼吸道感染'}, t), '出院诊断')
ok(call('POST', f'/inpatient/admissions/{aid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1,
     'usageRoute': '口服', 'frequency': 'bid', 'dosePerTime': '1粒'}]}, t), '药嘱')
oid = next(p['orderId'] for p in ok(call('GET', '/inpatient/orders/pending', token=t), '待执行')
           if p['admissionNo'] == adm['admissionNo'])
ok(call('PUT', f'/inpatient/orders/{oid}/execute', {}, t), '执行')
ok(call('POST', f'/inpatient/admissions/{aid}/discharge', token=t), '出院')

# 病案首页组装断言
fp = ok(call('GET', f'/inpatient/admissions/{aid}/front-page', token=t), '病案首页')
assert fp['diagnoses']['primary']['icd'] == 'J06.9' \
    and fp['diagnoses']['primary']['source'] == 'DISCHARGE', f'主诊断应取出院诊断: {fp["diagnoses"]}'
assert any(d['icd'] == 'E11.9' for d in fp['diagnoses']['others']), f'其他诊断应含 E11.9: {fp["diagnoses"]}'
assert any(c['order_type'] == 'DRUG' for c in fp['fees']['byCategory']), f'费用应按类含药品费: {fp["fees"]}'
assert fp['total_amount'] is not None, f'向后兼容 total_amount 应在: {fp}'
assert fp['patient']['name'] == '收尾环乙', f'患者基本信息应组装: {fp["patient"]}'
print(f"[阻塞7] 病案首页组装 OK：主诊断「{fp['diagnoses']['primary']['name']}」，"
      f"其他诊断 {len(fp['diagnoses']['others'])} 条，费用类目 {len(fp['fees']['byCategory'])} 项，总额 ¥{fp['total_amount']}")

# 病案检索选单
mr = ok(call('GET', '/quality/med-records?keyword=' + q(adm['admissionNo']), token=t), '病案检索')
assert any(m['id'] == aid for m in mr), '病案检索应能按住院号命中'
print(f"[阻塞7] 病案检索选单 OK（命中 {len(mr)} 条）")

print('\n=== 门诊/病历侧收尾环 E2E（断货预警 + 补正留痕 + 病案首页）全部通过 ✔ ===')
