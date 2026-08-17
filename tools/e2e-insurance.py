# -*- coding: utf-8 -*-
"""二十七期 E2E：医保补缺——目录对照 / 费用分割 / 审核提醒 / 对账留痕 / 住院医保通道"""
import datetime
import json
import sys
import urllib.parse
import urllib.request
from e2elib import BASE, call, find_free_bed, login, ok, q, today_bj  # noqa: E402



def close(a, b, step):
    assert abs(float(a) - float(b)) < 0.01, f'{step}: {a} != {b}'


t = login()
today = today_bj().isoformat()
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

# 住院医保：入院→出院结算走 YB 通道→对账含住院单 + 行级分割(INP) + 结算号回填
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': pat['id'], 'deptId': 2, 'bedId': free['id'],
                                                'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
settle = ok(call('POST', f"/inpatient/admissions/{adm['id']}/discharge?payMethod=YB", {}, t), '医保出院')
assert settle.get('ybSettleNo', '').startswith('YB'), f"住院医保结算号应回填: {settle.get('ybSettleNo')}"
recon3 = ok(call('GET', f'/insurance/reconcile?date={today}', token=t), '试对账3')
inp = next(r for r in recon3['rows'] if r['charge_no'] == settle['settleNo'])
assert inp['biz_type'] == 'INP' and inp['consistent'], inp
inp_sp = next((s for s in ok(call('GET', f'/insurance/splits?date={today}', token=t), '住院分割')
               if s['charge_no'] == settle['settleNo']), None)
assert inp_sp is not None and inp_sp['biz_type'] == 'INP', f'出院 YB 结算应有 INP 行级分割: {inp_sp}'
print(f"[医保-7] 住院医保通道 OK（{settle['settleNo']} 上传留痕+结算号 {settle['ybSettleNo']}，INP 行级分割，对账一致）")

# 汇总
summ = ok(call('GET', '/insurance/summary', token=t), '汇总')
assert summ['mappedCount'] >= 13 and summ['outpToday']['cnt'] >= 1
assert summ['lastRecon'], '应有对账批次'
print(f"[医保-8] 医保汇总 OK（今日 {summ['outpToday']['cnt']} 笔，统筹 ￥{summ['splitToday']['fund_pay']}，提醒 {summ['auditWarns']} 条）")

# 批次三：CSV 批量导入（1 合法行 + 1 非法行）与对照率统计
csv = ("item_type,item_code,item_name,yb_code,charge_class,self_ratio,effective_date\n"
       "ITEM,C0203,雾化吸入,120500003,C,0,2026-01-01\n"
       "ITEM,BADROW,坏行示例,X,Z,9\n")
imp = ok(call('POST', '/insurance/catalog/import', token=t, text=csv), 'CSV导入')
assert imp['imported'] == 1 and imp['errorCount'] == 1, imp
cat2 = ok(call('GET', '/insurance/catalog', token=t), '目录2')
st = cat2['stats']
assert st['total_drugs'] >= st['mapped_drugs'] >= 1 and st['total_items'] >= st['mapped_items'] >= 1
print(f"[医保-9] CSV 导入 OK（1 行入库、1 行拦截），对照率 药品 {st['mapped_drugs']}/{st['total_drugs']} 诊疗 {st['mapped_items']}/{st['total_items']}")

# 批次二：年度起付线（居民 5 元）→ 统筹按 (可报销-起付)×比例；退费恢复年度额度
ok(call('PUT', '/config/yb_deductible_resident?value=5', token=t), '设起付线')
try:
    pat3 = ok(call('POST', '/patients', {'name': '医保E2E线' + stamp, 'sex': 'F', 'insuranceType': 'YB_RESIDENT'}, t), '建患者3')
    reg3 = ok(call('POST', '/outpatient/registrations', {'patientId': pat3['id'], 'scheduleId': sch['id']}, t), '挂号3')
    ok(call('POST', f"/outpatient/doctor/{reg3['id']}/start", {}, t), '接诊3')
    ok(call('POST', f"/outpatient/doctor/{reg3['id']}/orders", {'lines': [
        {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单3')
    c3 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg3['id'], 'payMethod': 'YB'}, t), '结算3')
    assert c3.get('ybSettleNo', '').startswith('YB'), f"门诊医保结算号应回填: {c3.get('ybSettleNo')}"
    sp3 = next(s for s in ok(call('GET', f'/insurance/splits?date={today}', token=t), '分割3')
               if s['charge_no'] == c3['chargeNo'])
    # 肝功能 60 乙类自付10% → 可报销 54；挂号 5 丙类。起付 5 → (54-5)*0.7 = 34.30
    close(sp3['deductible_pay'], 5.00, '起付线扣除')
    close(sp3['fund_pay'], 34.30, '统筹3')
    # 退费恢复额度：再结算同款单，起付线应重新扣 5
    ok(call('POST', f"/outpatient/charges/{c3['id']}/refund", {}, t), '退费3')
    reg4 = ok(call('POST', '/outpatient/registrations', {'patientId': pat3['id'], 'scheduleId': sch['id']}, t), '挂号4')
    ok(call('POST', f"/outpatient/doctor/{reg4['id']}/start", {}, t), '接诊4')
    ok(call('POST', f"/outpatient/doctor/{reg4['id']}/orders", {'lines': [
        {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单4')
    c4 = ok(call('POST', '/outpatient/charges/settle', {'registrationId': reg4['id'], 'payMethod': 'YB'}, t), '结算4')
    sp4 = next(s for s in ok(call('GET', f'/insurance/splits?date={today}', token=t), '分割4')
               if s['charge_no'] == c4['chargeNo'])
    close(sp4['deductible_pay'], 5.00, '退费后起付线恢复')
    ok(call('POST', f"/outpatient/charges/{c4['id']}/refund", {}, t), '收尾退费4')
finally:
    ok(call('PUT', '/config/yb_deductible_resident?value=0', token=t), '还原起付线')
print('[医保-10] 年度起付线 OK（(54-5)×0.70=34.30，退费恢复额度后再扣如初），配置已还原')

print('\n=== 二十七期医保 E2E 全部通过（含批次一二三扩展）===')
