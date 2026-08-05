# -*- coding: utf-8 -*-
"""三十二至三十四期 E2E：设备全生命周期/采购单据/制度库、报卡闭环/体温日历/抽检/交接班、过号改约/药事分析/适配器路由"""
import datetime
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8080/api'
sys.stdout.reconfigure(encoding='utf-8')


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode('utf-8') if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raise AssertionError(f'{method} {path} -> HTTP {e.code}: {e.read().decode("utf-8", "replace")[:300]}')


def ok(r, step):
    assert r['code'] == 0, f'{step}: {r}'
    return r['data']


q = urllib.parse.quote
t = ok(call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'}), '登录')['token']
today = datetime.date.today().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# ============ 三十二期：设备全生命周期与后勤 ============

asset = ok(call('POST', '/hrp/assets', {'name': 'E2E彩超机' + stamp, 'category': '医疗设备',
                                        'price': 500000, 'purchaseDate': today, 'usefulYears': 8}, t), '建资产')
aid = asset['id']
# 维修：报修→越级完成拦截→派工→完成
ok(call('POST', '/equipment/repairs', {'assetId': aid, 'faultDesc': '探头无图像', 'repairType': 'OUT'}, t), '报修')
rep = next(r for r in ok(call('GET', '/equipment/repairs?keyword=' + q('探头'), token=t), '维修台账')
           if r['asset_id'] == aid)
r = call('PUT', f"/equipment/repairs/{rep['id']}/done?note=x", token=t)
assert r['code'] == 4683, '未派工不能完成'
ok(call('PUT', f"/equipment/repairs/{rep['id']}/assign?assignee=" + q('厂家工程师'), token=t), '派工')
ok(call('PUT', f"/equipment/repairs/{rep['id']}/done?note=" + q('更换探头后正常'), token=t), '完成维修')
print('[卅二-1] 维修工单 OK（报修→派工→完成，越级拦截 4683，台账关键字检索）')

# 保养：计划→执行→顺延；计量：到期标记
ok(call('POST', '/equipment/maintain-plans', {'assetId': aid, 'cycleDays': 30, 'nextDate': today,
                                              'remark': '月度保养'}, t), '保养计划')
plan = next(p for p in ok(call('GET', '/equipment/maintain-plans', token=t), '计划') if p['asset_id'] == aid)
assert plan['due'], '当日应标到期'
ok(call('POST', f"/equipment/maintain-plans/{plan['id']}/execute?content=" + q('例行保养正常'), token=t), '执行保养')
plan2 = next(p for p in ok(call('GET', '/equipment/maintain-plans', token=t), '计划2') if p['asset_id'] == aid)
assert not plan2['due'] and plan2['next_date'] > today, '执行后应顺延'
soon = (datetime.date.today() + datetime.timedelta(days=30)).isoformat()
ok(call('POST', '/equipment/metrology', {'assetId': aid, 'certNo': 'JD' + stamp, 'checkedAt': today,
                                         'validUntil': soon, 'agency': '市计量院'}, t), '计量')
met = next(m for m in ok(call('GET', '/equipment/metrology', token=t), '计量台账') if m['cert_no'] == 'JD' + stamp)
assert met['validity'] == 'EXPIRING'
print('[卅二-2] 保养计划执行顺延 + 计量台账到期预警 OK')

# 采购单据链 + 供应商证照
ok(call('POST', '/hrp/suppliers', {'name': 'E2E器械公司' + stamp, 'contact': '李经理',
                                   'phone': '13900000000', 'scope': '医疗器械'}, t), '建供应商')
sup = next(s for s in ok(call('GET', '/hrp/suppliers', token=t), '供应商') if s['name'] == 'E2E器械公司' + stamp)
doc = ok(call('POST', '/purchase/docs', {'docType': 'STOCK', 'supplierId': sup['id'],
                                         'items': '超声耦合剂×100', 'amount': 3000}, t), '备货单')
r = call('PUT', f"/purchase/docs/{doc['docNo']}/receive?invoiceNo=FP001", token=t)
assert r['code'] == 4693, '未审核不能验收'
ok(call('PUT', f"/purchase/docs/{doc['docNo']}/approve", token=t), '审核')
ok(call('PUT', f"/purchase/docs/{doc['docNo']}/receive?invoiceNo=FP-" + stamp, token=t), '验收补发票')
ok(call('PUT', f"/purchase/docs/{doc['docNo']}/receive?invoiceNo=FP-" + stamp + 'X', token=t), '发票调整')
ret = ok(call('POST', '/purchase/docs', {'docType': 'RETURN', 'supplierId': sup['id'],
                                         'items': '破损退回', 'amount': 300}, t), '退货单')
ok(call('PUT', f"/purchase/docs/{ret['docNo']}/cancel", token=t), '作废')
ok(call('PUT', f"/purchase/docs/{ret['docNo']}/restore", token=t), '还原')
ok(call('POST', '/purchase/supplier-certs', {'supplierId': sup['id'], 'certType': '经营许可证',
                                             'certNo': 'JY' + stamp, 'attachmentName': 'jy.pdf',
                                             'expireDate': soon}, t), '证照')
cert = next(c for c in ok(call('GET', '/purchase/supplier-certs', token=t), '证照台账')
            if c['cert_no'] == 'JY' + stamp)
assert cert['validity'] == 'EXPIRING'
print('[卅二-3] 采购单据链 OK（备货→审核→验收补发票→调整；退货作废→还原；证照到期预警）')

# 制度文件库
ok(call('POST', '/doclib', {'category': '院感制度', 'title': 'E2E手卫生制度' + stamp,
                            'content': '接触患者前后执行手卫生，七步洗手法。'}, t), '上传制度')
hits = ok(call('GET', '/doclib?keyword=' + q('手卫生'), token=t), '检索')
assert any(h['title'] == 'E2E手卫生制度' + stamp for h in hits)
view = ok(call('GET', f"/doclib/{hits[0]['id']}", token=t), '预览')
assert '七步洗手法' in view['content']
print('[卅二-4] 制度文件库 OK（上传→检索→在线预览）')

# ============ 三十三期：护理与院感精细化 ============

pat = ok(call('POST', '/patients', {'name': '报卡E2E' + stamp, 'sex': 'M'}, t), '建患者')
r = call('POST', '/infection/cards', {'patientId': pat['id'], 'diseaseName': 'x', 'cardClass': 'X'}, t)
assert r['code'] == 4700
ok(call('POST', '/infection/cards', {'patientId': pat['id'], 'diseaseName': '肺结核',
                                     'cardClass': 'B', 'onsetDate': today}, t), '报卡')
card = next(c for c in ok(call('GET', '/infection/cards', token=t), '报卡列表') if c['patient_id'] == pat['id'])
r = call('PUT', f"/infection/cards/{card['id']}/submit", token=t)
assert r['code'] == 4703, '未审核不能上报'
ok(call('PUT', f"/infection/cards/{card['id']}/review?approve=true&note=" + q('属实'), token=t), '审核')
ok(call('PUT', f"/infection/cards/{card['id']}/submit", token=t), '上报')
print('[卅三-1] 传染病报卡闭环 OK（报卡→审核→上报，越级 4703，类别校验 4700）')

# 体温日历 + 术后发热监测（入院→手术完成→录 38.5℃ 体征）
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']
free = None
for w in wards:
    beds = ok(call('GET', f"/inpatient/beds?wardId={w['id']}", token=t), '床')
    free = next((b for b in beds if b['status'] == 'FREE'), None)
    if free:
        break
assert free, '无空床'
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'diagIcd': 'A15.0', 'diagName': '肺结核',
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
ok(call('POST', '/inpatient/surgeries', {'admissionId': adm['id'], 'procedureName': '肺段切除术',
                                         'anesthesiaType': '全身麻醉', 'scheduledAt': None}, t), '手术')
sg = ok(call('GET', '/inpatient/surgeries', token=t), '手术列表')[0]
ok(call('PUT', f"/inpatient/surgeries/{sg['id']}/complete", {'opNote': '顺利', 'anesNote': '平稳'}, t), '完成手术')
ok(call('POST', f"/inpatient/admissions/{adm['id']}/vitals", {'temperature': 36.8, 'pulse': 78}, t), '体征1')
ok(call('POST', f"/inpatient/admissions/{adm['id']}/vitals", {'temperature': 38.5, 'pulse': 96}, t), '体征2')
cal = ok(call('GET', f"/nursing/temp-calendar?admissionId={adm['id']}", token=t), '体温日历')
assert len(cal) >= 1 and any(d['fever'] for d in cal), cal
fever = ok(call('GET', '/nursing/postop-fever', token=t), '术后发热')
assert any(f['admission_no'] == adm['admissionNo'] for f in fever), '术后 38.5℃ 应进监测'
print('[卅三-2] 体温日历（发热日标记）+ 术后 72h 发热监测 OK')

# 班次字典 + 抽检评分曲线 + 交接班
ok(call('POST', '/nursing/shift-types', {'code': 'A8' + stamp[-2:], 'name': '行政班',
                                         'startTime': '08:00', 'endTime': '17:30'}, t), '班次')
assert len(ok(call('GET', '/nursing/shift-types', token=t), '班次表')) >= 4
ok(call('POST', '/qc-check/plans', {'title': 'E2E手卫生抽检' + stamp, 'standard': '手卫生依从性',
                                    'adHoc': True}, t), '抽检计划')
plan = next(p for p in ok(call('GET', '/qc-check/plans', token=t), '计划') if p['title'] == 'E2E手卫生抽检' + stamp)
ok(call('POST', '/qc-check/scores', {'planId': plan['id'], 'target': '内科病区', 'score': 85, 'note': ''}, t), '评分1')
ok(call('POST', '/qc-check/scores', {'planId': plan['id'], 'target': '内科病区', 'score': 93, 'note': '整改后'}, t), '评分2')
curve = ok(call('GET', f"/qc-check/scores?planId={plan['id']}", token=t), '曲线')
assert len(curve) == 2 and float(curve[1]['score']) > float(curve[0]['score'])
ok(call('POST', '/nursing/handovers', {'deptId': 2, 'shiftType': 'DAY',
                                       'summary': '在院平稳，无危重', 'todo': '3 床明晨复查血常规'}, t), '交班')
assert len(ok(call('GET', '/nursing/handovers', token=t), '交接班')) >= 1
print('[卅三-3] 班次字典 + 抽检评分曲线（85→93）+ 交接班 OK')

# ============ 三十四期：门诊体验与药事分析 ============

# 过号/重呼
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': pat['id'], 'scheduleId': sch['id']}, t), '挂号')
r = call('POST', f"/outpatient/queue/pass?registrationId={reg['id']}", token=t)
assert r['code'] == 3302, '未叫号不能过号'
called = ok(call('POST', '/outpatient/queue/call-next?deptId=1', {}, t), '叫号')
# 可能叫到历史候诊，循环直到叫到本单
while called['registrationId'] != reg['id']:
    ok(call('POST', f"/outpatient/queue/pass?registrationId={called['registrationId']}", token=t), '过他人号')
    called = ok(call('POST', '/outpatient/queue/call-next?deptId=1', {}, t), '继续叫号')
ok(call('POST', f"/outpatient/queue/pass?registrationId={reg['id']}", token=t), '过号')
board = ok(call('GET', '/outpatient/queue?deptId=1', token=t), '大屏')
assert any(p['registrationId'] == reg['id'] for p in board['passed']), '应进过号队列'
recalled = ok(call('POST', f"/outpatient/queue/recall?registrationId={reg['id']}", token=t), '重呼')
assert recalled['status'] == 'CALLED'
print('[卅四-1] 过号队列 OK（叫号→过号→大屏过号显示→重呼，未叫号拦截 3302）')

# 改约 + 批量取消
ok(call('POST', f"/outpatient/doctor/{reg['id']}/start", {}, t), '接诊')
ecg = ok(call('GET', '/masterdata/charge-items?keyword=' + q('心电'), token=t), '心电')[0]
dr = ok(call('GET', '/masterdata/charge-items?keyword=' + q('DR'), token=t), 'DR')[0]
orders = ok(call('POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [
    {'orderType': 'EXAM', 'itemId': ecg['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': dr['id'], 'qty': 1}]}, t), '开单')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg['id'], 'payMethod': 'CASH'}, t), '收费')
a1 = ok(call('POST', '/appointments', {'orderId': orders[0]['id'], 'slotDate': today, 'period': 'AM'}, t), '预约1')
appts = ok(call('GET', f'/appointments?date={today}', token=t), '队列')
mine1 = next(a for a in appts if a['seq_no'] == a1['seqNo'] and a['period'] == 'AM'
             and a['item_name'] == orders[0]['itemName'])
resch = ok(call('PUT', f"/appointments/{mine1['id']}/reschedule", {'slotDate': today, 'period': 'PM'}, t), '改约')
assert resch['seqNo'] >= 1
a2 = ok(call('POST', '/appointments', {'orderId': orders[1]['id'], 'slotDate': today, 'period': 'AM'}, t), '预约2')
appts2 = ok(call('GET', f'/appointments?date={today}', token=t), '队列2')
mine2 = next(a for a in appts2 if a['item_name'] == orders[1]['itemName'] and a['status'] == 'BOOKED')
bc = ok(call('POST', '/appointments/batch-cancel', {'ids': [mine2['id']]}, t), '批量取消')
assert bc['cancelled'] == 1
print(f"[卅四-2] 预约改约（AM→PM 第{resch['seqNo']}号）+ 批量取消 OK")

# 药事分析
usage = ok(call('GET', '/drug-analysis/usage', token=t), '用药汇总')
assert len(usage) >= 1
cls = ok(call('GET', '/drug-analysis/class-summary', token=t), '中西药')
assert any(c['drug_class'] == '西药' for c in cls)
aud = ok(call('GET', '/drug-analysis/aud', token=t), 'AUD')
assert float(aud['ddds']) >= 0 and 'aud' in aud
fund = ok(call('GET', '/drug-analysis/monthly-fund', token=t), '经费表')
assert len(fund) >= 1
print(f"[卅四-3] 药事分析 OK（TOP{len(usage)} 用药，抗菌药 DDDs={aud['ddds']}，AUD={aud['aud']}）")

# 适配器路由
adapters = ok(call('GET', '/integration/adapters', token=t), '适配器')
assert len(adapters) >= 2
rt = ok(call('POST', '/integration/adapters/route-test?content=' + q('MSH|ORU^R01|test'), {}, t), '路由测试')
assert rt['matched'] and rt['type'] == 'FILE', rt
file_ad = next(a for a in adapters if a['type'] == 'FILE')
ok(call('PUT', f"/integration/adapters/{file_ad['id']}/toggle", token=t), '停用')
r = call('POST', '/integration/adapters/route-test?content=' + q('MSH|ORU^R01|test'), {}, t)
assert r['code'] == 4712, '停用适配器应拦截'
ok(call('PUT', f"/integration/adapters/{file_ad['id']}/toggle", token=t), '恢复')
logs = ok(call('GET', '/integration/logs?channel=ADAPTER', token=t), 'ADAPTER通道')
assert len(logs) >= 1
print('[卅四-4] 适配器路由 OK（ORU→文件落地留痕，停用拦截 4712）')

# 收尾：出院释放床位
ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge", {}, t), '收尾出院')

print('\n=== 三十二至三十四期 E2E 全部通过 ===')
