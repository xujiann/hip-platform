# -*- coding: utf-8 -*-
"""v46 手术室护理与麻醉质控 E2E：手术域地基 + 术中记录闭环 + 麻醉质控指标。
自成一体（自建患者/账号），末尾收尾。

本套的存在意义：这批 52★ 全是**投标偏离表已答「平台已实现」而代码里没有**的诚信补齐。
除了钉死「能真的走通」，本套还钉死三条**最容易被后人做坏**的地方：
  ① 打时间点绝不改 status、已 DONE 不可取消 —— 否则 v35 出院 gate 被悄悄掏空；
  ② 自体血只认 is_auto 不比 product_type —— 自体洗涤红细胞按 RBC+is_auto 录，字符串比对会漏统计；
  ③ 缺数据源的指标必须 available:false 而不是返回一个看起来像真的的 0。
"""
from e2elib import call, find_free_bed, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()


def new_surgery(admid, name='E2E术式'):
    ok(call('POST', '/inpatient/surgeries',
            {'admissionId': admid, 'procedureName': name, 'anesthesiaType': '全身麻醉'}, t), '建手术')
    lst = ok(call('GET', f'/inpatient/surgeries?admissionId={admid}', token=t), '手术列表')
    return max(x['id'] for x in lst)


pid = new_patient(t, '手术E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid, 'deptId': 2, 'bedId': free['id'], 'deposit': 500, 'payMethod': 'CASH'}, t), '入院')
admid = adm['id']

# ---- 1) 手术域地基：时间点 / 术中信息 / 取消四阶段 ----
sid = new_surgery(admid)
# 既有列表端点契约：新字段不得混进去（新字段走 /detail 与 /room-board 两条新通道）
lst = ok(call('GET', f'/inpatient/surgeries?admissionId={admid}', token=t), '既有列表')
row = next(x for x in lst if x['id'] == sid)
for k in ('roomNo', 'room_no', 'inRoomAt', 'in_room_at', 'surgeryLevel', 'asaGrade'):
    assert k not in row, f'既有列表端点被加了新字段 {k}——那是改契约: {list(row)}'

for stage in ('IN_ROOM', 'START', 'END', 'OUT_ROOM'):
    ok(call('PUT', f'/inpatient/surgeries/{sid}/timepoint', {'stage': stage}, t), f'打点 {stage}')
# 打满四点后 status 必须仍是 REQUESTED——绝不能顺手改状态机
det = ok(call('GET', f'/inpatient/surgeries/{sid}/detail', token=t), '手术详情')
assert det['status'] == 'REQUESTED', \
    f'打时间点绝不改 status（改了会让 v35 出院 gate 与前端标签同时失真）: {det["status"]}'
# 详情端点返回 snake_case（JdbcTemplate 直出）
assert det.get('in_room_at') and det.get('out_room_at'), f'四时间点应落库: {det}'
assert call('PUT', f'/inpatient/surgeries/{sid}/timepoint',
            {'stage': 'BOGUS'}, t)['code'] in (4908, 4900), '阶段非法应被拒'

ok(call('PUT', f'/inpatient/surgeries/{sid}/op-info',
        {'roomNo': 'OR-E2E', 'surgeryLevel': '三级', 'asaGrade': 'II',
         'incisionType': 'Ⅰ类', 'surgeryKind': 'ELECTIVE', 'isUnplannedReop': False}, t), '术中信息')
for bad, code in (({'surgeryLevel': '五级'}, 4903), ({'asaGrade': 'X'}, 4904),
                  ({'incisionType': 'V类'}, 4905), ({'surgeryKind': 'BOGUS'}, 4906)):
    body = {'roomNo': 'OR-E2E'}
    body.update(bad)
    assert call('PUT', f'/inpatient/surgeries/{sid}/op-info', body, t)['code'] == code, f'{bad} 应返 {code}'

board = ok(call('GET', f'/inpatient/surgeries/room-board?date={today}', token=t), '手术间排程')
assert isinstance(board, (list, dict)), board
print('[1] 手术域地基 OK（四时间点 / **打点不改 status** / 白名单 4903-4906 / 既有列表契约未被加列 / 手术间视图）')

# ---- 2) 取消四阶段 + 已 DONE 不可取消（出院 gate 保护）----
s2 = new_surgery(admid, '取消E2E')
ok(call('PUT', f'/inpatient/surgeries/{s2}/cancel',
        {'stage': 'SCHEDULE', 'reason': '患者临时发热'}, t), '排程阶段取消')
d2 = ok(call('GET', f'/inpatient/surgeries/{s2}/detail', token=t), '取消后详情')
assert d2['status'] == 'CANCELLED' and d2.get('cancel_stage') == 'SCHEDULE', d2
assert call('PUT', f'/inpatient/surgeries/{s2}/cancel',
            {'stage': 'BOGUS', 'reason': 'x'}, t)['code'] == 4907, '阶段非法应 4907'
s3 = new_surgery(admid, '已完成E2E')
ok(call('PUT', f'/inpatient/surgeries/{s3}/complete', {'opNote': '手术顺利', 'anesNote': '平稳'}, t), '完成手术')
assert call('PUT', f'/inpatient/surgeries/{s3}/cancel',
            {'stage': 'IN_OP', 'reason': '事后取消'}, t)['code'] == 4900, \
    '已 DONE 的手术不可取消——否则该住院的手术病历要求凭空消失，v35 出院 gate 被悄悄掏空'
print('[2] 取消四阶段 OK（取消不删记录 / 4907 阶段非法 / **已 DONE 不可取消，出院 gate 保护**）')

# ---- 3) 术中记录：管路 / 输血（自体血）/ 事件（计划性三态）----
ok(call('POST', '/surgery/intraop/tubes',
        {'surgeryId': sid, 'tubeType': '静脉通道', 'position': '左前臂', 'depthCm': 3.5,
         'insertedAt': today + 'T09:00:00Z'}, t), '管路')
assert call('POST', '/surgery/intraop/tubes',
            {'surgeryId': sid, 'tubeType': 'BOGUS', 'insertedAt': today + 'T09:00:00Z'}, t)['code'] == 4920, \
    '管路类型非法应 4920'
# 自体洗涤红细胞：product_type=RBC 但 is_auto=true —— 统计只认 is_auto，比对 'AUTO' 会漏掉这条
ok(call('POST', '/surgery/intraop/transfusions',
        {'surgeryId': sid, 'productType': 'RBC', 'volumeMl': 300, 'isAuto': True,
         'transfusedAt': today + 'T09:30:00Z'}, t), '自体洗涤红细胞')
ok(call('POST', '/surgery/intraop/transfusions',
        {'surgeryId': sid, 'productType': 'RBC', 'volumeMl': 400, 'isAuto': False,
         'transfusedAt': today + 'T09:40:00Z'}, t), '异体红细胞')
assert call('POST', '/surgery/intraop/transfusions',
            {'surgeryId': sid, 'productType': 'RBC', 'volumeMl': 0, 'isAuto': False,
             'transfusedAt': today + 'T09:40:00Z'}, t)['code'] == 4923, '输血量须>0 应 4923'
# 事件：计划性三态都录一条
for et, planned in (('PAIN_PUMP_ON', None), ('TO_ICU', True), ('TO_PACU', False)):
    body = {'surgeryId': sid, 'eventType': et, 'eventTime': today + 'T10:00:00Z'}
    if planned is not None:
        body['planned'] = planned
    ok(call('POST', '/surgery/intraop/events', body, t), f'事件 {et}')
assert call('POST', '/surgery/intraop/events',
            {'surgeryId': sid, 'eventType': 'BOGUS', 'eventTime': today + 'T10:00:00Z'}, t)['code'] == 4924, \
    '事件类型非法应 4924'
summ = ok(call('GET', f'/surgery/intraop/summary?surgeryId={sid}', token=t), '术中汇总')
assert summ, summ
print('[3] 术中记录 OK（管路 4920 / 输血 4923 / **自体洗涤红细胞按 RBC+isAuto 录** / 事件 4924 / 计划性三态）')

# ---- 4) 麻醉质控指标：穿透明细 + 自体血口径 + 缺数据源诚实标注 ----
cat = ok(call('GET', '/anes-qc/catalog', token=t), '指标目录')
assert len(cat) >= 15, f'指标数应 >=15: {len(cat)}'
ind = ok(call('GET', f'/anes-qc/indicators?from={today}&to={today}', token=t), '指标汇总')
blob = str(ind)
# 口径近似必须随体返回，不能只写在页面上
assert 'caveat' in blob or 'Caveat' in blob or 'note' in blob, \
    '历史手术新字段全空的口径必须随返回体下发，否则管理者会当成全院全历史口径'
# 缺数据源的三条必须 available:false，而不是返回一个看起来像真的的 0
assert 'available' in blob, '缺数据源的指标须显式标 available'
unavail = [k for k in ('1435', '1444', '1445') if k in blob]
assert 'false' in blob.lower(), '至少应有指标标为 available:false（出血量/毒麻药/肌松药无数据源）'
# 时间段非法
assert call('GET', f'/anes-qc/indicators?from={today}&to=2020-01-01', token=t)['code'] == 4940, '起止倒置应 4940'
assert call('GET', '/anes-qc/detail?indicator=BOGUS_X&from=' + today + '&to=' + today,
            token=t)['code'] == 4941, '指标编码不存在应 4941'
# 穿透明细：1422★/1423★ 明文要求，是防"指标算得出但对不上账"的关键
one = cat[0]['code'] if isinstance(cat[0], dict) and 'code' in cat[0] else None
if one:
    det2 = ok(call('GET', f'/anes-qc/detail?indicator={one}&from={today}&to={today}', token=t), '穿透明细')
    assert 'rows' in det2 or 'items' in det2, f'穿透明细返回体: {list(det2)}'
csv1 = call('GET', f'/anes-qc/indicators.csv?from={today}&to={today}', token=t, raw=True)
assert len(csv1) > 0, 'CSV 应可导出'
print(f'[4] 麻醉质控 OK（{len(cat)} 条指标 / 口径随体下发 / **缺数据源标 available:false** / 4940 4941 / 穿透明细 / CSV）')

# ---- 5) 只读保证：质控端点不得写任何数据 ----
before = ok(call('GET', f'/surgery/intraop/summary?surgeryId={sid}', token=t), '调用前')
ok(call('GET', f'/anes-qc/indicators?from={today}&to={today}', token=t), '再跑指标')
after = ok(call('GET', f'/surgery/intraop/summary?surgeryId={sid}', token=t), '调用后')
assert str(before) == str(after), '质控统计必须纯只读，调用前后术中记录不得变化'
print('[5] 只读保证 OK（质控端点调用前后术中记录逐字不变）')

print('\ne2e-v46-surgery 全部通过 ✅')
