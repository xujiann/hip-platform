# -*- coding: utf-8 -*-
"""v47 写入校验收口 E2E：体征写路径服务端校验 + 病历类型白名单。

本套的存在意义：这是**全仓唯一一批「改既有写路径」的改动**，风险不在"校验写得对不对"，
而在"有没有把本来能写的写坏"。所以本套除了钉死新校验，更钉死三条**回归红线**：
  ① gate 默认 warn 必须**真的放行**——999℃ 在 warn 档要能查回来，
     不能是"偷偷 block 再假装成功"（那等于静默丢数据，比不校验更糟）；
  ② **只录出入量的夜班液体平衡记录不得被 4823 拦**——护理实操里这是正常记录，
     不是漏测；口径若停在"六项体征全空"，院方切 block 当天夜班就写不进去；
  ③ off 档返回体必须与 v46 **逐字节同形**（不多 warnings 键），存量对接方零感知。
"""
from e2elib import call, find_free_bed, login, new_patient, ok  # noqa: E402

t = login()
GATE = '/config/emr.gate.vital.range'


def gate(v):
    ok(call('PUT', f'{GATE}?value={v}', token=t), f'置 gate={v}')


def vitals(admid, body):
    return call('POST', f'/inpatient/admissions/{admid}/vitals', body, t)


pid = new_patient(t, '写入校验E2E', sex='F')['id']
free = find_free_bed(t)
admid = ok(call('POST', '/inpatient/admissions',
                {'patientId': pid, 'deptId': 2, 'bedId': free['id'],
                 'deposit': 500, 'payMethod': 'CASH'}, t), '入院')['id']

try:
    # ---- 1) 默认档就是 warn，且 warn 真的放行 ----
    r = vitals(admid, {'temperature': 999, 'pulse': 80})
    assert r['code'] == 0, f'默认档必须放行（默认 warn 不是 block）：{r}'
    vid = r['data']['id']
    assert r['data'].get('warnings'), f'warn 档必须回带 warnings，否则护士看不见：{r["data"]}'
    # 关键：确认**真的落库了**，而不是返回成功却没写
    lst = ok(call('GET', f'/inpatient/admissions/{admid}/vitals', token=t), '回查体征')
    got = next((x for x in lst if x.get('id') == vid), None)
    assert got and float(got['temperature']) == 999.0, \
        f'warn 档必须真的落库——"返成功但不写"等于静默丢数据，比不校验更糟：{got}'
    print('[1] warn 档 OK（默认即 warn / 回带 warnings / **999℃ 确实落库可回查**）')

    # ---- 2) block 档四类判定 ----
    gate('block')
    assert vitals(admid, {'temperature': 999})['code'] == 4824, '量程越界应 4824'
    assert vitals(admid, {'tempAfterCooling': 999})['code'] == 4824, \
        '降温后体温同样印在三测单上，也必须受量程约束'
    assert vitals(admid, {'temperature': 36.5, 'measuredAt': '2099-01-01T00:00:00Z'})['code'] == 4825, \
        '测量时间晚于当前时刻应 4825'
    assert vitals(admid, {'temperature': 36.5, 'measureSite': 'BOGUS'})['code'] == 4822, \
        '测量部位非法应 4822'
    assert vitals(admid, {})['code'] == 4823, '整行全空且无未测原因应 4823'
    print('[2] block 档 OK（4824 量程含降温后体温 / 4825 时序 / 4822 部位 / 4823 整行全空）')

    # ---- 3) 红线：只录出入量的夜班液体平衡记录不得被拦 ----
    for body, why in (({'intakeMl': 1500}, '只录入量'), ({'outputMl': 1200}, '只录出量'),
                      ({'stoolCount': 1}, '只录大便'), ({'weightKg': 62.5}, '只录体重')):
        r = vitals(admid, body)
        assert r['code'] == 0, \
            f'{why}是正常护理记录不是漏测，block 档也必须放行（否则夜班写不进去）：{r}'
    ok(vitals(admid, {'notMeasuredReason': '外出检查'}), '带未测原因的空行')
    ok(vitals(admid, {'temperature': 36.8, 'pulse': 80, 'measureSite': 'AXILLARY'}), '正常体征')
    print('[3] 红线 OK（**出入量/体重/大便任一即不算漏测**，block 档亦放行；边界值与合法部位放行）')

    # ---- 4) off 档返回体与 v46 逐字节同形 ----
    gate('off')
    r = vitals(admid, {'temperature': 999, 'measureSite': 'BOGUS'})
    assert r['code'] == 0, f'off 档必须整段旁路：{r}'
    assert 'warnings' not in r['data'], \
        f'off 档返回体不得多出 warnings 键——存量对接方按 v46 形状解析：{list(r["data"])}'
    print('[4] off 档 OK（整段旁路 / **返回体不多 warnings 键**，存量对接方零感知）')

    # ---- 5) 病历类型白名单 9129（不受 gate 管辖）----
    r = call('POST', f'/inpatient/admissions/{admid}/records',
             {'recordType': '随便写的', 'content': '正文'}, t)
    assert r['code'] == 9129, \
        f'非白名单病历类型应 9129——此前任意字符串直落库，而完整性质控是精确等值判定，' \
        f'写错类型 100% 漏判且零报错：{r}'
    ok(call('POST', f'/inpatient/admissions/{admid}/records',
            {'recordType': 'PROGRESS', 'content': '病程正文'}, t), '合法类型')
    ok(call('POST', f'/inpatient/admissions/{admid}/records', {'content': '不传类型'}, t),
       '不传类型仍兜底 PROGRESS（既有行为不动）')
    print('[5] 病历类型白名单 OK（9129 拦非法 / 合法放行 / **不传仍兜底 PROGRESS**）')

finally:
    gate('warn')   # 还原默认档，避免串味到后续套件

print('\ne2e-v47-writeguard 全部通过 ✅')
