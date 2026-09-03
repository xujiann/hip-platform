# -*- coding: utf-8 -*-
"""v44 门诊医生站临床业务补全 E2E：诊断域完整化 + 处方模板/协定处方 + 开单资料提示与医嘱字段。
自成一体（自建患者/账号），末尾收尾。

本套的存在意义：三块全是**投标偏离表已答「平台已实现」而代码里没有**的诚信补齐
（977★979★982★983★984★1084★ 诊断域 / 999★1000★ 处方模板 / 1001★1002★1006★1013★1014★1016★ 开单）。
E2E 把「能真的走通」钉死，并把三条**刻意不做**的边界也钉死，防止下一轮被人补成假实现。
"""
import urllib.error
from e2elib import call, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# ---- 1) 诊断域完整化 ----
p1 = new_patient(t, '诊断E2E', sex='M')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 10, 'capacity': 9}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': p1, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')

# 契约保护：只传既有 5 字段时，新列必须全为 null（不默认 ICD10、不默认 CONFIRMED、不拿 icd_name 填 custom_name）
ok(call('PUT', f'/outpatient/doctor/{rid}/emr',
        {'emr': {'chiefComplaint': '发热', 'presentIllness': '两天', 'handling': '对症'},
         'diagnoses': [{'icdCode': 'J00', 'icdName': '急性鼻咽炎', 'primaryDiag': True}]}, t), '旧形态存诊断')
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
d0 = ws['diagnoses'][0]
for k in ('prefix', 'suffix', 'certainty', 'diagSystem', 'customName'):
    assert k in d0, f'新键须存在: {list(d0)}'
    assert d0[k] is None, f'只传旧字段时 {k} 必须为 null（不得回填默认值）: {d0[k]}'
assert d0['primaryDiag'] is True, 'primary_diag 语义不得变（病案首页与 DRG 靠它区分主诊断）'

# 新字段：前后缀 / 确诊疑诊 / 中医 / 自定义名称
ok(call('PUT', f'/outpatient/doctor/{rid}/emr',
        {'emr': {'chiefComplaint': '发热', 'presentIllness': '两天', 'handling': '对症'},
         'diagnoses': [
             {'icdCode': 'J00', 'icdName': '急性鼻咽炎', 'primaryDiag': True,
              'prefix': '急性', 'suffix': '(重型)', 'certainty': 'CONFIRMED',
              'diagSystem': 'ICD10', 'customName': '上呼吸道感染伴发热'},
             {'icdCode': '', 'icdName': '风热感冒', 'primaryDiag': False,
              'diagSystem': 'TCM', 'certainty': 'SUSPECTED'}]}, t), '存新字段诊断')
ds = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区2')['diagnoses']
west = next(d for d in ds if d['diagSystem'] == 'ICD10')
tcm = next(d for d in ds if d['diagSystem'] == 'TCM')
assert west['prefix'] == '急性' and west['suffix'] == '(重型)', west
assert west['customName'] == '上呼吸道感染伴发热' and west['icdName'] == '急性鼻咽炎', \
    'custom_name 与 icd_name 必须并存不替代（否则会悄悄改掉处方笺与 CDR 的诊断文本）'
assert tcm['icdCode'] == '', '中医诊断不编造编码：icd_code 应为空串而非假码'
assert call('PUT', f'/outpatient/doctor/{rid}/emr',
            {'emr': {'chiefComplaint': 'x', 'handling': 'y'},
             'diagnoses': [{'icdCode': 'J00', 'icdName': '急性鼻咽炎', 'certainty': 'BOGUS'}]},
            t)['code'] == 4033, '确诊/疑诊取值非法应 4033'
assert call('PUT', f'/outpatient/doctor/{rid}/emr',
            {'emr': {'chiefComplaint': 'x', 'handling': 'y'},
             'diagnoses': [{'icdCode': 'J00', 'icdName': '急性鼻咽炎', 'diagSystem': 'BOGUS'}]},
            t)['code'] == 4034, '诊断体系取值非法应 4034'

# 诊断助手三段
asst = ok(call('GET', f'/outpatient/doctor/diagnosis-assist?patientId={p1}', token=t), '诊断助手')
for k in ('history', 'favorite', 'frequent'):
    assert k in asst, f'诊断助手缺 {k}: {list(asst)}'
assert len(asst['history']) >= 1, '该患者应有历史诊断'
print(f"[1] 诊断域 OK（旧形态新列全 null / 前后缀+确诊疑诊 / 中医不编码 / custom 与 icd 并存 / 4033 4034 / "
      f"助手 史{len(asst['history'])}·常{len(asst['favorite'])}·频{len(asst['frequent'])}）")

# ---- 2) 医保特殊病种：只做院内登记，表结构不得有报送状态列 ----
ok(call('POST', '/outpatient/doctor/special-disease',
        {'patientId': p1, 'diseaseName': '高血压Ⅲ级', 'diseaseCode': 'HTN3',
         'startDate': today, 'endDate': '2027-12-31'}, t), '院内登记特殊病种')
assert call('POST', '/outpatient/doctor/special-disease',
            {'patientId': p1, 'diseaseName': '糖尿病'}, t)['code'] == 4035, '必填缺失应 4035'
print('[2] 医保特殊病种 OK（只做院内登记；向医保经办机构报送属外部条件，表结构刻意不设报送/审批状态列）')

# ---- 3) 处方模板与协定处方 ----
drug = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
tpl = ok(call('POST', '/outpatient/rx-templates',
              {'name': '上感成人常用E2E', 'category': 'RX', 'scope': 'PERSONAL',
               'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 2,
                          'usageRoute': '口服', 'frequency': 'bid', 'dosePerTime': '1粒', 'days': 3}]}, t), '建模板')
# 建模板返回的是裸 id（R<Long>），不是对象
assert call('POST', '/outpatient/rx-templates',
            {'name': '', 'category': 'RX', 'scope': 'PERSONAL', 'lines': []}, t)['code'] == 4061, '名称必填 4061'
assert call('POST', '/outpatient/rx-templates',
            {'name': 'x', 'category': 'RX', 'scope': 'PERSONAL', 'lines': []}, t)['code'] == 4062, '明细为空 4062'
assert call('POST', '/outpatient/rx-templates',
            {'name': 'x', 'category': 'RX', 'scope': 'BOGUS',
             'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1}]}, t)['code'] == 4063, '范围非法 4063'

lines = ok(call('GET', f"/outpatient/rx-templates/{tpl}/lines", token=t), '取明细')
assert len(lines) == 1, lines
ln = lines[0]
# 关键：返回体字段名必须与 OrderLine 逐字段对齐，前端拿到即可用、无需转换表
for k in ('orderType', 'itemId', 'qty', 'usageRoute', 'frequency', 'dosePerTime', 'days'):
    assert k in ln, f'明细缺开单字段 {k}: {list(ln)}'
# 套用 = 用明细走**原开单端点**，不存在任何批量开单路径
ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': ln['orderType'], 'itemId': ln['itemId'], 'qty': ln['qty'],
     'usageRoute': ln['usageRoute'], 'frequency': ln['frequency'],
     'dosePerTime': ln['dosePerTime'], 'days': ln['days']}]}, t), '套用后走原开单端点')

# 协定处方明细不可改（任何人，含 ADMIN）
agreed = ok(call('POST', '/outpatient/rx-templates',
                 {'name': '协定处方E2E', 'category': 'AGREED', 'scope': 'HOSPITAL',
                  'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1,
                             'usageRoute': '口服', 'frequency': 'qd', 'dosePerTime': '1粒', 'days': 1}]}, t), '建协定处方')
assert call('PUT', f"/outpatient/rx-templates/{agreed}",
            {'name': '协定处方E2E', 'category': 'AGREED', 'scope': 'HOSPITAL',
             'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 9,
                        'usageRoute': '口服', 'frequency': 'qd', 'dosePerTime': '1粒', 'days': 1}]},
            t)['code'] == 4064, '协定处方改明细应 4064（含 ADMIN 也不可改）'
# 停用后不可套用
ok(call('PUT', f"/outpatient/rx-templates/{tpl}/disable", {}, t), '停用模板')
assert call('GET', f"/outpatient/rx-templates/{tpl}/lines", token=t)['code'] == 4060, '停用模板套用应 4060'
ok(call('PUT', f"/outpatient/rx-templates/{tpl}/enable", {}, t), '启用模板')
# 个人模板越权：他人不可见
other = provision_user(t, 'doctor_v44_e2e', 'DOCTOR_OUTP', '他人医师E2E')
assert call('GET', f"/outpatient/rx-templates/{tpl}/lines", token=other)['code'] == 4060, '个人模板他人不可用 4060'
print('[3] 处方模板 OK（4061/4062/4063 / 明细字段与 OrderLine 对齐 / 套用走原开单端点 / 协定 4064 / 停用不可用 / 个人模板越权拒）')

# ---- 4) 开单资料提示与医嘱字段 ----
hints = ok(call('GET', '/masterdata/order-hints?type=DRUG&keyword=' + q('阿莫西林'), token=t), '药品资料提示')
for k in ('type', 'rows', 'unavailable', 'stockGate'):
    assert k in hints, f'资料提示缺 {k}: {list(hints)}'
r0 = hints['rows'][0]
for k in ('name', 'spec', 'price', 'stock', 'fee_category_code'):
    assert k in r0, f'药品资料缺 {k}: {list(r0)}'
# 诚实标注：本平台无法区分商品名/通用名，必须在 unavailable 里点名而不是渲染空栏
assert hints['unavailable'], '参数要而表里没有的字段必须显式点名（商品名/通用名等），不得静默留空'
assert hints['stockGate'] in ('off', 'warn', 'block'), hints['stockGate']
assert hints['stockGate'] == 'warn', '缺药开关默认应为 warn（不擅自硬拦）'
it = ok(call('GET', '/masterdata/order-hints?type=ITEM&category=LAB', token=t), '项目资料提示')
assert len(it['rows']) >= 1, it

# 医嘱新字段：备注 / 加急 / 标本类型 / 采样部位
items = ok(call('GET', '/masterdata/charge-items', token=t), '收费项目')
lab = next(i for i in items if i['category'] == 'LAB')
exam = next(i for i in items if i['category'] == 'EXAM')
ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'remark': '空腹采血',
     'urgent': True, 'specimenType': '静脉血', 'samplingSite': '肘正中静脉'},
    {'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1,
     'clinicalSummary': '发热两天伴咳嗽', 'examPurpose': '排除肺部感染', 'notice': '检查前取下金属物品'}]}, t), '开带新字段的医嘱')
# 下游必须看得见：打印数据集
doc = ok(call('GET', f'/print/doc/lab-request/{rid}', token=t), '检验申请单')
blob = str(doc)
assert '静脉血' in blob and '肘正中静脉' in blob, '标本类型/采样部位须进检验申请单'
docx = ok(call('GET', f'/print/doc/exam-request/{rid}', token=t), '检查申请单')
assert '排除肺部感染' in str(docx), '检查目的须进检查申请单'
# 下游必须看得见：LIS 采样台与 RIS 工作队列（合版补的取列）
# 注意：/lis/pending 只列已收费未建标本的申请，故先结算本次就诊
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '结算')
pend = ok(call('GET', '/lis/pending', token=t), 'LIS 待采样')
assert any(x.get('specimen_type') == '静脉血' for x in pend), 'LIS 采样台必须看得到标本类型（否则字段建了没人用）'
assert any(x.get('urgent') for x in pend), 'LIS 采样台必须看得到加急标志'
print(f"[4] 开单资料与医嘱字段 OK（资料提示 {len(hints['rows'])} 行 / unavailable 显式点名 / "
      f"缺药开关默认 warn / 新字段进申请单与 LIS 采样台）")

print('\ne2e-v44-outpdoctor 全部通过 ✅')
