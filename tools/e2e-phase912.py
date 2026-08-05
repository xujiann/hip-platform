# -*- coding: utf-8 -*-
"""九至十二期 E2E：护理白板/时限质控/不良事件/院感、随访/满意度/会诊/路径/体检、
物资/供应商/OA、指标快照/填报/数据质量"""
import json
import sys
import datetime
import urllib.parse
import urllib.request
from e2elib import BASE, call, login, ok, q  # noqa: E402



t = login()
today = datetime.date.today().isoformat()

# 准备一个在院患者
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']
free = next(b for b in ok(call('GET', f"/inpatient/beds?wardId={wards[0]['id']}", token=t), '床')
            if b['status'] == 'FREE')
icds = ok(call('GET', '/masterdata/icd10?keyword=' + q('糖尿病'), token=t), 'ICD')
adm = ok(call('POST', '/inpatient/admissions', {'patientId': 2, 'deptId': 1, 'bedId': free['id'],
                                                'diagIcd': icds[0]['code'], 'diagName': icds[0]['name'],
                                                'deposit': 300, 'payMethod': 'CASH'}, t), '入院')
aid = adm['id']

# ---- 九期 ----
board = ok(call('GET', '/inpatient/nursing/board', token=t), '白板')
assert any(b['admission_id'] == aid for b in board)
ok(call('PUT', f'/inpatient/admissions/{aid}/care-level?level=' + q('一级'), token=t), '护理级别')
board2 = ok(call('GET', '/inpatient/nursing/board', token=t), '白板2')
assert next(b for b in board2 if b['admission_id'] == aid)['care_level'] == '一级'
print('[九-1] 护理白板 + 护理级别调整 OK')

qc = ok(call('GET', '/quality/emr-timeliness', token=t), '时限质控')
assert 'defectTotal' in qc and 'unsignedOutpEmrCount' in qc
print(f"[九-2] 时限质控 OK（缺陷 {qc['defectTotal']}，未签名门诊病历 {qc['unsignedOutpEmrCount']}）")

ok(call('POST', '/quality/adverse-events', {'type': '跌倒', 'level': 3, 'occurredOn': today,
                                            'deptId': 1, 'description': '患者如厕滑倒，无伤', 'anonymous': True}, t), '不良事件')
ae = next(x for x in ok(call('GET', '/quality/adverse-events', token=t), '事件列表') if x['status'] == 'NEW')
assert ae['anonymous'] is True
ok(call('PUT', f"/quality/adverse-events/{ae['id']}/handle?note=" + q('已加装防滑垫'), token=t), '处理')
print('[九-3] 不良事件匿名上报→处理 OK')

ok(call('POST', '/quality/infections', {'admissionId': aid, 'site': '呼吸道', 'pathogen': '肺炎克雷伯菌',
                                        'confirmedOn': today, 'note': '痰培养阳性'}, t), '院感登记')
inf = ok(call('GET', '/quality/infections', token=t), '院感')
assert any(c['admission_no'] == adm['admissionNo'] for c in inf['cases'])
print(f"[九-4] 院感登记 OK（抗菌药物已执行 {inf['antibioticOrderCount']} 条）")

# ---- 十期 ----
ok(call('POST', '/patientcare/followups', {'patientId': 2, 'topic': '出院一周复诊提醒', 'dueDate': today}, t), '随访计划')
fus = ok(call('GET', '/patientcare/followups', token=t), '随访队列')
mine = next(f for f in fus if f['topic'] == '出院一周复诊提醒')
ok(call('PUT', f"/patientcare/followups/{mine['id']}/done?note=" + q('电话随访，恢复良好'), token=t), '完成随访')
print('[十-1] 随访计划→执行 OK')

ok(call('POST', '/patientcare/satisfaction', {'patientId': 2, 'source': 'PORTAL', 'score': 5, 'comment': '服务很好'}, t), '满意度')
stats = ok(call('GET', '/patientcare/satisfaction/stats', token=t), '满意度统计')
assert stats['count'] >= 1 and stats['avgScore'] > 0
print(f"[十-2] 满意度 OK（{stats['count']} 份，均分 {stats['avgScore']}）")

ok(call('POST', '/inpatient/consults', {'admissionId': aid, 'toDeptId': 2, 'question': '血糖控制不佳，请内分泌协助'}, t), '会诊申请')
c = ok(call('GET', '/inpatient/consults', token=t), '会诊列表')[0]
ok(call('PUT', f"/inpatient/consults/{c['id']}/complete?opinion=" + q('建议调整胰岛素方案'), token=t), '会诊完成')
print('[十-3] 院内会诊闭环 OK')

amx = ok(call('GET', '/masterdata/drugs?keyword=' + q('二甲双胍'), token=t), '药')[0]
ok(call('POST', '/pathways', {'name': '2型糖尿病基础路径', 'icdPrefix': 'E11', 'description': '入院常规',
                              'items': [{'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1,
                                         'usageRoute': '口服', 'frequency': 'bid', 'dosePerTime': '1片'}]}, t), '路径模板')
pw = ok(call('GET', '/pathways', token=t), '路径列表')[0]
applied = ok(call('POST', f"/pathways/{pw['id']}/apply/{aid}", {}, t), '入径')
assert applied['orders'] == 1
print('[十-4] 临床路径模板→入径批量开嘱 OK')

ok(call('POST', '/exam/packages', {'name': '入职体检套餐', 'price': 280, 'items': '血常规+尿常规+胸片+心电图'}, t), '套餐')
pk = ok(call('GET', '/exam/packages', token=t), '套餐列表')[0]
ok(call('POST', f"/exam/records?patientId=2&packageId={pk['id']}", {}, t), '体检登记')
er = ok(call('GET', '/exam/records', token=t), '体检记录')[0]
ok(call('PUT', f"/exam/records/{er['id']}/complete?summary=" + q('各项指标正常'), token=t), '总检')
print('[十-5] 体检套餐→登记→总检 OK')

# ---- 十一期 ----
ok(call('POST', '/hrp/materials', {'name': '一次性注射器 5ml', 'category': 'CONSUMABLE_LOW', 'unit': '支', 'price': 0.8}, t), '物资建档')
m = ok(call('GET', '/hrp/materials', token=t), '物资')[-1]
ok(call('POST', f"/hrp/materials/{m['id']}/stock-in?qty=1000&refNo=RK-TEST", {}, t), '入库')
ok(call('POST', f"/hrp/materials/{m['id']}/stock-out?qty=200&refNo=" + q('LY-病区'), {}, t), '出库')
m2 = next(x for x in ok(call('GET', '/hrp/materials', token=t), '物资2') if x['id'] == m['id'])
assert m2['stock'] == 800
r = call('POST', f"/hrp/materials/{m['id']}/stock-out?qty=99999", {}, t)
assert r['code'] == 9912
txns = ok(call('GET', f"/hrp/materials/{m['id']}/txns", token=t), '流水')
assert txns[0]['type'] == 'OUT' and txns[0]['stock_after'] == 800
print('[十一-1] 物资进销存 OK（1000入/200出/超量拦截/流水）')

ok(call('POST', '/hrp/suppliers', {'name': '四川械字号供应链', 'contact': '刘经理', 'phone': '13900000001', 'scope': '低值耗材'}, t), '供应商')
ok(call('POST', '/oa/requests', {'type': 'PURCHASE', 'title': '采购输液泵 2 台', 'content': 'ICU 设备补充，预算 6 万'}, t), 'OA申请')
oa = ok(call('GET', '/oa/requests', token=t), 'OA列表')[0]
ok(call('PUT', f"/oa/requests/{oa['id']}/decide?approve=true&note=" + q('同意，走招采'), token=t), '审批')
print('[十一-2] 供应商登记 + OA 申请→审批 OK')

# ---- 十二期 ----
snap = ok(call('POST', '/datagov/metrics/snapshot', {}, t), '指标快照')
assert snap['snapshotted'] >= 5  # 二十五期评审指标集扩充后为 9 项
ms = ok(call('GET', '/datagov/metrics', token=t), '指标')
drug_ratio = next(x for x in ms if x['code'] == 'M002')
assert drug_ratio['latest_value'] is not None
print(f"[十二-1] 指标快照 OK（5 项，药占比 {drug_ratio['latest_value']}%）")

ok(call('POST', '/datagov/report-tasks', {'title': '传染病月报', 'dueDate': today, 'fields': '病种/例数/转归'}, t), '填报任务')
task = ok(call('GET', '/datagov/report-tasks', token=t), '任务')[0]
ok(call('POST', '/datagov/submissions', {'taskId': task['id'], 'deptId': 1, 'content': '流感 3 例，均痊愈'}, t), '提交')
sub = ok(call('GET', f"/datagov/submissions?taskId={task['id']}", token=t), '提交列表')[0]
ok(call('PUT', f"/datagov/submissions/{sub['id']}/review?approve=true", token=t), '审核')
print('[十二-2] 数据填报→审核 OK')

checks = ok(call('GET', '/datagov/quality-checks', token=t), '数据质量')
assert len(checks) == 6
print(f"[十二-3] 数据质量核查 OK（{len(checks)} 条规则，待治理 {sum(1 for c in checks if not c['pass'])} 项）")

# 清场：出院（先执行路径开出的医嘱）
for p in ok(call('GET', '/inpatient/orders/pending', token=t), '待执行'):
    if p['admissionNo'] == adm['admissionNo']:
        ok(call('PUT', f"/inpatient/orders/{p['orderId']}/execute", token=t), '执行')
s = ok(call('POST', f'/inpatient/admissions/{aid}/discharge', token=t), '出院')
fp = ok(call('GET', f'/inpatient/admissions/{aid}/front-page', token=t), '病案首页')
assert fp['total_amount'] is not None and fp['admit_diag_icd']
ok(call('PUT', f'/inpatient/admissions/{aid}/archive', token=t), '归档')
print(f"[九-5] 病案首页汇编（费用 ¥{fp['total_amount']}）+ 归档 OK")

print('\n=== 九至十二期 E2E 全部通过 ✔ ===')
