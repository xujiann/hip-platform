# -*- coding: utf-8 -*-
"""三十五至三十七期 E2E：HR 人事/资产处置/价格交款、三管监测/现患率/窗口科研、封版工具链"""
import datetime
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from e2elib import BASE, call, discharge_cleanup, find_free_bed, new_patient, login, ok, q  # noqa: E402



t = login()
today = datetime.date.today().isoformat()
month = today[:7]
stamp = datetime.datetime.now().strftime('%H%M%S')

# ============ 三十五期：人财物 ============

# 员工档案 + 工资导入 + 个人查询
ok(call('POST', '/hr/employees', {'empNo': 'E' + stamp, 'name': 'HR员工' + stamp, 'sex': 'F',
                                  'deptId': 1, 'title': '主管护师', 'phone': '13700000001',
                                  'hireDate': today}, t), '建档')
r = call('POST', '/hr/employees', {'empNo': 'E' + stamp, 'name': 'x'}, t)
assert r['code'] == 4720, '重复工号应拦截'
imp = ok(call('POST', '/hr/salaries/import', {'month': month, 'rows': [
    {'empNo': 'E' + stamp, 'basePay': 8000, 'bonus': 2000, 'deduction': 500},
    {'empNo': 'NOEXIST', 'basePay': 1, 'bonus': 0, 'deduction': 0}]}, t), '工资导入')
assert imp['imported'] == 1 and imp['missing'] == 1
sal = ok(call('GET', f"/hr/salaries?empNo=E{stamp}", token=t), '个人工资')
assert len(sal) == 1 and float(sal[0]['total']) == 9500.0
# 幂等覆盖
ok(call('POST', '/hr/salaries/import', {'month': month, 'rows': [
    {'empNo': 'E' + stamp, 'basePay': 8000, 'bonus': 2500, 'deduction': 500}]}, t), '工资覆盖')
sal2 = ok(call('GET', f"/hr/salaries?empNo=E{stamp}&month={month}", token=t), '个人工资2')
assert float(sal2[0]['total']) == 10000.0
emp = next(e for e in ok(call('GET', '/hr/employees?keyword=E' + stamp, token=t), '员工检索'))
ok(call('POST', '/hr/trainings', {'employeeId': emp['id'], 'category': '院感培训',
                                  'certName': '院感上岗证', 'certNo': 'YG' + stamp}, t), '培训')
ok(call('POST', '/hr/recruits', {'candidateName': '应聘' + stamp, 'position': '护士',
                                 'interviewDate': today, 'note': ''}, t), '招聘')
rec = ok(call('GET', '/hr/recruits', token=t), '招聘列表')[0]
ok(call('PUT', f"/hr/recruits/{rec['id']}/result?result=PASS", token=t), '录用')
assert len(ok(call('GET', '/hr/directory', token=t), '通讯录')) >= 1
print(f"[卅五-1] HR 人事 OK（建档/重复工号 4720/工资导入幂等覆盖 9500→10000/培训/招聘/通讯录）")

# 资产处置：调拨→移交→报废审核（资产置 SCRAPPED 后禁调拨）
asset = ok(call('POST', '/hrp/assets', {'name': 'E2E监护仪' + stamp, 'category': '医疗设备',
                                        'price': 80000, 'purchaseDate': today, 'usefulYears': 6}, t), '建资产')
ok(call('POST', '/asset-plus/transfers', {'assetId': asset['id'], 'toDeptId': 2, 'note': '调往住院'}, t), '调拨')
ok(call('POST', '/asset-plus/handovers', {'assetId': asset['id'], 'fromPerson': '张三', 'toPerson': '李四',
                                          'note': ''}, t), '移交')
ok(call('POST', f"/asset-plus/scraps?assetId={asset['id']}&reason=" + q('损坏无法修复'), {}, t), '报废申请')
r = call('POST', f"/asset-plus/scraps?assetId={asset['id']}&reason=x", {}, t)
assert r['code'] == 4732, '重复申请应拦截'
scrap = ok(call('GET', '/asset-plus/scraps', token=t), '报废列表')[0]
ok(call('PUT', f"/asset-plus/scraps/{scrap['id']}/review?approve=true&note=" + q('同意'), token=t), '报废审核')
r = call('POST', '/asset-plus/transfers', {'assetId': asset['id'], 'toDeptId': 1}, t)
assert r['code'] == 4731, '已报废不可调拨'
ok(call('POST', '/asset-plus/buildings', {'buildingNo': 'F' + stamp[-3:], 'name': 'E2E综合楼',
                                          'usageType': '门诊', 'areaSqm': 8000, 'address': '院区东侧'}, t), '房屋')
print('[卅五-2] 资产处置 OK（调拨留痕→移交→报废申请 4732→审核置 SCRAPPED→禁调拨 4731；房屋台账）')

# 价格规则 + 异常交款
item = ok(call('GET', '/masterdata/charge-items?keyword=' + q('肌肉注射'), token=t), '项目')[0]
old_price = float(item['price'])
ok(call('PUT', f"/price/charge-items/{item['id']}", {'newPrice': old_price + 1, 'reason': 'E2E 调价演练'}, t), '调价')
logs = ok(call('GET', '/price/change-logs', token=t), '调价日志')
assert float(logs[0]['new_price']) == old_price + 1
ok(call('PUT', f"/price/charge-items/{item['id']}", {'newPrice': old_price, 'reason': 'E2E 调回'}, t), '调回')
recon = ok(call('GET', f'/finance/reconciliation?date={today}', token=t), '交款核对')
assert 'byCashier' in recon and 'anomalies' in recon
print(f"[卅五-3] 价格规则留痕（{old_price}→{old_price + 1}→调回）+ 交款核对 OK")

# ============ 三十六期：院感专项与窗口科研 ============

# 三管监测：置管→日评估→判定感染→千日率；另一根拔管
pat = new_patient(t, '三管E2E' + stamp, 'M')
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'diagIcd': 'J96.0', 'diagName': '呼吸衰竭',
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
ok(call('POST', '/infection-plus/catheters', {'admissionId': adm['id'], 'lineType': 'VENT'}, t), '呼吸机置管')
r = call('POST', '/infection-plus/catheters', {'admissionId': adm['id'], 'lineType': 'VENT'}, t)
assert r['code'] == 4752, '同类型重复置管应拦截'
ok(call('POST', '/infection-plus/catheters', {'admissionId': adm['id'], 'lineType': 'URINARY'}, t), '导尿管置管')
lines = ok(call('GET', '/infection-plus/catheters', token=t), '置管列表')
vent = next(l for l in lines if l['admission_id'] == adm['id'] and l['line_type'] == 'VENT')
uri = next(l for l in lines if l['admission_id'] == adm['id'] and l['line_type'] == 'URINARY')
ok(call('POST', f"/infection-plus/catheters/{vent['id']}/assess?keepLine=true&note=" + q('无感染征象'), {}, t), '日评估')
ok(call('PUT', f"/infection-plus/catheters/{uri['id']}/remove", token=t), '拔尿管')
ok(call('PUT', f"/infection-plus/catheters/{vent['id']}/infect?pathogen=" + q('肺炎克雷伯菌'), token=t), '判定VAP')
rate = ok(call('GET', '/infection-plus/rate', token=t), '千日率')
vent_rate = next(x for x in rate if x['line_type'] == 'VENT')
assert vent_rate['infections'] >= 1 and float(vent_rate['per_1000_days']) > 0
prev = ok(call('GET', '/infection-plus/prevalence', token=t), '现患率')
mine = next(x for x in prev['rows'] if x['admission_no'] == adm['admissionNo'])
assert mine['infected'] and 'VAP' in str(mine['sites'])
print(f"[卅六-1] 三管监测 OK（置管 4752→日评估→VAP 判定联动院感病例，千日率 {vent_rate['per_1000_days']}‰，现患率 {prev['prevalenceRate']}%）")

# 窗口 + 科研 + 我发起的 OA
ok(call('POST', '/windows', {'winNo': 'W' + stamp[-3:], 'name': 'E2E快检窗', 'winType': 'SAMPLE'}, t), '建窗口')
ok(call('PUT', f"/windows/W{stamp[-3:]}/toggle", token=t), '关窗')
wins = ok(call('GET', '/windows', token=t), '窗口')
assert next(w for w in wins if w['win_no'] == 'W' + stamp[-3:])['status'] == 'CLOSED'
ok(call('POST', '/research', {'itemType': 'IP', 'title': 'E2E专利转化' + stamp, 'leader': '王研',
                              'content': '', 'amount': 100000}, t), '科研登记')
sr = ok(call('GET', '/research', token=t), '科研台账')[0]
ok(call('PUT', f"/research/{sr['id']}/review?approve=true&note=" + q('同意'), token=t), '科研审核')
r = call('DELETE', f"/research/{sr['id']}", token=t)
assert r['code'] == 4763, '已审核不可删除'
assert ok(call('GET', '/oa/my-requests', token=t), '我发起的') is not None
print('[卅六-2] 窗口维护/科研审核（已审禁删 4763）/我发起的 OA OK')

# ============ 三十七期：封版工具链 ============

env = dict(os.environ, PYTHONIOENCODING='utf-8')
r1 = subprocess.run([sys.executable, 'tools/bootstrap-demo.py'], capture_output=True, text=True,
                    encoding='utf-8', errors='replace', env=env)
assert '完成' in r1.stdout, r1.stdout + r1.stderr
r2 = subprocess.run([sys.executable, 'tools/export-deviation.py'], capture_output=True, text=True,
                    encoding='utf-8', errors='replace', env=env)
assert '已导出' in r2.stdout, r2.stdout + r2.stderr
assert os.path.exists('docs/验收/技术偏离表.csv')
print(f"[卅七-1] 封版工具链 OK（演示引导幂等 + 技术偏离表导出：{r2.stdout.strip().splitlines()[0]}）")

# 收尾：出院释放床位
discharge_cleanup(t, adm['id'])

print('\n=== 三十五至三十七期 E2E 全部通过 ===')
