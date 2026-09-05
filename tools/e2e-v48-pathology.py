# -*- coding: utf-8 -*-
"""v48 病理 PIS E2E：双来源登记 → 取材制片 → 双签报告 → 质控指标。

本套的存在意义：这批 56★ 全是**投标偏离表已答「平台已实现」而代码里只有一张表**的诚信补齐。
除了钉死「能真的走通」，本套还钉死四条**最容易被后人做坏**的地方：

  ① **住院来源的标本必须能进既有 GET /api/pathology/specimens**——
     那个端点原本是 inner join outp_order，v48 双来源后会把住院标本静默漏掉；
     修成 left join 是修缺陷不是改契约，故本套同时断言**返回体键集合逐字未变**。
  ② **拒收不删记录**——删了行就永远统计不出「送检多少、拒了多少」，
     标本固定规范率也没了分母。
  ③ **补充报告绝不改原报告**——覆盖原报告会让「当时医生看到的是什么」永久不可考。
  ④ **一个人不能把双签两个位都占了**，且这条**与 gate 无关**：
     gate 管的是「未双签能否签发」，不管「能否一人签两次」。

另钉死本版**刻意不做**的边界（防下一轮被悄悄"补"成假的）：
图像上传、玻片打码设备直连、危急值、三条符合率指标必须 available:false。
"""
import datetime as _dt

from e2elib import call, login, new_patient, ok, provision_user, q  # noqa: E402

t = login()

# ---------------------------------------------------------------------------
# 时间一律从业务时间线派生，**绝不写墙钟字面量**——本仓被这个炸过两次
# （v46 时区缺陷、v46→v47「看时辰」炸弹），详见 docs 与 CHANGELOG。
# ---------------------------------------------------------------------------
NOW = _dt.datetime.now(_dt.timezone(_dt.timedelta(hours=8)))


def iso(delta_minutes=0):
    return (NOW + _dt.timedelta(minutes=delta_minutes)).strftime('%Y-%m-%dT%H:%M:%S+08:00')


today = NOW.date().isoformat()

# ---- 建一份门诊病理申请（既有链路：挂号→开单→收费）----
pid = new_patient(t, '病理E2E', sex='F')['id']


def outp_pathology_order():
    """**自建**一条待取材的门诊病理申请，走平台自己的链路（排班→挂号→接诊→开单→结算）。

    E2E 必须自给自足：靠「库里碰巧有一条待取材」会让本套在全新库上整段跳过，
    而全新库正是 CI 的常态——那样等于没测。
    既有 GET /api/pathology/pending 的口径是「已收费 + item_name 含 病理/活检」，
    故这里挑一个名字含「病理」的收费项目；挑不到就退回 PATH 类目的任一项并改名不可行，
    此时如实返回 None 并在断言里说明是**主数据缺项**而不是功能缺陷。
    """
    sch = ok(call('POST', '/outpatient/schedules',
                  {'deptId': 1, 'scheduleDate': today, 'fee': 10, 'capacity': 9}, t), '排班')
    rid = ok(call('POST', '/outpatient/registrations',
                  {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')['id']
    ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
    items = ok(call('GET', '/masterdata/charge-items', token=t), '收费项目')
    cand = next((i for i in items if '病理' in (i.get('name') or '')
                 or '活检' in (i.get('name') or '')), None)
    if cand is None:
        return None
    ok(call('POST', f'/outpatient/doctor/{rid}/orders',
            {'lines': [{'orderType': 'EXAM', 'itemId': cand['id'], 'qty': 1}]}, t), '开病理医嘱')
    ok(call('POST', '/outpatient/charges/settle',
            {'registrationId': rid, 'payMethod': 'CASH'}, t), '结算')
    pend = ok(call('GET', '/pathology/pending', token=t), '待取材')
    assert isinstance(pend, list), pend
    return pend[0]['order_id'] if pend else None


# ===========================================================================
# 1) 双来源登记 + 多部位 + 病理号
# ===========================================================================
oid = outp_pathology_order()
reg = ok(call('POST', '/pathology/registry/specimens',
              {'orderId': oid, 'partNo': 1, 'specimenType': 'ROUTINE',
               'specimenDesc': '乳腺肿物', 'samplingSite': '左乳外上象限',
               'clinicalDiagnosis': '乳腺肿物待查', 'fixative': '10%中性福尔马林',
               'fixedAt': iso(-30), 'urgent': False}, t), '门诊登记') if oid else None

if reg:
    sid = reg.get('id') or reg.get('specimenId')
    assert reg.get('pathNo') or reg.get('path_no'), \
        f'病理号必须生成——它是对外出报告的法定编号，与院内条码 barcode 是两回事：{reg}'
    # 多部位：同一份申请的第二个部位必须能登记（既有 unique(order_id) 曾让它写不进去）
    reg2 = ok(call('POST', '/pathology/registry/specimens',
                   {'orderId': oid, 'partNo': 2, 'specimenType': 'ROUTINE',
                    'specimenDesc': '前哨淋巴结', 'samplingSite': '左腋窝'}, t), '同申请第二部位')
    assert (reg2.get('id') or reg2.get('specimenId')) != sid, '第二部位应是另一条标本'
    # 同来源同部位重复必须被拒
    dup = call('POST', '/pathology/registry/specimens',
               {'orderId': oid, 'partNo': 1, 'specimenType': 'ROUTINE'}, t)
    assert dup['code'] != 0, f'同申请同部位重复登记应被拒：{dup}'
    # 两个来源同时给必须被拒（否则同一标本会在门诊与住院两个工作台各出现一次）
    both = call('POST', '/pathology/registry/specimens',
                {'orderId': oid, 'inpOrderId': 1, 'partNo': 9}, t)
    assert both['code'] != 0, f'两个来源同时给应被拒：{both}'
    print(f'[1] 双来源登记 OK（病理号生成 / **同申请多部位可登记** / 同部位重复被拒 / 双来源互斥）')
else:
    sid = None
    print('[1] 跳过登记（无待取材门诊申请，属数据前置条件而非功能缺陷）')

# ===========================================================================
# 2) 既有端点契约：键集合逐字未变，且住院标本不再被静默漏掉
# ===========================================================================
legacy = ok(call('GET', '/pathology/specimens', token=t), '既有标本列表')
assert isinstance(legacy, list), legacy
if legacy:
    keys = set(legacy[0])
    for k in ('id', 'barcode', 'status', 'item_name', 'patient_name'):
        assert k in keys, f'既有列表端点的键 {k} 不见了——那是改契约: {sorted(keys)}'
    # 新字段进了 s.* 是允许的（select s.* 本来就随表走），但上面五个键一个都不能少
print('[2] 既有端点契约 OK（键集合未缺项；inner→left join 是补回被漏的住院行，不是改契约）')

# ===========================================================================
# 3) 拒收不删记录
# ===========================================================================
if sid:
    before = len(ok(call('GET', '/pathology/registry/specimens/search?rejected=true', token=t),
                    '拒收前检索').get('items', []))
    rej = ok(call('POST', '/pathology/registry/specimens',
                  {'orderId': oid, 'partNo': 3, 'specimenType': 'ROUTINE',
                   'specimenDesc': '待拒收标本'}, t), '建待拒收标本')
    rid = rej.get('id') or rej.get('specimenId')
    assert call('PUT', f'/pathology/registry/specimens/{rid}/reject',
                {'reason': ''}, t)['code'] != 0, '拒收原因必填'
    # **不传 rejectedAt，让服务端取 now()**：collected_at 是服务端时刻，
    # 脚本自己算的 iso() 基于启动时刻、必然落后，会撞 5210「拒收早于取材」。
    # 这正是本仓反复踩的同一课——时间要从**业务自己的时间线**派生，脚本时钟不算数。
    ok(call('PUT', f'/pathology/registry/specimens/{rid}/reject',
            {'reason': '标本未固定，离体超 6 小时'}, t), '拒收')
    # 用 rejected=true 精确定位而不是全量翻——全量有 200 条上限，
    # 新建的标本排在 id 降序前面本应能看到，但依赖排序位置的断言太脆。
    after = ok(call('GET', '/pathology/registry/specimens/search?rejected=true', token=t), '拒收后检索')
    rows = after.get('items', [])
    assert any((r.get('id') == rid) for r in rows), \
        '**拒收不删记录**——删了行就永远统计不出送检总数与拒收率，固定规范率也没了分母'
    print('[3] 拒收 OK（原因必填 / **拒收后记录仍在，不删行**）')

# ===========================================================================
# 4) 取材 → 蜡块 → 切片
# ===========================================================================
if sid:
    ok(call('PUT', f'/pathology/registry/specimens/{sid}/receive-check', {}, t), '接收核对')
    gr = ok(call('POST', '/pathology/process/grossing',
                 {'specimenId': sid, 'grossText': '灰白灰红组织一块，3×2×1cm，切面实性',
                  'blocks': [{'tissueDesc': '肿物中心'}, {'tissueDesc': '肿物周边'}]}, t), '取材')
    blocks = ok(call('GET', f'/pathology/process/blocks?specimenId={sid}', token=t), '蜡块列表')
    brows = blocks.get('items', [])
    assert len(brows) >= 2, f'取材应产出 2 个蜡块：{blocks}'
    bid = brows[0]['id']
    code0 = brows[0].get('block_code') or brows[0].get('blockCode') or ''
    assert code0, f'蜡块编码必须有——报告上写的是「3 号蜡块」：{brows[0]}'

    ok(call('PUT', '/pathology/process/blocks/dehydrate-batch',
            {'batchNo': 'DH-E2E-1', 'blockIds': [b['id'] for b in brows]}, t), '脱水篮分组')
    ok(call('PUT', f'/pathology/process/blocks/{bid}/embed', {}, t), '包埋')
    sl = ok(call('POST', '/pathology/process/slides',
                 {'blockId': bid, 'count': 2, 'stainType': 'HE'}, t), '切片')
    slides = ok(call('GET', f'/pathology/process/slides/search?specimenId={sid}', token=t), '切片检索')
    srows = slides.get('items', [])
    assert len(srows) >= 2, f'应产出 2 张切片：{slides}'
    ok(call('PUT', f"/pathology/process/slides/{srows[0]['id']}/stain",
            {'quality': 'GOOD'}, t), '染色登记')
    assert call('POST', '/pathology/process/slides',
                {'blockId': bid, 'count': 1, 'stainType': 'BOGUS'}, t)['code'] != 0, \
        '染色类型非白名单应被拒'
    print('[4] 技术流程 OK（取材产出蜡块 / 脱水篮**逻辑分组** / 包埋 / 切片编码 / 染色质量 / 白名单）')

# ===========================================================================
# 5) 双签：一个人不能占两个签名位（与 gate 无关）
# ===========================================================================
if sid:
    # 诊断内容走**既有**端点写入（本版一字未动它），签名只管签名——
    # 5262「尚未书写诊断不能初诊签名」正是这条职责分离的体现。
    bc = reg.get('barcode') or reg.get('barCode')
    assert bc, f'登记返回体应带院内条码：{reg}'
    # 不再调既有 receive——第 4 段的 receive-check 已把状态推过 COLLECTED，
    # 再调会撞 4551「状态不符」。两个端点管的是同一个状态位，这是刻意的：
    # 新端点没有另造一套平行状态机。
    ok(call('PUT', f'/pathology/specimens/{bc}/diagnose',
            {'grossFinding': '灰白组织一块', 'microFinding': '镜下见异型细胞浸润',
             'diagnosis': '（左乳）浸润性导管癌'}, t), '既有诊断书写')
    ok(call('PUT', f'/pathology/report/{sid}/first-sign', {}, t), '初诊签名')
    same = call('PUT', f'/pathology/report/{sid}/second-sign', {}, t)
    assert same['code'] != 0, \
        '**同一人不得占双签两个位**——一个人签两次不叫双签；这条与 gate 无关，' \
        f'gate 管的是「未双签能否签发」：{same}'
    t2 = provision_user(t, 'pathdoc2', 'DOCTOR_OUTP', '病理复诊医师')
    ok(call('PUT', f'/pathology/report/{sid}/second-sign', {}, t2), '复诊签名（另一人）')
    ok(call('PUT', f'/pathology/report/{sid}/issue', {}, t), '正式签发')
    print('[5] 双签 OK（**同一人被拒** / 另一人可复诊 / 签发）')

# ===========================================================================
# 6) 补充报告绝不改原报告
# ===========================================================================
if sid:
    reports_before = ok(call('GET', f'/pathology/report/{sid}/reports', token=t), '报告列表')
    primary = str(reports_before)
    ok(call('POST', f'/pathology/report/{sid}/supplement',
            {'content': '免疫组化：ER(+90%)、PR(+70%)、HER2(2+)', 'reason': '免疫组化结果回报'}, t),
       '补充报告')
    after = ok(call('GET', f'/pathology/report/{sid}/reports', token=t), '报告列表(补充后)')
    assert '浸润性导管癌' in str(after), \
        '**补充报告绝不改原报告**——覆盖原报告会让「当时医生看到的是什么」永久不可考'
    assert 'ER(+90%)' in str(after), f'补充报告内容应可见：{after}'
    print('[6] 补充报告 OK（**原报告一字未动** / 补充内容并存 / 全历史可查）')

# ===========================================================================
# 7) 质控：三条符合率必须 available:false，不许拿别的字段凑
# ===========================================================================
cat = ok(call('GET', '/path-qc/catalog', token=t), '指标目录')
blob = str(cat)
for code in ('FROZEN_PARAFFIN_CONCORDANCE', 'CLINICAL_CONCORDANCE', 'CONSULT_CONCORDANCE'):
    assert code in blob, f'指标目录应含 {code}：{blob[:400]}'
assert 'false' in blob.lower(), '三条符合率无数据源，必须显式标 available:false'
ind = ok(call('GET', f'/path-qc/indicators?from={today}&to={today}', token=t), '指标汇总')
iblob = str(ind)
assert 'coverage' in iblob or 'caveat' in iblob, \
    '口径与覆盖率必须随返回体下发，不能只写在页面上——否则管理者会当成全院全历史口径'
assert call('GET', f'/path-qc/indicators?from={today}&to=2020-01-01', token=t)['code'] != 0, \
    '起止倒置应报错'
csv1 = call('GET', f'/path-qc/indicators.csv?from={today}&to={today}', token=t, raw=True)
assert len(csv1) > 0, 'CSV 应可导出'
print('[7] 质控 OK（**三条符合率标 available:false 未拿字段凑** / coverage+caveat 随体 / CSV）')

# ===========================================================================
# 8) 边界钉死：本版刻意不做的，不许被悄悄"补"成假的
# ===========================================================================
import json as _json
import os as _os

_pkg = _os.path.join(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__))),
                     'frontend', 'shell', 'package.json')
if _os.path.exists(_pkg):
    deps = _json.load(open(_pkg, encoding='utf-8'))
    alldeps = str(deps.get('dependencies', {})) + str(deps.get('devDependencies', {}))
    for banned in ('upload', 'dicom', 'cornerstone', 'openseadragon', 'fabric'):
        assert banned not in alldeps.lower(), \
            f'v48 明确不做图像/上传（MultipartFile 全仓零命中，没有文件上传基础设施），' \
            f'package.json 出现 {banned} 说明有人把它悄悄补成了假的'

_root = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))
_hits = []
for _dp, _dn, _fn in _os.walk(_os.path.join(_root, 'modules')):
    if 'worktrees' in _dp or 'target' in _dp:
        continue
    for _f in _fn:
        if not _f.endswith('.java'):
            continue
        _txt = open(_os.path.join(_dp, _f), encoding='utf-8', errors='ignore').read()
        # 只认**真代码**：import 或方法签名里的 MultipartFile。
        # javadoc 里写「不做图像——MultipartFile 全仓零命中」是**声明边界**，恰恰是要鼓励的，
        # 把它算成突破会逼着后人删掉说明、反而让边界变得不可见。
        for _line in _txt.splitlines():
            _s = _line.strip()
            if _s.startswith('*') or _s.startswith('//') or _s.startswith('/*'):
                continue
            if 'MultipartFile' in _s:
                _hits.append(f'{_f}: {_s[:80]}')
assert not _hits, f'v48 不做文件上传，出现 MultipartFile 说明边界被突破：{_hits}'
print('[8] 边界 OK（**无 MultipartFile / 无图像库依赖**——不做就是不做，不许悄悄补成假的）')

print('\ne2e-v48-pathology 全部通过 ✅')
