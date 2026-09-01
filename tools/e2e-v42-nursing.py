# -*- coding: utf-8 -*-
"""v42 护理文书版 E2E：体温单三测单出纸 + 护理记录单/巡视/签名 + 护理级别留痕 +
病案终末质控甲乙丙 + 费别/费用类别金额汇总 + PREOP 幽灵类型修复。
自成一体（自建患者/账号），末尾收尾。

本套的存在意义：v42 五项里有四项是**投标偏离表已答「平台已实现」而代码里不存在**的诚信补齐
（体温单 2025★/2026★/2073、护理记录 1268/1708/2427、终末质控 2647/2734、费别汇总 1034★/675/3684）。
E2E 在此把「能真的走通」钉死，避免下一轮又只剩一句应答。
"""
import sys
from e2elib import call, find_free_bed, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# ---- 1) 体温单（三测单）出纸：新列存取 + 周窗口 + 越界 4821 ----
pid = new_patient(t, '体温单E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid, 'deptId': 2, 'bedId': free['id'], 'deposit': 500, 'payMethod': 'CASH'}, t), '入院')
admid = adm['id']
# 录一条含 v42 新列的体征（三测 + 出入量 + 大便 + 体重身高 + 测量部位 + 降温后体温）
ok(call('POST', f'/inpatient/admissions/{admid}/vitals',
        {'temperature': 39.2, 'pulse': 108, 'respiration': 24, 'sbp': 118, 'dbp': 76, 'spo2': 96,
         'intakeMl': 1500, 'outputMl': 1200, 'stoolCount': 2, 'weightKg': 62.5, 'heightCm': 170,
         'measureSite': 'AXILLARY', 'tempAfterCooling': 38.1}, t), '录三测')
# 再录一条「未测」行——三测单必须能画未测而不是断线
ok(call('POST', f'/inpatient/admissions/{admid}/vitals',
        {'notMeasuredReason': '外出检查'}, t), '录未测')
sheet = ok(call('GET', f'/inpatient/admissions/{admid}/print/temp-sheet?week=1', token=t), '体温单第1周')
for k in ['header', 'week', 'weekStart', 'weekEnd', 'totalWeeks', 'days']:
    assert k in sheet, f'体温单缺键 {k}: {list(sheet)}'
hdr = sheet['header']
for k in ['patient_name', 'admission_no', 'admit_at', 'care_level', 'bed_no', 'dept_name']:
    assert k in hdr, f'页眉缺 {k}: {list(hdr)}'
assert sheet['week'] == 1 and int(sheet['totalWeeks']) >= 1, sheet
# 周次越界必须被拒（不能静默返空页，否则打印出白纸）
assert call('GET', f'/inpatient/admissions/{admid}/print/temp-sheet?week=999', token=t)['code'] == 4821, '越界应 4821'
assert call('GET', '/inpatient/admissions/99999999/print/temp-sheet?week=1', token=t)['code'] == 4820, '不存在应 4820'
# 新列确实回读得到（不是只写不读）
flat = [v for d in sheet['days'] for v in (d.get('points') or d.get('vitals') or [])]
blob = str(sheet)
assert '39.2' in blob or any(str(v.get('temperature')) == '39.2' for v in flat), '新录体温应出现在体温单'
assert '外出检查' in blob, '未测原因应出现在体温单（画「未测」而不断线）'
print(f"[1] 体温单三测单 OK（页眉齐 / 第 1/{sheet['totalWeeks']} 周 / 未测行可画 / 越界 4821 / 不存在 4820）")

# ---- 2) 护理记录单 + 日常巡视 + 签名 + 打印 ----
nurse = provision_user(t, 'nurse_v42_e2e', 'NURSE', '护理文书E2E')
r1 = ok(call('POST', '/nursing/records',
             {'admissionId': admid, 'recordKind': 'OBSERVE', 'observation': '患者主诉头痛，T39.2',
              'measure': '物理降温、遵医嘱补液', 'effect': '30 分钟后 T38.1'}, nurse), '护理观察记录')
r2 = ok(call('POST', '/nursing/records',
             {'admissionId': admid, 'recordKind': 'ROUNDS', 'observation': '晨间护理已执行，患者进食半流质'},
             nurse), '日常巡视')   # 兑现偏离表 2074
assert call('POST', '/nursing/records',
            {'admissionId': admid, 'recordKind': 'BOGUS', 'observation': 'x'}, nurse)['code'] == 4801, '类型非法 4801'
assert call('POST', '/nursing/records',
            {'admissionId': admid, 'recordKind': 'OBSERVE'}, nurse)['code'] == 4802, '观察与措施同空 4802'
rid = r1['id'] if isinstance(r1, dict) and 'id' in r1 else r1.get('recordId')
ok(call('POST', f'/nursing/records/{rid}/sign', token=nurse), '护士签名')
# 已签名不可改
assert call('PUT', f'/nursing/records/{rid}',
            {'admissionId': admid, 'recordKind': 'OBSERVE', 'observation': '篡改'}, nurse)['code'] == 4803, '已签不可改 4803'
lst = ok(call('GET', f'/nursing/records?admissionId={admid}', token=t), '护理记录列表')
assert len(lst) >= 2, lst
pr = ok(call('GET', f'/inpatient/admissions/{admid}/print/nursing-record', token=t), '护理记录单打印数据集')
assert 'rows' in pr and len(pr['rows']) >= 2, pr
assert any(x.get('kind_name') for x in pr['rows']), '打印行应带中文类型名'
print(f"[2] 护理记录单 OK（观察+巡视 {len(lst)} 条 / 签名 / 4801 类型 / 4802 空 / 4803 已签不可改 / 打印数据集）")

# ---- 3) 护理级别留痕（旧端点契约不变 + 新端点带原因）----
ok(call('PUT', f"/inpatient/admissions/{admid}/care-level?level={q('一级')}", token=t), '旧端点改护理级别(契约不变)')
ok(call('PUT', f'/inpatient/admissions/{admid}/care-level/change',
        {'level': '特级', 'reason': '病情加重转特级护理'}, t), '新端点带原因')
assert call('PUT', f'/inpatient/admissions/{admid}/care-level/change',
            {'level': '特级'}, t)['code'] == 4807, '变更原因必填 4807'
assert call('PUT', f'/inpatient/admissions/{admid}/care-level/change',
            {'level': '超级', 'reason': 'x'}, t)['code'] == 4806, '级别非法 4806'
print('[3] 护理级别留痕 OK（旧端点逐字不变 / 新端点 4806 级别 + 4807 原因必填）')

# ---- 4) PREOP 幽灵类型修复（gate 要它、此前全仓无录入入口）----
integ = ok(call('GET', f'/inpatient/admissions/{admid}/emr-integrity', token=t), '完整性预检')
ok(call('POST', f'/inpatient/admissions/{admid}/records',
        {'recordType': 'PREOP', 'title': '术前小结', 'content': '术前讨论：拟行阑尾切除术。'}, t), '写 PREOP')
recs = ok(call('GET', f'/inpatient/admissions/{admid}/records', token=t), '病历列表')
assert any(r.get('recordType') == 'PREOP' for r in recs), 'PREOP 须逐字落库不被兜底成 PROGRESS'
print('[4] PREOP 幽灵类型 OK（此前全仓无录入入口，手术病例必常亮无法自救的缺项）')

# ---- 5) 病案终末质控甲乙丙 ----
items = ok(call('GET', '/quality/mr-qc/items', token=t), '扣分项字典')
assert len(items) >= 10, f'扣分项种子应 >=10: {len(items)}'
# 未出院不可终末评分
assert call('POST', f'/quality/mr-qc/sheets/{admid}/prefill', {}, t)['code'] == 4844, '未出院应 4844'
ok(call('POST', f'/inpatient/admissions/{admid}/discharge?payMethod=CASH', {}, t), '出院')
pre = ok(call('POST', f'/quality/mr-qc/sheets/{admid}/prefill', {}, t), '自动预填')
assert 'finalScore' in pre or 'final_score' in pre or 'grade' in pre, pre
sheet2 = ok(call('GET', f'/quality/mr-qc/sheets/{admid}', token=t), '评分单')
ok(call('POST', f'/quality/mr-qc/sheets/{admid}/submit', {'note': 'E2E 评分'}, t), '提交评分')
assert call('POST', f'/quality/mr-qc/sheets/{admid}/submit', {'note': 'x'}, t)['code'] == 4841, '重复提交 4841'
summ = ok(call('GET', f'/quality/mr-qc/summary/{admid}', token=t), '首页质控摘要')
assert summ.get('grade') in ('甲', '乙', '丙'), f'评级应为甲乙丙: {summ}'
st = ok(call('GET', '/quality/mr-qc/stats', token=t), '质控统计')
for k in ['byScoreType', 'byMonthDept', 'deptRank', 'topDeductItems']:
    assert k in st, f'统计缺键 {k}: {list(st)}'
# 诚信断言（本套最要紧的一条）：环节质控在本平台是在院实时现算、不落库，
# 「按评分类型分类汇总」里它必须如实报 persisted=false 且份数 0 并附注原因，
# 绝不能拿实时现算值冒充历史评分单来把这一格填满。改成那样做的实现会在此立刻挂。
bst = st['byScoreType']
assert isinstance(bst, list) and len(bst) >= 2, bst
running = next((r for r in bst if r.get('scoreType') == 'RUNNING'), None)
terminal = next((r for r in bst if r.get('scoreType') == 'TERMINAL'), None)
assert running is not None and terminal is not None, bst
assert running.get('persisted') is False, f'运行质控不落库，必须 persisted=false: {running}'
assert int(running.get('sheets') or 0) == 0, f'运行质控无历史评分单，份数必须为 0: {running}'
assert running.get('avgScore') is None, f'运行质控不得给出均分（无评分单可均）: {running}'
assert running.get('note'), '运行质控必须附注「为何为空」，否则读者会当成数据缺失'
assert terminal.get('persisted') is True and int(terminal.get('sheets') or 0) >= 1, terminal
csv_text = call('GET', '/quality/mr-qc/stats.csv', token=t, raw=True)
assert len(csv_text) > 0, '统计 CSV 应可导出'
print(f"[5] 病案终末质控 OK（字典 {len(items)} 项 / 未出院 4844 / 预填→评级 {summ.get('grade')} / 重复提交 4841 / 统计+CSV）")

# ---- 6) 费别与费用类别金额汇总（兑现 1034★/675/3684）----
cats = ok(call('GET', '/masterdata/fee-categories', token=t), '费用类别字典')
assert len(cats) >= 8, f'类别种子应 >=8: {len(cats)}'
# 国标码出厂必须为空——预置即为伪造（配套产品/实施期填）
assert all(not c.get('std_code') and not c.get('stdCode') for c in cats), '国标码出厂必须留空'
month = today[:7]
byins = ok(call('GET', f'/stats/fee-by-insurance?month={month}', token=t), '按费别汇总')
bycat = ok(call('GET', f'/stats/fee-by-category?month={month}', token=t), '按费用类别汇总')
for r, name in ((byins, '费别'), (bycat, '费用类别')):
    assert 'caveat' in str(r), f'{name}汇总必须随体返回口径近似说明（端点与页面同源）'
c1 = call('GET', f'/stats/fee-by-insurance.csv?month={month}', token=t, raw=True)
c2 = call('GET', f'/stats/fee-by-category.csv?month={month}', token=t, raw=True)
assert len(c1) > 0 and len(c2) > 0, 'CSV 应可导出'
print(f"[6] 费别/费用类别汇总 OK（字典 {len(cats)} 类 / 国标码留空 / 两份 JSON+CSV / 口径随体返回）")

print('\ne2e-v42-nursing 全部通过 ✅')
