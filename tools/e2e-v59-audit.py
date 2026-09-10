# -*- coding: utf-8 -*-
"""v59 核账 E2E：病理四条契约（2530 复核打回 / 2558 既往对比 / 2563 一致性 / 2576 签发口径 + 表头字典）在**真实服务**上逐条对账。

本套的由来与 e2e-v57-audit.py、e2e-v58-audit.py 同：v59 四条车道（A 2530 V166、B 2558、C 2563+2576-③、D 2576 表头）
各自的 JUnit 都绿了，但 JUnit 直接调控制器方法、跑在一个回滚事务里——**线格式、序列化、JWT、跨端点状态机**
一个都没走过。本套用 HTTP 把四条契约再钉一遍，断言全是**修复前主控实测过的反向事实**：
  [1] 2530 ——修复前字段行是一次性快照（唯一约束 (specimen_id, seq)），根本没有 PUT /grossing/{id}/fields，
             读端点没有 fieldsRevisionSeq / textRevisionSeq / fieldsCurrent 三键；已有大体所见后任何取材再传描述
             一律 5222（已诊断标本的补取材没有任何入口记录描述）；模板码只拼进 path_process.remark 不落标本；
             旧端点 POST /pathology/specimens 描述可空、旧页写死「手术切除标本」落库；
  [2] 2558 ——修复前 /prior 行没有 status / supplements 两键（补充报告只给份数）；/history 默认**含已拒收标本**，
             同名他人的 specimen_count 把拒收行算进份数；
  [3] 2563 ——修复前切片挂接只校验医嘱存在 / 同标本 / 同蜡块 / ORDERED，HE 片、CK20 片都能挂到「免疫组化 CK7」
             并把进度推到 STAINED / DONE；染色登记 coalesce(?, stain_item) 可把已挂接切片的项目改成任何项目；
             清单不回挂接切片的实际染色；
  [4] 2576 ——修复前 diagnose 同一事务把 outp_order 置 EXECUTED（医生站在报告尚未签发时就显示「已执行」）；
             v59 地基上前端 ZH 漏了 stat_day / issue_day / stained_count / progress_name 四键——页面表头英文、CSV 表头中文；
  [5] 边界 ——V166 零回填：剥注释后无 `^\\s*update <表> set` / `^\\s*insert into` 形态（对照组 V22:40 / V161 抓得到）。

助手逐字抄自 e2e-v58-audit.py（已验证能跑通）；调用形态照抄 V59GrossReviseTest / V59PriorHistoryTest /
V59TechConsistencyTest / V59QcLabelsTest 钉住的 HTTP 契约，不凭印象猜契约——本仓已因猜契约返工多次
（驼峰/蛇形、rows/items/groups、排班是 POST 建、开药前须先 start、登记只认 CHARGED、签发才置 EXECUTED）。
时间一律从服务端响应或业务时间线派生，**不写墙钟字面量**。

**HTTP 与 JUnit 的一处口径差，如实写在这里**：取材端点对「已有大体所见 + append=false + 带描述」的 5222 守卫排在
「已有蜡块 + append=false」的 5223 守卫之后，而取材端点每次必产蜡块——走 HTTP 造出来的标本只要有大体所见就有蜡块，
append=false 一律先撞 5223；5222 那条只有 SQL 置入 gross_finding 的历史标本（V59GrossReviseTest (e) legacy 夹具）
才走得到。本套钉 HTTP 上真实可达的反向事实：append=false 带描述被拒（5223）且零写入、append=true 超长 5222 零写入。
"""
import datetime
import os
import re
import time
import urllib.parse as _u

from e2elib import call, login, new_patient, ok, today_bj  # noqa: E402

t = login()
today = today_bj()


def api(m, p, b=None, **kw):
    return call(m, p, b, t, **kw)


def q(s):
    return _u.quote(s, safe='')


def uniq(pre):
    return pre + str(int(time.time() * 1000) % 100000000)


def iso(mins):
    return (datetime.datetime.now(datetime.timezone.utc)
            + datetime.timedelta(minutes=mins)).isoformat().replace('+00:00', 'Z')


def visited(pid, dept_id=1):
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': dept_id, 'scheduleDate': today.isoformat(), 'fee': '0.00', 'capacity': 50}), '排班')
    reg = ok(api('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}), '挂号')
    ok(api('POST', f"/outpatient/doctor/{reg['id']}/start", {}), '接诊')
    return reg['id']


_used_orders = set()


def pathology_order(pid, name):
    """自建一条已收费的门诊病理申请，返回 (order_id, registration_id)。
    在待取材队列里**按 patient_name 认领自己的**（既有 GET /pathology/pending 只回
    order_id/item_name/group_no/patient_name 四键，没有 patient_id；患者名带 uniq 后缀），
    不拿 pend[0]——脏库里 pend[0] 可能是别人的。本套 [2] 会给同名他人再开单，故照抄 demo-pathology.py：
    排除本次已认领过的 order_id、取最新的一条。registration_id 供 [4] 读医生站工作区里医嘱的 status。"""
    rid = visited(pid)
    items = ok(api('GET', '/masterdata/charge-items'), '收费项目')
    cand = next((i for i in items if '病理' in (i.get('name') or '') or '活检' in (i.get('name') or '')), None)
    assert cand, '主数据无病理收费项目（主数据缺项，非功能缺陷）'
    ok(api('POST', f'/outpatient/doctor/{rid}/orders',
           {'lines': [{'orderType': 'EXAM', 'itemId': cand['id'], 'qty': 1}]}), '开病理医嘱')
    ok(api('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}), '结算')
    pend = ok(api('GET', '/pathology/pending'), '待取材')
    mine = [p['order_id'] for p in pend
            if p.get('order_id') and p.get('patient_name') == name and p['order_id'] not in _used_orders]
    assert mine, f'待取材队列里没有患者 {name} 刚开的病理医嘱：{pend[:3]}'
    oid = max(mine)
    _used_orders.add(oid)
    return oid, rid


def register(oid, part, desc, receive=True):
    """登记（默认再接收核对）→ (specimenId, barcode)。
    **同一申请的各部位须在任一部位正式签发（PUT /pathology/report/{id}/issue）之前全部登记完**：
    v59 起签发才把 outp_order 置 EXECUTED，而登记只认 CHARGED（5201）。本套各段都是先登记完全部部位再 diagnose / issue。"""
    reg = ok(api('POST', '/pathology/registry/specimens',
                 {'orderId': oid, 'partNo': part, 'specimenType': 'ROUTINE', 'specimenDesc': desc,
                  'samplingSite': '左乳', 'clinicalDiagnosis': '待查', 'fixative': '福尔马林',
                  'fixedAt': iso(-30), 'urgent': False}), f'登记部位 {part}')
    sid = reg.get('id') or reg.get('specimenId')
    bc = reg.get('barcode') or reg.get('barCode')
    assert sid and bc, f'登记返回体应带 id 与条码：{reg}'
    if receive:
        ok(api('PUT', f'/pathology/registry/specimens/{sid}/receive-check', {}), '接收核对')
    return sid, bc


def grossing(sid, text):
    """取材写大体所见（GROSSING 节点，落 path_specimen.gross_finding），返回首个蜡块 id"""
    ok(api('POST', '/pathology/process/grossing',
           {'specimenId': sid, 'grossText': text, 'blocks': [{'tissueDesc': '肿物中心'}]}), '取材')
    blocks = ok(api('GET', f'/pathology/process/blocks?specimenId={sid}'), '蜡块列表').get('items') or []
    assert blocks, '取材应产出蜡块'
    return blocks[0]['id']


def grossing_view(sid):
    v = ok(api('GET', f'/pathology/process/grossing/{sid}'), '取材视图')
    for k in ('fieldsAvailable', 'fields', 'revisions', 'grossFinding', 'fieldsRevisionSeq', 'textRevisionSeq', 'fieldsCurrent'):
        assert k in v, f'取材视图缺键 {k}（v59 三个版号键修复前根本没有）：{sorted(v)}'
    return v


def labels(fields):
    return [f.get('label') for f in fields]


def tech_row(sid, tid):
    rows = ok(api('GET', f'/pathology/report/tech-orders?specimenId={sid}'), '技术医嘱清单').get('items') or []
    row = next((r for r in rows if r.get('id') == tid), None)
    assert row, f'清单里找不到技术医嘱 {tid}：{rows}'
    return row


def wide_row(tid, barcode):
    wide = ok(api('GET', f"/pathology/report/tech-orders?status=ALL&keyword={q(barcode)}&limit=200"),
              '全院清单').get('items') or []
    wrow = next((r for r in wide if r.get('id') == tid), None)
    assert wrow, f'全院清单 status=ALL 按条码应能搜到医嘱 {tid}'
    return wrow


def qc_tech_row(tid):
    """质控穿透 WORKLOAD_TECH 里这条医嘱的行；日期窗两端各留一天余量（照抄 V59TechConsistencyTest.qcTechRow）"""
    f, to = (today - datetime.timedelta(days=1)).isoformat(), (today + datetime.timedelta(days=1)).isoformat()
    items = ok(api('GET', f'/path-qc/detail?indicator=WORKLOAD_TECH&from={f}&to={to}'), '质控穿透 WORKLOAD_TECH').get('items') or []
    row = next((x for x in items if x.get('tech_order_id') == tid), None)
    assert row, f'质控穿透里找不到技术医嘱 {tid}'
    return row


def assert_progress(sid, tid, progress, name, slides, stained):
    """标本清单 + 全院清单（status=ALL，按条码 keyword）两处同口径——照抄 e2e-v58-audit.assert_progress"""
    row = tech_row(sid, tid)
    assert row.get('progress') == progress and row.get('progress_name') == name, (
        f'标本清单 progress 应为 {progress}/{name}：{row}')
    assert row.get('slide_count') == slides and row.get('stained_count') == stained, (
        f'标本清单 slide_count/stained_count 应为 {slides}/{stained}：{row}')
    wrow = wide_row(tid, row['barcode'])
    assert (wrow.get('progress'), wrow.get('slide_count'), wrow.get('stained_count')) == (progress, slides, stained), (
        f'全院清单与标本清单须同口径：{wrow}')
    return row


def assert_attached_stain(sid, tid, expected):
    """attached_stain 三处同口径（标本清单 / 全院清单 / 质控穿透）；expected 为 None 即「列在、值为 null」，
    为 set 即只比「、」拆开后的条目集合（string_agg 按 stain_type, stain_item 排序，null 项目排在最后，不钉顺序）"""
    row = tech_row(sid, tid)
    for where, r in (('标本清单', row), ('全院清单', wide_row(tid, row['barcode'])), ('质控穿透', qc_tech_row(tid))):
        assert 'attached_stain' in r, f'{where} 行必须带 attached_stain 列（修复前三处都不回挂接切片的实际染色）：{sorted(r)}'
        got = r.get('attached_stain')
        if isinstance(expected, set):
            assert got and set(got.split('、')) == expected, f'{where} attached_stain 条目应为 {expected!r}：{got!r}'
        else:
            assert got == expected, f'{where} attached_stain 应为 {expected!r}：{got!r}'


def tech_order(sid, block_id, tech_type, item, reason=None):
    body = {'specimenId': sid, 'blockId': block_id, 'techType': tech_type, 'techItem': item}
    if reason:
        body['reason'] = reason
    c = ok(api('POST', '/pathology/report/tech-orders', body), f'下 {tech_type} 医嘱 {item}')
    assert c.get('progress') == 'PENDING_SECTION', f'刚下达的医嘱返回体应带派生进度 PENDING_SECTION：{c}'
    return c['id']


def slides_of(sid, tid=None):
    items = ok(api('GET', f'/pathology/process/slides/search?specimenId={sid}'), '切片检索').get('items') or []
    return [s for s in items if tid is None or s.get('tech_order_id') == tid]


def slide_by_id(sid, slide_id):
    row = next((s for s in slides_of(sid) if s.get('id') == slide_id), None)
    assert row, f'切片检索里找不到 {slide_id}'
    return row


# ===========================================================================
# 1) 2530 复核打回：取材带模板 → 修订字段（新版本）→ 诊断覆盖（fieldsCurrent=false）→ 诊断后修订 5221；
#    补取材 append=true 带描述出新版本、append=false 被拒零写入；旧端点描述必填 4554
# ===========================================================================
n1 = '2530修订' + uniq('')
p1 = new_patient(t, n1, sex='F')['id']
o1, _ = pathology_order(p1, n1)
s1, b1 = register(o1, 1, 'v59 取材修订 + 诊断覆盖')
s2, b2 = register(o1, 2, 'v59 补取材追加')
G1 = {'大小': '3×2×1cm', '颜色': '灰白'}
FREE1 = '切面实性 ' + uniq('F')
EXPECTED1 = '大小：3×2×1cm；颜色：灰白。' + FREE1

# (a) 取材带模板（小写传入 → 大写规范化落库）
gr = ok(api('POST', '/pathology/process/grossing',
            {'specimenId': s1, 'templateCode': 'gi_biopsy', 'gross': G1, 'grossText': FREE1,
             'blocks': [{'tissueDesc': '肿物中心'}]}), '取材（带模板 + 两字段）')
assert gr.get('grossFinding') == EXPECTED1 and gr.get('grossFieldCount') == 2, f'拼装文本 / 字段数：{gr}'
assert gr.get('grossRevisionSeq') == 1, f'首写是第 1 版（v59 返回体新键 grossRevisionSeq）：{gr}'
v = grossing_view(s1)
assert v['fieldsAvailable'] is True and labels(v['fields']) == list(G1), f'字段按入参序：{v["fields"]}'
assert v['fieldsRevisionSeq'] == 1 and v['textRevisionSeq'] == 1 and v['fieldsCurrent'] is True, (
    f'首写：fieldsRevisionSeq=1、textRevisionSeq=1、fieldsCurrent=true（修复前没有这三个键）：{v}')
revs = v['revisions']
assert len(revs) == 1 and revs[0].get('templateCode') == 'GI_BIOPSY', (
    f'**模板码落修订行且大写规范化**（修复前只拼进 path_process.remark，path_gross_revision 没有 template_code 列）：{revs}')
assert revs[0].get('sourceName') == '取材首写' and revs[0].get('source') == 'GROSSING' and revs[0].get('oldText') is None, f'{revs[0]}'

# (b) 取材修订：新版本 GROSSING_EDIT，字段行落第 2 版，读端点回新版且 fieldsCurrent=true
G2 = {'大小': ' 3.5×2×1cm ', '颜色': '灰白', '质地': '质硬'}
FREE2 = '切面实性，局灶出血 ' + uniq('E')
EXPECTED2 = '大小：3.5×2×1cm；颜色：灰白；质地：质硬。' + FREE2
rv = ok(api('PUT', f'/pathology/process/grossing/{s1}/fields',
            {'templateCode': 'generic', 'gross': G2, 'grossText': FREE2}), '取材修订（修复前根本没有这个端点）')
assert set(rv) == {'revisionSeq', 'grossFieldCount', 'grossFinding'}, f'修订返回体三键：{rv}'
assert rv['revisionSeq'] == 2 and rv['grossFieldCount'] == 3 and rv['grossFinding'] == EXPECTED2, f'{rv}'
v = grossing_view(s1)
assert v['grossFinding'] == EXPECTED2, f'gross_finding 更新为新文本：{v["grossFinding"]!r}'
assert labels(v['fields']) == list(G2), f'**读端点回最大 revision_seq 那一版**（修复前字段永远是第 1 版）：{v["fields"]}'
assert [f.get('value') for f in v['fields']] == ['3.5×2×1cm', '灰白', '质硬'], f'值已 trim：{v["fields"]}'
assert [f.get('seq') for f in v['fields']] == [1, 2, 3], f'seq 在本版内从 1 起：{v["fields"]}'
assert set(v['fields'][0]) == {'seq', 'label', 'value', 'createdAt', 'operatorId', 'operatorName'}, f'字段行的键不变（v58 契约）：{v["fields"][0]}'
assert v['fieldsRevisionSeq'] == 2 and v['textRevisionSeq'] == 2 and v['fieldsCurrent'] is True, (
    f'**修订后 fieldsRevisionSeq=2、fieldsCurrent=true**：{v}')
revs = v['revisions']
assert [r.get('source') for r in revs] == ['GROSSING', 'GROSSING_EDIT'], f'来源顺序：{revs}'
assert [r.get('sourceName') for r in revs] == ['取材首写', '取材修订'], f'**revisions[1].sourceName=取材修订**：{revs}'
assert revs[1].get('seq') == 2 and revs[1].get('oldText') == EXPECTED1 and revs[1].get('newText') == EXPECTED2, (
    f'第 2 条 old_text 是修订前原文、new_text 是新文本：{revs[1]}')
assert revs[1].get('templateCode') == 'GENERIC', f'修订所用模板码落库（大写规范化）：{revs[1]}'
assert revs[1].get('changedBy') and revs[1].get('changedByName'), f'修订带人：{revs[1]}'
# 被拒路径全部零写入：相同文本 / 空内容 / 空 body / 未知模板 / 字段名重复 / 查无此标本
assert api('PUT', f'/pathology/process/grossing/{s1}/fields', {'gross': G2, 'grossText': FREE2})['code'] == 5222, '与当前相同 → 5222（没有变化就没有版本）'
assert api('PUT', f'/pathology/process/grossing/{s1}/fields', {'grossText': '   '})['code'] == 5222, '空内容 5222'
assert api('PUT', f'/pathology/process/grossing/{s1}/fields', {})['code'] == 5222, '空 body 5222'
assert api('PUT', f'/pathology/process/grossing/{s1}/fields', {'templateCode': 'NO_SUCH_TPL', 'gross': G1})['code'] == 5225, '未知模板 5225'
assert api('PUT', f'/pathology/process/grossing/{s1}/fields', {'gross': {'大小': 'a', ' 大小 ': 'b'}})['code'] == 5222, '字段名 trim 后重复 5222'
assert api('PUT', '/pathology/process/grossing/999999999/fields', {'gross': G1})['code'] == 5220, '查无此标本 5220'
v = grossing_view(s1)
assert v['grossFinding'] == EXPECTED2 and len(v['revisions']) == 2 and labels(v['fields']) == list(G2), '被拒路径不改文本、不出版本、不落字段行'

# (c) 诊断覆盖：第 3 版 DIAGNOSE 只改文本，字段仍第 2 版，读端点如实标 fieldsCurrent=false
G3 = '诊断时改写的大体所见 ' + uniq('D')
dg = ok(api('PUT', f'/pathology/specimens/{b1}/diagnose',
            {'grossFinding': G3, 'microFinding': '镜下 v59', 'diagnosis': '浸润性导管癌 v59'}), 'diagnose 覆盖大体')
assert dg.get('grossRevised') is True and dg.get('grossKept') is False, f'{dg}'
v = grossing_view(s1)
assert v['grossFinding'] == G3
assert v['fieldsRevisionSeq'] == 2 and v['textRevisionSeq'] == 3 and v['fieldsCurrent'] is False, (
    f'**诊断覆盖后：字段仍第 2 版、文本到第 3 版、fieldsCurrent=false**（修复前两处并排自相矛盾而无任何标记）：{v}')
assert labels(v['fields']) == list(G2), 'fields 仍是第 2 版那套'
assert [r.get('sourceName') for r in v['revisions']] == ['取材首写', '取材修订', '诊断修订'], f'{v["revisions"]}'
assert v['revisions'][2].get('templateCode') is None and v['revisions'][2].get('oldText') == EXPECTED2, f'诊断覆盖没有模板、old 是覆盖前原文：{v["revisions"][2]}'

# (d) 诊断后修订 → 5221 零写入
r = api('PUT', f'/pathology/process/grossing/{s1}/fields', {'templateCode': 'GENERIC', 'gross': {'大小': '9×9×9cm'}, 'grossText': '诊断后还想改'})
assert r['code'] == 5221, f'**诊断后取材端不再修订大体所见 → 5221**：{r}'
v = grossing_view(s1)
assert v['grossFinding'] == G3 and len(v['revisions']) == 3 and labels(v['fields']) == list(G2), '5221 路径零写入'
# 尚无大体所见的标本修订 → 5221（只修订不首写）
s0, _ = register(o1, 3, 'v59 尚无大体所见')
assert api('PUT', f'/pathology/process/grossing/{s0}/fields', {'gross': G1})['code'] == 5221, '尚无大体所见 → 5221'
v0 = ok(api('GET', f'/pathology/process/grossing/{s0}'), '取材视图 s0')
assert v0['grossFinding'] is None and v0['revisions'] == [] and v0['fields'] == [] and v0['blockCount'] == 0, f'零写入：{v0}'

# (e) 补取材记描述：已有大体所见 + append=true 带描述 → 新版本，「。补取材：」拼接、字段在新版；append=false 被拒零写入
gr2 = ok(api('POST', '/pathology/process/grossing',
             {'specimenId': s2, 'gross': G1, 'grossText': FREE1, 'blocks': [{'tissueDesc': '肿物周边'}]}), '取材（第二标本）')
assert gr2.get('grossFinding') == EXPECTED1 and gr2.get('grossRevisionSeq') == 1
GA = {'块数': '2', '最大径': '0.5cm'}
FREEA = '补取材组织 ' + uniq('A')
EXPECTED_A = EXPECTED1 + '。补取材：' + '块数：2；最大径：0.5cm。' + FREEA
ga = ok(api('POST', '/pathology/process/grossing',
            {'specimenId': s2, 'templateCode': 'GI_BIOPSY', 'gross': GA, 'grossText': FREEA, 'append': True,
             'blocks': [{'tissueDesc': '补取材'}]}), '补取材（append=true 带描述；修复前一律 5222）')
assert ga.get('grossFinding') == EXPECTED_A, f'**返回体是「。补取材：」拼接后的全文**：{ga.get("grossFinding")!r}'
assert '。补取材：' in ga['grossFinding']
assert ga.get('grossFindingWritten') is True and ga.get('grossFieldCount') == 2 and ga.get('grossRevisionSeq') == 2, f'{ga}'
assert ga.get('totalBlockCount') == 2, f'蜡块照常追加：{ga}'
v = grossing_view(s2)
assert v['grossFinding'] == EXPECTED_A
assert v['fieldsRevisionSeq'] == 2 and v['textRevisionSeq'] == 2 and v['fieldsCurrent'] is True, f'{v}'
assert labels(v['fields']) == list(GA), f'本次字段落在第 2 版、读端点回第 2 版：{v["fields"]}'
revs = v['revisions']
assert len(revs) == 2 and revs[1].get('source') == 'GROSSING' and revs[1].get('sourceName') == '补取材追加', (
    f'补取材仍是取材端点写的：source 不变、sourceName=补取材追加（GROSSING 且带原文的是追加，不叫「首写」）：{revs}')
assert revs[1].get('oldText') == EXPECTED1 and revs[1].get('newText') == EXPECTED_A and revs[1].get('templateCode') == 'GI_BIOPSY', f'{revs[1]}'
# append=false 带描述：被拒且零写入（HTTP 造的标本有蜡块，先撞 5223；5222 那条只有 SQL 置入文本的历史标本走得到，见文件头）
before = (v['grossFinding'], len(v['revisions']), labels(v['fields']), v['blockCount'])
r = api('POST', '/pathology/process/grossing',
        {'specimenId': s2, 'gross': GA, 'grossText': FREEA, 'append': False, 'blocks': [{'tissueDesc': 'x'}]})
assert r['code'] == 5223 and 'append=true' in (r.get('message') or ''), f'已有蜡块 + append=false 被拒并指路 append=true：{r}'
# append=true 但追加后超长 → 5222，在任何写入之前
r = api('POST', '/pathology/process/grossing',
        {'specimenId': s2, 'grossText': 'x' * 1995, 'append': True, 'blocks': [{'tissueDesc': 'y'}]})
assert r['code'] == 5222 and '超过' in (r.get('message') or ''), f'追加后超长 → 5222：{r}'
v = grossing_view(s2)
assert (v['grossFinding'], len(v['revisions']), labels(v['fields']), v['blockCount']) == before, f'两条被拒路径零写入：{v}'
# append=true 不带描述：蜡块照加、不出版本（没有新描述就没有新版本）
ok(api('POST', '/pathology/process/grossing', {'specimenId': s2, 'append': True, 'blocks': [{'tissueDesc': '再补一块'}]}), '补取材（不带描述）')
v = grossing_view(s2)
assert v['blockCount'] == 3 and len(v['revisions']) == 2 and v['grossFinding'] == EXPECTED_A, f'不带描述的补取材不出版本：{v}'

# (f) 旧端点 POST /pathology/specimens：描述缺失 / 空白 → 4554 且零行（修复前描述可空、旧页写死「手术切除标本」落库）
mine_before = [x for x in ok(api('GET', f'/pathology/registry/specimens/search?patientName={q(n1)}'), '检索').get('items') or []]
assert len(mine_before) == 3, f'夹具前提：本患者已有 s1/s2/s0 三条：{[x.get("id") for x in mine_before]}'
r = api('POST', f'/pathology/specimens?orderId={o1}', {})
assert r['code'] == 4554, f'**描述缺失 → 4554**（修复前直接落一行 specimen_desc 为空的标本）：{r}'
assert api('POST', f'/pathology/specimens?orderId={o1}&specimenDesc=%20%20', {})['code'] == 4554, '空白描述 4554'
mine_after = ok(api('GET', f'/pathology/registry/specimens/search?patientName={q(n1)}'), '检索').get('items') or []
assert {x.get('id') for x in mine_after} == {x.get('id') for x in mine_before}, '4554 路径不落任何标本行'
print('[1] 2530 OK（**模板码落修订行 GI_BIOPSY** / **PUT …/fields → 第 2 版 GROSSING_EDIT、fieldsRevisionSeq=2、fieldsCurrent=true、sourceName=取材修订** / '
      '被拒六路零写入 / **diagnose 覆盖 → fieldsCurrent=false、textRevisionSeq=3** / 诊断后修订 5221 / '
      '**append=true 带描述 → 「。补取材：」新版本 sourceName=补取材追加** / append=false 5223 与超长 5222 零写入 / 旧端点无描述 4554 零行）')

# ===========================================================================
# 2) 2558 既往对比：同一患者三条（签发 + 补充报告 / 核收 / 拒收）+ 同名他人；prior 带全文与补充正文、拒收不算既往，
#    history 默认排除拒收、includeRejected=true 才带，同名份数只算未拒收
# ===========================================================================
n2 = '2558既往' + uniq('')
p2 = new_patient(t, n2, sex='F')['id']
o2, _ = pathology_order(p2, n2)
# 三个部位先全部登记完再签发（签发置 EXECUTED 后登记只认 CHARGED）
sA, bA = register(o2, 1, 'v59 既往 A1（签发 + 补充）')
sB, bB = register(o2, 2, 'v59 既往 A2（当前）')
sC, bC = register(o2, 3, 'v59 既往 A3（拒收）', receive=False)
GROSS_A = '灰白组织一块 ' + uniq('G')
MICRO_A = '镜下见异型细胞 ' + uniq('M')
DX_A = '（左乳）浸润性导管癌 ' + uniq('X')
ok(api('PUT', f'/pathology/specimens/{bA}/diagnose', {'grossFinding': GROSS_A, 'microFinding': MICRO_A, 'diagnosis': DX_A}), '诊断 A1')
ok(api('PUT', f'/pathology/report/{sA}/first-sign', {}), '初诊签名 A1')
issued = ok(api('PUT', f'/pathology/report/{sA}/issue', {}), '签发 A1（gate=warn 缺复签放行）')
assert issued.get('reportIssuedAt'), f'签发响应应带 reportIssuedAt：{issued}'
SUP = '免疫组化：CK7(+)、CK20(-) ' + uniq('S')
sup = ok(api('POST', f'/pathology/report/{sA}/supplement', {'content': SUP, 'reason': '免疫组化回报'}), '补充报告 A1')
assert sup.get('seqNo') == 1, f'{sup}'
REJ = '标本未固定 ' + uniq('R')
ok(api('PUT', f'/pathology/registry/specimens/{sC}/reject', {'reason': REJ}), '拒收 A3（不传 rejectedAt，服务端取 now()）')
# 同名他人 C：一条在办 + 一条拒收（同名提醒的份数口径）
pC = new_patient(t, n2, sex='M')['id']
assert pC != p2, '同名他人须是另一个 empi 患者'
oC, _ = pathology_order(pC, n2)
sC1, _ = register(oC, 1, 'v59 同名他人 C1（在办）')
sC2, _ = register(oC, 2, 'v59 同名他人 C2（拒收）', receive=False)
ok(api('PUT', f'/pathology/registry/specimens/{sC2}/reject', {'reason': REJ}), '拒收 C2')

prior = ok(api('GET', f'/pathology/report/{sB}/prior'), '既往（从 A2 看）')
assert prior.get('patientResolved') is True and prior.get('patientId') == p2, f'{prior}'
ids = {x.get('id') for x in prior.get('items') or []}
assert sA in ids, f'已签发的 A1 是 A2 的既往：{ids}'
assert sC not in ids, '**已拒收的 A3 不算既往**'
assert sB not in ids and sC1 not in ids and sC2 not in ids, f'本标本自身与同名他人的标本不许混进来（患者同一性以 empi_patient.id 为准）：{ids}'
row = next(x for x in prior['items'] if x.get('id') == sA)
assert 'status' in row and 'supplements' in row, f'**既往行须带 status / supplements 两键**（修复前只有 supplement_count 份数）：{sorted(row)}'
assert row.get('status') == 'DIAGNOSED' and row.get('report_issued_at') == issued['reportIssuedAt'], f'{row}'
assert row.get('gross_finding') == GROSS_A and row.get('micro_finding') == MICRO_A and row.get('diagnosis') == DX_A, (
    f'**大体 / 镜下 / 诊断正文非空且原样**（后端本就返回、前端此前整行丢弃，钉住不许再掉）：{row}')
assert row.get('clinical_diagnosis') == '待查' and row.get('supplement_count') == 1, f'{row}'
sups = row['supplements']
assert isinstance(sups, list) and len(sups) == 1, f'A1 恰有 1 份补充报告正文：{sups}'
assert sups[0].get('seqNo') == 1 and sups[0].get('diagnosis') == SUP and sups[0].get('reason') == '免疫组化回报', (
    f'**supplements[0].diagnosis 是补充报告正文**：{sups[0]}')
assert sups[0].get('signedAt') and sups[0].get('signerName'), f'补充报告带签名人与时刻：{sups[0]}'
note = str(prior.get('note'))
assert '已写诊断' in note and '未拒收' in note and '同一患者' in note, f'口径写进 note：{note}'
# 反过来：从 A1 看，未诊断的 A2 与拒收的 A3 都不算
from_a1 = {x.get('id') for x in ok(api('GET', f'/pathology/report/{sA}/prior'), '既往（从 A1 看）').get('items') or []}
assert sB not in from_a1 and sC not in from_a1, f'未写诊断的 A2 正在做、A3 已拒收，都不构成既往：{from_a1}'

hist = ok(api('GET', f'/pathology/registry/specimens/{sB}/history'), '既往（登记页默认）')
h_ids = {x.get('id') for x in hist.get('items') or []}
assert sA in h_ids and sC not in h_ids, f'**默认口径不含已拒收的 A3**（修复前含）：{h_ids}'
assert sC1 not in h_ids and hist.get('includeRejected') is False and 'prior' in str(hist.get('note')), f'{hist}'
assert hist.get('sameName') is None, '不传 includeSameName 不查同名'
hist_r = ok(api('GET', f'/pathology/registry/specimens/{sB}/history?includeRejected=true'), '既往（含拒收）')
h_ids = {x.get('id') for x in hist_r.get('items') or []}
assert sA in h_ids and sC in h_ids and sC2 not in h_ids, f'含拒收时 A1 与 A3 都在、仍只含本患者的：{h_ids}'
assert hist_r.get('includeRejected') is True
rowC = next(x for x in hist_r['items'] if x.get('id') == sC)
assert rowC.get('rejected_at') and rowC.get('reject_reason') == REJ, f'**拒收行带 rejected_at / reject_reason**：{rowC}'
assert rowC.get('status') == 'COLLECTED', f'拒收不改 status，前端按 rejected_at 标「已拒收」：{rowC}'
assert next(x for x in hist_r['items'] if x.get('id') == sA).get('rejected_at') is None
# 同名他人块：份数只算未拒收，拒收份数另给 rejected_count
same = ok(api('GET', f'/pathology/registry/specimens/{sB}/history?includeSameName=true'), '既往（同名）').get('sameName') or []
rc = next((x for x in same if x.get('patient_id') == pC), None)
assert rc, f'同名他人 C 必须在提醒块里：{same}'
assert rc.get('specimen_count') == 1 and rc.get('rejected_count') == 0, f'**同名份数排除拒收的 C2（修复前算成 2）**：{rc}'
assert 'diagnosis' not in rc, '同名他人不给诊断——那是越界'
same_r = ok(api('GET', f'/pathology/registry/specimens/{sB}/history?includeSameName=true&includeRejected=true'), '既往（同名 + 含拒收）').get('sameName') or []
rc = next(x for x in same_r if x.get('patient_id') == pC)
assert rc.get('specimen_count') == 1 and rc.get('rejected_count') == 1, f'含拒收时 specimen_count 仍 1、rejected_count=1：{rc}'
assert api('GET', '/pathology/registry/specimens/999999999/history')['code'] == 5206, '标本不存在仍 5206'
print('[2] 2558 OK（**prior 含已签发 A1、带 gross/micro/diagnosis 正文与 status、supplements[0].diagnosis 是补充正文** / '
      '**拒收 A3 不算既往、同名他人不混入** / **history 默认不含拒收、includeRejected=true 含且 rejected_at 非空** / 同名份数只算未拒收）')

# ===========================================================================
# 3) 2563 一致性：挂接 / 染色登记须与特检医嘱一致（5274，零插入、被拒不写）；清单回 attached_stain
# ===========================================================================
n3 = '2563一致' + uniq('')
p3 = new_patient(t, n3, sex='M')['id']
o3, _ = pathology_order(p3, n3)
sT, bT = register(o3, 1, 'v59 一致性')
blockT = grossing(sT, 'v59 取材 T')
t1 = tech_order(sT, blockT, 'IHC', 'CK7', '查 CK7 定来源')
assert_attached_stain(sT, t1, None)
# 类型不符：HE 片挂 IHC 医嘱 → 5274、零插入（修复前照挂、进度照推）
r = api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 2, 'stainType': 'HE', 'techOrderId': t1})
assert r['code'] == 5274 and '染色类型' in (r.get('message') or '') and 'IHC' in (r.get('message') or ''), f'**HE 片挂 IHC 医嘱 → 5274**：{r}'
assert slides_of(sT) == [], '**被拒时一张片都不许插**'
# 类型对项目不对：IHC CK20 挂 CK7 医嘱 → 5274
r = api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 2, 'stainType': 'IHC', 'stainItem': 'CK20', 'techOrderId': t1})
assert r['code'] == 5274 and 'CK7' in (r.get('message') or '') and 'CK20' in (r.get('message') or ''), f'**IHC CK20 挂 CK7 医嘱 → 5274**：{r}'
# 项目缺失：医嘱有项目而切片不给 → 5274
assert api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'IHC', 'techOrderId': t1})['code'] == 5274, '项目缺失 5274'
assert slides_of(sT) == [], '三条被拒路径零插入'
assert_progress(sT, t1, 'PENDING_SECTION', '待切片', 0, 0)
tr = ok(api('GET', f'/pathology/process/trail/{sT}'), '流转轨迹')
assert not [x for x in tr.get('nodes') or [] if x.get('node') == 'SECTION'], '被拒的切片不留 SECTION 节点'
# 合法：IHC CK7（项目 trim 后比较）
sl = ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 2, 'stainType': 'IHC', 'stainItem': '  CK7 ', 'techOrderId': t1}), '挂接 IHC CK7')
assert sl.get('techOrderId') == t1 and len(sl['slides']) == 2, f'{sl}'
assert all(s.get('stain_type') == 'IHC' and s.get('stain_item') == 'CK7' and s.get('tech_order_id') == t1 for s in sl['slides']), f'{sl["slides"]}'
x1, x2 = sl['slides'][0]['id'], sl['slides'][1]['id']
assert_progress(sT, t1, 'SECTIONING', '切片中', 2, 0)
assert_attached_stain(sT, t1, 'IHC CK7 ×2')
# 染色登记：已挂接「有项目」医嘱的切片，项目不得改成别的 → 5274 且不写（修复前 coalesce(?, stain_item) 照改、进度照 +1）
r = api('PUT', f'/pathology/process/slides/{x1}/stain', {'quality': 'GOOD', 'stainItem': 'CK20'})
assert r['code'] == 5274 and 'CK7' in (r.get('message') or '') and 'CK20' in (r.get('message') or ''), f'**染色登记 stainItem=CK20 → 5274**：{r}'
row1 = slide_by_id(sT, x1)
assert row1.get('stained_at') is None and row1.get('quality') is None and row1.get('stain_item') == 'CK7', f'被拒不写：{row1}'
assert_progress(sT, t1, 'SECTIONING', '切片中', 2, 0)
# 空照旧：保留 CK7；相同项目（trim 后）照过
st1 = ok(api('PUT', f'/pathology/process/slides/{x1}/stain', {'quality': 'GOOD'}), '染色 x1（不传项目）')
assert st1.get('stain_item') == 'CK7' and st1.get('stained_at'), f'{st1}'
assert_progress(sT, t1, 'SECTIONING', '切片中', 2, 1)
st2 = ok(api('PUT', f'/pathology/process/slides/{x2}/stain', {'quality': 'GOOD', 'stainItem': ' CK7 '}), '染色 x2（相同项目）')
assert st2.get('stain_item') == 'CK7', f'{st2}'
assert_progress(sT, t1, 'STAINED', '已染色待确认', 2, 2)
assert api('PUT', f'/pathology/process/slides/{x1}/stain', {'stainItem': 'CK20'})['code'] == 5274, '已染色的片子再传错项目仍 5274（判在 update 之前）'
assert_attached_stain(sT, t1, 'IHC CK7 ×2')
# 批量核销：批内任一张不一致 → 5274 整批不写（含普通切片）；空照旧
t2 = tech_order(sT, blockT, 'IHC', 'CK20')
sl2 = ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 2, 'stainType': 'IHC', 'stainItem': 'CK20', 'techOrderId': t2}), '挂接 IHC CK20')
y1, y2 = sl2['slides'][0]['id'], sl2['slides'][1]['id']
plain = ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'HE'}), '普通切片')['slides'][0]['id']
r = api('PUT', '/pathology/process/slides/batch-complete', {'slideIds': [y1, y2, plain], 'quality': 'GOOD', 'stainItem': 'CK7'})
assert r['code'] == 5274 and f'#{t2} CK20' in (r.get('message') or ''), f'**批量核销项目不一致 → 5274 整批不写**：{r}'
for sid_ in (y1, y2, plain):
    assert slide_by_id(sT, sid_).get('stained_at') is None, f'整批不写：切片 {sid_} 仍未染色'
assert slide_by_id(sT, plain).get('stain_item') is None, '普通切片也在整批不写之列'
assert_progress(sT, t2, 'SECTIONING', '切片中', 2, 0)
bc_ok = ok(api('PUT', '/pathology/process/slides/batch-complete', {'slideIds': [y1, y2, plain], 'quality': 'GOOD'}), '批量核销（不传项目）')
assert bc_ok.get('completed') == 3, f'{bc_ok}'
assert slide_by_id(sT, plain).get('stain_item') is None and slide_by_id(sT, plain).get('stained_at'), '普通切片留空就还是空（coalesce 保留）'
assert_progress(sT, t2, 'STAINED', '已染色待确认', 2, 2)
assert_attached_stain(sT, t2, 'IHC CK20 ×2')
# 深切医嘱（无项目）：IHC 片不行，HE 片可挂且项目不限；特殊染色 / 分子病理各对各的染色类型
deep = tech_order(sT, blockT, 'DEEP_CUT', None)
assert api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': deep})['code'] == 5274, '深切挂 IHC 片 5274'
ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'HE', 'techOrderId': deep}), '深切挂 HE 片')
ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'HE', 'stainItem': '连续切片', 'techOrderId': deep}), '深切挂 HE 片（任意项目）')
assert_attached_stain(sT, deep, {'HE ×1', 'HE 连续切片 ×1'})
pas = tech_order(sT, blockT, 'SPECIAL_STAIN', 'PAS')
assert api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'IHC', 'stainItem': 'PAS', 'techOrderId': pas})['code'] == 5274, '特殊染色医嘱挂 IHC 片 5274'
ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'SPECIAL', 'stainItem': 'PAS', 'techOrderId': pas}), '特殊染色挂 SPECIAL 片')
# 5272 四条仍先于 5274：他标本的医嘱，即使类型也不对，返回的仍是 5272；普通切片不受任何限制
sU, _ = register(o3, 2, 'v59 一致性 U')
blockU = grossing(sU, 'v59 取材 U')
onU = tech_order(sU, blockU, 'IHC', 'Ki-67')
assert api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'HE', 'techOrderId': onU})['code'] == 5272, '跨标本仍 5272 先于 5274'
ok(api('POST', '/pathology/process/slides', {'blockId': blockT, 'count': 1, 'stainType': 'IHC', 'stainItem': '随便'}), '普通切片不受限')
print('[3] 2563 OK（**HE / IHC CK20 / 无项目挂 CK7 医嘱三路 5274 零插入、不留 SECTION 节点** / IHC CK7 成功 / '
      '**染色登记 stainItem=CK20 5274 不写、空与 CK7 照过** / 批量核销整批不写 / 深切 · 特殊染色各对各的类型 / '
      '**清单三处 attached_stain=IHC CK7 ×2** / 5272 仍先于 5274）')

# ===========================================================================
# 4) 2576：diagnose 后门诊申请仍 CHARGED、签发后 EXECUTED；质控表头字典（后端 zh() CSV 中文 / 前端 ZH 补齐四键）
# ===========================================================================
n4 = '2576签发' + uniq('')
p4 = new_patient(t, n4, sex='F')['id']
o4, rid4 = pathology_order(p4, n4)
sE, bE = register(o4, 1, 'v59 签发口径')


def order_status():
    """医生站工作区 GET /outpatient/doctor/{rid}/workspace 的 orders[].status（照抄 e2e-integration.py 读 EXECUTED 的方式）"""
    ws = ok(api('GET', f'/outpatient/doctor/{rid4}/workspace'), '医生站工作区')
    o = next((x for x in ws.get('orders') or [] if x.get('id') == o4), None)
    assert o, f'工作区里找不到病理医嘱 {o4}：{ws.get("orders")}'
    return o.get('status')


assert order_status() == 'CHARGED', '夹具前提：登记后申请仍 CHARGED'
ok(api('PUT', f'/pathology/specimens/{bE}/diagnose', {'grossFinding': 'g', 'microFinding': '镜下 2576', 'diagnosis': '2576 诊断'}), '书写诊断')
assert order_status() == 'CHARGED', (
    '**diagnose 后门诊申请仍 CHARGED**（修复前 diagnose 同一事务置 EXECUTED——医生站在报告尚未签发时就显示「已执行」）')
ok(api('PUT', f'/pathology/report/{sE}/first-sign', {}), '初诊签名')
assert order_status() == 'CHARGED', '初签也不置 EXECUTED——「报告出了」= 正式签发'
issued = ok(api('PUT', f'/pathology/report/{sE}/issue', {}), '签发')
assert issued.get('orderExecuted') is True and issued.get('reportIssuedAt'), f'签发返回体回带「本次置了 EXECUTED」的事实：{issued}'
assert order_status() == 'EXECUTED', '**正式签发后门诊申请才是 EXECUTED**'
assert api('PUT', f'/pathology/report/{sE}/issue', {})['code'] == 5261, '重复签发 5261'
assert order_status() == 'EXECUTED'
# 未诊断即签发 5262 → 不置（另一部位，登记须在上面签发之前……但本申请已 EXECUTED，登记会 5201——这正是「先登记完再签发」纪律的活证据）
assert api('POST', '/pathology/registry/specimens', {'orderId': o4, 'partNo': 2, 'specimenType': 'ROUTINE', 'specimenDesc': 'x'})['code'] == 5201, (
    '签发后申请 EXECUTED，登记只认 CHARGED → 5201（各部位须在签发前登记完）')

# 质控表头：后端 CSV 表头经 zh() 中文（stat_day → 日期）；JSON 穿透仍回原始列名（页面由前端 ZH 翻译）
det = ok(api('GET', '/path-qc/detail?indicator=WORKLOAD_REGISTER'), '质控穿透 WORKLOAD_REGISTER（默认近 30 天）')
items = det.get('items') or []
assert items and 'path_no' in items[0] and 'specimen_id' in items[0] and 'collected_at' in items[0], (
    f'穿透 JSON 回原始列名（本套刚登记的标本必在默认窗内）：{sorted(items[0]) if items else items}')
assert any(x.get('specimen_id') == sE for x in items), f'本套刚登记的标本须在穿透里：{[x.get("specimen_id") for x in items][:5]}'


def csv_header(path):
    """CSV 文本：首行指标元信息、次行取值、空行，之后第一行才是列名表头"""
    text = api('GET', path, raw=True).lstrip(chr(0xFEFF))   # 服务端 CSV 带 UTF-8 BOM，先剥掉
    lines = text.splitlines()
    assert lines and lines[0].startswith('指标编码,'), f'CSV 首行应是指标元信息：{lines[:2]}'
    assert '（该统计区间内无数据）' not in text, f'本套已造数，CSV 不该是空表：{text[:300]}'
    return next(ln for ln in lines[3:] if ln.strip())


hdr = csv_header('/path-qc/indicators.csv?indicator=WORKLOAD_REGISTER')
assert hdr.startswith('日期,'), f'**指标汇总 CSV 表头首列 stat_day 经 zh() 是「日期」**：{hdr!r}'
assert 'stat_day' not in hdr and '登记标本数' in hdr, f'表头全部中文化（registered → 登记标本数）：{hdr!r}'
dhdr = csv_header('/path-qc/detail.csv?indicator=WORKLOAD_REGISTER')
assert '病理号' in dhdr and '患者' in dhdr and '标本ID' in dhdr, f'穿透 CSV 表头经 zh()：{dhdr!r}'
assert 'path_no' not in dhdr and 'specimen_id' not in dhdr, f'穿透 CSV 表头不该漏成英文：{dhdr!r}'
# 前端 ZH ⊇ 后端 zh()：v59 地基上漏的四个键必须在 format.ts 里且中文与后端同名 case 逐字一致
_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_qc_java = open(os.path.join(_root, 'modules', 'medtech', 'src', 'main', 'java', 'cn', 'hip', 'medtech', 'web', 'PathQcController.java'), encoding='utf-8').read()
_format_ts = open(os.path.join(_root, 'frontend', 'shell', 'src', 'views', 'medtech', 'pathology', 'format.ts'), encoding='utf-8').read()


def backend_label(key, src=_qc_java):
    m = re.search(r'case\s+"' + re.escape(key) + r'"\s*->\s*"([^"]*)"', src)
    return m.group(1) if m else None


def zh_label(key, src=_format_ts):
    m = re.search(r'(?m)^\s*' + re.escape(key) + r"\s*:\s*'([^']*)'", src)
    return m.group(1) if m else None


for key in ('stat_day', 'issue_day', 'stained_count', 'progress_name'):
    be, fe = backend_label(key), zh_label(key)
    assert be, f'后端 zh() 已没有 case "{key}"——反向事实失效，请核对'
    assert fe == be, f'**前端 ZH.{key} 须与后端 zh() 同名 case 逐字一致**（v59 地基上 ZH 缺此键：页面英文、CSV 中文）：前端 {fe!r} 后端 {be!r}'
assert backend_label('stat_day') == '日期' and hdr.startswith(backend_label('stat_day') + ','), 'CSV 表头首列与 zh("stat_day") 同源'
assert backend_label('zz_probe_none') is None and zh_label('zz_probe_none') is None, '探针：不存在的键两侧都解析不出'
print('[4] 2576 OK（**diagnose / 初签后仍 CHARGED、签发后 EXECUTED、orderExecuted=true** / 签发后登记 5201 / '
      '**指标 CSV 表头首列「日期」、穿透 CSV 表头中文而 JSON 仍原始列名** / 前端 ZH 四键与后端 zh() 逐字一致）')

# ===========================================================================
# 5) 边界：V166 零回填——剥注释后无顶层 update / insert 形态；对照组 V22:40 / V161 抓得到；探针证明扫描器在咬
# ===========================================================================
_mig = os.path.join(_root, 'server', 'src', 'main', 'resources', 'db', 'migration')


def read_sql(name):
    with open(os.path.join(_mig, name), encoding='utf-8') as f:
        return f.read()


def strip_sql_comments(sql):
    """先剥块注释（斜杠星 … 星斜杠），再剥每行 -- 之后的部分——照抄 V59GrossReviseTest.stripSqlComments"""
    no_block = re.sub(r'/\*.*?\*/', '', sql, flags=re.S)
    return '\n'.join(ln.split('--', 1)[0] for ln in no_block.splitlines())


# 只认**语法形态**：行首（允许缩进）update <表> set / insert into <表>。不匹配裸 token——注释里「零条 update」这句话本身就含它
TOP_LEVEL_UPDATE = re.compile(r'^\s*update\s+\w+\s+set\b', re.I | re.M)
TOP_LEVEL_INSERT = re.compile(r'^\s*insert\s+into\s+\w+', re.I | re.M)
v166 = read_sql('V166__path_gross_revise.sql')
body = strip_sql_comments(v166)
assert 'add column revision_seq smallint not null default 1' in body, '读到的不是 V166 本尊：字段行加 revision_seq，默认 1'
assert 'drop constraint uq_path_gross_field_seq' in body and 'unique (specimen_id, revision_seq, seq)' in body, '唯一约束改为 (specimen_id, revision_seq, seq)'
assert 'add column template_code varchar(32)' in body, '修订行加 template_code'
assert "check (source in ('GROSSING', 'DIAGNOSE', 'GROSSING_EDIT'))" in body, 'source 白名单三档'
assert '零条 update' in v166, 'V166 注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本'
assert '默认值 1 是事实' in v166 or 'default 1 写的是它们真实的版本号' in v166, '头注释须论证 revision_seq 默认 1 为何是事实而非编造'
assert not TOP_LEVEL_UPDATE.search(body), '**V166 不许有任何 update 语句**：不给既有修订行猜 template_code，不改既有字段行的版号'
assert not TOP_LEVEL_INSERT.search(body), '**V166 不许 insert 任何行**：不伪造修订历史'
# 活的对照组：V22 第 40 行 `update md_drug set abx_level = 1 where antibiotic;`——扫描器抓不到就是扫描器坏了；V161 确有 insert into sys_config
v22 = read_sql('V22__phase25_mgmt.sql')
line40 = v22.splitlines()[39]
assert line40.startswith('update md_drug set abx_level = 1'), f'对照组 V22:40 不是预期那条 update：{line40!r}'
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v22)), '对照组 V22 确有 update md_drug set …，扫描器没抓到就是扫描器坏了'
assert TOP_LEVEL_INSERT.search(strip_sql_comments(read_sql('V161__timeliness_gate_seed.sql'))), '对照组 V161 确有 insert into sys_config，扫描器没抓到就是扫描器坏了'
# 探针：扫描器真的在咬
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v166 + '\nupdate path_gross_field set revision_seq = 1;\n')), '补一条回填语句后必须被抓到'
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v166 + "\n    UPDATE path_gross_revision SET template_code = 'GENERIC';\n")), '大小写与缩进不影响'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(v166 + '\n-- update path_gross_revision set template_code = null;\n')), '行注释里的不算'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(v166 + '\n/* update path_gross_field\n   set revision_seq = 2; */\n')), '块注释里的不算'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments("comment on column path_gross_field.revision_seq is 'update set 之类的字样';")), '裸 token 不算'
assert TOP_LEVEL_INSERT.search(strip_sql_comments(v166 + "\ninsert into path_gross_revision(specimen_id, seq, new_text, source) select id, 1, gross_finding, 'GROSSING' from path_specimen;\n")), '伪造修订历史的 insert 必须被抓到'
assert not TOP_LEVEL_INSERT.search(strip_sql_comments(v166 + '\n-- insert into path_gross_revision ...\n')), '行注释里的 insert 不算'
print('[5] 边界 OK（**V166 剥注释后零顶层 update、零 insert** / 对照组 V22:40 与 V161 抓得到 / 探针证明扫描器在咬）')

print('\ne2e-v59-audit 全部通过 ✅')
