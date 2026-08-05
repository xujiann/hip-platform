# -*- coding: utf-8 -*-
"""三十九至四十期 E2E：护理风险评估（Braden/Morse）、检验替检参数化、继续教育/考勤、资产价值调整、结账检索"""
import datetime
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from e2elib import BASE, call, discharge_cleanup, find_free_bed, new_patient, login, ok, q  # noqa: E402



t = login()
today = datetime.date.today().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# ============ 三十九期 ============

# 风险评估：Braden 10 → 高危；Morse 30 → 中危；越界拦截；趋势与高危预警
pat = new_patient(t, '评估E2E' + stamp, 'F')
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'diagIcd': 'I63.9', 'diagName': '脑梗死',
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
r = call('POST', '/nursing/risk-assess', {'admissionId': adm['id'], 'assessType': 'BRADEN', 'score': 30}, t)
assert r['code'] == 4781, 'Braden 越界应拦截'
lv1 = ok(call('POST', '/nursing/risk-assess', {'admissionId': adm['id'], 'assessType': 'BRADEN', 'score': 10}, t), '压疮评估')
assert lv1['riskLevel'] == 'HIGH'
lv2 = ok(call('POST', '/nursing/risk-assess', {'admissionId': adm['id'], 'assessType': 'BRADEN', 'score': 16}, t), '复评')
assert lv2['riskLevel'] == 'LOW'
lv3 = ok(call('POST', '/nursing/risk-assess', {'admissionId': adm['id'], 'assessType': 'MORSE', 'score': 30}, t), '跌倒评估')
assert lv3['riskLevel'] == 'MID'
trend = ok(call('GET', f"/nursing/risk-assess?admissionId={adm['id']}&assessType=BRADEN", token=t), '趋势')
assert len(trend) == 2 and trend[0]['risk_level'] == 'HIGH' and trend[1]['risk_level'] == 'LOW'
alerts = ok(call('GET', '/nursing/risk-assess/alerts', token=t), '高危预警')
assert not any(a['admission_no'] == adm['admissionNo'] and a['assess_type'] == 'BRADEN' for a in alerts), \
    '复评降为低危后不应在 Braden 高危清单'
print('[卅九-1] 风险评估 OK（Braden 10→高危→复评 16 低危出清单，Morse 30 中危，越界 4781，趋势 2 条）')

# 替检：开关关闭拦截 → 开启 → 采样带替检人 → 列表红标
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': pat['id'], 'scheduleId': sch['id']}, t), '挂号')
ok(call('POST', f"/outpatient/doctor/{reg['id']}/start", {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('血常规'), token=t), '检验项')[0]
orders = ok(call('POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg['id'], 'payMethod': 'CASH'}, t), '收费')
ok(call('PUT', '/config/lis_allow_substitute?value=false', token=t), '关替检')
r = call('POST', f"/lis/samples?orderId={orders[0]['id']}&substituteName=" + q('家属张某'), {}, t)
assert r['code'] == 4770, '参数禁止时替检应拦截'
ok(call('PUT', '/config/lis_allow_substitute?value=true', token=t), '开替检')
bc = ok(call('POST', f"/lis/samples?orderId={orders[0]['id']}&substituteName=" + q('家属张某'), {}, t), '替检采样')['barcode']
sample = next(s for s in ok(call('GET', '/lis/samples', token=t), '标本') if s['barcode'] == bc)
assert sample['substitute'] and sample['substitute_name'] == '家属张某'
print('[卅九-2] 替检管理 OK（参数关→4770 拦截，开→替检人标识随标本流转）')

# ============ 四十期 ============

# 继续教育：登记两条 → 年度学分汇总
ok(call('POST', '/hr/employees', {'empNo': 'CE' + stamp, 'name': '继教E2E' + stamp, 'sex': 'F',
                                  'deptId': 1, 'title': '主管护师'}, t), '建员工')
emp = ok(call('GET', '/hr/employees?keyword=CE' + stamp, token=t), '员工')[0]
ok(call('POST', '/hr/cme', {'employeeId': emp['id'], 'projectName': '护理安全培训', 'credit': 5,
                            'cmeYear': 2026, 'organizer': '省继教平台'}, t), '继教1')
ok(call('POST', '/hr/cme', {'employeeId': emp['id'], 'projectName': '院感防控进展', 'credit': 3.5,
                            'cmeYear': 2026}, t), '继教2')
cme = ok(call('GET', f"/hr/cme?employeeId={emp['id']}", token=t), '继教台账')
assert len(cme['records']) == 2
mine = next(s for s in cme['creditSummary'] if s['cme_year'] == 2026)
assert float(mine['total_credit']) == 8.5
print('[四十-1] 继续教育 OK（2 项登记，2026 年度学分合计 8.5）')

# 考勤：打卡 → 补卡无说明拦截 → 补卡覆盖
ok(call('POST', '/hr/attendance', {'employeeId': emp['id'], 'workDate': today,
                                   'checkIn': '08:00', 'attType': 'NORMAL'}, t), '打卡')
r = call('POST', '/hr/attendance', {'employeeId': emp['id'], 'workDate': today,
                                    'checkOut': '17:30', 'attType': 'MAKEUP'}, t)
assert r['code'] == 4791, '补卡无说明应拦截'
ok(call('POST', '/hr/attendance', {'employeeId': emp['id'], 'workDate': today, 'checkOut': '17:30',
                                   'attType': 'MAKEUP', 'note': '忘打下班卡'}, t), '补卡')
att = next(a for a in ok(call('GET', f'/hr/attendance?date={today}', token=t), '考勤')
           if a['employee_id'] == emp['id'])
assert att['check_in'] == '08:00' and att['check_out'] == '17:30' and att['att_type'] == 'MAKEUP'
print('[四十-2] 考勤 OK（打卡→补卡须说明 4791→同日 upsert 合并上下班）')

# 资产价值调整 + 附件
asset = ok(call('POST', '/hrp/assets', {'name': 'E2E呼吸机' + stamp, 'category': '医疗设备',
                                        'price': 200000, 'purchaseDate': today, 'usefulYears': 10}, t), '建资产')
r = call('POST', '/asset-plus/value-adjusts', {'assetId': asset['id'], 'adjustType': 'X',
                                               'amount': 1, 'reason': 'x'}, t)
assert r['code'] == 4792
ok(call('POST', '/asset-plus/value-adjusts', {'assetId': asset['id'], 'adjustType': 'DEP_FIX',
                                              'amount': 5000, 'reason': '历史折旧补录'}, t), '折旧补录')
ok(call('POST', '/asset-plus/value-adjusts', {'assetId': asset['id'], 'adjustType': 'APPRECIATION',
                                              'amount': 12000, 'reason': '加装模块增值'}, t), '增值')
adjs = [a for a in ok(call('GET', '/asset-plus/value-adjusts', token=t), '调整台账')
        if a['asset_id'] == asset['id']]
assert len(adjs) == 2
ok(call('POST', '/asset-plus/docs', {'assetId': asset['id'], 'docName': '购置合同.pdf', 'remark': ''}, t), '附件')
assert len(ok(call('GET', f"/asset-plus/docs?assetId={asset['id']}", token=t), '附件台账')) == 1
print('[四十-3] 资产价值调整（补录+增值）与附件台账 OK（类型校验 4792）')

# 结账明细检索
hits = ok(call('GET', f'/finance/charge-search?from={today}&to={today}', token=t), '结账检索')
assert len(hits) >= 1
cash_hits = ok(call('GET', f'/finance/charge-search?from={today}&to={today}&payMethod=CASH', token=t), '按方式')
assert all(h['pay_method'] == 'CASH' for h in cash_hits)
print(f"[四十-4] 结账明细检索 OK（今日 {len(hits)} 笔，按支付方式过滤生效）")

# 收尾：出院释放床位
discharge_cleanup(t, adm['id'])

print('\n=== 三十九至四十期 E2E 全部通过 ===')
