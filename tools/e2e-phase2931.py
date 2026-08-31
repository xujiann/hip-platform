# -*- coding: utf-8 -*-
"""二十九至三十一期 E2E：扫码支付/患者端报告与导诊/报告分享、病案统计/路径变异/麻醉单/团检、脱敏/密码有效期/敏感审计"""
import datetime
import json
import sys
import urllib.error
import urllib.parse
import os
import urllib.request
from e2elib import BASE, call, discharge_cleanup, find_free_bed, login, ok, provision_user, q, today_bj  # noqa: E402



# 为断言 passwordAgeDays 需拿原始 login 响应，但凭据必须走 e2elib 的环境变量覆盖——
# 硬编码 admin123 会在改密后的环境（在线演示重灌数据）恒失败并给 admin 记失败计数（第六轮审阅 P2-D）
login_resp = call('POST', '/auth/login', {
    'username': os.environ.get('HIP_E2E_USER', 'admin'),
    'password': os.environ.get('HIP_E2E_PASSWORD', 'admin123')})
t = ok(login_resp, '登录')['token']
today = today_bj().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# ============ 三十一期（先验登录附加字段） ============
assert 'passwordAgeDays' in login_resp['data'] and 'passwordExpireWarning' in login_resp['data'], login_resp['data']
print(f"[卅一-1] 密码有效期提醒 OK（口令已使用 {login_resp['data']['passwordAgeDays']} 天，预警={login_resp['data']['passwordExpireWarning']}）")

# ============ 二十九期：患者服务与支付闭环 ============

phone = '138' + stamp + '00'
pat = ok(call('POST', '/patients', {'name': '患者服务' + stamp, 'sex': 'F', 'phone': phone,
                                    'idNo': '', 'insuranceType': 'SELF'}, t), '建患者')
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 20}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': pat['id'], 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('肝功能'), token=t), '检验项')[0]
ecg = ok(call('GET', '/masterdata/charge-items?keyword=' + q('心电'), token=t), '心电项')[0]
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': ecg['id'], 'qty': 1}]}, t), '开单')
lab_o = next(o for o in orders if o['orderType'] == 'LAB')
ecg_o = next(o for o in orders if o['orderType'] == 'EXAM')

# 患者端：登录 → 待缴费 → 出码 → 确认支付（Mock 回调）→ 自动结算
pt = ok(call('POST', '/portal/login', {'patientNo': pat['patientNo'], 'phone': phone}), '患者登录')['token']
bills = ok(call('GET', '/portal/my/pending-bill', token=pt), '待缴费')
assert len(bills) == 1 and float(bills[0]['total']) > 0, bills
pay = ok(call('POST', '/portal/my/pay', {'registrationId': rid, 'channel': 'WECHAT'}, pt), '出码')
assert pay['qrContent'].startswith('hippay://wechat')
r = call('POST', '/portal/my/pay', {'registrationId': rid, 'channel': 'ALIPAY'}, pt)
assert r['code'] == 5102, '重复出码应拦截'
paid = ok(call('POST', f"/portal/my/pay/{pay['payNo']}/confirm", {}, pt), '确认支付')
assert len(ok(call('GET', '/portal/my/pending-bill', token=pt), '待缴费2')) == 0, '支付后应无待缴费'
charges = ok(call('GET', '/portal/my/charges', token=pt), '账单')
assert any(c['chargeNo'] == paid['chargeNo'] for c in charges)
print(f"[廿九-1] 患者端扫码支付闭环 OK（出码→Mock 回调→自动结算 {paid['chargeNo']}，重复出码拦截 5102）")

# 收费台出码 + 作废（作废路径同时喂给敏感审计用例）
pat2 = ok(call('POST', '/patients', {'name': '支付台' + stamp, 'sex': 'M'}, t), '建患者2')
reg2 = ok(call('POST', '/outpatient/registrations', {'patientId': pat2['id'], 'scheduleId': sch['id']}, t), '挂号2')
po = ok(call('POST', '/pay/orders', {'registrationId': reg2['id'], 'channel': 'ALIPAY'}, t), '支付台出码')
ok(call('PUT', f"/pay/orders/{po['payNo']}/cancel", token=t), '作废')
pay_logs = ok(call('GET', '/integration/logs?channel=PAY', token=t), 'PAY通道')
assert any(l['refNo'] == pay['payNo'] for l in pay_logs), 'PAY 通道应留痕'
print('[廿九-2] 收费台出码/作废 OK，聚合支付通道留痕（channel=PAY）')

# 危急值不外显：LIS 发布 HH → 患者端遮蔽
bc = ok(call('POST', f"/lis/samples?orderId={lab_o['id']}", {}, t), '采样')['barcode']
ok(call('PUT', f'/lis/samples/{bc}/receive', token=t), '核收')
ok(call('POST', f'/lis/samples/{bc}/publish', {'results': [
    {'code': 'ALT', 'name': '丙氨酸氨基转移酶', 'value': '300', 'unit': 'U/L', 'refRange': '9-50', 'flag': 'HH'},
    {'code': 'AST', 'name': '天冬氨酸氨基转移酶', 'value': '30', 'unit': 'U/L', 'refRange': '15-40', 'flag': 'N'}]}, t), '发布')
labs = ok(call('GET', '/portal/my/lab-reports', token=pt), '患者检验报告')
mine = next(l for l in labs if l['orderId'] == lab_o['id'])
crit = next(x for x in mine['results'] if x['abnormalFlag'] == 'CRIT')
assert '回院' in crit['resultValue'] and '300' not in crit['resultValue'], crit
normal = next(x for x in mine['results'] if x['abnormalFlag'] == 'N')
assert normal['resultValue'] == '30'
print('[廿九-3] 危急值不外显 OK（HH 结果替换为回院提示，正常值照常显示）')

# 检查报告 + 对外分享短链（匿名、脱敏、限时）
wl = ok(call('GET', '/ris/worklist?modality=ECG', token=t), 'ECG队列')
row = next(w for w in wl if w['group_no'] == ecg_o['groupNo'])
ok(call('PUT', f"/ris/exams/{row['id']}/report", {'findings': '窦性心律', 'impression': '正常心电图'}, t), '报告')
_rv = provision_user(t, 'ris_verifier_2931', 'TECHNICIAN', '放射审核')   # v33 双签：审核人≠报告人
ok(call('PUT', f"/ris/exams/{row['id']}/verify", token=_rv), '审核')
exams = ok(call('GET', '/portal/my/exam-reports', token=pt), '患者检查报告')
assert any(e['report_type'] == 'EXAM' and e['conclusion'] == '正常心电图' for e in exams)
share = ok(call('POST', f"/ris/exams/{row['id']}/share", {}, t), '分享')
anon = ok(call('GET', f"/share/{share['token']}"), '匿名访问')
assert anon['patientName'].endswith('**') and anon['impression'] == '正常心电图', anon
# 1.1.4 B-10：有效期上限 30 天，0/超限均拒；吊销是泄漏后的唯一回收手段
r = call('POST', f"/ris/exams/{row['id']}/share?expireMinutes=0", {}, t)
assert r['code'] == 4663, f'expireMinutes=0 应被拒: {r}'
r = call('POST', f"/ris/exams/{row['id']}/share?expireMinutes=52560000", {}, t)
assert r['code'] == 4663, f'百年外链应被拒: {r}'
ok(call('DELETE', f"/ris/exams/{row['id']}/share", token=t), '吊销分享')
r = call('GET', f"/share/{share['token']}")
assert r['code'] == 4662, f'吊销后链接应立即失效: {r}'
print('[廿九-4] 患者端检查报告 + 分享短链 OK（姓名脱敏，有效期上限 4663，吊销后 4662 失效）')

# 智能导诊
g = ok(call('GET', '/portal/guide?symptom=' + q('咳嗽两周还发热'), token=pt), '导诊')
assert len(g) >= 2 and any(x['dept_name'] == '呼吸内科' for x in g), g
print(f"[廿九-5] 智能导诊 OK（命中 {len(g)} 条：{'、'.join(x['dept_name'] for x in g)}）")

# ============ 三十期：病案与临床专科深化 ============

# 病案统计
ov = ok(call('GET', '/mrstats/overview', token=t), '病案概览')
assert ov['discharged'] >= 1
top = ok(call('GET', '/mrstats/disease-top', token=t), '疾病谱')
assert len(top) >= 1
assert len(ok(call('GET', '/mrstats/dept-discharge', token=t), '科室构成')) >= 1
assert len(ok(call('GET', '/mrstats/icd-composition', token=t), 'ICD构成')) >= 1
print(f"[卅-1] 病案统计 OK（出院 {ov['discharged']} 份，编码率 {ov['codedRate']}%，疾病谱 TOP{len(top)}）")

# 手术 + 麻醉记录单（术中时间轴 + PACU 苏醒判定）
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'diagIcd': 'K80.2', 'diagName': '胆囊结石',
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
ok(call('POST', '/inpatient/surgeries', {'admissionId': adm['id'], 'procedureName': '腹腔镜胆囊切除术',
                                         'anesthesiaType': '全身麻醉', 'scheduledAt': None}, t), '手术申请')
sg = ok(call('GET', '/inpatient/surgeries', token=t), '手术列表')[0]
r = call('POST', '/anes/records', {'surgeryId': sg['id'], 'phase': 'PACU', 'stewardScore': 9}, t)
assert r['code'] == 4672, 'Steward 越界应拦截'
ok(call('POST', '/anes/records', {'surgeryId': sg['id'], 'phase': 'INTRA', 'hr': 68, 'sbp': 110,
                                  'dbp': 70, 'spo2': 99, 'note': '诱导后'}, t), '术中1')
ok(call('POST', '/anes/records', {'surgeryId': sg['id'], 'phase': 'INTRA', 'hr': 72, 'sbp': 118,
                                  'dbp': 74, 'spo2': 99, 'note': '气腹建立'}, t), '术中2')
ok(call('POST', '/anes/records', {'surgeryId': sg['id'], 'phase': 'PACU', 'hr': 80, 'spo2': 96,
                                  'stewardScore': 3, 'note': '入 PACU'}, t), 'PACU1')
st = ok(call('GET', f"/anes/records/pacu-status?surgeryId={sg['id']}", token=t), '苏醒判定1')
assert not st['canLeave'] and st['latestScore'] == 3
ok(call('POST', '/anes/records', {'surgeryId': sg['id'], 'phase': 'PACU', 'hr': 76, 'spo2': 99,
                                  'stewardScore': 6, 'note': '完全清醒'}, t), 'PACU2')
st2 = ok(call('GET', f"/anes/records/pacu-status?surgeryId={sg['id']}", token=t), '苏醒判定2')
assert st2['canLeave'] and st2['latestScore'] == 6
timeline = ok(call('GET', f"/anes/records?surgeryId={sg['id']}", token=t), '时间轴')
assert len(timeline) == 4
print('[卅-2] 麻醉记录单 OK（术中×2+PACU×2 时间轴，Steward 3 禁出→6 可出，越界 4672）')

# 临床路径变异
tpl = ok(call('POST', '/pathways', {'name': 'E2E路径' + stamp, 'icdPrefix': 'K80',
                                    'description': 'E2E', 'items': []}, t), '建路径')
tpl_id = [p for p in ok(call('GET', '/pathways', token=t), '路径列表') if p['name'] == 'E2E路径' + stamp][0]['id']
ok(call('POST', f"/pathways/{tpl_id}/apply/{adm['id']}", {}, t), '入径')
r = call('POST', f"/pathways/{tpl_id}/apply/{adm['id']}", {}, t)
assert r['code'] == 9906, '重复入径应拦截'
en = next(e for e in ok(call('GET', '/pathways/enrollments', token=t), '入径列表')
          if e['admission_id'] == adm['id'])
ok(call('POST', f"/pathways/enrollments/{en['id']}/variances",
        {'dayNo': 2, 'varType': 'DELAY', 'reason': '检查延迟一天'}, t), '记变异')
ok(call('PUT', f"/pathways/enrollments/{en['id']}/complete", token=t), '完成路径')
vs = next(v for v in ok(call('GET', '/pathways/variance-stats', token=t), '变异统计')
          if v['pathway_name'] == 'E2E路径' + stamp)
assert vs['enrolled'] == 1 and vs['varied'] == 1 and float(vs['variance_rate']) == 100.0, vs
print('[卅-3] 临床路径变异 OK（入径留痕→变异登记→完成，重复入径 9906，变异率统计）')

# 体检团检
pkg = ok(call('POST', '/exam/packages', {'name': 'E2E入职套餐' + stamp, 'price': 299,
                                         'items': '血常规,肝功能,胸片'}, t), '建套餐')
pkg_id = [p for p in ok(call('GET', '/exam/packages', token=t), '套餐') if p['name'] == 'E2E入职套餐' + stamp][0]['id']
grp = ok(call('POST', '/exam/groups', {'unitName': 'E2E科技公司' + stamp, 'contact': '王主管',
                                       'packageId': pkg_id,
                                       'memberNames': ['团检甲' + stamp, '团检乙' + stamp, '团检丙' + stamp]}, t), '团检建档')
assert grp['members'] == 3
recs = ok(call('GET', f"/exam/groups/{grp['groupId']}/records", token=t), '团检名单')
ok(call('PUT', f"/exam/records/{recs[0]['id']}/complete?summary=" + q('各项正常') + '&abnormal=false', token=t), '完成1')
ok(call('PUT', f"/exam/records/{recs[1]['id']}/complete?summary=" + q('血压偏高') + '&abnormal=true', token=t), '完成2')
gs = next(x for x in ok(call('GET', '/exam/groups', token=t), '团检汇总') if x['id'] == grp['groupId'])
assert gs['members'] == 3 and gs['done'] == 2 and gs['abnormal_cnt'] == 1 and float(gs['abnormal_rate']) == 50.0, gs
print('[卅-4] 体检团检 OK（3 人建档→2 人完成→异常率 50%）')

# ============ 三十一期：脱敏 / 敏感审计 ============

# 数据脱敏：非 ADMIN 用户看列表手机号打码
opname = 'e2emask' + stamp
# 1.0.9 起患者列表限临床/收费等角色：无角色账号本就不该看全院患者，故给护士角色（仍非 ADMIN）
ok(call('POST', '/system/users', {'username': opname, 'password': 'Abcd1234', 'realName': '脱敏测试',
                                  'roleCodes': ['NURSE']}, t), '建普通用户')
t2 = ok(call('POST', '/auth/login', {'username': opname, 'password': 'Abcd1234'}), '普通登录')['token']
# v27-A：初始口令未改前业务接口被 1009 兜底拦截；改密后旧 token 失效须重新登录
r = call('GET', '/patients?keyword=x', token=t2)
assert r['code'] == 1009, f'强制改密兜底应拦业务接口: {r}'
ok(call('POST', '/auth/change-password', {'oldPassword': 'Abcd1234', 'newPassword': 'Abcd1235'}, t2), '首登改密')
t2 = ok(call('POST', '/auth/login', {'username': opname, 'password': 'Abcd1235'}), '改密后登录')['token']
print('[卅一-1.5] 首登强制改密 OK（改前业务接口 1009，改后放行）')
masked = ok(call('GET', '/patients?keyword=' + q('患者服务' + stamp), token=t2), '脱敏列表')['records'][0]
assert '****' in masked['phone'] and phone not in masked['phone'], masked
plain = ok(call('GET', '/patients?keyword=' + q('患者服务' + stamp), token=t), '明文列表')['records'][0]
assert plain['phone'] == phone
print(f"[卅一-2] 数据脱敏 OK（普通用户见 {masked['phone']}，ADMIN 见明文）")

# 敏感操作审计：作废/授权/退费等路径可单独过滤
sens = ok(call('GET', '/audit/logs?sensitive=true', token=t), '敏感审计')['list']   # v32：返回 {list,total}
assert len(sens) >= 1
keywords = ['/refund', '/roles', '/menus', '/abx-privileges', '/system/users', '/insurance/catalog', '/cancel']
assert all(any(k in s['path'] for k in keywords) for s in sens), sens[:3]
print(f"[卅一-3] 敏感操作审计 OK（{len(sens)} 条，含支付作废/用户管理等路径）")

# 收尾：出院释放床位
discharge_cleanup(t, adm['id'])

print('\n=== 二十九至三十一期 E2E 全部通过 ===')
