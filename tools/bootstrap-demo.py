# -*- coding: utf-8 -*-
"""演示/验收数据一键引导（幂等）：基础患者、员工、体检套餐、抽检计划等。
用法：python tools/bootstrap-demo.py（后端须运行）
"""
import json
import os
import sys
import urllib.request

# 地址优先级：HIP_E2E_BASE > HIP_BASE > 8080 默认。**为什么先认 HIP_E2E_BASE**：本脚本被
# e2e-phase38 / e2e-phase3537 以子进程调用，而 E2E 全家走 e2elib 统一读 HIP_E2E_BASE；两个变量名不一致时，
# 在非 8080 端口的实例上跑整套（如全新库验收起在 8085）子进程仍连 8080 必然失败。CI 恰好起在 8080 才没暴露。
BASE = os.environ.get('HIP_E2E_BASE') or os.environ.get('HIP_BASE', 'http://localhost:8080/api')
# 口令可被环境变量覆盖：在线演示会把 admin 默认口令改掉，改掉后仍要能重灌数据
USER = os.environ.get('HIP_USER', 'admin')
PASSWORD = os.environ.get('HIP_PASSWORD', 'admin123')
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


t = call('POST', '/auth/login', {'username': USER, 'password': PASSWORD})['data']['token']

# 基础患者（CI 同款：1 号 Test / 2 号 张三）
patients = call('GET', '/patients?keyword=&page=0&size=5', t=t)['data']['records']
if not any(p.get('patientNo') == 'P00000002' for p in patients):
    call('POST', '/patients', {'name': 'Test', 'sex': 'U', 'idType': 'OTHER'}, t)
    call('POST', '/patients', {'name': '张三', 'sex': 'M', 'idType': 'ID_CARD',
                               'idNo': '510181199003078511', 'phone': '13800138000',
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

# v74 审阅修补（1026★）：收费项目执行科室——治疗单/导诊单"前往科室"取自 md_charge_item.exec_dept_id，
# 此前种子全空、产品内无写入路径，单据上恒印"—"。现在走产品自己的写入口（attrs 接口，同管理员在
# 「基础数据 → 收费项目 → 维护属性」里点的那一下）给**已有科室能对上的**种子项目配上。
# 只配能诚实对上的：门诊治疗（静脉输液/肌肉注射/雾化吸入）在内科门诊 OUTP_IM 做；
# 种子科室里没有检验科/影像科，检验/检查类种子项目**不硬套**别的科室，保持未配置——
# 要演示检验/检查的前往科室，先在「系统管理 → 科室管理」建科室，再到收费项目页给项目选上。
# 只补空值（execDeptId 已配的不覆盖），幂等。
dept_rows = call('GET', '/system/depts', t=t).get('data') or []
im = next((d for d in dept_rows if d.get('code') == 'OUTP_IM' and d.get('enabled', True)), None)
if im:
    n_cfg = 0
    for it in call('GET', '/masterdata/charge-items?category=TREAT&keyword=', t=t).get('data') or []:
        if it.get('code') in ('C0201', 'C0202', 'C0203') and not it.get('execDeptId'):
            # attrs 的 feeCategoryCode 是整体替换，须把原值带回，否则会顺手清掉挂类
            body = {'feeCategoryCode': it.get('feeCategoryCode'), 'execDeptId': im['id']}
            r = call('PUT', f"/masterdata/charge-items/{it['id']}/attrs", body, t)
            if r.get('code') == 0:
                n_cfg += 1
    print(f'收费项目执行科室：新配 {n_cfg} 项（治疗类 → 内科门诊）')
else:
    print('收费项目执行科室：未找到内科门诊(OUTP_IM)，跳过')

# 三十八期：多角色演示账号（幂等）
# v74（993★ 复核）：演示医生必须挂科室——病历模板按登录人科室与授权过滤，无科室的医生看不到任何科室模板；
# 门诊排班、导诊单"挂号医师"也依赖它。deptId=1 即内科门诊（V8 种子）。其余账号暂不挂科室（无依赖）。
DEMO_USERS = [
    ('doctor01', '演示门诊医生', ['DOCTOR_OUTP'], 1),
    ('nurse01', '演示护士', ['NURSE']),
    ('cashier01', '演示收费员', ['CASHIER']),
    ('pharm01', '演示药师', ['PHARMACIST']),
    ('tech01', '演示医技', ['TECHNICIAN']),
    ('quality01', '演示质控院感', ['QUALITY']),
    ('ops01', '演示运营后勤', ['OPERATION']),
]
records = {u['username']: u for u in call('GET', '/system/users?page=0&size=100', t=t)['data']['records']}
existing = set(records)
created = 0
for username, real_name, roles, *rest in DEMO_USERS:
    dept_id = rest[0] if rest else None
    if username in existing:
        # v74：老库里的演示医生没有科室——补上（连同角色一并回传，更新接口按整体覆盖）
        if dept_id and records[username].get('deptId') is None:
            r0 = call('PUT', f"/system/users/{records[username]['id']}",
                      {'username': username, 'realName': real_name, 'deptId': dept_id, 'roleCodes': roles}, t)
            assert r0['code'] == 0, f'{username} 补科室失败: {r0}'
            print(f'  {username}: 已补科室 {dept_id}')
        # 自愈（第六轮审阅 P3）：首跑若在"建号成功、闭环未完成"间崩溃，
        # 该账号会永留强制改密标志——重跑时探测一次并补闭环，而不是跳过了事
        probe = call('POST', '/auth/login', {'username': username, 'password': 'Demo1234'})
        if probe.get('code') == 0 and probe['data'].get('mustChangePassword'):
            ut = probe['data']['token']
            call('POST', '/auth/change-password', {'oldPassword': 'Demo1234', 'newPassword': 'Demo1234a'}, ut)
            ut = call('POST', '/auth/login', {'username': username, 'password': 'Demo1234a'})['data']['token']
            r2 = call('POST', '/auth/change-password', {'oldPassword': 'Demo1234a', 'newPassword': 'Demo1234'}, ut)
            assert r2['code'] == 0, f'{username} 补闭环失败: {r2}'
            print(f'  {username}: 检出残留强制改密标志，已补闭环')
        continue
    r = call('POST', '/system/users', {'username': username, 'password': 'Demo1234',
                                       'realName': real_name, 'roleCodes': roles, 'deptId': dept_id}, t)
    if r['code'] == 0:
        created += 1
        # v27-A：管理员设的初始口令首登会被强制改密（业务接口一律 1009）。
        # 演示账号必须保持文档口令 Demo1234，故引导时替用户完成改密闭环：
        # 改成临时口令再改回来——强制标志清除、对外口令不变。
        ut = call('POST', '/auth/login', {'username': username, 'password': 'Demo1234'})['data']['token']
        r1 = call('POST', '/auth/change-password', {'oldPassword': 'Demo1234', 'newPassword': 'Demo1234a'}, ut)
        # 改密后旧 token 立即失效，须用新口令重新登录再改回
        ut = call('POST', '/auth/login', {'username': username, 'password': 'Demo1234a'})['data']['token']
        r2 = call('POST', '/auth/change-password', {'oldPassword': 'Demo1234a', 'newPassword': 'Demo1234'}, ut)
        assert r1['code'] == 0 and r2['code'] == 0, f'{username} 改密闭环失败: {r1} / {r2}'
print(f'演示账号：新建 {created} 个（统一密码 Demo1234，含医生/护士/收费/药师/医技/质控/运营）')

print('演示数据引导完成 ✔')
