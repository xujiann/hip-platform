# -*- coding: utf-8 -*-
"""1.0.1 快赢包 E2E：每日清单/明细类型检索/审方上限/编号前缀/输血处置字典/死亡登记卡/体检总检配置"""
import datetime
import urllib.error
from e2elib import BASE, call, discharge_cleanup, find_free_bed, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()
stamp = datetime.datetime.now().strftime('%H%M%S')

# 1 编号前缀配置化（1749）：改前缀→新结算单生效→还原
ok(call('PUT', '/config/billno_prefix_charge?value=JS', token=t), '改前缀')
try:
    pat = new_patient(t, '快赢E2E' + stamp, 'M')
    sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 5, 'capacity': 10}, t), '排班')
    reg = ok(call('POST', '/outpatient/registrations', {'patientId': pat['id'], 'scheduleId': sch['id']}, t), '挂号')
    c1 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg['id'], 'payMethod': 'CASH'}, t), '结算')
    assert c1['chargeNo'].startswith('JS'), c1['chargeNo']
finally:
    ok(call('PUT', '/config/billno_prefix_charge?value=SJ', token=t), '还原前缀')
print(f"[101-1] 单据前缀配置化 OK（{c1['chargeNo']} 以 JS 开头，已还原 SJ）")

# 2 审方待审上限（505）：设 1 → 列表长度 ≤1 → 还原
ok(call('PUT', '/config/review_pending_limit?value=1', token=t), '设上限')
try:
    pending = ok(call('GET', '/outpatient/review/pending', token=t), '待审')
    assert len(pending) <= 1, len(pending)
finally:
    ok(call('PUT', '/config/review_pending_limit?value=0', token=t), '还原上限')
print(f"[101-2] 审方待审上限 OK（limit=1 时列表 {len(pending)} 条）")

# 3 结账明细类型检索（1227）：明细行模式返回 item_name/order_type
hits = ok(call('GET', f'/finance/charge-search?from={today}&to={today}&orderType=REG', token=t), '类型检索')
assert hits and all(h['order_type'] == 'REG' for h in hits), hits[:2]
assert 'item_name' in hits[0]
print(f"[101-3] 结账明细类型检索 OK（REG 明细 {len(hits)} 行）")

# 4 输血处置方案字典（1814）
plans = ok(call('GET', '/inpatient/blood/reaction-plans', token=t), '处置字典')
assert len(plans) >= 4 and any('停止输血' in p['name'] for p in plans)
print(f"[101-4] 输血不良反应处置字典 OK（{len(plans)} 项）")

# 5 住院每日清单（2067）：入院→开嘱执行→按日检索→收尾出院
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
drug = ok(call('GET', '/masterdata/drugs?keyword=' + q('二甲双胍'), token=t), '药')[0]
orders = ok(call('POST', f"/inpatient/admissions/{adm['id']}/orders", {'lines': [
    {'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 2, 'usageRoute': '口服', 'frequency': 'bid',
     'dosePerTime': '1片'}]}, t), '开嘱')
for o in orders:
    ok(call('PUT', f"/inpatient/orders/{o['id']}/execute", {}, t), '执行')
daily = ok(call('GET', f"/inpatient/admissions/{adm['id']}/daily-fees?date={today}", token=t), '每日清单')
assert daily['rows'] and float(daily['total']) > 0, daily
empty = ok(call('GET', f"/inpatient/admissions/{adm['id']}/daily-fees?date=2000-01-01", token=t), '空日清单')
assert not empty['rows'] and float(empty['total']) == 0
discharge_cleanup(t, adm['id'])
print(f"[101-5] 住院每日清单 OK（今日 {len(daily['rows'])} 行合计 ¥{daily['total']}，空日为 0）")

# 6 死亡登记卡（1028）：登记→列表→打印数据集；缺必填拦截
r = call('POST', '/mrstats/death-cards', {'patientId': pat['id'], 'diedAt': ''}, t)
assert r['code'] == 9950, r
ok(call('POST', '/mrstats/death-cards', {'patientId': pat['id'], 'admissionId': adm['id'],
                                         'diedAt': today + 'T10:00:00+08:00', 'directCause': '呼吸循环衰竭',
                                         'directCauseIcd': 'J96.9', 'chainB': '重症肺炎',
                                         'place': '病房', 'registrar': 'admin'}, t), '登记')
cards = ok(call('GET', '/mrstats/death-cards', token=t), '列表')
mine = next(c for c in cards if c['patient_id'] == pat['id'])
prt = ok(call('GET', f"/print/death-card/{mine['id']}", token=t), '打印数据集')
assert prt['direct_cause'] == '呼吸循环衰竭' and prt['patient_name']
print(f"[101-6] 死亡登记卡 OK（登记/必填拦截 9950/打印数据集）")

# 7 体检总检显示配置（1938）：建套餐带 hiddenItems → 列表返回
ok(call('POST', '/exam/packages', {'name': '快赢套餐' + stamp, 'price': 99,
                                   'items': '血常规,肝功能,心电图', 'hiddenItems': '心电图'}, t), '建套餐')
pkgs = ok(call('GET', '/exam/packages', token=t), '套餐')
mine_pkg = next(p for p in pkgs if p['name'] == '快赢套餐' + stamp)
assert mine_pkg['hidden_items'] == '心电图', mine_pkg
print('[101-7] 体检总检显示配置 OK（hiddenItems=心电图 入库可查）')

print('\n=== 1.0.1 快赢包 E2E 全部通过 ===')
