# -*- coding: utf-8 -*-
"""二十二至二十六期 E2E：运维保障 / 临床闭环补缺 / 专科流程 / 管理院感 / 数据治理完整化"""
import datetime
import json
import subprocess
import sys
import urllib.parse
import urllib.request
from e2elib import BASE, call, discharge_cleanup, find_free_bed, new_patient, login, ok, q  # noqa: E402



def wsl(cmd):
    return subprocess.run(['wsl', '-d', 'Ubuntu', '--', 'sh', '-c', cmd],
                          capture_output=True, text=True, encoding='utf-8', errors='replace')


t = login()
today = datetime.date.today().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# ============ 二十二期：运维保障 ============

# 健康概览 + 故障告警联动
ok(call('POST', '/ops/faults', {'title': f'E2E 演练故障 {stamp}', 'level': 'HIGH'}, t), '登记故障')
h = ok(call('GET', '/ops/health-overview', token=t), '健康概览')
assert h['dbUp'] and any('高级别故障' in a for a in h['alerts']), h
fault = next(f for f in ok(call('GET', '/ops/faults', token=t), '故障列表') if f['status'] == 'OPEN')
ok(call('PUT', f"/ops/faults/{fault['id']}/resolve?note=" + q('E2E 已处理'), token=t), '处理故障')
r = call('PUT', f"/ops/faults/{fault['id']}/resolve?note=x", token=t)
assert r['code'] == 9950, '重复处理应拦截'
print('[廿二-1] 健康概览+故障台账 OK（HIGH 故障触发告警，处理后幂等拦截 9950）')

# 巡检台账
ok(call('POST', '/ops/inspections', {'item': '备份文件完整性', 'result': 'PASS', 'note': 'E2E'}, t), '巡检')
assert len(ok(call('GET', '/ops/inspections', token=t), '巡检列表')) >= 1
print('[廿二-2] 巡检台账 OK')

# 真实备份 + 恢复演练（本机经 WSL pg_dump → 恢复到校验库；CI 等无 WSL 环境降级为台账留痕）
try:
    has_wsl = wsl('true').returncode == 0
except FileNotFoundError:
    has_wsl = False
if has_wsl:
    dump = f'/tmp/hip_e2e_{stamp}.dump'
    r1 = wsl(f'PGPASSWORD=hip123456 pg_dump -h 127.0.0.1 -U hip -d hip -F c -f {dump}')
    assert r1.returncode == 0, f'pg_dump 失败: {r1.stderr}'
    size = int(wsl(f'stat -c %s {dump}').stdout.strip())
    ok(call('POST', '/ops/backups', {'fileName': dump, 'sizeBytes': size, 'status': 'SUCCESS', 'note': 'E2E 演练'}, t), '备份留痕')
    r2 = subprocess.run(['wsl', '-d', 'Ubuntu', '-u', 'root', '--', 'su', 'postgres', '-c',
                         'dropdb --if-exists hip_restore_check && createdb -O hip hip_restore_check'],
                        capture_output=True, text=True)
    assert r2.returncode == 0, f'重建校验库失败: {r2.stderr}'
    wsl(f'PGPASSWORD=hip123456 pg_restore -h 127.0.0.1 -U hip -d hip_restore_check --no-owner {dump}')
    users = wsl('PGPASSWORD=hip123456 psql -h 127.0.0.1 -U hip -d hip_restore_check -tAc "select count(*) from sys_user"').stdout.strip()
    assert int(users) >= 1, f'恢复校验失败: {users}'
    ok(call('POST', '/ops/backups', {'fileName': dump, 'sizeBytes': size, 'status': 'VERIFIED', 'note': f'恢复演练通过 sys_user={users}'}, t), '演练留痕')
    wsl(f'rm -f {dump}')
    drill = f'备份({size // 1024}KB)→恢复演练(sys_user={users})'
else:
    ok(call('POST', '/ops/backups', {'fileName': 'ci-mock.dump', 'sizeBytes': 1024, 'status': 'SUCCESS',
                                     'note': 'CI 环境无 WSL，仅验证台账链路'}, t), '备份留痕')
    drill = '备份台账（CI 降级）'
h2 = ok(call('GET', '/ops/health-overview', token=t), '健康概览2')
assert not any('无成功备份' in a for a in h2['alerts']), '备份后告警应消除'
assert ok(call('GET', '/ops/slow-apis', token=t), '慢接口') is not None
print(f'[廿二-3] {drill}→台账+告警消除 OK')

# ============ 二十三期：临床闭环 ============

# 复位 admin 抗菌处方权（保证可重复运行）
ok(call('PUT', '/outpatient/abx-privileges/1?level=1', token=t), '复位处方权')

# 准备：专用测试患者 + 挂号→接诊→开单（静脉青霉素类+检查+检验）→收费
pat = new_patient(t, 'E2E廿二' + stamp, 'M')
pid = pat['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 30}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
amx = ok(call('GET', '/masterdata/drugs?keyword=' + q('阿莫西林'), token=t), '药品')[0]
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('肝功能'), token=t), '检验项')[0]
exam = ok(call('GET', '/masterdata/charge-items?keyword=' + q('彩超'), token=t), '检查项')[0]
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': amx['id'], 'qty': 1, 'usageRoute': '静脉滴注', 'frequency': 'qd',
     'dosePerTime': '2.5g', 'days': 1},
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1}]}, t), '开单')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')
drug_order = next(o for o in orders if o['orderType'] == 'DRUG')
lab_order = next(o for o in orders if o['orderType'] == 'LAB')
exam_order = next(o for o in orders if o['orderType'] == 'EXAM')

# 门诊护士站：输液单 + 皮试拦截闭环
cands = ok(call('GET', '/outpatient/nurse/infusion-candidates', token=t), '输液候选')
assert any(c['order_id'] == drug_order['id'] for c in cands), '静脉医嘱应进输液候选'
ok(call('POST', f"/outpatient/nurse/infusions?orderId={drug_order['id']}", {}, t), '建输液单')
inf = next(i for i in ok(call('GET', '/outpatient/nurse/infusions', token=t), '输液单')
           if i['item_name'] == drug_order['itemName'] and i['status'] == 'PENDING')
r = call('PUT', f"/outpatient/nurse/infusions/{inf['id']}/start", token=t)
assert r['code'] == 4502, f'青霉素类无皮试应拦截: {r}'
ok(call('POST', '/outpatient/nurse/skin-tests', {'registrationId': rid, 'drugName': '青霉素'}, t), '登记皮试')
st = next(s for s in ok(call('GET', f'/outpatient/nurse/skin-tests?registrationId={rid}', token=t), '皮试')
          if s['result'] == 'PENDING')
ok(call('PUT', f"/outpatient/nurse/skin-tests/{st['id']}/result?result=NEG", token=t), '皮试阴性')
ok(call('PUT', f"/outpatient/nurse/infusions/{inf['id']}/start", token=t), '开始输液')
ok(call('POST', f"/outpatient/nurse/infusions/{inf['id']}/checks?note=" + q('滴速 40 滴/分，无不适'), token=t), '巡视')
ok(call('PUT', f"/outpatient/nurse/infusions/{inf['id']}/finish", token=t), '结束输液')
print('[廿三-1] 门诊护士站 OK（无皮试拦截 4502 → 阴性皮试 → 输液执行+巡视闭环）')

# 时段预约 + 重复拦截
ap = ok(call('POST', '/appointments', {'orderId': exam_order['id'], 'slotDate': today, 'period': 'AM'}, t), '预约检查')
r = call('POST', '/appointments', {'orderId': exam_order['id'], 'slotDate': today, 'period': 'PM'}, t)
assert r['code'] == 4511, f'重复预约应拦截: {r}'
ok(call('POST', '/appointments', {'orderId': lab_order['id'], 'slotDate': today, 'period': 'AM'}, t), '预约检验')
appts = ok(call('GET', f'/appointments?date={today}', token=t), '预约队列')
assert len(appts) >= 2
print(f"[廿三-2] 时段预约 OK（检查 AM 第{ap['seqNo']}号，重复预约拦截 4511）")

# 叫号扩展：药房/检查/检验
ph = ok(call('GET', '/queue-center/PHARMACY', token=t), '药房队列')
assert any(w['biz_key'] == drug_order['groupNo'] for w in ph['waiting']), '已收费处方应进取药队列'
ok(call('POST', '/queue-center/PHARMACY/call-next', {}, t), '药房叫号')
called = ok(call('POST', '/queue-center/EXAM/call-next', {}, t), '检查叫号')
assert called['patient_name']
ok(call('POST', '/queue-center/LAB/call-next', {}, t), '检验叫号')
print('[廿三-3] 叫号扩展 OK（药房/检查/检验三队列均可叫号，检查预约联动 CALLED）')

# 合理用血：全状态机（含违规拦截）
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pid, 'deptId': 2, 'bedId': free['id'],
                                                'diagIcd': 'I21.0', 'diagName': '急性心肌梗死',
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
ok(call('POST', '/inpatient/blood/applies', {'admissionId': adm['id'], 'product': 'RBC',
                                             'volumeMl': 200, 'reason': '术前备血'}, t), '用血申请')
ba = ok(call('GET', '/inpatient/blood/applies', token=t), '用血列表')[0]
r = call('PUT', f"/inpatient/blood/applies/{ba['id']}/issue", token=t)
assert r['code'] == 4532, f'未审批发血应拦截: {r}'
ok(call('PUT', f"/inpatient/blood/applies/{ba['id']}/review?approve=true&note=" + q('同意'), token=t), '审批')
ok(call('PUT', f"/inpatient/blood/applies/{ba['id']}/issue", token=t), '发血')
ok(call('PUT', f"/inpatient/blood/applies/{ba['id']}/transfuse?note=" + q('输注顺利无反应'), token=t), '输血记录')
print('[廿三-4] 合理用血 OK（申请→审批→发血→输血，越级发血拦截 4532）')

# 单病种：ICD 命中入组 → 建卡 → 上报
cands = ok(call('GET', '/quality/single-disease/candidates', token=t), '入组建议')
hit = next(c for c in cands if c['admission_id'] == adm['id'] and c['disease_code'] == 'AMI')
ok(call('POST', '/quality/single-disease/cases', {'admissionId': adm['id'], 'diseaseCode': 'AMI',
                                                  'dataset': '{"D2B(min)": 85}'}, t), '建卡')
r = call('POST', '/quality/single-disease/cases', {'admissionId': adm['id'], 'diseaseCode': 'AMI', 'dataset': 'x'}, t)
assert r['code'] == 4540, '重复建卡应拦截'
case = next(c for c in ok(call('GET', '/quality/single-disease/cases', token=t), '上报卡')
            if c['admission_id'] == adm['id'])
ok(call('PUT', f"/quality/single-disease/cases/{case['id']}/report", token=t), '上报')
print(f"[廿三-5] 单病种 OK（I21.0 自动命中 {hit['disease_name']}，建卡→上报，重复建卡拦截 4540）")

# ============ 二十四期：专科流程 ============

# 心电（ECG 模态）：开单收费 → 模态自动归类 → 报告审核
ecg_item = ok(call('GET', '/masterdata/charge-items?keyword=' + q('心电'), token=t), '心电项')[0]
endo_item = ok(call('GET', '/masterdata/charge-items?keyword=' + q('胃镜'), token=t), '胃镜项')[0]
path_item = ok(call('GET', '/masterdata/charge-items?keyword=' + q('病理'), token=t), '病理项')[0]
pat2 = ok(call('POST', '/patients', {'name': 'E2E廿四' + stamp, 'sex': 'F'}, t), '建患者2')
reg2 = ok(call('POST', '/outpatient/registrations', {'patientId': pat2['id'], 'scheduleId': sch['id']}, t), '挂号2')
rid2 = reg2['id']
ok(call('POST', f'/outpatient/doctor/{rid2}/start', {}, t), '接诊2')
orders2 = ok(call('POST', f'/outpatient/doctor/{rid2}/orders', {'lines': [
    {'orderType': 'EXAM', 'itemId': ecg_item['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': endo_item['id'], 'qty': 1},
    {'orderType': 'EXAM', 'itemId': path_item['id'], 'qty': 1}]}, t), '开专科单')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid2, 'payMethod': 'CASH'}, t), '收费2')
ecg_o = next(o for o in orders2 if o['itemId'] == ecg_item['id'])
endo_o = next(o for o in orders2 if o['itemId'] == endo_item['id'])
path_o = next(o for o in orders2 if o['itemId'] == path_item['id'])

wl = ok(call('GET', '/ris/worklist?modality=ECG', token=t), 'ECG队列')
ecg_row = next(w for w in wl if w['group_no'] == ecg_o['groupNo'])
ok(call('PUT', f"/ris/exams/{ecg_row['id']}/report",
        {'findings': '窦性心律，心率 72 次/分', 'impression': '正常心电图'}, t), '心电报告')
ok(call('PUT', f"/ris/exams/{ecg_row['id']}/verify", token=t), '心电审核')
wl2 = ok(call('GET', '/ris/worklist?modality=ENDO', token=t), 'ENDO队列')
endo_row = next(w for w in wl2 if w['group_no'] == endo_o['groupNo'])
ok(call('PUT', f"/ris/exams/{endo_row['id']}/report",
        {'findings': '食管黏膜光滑，胃底体未见溃疡', 'impression': '慢性非萎缩性胃炎'}, t), '内镜报告')
ok(call('PUT', f"/ris/exams/{endo_row['id']}/verify", token=t), '内镜审核')
print('[廿四-1] 心电/内镜 OK（RIS 模式实例化，模态自动归类 ECG/ENDO，报告→审核→医嘱执行）')

# 病理：取材→核收→诊断（LIS 模式）
pend = ok(call('GET', '/pathology/pending', token=t), '病理待取材')
assert any(p['order_id'] == path_o['id'] for p in pend)
bc = ok(call('POST', f"/pathology/specimens?orderId={path_o['id']}&specimenDesc=" + q('胃窦活检组织'), {}, t), '取材')['barcode']
r = call('PUT', f'/pathology/specimens/{bc}/diagnose', {'diagnosis': 'x'}, t)
assert r['code'] == 4553, '未核收不能诊断'
ok(call('PUT', f'/pathology/specimens/{bc}/receive', token=t), '核收')
ok(call('PUT', f'/pathology/specimens/{bc}/diagnose',
        {'grossFinding': '灰白组织 2 块', 'microFinding': '腺体规则，间质慢性炎细胞浸润',
         'diagnosis': '（胃窦）慢性浅表性胃炎'}, t), '病理诊断')
sp = next(s for s in ok(call('GET', '/pathology/specimens', token=t), '标本') if s['barcode'] == bc)
assert sp['status'] == 'DIAGNOSED'
print(f'[廿四-2] 病理 OK（取材 {bc}→核收→诊断报告，未核收拦截 4553）')

# 急诊留观
tri = ok(call('POST', '/outpatient/triage', {'patientName': 'E2E留观' + stamp, 'level': 2,
                                             'chiefComplaint': '胸闷 2 小时'}, t), '分诊')
ok(call('POST', '/outpatient/er-observation', {'triageId': tri['id'], 'bedNo': 'L' + stamp[-2:]}, t), '入留观')
r = call('POST', '/outpatient/er-observation', {'triageId': tri['id'], 'bedNo': 'L99'}, t)
assert r['code'] == 4561, '重复入观应拦截'
obs = next(o for o in ok(call('GET', '/outpatient/er-observation', token=t), '留观列表')
           if o['triage_id'] == tri['id'])
ok(call('POST', f"/outpatient/er-observation/{obs['id']}/notes?note=" + q('神志清，胸闷缓解'), token=t), '观察记录')
ok(call('PUT', f"/outpatient/er-observation/{obs['id']}/end?outcome=ADMITTED", token=t), '离观收住院')
print('[廿四-3] 急诊留观 OK（分诊→入观→观察记录→离观，重复入观拦截 4561）')

# ICU 记录
r = call('POST', '/inpatient/icu-records', {'admissionId': adm['id'], 'gcs': 20}, t)
assert r['code'] == 4571, 'GCS 越界应拦截'
ok(call('POST', '/inpatient/icu-records', {'admissionId': adm['id'], 'temperature': 37.2, 'pulse': 92,
                                           'respiration': 18, 'sbp': 130, 'dbp': 82, 'spo2': 97, 'gcs': 14,
                                           'intakeMl': 500, 'outputMl': 300, 'ventilator': False,
                                           'note': '入 ICU 首记'}, t), 'ICU记录1')
ok(call('POST', '/inpatient/icu-records', {'admissionId': adm['id'], 'pulse': 88, 'spo2': 98, 'gcs': 15,
                                           'intakeMl': 200, 'outputMl': 150, 'ventilator': True}, t), 'ICU记录2')
bal = ok(call('GET', f"/inpatient/icu-records/balance?admissionId={adm['id']}", token=t), '出入量')
assert bal['intake'] == 700 and bal['output'] == 450 and bal['balance'] == 250, bal
recs = ok(call('GET', f"/inpatient/icu-records?admissionId={adm['id']}", token=t), 'ICU列表')
assert len(recs) >= 2
print(f"[廿四-4] ICU 记录 OK（高频体征×2，24h 平衡 +{bal['balance']}ml，GCS 越界拦截 4571）")

# ============ 二十五期：管理与院感 ============

# 护理排班 + 冲突拦截 + 质控评分
nurse = 'E2E护士' + stamp
ok(call('POST', '/nursing/shifts', {'deptId': 2, 'nurseName': nurse, 'shiftDate': today, 'shiftType': 'DAY'}, t), '排班')
r = call('POST', '/nursing/shifts', {'deptId': 2, 'nurseName': nurse, 'shiftDate': today, 'shiftType': 'DAY'}, t)
assert r['code'] == 4581, '重复排班应拦截'
ok(call('POST', '/nursing/qc-scores', {'deptId': 2, 'item': '基础护理', 'score': 92.5, 'note': 'E2E'}, t), '评分')
summ = ok(call('GET', '/nursing/qc-scores/summary', token=t), '质控汇总')
assert len(summ) >= 1
print('[廿五-1] 护理排班+质控评分 OK（冲突拦截 4581，科室横向对比）')

# 医务台账：到期预警
soon = (datetime.date.today() + datetime.timedelta(days=30)).isoformat()
ok(call('POST', '/hrp/credentials', {'staffName': 'E2E医师' + stamp, 'certType': '定期考核',
                                     'certNo': 'DK' + stamp, 'expireDate': soon}, t), '登记资质')
creds = ok(call('GET', '/hrp/credentials', token=t), '台账')
me = next(c for c in creds if c['cert_no'] == 'DK' + stamp)
assert me['validity'] == 'EXPIRING'
assert any(c['cert_no'] == 'DK' + stamp for c in ok(call('GET', '/hrp/credentials/expiring', token=t), '到期预警'))
print('[廿五-2] 医务台账 OK（30 天内到期自动标记 EXPIRING 并进预警清单）')

# 抗菌分级处方权：拦截 → 授权 → 放行
cef = ok(call('GET', '/masterdata/drugs?keyword=' + q('头孢克肟'), token=t), '限制级药')[0]
pat3 = ok(call('POST', '/patients', {'name': 'E2E廿五' + stamp, 'sex': 'M'}, t), '建患者3')
reg3 = ok(call('POST', '/outpatient/registrations', {'patientId': pat3['id'], 'scheduleId': sch['id']}, t), '挂号3')
rid3 = reg3['id']
ok(call('POST', f'/outpatient/doctor/{rid3}/start', {}, t), '接诊3')
r = call('POST', f'/outpatient/doctor/{rid3}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': cef['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1片', 'days': 3}]}, t)
assert r['code'] == 4014, f'限制级抗菌药无授权应拦截: {r}'
ok(call('PUT', '/outpatient/abx-privileges/1?level=2', token=t), '授权2级')
ok(call('POST', f'/outpatient/doctor/{rid3}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': cef['id'], 'qty': 1, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1片', 'days': 3}]}, t), '授权后开单')
ok(call('PUT', '/outpatient/abx-privileges/1?level=1', token=t), '复位处方权2')
print('[廿五-3] 抗菌分级管控 OK（限制级无权拦截 4014 → 授 2 级放行 → 复位）')

# 消毒供应追溯：包-灭菌-发放-使用链
pkg = ok(call('POST', '/cssd/packages', {'name': '腹部手术器械包'}, t), '打包')['pkgNo']
r = call('PUT', f'/cssd/packages/{pkg}/issue?deptId=2', token=t)
assert r['code'] == 4601, '未灭菌发放应拦截'
ok(call('PUT', f'/cssd/packages/{pkg}/sterilize?batch=MJ-{stamp}', token=t), '灭菌')
ok(call('PUT', f'/cssd/packages/{pkg}/issue?deptId=2', token=t), '发放')
ok(call('PUT', f'/cssd/packages/{pkg}/use', token=t), '使用')
trace = ok(call('GET', f'/cssd/packages/{pkg}/trace', token=t), '追溯')
assert [x['action'] for x in trace] == ['PACKED', 'STERILIZED', 'ISSUED', 'USED'], trace
print(f'[廿五-4] 消毒供应 OK（{pkg} 四环节追溯链完整，越级发放拦截 4601）')

# 预防保健
ok(call('POST', '/phc/records', {'patientId': pid, 'recordType': 'VACCINATION', 'content': '流感疫苗第 1 剂'}, t), '接种')
stats = ok(call('GET', '/phc/stats', token=t), '预防统计')
assert any(s['record_type'] == 'VACCINATION' for s in stats)
print('[廿五-5] 预防保健 OK')

# 评审指标集扩充：快照含 M006-M009
snap = ok(call('POST', '/datagov/metrics/snapshot', {}, t), '快照')
assert snap['snapshotted'] >= 9, snap
codes = [m['code'] for m in ok(call('GET', '/datagov/metrics', token=t), '指标')]
for c in ['M006', 'M007', 'M008', 'M009']:
    assert c in codes, f'缺指标 {c}'
print(f"[廿五-6] 评审指标集 OK（{snap['snapshotted']} 项快照，含平均住院日/抗菌处方比/签名率/审核率）")

# ============ 二十六期：数据治理完整化 ============

# 数据元 + 术语
ok(call('POST', '/datagov/elements', {'code': 'DE.E2E.' + stamp, 'name': 'E2E 测试元', 'datatype': 'S',
                                      'format': 'AN..10', 'stdRef': 'WS/T 303'}, t), '数据元')
r = call('POST', '/datagov/elements', {'code': 'DE.E2E.' + stamp, 'name': 'x', 'datatype': 'S'}, t)
assert r['code'] == 4630, '重复数据元应拦截'
assert any(e['code'].startswith('DE02') for e in ok(call('GET', '/datagov/elements', token=t), '数据元列表'))
ok(call('POST', '/datagov/terms', {'category': 'LAB', 'localName': 'E2E术语' + stamp, 'stdCode': '999-9',
                                   'stdName': 'E2E', 'stdSystem': 'LOINC'}, t), '术语')
assert len(ok(call('GET', '/datagov/terms?category=LAB', token=t), '术语列表')) >= 1
print('[廿六-1] 数据元/术语管理 OK（种子含 WS 363 数据元与 ICD10/LOINC/ATC 映射，重复拦截 4630）')

# 服务订阅 + 推送流水
ok(call('POST', '/datagov/subscriptions', {'eventType': 'LAB_PUBLISHED', 'subscriber': '区域平台',
                                           'targetUrl': 'http://demo.example/webhook'}, t), '订阅')
sub = ok(call('GET', '/datagov/subscriptions', token=t), '订阅列表')[0]
ok(call('POST', f"/datagov/subscriptions/{sub['id']}/test-push", {}, t), '测试推送')
pushes = ok(call('GET', f"/datagov/subscriptions/{sub['id']}/pushes", token=t), '推送流水')
assert len(pushes) >= 1 and pushes[0]['result'] == 'MOCK'
ok(call('PUT', f"/datagov/subscriptions/{sub['id']}/toggle", token=t), '停用')
r = call('POST', f"/datagov/subscriptions/{sub['id']}/test-push", {}, t)
assert r['code'] == 4633, '停用后推送应拦截'
ok(call('PUT', f"/datagov/subscriptions/{sub['id']}/toggle", token=t), '恢复')
print('[廿六-2] 服务订阅 OK（订阅→推送留痕→停用拦截 4633）')

# 报表引擎：白名单 + 运行
r = call('POST', '/datagov/reports', {'name': '恶意', 'sqlText': 'delete from sys_user'}, t)
assert r['code'] == 4634, '非 SELECT 应拦截'
rpts = ok(call('GET', '/datagov/reports', token=t), '报表列表')
builtin = next(x for x in rpts if x['name'] == '在院患者一览')
result = ok(call('POST', f"/datagov/reports/{builtin['id']}/run", {}, t), '运行报表')
assert len(result['columns']) >= 3 and len(result['rows']) >= 1, result
print(f"[廿六-3] 报表引擎 OK（危险 SQL 拦截 4634，内置报表运行 {len(result['rows'])} 行）")

# ODR 运营汇总
odr = ok(call('GET', '/datagov/odr/summary', token=t), 'ODR')
assert odr['outpToday'] >= 1 and odr['inHospital'] >= 1
assert len(odr['metricSnapshots']) >= 9
print(f"[廿六-4] ODR OK（今日门诊 {odr['outpToday']}，在院 {odr['inHospital']}，指标快照 {len(odr['metricSnapshots'])} 项）")

# 收尾：出院释放床位（避免反复运行占满病区）
discharge_cleanup(t, adm['id'])

print('\n=== 二十二至二十六期 E2E 全部通过 ===')
