# -*- coding: utf-8 -*-
"""演示/验收数据一键引导（幂等）：基础患者、员工、体检套餐、抽检计划等。
用法：python tools/bootstrap-demo.py（后端须运行）
"""
import json
import sys
import urllib.request

BASE = 'http://localhost:8080/api'
sys.stdout.reconfigure(encoding='utf-8')


def call(m, p, b=None, t=None):
    r = urllib.request.Request(BASE + p, method=m)
    r.add_header('Content-Type', 'application/json')
    if t:
        r.add_header('Authorization', 'Bearer ' + t)
    try:
        with urllib.request.urlopen(r, data=json.dumps(b).encode() if b is not None else None) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {'code': e.code, 'message': e.read().decode('utf-8', 'replace')[:120]}


t = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})['data']['token']

# 基础患者（CI 同款：1 号 Test / 2 号 张三）
patients = call('GET', '/patients?keyword=&page=0&size=5', t=t)['data']['records']
if not any(p.get('patientNo') == 'P00000002' for p in patients):
    call('POST', '/patients', {'name': 'Test', 'sex': 'U', 'idType': 'OTHER'}, t)
    call('POST', '/patients', {'name': '张三', 'sex': 'M', 'idType': 'ID_CARD',
                               'idNo': '510181199003078514', 'phone': '13800138000',
                               'insuranceType': 'YB_RESIDENT'}, t)
    print('基础患者：已创建 Test / 张三')
else:
    print('基础患者：已存在，跳过')

# 演示员工
emps = call('GET', '/hr/employees?keyword=G000', t=t)['data']
if not emps:
    for no, name, title in [('G0001', '演示医生', '主治医师'), ('G0002', '演示护士', '主管护师')]:
        call('POST', '/hr/employees', {'empNo': no, 'name': name, 'sex': 'U', 'deptId': 1,
                                       'title': title, 'phone': '13800000001'}, t)
    print('演示员工：已创建 G0001/G0002')
else:
    print('演示员工：已存在，跳过')

# 体检套餐
pkgs = call('GET', '/exam/packages', t=t)['data']
if not pkgs:
    call('POST', '/exam/packages', {'name': '入职体检套餐', 'price': 299, 'items': '血常规,肝功能,胸片'}, t)
    print('体检套餐：已创建')
else:
    print(f'体检套餐：已有 {len(pkgs)} 个，跳过')

# 三十八期：多角色演示账号（幂等）
DEMO_USERS = [
    ('doctor01', '演示门诊医生', ['DOCTOR_OUTP']),
    ('nurse01', '演示护士', ['NURSE']),
    ('cashier01', '演示收费员', ['CASHIER']),
    ('pharm01', '演示药师', ['PHARMACIST']),
    ('tech01', '演示医技', ['TECHNICIAN']),
    ('quality01', '演示质控院感', ['QUALITY']),
    ('ops01', '演示运营后勤', ['OPERATION']),
]
existing = {u['username'] for u in call('GET', '/system/users?page=0&size=100', t=t)['data']['records']}
created = 0
for username, real_name, roles in DEMO_USERS:
    if username in existing:
        continue
    r = call('POST', '/system/users', {'username': username, 'password': 'Demo1234',
                                       'realName': real_name, 'roleCodes': roles}, t)
    if r['code'] == 0:
        created += 1
print(f'演示账号：新建 {created} 个（统一密码 Demo1234，含医生/护士/收费/药师/医技/质控/运营）')

print('演示数据引导完成 ✔')
