# -*- coding: utf-8 -*-
"""上线检查单·车道B「临床收尾三项」E2E 回归：
  ① 住院中间结算（INTERIM）与出院结算（FINAL）口径不算重；
  ② 病历复印（受理→登记→出复印件，复印件带水印/登记号/病历正文）；
  ③ 急诊抢救记录（独立于住院 ICU：录入→查看→结束）。
前提：后端运行于 localhost:8080。
"""
import time

from e2elib import call, ensure_not_admitted, find_free_bed, login, new_patient, ok, q  # noqa: E402

token = login()
stamp = str(int(time.time()))

# ==================== ① 住院中间结算：与出院结算不算重 ====================
pa = new_patient(token, '中间结算E2E' + stamp, sex='M')
ensure_not_admitted(token, pa['id'])
bed = find_free_bed(token)
icds = ok(call('GET', '/masterdata/icd10?keyword=' + q('糖尿病'), token=token), 'ICD')
adm = ok(call('POST', '/inpatient/admissions', {
    'patientId': pa['id'], 'deptId': 1, 'bedId': bed['id'],
    'diagIcd': icds[0]['code'], 'diagName': icds[0]['name'],
    'deposit': 2000, 'payMethod': 'CASH'}, token), '入院')
aid = adm['id']

drug = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=token), '药品')[0]
ok(call('POST', '/inventory/stock-in', {'drugId': drug['id'], 'qty': 50, 'batchNo': 'BINT' + stamp,
                                        'expireDate': '2028-06-30', 'supplier': '四川医药股份'}, token), '入库')

# 第一批医嘱 qty=1 → 执行 → 中间结算1
o1 = ok(call('POST', f'/inpatient/admissions/{aid}/orders',
             {'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1}]}, token), '开嘱1')[0]
ok(call('PUT', f"/inpatient/orders/{o1['id']}/execute", token=token), '执行1')
price1 = float(o1['amount'])
it1 = ok(call('POST', f'/inpatient/admissions/{aid}/interim-settle', token=token), '中间结算1')
assert it1['settleType'] == 'INTERIM', f"中间结算类型应为 INTERIM: {it1}"
assert abs(float(it1['totalAmount']) - price1) < 0.01, f"中间结算1金额应=已发生 {price1}: {it1['totalAmount']}"

# 第二批 qty=2 → 执行 → 中间结算2（只结新增，不与第一张重叠）
o2 = ok(call('POST', f'/inpatient/admissions/{aid}/orders',
             {'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 2}]}, token), '开嘱2')[0]
ok(call('PUT', f"/inpatient/orders/{o2['id']}/execute", token=token), '执行2')
price2 = float(o2['amount'])
it2 = ok(call('POST', f'/inpatient/admissions/{aid}/interim-settle', token=token), '中间结算2')
assert abs(float(it2['totalAmount']) - price2) < 0.01, \
    f"中间结算2应只结新增 {price2}，不含第一张已认领的费用: {it2['totalAmount']}"

hist = ok(call('GET', f'/inpatient/admissions/{aid}/interim-settlements', token=token), '中间结算历史')
assert len(hist) == 2, f"应有两条中间结算: {len(hist)}"

# 出院结算：总额=全账单(price1+price2)，未被中间结算行抬高（关键：不算重）
fin = ok(call('POST', f'/inpatient/admissions/{aid}/discharge?payMethod=CASH', {}, token), '出院结算')
assert fin['settleType'] == 'FINAL', f"出院结算类型应为 FINAL: {fin}"
assert abs(float(fin['totalAmount']) - (price1 + price2)) < 0.01, \
    f"出院总额应=全账单 {price1 + price2}，不被中间结算抬高: {fin['totalAmount']}"
print(f"[①中间结算] it1={it1['totalAmount']} it2={it2['totalAmount']} "
      f"出院={fin['totalAmount']}（=账单{price1 + price2}，Σ中间是其子集，不算重）OK")

# ==================== ② 病历复印 ====================
# 给上面这份（已出院）病案补一条病历正文，作为复印内容
ok(call('POST', f'/inpatient/admissions/{aid}/records',
        {'recordType': 'ADMISSION', 'title': '入院记录', 'content': '主诉：发热咳嗽3天，为E2E复印测试。'}, token), '写病历')

cp = ok(call('POST', '/quality/emr-copy', {
    'admissionId': aid, 'applicantName': '患者本人', 'applicantRelation': 'SELF',
    'applicantIdNo': '110101199001010000', 'copyScope': '全部病历', 'purpose': '医保报销', 'copies': 2}, token), '复印受理')
cid = cp['id']

# 未登记不出件（9812）
und = call('GET', f'/quality/emr-copy/{cid}/document', token=token)
assert und['code'] == 9812, f"未登记应拒出复印件 9812: {und}"

rg = ok(call('PUT', f'/quality/emr-copy/{cid}/register', token=token), '复印登记')
assert str(rg['regNo']).startswith('FY'), f"复印登记号前缀应为 FY: {rg}"

doc = ok(call('GET', f'/quality/emr-copy/{cid}/document', token=token), '复印件数据集')
assert doc['watermark'] == '复印件', f"复印件水印文案应为'复印件': {doc.get('watermark')}"
assert doc['request']['reg_no'] == rg['regNo'], "复印件须带复印登记号（法定留痕）"
assert len(doc['records']) >= 1, "复印件须含病历正文"
ok(call('PUT', f'/quality/emr-copy/{cid}/issue', token=token), '出复印件')
print(f"[②病历复印] 登记号={rg['regNo']} 水印={doc['watermark']} 病历{len(doc['records'])}条 → 已出件 OK")

# ==================== ③ 急诊抢救记录 ====================
tri = ok(call('POST', '/outpatient/triage',
              {'patientName': '抢救E2E' + stamp, 'level': 1, 'chiefComplaint': '胸痛待查'}, token), '分诊')
tid = tri['id']

# GCS 越界拦截（4567）
bad = call('POST', '/outpatient/er-rescue', {'triageId': tid, 'gcs': 20, 'measures': 'x'}, token)
assert bad['code'] == 4567, f"GCS 越界应 4567: {bad}"

r = ok(call('POST', '/outpatient/er-rescue', {
    'triageId': tid, 'gcs': 8, 'pulse': 110, 'measures': '心肺复苏、气管插管',
    'participants': '张医生/李护士'}, token), '抢救录入')
rid = r['id']
recs = ok(call('GET', f'/outpatient/er-rescue?triageId={tid}', token=token), '抢救记录列表')
mine = next(x for x in recs if x['id'] == rid)
assert mine['patient_name'] == '抢救E2E' + stamp, "抢救记录患者名应由分诊带出"
assert mine['outcome'] == 'ONGOING', "缺省转归应为进行中"
assert mine['gcs'] == 8, "GCS 应落库"

ok(call('PUT', f'/outpatient/er-rescue/{rid}/end', {'outcome': 'SUCCESS', 'note': '复苏成功'}, token), '抢救结束')
after = next(x for x in ok(call('GET', f'/outpatient/er-rescue?triageId={tid}', token=token), '复查')
             if x['id'] == rid)
assert after['outcome'] == 'SUCCESS' and after['rescue_end'], "结束应落转归与结束时间"
print(f"[③急诊抢救] rid={rid} 患者={after['patient_name']} 转归={after['outcome']} OK")

print('\n车道B 临床收尾三项 E2E 全部通过 ✅')
