# -*- coding: utf-8 -*-
"""二十七期 E2E：医保补缺——目录对照 / 费用分割 / 审核提醒 / 对账留痕 / 住院医保通道"""
import datetime
import json
import sys
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


def close(a, b, step):
    assert abs(float(a) - float(b)) < 0.01, f'{step}: {a} != {b}'


q = urllib.parse.quote
t = ok(call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'}), '登录')['token']
today = datetime.date.today().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# 基线：雾化吸入按丙类（保证可重复运行），统筹比例确认
ok(call('POST', '/insurance/catalog', {'itemType': 'ITEM', 'itemCode': 'C0203', 'itemName': '雾化吸入',
                                       'ybCode': '120500003', 'chargeClass': 'C', 'selfRatio': 0}, t), '基线对照')
cat = ok(call('GET', '/insurance/catalog', token=t), '目录')
assert len(cat['mapped']) >= 13
print(f"[医保-1] 目录对照 OK（已对照 {len(cat['mapped'])} 项，未对照药品 {len(cat['unmappedDrugs'])} / 诊疗 {len(cat['unmappedItems'])}）")

# 门诊医保结算：职工医保 85%，二甲双胍(A)×6 + 肝功能(B 自付10%) + 雾化吸入(C) + 挂号费(未对照→C)
pat = ok(call('POST', '/patients', {'name': '医保E2E' + stamp, 'sex': 'M', 'insuranceType': 'YB_STAFF'}, t), '建患者')
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': pat['id'], 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
met = ok(call('GET', '/masterdata/drugs?keyword=' + q('二甲双胍'), token=t), '药')[0]
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('肝功能'), token=t), '检验')[0]
neb = ok(call('GET', '/masterdata/charge-items?keyword=' + q('雾化'), token=t), '雾化')[0]
ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [
    {'orderType': 'DRUG', 'itemId': met['id'], 'qty': 6, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1片', 'days': 30},
    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
    {'orderType': 'TREAT', 'itemId': neb['id'], 'qty': 1}]}, t), '开单')
charge = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'YB'}, t), '医保结算')
cno = charge['chargeNo']

# 分割断言：A=59.4 B=60 C=23(挂号5+雾化18)，统筹=59.4*0.85+60*0.9*0.85=96.39
splits = ok(call('GET', f'/insurance/splits?date={today}', token=t), '分割')
sp = next(s for s in splits if s['charge_no'] == cno)
close(sp['total'], 142.40, '总额')
close(sp['class_a'], 59.40, '甲类')
close(sp['class_b'], 60.00, '乙类')
close(sp['class_c'], 23.00, '丙类')
close(sp['fund_pay'], 96.39, '统筹')
close(sp['self_pay'], 46.01, '个人')
assert sp['insurance_type'] == 'YB_STAFF'
detail = json.loads(sp['detail'])
assert len(detail) == 4
print(f"[医保-2] 费用分割 OK（{cno}：总额 142.40 = 甲 59.40 + 乙 60.00 + 丙 23.00，统筹 96.39 / 个人 46.01）")

# 审核提醒：R001 数量超限
audits = ok(call('GET', '/insurance/audits', token=t), '审核')
r001 = [a for a in audits if a['charge_no'] == cno and a['rule_code'] == 'R001']
assert r001, f'应有 R001 超量提醒: {audits[:3]}'
print(f"[医保-3] 智能审核雏形 OK（R001：{r001[0]['message'][:30]}…）")

# 对账：试对账一致 → 留痕
recon = ok(call('GET', f'/insurance/reconcile?date={today}', token=t), '试对账')
mine = next(r for r in recon['rows'] if r['charge_no'] == cno)
assert mine['consistent'] and mine['has_settle_msg'], mine
batch = ok(call('POST', f'/insurance/reconcile?date={today}', {}, t), '对账留痕')
assert batch['total'] >= 1
batches = ok(call('GET', '/insurance/reconcile/batches', token=t), '批次')
assert len(batches) >= 1
print(f"[医保-4] 对账 OK（{batch['matched']}/{batch['total']} 一致，差异 {batch['diff']}，批次已留痕）")

# 退费冲正后对账仍一致（结算+退费报文齐备）
ok(call('POST', f"/outpatient/charges/{charge['id']}/refund", {}, t), '退费')
recon2 = ok(call('GET', f'/insurance/reconcile?date={today}', token=t), '试对账2')
mine2 = next(r for r in recon2['rows'] if r['charge_no'] == cno)
assert mine2['local_status'] == 'REFUNDED' and mine2['has_refund_msg'] and mine2['consistent'], mine2
print('[医保-5] 退费冲正 OK（退费报文齐备，对账仍一致）')

# 目录调整生效：雾化吸入改乙类(自付10%) → 新结算按乙类分割
ok(call('POST', '/insurance/catalog', {'itemType': 'ITEM', 'itemCode': 'C0203', 'itemName': '雾化吸入',
                                       'ybCode': '120500003', 'chargeClass': 'B', 'selfRatio': 0.10}, t), '改对照')
pat2 = ok(call('POST', '/patients', {'name': '医保E2E乙' + stamp, 'sex': 'F', 'insuranceType': 'YB_RESIDENT'}, t), '建患者2')
reg2 = ok(call('POST', '/outpatient/registrations', {'patientId': pat2['id'], 'scheduleId': sch['id']}, t), '挂号2')
ok(call('POST', f"/outpatient/doctor/{reg2['id']}/start", {}, t), '接诊2')
ok(call('POST', f"/outpatient/doctor/{reg2['id']}/orders", {'lines': [
    {'orderType': 'TREAT', 'itemId': neb['id'], 'qty': 1}]}, t), '开单2')
c2 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg2['id'], 'payMethod': 'YB'}, t), '结算2')
sp2 = next(s for s in ok(call('GET', f'/insurance/splits?date={today}', token=t), '分割2')
           if s['charge_no'] == c2['chargeNo'])
# 居民 70%：雾化 18 乙类 → 18*0.9*0.7=11.34；挂号 5 丙类
close(sp2['class_b'], 18.00, '乙类2')
close(sp2['fund_pay'], 11.34, '统筹2')
assert sp2['insurance_type'] == 'YB_RESIDENT'
ok(call('POST', '/insurance/catalog', {'itemType': 'ITEM', 'itemCode': 'C0203', 'itemName': '雾化吸入',
                                       'ybCode': '120500003', 'chargeClass': 'C', 'selfRatio': 0}, t), '还原对照')
print('[医保-6] 目录调整即时生效 OK（雾化吸入 C→B 后按乙类分割，居民 70% 统筹 11.34）')

# 住院医保：入院→出院结算走 YB 通道→对账含住院单
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']
free = None
for w in wards:
    beds = ok(call('GET', f"/inpatient/beds?wardId={w['id']}", token=t), '床')
    free = next((b for b in beds if b['status'] == 'FREE'), None)
    if free:
        break
assert free, '无空床'
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
settle = ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge?payMethod=YB", {}, t), '医保出院')
recon3 = ok(call('GET', f'/insurance/reconcile?date={today}', token=t), '试对账3')
inp = next(r for r in recon3['rows'] if r['charge_no'] == settle['settleNo'])
assert inp['biz_type'] == 'INP' and inp['consistent'], inp
print(f"[医保-7] 住院医保通道 OK（{settle['settleNo']} 出院结算上传留痕，对账一致）")

# 汇总
summ = ok(call('GET', '/insurance/summary', token=t), '汇总')
assert summ['mappedCount'] >= 13 and summ['outpToday']['cnt'] >= 1
assert summ['lastRecon'], '应有对账批次'
print(f"[医保-8] 医保汇总 OK（今日 {summ['outpToday']['cnt']} 笔，统筹 ￥{summ['splitToday']['fund_pay']}，提醒 {summ['auditWarns']} 条）")

print('\n=== 二十七期医保 E2E 全部通过 ===')
