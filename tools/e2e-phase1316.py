# -*- coding: utf-8 -*-
"""十三至十六期 E2E：打印数据/日结CSV/审计/登录锁定/密码策略、LIS 标本流转、RIS 报告、手麻、病历模板"""
import json
import sys
import datetime
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8080/api'
sys.stdout.reconfigure(encoding='utf-8')


def call(method, path, body=None, token=None, raw=False):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode('utf-8') if body is not None else None
    with urllib.request.urlopen(req, data=data) as resp:
        body_bytes = resp.read()
        return body_bytes.decode('utf-8') if raw else json.loads(body_bytes.decode('utf-8'))


def ok(r, step):
    assert r['code'] == 0, f'{step}: {r}'
    return r['data']


q = urllib.parse.quote
t = ok(call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'}), '登录')['token']
today = datetime.date.today().isoformat()

# 准备：挂号→接诊→开检验+检查→收费
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': 2, 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('肝功能'), token=t), '检验项')[0]
exam = ok(call('GET', '/masterdata/charge-items?keyword=' + q('彩超'), token=t), '检查项')[0]
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1}]}, t), '开单')
charge = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')
lab_order = next(o for o in orders if o['orderType'] == 'LAB')

# ---- 十三期：打印数据集 ----
slip = ok(call('GET', f'/print/registration/{rid}', token=t), '挂号凭条')
assert slip['patient_name'] == '张三' and slip['reg_no'] == reg['regNo']
receipt = ok(call('GET', f"/print/charge/{charge['id']}", token=t), '收费票据')
assert len(receipt['items']) == 3 and float(receipt['total_amount']) > 0  # 挂号费+检验+检查
print(f"[十三-1] 打印数据集 OK（票据 {receipt['charge_no']} 共 {len(receipt['items'])} 行）")

# ---- 十三期：日结 + CSV ----
daily = ok(call('GET', '/reports/daily-settlement', token=t), '日结')
assert float(daily['total']['paid']) > 0
csv_text = call('GET', '/reports/daily-settlement.csv', token=t, raw=True)
assert charge['chargeNo'] in csv_text
print(f"[十三-2] 日结报表 OK（今日实收 ¥{daily['total']['paid']}），CSV 含本单")

# ---- 十三期：审计日志 ----
logs = ok(call('GET', '/audit/logs?username=admin', token=t), '审计')
assert any(l['path'].endswith('/settle') for l in logs), '审计应记录收费操作'
print(f"[十三-3] 审计日志 OK（admin 最近 {len(logs)} 条写操作留痕）")

# ---- 十三期：密码策略 + 登录锁定 ----
r = call('POST', '/system/users', {'username': 'weakpwd', 'password': '123456', 'realName': '弱密码'}, t)
assert r['code'] == 1102, '弱密码应被拒'
lockuser = 'locktest' + datetime.datetime.now().strftime('%H%M%S')
ok(call('POST', '/system/users', {'username': lockuser, 'password': 'Abcd1234', 'realName': '锁定测试'}, t), '建测试用户')
for i in range(5):
    r = call('POST', '/auth/login', {'username': lockuser, 'password': 'wrong'})
    assert r['code'] == 1001
r = call('POST', '/auth/login', {'username': lockuser, 'password': 'Abcd1234'})
assert r['code'] == 1002, f'第 6 次应锁定: {r}'
print('[十三-4] 密码策略(1102) + 5 次失败锁定(1002) OK')

# ---- 十四期：LIS 标本流转 ----
pend = ok(call('GET', '/lis/pending', token=t), 'LIS待采样')
assert any(p['order_id'] == lab_order['id'] for p in pend)
bc = ok(call('POST', f"/lis/samples?orderId={lab_order['id']}", {}, t), '采样')['barcode']
ok(call('PUT', f'/lis/samples/{bc}/receive', token=t), '核收')
ok(call('POST', f'/lis/samples/{bc}/publish', {'results': [
    {'code': 'ALT', 'name': '丙氨酸氨基转移酶', 'value': '210', 'unit': 'U/L', 'refRange': '9-50', 'flag': 'HH'},
    {'code': 'AST', 'name': '天冬氨酸氨基转移酶', 'value': '45', 'unit': 'U/L', 'refRange': '15-40', 'flag': 'H'}]}, t), '发布')
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
o = next(x for x in ws['orders'] if x['id'] == lab_order['id'])
assert o['status'] == 'EXECUTED', 'LIS 发布应自动执行医嘱'
alerts = ok(call('GET', '/outpatient/critical-alerts?status=NEW', token=t), '危急值')
assert any('丙氨酸氨基转移酶' in a['content'] for a in alerts), '手工通道 HH 也应触发危急值'
report = ok(call('GET', f"/print/lab-report/{lab_order['id']}", token=t), '报告单')
assert len(report['results']) == 2
print(f"[十四-1] LIS 采样({bc})→核收→录入发布→自动执行+危急值+报告单 OK")

# ---- 十四期：RIS 报告 ----
wl = ok(call('GET', '/ris/worklist', token=t), 'RIS队列')
exam_row = next(w for w in wl if w['group_no'] == next(o['groupNo'] for o in orders if o['orderType'] == 'EXAM'))
ok(call('PUT', f"/ris/exams/{exam_row['id']}/report",
        {'findings': '肝实质回声均匀，胆囊壁光滑', 'impression': '肝胆未见明显异常'}, t), '写报告')
ok(call('PUT', f"/ris/exams/{exam_row['id']}/verify", token=t), '审核')
ws2 = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区2')
assert next(x for x in ws2['orders'] if x['orderType'] == 'EXAM')['status'] == 'EXECUTED'
print('[十四-2] RIS 登记→报告→审核→医嘱执行 OK')

# ---- 十五期：手麻 ----
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']
free = None
for w in wards:
    beds = ok(call('GET', f"/inpatient/beds?wardId={w['id']}", token=t), '床')
    free = next((b for b in beds if b['status'] == 'FREE'), None)
    if free:
        break
assert free, '无空床（请先清理在院测试数据）'
adm = ok(call('POST', '/inpatient/admissions', {'patientId': 2, 'deptId': 2, 'bedId': free['id'],
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
ok(call('POST', '/inpatient/surgeries', {'admissionId': adm['id'], 'procedureName': '腹腔镜胆囊切除术',
                                         'anesthesiaType': '全身麻醉', 'scheduledAt': None}, t), '手术申请')
sg = ok(call('GET', '/inpatient/surgeries', token=t), '手术列表')[0]
ok(call('PUT', f"/inpatient/surgeries/{sg['id']}/complete",
        {'opNote': '手术顺利', 'anesNote': '麻醉平稳'}, t), '术后记录')
ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge", token=t), '出院清场')
print('[十五-1] 手术申请→术后记录闭环 OK')

# ---- 十五期：病历模板 ----
ok(call('POST', '/emr-templates', {'deptId': 1, 'name': '上感门诊模板',
                                   'content': '{"chiefComplaint":"咽痛发热_天","advice":"多饮水，对症治疗"}'}, t), '建模板')
tpls = ok(call('GET', '/emr-templates?deptId=1', token=t), '模板列表')
assert any(x['name'] == '上感门诊模板' for x in tpls)
print('[十五-2] 病历模板 OK')

print('\n=== 十三至十六期 E2E 全部通过 ✔ ===')
