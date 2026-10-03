# -*- coding: utf-8 -*-
"""演示/验收数据一键引导（幂等）：基础患者、员工、体检套餐、抽检计划等。
用法：python tools/bootstrap-demo.py（后端须运行）
"""
import json
import os
import sys
import urllib.parse
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
    # v75 车道B：路径里可能带中文检索词（按名称查项目/患者），统一转义；%、?、&、= 原样保留
    r = urllib.request.Request(BASE + urllib.parse.quote(p, safe='/?&=:%'), method=m)
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
# v75 车道B：幂等判据改为"按身份证号精确找张三"。原先只看最新 5 条里有没有 P00000002，老库里新患者一多
# 就恒为"没有"，每跑一遍都多建一个 Test（开发库里 Test 已有一长串）。Test 无证件号、无法按号查重，
# 故与张三同进退：张三在则整段跳过。
ZS_IDNO = '510181199003078511'
zs_rows = call('GET', f'/patients?keyword={ZS_IDNO}&page=0&size=5', t=t)['data']['records']
if not zs_rows:
    call('POST', '/patients', {'name': 'Test', 'sex': 'U', 'idType': 'OTHER'}, t)
    call('POST', '/patients', {'name': '张三', 'sex': 'M', 'idType': 'ID_CARD',
                               'idNo': ZS_IDNO, 'phone': '13800138000',
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

# v75 车道B：演示科室「检验科」「影像科」——种子里没有这两个科室，检验/检查类收费项目的执行科室无处可配，
# 申请单/导诊单"前往科室"恒印"—"。走产品自己的 /system/depts 写入口（同「系统管理 → 科室管理」新增）。
# 幂等：按 code 判重，已有（含已停用的）一律不再建。type=MEDTECH 与门诊药房同类（医技科室）。
DEMO_DEPTS = [('LAB', '检验科'), ('RIS', '影像科')]
dept_rows = call('GET', '/system/depts', t=t).get('data') or []
n_dept = 0
for code, name in DEMO_DEPTS:
    if any(d.get('code') == code for d in dept_rows):
        continue
    r = call('POST', '/system/depts', {'name': name, 'code': code, 'type': 'MEDTECH', 'sortNo': 90}, t)
    assert r['code'] == 0, f'建科室 {code} 失败: {r}'
    n_dept += 1
print(f'演示科室：新建 {n_dept} 个（检验科 LAB / 影像科 RIS；已有则跳过）')

# v74 审阅修补（1026★）：收费项目执行科室——治疗单/导诊单"前往科室"取自 md_charge_item.exec_dept_id，
# 此前种子全空、产品内无写入路径，单据上恒印"—"。现在走产品自己的写入口（attrs 接口，同管理员在
# 「基础数据 → 收费项目 → 维护属性」里点的那一下）给种子项目配上。
# v75 车道B：补上检验/检查类——C0001–C0005（检验）→ 检验科 LAB；C0101–C0104、C0301（检查/病理活检）→ 影像科 RIS；
# 治疗类 C0201–C0203 仍在内科门诊 OUTP_IM 做。
# 只补空值（execDeptId 已配的不覆盖），幂等。
dept_rows = call('GET', '/system/depts', t=t).get('data') or []
dept_by_code = {d.get('code'): d for d in dept_rows if d.get('enabled', True)}
EXEC_PLAN = [  # (收费项目 category, 项目编码, 执行科室 code)
    ('TREAT', ('C0201', 'C0202', 'C0203'), 'OUTP_IM'),
    ('LAB', ('C0001', 'C0002', 'C0003', 'C0004', 'C0005'), 'LAB'),
    ('EXAM', ('C0101', 'C0102', 'C0103', 'C0104', 'C0301'), 'RIS'),
]
for category, codes, dept_code in EXEC_PLAN:
    dept = dept_by_code.get(dept_code)
    if not dept:
        print(f'收费项目执行科室（{category}）：未找到启用的科室 {dept_code}，跳过')
        continue
    n_cfg = n_have = 0
    for it in call('GET', f'/masterdata/charge-items?category={category}&keyword=', t=t).get('data') or []:
        if it.get('code') not in codes:
            continue
        if it.get('execDeptId'):
            n_have += 1
            continue
        # attrs 的 feeCategoryCode 是整体替换，须把原值带回，否则会顺手清掉挂类
        body = {'feeCategoryCode': it.get('feeCategoryCode'), 'execDeptId': dept['id']}
        r = call('PUT', f"/masterdata/charge-items/{it['id']}/attrs", body, t)
        if r.get('code') == 0:
            n_cfg += 1
        else:
            print(f"  {it['code']} 配执行科室失败: {r}")
    print(f"收费项目执行科室（{category} → {dept['name']}）：新配 {n_cfg} 项，已配跳过 {n_have} 项")

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

# =====================================================================================================
# v75 车道B：演示前置一键就绪（全部幂等：已有的一律跳过，只新增/只补空，不改动、不删除既有数据）
#   ① 张三补出生日期   ② 五张单据前置（排班→挂号→接诊→开四类医嘱→收费）   ③ 在院演示患者
# 对应培训脚本「演示前置（一键）」一节。所有 id 一律按名称/编码用接口查，不写死数字 id。
# =====================================================================================================
import datetime

TODAY = datetime.date.today().isoformat()


def login_as(username, password):
    r = call('POST', '/auth/login', {'username': username, 'password': password})
    return r['data']['token'] if r.get('code') == 0 else None


# ---- ① 演示患者张三补出生日期 1990-03-07（身份证号本就是这个生日，老库里该列为空，各单据"年龄"印"—"）----
# 走患者更新接口 PUT /patients/{id}，请求体只带 birthDate（该接口对 null 字段不覆盖；身份三项不动，故不触发改身份限权）。
# 只在为空时补，已有出生日期（含手工改过的）不覆盖。
zs = next(iter(call('GET', f'/patients?keyword={ZS_IDNO}&page=0&size=5', t=t)['data']['records']), None)
if zs is None:
    print('张三出生日期：未找到张三，跳过')
elif zs.get('birthDate'):
    print(f"张三出生日期：已有 {zs['birthDate']}，跳过")
else:
    r = call('PUT', f"/patients/{zs['id']}", {'birthDate': '1990-03-07'}, t)
    assert r['code'] == 0, f'张三补出生日期失败: {r}'
    print('张三出生日期：已补 1990-03-07')


# ---- ② 五张单据（处方笺/检验申请单/检查申请单/治疗单/导诊单）演示前置一键就绪 ----
def ensure_five_docs_ready():
    """admin 排今日班 → 张三挂今日号 → doctor01 接诊 → 开四类医嘱各一条 → 收费台现金结算。
    每步打印一行；任何一步前置条件不满足就说明原因并停在那一步（不抛异常，不影响后面的住院演示）。"""
    doc = records.get('doctor01')
    if not doc:
        print('五张单据·排班：未找到 doctor01，停止')
        return
    if zs is None:
        print('五张单据·排班：未找到张三，停止')
        return
    doc_dept = doc.get('deptId') or 1   # doctor01 挂内科门诊（见上方演示账号）

    # 1) admin 给 doctor01 排今日内科门诊班（GET/POST /outpatient/schedules；已有今日该医生启用排班则跳过）
    #    POST 请求体 = OutpSchedule 实体字段：deptId/doctorId/scheduleDate/shift(FULL 全天)/regType/fee/capacity
    scheds = call('GET', f'/outpatient/schedules?date={TODAY}&deptId={doc_dept}', t=t).get('data') or []
    sch = next((s for s in scheds if s.get('doctorId') == doc['id']), None)
    if sch:
        print(f"五张单据·排班：今日已有 doctor01 排班 #{sch['id']}（{sch.get('deptName')}），跳过")
    else:
        r = call('POST', '/outpatient/schedules', {'deptId': doc_dept, 'doctorId': doc['id'], 'scheduleDate': TODAY,
                                                   'shift': 'FULL', 'regType': 'GENERAL', 'fee': 10,
                                                   'capacity': 30}, t)
        assert r['code'] == 0, f'排班失败: {r}'
        sch = r['data']
        print(f"五张单据·排班：已给 doctor01 排今日内科门诊班 #{sch['id']}（全天/普通号/号源 30/挂号费 10 元）")

    # 2) 张三挂今日号：先查 doctor01 的今日接诊队列（mine=true），已有张三未退号的挂号就用那一条
    dt = login_as('doctor01', 'Demo1234')
    if not dt:
        print('五张单据·挂号：doctor01 登录失败（口令非 Demo1234？），停止')
        return
    wl = call('GET', f'/outpatient/doctor/worklist?date={TODAY}&mine=true', t=dt).get('data') or []
    mine = next((w for w in wl if w.get('patientId') == zs['id']), None)
    if mine:
        rid = mine['registrationId']
        print(f"五张单据·挂号：张三今日已有挂号 #{rid}（状态 {mine['status']}），跳过并沿用")
    else:
        r = call('POST', '/outpatient/registrations', {'patientId': zs['id'], 'scheduleId': sch['id']}, t)
        assert r['code'] == 0, f'挂号失败: {r}'
        rid = r['data']['id']
        print(f"五张单据·挂号：已给张三挂今日号 #{rid}（号 {r['data'].get('regNo')}）")

    # 3) doctor01 接诊：POST /outpatient/doctor/{rid}/start（已接诊再调是空操作，天然幂等）
    if mine and mine.get('status') == 'VISITED':
        print(f'五张单据·接诊：#{rid} 已是已接诊状态，跳过')
    else:
        r = call('POST', f'/outpatient/doctor/{rid}/start', {}, dt)
        assert r['code'] == 0, f'接诊失败: {r}'
        print(f"五张单据·接诊：doctor01 已接诊 #{rid}（状态 {r['data']['status']}）")

    # 3b) 病历 + 主诊断：处方笺/各申请单要印诊断，空诊断的演示单据没有说服力。已有病历则不碰
    ws = call('GET', f'/outpatient/doctor/{rid}/workspace', t=dt)['data']
    if ws.get('emr') is None and not ws.get('diagnoses'):
        icd = next(iter(call('GET', '/masterdata/icd10?keyword=上呼吸道', t=t).get('data') or []), None)
        body = {'emr': {'chiefComplaint': '咽痛发热2天', 'presentIllness': '受凉后咽痛、发热，体温最高 38.2℃',
                        'pastHistory': '无特殊', 'physicalExam': '咽部充血，扁桃体不大', 'advice': '多饮水，按医嘱用药'},
                'diagnoses': [{'icdCode': icd['code'], 'icdName': icd['name']}] if icd else []}
        r = call('PUT', f'/outpatient/doctor/{rid}/emr', body, dt)
        print(f"五张单据·病历：{'已写病历与主诊断 ' + icd['name'] if r['code'] == 0 else '写病历失败 ' + str(r)}")
        ws = call('GET', f'/outpatient/doctor/{rid}/workspace', t=dt)['data']
    else:
        print('五张单据·病历：已有病历/诊断，跳过')

    # 4) 开四类医嘱各一条（POST /outpatient/doctor/{rid}/orders，body {lines:[OrderLine...]}）。
    #    已有未作废的该类医嘱就不再开。所有 id 按编码/名称查：药品 D0001 阿莫西林胶囊（1 级，避开 2 级抗菌药，
    #    doctor01 无限制级处方权会 4014）；检验=肝功能全套；检查=腹部彩超；治疗=静脉输液。
    have = {o['orderType'] for o in ws.get('orders', []) if o.get('status') != 'CANCELLED' and o.get('orderType') != 'REG'}
    drug = next((d for d in call('GET', '/masterdata/drugs?keyword=阿莫西林', t=t).get('data') or []
                 if d.get('code') == 'D0001'), None)

    def charge_item(category, name):
        return next((c for c in call('GET', f'/masterdata/charge-items?category={category}&keyword={name}', t=t)
                     .get('data') or [] if c.get('name') == name), None)

    items = {'LAB': charge_item('LAB', '肝功能全套'), 'EXAM': charge_item('EXAM', '腹部彩超'),
             'TREAT': charge_item('TREAT', '静脉输液')}
    # 键名 = DoctorStationService.OrderLine：remark/urgent/clinicalSummary/examPurpose/notice/specimenType/samplingSite
    lines = {
        'DRUG': drug and {'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'tid',
                          'dosePerTime': '0.5g', 'days': 3, 'remark': '演示：饭后服用', 'urgent': False},
        'LAB': items['LAB'] and {'orderType': 'LAB', 'itemId': items['LAB']['id'], 'qty': 1, 'remark': '演示：晨起空腹采血',
                                 'specimenType': '血清', 'samplingSite': '肘静脉', 'clinicalSummary': '肝功能复查',
                                 'urgent': False},
        'EXAM': items['EXAM'] and {'orderType': 'EXAM', 'itemId': items['EXAM']['id'], 'qty': 1,
                                   'remark': '演示：检查前空腹', 'examPurpose': '腹部脏器筛查',
                                   'clinicalSummary': '咽痛发热2天，既往体健', 'notice': '检查前空腹6小时，充盈膀胱',
                                   'urgent': False},
        'TREAT': items['TREAT'] and {'orderType': 'TREAT', 'itemId': items['TREAT']['id'], 'qty': 1,
                                     'remark': '演示：滴速60滴/分', 'notice': '输液前确认无药物过敏', 'urgent': False},
    }
    missing_src = [k for k, v in lines.items() if not v]
    if missing_src:
        print(f'五张单据·开单：按名称/编码没查到项目 {missing_src}，缺的类别跳过')
    todo = [v for k, v in lines.items() if v and k not in have]
    if not todo:
        print(f'五张单据·开单：四类医嘱已齐（已有 {sorted(have)}），跳过')
    else:
        r = call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': todo}, dt)
        if r['code'] == 0:
            print(f"五张单据·开单：doctor01 已开 {len(todo)} 条（{'/'.join(o['orderType'] for o in r['data'])}）；"
                  f"原已有 {sorted(have) or '无'}")
        else:
            print(f"五张单据·开单：失败 {r.get('code')} {r.get('message')}")
            return

    # 5) 收费台结算（POST /outpatient/charges/settle，payMethod=CASH；admin 有权，等同收费员点「收费」）。
    #    先看待收明细——没有待收行（已结算过）就跳过
    unpaid = call('GET', f'/outpatient/charges/unpaid?registrationId={rid}', t=t)['data']
    if not unpaid.get('orders'):
        print('五张单据·收费：无待收项目（已结算过），跳过')
    else:
        r = call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t)
        if r['code'] == 0:
            print(f"五张单据·收费：已现金结算 {r['data']['chargeNo']} ¥{r['data']['totalAmount']}"
                  "（检验/检查进医技队列，药品进药房待发药，治疗进护士站待执行）")
        else:
            print(f"五张单据·收费：失败 {r.get('code')} {r.get('message')}")


ensure_five_docs_ready()


# ---- ③ 住院演示：办一名在院患者（住院医生站用 admin 演示——doctor01 无该菜单，也不新造角色）----
# 演示患者「演示住院患者」按姓名精确查（建档无证件号，无法靠证件号幂等）；已有 IN_HOSPITAL 记录就跳过。
# 入院 POST /inpatient/admissions，body = AdmitRequest：patientId/deptId/bedId/doctorId/diagIcd/diagName/deposit/payMethod。
# 床位取内科病区(W_IM)第一张空床；收治科室内科门诊(OUTP_IM)；主管医生 admin。
def ensure_inpatient_demo():
    name = '演示住院患者'
    rows = [p for p in call('GET', f'/patients?keyword={name}&page=0&size=20', t=t)['data']['records']
            if p.get('name') == name]
    if rows:
        pat = rows[0]
    else:
        r = call('POST', '/patients', {'name': name, 'sex': 'F', 'idType': 'OTHER', 'birthDate': '1968-11-20',
                                       'phone': '13800138001'}, t)
        assert r['code'] == 0, f'建住院演示患者失败: {r}'
        pat = r['data']
        print(f"住院演示：已建档 {name}（{pat['patientNo']}）")
    cur = [a for a in call('GET', '/inpatient/admissions', t=t)['data'] if a.get('patientId') == pat['id']]
    if cur:
        print(f"住院演示：{name} 已在院（{cur[0]['admissionNo']}，{cur[0].get('wardName')} {cur[0].get('bedNo')} 床），跳过")
        return
    depts = {d['code']: d for d in call('GET', '/system/depts', t=t)['data']}
    ward, im = depts.get('W_IM'), depts.get('OUTP_IM')
    if not ward or not im:
        print('住院演示：未找到内科病区 W_IM / 内科门诊 OUTP_IM，跳过')
        return
    bed = next((b for b in call('GET', f"/inpatient/beds?wardId={ward['id']}", t=t)['data'] if b['status'] == 'FREE'), None)
    if not bed:
        print('住院演示：内科病区无空床（请先清理在院测试数据），跳过')
        return
    r = call('POST', '/inpatient/admissions', {
        'patientId': pat['id'], 'deptId': im['id'], 'bedId': bed['id'],
        'doctorId': (records.get('admin') or {}).get('id', 1), 'diagIcd': 'J18.9', 'diagName': '肺炎',
        'deposit': 2000, 'payMethod': 'CASH'}, t)
    if r['code'] == 0:
        print(f"住院演示：已办入院 {r['data']['admissionNo']}（{ward['name']} {bed['bedNo']} 床，押金 2000 元）")
    else:
        print(f"住院演示：入院失败 {r.get('code')} {r.get('message')}")


ensure_inpatient_demo()

print('演示数据引导完成 ✔')


# v76 复核（993★ 第三轮审计者）：全新库零模板时门诊医生站模板栏整条不渲染（v-if 按模板数），评委按默认路径会以为功能不存在。
# 种一张全院（HOSPITAL）门诊模板，标签行格式与「存为模板」产物一致；按名称幂等。
def ensure_demo_outp_template():
    name = '演示门诊上感模板'
    have = call('GET', '/emr-templates/visible?type=EMR&keyword=' + name, t=t)
    if any(x.get('name') == name for x in (have.get('data') or [])):
        print('  门诊演示模板：已有，跳过'); return
    content = ('主诉：咽痛、发热 2 天\n'
               '现病史：2 天前受凉后出现咽痛、发热，体温最高 38.5℃，伴鼻塞流涕，无胸闷气促。\n'
               '既往史：无特殊\n'
               '体格检查：咽部充血，扁桃体 I 度肿大，双肺呼吸音清\n'
               '处理意见：多饮水，对症治疗，3 天后复诊')
    r = call('POST', '/emr-templates', {'name': name, 'content': content, 'templateType': 'EMR',
                                        'scope': 'HOSPITAL', 'deptId': None, 'recordType': 'OUTP'}, t)
    assert r.get('code') == 0, f'门诊演示模板创建失败: {r}'
    print('  门诊演示模板：已创建（全院可见）')

ensure_demo_outp_template()
