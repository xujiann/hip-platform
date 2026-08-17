# -*- coding: utf-8 -*-
"""四至八期 E2E：叫号/转科/病历签名冻结/合理用药拦截/审方/运营指标/固资/预检分诊"""
import json
import sys
import datetime
import urllib.parse
import urllib.request
from e2elib import ensure_not_admitted, BASE, call, login, ok, q, today_bj  # noqa: E402



t = login()
today = today_bj().isoformat()

# ---- 第四期：叫号 ----
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': 2, 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
called = ok(call('POST', f"/outpatient/queue/call-next?deptId=1", {}, t), '叫号')
assert called['status'] == 'CALLED'
queue = ok(call('GET', '/outpatient/queue?deptId=1', token=t), '队列')
assert any(c['registrationId'] == called['registrationId'] for c in queue['called'])
ok(call('POST', f"/outpatient/doctor/{called['registrationId']}/start", {}, t), '接诊(CALLED→VISITED)')
print(f"[四-1] 叫号→大屏→接诊 OK（{called['regNo']} 号 {called.get('patientName')}）")

# ---- 第四期：病历签名冻结 ----
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '确保接诊')
ok(call('PUT', f'/outpatient/doctor/{rid}/emr', {'emr': {'chiefComplaint': '咳嗽3天', 'advice': '对症治疗'}, 'diagnoses': []}, t), '写病历')
sig = ok(call('POST', f'/outpatient/doctor/{rid}/emr/sign', {}, t), '签名')
assert sig['signature']
r = call('PUT', f'/outpatient/doctor/{rid}/emr', {'emr': {'chiefComplaint': '篡改'}, 'diagnoses': []}, t)
assert r['code'] == 4008, r
print(f"[四-2] 病历签名 + 冻结拦截(4008) OK")

# ---- 第四期：转科 ----
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']
w1 = next(w for w in wards if w['name'] == '内科病区')
w2 = next(w for w in wards if w['name'] == '外科病区')
b1 = next(b for b in ok(call('GET', f"/inpatient/beds?wardId={w1['id']}", token=t), '床') if b['status'] == 'FREE')
ensure_not_admitted(t, 2)   # 1.1.0：同一患者只能一条在院记录，先清历史未收尾的
adm = ok(call('POST', '/inpatient/admissions', {'patientId': 2, 'deptId': 1, 'bedId': b1['id'], 'deposit': 100, 'payMethod': 'CASH'}, t), '入院')
b2 = next(b for b in ok(call('GET', f"/inpatient/beds?wardId={w2['id']}", token=t), '床2') if b['status'] == 'FREE')
tr = ok(call('POST', f"/inpatient/admissions/{adm['id']}/transfer", {'toDeptId': 2, 'toBedId': b2['id']}, t), '转科')
assert tr['bedId'] == b2['id'] and tr['deptId'] == 2
beds1 = ok(call('GET', f"/inpatient/beds?wardId={w1['id']}", token=t), '床复查')
assert next(b for b in beds1 if b['id'] == b1['id'])['status'] == 'FREE', '原床应释放'
ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge", token=t), '出院清场')
print(f"[四-3] 转科转床 OK（{w1['name']}→{w2['name']}，原床释放）")

# ---- 第五期：合理用药拦截 ----
# 给张三写入青霉素过敏史
ok(call('PUT', '/patients/2', {'name': '张三', 'sex': 'M', 'idType': 'ID_CARD', 'idNo': '510181199003078511',
                              'phone': '13800138000', 'insuranceType': 'YB_RESIDENT',
                              'allergyHistory': '青霉素过敏'}, t), '更新过敏史')
amx = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药')[0]
r = call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1}]}, t)
assert r['code'] == 4012, f'青霉素过敏应拦截阿莫西林(西林): {r}'
blf = ok(call('GET', '/masterdata/drugs?keyword=' + q('布洛芬'), token=t), '药2')[0]
blf_oid = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'DRUG', 'itemId': blf['id'], 'qty': 1}]}, t), '开布洛芬')[0]['id']
r = call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'DRUG', 'itemId': blf['id'], 'qty': 1}]}, t)
assert r['code'] == 4013, f'重复用药应拦截: {r}'
ok(call('PUT', '/patients/2', {'name': '张三', 'sex': 'M', 'idType': 'ID_CARD', 'idNo': '510181199003078511',
                              'phone': '13800138000', 'insuranceType': 'YB_RESIDENT', 'allergyHistory': ''}, t), '清过敏史')
print('[五-1] 过敏禁忌拦截(4012) + 重复用药拦截(4013) OK')

# ---- 第五期：审方 ----
pending = ok(call('GET', '/outpatient/review/pending', token=t), '审方队列')
mine = next(p for p in pending if p['orderId'] == blf_oid)  # 用本次创建的订单 id，防命中他单同名药
ok(call('PUT', f"/outpatient/review/{mine['orderId']}/reject?reason=" + q('用量不适宜'), token=t), '拒绝')
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
o = next(x for x in ws['orders'] if x['id'] == mine['orderId'])
assert o['status'] == 'CANCELLED' and o['reviewStatus'] == 'REJECTED'
print('[五-2] 审方拒绝→处方作废 OK')

# ---- 第六期：运营指标 ----
op = ok(call('GET', '/stats/operation', token=t), '运营指标')
assert 'drugRatio' in op and op['dischargedCount'] > 0 and len(op['diagnosisGroups']) > 0
g = op['diagnosisGroups'][0]
print(f"[六] 运营指标 OK：药占比 {op['drugRatio']}%，出院 {op['dischargedCount']} 人次，"
      f"平均住院日 {op['avgInpDays']} 天，最大病组 {g['icd_group']}({g['sample_name']}) {g['cases']} 例均费 ¥{g['avg_cost']}")

# ---- 第七期：固定资产 ----
a = ok(call('POST', '/hrp/assets', {'name': '彩色多普勒超声诊断仪', 'category': '医疗设备', 'deptId': 3,
                                    'price': 850000, 'purchaseDate': '2024-01-15', 'usefulYears': 8}, t), '登记资产')
assert a['assetNo'].startswith('ZC')
assets = ok(call('GET', '/hrp/assets', token=t), '台账')
mine_a = next(x for x in assets if x['id'] == a['id'])
assert 0 < float(mine_a['netValue']) < 850000, '净值应按直线折旧递减'
ok(call('PUT', f"/hrp/assets/{a['id']}/status?status=REPAIR", token=t), '报修')
print(f"[七] 固资台账 OK：{a['assetNo']} 原值 85 万，净值 ¥{mine_a['netValue']}（折旧生效），状态流转 OK")

# ---- 第八期：预检分诊 ----
tg = ok(call('POST', '/outpatient/triage', {'patientName': '李四', 'level': 2, 'chiefComplaint': '胸痛1小时',
                                            'sbp': 85, 'dbp': 50, 'spo2': 90, 'destDeptId': 1}, t), '分诊')
assert tg['status'] == 'TRIAGED'
r = call('POST', '/outpatient/triage', {'patientName': '王五', 'level': 9, 'chiefComplaint': 'x'}, t)
assert r['code'] == 9702, '非法分级应拒绝'
lst = ok(call('GET', '/outpatient/triage', token=t), '队列')
assert lst[0]['level'] <= lst[-1]['level'], '队列应按分级排序'
ok(call('PUT', f"/outpatient/triage/{tg['id']}/status?status=IN_TREATMENT", token=t), '开始救治')
print(f"[八] 预检分诊 OK：II 级胸痛入队置顶，非法分级拒绝(9702)，状态流转 OK")

print('\n=== 四至八期 E2E 全部通过 ✔ ===')
