# -*- coding: utf-8 -*-
"""v49 麻醉质控口径修正 E2E：**机械对账** + by/bucket 两个新参数的契约。

本套的存在意义与 v46 那套不同。v46 那套证明的是「指标算得出来」；
本套证明的是 **「算出来的数对得上账」**——这正是 v46 漏掉的那一半：

  v46 的机械对账只覆盖了 1428★ 一条指标（汇总合计 == 穿透明细条数）。
  其余 19 条 available 指标从没被机械核对过，于是
    · 1429★ 的 deaths / 分母数的是**手术台次**而不是**患者**（一人多台被重复计入）
    · 1433★/1434★ 汇总是**患者粒度**、穿透明细是**记录粒度**（点开当场对不上账）
    · 1437★ 汇总按**事件日期**归桶、明细按**手术锚点日期**归桶（两套口径互相核对不了）
    · 1437★ 的 in_use 压根**点不出来**
  一路活到评委面前。**缺陷不是没被发现，是根本没有人（没有机器）去核对。**

因此本套钉死四件事：
  ① **逐条 available 指标**都要对账，指标清单从 GET /catalog 现取现遍历——
     以后新增指标会自动被纳入，而不是又漏掉一条；表里没有判据的指标当场报错，不许静默跳过。
  ② **术后延迟发生的事件按事件日期归桶**（次日拔管 / 术后非计划转 ICU / 48 小时拆镇痛泵）——
     全部用**增量**断言（建之前先量一遍，建之后再量一遍，只看差值），
     不依赖库里有没有别的数据，也就不会在脏库上假红假绿。
  ③ **占比的分子取得出来**：bucket 穿透回来的条数 == 该行的 cases == bucketSummaryCount。
  ④ **既有对接方零感知**：不传 by/bucket 时，v46 的返回体键一个不少；
     非事件类指标三种 by 的数值逐字相同；不传 bucket == 全量。

时间一律**从被测对象自己的时间线派生**（详见 timeline()）：本仓已因时间字面量炸过四次。
"""
import datetime as _dt  # noqa: E402
import json as _json  # noqa: E402

from e2elib import call, find_free_bed, login, new_patient, ok, q  # noqa: E402

BJ = _dt.timezone(_dt.timedelta(hours=8))

# v46 已上线的返回体键：**只增不改不删**。删一个键或改一个键名就是断掉一条已上线的对接
V46_TOP_KEYS = ['from', 'to', 'days', 'onTimeMinutes', 'standardNote',
                'anchorNote', 'timepointCaveat', 'coverage', 'indicators']
V46_INDICATOR_KEYS = ['code', 'name', 'available', 'detailEndpoint', 'rows']
V46_DETAIL_KEYS = ['code', 'name', 'available', 'from', 'to', 'limit', 'items', 'truncated', 'anchorNote']

# 事件类指标：by=event|surgery 只对这几条有意义（错误码分段表 4943 行登记在案）
EVENT_INDICATORS = {'1437', '1446', '1447', '1448', '1449', '1450'}

# 缺数据源三条：1435★ 术中出血量 / 1444★ 毒麻药 / 1445★ 肌松药
UNAVAILABLE = {'1435', '1444', '1445'}

# ---- 逐指标的对账判据：汇总的哪个量必须等于穿透明细的条数 ----
# 判据因指标而异，一刀切必错：
#   · 台次/记录/事件粒度的指标 → 各行计数之和 == 明细条数
#   · 1433★/1434★ 是**患者粒度**（汇总先按 patient_id 去重）→ 人数 == 明细条数（明细也须是患者粒度）
#   · 1438★ 汇总行有 limit 50 → 走 summary.cases，不用行合计
#   · 1437★ 的 in_use 是全历史累计、与时间窗内的明细不同 population → 走 bucket=in_use 另一条穿透
# ROWS_SUM: 各汇总行该列之和 == 明细条数；SUMMARY: summary 的该列 == 明细条数
RECON = {
    '1424': ('ROWS_SUM', 'first_cases'),
    '1425': ('ROWS_SUM', 'cases'),
    '1426': ('ROWS_SUM', 'cases'),
    '1427': ('ROWS_SUM', 'cross_day_cases'),
    '1428': ('ROWS_SUM', 'cases'),
    '1429': ('ROWS_SUM', 'cases'),
    '1430': ('ROWS_SUM', 'cases'),
    '1431': ('ROWS_SUM', 'cases'),
    '1432': ('ROWS_SUM', 'records'),
    '1433': ('ROWS_SUM', 'patients'),            # 患者粒度：明细一行一名患者
    '1434': ('ROWS_SUM', 'transfused_patients'),  # 同上
    '1436': ('ROWS_SUM', 'cases'),
    '1437': ('ROWS_SUM2', ('added', 'removed')),  # 明细是 ON/OFF 事件流水，两列之和
    '1438': ('SUMMARY', 'cases'),                 # 汇总行 limit 50，行合计不等于总量
    '1439': ('ROWS_SUM', 'cases'),
    '1446': ('ROWS_SUM', 'events'),
    '1447': ('ROWS_SUM', 'events'),
    '1448': ('ROWS_SUM', 'events'),
    '1449': ('ROWS_SUM', 'events'),
    '1450': ('ROWS_SUM', 'events'),
}


def _iso(dt):
    """转成服务端 parseTime 认的带偏移 ISO-8601。"""
    return dt.strftime('%Y-%m-%dT%H:%M:%S') + '+00:00'


def _parse(v):
    """detail 端点回的是 '2026-09-05T03:01:39.716+00:00'（JdbcTemplate 直出 + Jackson）。"""
    return _dt.datetime.fromisoformat(v.replace('Z', '+00:00'))


def _bjday(dt):
    """事件落在**业务时区**的哪一天。绝不用运行机的本地时区：CI 跑 UTC，用本地时区必差一天。"""
    return dt.astimezone(BJ).date().isoformat()


def timeline(sid, token):
    """读手术真实时间线（入室 / 开台 / 出室）。

    **为什么必须派生而不能写死墙钟字面量**：v46 那套原先把术中事件时间硬编码成 `T10:00:00Z`，
    而入室时间走服务端 `Instant.now()`，于是成败取决于**跑在一天里的什么时辰**——
    10:00 UTC 前跑绿、之后跑撞 4925 红。时间断言一律从被测对象自己的时间线派生。

    统计锚点是 `coalesce(start_at, ...)`，故「手术当天」一律取 **start_at** 的业务日，
    不拿入室或出室去凑——差一秒跨零点就是差一天，本仓已因这类事炸过四次。
    """
    d = ok(call('GET', f'/inpatient/surgeries/{sid}/detail', token=token), '读时间线')
    return _parse(d['in_room_at']), _parse(d['start_at']), _parse(d['out_room_at'])


t = login()


def qc(path):
    return ok(call('GET', '/anes-qc/' + path, token=t), 'GET /anes-qc/' + path)


def indicator(code, frm, to, **kw):
    extra = ''.join(f'&{k}={q(str(v), safe="")}' for k, v in kw.items() if v is not None)
    d = qc(f'indicators?from={frm}&to={to}&indicator={code}{extra}')
    assert len(d['indicators']) == 1, d
    return d['indicators'][0]


def detail(code, frm, to, **kw):
    extra = ''.join(f'&{k}={q(str(v), safe="")}' for k, v in kw.items() if v is not None)
    return qc(f'detail?indicator={code}&from={frm}&to={to}{extra}')


def rows(code, frm, to, **kw):
    return indicator(code, frm, to, **kw).get('rows') or []


def _n(row, key):
    v = row.get(key)
    return 0 if v is None else v


def row_sum(rs, key):
    return sum(_n(r, key) for r in rs)


def pick(rs, **match):
    """取汇总里满足全部条件的那一行的某列合计（取不到算 0，用于增量断言）"""
    return [r for r in rs if all(str(r.get(k)) == str(v) for k, v in match.items())]


# ============================================================================
# 建数据：同一次住院两台手术（1429★ 患者粒度的载体）+ 输血 + 术中/术后事件
# ============================================================================
pid = new_patient(t, '麻醉口径E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid, 'deptId': 2, 'bedId': free['id'], 'deposit': 500, 'payMethod': 'CASH'}, t), '入院')
admid = adm['id']


def new_surgery(name, room):
    ok(call('POST', '/inpatient/surgeries',
            {'admissionId': admid, 'procedureName': name, 'anesthesiaType': '全身麻醉'}, t), '建手术')
    lst = ok(call('GET', f'/inpatient/surgeries?admissionId={admid}', token=t), '手术列表')
    sid = max(x['id'] for x in lst)
    for stage in ('IN_ROOM', 'START', 'END', 'OUT_ROOM'):
        ok(call('PUT', f'/inpatient/surgeries/{sid}/timepoint', {'stage': stage}, t), f'打点 {stage}')
    ok(call('PUT', f'/inpatient/surgeries/{sid}/op-info',
            {'roomNo': room, 'surgeryLevel': '三级', 'asaGrade': 'II', 'incisionType': 'Ⅰ类',
             'surgeryKind': 'ELECTIVE', 'isUnplannedReop': False}, t), '术中信息')
    return sid


# ---- ① 1429★：同一次住院两台同 ASA 的手术 —— 第二台只加台次，不加人次 ----
s1 = new_surgery('V49口径E2E术一', 'V49-OR-A')
in1, start1, out1 = timeline(s1, t)
day = _bjday(start1)                             # 手术当天 = 统计锚点 start_at 的业务日
day1 = _bjday(out1 + _dt.timedelta(hours=24))    # 术后次日（拔管 / 转 ICU 事件真正发生的那一天）
day2 = _bjday(out1 + _dt.timedelta(hours=48))    # 术后第三日（拆镇痛泵那一天）
assert day < day1 < day2, f'三个日子必须严格递增：{day} / {day1} / {day2}'

asa_before = rows('1429', day, day)
cases_before = row_sum(pick(asa_before, asa_grade='II'), 'cases')
patients_before = row_sum(pick(asa_before, asa_grade='II'), 'patients')

s2 = new_surgery('V49口径E2E术二', 'V49-OR-B')
# 两台手术必须落在同一个业务日，否则下面的增量断言测的是「跨了零点」而不是口径。
# 正好跨零点时重跑一次即可——**宁可明说，不要偷偷放过**
assert _bjday(timeline(s2, t)[1]) == day, \
    f'两台手术跨了业务日零点（{day} vs {_bjday(timeline(s2, t)[1])}），本次运行的增量断言不成立，请重跑'
asa_after = rows('1429', day, day)
asa_row = pick(asa_after, asa_grade='II')
cases_after = row_sum(asa_row, 'cases')
patients_after = row_sum(asa_row, 'patients')
# 基线是在建完**第一台**之后量的，故这里看的是「同一次住院再加一台手术」带来的增量
assert cases_after - cases_before == 1, \
    f'第二台手术应让 ASA II 的台次 +1，实测 +{cases_after - cases_before}'
assert asa_row and 'patients' in asa_row[0], (
    '1429★ 必须给出**患者粒度**的 patients 列。'
    'v46 只有台次列：分子分母都是台次，而《2022 版》「各 ASA 分级患者麻醉死亡率」的分子分母都是患者')
assert patients_after - patients_before == 0, (
    '同一次住院再加一台手术**不应多算一个人次**，实测 +%d。'
    'v46 是台次口径：一名患者一次住院做 3 台手术后死亡会被记成 3 例死亡，'
    'ASA 分级死亡率是对外上报指标，分子多算即失真' % (patients_after - patients_before))
# 明细必须说得清人次是怎么数出来的，否则「患者粒度」只是一句口号
det29 = detail('1429', day, day)
assert not det29['truncated'], '明细被截断时对账不成立，请缩窗'
assert all('counts_as_patient' in it and 'attributed_asa_grade' in it for it in det29['items']), \
    f'1429★ 明细须逐行给出 counts_as_patient / attributed_asa_grade：{list(det29["items"][0])}'
assert sum(1 for it in det29['items'] if it['counts_as_patient']) == row_sum(rows('1429', day, day), 'patients'), \
    '1429★ 明细里「计入人数」的行数必须等于各分级 patients 之和 —— 这就是患者粒度的机械对账'
print("[1] 1429★ 患者粒度 OK（同住院再加一台 → 台次 +1 而人次 +0；明细 counts_as_patient 行数 == Σpatients）")

# ---- ② 1432★/1433★/1434★：输血三条（含自体洗涤红细胞 RBC + isAuto）----
during = _iso(in1 + _dt.timedelta(seconds=10))
tx_before = rows('1434', day, day)
p_before = row_sum(tx_before, 'transfused_patients')
sg_before = row_sum(tx_before, 'transfused_surgeries')
band_before = row_sum(pick(rows('1433', day, day), band='400–1000ml'), 'patients')
for sid, ptype, ml, auto in ((s1, 'AUTO', 600, True), (s1, 'RBC', 300, True),
                             (s1, 'RBC', 400, False), (s2, 'RBC', 200, False)):
    ok(call('POST', '/surgery/intraop/transfusions',
            {'surgeryId': sid, 'productType': ptype, 'volumeMl': ml, 'isAuto': auto,
             'transfusedAt': during}, t), f'输血 {ptype} {ml}')
tx_after = rows('1434', day, day)
assert row_sum(tx_after, 'transfused_patients') - p_before == 1, \
    '口径是**患者数**不是台次数：同一患者两台手术都输了血只算一人'
assert row_sum(tx_after, 'transfused_surgeries') - sg_before == 2, '台次数另列，不与患者数混为一谈'
# 自体血 600(AUTO) + 300(RBC+isAuto) = 900 → 落 400–1000ml 档。
# 若按 product_type='AUTO' 字符串比对会只算 600，且漏得悄无声息
assert row_sum(pick(rows('1433', day, day), band='400–1000ml'), 'patients') - band_before == 1, \
    '自体血只认 is_auto 布尔位：自体洗涤红细胞按 RBC + isAuto=true 录，字符串比对会整片漏统计'
# 患者粒度的明细：行数 == 人数，且带患者去重键
d34 = detail('1434', day, day)
assert not d34['truncated'], '明细被截断时对账不成立'
assert len(d34['items']) == row_sum(rows('1434', day, day), 'transfused_patients'), (
    '1434★ 汇总是患者粒度，明细行数必须等于患者数。'
    'v46 的明细是记录粒度：汇总说「3 人」而明细列出 7 行，点开穿透当场对不上账')
assert d34['items'] and ('patient_id' in d34['items'][0] or 'patient_no' in d34['items'][0]), \
    f'患者粒度明细必须带患者去重键（与汇总的 patient_id 同源）：{list(d34["items"][0])}'
print('[2] 输血三条 OK（1434★ 人数 +1 / 台次 +2；1433★ 自体血只认 is_auto；**明细已是患者粒度且带去重键**）')

# ---- ③ 事件类指标：术后延迟事件必须按**事件日期**归桶 ----
after24 = _iso(out1 + _dt.timedelta(hours=24))   # 次日在 ICU 内延迟拔管 / 术后非计划转 ICU
after48 = _iso(out1 + _dt.timedelta(hours=48))   # 术后 48 小时拆镇痛泵（规范里的常规时点）


def ev_count(code, frm, to, **match):
    kw = {k: v for k, v in match.items() if k in ('by',)}
    sel = {k: v for k, v in match.items() if k not in ('by',)}
    return row_sum(pick(rows(code, frm, to, **kw), **sel), 'events')


def in_use_at(d):
    """汇总里截止日那一行的 in_use（截至该日末仍在用的镇痛泵台数）"""
    r = [x for x in rows('1437', day, d) if str(x.get('op_day')) == d]
    assert r, f'1437★ 的日表必须含截止日 {d} 那一行'
    return _n(r[0], 'in_use')


def in_use_drill(d):
    """bucket=in_use 的「在用清单」穿透，返回 (返回体, 台数)"""
    b = detail('1437', day, d, bucket='in_use')
    assert not b['truncated'], '在用清单被截断时对账不成立'
    return b, sum((it.get('in_use') if 'in_use' in it else 1) for it in b['items'])


ext_d1_event = ev_count('1447', day1, day1, event_type='EXTUBATE')
ext_d1_surg = ev_count('1447', day1, day1, event_type='EXTUBATE', by='surgery')
ext_d0_surg = ev_count('1447', day, day, event_type='EXTUBATE', by='surgery')
icu_d1_event = ev_count('1446', day1, day1, target='转入 ICU', planned_name='非计划')
# 装泵前先量在用量基线：装了要 +1、拆了要回到基线，两头都量才不是「0 == 0」的自欺
inuse_d1_before = in_use_at(day1)
inuse_d2_before = in_use_at(day2)
drill_d1_before = in_use_drill(day1)[1]

ok(call('POST', '/surgery/intraop/events',
        {'surgeryId': s1, 'eventType': 'PAIN_PUMP_ON', 'eventTime': during}, t), '术中装镇痛泵')
assert in_use_at(day1) - inuse_d1_before == 1, \
    f'装了一台泵、截至 {day1} 尚未拆，在用量应 +1'
assert in_use_drill(day1)[1] - drill_d1_before == 1, \
    '在用清单必须同步多出这一台——汇总加了 1 而清单点不出来，就又回到了 v46 那个「点不出来的数字」'
assert any(it.get('surgery_id') == s1 for it in in_use_drill(day1)[0]['items']), \
    '在用清单必须能落到具体台次（本次装泵的手术应在清单里）'
ok(call('POST', '/surgery/intraop/events',
        {'surgeryId': s1, 'eventType': 'EXTUBATE', 'eventTime': after24}, t), '次日延迟拔管')
ok(call('POST', '/surgery/intraop/events',
        {'surgeryId': s1, 'eventType': 'TO_ICU', 'eventTime': after24, 'planned': False}, t), '术后非计划转 ICU')

assert ev_count('1447', day1, day1, event_type='EXTUBATE') - ext_d1_event == 1, (
    '拔管发生在术后次日，就该落在**次日**的桶里。'
    'v46 按手术锚点日期归桶，会把它记进手术当天——管理者按月查会漏，跨月的更是整批错月')
assert ev_count('1446', day1, day1, target='转入 ICU', planned_name='非计划') - icu_d1_event == 1, \
    '「非计划转入 ICU」是重点监控指标，术后次日发生就该落在次日，错月即失真'
# 旧口径必须保留：by=surgery 复现 v46 的手术锚点归桶（率类计算要分子分母同 population）
assert ev_count('1447', day1, day1, event_type='EXTUBATE', by='surgery') - ext_d1_surg == 0, \
    'by=surgery 是手术锚点口径，手术不在次日就该是 0'
assert ev_count('1447', day, day, event_type='EXTUBATE', by='surgery') - ext_d0_surg == 1, \
    'by=surgery 下这次拔管应挂回**手术当天**——v46 旧口径必须原样保留，供率类计算使用'

# 1437★ 镇痛泵：拆泵在术后 48 小时，同样按事件日期落窗
removed_d2 = row_sum(rows('1437', day2, day2), 'removed')
ok(call('POST', '/surgery/intraop/events',
        {'surgeryId': s1, 'eventType': 'PAIN_PUMP_OFF', 'eventTime': after48}, t), '术后 48 小时拆镇痛泵')
assert row_sum(rows('1437', day2, day2), 'removed') - removed_d2 == 1, \
    '拆泵发生在术后第三日，就该落在那一天'
d37 = detail('1437', day2, day2)
assert not d37['truncated'] and len(d37['items']) == \
    row_sum(rows('1437', day2, day2), 'added') + row_sum(rows('1437', day2, day2), 'removed'), (
    '1437★ 的汇总按事件日期数、明细却按手术锚点日期落窗，两者永远对不上账——'
    'v49 把汇总与明细钉在同一个 by 上，这条断言就是那颗钉子')
for it in d37['items']:
    assert _bjday(_parse(it['event_time'])) == day2, f'明细里混进了不属于 {day2} 的事件：{it}'
print('[3] 事件日期归桶 OK（次日拔管 / 术后非计划转 ICU / 48h 拆泵各归各日；**by=surgery 原样复现 v46 旧口径**）')

# ---- ④ 1437★ in_use：从「点不出来的数字」到「点得出来且对得上」----
# 拆泵后在用量必须回到装泵前的基线：装了 +1、拆了 -1，净变化 0
assert in_use_at(day2) - inuse_d2_before == 0, \
    f'泵已在 {day2} 拆掉，截至当日末的在用量应回到装泵前的基线'
# 三个截止日各验一遍「汇总 == 在用清单」。其中 day1 那天在用量非零，
# 不是「0 == 0」的自欺——一条恒真的断言比没有断言更坏，它会让人以为这里有保障
for d in (day, day1, day2):
    body, pumps = in_use_drill(d)
    assert pumps == in_use_at(d), (
        'in_use 与「在用清单」穿透对不上账（截至 %s）：汇总说 %s 台、清单给出 %s 台。'
        'v46 的 in_use 是一个**点不出来**的数字（无下界的全历史累计 vs 带时间窗的明细），'
        '能点开、且点开就对得上，正是本版要补的那条路径' % (d, in_use_at(d), pumps))
assert in_use_at(day1) > 0, '在用量断言必须在非零的那一天验过，否则「0 == 0」永远成立'
print(f'[4] 1437★ in_use 可穿透 OK（{day1} 在用 {in_use_at(day1)} 台、{day2} 拆后回落基线；'
      f'三个截止日「汇总 == 在用清单」逐台对得上）')

# ============================================================================
# ⑤ 逐条 available 指标机械对账 —— 清单从 /catalog 现取，新增指标自动入网
# ============================================================================
catalog = qc('catalog')
assert catalog, '指标目录不应为空'
reconciled, skipped = [], []
for d in catalog:
    code = d['code']
    if not d['available']:
        assert code in UNAVAILABLE, \
            f'{code}★ 标了 available=false 但不在本套登记的缺数据源三条里，新增「做不了」的指标须同时说明为什么'
        continue
    assert code in RECON, (
        f'{code}★（{d["name"]}）是 available 的，本套却没有对账判据。'
        '指标清单是从 /catalog 现取的，新增指标会自动被纳入——请补一条判据，不许静默跳过：'
        '**静默跳过正是 v46 那批口径缺陷活下来的原因**')
    kind, key = RECON[code]
    body = detail(code, day, day)
    assert not body['truncated'], f'{code}★ 明细被截断，对账不成立（请缩窗，不要在截断状态下断言）'
    got = len(body['items'])
    if kind == 'ROWS_SUM':
        want = row_sum(rows(code, day, day), key)
    elif kind == 'ROWS_SUM2':
        want = sum(row_sum(rows(code, day, day), k) for k in key)
    else:
        want = _n(indicator(code, day, day).get('summary') or {}, key)
    assert want == got, (
        f'{code}★（{d["name"]}）汇总与穿透明细对不上账：汇总 {key} = {want}，明细 {got} 条。'
        '**穿透明细的存在意义正是防「指标算得出但对不上账」**')
    reconciled.append(code)
print(f'[5] 逐条对账 OK（{len(reconciled)} 条 available 指标全部核过：{" ".join(reconciled)}）'
      f'—— v46 只核过 1428★ 一条')

# ============================================================================
# ⑥ 占比穿透：bucket 参数（1422★「查看各指标占比详情」与「对占比准确性核对校验」）
# ============================================================================
bucketed = 0
for d in catalog:
    code = d['code']
    if not d['available']:
        continue
    probe = detail(code, day, day)
    cols = probe.get('bucketColumns')
    if not cols or 'in_use' in cols:
        # 给了占比却点不开 = 1422★ 的两条子要求不成立，必须当场红
        assert not any('pct' in r for r in rows(code, day, day)), \
            f'{code}★ 给了占比列 pct 却不支持 bucket 分桶穿透：占比的分子取不出来'
        continue
    sep = probe.get('bucketSeparator', ' / ')
    for r in rows(code, day, day):
        key = sep.join(str(r.get(c)) for c in cols)
        b = detail(code, day, day, bucket=key)
        assert not b['truncated'], f'{code}★ 分桶穿透被截断，对账不成立'
        assert len(b['items']) == _n(r, 'cases'), (
            f'{code}★ bucket=「{key}」穿透 {len(b["items"])} 条，汇总说 {_n(r, "cases")} 条——'
            '「这个 32% 是哪 8 台」仍然点不出来')
        assert b['bucketSummaryCount'] == _n(r, 'cases'), \
            f'{code}★ bucket=「{key}」自报的 bucketSummaryCount 与汇总行对不上'
        bucketed += 1
    # 不传 bucket == 全量（既有行为一字不变）。1438★ 的汇总行有 limit 50，
    # 只有不足 50 行时行合计才等于总量，故到上限就不用行合计比
    if len(rows(code, day, day)) < 50:
        assert len(probe['items']) == row_sum(rows(code, day, day), 'cases'), \
            f'{code}★ 不传 bucket 必须是全量明细，与本版之前逐字同行为'
print(f'[6] 占比分桶穿透 OK（{bucketed} 个分桶逐个核对：桶内条数 == 汇总 cases == bucketSummaryCount）')

# ============================================================================
# ⑦ 既有对接方零感知：不传新参数时的键与数值
# ============================================================================
plain = qc(f'indicators?from={day}&to={day}')
for k in V46_TOP_KEYS:
    assert k in plain, f'v46 的顶层键 {k} 不见了——既有返回体的键只增不改不删'
for k in ['surgeries', 'with_room', 'with_start', 'with_level', 'with_asa', 'note']:
    assert k in plain['coverage'], f'coverage 段的键 {k} 不见了（「先看覆盖率再看指标值」的依据）'
one28 = indicator('1428', day, day)
for k in V46_INDICATOR_KEYS:
    assert k in one28, f'v46 的指标体键 {k} 不见了'
for k in V46_DETAIL_KEYS:
    assert k in detail('1428', day, day), f'v46 的穿透体键 {k} 不见了'

# 非事件类指标：by 对它们没有意义，三种调法必须**逐字相同**——这才是真正的「零感知」
for d in catalog:
    code = d['code']
    if not d['available'] or code in EVENT_INDICATORS:
        continue
    a = _json.dumps(indicator(code, day, day), sort_keys=True, ensure_ascii=False)
    for by in ('event', 'surgery'):
        b = _json.dumps(indicator(code, day, day, by=by), sort_keys=True, ensure_ascii=False)
        assert a == b, f'{code}★ 不是事件类指标，by={by} 不得改变它的任何一个数'
# 事件类指标是**刻意的例外**：默认口径由「手术锚点日期」改成「事件日期」，
# 那是错误码分段表 4943 行登记在案的**口径变更**（v46 算错了，不是新功能），旧口径由 by=surgery 保留。
# 口径变更必须随返回体下发，只写在页面 alert 上不算——拿 API 取数的对接方看不到页面。
blob = str(indicator('1447', day, day))
assert '口径' in blob, '返回体必须带口径说明'
assert ('事件日期' in blob or '事件当日' in blob or '事件发生' in blob or 'by=event' in blob), \
    '归桶口径这一版改了，返回体必须写清改了什么、从哪个版本起生效，否则历史报表与新报表对不上时无从追溯'
print('[7] 既有契约 OK（v46 键一个不少 / 非事件类指标三种 by 逐字相同 / **口径变更随体下发**）')

# ============================================================================
# ⑧ 校验码：新增 4943 / 4944 不许把 v46 的 4940 / 4941 / 4942 带塌
# ============================================================================
assert call('GET', f'/anes-qc/indicators?from={day}&to={day}&indicator=1447&by=BOGUS',
            token=t)['code'] == 4943, \
    'by 只收 event / surgery：静默回落成默认口径 = 让人拿到一个说不清是哪套口径的数'
assert call('GET', f'/anes-qc/detail?indicator=1447&from={day}&to={day}&by=BOGUS',
            token=t)['code'] == 4943, '穿透同样要校验 by'
assert call('GET', f'/anes-qc/detail?indicator=1428&from={day}&to={day}&bucket={q("根本没有这个桶", safe="")}',
            token=t)['code'] == 4944, \
    'bucket 取值非法必须当场拒绝：静默返回全量会让人以为「这个桶就是这么多」，比报错坏得多'
assert call('GET', f'/anes-qc/indicators?from={day}&to=2020-01-01', token=t)['code'] == 4940, '起止倒置'
assert call('GET', f'/anes-qc/detail?indicator=BOGUS_X&from={day}&to={day}', token=t)['code'] == 4941
assert call('GET', f'/anes-qc/detail?indicator=1424&from={day}&to={day}&limit=201', token=t)['code'] == 4942
print('[8] 校验码 OK（新增 4943 by / 4944 bucket；v46 的 4940 4941 4942 未被带塌）')

# ============================================================================
# ⑨ 缺数据源三条：available:false，不给 rows / summary，**连一个数字都不许有**
# ============================================================================
def _no_numbers(node, where):
    if isinstance(node, bool):
        return
    if isinstance(node, (int, float)):
        raise AssertionError(f'{where} 里出现了数字 {node}：缺数据源的指标不许给近似值，'
                             '一个「看起来像真的」的 0 比不返回更坏')
    if isinstance(node, dict):
        for k, v in node.items():
            _no_numbers(v, f'{where}.{k}')
    elif isinstance(node, list):
        for v in node:
            _no_numbers(v, where + '[]')


for code in sorted(UNAVAILABLE):
    ind = indicator(code, day, day)
    assert ind['available'] is False, f'{code}★ 应标缺数据源'
    assert ind.get('unavailableReason'), f'{code}★ 必须写明为什么没有'
    assert 'rows' not in ind, f'{code}★ 不许给 rows：给一个空数组前端就会画出一张「全 0」的表'
    assert 'summary' not in ind, f'{code}★ 不许给 summary'
    _no_numbers(ind, f'{code}★ 的指标返回体')
    dd = detail(code, day, day)
    assert dd['available'] is False and dd['items'] == [] and dd.get('unavailableReason')
    assert 'rows' not in dd and 'summary' not in dd
assert '输血量' in indicator('1435', day, day)['unavailableReason'], \
    '1435★ 必须明写「输血量不是出血量」：拿前者冒充后者会直接误导备血与失血管理决策'
print('[9] 缺数据源三条 OK（1435 出血量 / 1444 毒麻药 / 1445 肌松药：available:false + 原因 + **零数字**）')

# ============================================================================
# ⑩ 只读保证：新增的 by / bucket 两条 SQL 分支同样不得写库
# ============================================================================
before = ok(call('GET', f'/surgery/intraop/summary?surgeryId={s1}', token=t), '调用前术中汇总')
for d in catalog:
    code = d['code']
    for by in ('event', 'surgery'):
        call('GET', f'/anes-qc/indicators?from={day}&to={day2}&indicator={code}&by={by}', token=t)
        call('GET', f'/anes-qc/detail?indicator={code}&from={day}&to={day2}&by={by}', token=t)
after = ok(call('GET', f'/surgery/intraop/summary?surgeryId={s1}', token=t), '调用后术中汇总')
assert str(before) == str(after), '质控统计必须纯只读，带 by / bucket 调用前后术中记录不得变化'
print('[10] 只读保证 OK（带 by / bucket 全量跑一遍，术中记录逐字不变）')

# ---- 收尾：释放床位。出院被 gate 拦住不算本套失败，但要说出来，别让床位悄悄漏光 ----
_r = call('POST', f'/inpatient/admissions/{admid}/discharge?payMethod=CASH', {}, t)
print('[收尾] 出院释放床位 ' + ('OK' if _r['code'] == 0 else f'未成功（{_r["code"]} {_r["message"]}），请人工清理'))

print('\ne2e-v49-anesqc 全部通过 ✅')
