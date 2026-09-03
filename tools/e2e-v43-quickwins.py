# -*- coding: utf-8 -*-
"""v43 ★快赢第一批 E2E：门诊病历签名入口 + 五种日常单据打印 + 药品启停 + 住院多维检索 + 皮试回诊室。
自成一体（自建患者/账号），末尾收尾。

本套的存在意义：五项全部是**投标偏离表已答「平台已实现」而代码里没有入口或没有功能**的诚信补齐
（991★ 签名入口 / 1026★ 五种单据 / 1162★ 药品启停 / 2012★2013★2028★ 住院检索 / 1050 皮试回诊室）。
E2E 把「能真的走通」钉死，避免下一轮又只剩一句应答。
"""
from e2elib import call, find_free_bed, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# ---- 1) 门诊病历签名入口（991★）：签名 → 冻结 → 补正路径才可达 ----
p1 = new_patient(t, '签名E2E', sex='M')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 10, 'capacity': 9}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': p1, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
# 未书写过病历：走既有 4009（不是新码——车道A 刻意不为既有语义另造同义码）
assert call('POST', f'/outpatient/doctor/{rid}/emr/sign', {}, t)['code'] == 4009, '未书写应 4009'
# 病历存在但五段正文全空：这才是新增的 4022
ok(call('PUT', f'/outpatient/doctor/{rid}/emr', {'emr': {
    'chiefComplaint': '', 'presentIllness': '', 'pastHistory': '', 'physicalExam': '', 'handling': ''}}, t), '存空病历')
assert call('POST', f'/outpatient/doctor/{rid}/emr/sign', {}, t)['code'] == 4022, '正文全空应 4022'
ok(call('PUT', f'/outpatient/doctor/{rid}/emr', {'emr': {
    'chiefComplaint': '发热咳嗽 3 天', 'presentIllness': '3 天前受凉后发热',
    'pastHistory': '无特殊', 'physicalExam': 'T38.5 咽充血', 'handling': '对症处理'}}, t), '写病历')
sig = ok(call('POST', f'/outpatient/doctor/{rid}/emr/sign', {}, t), '签名')
assert set(sig.keys()) == {'signature', 'signedAt'}, f'签名返回体契约不得变: {sig}'
# 重复签名被拒（既有 4010，未造同义新码）
assert call('POST', f'/outpatient/doctor/{rid}/emr/sign', {}, t)['code'] == 4010, '重复签名应 4010'
# 本条的真实价值：签名后补正路径才可达（此前前端无按钮，这条路永远走不到）
ok(call('POST', f'/outpatient/doctor/{rid}/emr/amend',
        {'amendText': '补充：血常规回报白细胞正常', 'reason': '结果回报后补充'}, t), '补正')
am = ok(call('GET', f'/outpatient/doctor/{rid}/emr/amendments', token=t), '补正历史')
assert len(am) >= 1, f'补正应留痕: {am}'
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
assert ws.get('emrSignerName'), '工作区应带出签名人（冻结态展示用）'
print(f"[1] 门诊病历签名 OK（空病历 4022 / 签名返回体契约不变 / 重复 4010 / **签名后补正路径才可达** / 签名人 {ws['emrSignerName']}）")

# ---- 2) 五种日常单据打印（1026★）----
drug = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
items = ok(call('GET', '/masterdata/charge-items', token=t), '收费项目')
lab = next(i for i in items if i['category'] == 'LAB')
exam = next(i for i in items if i['category'] == 'EXAM')
treat = next(i for i in items if i['category'] == 'TREAT')
ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 2, 'usageRoute': '口服', 'frequency': 'bid', 'dosePerTime': '1粒'},
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1},
    {'orderType': 'TREAT', 'itemId': treat['id'], 'qty': 1}]}, t), '开四类医嘱')
for dt, name in (('prescription', '处方笺'), ('lab-request', '检验申请单'), ('exam-request', '检查申请单'),
                 ('treat-sheet', '治疗单'), ('guide-sheet', '导诊单')):
    doc = ok(call('GET', f'/print/doc/{dt}/{rid}', token=t), name)
    # 返回体为扁平结构：页眉字段 + docType/docTitle + groups（一个单据号=一张纸）
    for k in ('patient_name', 'patient_no', 'dept_name', 'doctor_name', 'docType', 'docTitle', 'rows'):
        assert k in doc, f'{name} 缺 {k}: {list(doc)}'
    assert doc['docType'] == dt, f'{name} docType 应为 {dt}: {doc["docType"]}'
    if dt == 'guide-sheet':
        # 导诊单是一张汇总纸，不按单据号分张（只挂号未开单也要拿它找诊室，故不强求有明细）
        assert 'groups' not in doc, f'导诊单不应分组: {list(doc)}'
    else:
        # 其余四种一个 group_no = 一张纸；本次就诊四类医嘱都开了，各自应有分组与明细
        assert len(doc.get('groups') or []) >= 1, f'{name} 应有单据分组: {doc.get("groups")}'
        assert len(doc['rows']) >= 1, f'{name} 应有明细行: {doc["rows"]}'
assert call('GET', f'/print/doc/bogus-type/{rid}', token=t)['code'] == 4892, '类型不支持应 4892'
assert call('GET', '/print/doc/prescription/99999999', token=t)['code'] == 4893, '单据不存在应 4893'
print('[2] 五种日常单据打印 OK（处方笺/检验/检查/治疗/导诊 页眉齐 / 4892 类型 / 4893 不存在）')

# ---- 3) 药品启用停用（1162★）----
d2 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品2')[0]
assert call('PUT', f"/masterdata/drugs/{d2['id']}/disable", {}, t)['code'] == 8015, '停用原因必填 8015'
ok(call('PUT', f"/masterdata/drugs/{d2['id']}/disable", {'reason': '厂家召回批次核查'}, t), '停用')
assert call('PUT', f"/masterdata/drugs/{d2['id']}/disable", {'reason': 'x'}, t)['code'] == 8014, '重复停用 8014'
assert call('PUT', '/masterdata/drugs/99999999/disable', {'reason': 'x'}, t)['code'] == 8013, '药品不存在 8013'
# 停用药品不可开单（8016）——且必须零副作用
p3 = new_patient(t, '停药E2E', sex='F')['id']
rid3 = ok(call('POST', '/outpatient/registrations', {'patientId': p3, 'scheduleId': sch['id']}, t), '挂号3')['id']
ok(call('POST', f'/outpatient/doctor/{rid3}/start', {}, t), '接诊3')
before = ok(call('GET', f'/outpatient/doctor/{rid3}/workspace', token=t), '开单前')
assert call('POST', f'/outpatient/doctor/{rid3}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': d2['id'], 'qty': 1, 'usageRoute': '口服',
     'frequency': 'qd', 'dosePerTime': '1粒'}]}, t)['code'] == 8016, '停用药开单应 8016'
after = ok(call('GET', f'/outpatient/doctor/{rid3}/workspace', token=t), '开单后')
assert len(after.get('orders') or []) == len(before.get('orders') or []), '被拒时不得落任何订单（零副作用）'
# 默认列表口径不变：停用药不得回到开单选择器
lst = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '默认列表')
assert all(x['id'] != d2['id'] for x in lst), '默认列表仍应仅返回启用中（开单选择器唯一数据源）'
allp = ok(call('GET', '/masterdata/drugs?all=true&keyword=' + q('阿莫西林'), token=t), '维护页列表')
assert any(x['id'] == d2['id'] for x in allp), 'all=true 应能看到停用药'
ok(call('PUT', f"/masterdata/drugs/{d2['id']}/enable", {}, t), '启用')
ok(call('POST', f'/outpatient/doctor/{rid3}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': d2['id'], 'qty': 1, 'usageRoute': '口服',
     'frequency': 'qd', 'dosePerTime': '1粒'}]}, t), '启用后可开单')
print('[3] 药品启停 OK（8015 原因 / 8014 重复 / 8013 不存在 / 8016 停用不可开单且零副作用 / 默认口径不变 / 启用后恢复）')

# ---- 4) 住院多维检索（2012★/2013★/2028★）----
doc_user = provision_user(t, 'doctor_v43_e2e', 'DOCTOR_OUTP', '主管医师E2E')
me = ok(call('GET', '/auth/me', token=doc_user), '登录态')
p4 = new_patient(t, '检索E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': p4, 'deptId': 2, 'bedId': free['id'], 'deposit': 300, 'payMethod': 'CASH'}, t), '入院')
admid = adm['id']
# 不传参 = 旧行为（本套最要紧的兼容断言）
base = ok(call('GET', '/inpatient/admissions', token=t), '不传参')
assert isinstance(base, list) and len(base) >= 1, base
assert 'admissionNo' in base[0] and 'patientName' in base[0], f'既有键必须在: {list(base[0])}'
# 设主管医生 → mine=true 只回本人主管
ok(call('PUT', f'/inpatient/admissions/{admid}/attending-doctor',
        {'doctorId': me['id']}, t), '设主管医生')
mine = ok(call('GET', '/inpatient/admissions?mine=true', token=doc_user), '我的病人')
assert any(a['id'] == admid for a in mine), '应能查到本人主管患者'
assert all(a.get('doctorId') == me['id'] for a in mine), 'mine 只应返回本人主管'
# 各过滤条件
ok(call('GET', '/inpatient/admissions?deptId=2', token=t), '按科室')
ok(call('GET', f"/inpatient/admissions?keyword={q('检索E2E')}", token=t), '按关键词')
assert call('GET', f"/inpatient/admissions?careLevel={q('超级')}", token=t)['code'] == 4881, '护理级别非法 4881'
assert call('GET', '/inpatient/admissions?deptId=-1', token=t)['code'] == 4880, '检索条件非法 4880'
# 注入安全：关键词里的通配符必须被转义
esc = ok(call('GET', '/inpatient/admissions?keyword=%25', token=t), '通配符转义')
assert len(esc) == 0, '「%」未转义会退化成匹配全部'
# 医嘱跨患者检索
drug2 = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
ok(call('POST', f'/inpatient/admissions/{admid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug2['id'], 'qty': 1, 'usageRoute': '口服',
     'frequency': 'qd', 'dosePerTime': '1粒'}]}, t), '开住院医嘱')
srch = ok(call('GET', '/inpatient/orders/search?keyword=' + q('阿莫西林'), token=t), '医嘱检索')
for k in ('items', 'total', 'limit', 'truncated'):
    assert k in srch, f'医嘱检索缺 {k}: {list(srch)}'
assert len(srch['items']) >= 1, '应命中刚开的医嘱'
assert srch['truncated'] is False and srch['limit'] == 200, f'未超限时不应标截断: {srch["truncated"]}/{srch["limit"]}'
assert call('GET', '/inpatient/orders/search', token=t)['code'] == 4880, '零条件应 4880'
print(f"[4] 住院多维检索 OK（不传参=旧行为 / mine 我的病人 / 4881 级别 / 4880 条件 / 通配符转义 / 医嘱检索 {len(srch['items'])} 行）")

# ---- 5) 皮试结果回诊室（1050）：医生只读，写权限不放 ----
st = ok(call('GET', f'/outpatient/nurse/skin-tests/for-doctor?registrationId={rid}', token=doc_user), '医生读皮试')
assert isinstance(st, list), '应返回列表'
# 写权限必须仍然挡住医生。注意：Spring Security 在方法级拒绝时直接返 HTTP 403，
# 不走 R.fail 包装，e2elib.call 会抛 HTTPError——所以这里必须捕获而不是看返回码。
import urllib.error
denied = False
try:
    call('POST', '/outpatient/nurse/skin-tests', {'registrationId': rid, 'drugName': '青霉素'}, doc_user)
except urllib.error.HTTPError as e:
    denied = e.code == 403
assert denied, '医生不得有皮试写权限（只放开读）'
print('[5] 皮试回诊室 OK（医生可读 / 写权限仍挡住医生）')

print('\ne2e-v43-quickwins 全部通过 ✅')
