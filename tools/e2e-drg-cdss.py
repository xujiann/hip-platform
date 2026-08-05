# -*- coding: utf-8 -*-
"""二十八期 E2E：DRG 分组器（入组/CMI/费用消耗指数）+ CDSS 规则（DDI/疗程/年龄/诊断建议）"""
import datetime
import json
import sys
import urllib.parse
import urllib.request
from e2elib import BASE, call, login, ok, q  # noqa: E402



t = login()
today = datetime.date.today().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# CDSS 需要开限制级抗菌药（头孢/左氧），先授 2 级处方权
ok(call('PUT', '/outpatient/abx-privileges/1?level=2', token=t), '授权2级')


def visit(name, birth=None):
    body = {'name': name, 'sex': 'M'}
    if birth:
        body['birthDate'] = birth
    p = ok(call('POST', '/patients', body, t), '建患者')
    r = ok(call('POST', '/outpatient/registrations', {'patientId': p['id'], 'scheduleId': sch['id']}, t), '挂号')
    ok(call('POST', f"/outpatient/doctor/{r['id']}/start", {}, t), '接诊')
    return p, r['id']


def drug(kw):
    return ok(call('GET', '/masterdata/drugs?keyword=' + q(kw), token=t), '药品')[0]


sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 30}, t), '排班')

# ---- CDSS：DDI 禁用拦截（头孢 + 含乙醇制剂） ----
_, rid1 = visit('CDSS禁用' + stamp)
r = call('POST', f'/outpatient/doctor/{rid1}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug('藿香正气')['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'tid',
     'dosePerTime': '1支', 'days': 3},
    {'orderType': 'DRUG', 'itemId': drug('头孢克肟')['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1片', 'days': 3}]}, t)
assert r['code'] == 4015, f'双硫仑组合应 FORBID 拦截: {r}'
print(f"[CDSS-1] DDI 禁用拦截 OK（4015：{r['message'][:40]}…）")

# ---- CDSS：DDI 提醒留痕（左氧氟沙星 + 布洛芬）+ 疗程超限 ----
_, rid2 = visit('CDSS提醒' + stamp)
o_blf = ok(call('POST', f'/outpatient/doctor/{rid2}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug('布洛芬')['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1粒', 'days': 7}]}, t), '开布洛芬7天')
o_lvx = ok(call('POST', f'/outpatient/doctor/{rid2}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug('左氧氟沙星')['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'qd',
     'dosePerTime': '1片', 'days': 7}]}, t), '开左氧')
alerts = ok(call('GET', '/cdss/alerts', token=t), '提醒')
mine = [a for a in alerts if a['registration_id'] == rid2]
assert any(a['rule_type'] == 'DOSE' for a in mine), f'应有疗程提醒: {mine}'
assert any(a['rule_type'] == 'DDI' for a in mine), f'应有 DDI 提醒: {mine}'
print(f"[CDSS-2] CAUTION 提醒留痕 OK（DOSE 疗程超限 + DDI 喹诺酮×NSAIDs，共 {len(mine)} 条不拦截）")

# ---- CDSS：年龄禁忌拦截（未成年人喹诺酮） ----
_, rid3 = visit('CDSS未成年' + stamp, birth='2016-05-01')
r = call('POST', f'/outpatient/doctor/{rid3}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': drug('左氧氟沙星')['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'qd',
     'dosePerTime': '1片', 'days': 5}]}, t)
assert r['code'] == 4017, f'未成年人喹诺酮应拦截: {r}'
print(f"[CDSS-3] 年龄禁忌拦截 OK（4017：{r['message'][:40]}…）")

# ---- CDSS：诊断建议 + 规则维护 ----
sugg = ok(call('GET', '/cdss/suggestions?icd=J15.900', token=t), '建议')
assert len(sugg) >= 1 and '胸部DR' in sugg[0]['content']
rules_before = len(ok(call('GET', '/cdss/rules', token=t), '规则')['ddi'])
ok(call('POST', '/cdss/ddi-rules', {'drugA': 'E2E药A' + stamp, 'drugB': 'E2E药B' + stamp,
                                    'severity': 'CAUTION', 'message': 'E2E 测试规则'}, t), '加规则')
rules_after = len(ok(call('GET', '/cdss/rules', token=t), '规则2')['ddi'])
assert rules_after == rules_before + 1
print(f"[CDSS-4] 诊断建议 OK（J15.900 命中肺炎路径提示）；DDI 规则可维护（{rules_before}→{rules_after}）")

# ---- DRG：四类病例入组 ----
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']


def admit_discharge(patient_id, icd, name, surgery=False):
    free = None
    for w in wards:
        beds = ok(call('GET', f"/inpatient/beds?wardId={w['id']}", token=t), '床')
        free = next((b for b in beds if b['status'] == 'FREE'), None)
        if free:
            break
    assert free, '无空床'
    adm = ok(call('POST', '/inpatient/admissions', {'patientId': patient_id, 'deptId': 2, 'bedId': free['id'],
                                                    'diagIcd': icd, 'diagName': name,
                                                    'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
    if surgery:
        ok(call('POST', '/inpatient/surgeries', {'admissionId': adm['id'], 'procedureName': '经皮冠状动脉支架植入术',
                                                 'anesthesiaType': '局部麻醉', 'scheduledAt': None}, t), '手术')
    ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge", {}, t), '出院')
    return adm


p1 = ok(call('POST', '/patients', {'name': 'DRG手术' + stamp, 'sex': 'M'}, t), '患者1')
p2 = ok(call('POST', '/patients', {'name': 'DRG内科' + stamp, 'sex': 'F'}, t), '患者2')
p3 = ok(call('POST', '/patients', {'name': 'DRG肺炎' + stamp, 'sex': 'M'}, t), '患者3')
p4 = ok(call('POST', '/patients', {'name': 'DRG歧义' + stamp, 'sex': 'F'}, t), '患者4')
a1 = admit_discharge(p1['id'], 'I21.0', '急性心肌梗死', surgery=True)   # → FM19 手术组
a2 = admit_discharge(p2['id'], 'I21.9', '急性心肌梗死', surgery=False)  # → FR41 内科组
a3 = admit_discharge(p3['id'], 'J15.9', '细菌性肺炎')                    # → ES31
a4 = admit_discharge(p4['id'], 'Z99.9', '未定义诊断')                    # → QY

res = ok(call('POST', '/drg/group-all', {}, t), '批量入组')
assert res['grouped'] >= 3 and res['ambiguous'] >= 1, res
cases = ok(call('GET', '/drg/cases', token=t), '入组明细')
by_adm = {c['admission_no']: c for c in cases}
# 无其他诊断 → 尾码 5（不伴并发症）
assert by_adm[a1['admissionNo']]['drg_code'] == 'FM15', by_adm[a1['admissionNo']]
assert by_adm[a2['admissionNo']]['drg_code'] == 'FR45', by_adm[a2['admissionNo']]
assert by_adm[a3['admissionNo']]['drg_code'] == 'ES35', by_adm[a3['admissionNo']]
assert by_adm[a4['admissionNo']]['drg_code'] == 'QY', by_adm[a4['admissionNo']]
print(f"[DRG-1] ADRG 分组 OK（I21 手术→FM15(3.12)/内科→FR45(1.32)，J15→ES35，未知→QY；本批 {res['grouped']}+歧义 {res['ambiguous']}）")

# 幂等：再次入组不重复
res2 = ok(call('POST', '/drg/group-all', {}, t), '再次入组')
assert res2['grouped'] == 0 and res2['ambiguous'] == 0, res2
print('[DRG-2] 入组幂等 OK（重复执行 0 新增）')

# 细分组：补录其他诊断（I50 心衰=MCC / E11 糖尿病=CC）→ 重新入组 → 尾码与权重变化
ok(call('POST', '/inpatient/diagnoses', {'admissionId': a2['id'], 'icd': 'I50.9', 'name': '心力衰竭'}, t), '补录MCC')
r = call('POST', '/inpatient/diagnoses', {'admissionId': a2['id'], 'icd': 'I50.9', 'name': '心力衰竭'}, t)
assert r['code'] == 9015, '重复诊断应拦截'
ok(call('POST', '/inpatient/diagnoses', {'admissionId': a3['id'], 'icd': 'E11.9', 'name': '2型糖尿病'}, t), '补录CC')
ok(call('POST', '/drg/regroup-all', {}, t), '重新入组')
cases2 = {c['admission_no']: c for c in ok(call('GET', '/drg/cases', token=t), '明细2')}
c2, c3 = cases2[a2['admissionNo']], cases2[a3['admissionNo']]
assert c2['drg_code'] == 'FR41' and c2['severity'] == 'MCC', c2
assert abs(float(c2['weight']) - 1.32 * 1.30) < 0.001, c2
assert c3['drg_code'] == 'ES33' and c3['severity'] == 'CC', c3
assert abs(float(c3['weight']) - 0.89 * 1.15) < 0.001, c3
print(f"[DRG-3] 细分组 OK（补心衰→FR41/MCC 权重 {c2['weight']}，补糖尿病→ES33/CC 权重 {c3['weight']}，重算生效）")

# 支付模拟：标杆=权重×费率，费用极端值高低倍率标记
ana = ok(call('GET', '/drg/analysis', token=t), '分析')
assert ana['cases'] >= 4 and float(ana['cmi']) > 0 and float(ana['rate']) > 0
assert 'totalBenchmark' in ana and 'totalBalance' in ana
fr41 = next(g for g in ana['groups'] if g['drg_code'] == 'FR41')
assert abs(float(fr41['benchmark_pay']) - float(fr41['weight']) * float(ana['rate'])) < 0.01
assert 'time_index' in fr41
assert abs(float(c2['benchmark_pay']) - 1.716 * float(ana['rate'])) < 0.01, c2
assert c2['pay_flag'] == 'LOW', f"零费用病例应标低倍率: {c2['pay_flag']}"
print(f"[DRG-4] 支付模拟 OK（费率 {ana['rate']} 元/权重，标杆合计 ￥{ana['totalBenchmark']}，模拟结余 ￥{ana['totalBalance']}，零费用例标低倍率）")

# 科室 CMI
dept = ok(call('GET', '/drg/dept-analysis', token=t), '科室CMI')
assert len(dept) >= 1 and all('cmi' in d for d in dept)
print(f"[DRG-5] 科室 CMI OK（{len(dept)} 个科室，首位 {dept[0]['dept_name']} CMI={dept[0]['cmi']}）")

# 收尾：作废未收费处方（防污染审方/收费队列），处方权复位
for o in o_blf + o_lvx:
    ok(call('PUT', f"/outpatient/doctor/orders/{o['id']}/cancel", token=t), '作废处方')
ok(call('PUT', '/outpatient/abx-privileges/1?level=1', token=t), '复位处方权')

print('\n=== 二十八期 DRG + CDSS E2E 全部通过 ===')
