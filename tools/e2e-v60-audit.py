# -*- coding: utf-8 -*-
"""v60 核账 E2E：病理三条契约的尾巴（2530 尾 / 2563 尾 / 2576 尾）在**真实服务**上逐条对账。

本套的由来与 e2e-v57-audit.py、e2e-v58-audit.py、e2e-v59-audit.py 同：v60 三条车道（A 2530 尾四个面板只读展示、
B 2563 尾 V167 补取材挂接 + 2576 尾-① 签发口径、C 2576 尾-②③ 科室维度 + 去 Markdown 标记）各自的 JUnit 都绿了，
但 JUnit 直接调控制器方法、跑在一个回滚事务里——**线格式、序列化、JWT、跨端点状态机**一个都没走过。
本套用 HTTP 把三条契约再钉一遍，断言全是**修复前主控实测过的反向事实**（v60 地基 96f098e）：
  [1] 2530 尾——登记时强制录入的 specimen_desc（4554 必填）在读端点都 select 了但前端**没有任何一处只读展示**；
             读端点没有 fieldsByRevision（被取代版本的结构化字段无处调阅：fields 只回最新有字段的那一版）；
  [2] 2563 尾——取材 append 路径不读不写 path_tech_order：补取材 RESAMPLE 医嘱的新蜡块永远挂不到医嘱上（V167 之前
             path_block 没有 tech_order_id 列）、医嘱 block_id 永远空、进度恒「待切片」、清单「蜡块」列永远「—」；
             挂接切片染色列是英文枚举（attached_stain=IHC CK7 ×2），没有中文版；
  [3] 2576 尾——多部位申请第 1 个部位一签发就把整张 outp_order 置 EXECUTED（另两个部位还在切片医生站就显示「已执行」）；
             质控汇总没有按送检科室聚合的维度（indicator=WORKLOAD_DEPT 返 5281）；口径 alert / 覆盖率 note 的 **强调**
             星号原样上屏（前端 {{ c }} / :title="sec.note" 直出）；
  [4] 边界 ——V167 零回填：剥注释后无 `^\\s*update <表> set` / `^\\s*insert into` 形态（对照组 V22:40 / V161 抓得到）。

助手逐字抄自 e2e-v59-audit.py（已验证能跑通）；调用形态照抄 V60ResampleProgressTest / V60IssueAllPartsTest /
V60DeptDimensionTest / V60SpecimenDescShownTest 钉住的 HTTP 契约，不凭印象猜契约——本仓已因猜契约返工多次
（驼峰/蛇形、rows/items/groups、排班是 POST 建、开药前须先 start、登记只认 CHARGED、签发才置 EXECUTED、
补取材必须 append=true、RESAMPLE 医嘱下达时 block_id 必空）。
时间一律从服务端响应或业务时间线派生，**不写墙钟字面量**。

**一处口径如实写在这里**：v60 起多部位申请第 1 部位签发后 outp_order 仍是 CHARGED，所以**签发之后还能再登记新部位**
（登记只认 CHARGED，5201 只在最后一个部位签发之后才出现）——这是收紧口径的必然后果，不是缺陷，本套 [3] 把它钉成活证据。
"""
import datetime
import os
import re
import subprocess
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
    不拿 pend[0]——脏库里 pend[0] 可能是别人的。排除本次已认领过的 order_id、取最新的一条。
    registration_id 供 [3] 读医生站工作区里医嘱的 status。"""
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
    v60 起多部位申请只在**全部未拒收部位都签发**后才置 EXECUTED，中途仍可登记新部位；单部位申请签发即 EXECUTED、之后登记 5201。"""
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
    blocks = blocks_of(sid)
    assert blocks, '取材应产出蜡块'
    return blocks[0]['id']


def blocks_of(sid):
    """GET /pathology/process/blocks?specimenId= 的 items，按块号升序（端点本身按 block_no 排）"""
    return ok(api('GET', f'/pathology/process/blocks?specimenId={sid}'), '蜡块列表').get('items') or []


def grossing_view(sid):
    v = ok(api('GET', f'/pathology/process/grossing/{sid}'), '取材视图')
    for k in ('specimen', 'fieldsAvailable', 'fields', 'revisions', 'grossFinding', 'fieldsRevisionSeq', 'textRevisionSeq',
              'fieldsCurrent', 'fieldsByRevision'):
        assert k in v, f'取材视图缺键 {k}（v60 新键 fieldsByRevision 修复前根本没有）：{sorted(v)}'
    return v


def labels(fields):
    return [f.get('label') for f in fields]


def trail(sid):
    return ok(api('GET', f'/pathology/process/trail/{sid}'), '流转轨迹')


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
    """质控穿透 WORKLOAD_TECH 里这条医嘱的行；日期窗两端各留一天余量（照抄 V60ResampleProgressTest.qcTechRow）"""
    f, to = (today - datetime.timedelta(days=1)).isoformat(), (today + datetime.timedelta(days=1)).isoformat()
    items = ok(api('GET', f'/path-qc/detail?indicator=WORKLOAD_TECH&from={f}&to={to}'), '质控穿透 WORKLOAD_TECH').get('items') or []
    row = next((x for x in items if x.get('tech_order_id') == tid), None)
    assert row, f'质控穿透里找不到技术医嘱 {tid}'
    return row


def assert_progress(sid, tid, progress, name, slides, stained):
    """标本清单 + 全院清单（status=ALL，按条码 keyword）+ 质控穿透三处同口径——
    v60 合并后补齐（eb74922）让穿透也走四参派生，SAMPLED 在三处都得派得出来"""
    row = tech_row(sid, tid)
    assert row.get('progress') == progress and row.get('progress_name') == name, (
        f'标本清单 progress 应为 {progress}/{name}：{row}')
    assert row.get('slide_count') == slides and row.get('stained_count') == stained, (
        f'标本清单 slide_count/stained_count 应为 {slides}/{stained}：{row}')
    wrow = wide_row(tid, row['barcode'])
    assert (wrow.get('progress'), wrow.get('slide_count'), wrow.get('stained_count')) == (progress, slides, stained), (
        f'全院清单与标本清单须同口径：{wrow}')
    qrow = qc_tech_row(tid)
    assert (qrow.get('progress'), qrow.get('slide_count')) == (progress, slides), (
        f'质控穿透 WORKLOAD_TECH 与清单须同口径（合并后补齐：穿透走四参派生才有 SAMPLED）：{qrow}')
    return row


def assert_derived(sid, tid, blocks_derived, sampled, stain_name):
    """v60 三列三处同口径（标本清单 / 全院清单 / 质控穿透）：blocks_derived / sampled_block_count / attached_stain_name；
    expected 为 None 即「列在、值为 null」"""
    row = tech_row(sid, tid)
    for where, r in (('标本清单', row), ('全院清单', wide_row(tid, row['barcode'])), ('质控穿透', qc_tech_row(tid))):
        for k in ('blocks_derived', 'sampled_block_count', 'attached_stain_name'):
            assert k in r, f'{where} 行必须带 {k} 列（修复前三处都没有）：{sorted(r)}'
        assert r.get('blocks_derived') == blocks_derived, f'{where} blocks_derived 应为 {blocks_derived!r}：{r.get("blocks_derived")!r}'
        assert r.get('sampled_block_count') == sampled, f'{where} sampled_block_count 应为 {sampled}：{r.get("sampled_block_count")!r}'
        assert r.get('attached_stain_name') == stain_name, f'{where} attached_stain_name 应为 {stain_name!r}：{r.get("attached_stain_name")!r}'


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


def snapshot(sid, tid):
    """标本上一切「写」的 HTTP 可见快照：大体所见、修订数、蜡块 id 集、轨迹节点数、该医嘱的 block_id——被拒路径前后必须逐字相等"""
    v = grossing_view(sid)
    tr = trail(sid)
    return (v['grossFinding'], len(v['revisions']), sorted(b['id'] for b in blocks_of(sid)), tr.get('nodeCount'),
            tech_row(sid, tid).get('block_id'))


# ===========================================================================
# 1) 2530 尾：登记录入的 specimen_desc 在检索表 / history / grossing 头 / trail 头 / reports 头 / prior 行都回出；
#    取材首写字段 + 修订 → fieldsByRevision 两版各版字段正确（再补一版纯文本 → fields=[]）；四个面板剥注释后确有渲染形态
# ===========================================================================
n1 = '2530描述' + uniq('')
p1 = new_patient(t, n1, sex='F')['id']
o1, _ = pathology_order(p1, n1)
D1 = '左乳肿物切除标本，灰白结节一枚 ' + uniq('D')
D2 = '右乳穿刺活检条索状组织两条 ' + uniq('E')
s1, b1 = register(o1, 1, D1)
s2, b2 = register(o1, 2, D2)

# (a) 登记检索表（RegistryPanel 检索表的数据源）
items = ok(api('GET', f'/pathology/registry/specimens/search?patientName={q(n1)}'), '登记检索').get('items') or []
by_id = {x.get('id'): x for x in items}
assert s1 in by_id and s2 in by_id, f'检索应搜到本患者两条：{sorted(by_id)}'
assert by_id[s1].get('specimen_desc') == D1 and by_id[s2].get('specimen_desc') == D2, (
    f'**检索行 specimen_desc 原样回出**（登记时 4554 强制录入，修复前前端一处不画）：{by_id[s1].get("specimen_desc")!r} / {by_id[s2].get("specimen_desc")!r}')
# (b) 既往抽屉（history，从 s2 看 s1）
hist = ok(api('GET', f'/pathology/registry/specimens/{s2}/history'), '既往（登记页）').get('items') or []
hrow = next((x for x in hist if x.get('id') == s1), None)
assert hrow and hrow.get('specimen_desc') == D1, f'**history 行带 specimen_desc**：{hrow}'
assert not [x for x in hist if x.get('id') == s2], '本标本自身不在自己的既往里'
# (c) 取材视图头 / (d) 轨迹头 / (e) 报告头：三处 specimen 头都带 specimen_desc（取材前就有头，不依赖大体所见）
v = grossing_view(s1)
assert v['specimen'].get('specimen_desc') == D1, f'**grossingView 的 specimen 头带 specimen_desc**：{v["specimen"]}'
th = trail(s1).get('specimen') or {}
assert 'specimen_desc' in th and th.get('specimen_desc') == D1, f'**trail 头带 specimen_desc**（车道 B 加的列）：{sorted(th)}'
rh = ok(api('GET', f'/pathology/report/{s1}/reports'), '报告全景').get('specimen') or {}
assert rh.get('specimen_desc') == D1, f'reports 头带 specimen_desc（DiagnosisPanel 抽屉头的数据源）：{rh}'
wl = ok(api('GET', f'/pathology/report/worklist?scope=all&keyword={q(b1)}&limit=50'), '阅片列表（scope=all：默认 stained 不含无片标本）').get('items') or []
wrow1 = next((x for x in wl if x.get('id') == s1), None)
assert wrow1 and wrow1.get('specimen_desc') == D1, f'worklist 行带 specimen_desc：{wrow1}'
gwl = ok(api('GET', f'/pathology/process/grossing/worklist?keyword={q(b1)}&limit=50'), '取材工作列表（默认 pending：已核收未诊断）').get('items') or []
gwrow = next((x for x in gwl if x.get('id') == s1), None)
assert gwrow and gwrow.get('specimen_desc') == D1, f'**取材工作列表行带 specimen_desc**（GrossingPanel 列表的数据源）：{gwrow}'

# (f) 取材两次：首写字段（第 1 版）+ 修订（第 2 版）→ fieldsByRevision 两版、各版字段正确；再补一版纯文本 → 第 3 版 fields=[]
G1 = {'大小': '3×2×1cm', '颜色': '灰白'}
FREE1 = '切面实性 ' + uniq('F')
gr = ok(api('POST', '/pathology/process/grossing',
            {'specimenId': s1, 'templateCode': 'gi_biopsy', 'gross': G1, 'grossText': FREE1,
             'blocks': [{'tissueDesc': '肿物中心'}]}), '取材首写（带模板 + 两字段）')
assert gr.get('grossRevisionSeq') == 1 and gr.get('grossFieldCount') == 2, f'{gr}'
v = grossing_view(s1)
fbr = v['fieldsByRevision']
assert isinstance(fbr, list) and len(fbr) == 1, f'首写后 fieldsByRevision 恰 1 版：{fbr}'
assert fbr[0].get('revisionSeq') == 1 and fbr[0].get('source') == 'GROSSING' and fbr[0].get('sourceName') == '取材首写', f'{fbr[0]}'
assert fbr[0].get('templateCode') == 'GI_BIOPSY', f'每版带本版模板码（大写规范化）：{fbr[0]}'
assert labels(fbr[0]['fields']) == list(G1) and [f.get('value') for f in fbr[0]['fields']] == list(G1.values()), f'{fbr[0]["fields"]}'
assert [f.get('seq') for f in fbr[0]['fields']] == [1, 2], f'每版 fields 按 seq 从 1 起：{fbr[0]["fields"]}'
assert set(fbr[0]['fields'][0]) == {'seq', 'label', 'value'}, f'fieldsByRevision 的字段行只有 seq/label/value 三键：{fbr[0]["fields"][0]}'
G2 = {'大小': '3.5×2×1cm', '颜色': '灰白', '质地': '质硬'}
FREE2 = '切面实性，局灶出血 ' + uniq('E')
rv = ok(api('PUT', f'/pathology/process/grossing/{s1}/fields', {'templateCode': 'generic', 'gross': G2, 'grossText': FREE2}), '取材修订')
assert rv['revisionSeq'] == 2 and rv['grossFieldCount'] == 3, f'{rv}'
v = grossing_view(s1)
fbr = v['fieldsByRevision']
assert [r.get('revisionSeq') for r in fbr] == [1, 2], f'**修订后两版、revisionSeq 升序**：{fbr}'
assert [r.get('source') for r in fbr] == ['GROSSING', 'GROSSING_EDIT'], f'{fbr}'
assert labels(fbr[0]['fields']) == list(G1), f'**被取代的第 1 版字段仍可调阅**（修复前 fields 只回最新一版、旧版无处看）：{fbr[0]["fields"]}'
assert labels(fbr[1]['fields']) == list(G2) and [f.get('value') for f in fbr[1]['fields']] == list(G2.values()), f'第 2 版字段正确：{fbr[1]["fields"]}'
assert fbr[1].get('templateCode') == 'GENERIC' and fbr[1].get('changedByName'), f'{fbr[1]}'
assert labels(v['fields']) == list(G2) and v['fieldsRevisionSeq'] == 2 and v['fieldsCurrent'] is True, '既有键不动：fields 仍是最新有字段的那一版'
# 再补一版只有自由文本 → 第 3 版 fields=[]（不是缺键、不是 null）；fields / fieldsRevisionSeq 仍指第 2 版、fieldsCurrent=false
ga = ok(api('POST', '/pathology/process/grossing',
            {'specimenId': s1, 'grossText': '再补一块，未填字段 ' + uniq('A'), 'append': True, 'blocks': [{'tissueDesc': '再补'}]}), '补取材（纯文本）')
assert ga.get('grossRevisionSeq') == 3, f'{ga}'
v = grossing_view(s1)
fbr = v['fieldsByRevision']
assert [r.get('revisionSeq') for r in fbr] == [1, 2, 3] and fbr[2].get('fields') == [], f'**无字段行的版本 fields=[]**：{fbr[2]}'
assert fbr[2].get('sourceName') == '补取材追加' and labels(fbr[1]['fields']) == list(G2), f'{fbr}'
assert v['fieldsRevisionSeq'] == 2 and v['textRevisionSeq'] == 3 and v['fieldsCurrent'] is False, f'既有三键口径不变：{v["fieldsRevisionSeq"]}/{v["textRevisionSeq"]}/{v["fieldsCurrent"]}'
# 尚无大体所见的标本：fieldsByRevision=[]（不是缺键）
v2 = grossing_view(s2)
assert v2['fieldsByRevision'] == [] and v2['revisions'] == [], f'无修订的标本 fieldsByRevision=[]：{v2["fieldsByRevision"]}'

# (g) prior 行（诊断抽屉对比框的数据源）：s1 写诊断后成为 s2 的既往，行带 specimen_desc；不传大体所见 → 不出新版本
dg = ok(api('PUT', f'/pathology/specimens/{b1}/diagnose', {'microFinding': '镜下 v60', 'diagnosis': '浸润性导管癌 v60 ' + uniq('X')}), '诊断 s1（不传大体）')
assert dg.get('grossKept') is True, f'空白入参保留取材大体所见（v57 契约）：{dg}'
prior = ok(api('GET', f'/pathology/report/{s2}/prior'), '既往（从 s2 看）')
prow = next((x for x in prior.get('items') or [] if x.get('id') == s1), None)
assert prow and prow.get('specimen_desc') == D1, f'**prior 行带 specimen_desc**：{prow}'
assert len(grossing_view(s1)['fieldsByRevision']) == 3, '不传大体的诊断不出新版本'

# (h) 四个面板剥注释后确有 specimen_desc 的渲染形态（照抄 V60SpecimenDescShownTest 的三族形态与最少处数）；
#     对照组 = 地基 96f098e 的真文本（git show），浅克隆取不到就明说跳过，不假绿
_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_PANEL_DIR = os.path.join('frontend', 'shell', 'src', 'views', 'medtech', 'pathology')
MIN_RENDER = {'RegistryPanel.vue': 2, 'GrossingPanel.vue': 2, 'DiagnosisPanel.vue': 3, 'TrailPanel.vue': 1}
MUSTACHE = re.compile(r'\{\{(?:(?!\}\}).)*?\bspecimen_desc\b(?:(?!\}\}).)*?\}\}', re.S)
PROP = re.compile(r'\bprop="specimen_desc"')
LABEL_BIND = re.compile(r':label="[^"]*\bspecimen_desc\b[^"]*"')
V_MODEL = re.compile(r'v-model="[^"]*\bspecimen(?:_d|D)esc\b[^"]*"')


def read_text(rel):
    with open(os.path.join(_root, rel), encoding='utf-8') as f:
        return f.read()


def strip_html_comments(s):
    out, i = [], 0
    while True:
        start = s.find('<!--', i)
        if start < 0:
            out.append(s[i:])
            break
        out.append(s[i:start])
        end = s.find('-->', start + 4)
        stop = len(s) if end < 0 else end + 3
        out.append('\n' * s.count('\n', start, stop))
        i = stop
    return ''.join(out)


def strip_js_comments(s):
    """去掉 // 行注释与 /* */ 块注释，字符串里的内容原样保留——照抄 V60SpecimenDescShownTest.stripJsComments"""
    out, quote, i, n = [], None, 0, len(s)
    while i < n:
        ch = s[i]
        if quote:
            out.append(ch)
            if ch == '\\' and i + 1 < n:
                out.append(s[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
        elif ch in ('\'', '"', '`'):
            quote = ch
            out.append(ch)
            i += 1
        elif ch == '/' and i + 1 < n and s[i + 1] == '/':
            while i < n and s[i] != '\n':
                i += 1
            out.append('\n')
            i += 1
        elif ch == '/' and i + 1 < n and s[i + 1] == '*':
            e = s.find('*/', i + 2)
            end = n if e < 0 else e + 2
            out.append('\n' * s.count('\n', i, end))
            i = end
        else:
            out.append(ch)
            i += 1
    return ''.join(out)


def stripped(vue):
    return strip_js_comments(strip_html_comments(vue))


def render_count(code):
    return sum(len(p.findall(code)) for p in (MUSTACHE, PROP, LABEL_BIND))


def git_show(sha, rel):
    try:
        r = subprocess.run(['git', '-C', _root, 'show', f'{sha}:{rel}'], capture_output=True)
        return r.stdout.decode('utf-8') if r.returncode == 0 else None
    except Exception:
        return None


for name, need in MIN_RENDER.items():
    code = stripped(read_text(os.path.join(_PANEL_DIR, name)))
    n = render_count(code)
    assert n >= need, f'**{name} 剥注释后 specimen_desc 渲染形态只有 {n} 处，至少要 {need}**（修复前四文件都是 0）'
assert len(V_MODEL.findall(stripped(read_text(os.path.join(_PANEL_DIR, 'RegistryPanel.vue'))))) >= 1, '登记表单的 v-model 仍在——只读展示不是把录入口改掉'
# 探针：注释里的形态不计、v-model 不算渲染、驼峰不算
_syn = '<template>\n  <!-- {{ fmt(row.specimen_desc) }} -->\n  <el-table-column prop="specimen_desc" />\n  <el-input v-model="regForm.specimenDesc" />\n  <span>{{\n fmt(a.specimen_desc) }}</span>\n</template>\n<script setup lang="ts">\n// {{ fmt(row.specimen_desc) }}\nconst k = \'specimen_desc\'\n</script>'
assert render_count(stripped(_syn)) == 2 and render_count(_syn) == 4 and render_count('{{ fmt(row.specimenDesc) }}') == 0, '扫描器探针：剥注释是活的、驼峰不计'
_base_ok = True
for name in MIN_RENDER:
    base = git_show('96f098e', (_PANEL_DIR + '/' + name).replace(os.sep, '/'))
    if base is None:
        _base_ok = False
        break
    assert render_count(stripped(base)) == 0, f'对照组：修复前 {name} 不该有任何渲染形态（反驳者原话），扫描器却数到了'
    assert base.replace('\r\n', '\n') != read_text(os.path.join(_PANEL_DIR, name)).replace('\r\n', '\n'), f'{name} 地基文本与工作区文本相同——对照组读到的不是地基'
print('[1] 2530 尾 OK（**specimen_desc 在检索表 / history / grossing 头 / trail 头 / reports 头 / worklist / prior 行原样回出** / '
      '**fieldsByRevision：首写 + 修订两版各版字段正确、纯文本第 3 版 fields=[]、无修订标本 []** / '
      '四个面板剥注释后渲染形态 ≥ 2/2/3/1' + ('、对照组 96f098e 四文件皆 0' if _base_ok else '、对照组不可用（浅克隆）已跳过') + '）')

# ===========================================================================
# 2) 2563 尾：RESAMPLE 医嘱（block 空）→ append 取材挂接 techOrderId → 新块 tech_order_id、医嘱 block_id 回写、
#    进度 SAMPLED、blocks_derived 含新块码；IHC / 他标本 / 已取消 / 不存在 → 5275 零写入；append=false 带 techOrderId → 5222；
#    attached_stain_name 中文；随后切片 → SECTIONING → 染色 → STAINED → done → DONE
# ===========================================================================
n2 = '2563补取' + uniq('')
p2 = new_patient(t, n2, sex='M')['id']
o2, _ = pathology_order(p2, n2)
sA, bA = register(o2, 1, 'v60 补取材 A')
sB, bB = register(o2, 2, 'v60 补取材 B（他标本）')
blockA = grossing(sA, 'v60 取材 A')
blockB = grossing(sB, 'v60 取材 B')
codeA = blocks_of(sA)[0]['block_code']
rs = tech_order(sA, None, 'RESAMPLE', None, '肿物边缘未取到 ' + uniq('R'))
row = tech_row(sA, rs)
assert row.get('block_id') is None and row.get('block_code') is None, f'夹具前提：RESAMPLE 下达时块还不存在，block_id 必空：{row}'
assert_progress(sA, rs, 'PENDING_SECTION', '待切片', 0, 0)
assert_derived(sA, rs, None, 0, None)

# 被拒四路 5275（全部在任何写入之前）：非 RESAMPLE 的 IHC 医嘱 / 他标本的 RESAMPLE / 已取消 / 不存在
ihc = tech_order(sA, blockA, 'IHC', 'CK7', '查 CK7 定来源')
rsB = tech_order(sB, None, 'RESAMPLE', None)
canc = tech_order(sA, None, 'RESAMPLE', None)
ok(api('PUT', f'/pathology/report/tech-orders/{canc}/cancel', {'reason': '不补了 ' + uniq('C')}), '取消一条 RESAMPLE')
before = snapshot(sA, rs)
for tid, why in ((ihc, 'RESAMPLE'), (rsB, '不属于该标本'), (canc, '待执行'), (999999999, '不存在')):
    r = api('POST', '/pathology/process/grossing',
            {'specimenId': sA, 'grossText': '被拒路径描述 ' + uniq('Z'), 'append': True, 'blocks': [{'tissueDesc': 'x'}], 'techOrderId': tid})
    assert r['code'] == 5275 and why in (r.get('message') or ''), f'**techOrderId={tid} → 5275 且消息点明「{why}」**：{r}'
    assert snapshot(sA, rs) == before, f'techOrderId={tid} 被拒后零写入（蜡块 / 大体 / 修订 / 节点 / block_id 全不动）'
assert tech_row(sB, rsB).get('block_id') is None, '他标本的医嘱不被回写'
assert tech_row(sA, ihc).get('block_id') == blockA, 'IHC 医嘱下达时指定的 blockA 不被动'
# append=false 带 techOrderId → 5222（首次取材没有补取材医嘱可挂）零写入：用尚无蜡块的新标本（有蜡块的先撞 5223，见 v59 文件头）
sC, bC = register(o2, 3, 'v60 首次取材挂接被拒 C')
rsC = tech_order(sC, None, 'RESAMPLE', None)
r = api('POST', '/pathology/process/grossing',
        {'specimenId': sC, 'grossText': '首次取材 ' + uniq('C'), 'append': False, 'blocks': [{'tissueDesc': 'x'}], 'techOrderId': rsC})
assert r['code'] == 5222 and '首次取材不能挂接补取材医嘱' in (r.get('message') or ''), f'**append=false 带 techOrderId → 5222**：{r}'
vC = grossing_view(sC)
assert vC['blockCount'] == 0 and vC['revisions'] == [] and vC['grossFinding'] is None and tech_row(sC, rsC).get('block_id') is None, f'5222 零写入：{vC}'
ok(api('POST', '/pathology/process/grossing', {'specimenId': sC, 'grossText': '首次取材 ' + uniq('C'), 'blocks': [{'tissueDesc': 'x'}]}), '同标本不带 techOrderId 照旧能首次取材')

# 正向：append=true 带 techOrderId → 两块都挂医嘱、医嘱 block_id 回写首块、GROSSING 节点备注带医嘱、SAMPLED、
# blocks_derived 列出**为本医嘱补出的每一块**（v60 复核修补：旧写法被回写的 block_id 短路，只显示第 1 块）
ga = ok(api('POST', '/pathology/process/grossing',
            {'specimenId': sA, 'grossText': '补取材灰白组织两块 ' + uniq('G'), 'append': True, 'remark': '补切缘',
             'blocks': [{'tissueDesc': '切缘一'}, {'tissueDesc': '切缘二'}], 'techOrderId': rs}), '补取材挂接 RESAMPLE（修复前无此入参）')
assert ga.get('techOrderId') == rs and ga.get('techOrderBlockBackfilled') is True, f'返回体回带挂接医嘱与「block_id 由空回写」：{ga}'
created = ga['blocks']
assert len(created) == 2 and all(b.get('tech_order_id') == rs for b in created), f'**新蜡块 tech_order_id = 医嘱**（修复前永远挂不上）：{created}'
nb1, nb2 = created[0]['id'], created[1]['id']
code1, code2 = created[0]['block_code'], created[1]['block_code']
assert code1 != code2 and code1 != codeA
row = tech_row(sA, rs)
assert row.get('block_id') == nb1 and row.get('block_code') == code1, f'**医嘱 block_id 回写为本次首块**：{row}'
assert_progress(sA, rs, 'SAMPLED', '已补取材待切片', 0, 0)
assert_derived(sA, rs, code1 + '、' + code2, 2, None)
gn = [x for x in trail(sA).get('nodes') or [] if x.get('node') == 'GROSSING']
assert len(gn) == 2 and f'补取材医嘱#{rs}' in str(gn[-1].get('remark')) and '取材产出 2 块' in str(gn[-1].get('remark')), f'GROSSING 节点备注带医嘱：{gn}'
assert gn[-1].get('occurred_at') and gn[-1].get('operator_id'), f'节点带人与时刻（库端 now()）：{gn[-1]}'
# 再补一块：block_id 已有值不覆盖，挂接蜡块数 3
ga2 = ok(api('POST', '/pathology/process/grossing', {'specimenId': sA, 'append': True, 'blocks': [{'tissueDesc': '切缘三'}], 'techOrderId': rs}), '再补一块')
assert ga2.get('techOrderBlockBackfilled') is False and ga2['blocks'][0].get('tech_order_id') == rs, f'{ga2}'
nb3 = ga2['blocks'][0]['id']
code3 = ga2['blocks'][0]['block_code']
assert tech_row(sA, rs).get('block_id') == nb1, 'block_id 不覆盖'
# 「蜡块」列列出为本医嘱补出的**每一块**：v60 复核时两个独立反驳者指出旧写法
# coalesce(b.block_code, union…) 被回写的 block_id 短路，补出 N 块只显示第 1 块，
# 而同一行 sampled_block_count 是 N——同屏自相矛盾。改并集后三块都在。
assert_derived(sA, rs, code1 + '、' + code2 + '、' + code3, 3, None)
assert_progress(sA, rs, 'SAMPLED', '已补取材待切片', 0, 0)
# 派生路径：未指定蜡块的 IHC 从 blockA 挂片后「蜡块」列由挂接切片所在块派生（修复前永远「—」）；中文汇总
ck20 = tech_order(sA, None, 'IHC', 'CK20')
assert_derived(sA, ck20, None, 0, None)
ok(api('POST', '/pathology/process/slides', {'blockId': blockA, 'count': 2, 'stainType': 'IHC', 'stainItem': 'CK20', 'techOrderId': ck20}), '挂接 IHC CK20')
assert tech_row(sA, ck20).get('block_id') is None, '派生不回写 block_id：读侧只读'
assert_derived(sA, ck20, codeA, 0, '免疫组化 CK20 ×2')
assert tech_row(sA, ck20).get('attached_stain') == 'IHC CK20 ×2', 'v59 的英文枚举版 attached_stain 一个字节不动'
# 指定了蜡块的 IHC：blocks_derived 取其块码；中文版与英文版并存
ok(api('POST', '/pathology/process/slides', {'blockId': blockA, 'count': 2, 'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': ihc}), '挂接 IHC CK7')
assert_derived(sA, ihc, codeA, 0, '免疫组化 CK7 ×2')
assert tech_row(sA, ihc).get('attached_stain') == 'IHC CK7 ×2'
# 切片：首块（回写的 block_id）→ SECTIONING；为它补出的第 2 块也可挂（v60 放宽 5272 一档）；HE 中文「HE 染色 ×2」
sl1 = ok(api('POST', '/pathology/process/slides', {'blockId': nb1, 'count': 1, 'stainType': 'HE', 'techOrderId': rs}), 'RESAMPLE 首块切片')
assert_progress(sA, rs, 'SECTIONING', '切片中', 1, 0)
sl2 = ok(api('POST', '/pathology/process/slides', {'blockId': nb2, 'count': 1, 'stainType': 'HE', 'techOrderId': rs}), 'RESAMPLE 第 2 块切片（放宽）')
assert_progress(sA, rs, 'SECTIONING', '切片中', 2, 0)
assert_derived(sA, rs, code1 + '、' + code2 + '、' + code3, 3, 'HE 染色 ×2')
assert tech_row(sA, rs).get('attached_stain') == 'HE ×2'
# 既非医嘱指定块、也非为它补出的块：仍 5272 一张不插；类型不符仍 5274
r = api('POST', '/pathology/process/slides', {'blockId': blockA, 'count': 1, 'stainType': 'HE', 'techOrderId': rs})
assert r['code'] == 5272 and '为它补出的蜡块' in (r.get('message') or ''), f'首次取材的 blockA 不是为 rs 补出的 → 5272：{r}'
assert api('POST', '/pathology/process/slides', {'blockId': nb3, 'count': 1, 'stainType': 'IHC', 'stainItem': 'x', 'techOrderId': rs})['code'] == 5274, 'RESAMPLE → HE，IHC 片 5274'
assert api('POST', '/pathology/process/slides', {'blockId': nb2, 'count': 1, 'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': ihc})['code'] == 5272, 'nb2 是为 rs 补出的，不是为 ihc：原 5272 路径不受放宽影响'
assert_progress(sA, rs, 'SECTIONING', '切片中', 2, 0)
# 染色 → STAINED；done → DONE（切片全染色，任何 gate 档都无缺口）；已完成的医嘱不能再挂补取材块 5275
x1, x2 = sl1['slides'][0]['id'], sl2['slides'][0]['id']
ok(api('PUT', f'/pathology/process/slides/{x1}/stain', {'quality': 'GOOD'}), '染色 x1')
assert_progress(sA, rs, 'SECTIONING', '切片中', 2, 1)
ok(api('PUT', f'/pathology/process/slides/{x2}/stain', {'quality': 'GOOD'}), '染色 x2')
assert_progress(sA, rs, 'STAINED', '已染色待确认', 2, 2)
done = ok(api('PUT', f'/pathology/report/tech-orders/{rs}/done', {}), '完成 RESAMPLE')
assert done.get('progress') == 'DONE' and done.get('warnings') == [], f'{done}'
assert_progress(sA, rs, 'DONE', '已完成', 2, 2)
assert_derived(sA, rs, code1 + '、' + code2 + '、' + code3, 3, 'HE 染色 ×2')
r = api('POST', '/pathology/process/grossing', {'specimenId': sA, 'append': True, 'blocks': [{'tissueDesc': 'x'}], 'techOrderId': rs})
assert r['code'] == 5275 and '待执行' in (r.get('message') or ''), f'已完成的医嘱不能再挂补取材块：{r}'
assert len(blocks_of(sA)) == 4, '5275 不加块'
# 旧世界路径（不传 techOrderId）逐字照旧：新块不挂、医嘱 block_id 仍空、进度恒待切片
rs2 = tech_order(sA, None, 'RESAMPLE', None)
gl = ok(api('POST', '/pathology/process/grossing', {'specimenId': sA, 'append': True, 'blocks': [{'tissueDesc': '旧世界补取材'}]}), '补取材（不带 techOrderId）')
assert gl.get('techOrderId') is None and gl.get('techOrderBlockBackfilled') is False and gl['blocks'][0].get('tech_order_id') is None, f'{gl}'
assert tech_row(sA, rs2).get('block_id') is None
assert_progress(sA, rs2, 'PENDING_SECTION', '待切片', 0, 0)
assert_derived(sA, rs2, None, 0, None)
assert '补取材医嘱' not in str([x for x in trail(sA)['nodes'] if x.get('node') == 'GROSSING'][-1].get('remark')), '不挂接的节点备注不提医嘱'
print('[2] 2563 尾 OK（**append=true 带 techOrderId → 新块 tech_order_id=医嘱、医嘱 block_id 回写首块、GROSSING 备注带医嘱、'
      '三处进度 SAMPLED、blocks_derived 列出补出的每一块、sampled_block_count=2→3** / **IHC · 他标本 · 已取消 · 不存在四路 5275 零写入** / '
      '**append=false 带 techOrderId 5222 零写入** / 未指定蜡块的 IHC 从挂接切片派生 blocks_derived / '
      '**attached_stain_name 中文「免疫组化 CK20 ×2」「HE 染色 ×2」、attached_stain 英文不动** / '
      '首块与补出的第 2 块可挂片、blockA 仍 5272、IHC 片 5274 / SECTIONING → STAINED → DONE / 已完成再挂 5275 / 旧路径照旧）')

# ===========================================================================
# 3) 2576 尾：同一申请三部位（第 3 部位拒收）：第 1 部位签发 → 仍 CHARGED、orderExecuted=false、partsPending=1；
#    中途仍可登记（申请仍 CHARGED）；第 2 部位签发 → EXECUTED、partsPending=0；单部位行为不变；
#    WORKLOAD_DEPT 含本科室且非零、与 WORKLOAD_REGISTER 同分母、穿透按 dept 过滤、CSV 表头中文；
#    口径文本：后端 caveats 确带 **（事实）、前端 mdText 去标记后零星号、PathQcView 所有口径绑定都经 mdText、ZH ⊇ zh()
# ===========================================================================
n3 = '2576多部位' + uniq('')
p3 = new_patient(t, n3, sex='F')['id']
o3, rid3 = pathology_order(p3, n3)
sP1, bP1 = register(o3, 1, 'v60 部位一')
sP2, bP2 = register(o3, 2, 'v60 部位二')
sP3, bP3 = register(o3, 3, 'v60 部位三（拒收）', receive=False)
ok(api('PUT', f'/pathology/registry/specimens/{sP3}/reject', {'reason': '标本未固定 ' + uniq('R')}), '拒收部位三')


def order_status(rid, oid):
    """医生站工作区 GET /outpatient/doctor/{rid}/workspace 的 orders[].status（照抄 e2e-v59-audit.order_status）"""
    ws = ok(api('GET', f'/outpatient/doctor/{rid}/workspace'), '医生站工作区')
    o = next((x for x in ws.get('orders') or [] if x.get('id') == oid), None)
    assert o, f'工作区里找不到病理医嘱 {oid}：{ws.get("orders")}'
    return o.get('status')


def diagnose_and_sign(sid, bc):
    ok(api('PUT', f'/pathology/specimens/{bc}/diagnose', {'grossFinding': 'g', 'microFinding': '镜下 v60', 'diagnosis': 'v60 诊断 ' + uniq('D')}), '诊断')
    ok(api('PUT', f'/pathology/report/{sid}/first-sign', {}), '初诊签名')


assert order_status(rid3, o3) == 'CHARGED', '夹具前提：登记 / 拒收后申请仍 CHARGED'
diagnose_and_sign(sP1, bP1)
diagnose_and_sign(sP2, bP2)
assert order_status(rid3, o3) == 'CHARGED', 'diagnose / 初签不置 EXECUTED（v59 契约不变）'
i1 = ok(api('PUT', f'/pathology/report/{sP1}/issue', {}), '签发部位一')
assert i1.get('reportIssuedAt'), f'{i1}'
assert i1.get('orderExecuted') is False and i1.get('partsPending') == 1, (
    f'**第 1 部位签发不置 EXECUTED、partsPending=1（部位二；拒收的部位三不算）**（修复前 orderExecuted=true / partsPending 键不存在）：{i1}')
assert order_status(rid3, o3) == 'CHARGED', '**第 1 部位签发后医生站仍 CHARGED**（修复前已显示「已执行」而部位二还没出报告）'
for k in ('specimenId', 'reportIssuedAt', 'diagnosedAt', 'doubleSignGate', 'doubleSignComplete', 'warnings', 'orderExecuted', 'partsPending'):
    assert k in i1, f'签发返回体缺 {k}：{sorted(i1)}'
# 收紧口径的必然后果（如实钉住）：申请仍 CHARGED → 还能登记第 4 部位（不是 5201）；随即拒收它，不算未完成
sP4, _ = register(o3, 4, 'v60 部位四（签发后登记，随即拒收）', receive=False)
ok(api('PUT', f'/pathology/registry/specimens/{sP4}/reject', {'reason': '量不足 ' + uniq('R')}), '拒收部位四')
assert api('PUT', f'/pathology/report/{sP1}/issue', {})['code'] == 5261, '重复签发 5261'
assert api('PUT', f'/pathology/report/{sP3}/issue', {})['code'] == 5261, '已拒收的部位不能签发 5261'
assert order_status(rid3, o3) == 'CHARGED'
i2 = ok(api('PUT', f'/pathology/report/{sP2}/issue', {}), '签发部位二')
assert i2.get('orderExecuted') is True and i2.get('partsPending') == 0, f'**最后一个未拒收部位签发才置 EXECUTED、partsPending=0**：{i2}'
assert order_status(rid3, o3) == 'EXECUTED', '**全部未拒收部位签发后医生站才 EXECUTED**'
assert api('POST', '/pathology/registry/specimens', {'orderId': o3, 'partNo': 5, 'specimenType': 'ROUTINE', 'specimenDesc': 'x'})['code'] == 5201, (
    '申请 EXECUTED 后登记只认 CHARGED → 5201（各部位须在最后一个部位签发之前登记完）')
# 单部位申请行为逐字不变：签发即 EXECUTED、partsPending=0
o3s, rid3s = pathology_order(p3, n3)
sS, bS = register(o3s, 1, 'v60 单部位对照')
diagnose_and_sign(sS, bS)
iS = ok(api('PUT', f'/pathology/report/{sS}/issue', {}), '签发单部位')
assert iS.get('orderExecuted') is True and iS.get('partsPending') == 0 and order_status(rid3s, o3s) == 'EXECUTED', f'单部位：签发即 EXECUTED：{iS}'
assert api('POST', '/pathology/registry/specimens', {'orderId': o3s, 'partNo': 2, 'specimenType': 'ROUTINE', 'specimenDesc': 'x'})['code'] == 5201

# WORKLOAD_DEPT：本套全部登记都走 dept 1 的挂号科室——该科室行必在且非零；与 WORKLOAD_REGISTER 同窗同分母；穿透可按 dept 过滤
f_, to_ = (today - datetime.timedelta(days=1)).isoformat(), (today + datetime.timedelta(days=1)).isoformat()
depts = ok(api('GET', '/system/depts'), '科室')
dept1 = next((d for d in depts if d.get('id') == 1), None)
assert dept1 and (dept1.get('name') or '').strip(), f'夹具前提：科室 1 存在且有名：{dept1}'
DEPT1 = dept1['name'].strip()
ind_body = ok(api('GET', f'/path-qc/indicators?indicator=WORKLOAD_DEPT&from={f_}&to={to_}'), '质控 WORKLOAD_DEPT（修复前 5281）')
inds = ind_body.get('indicators') or []
assert len(inds) == 1 and inds[0].get('code') == 'WORKLOAD_DEPT' and inds[0].get('name') == '送检科室工作量' and inds[0].get('available') is True, f'{inds}'
ind = inds[0]
assert ind.get('anchorField') == 'path_specimen.collected_at' and '（未知科室）' in str(ind.get('caveat')), f'按登记时刻落窗、口径写明未知科室归法：{ind.get("anchorField")} / {ind.get("caveat")}'
rows = ind.get('rows') or []
assert rows and list(rows[0]) == ['dept_name', 'registered', 'issued', 'rejected', 'in_progress'], f'汇总行列序：{rows[:1]}'
names = [r.get('dept_name') for r in rows]
assert len(names) == len(set(names)), f'一科一行：{names}'
mine = next((r for r in rows if r.get('dept_name') == DEPT1), None)
assert mine, f'**本科室「{DEPT1}」必须有一行**（本套刚在该科室登记了十余条）：{names}'
# 本套在该科室登记：s1 s2 / sA sB sC / sP1 sP2 sP3 sP4 / sS = 10 条；签发 sP1 sP2 sS = 3；拒收 sP3 sP4 = 2；在办 ≥ 5
assert mine['registered'] >= 10 and mine['issued'] >= 3 and mine['rejected'] >= 2 and mine['in_progress'] >= 5, f'**WORKLOAD_DEPT 非零且与本套造数相容**：{mine}'
assert mine['registered'] == mine['issued'] + mine['rejected'] + mine['in_progress'], f'三列互斥之和 = 登记数：{mine}'
assert [r['registered'] for r in rows] == sorted((r['registered'] for r in rows), reverse=True), f'按 registered 降序：{rows}'
summ = ind.get('summary') or {}
assert list(summ) == ['registered', 'issued', 'rejected', 'in_progress', 'dept_count', 'unknown_dept'], f'合计行列：{summ}'
for col in ('registered', 'issued', 'rejected', 'in_progress'):
    assert summ[col] == sum(r[col] for r in rows), f'summary.{col} = 各行之和：{summ} / {rows}'
assert summ['dept_count'] == len(rows) and summ['registered'] == summ['issued'] + summ['rejected'] + summ['in_progress'], f'{summ}'
reg_ind = ok(api('GET', f'/path-qc/indicators?indicator=WORKLOAD_REGISTER&from={f_}&to={to_}'), '质控 WORKLOAD_REGISTER')['indicators'][0]
assert reg_ind['summary']['registered'] == summ['registered'] and reg_ind['summary']['rejected'] == summ['rejected'], (
    f'**与 WORKLOAD_REGISTER 同窗同分母**（科室维度只换分组不换总体）：{reg_ind["summary"]} vs {summ}')
cat = ok(api('GET', '/path-qc/catalog'), '指标目录')
assert any(c.get('code') == 'WORKLOAD_DEPT' and c.get('available') is True for c in cat.get('indicators') or []), '目录含 WORKLOAD_DEPT'
assert any(i.get('code') == 'WORKLOAD_DEPT' for i in ok(api('GET', f'/path-qc/indicators?from={f_}&to={to_}'), '全量 indicators').get('indicators') or []), '全量 indicators 含 WORKLOAD_DEPT'
# 穿透：复用 SPEC_SELECT + stage；dept 过滤只对本指标生效
det = ok(api('GET', f'/path-qc/detail?indicator=WORKLOAD_DEPT&from={f_}&to={to_}&dept={q(DEPT1)}'), '穿透（按科室）')
assert det.get('dept') == DEPT1, f'生效的过滤回带：{det.get("dept")!r}'
ditems = det.get('items') or []
assert ditems and all(x.get('dept_name') == DEPT1 for x in ditems), f'过滤后只有本科室行：{[x.get("dept_name") for x in ditems][:5]}'
for k in ('specimen_id', 'path_no', 'barcode', 'patient_name', 'dept_name', 'collected_at', 'report_issued_at', 'rejected_at', 'stage'):
    assert k in ditems[0], f'穿透明细缺列 {k}：{sorted(ditems[0])}'
stage = {x.get('specimen_id'): x.get('stage') for x in ditems}
assert stage.get(sP1) == '已签发' and stage.get(sP3) == '已拒收' and stage.get(s2) == '在办', f'**stage 与汇总三列同判据**：{stage.get(sP1)} / {stage.get(sP3)} / {stage.get(s2)}'
assert det.get('truncated') is True or len(ditems) >= 10, f'本套 10 条都该在（或已按 200 上限截断）：{len(ditems)} / {det.get("truncated")}'
other = ok(api('GET', f'/path-qc/detail?indicator=WORKLOAD_REGISTER&from={f_}&to={to_}&dept={q(DEPT1)}'), '穿透（非本指标带 dept）')
assert 'dept' not in other, '非本指标不该假装套用了科室过滤'


def csv_text(path):
    text = api('GET', path, raw=True).lstrip(chr(0xFEFF))
    assert text.startswith('指标编码,'), f'CSV 首行应是指标元信息：{text[:80]!r}'
    assert '（该统计区间内无数据）' not in text
    return text


csv_ind = csv_text(f'/path-qc/indicators.csv?indicator=WORKLOAD_DEPT&from={f_}&to={to_}')
assert '科室,登记标本数,签发份数,拒收数,在办数' in csv_ind, f'**汇总 CSV 表头走 zh() 中文**：{csv_ind[:300]!r}'
assert f'{DEPT1},{mine["registered"]},{mine["issued"]},{mine["rejected"]},{mine["in_progress"]}' in csv_ind, 'CSV 与页面同口径'
csv_det = csv_text(f'/path-qc/detail.csv?indicator=WORKLOAD_DEPT&from={f_}&to={to_}&dept={q(DEPT1)}')
assert f'科室过滤：{DEPT1}' in csv_det and ',办理阶段' in csv_det and ',已签发' in csv_det and ',已拒收' in csv_det, f'穿透 CSV 页脚写明过滤、表头含 stage 中文：{csv_det[:400]!r}'

# 口径文本：后端 caveats / coverage note 原样带 Markdown 标记是**后端事实**（本控制器不改文本）；去标记是前端 mdText 的事——
# 把 format.ts 的 mdText 三条 .replace 规则逐条解析、在 Python 里按相同语义回放（照抄 V60DeptDimensionTest (e)）
caveats = ind_body.get('caveats') or []
assert caveats and any('**' in c for c in caveats), f'后端口径文本本该带 ** 强调（否则 mdText 无事可做）：{caveats}'
cov = ind_body.get('coverage') or {}
assert isinstance(cov, dict) and cov and all(isinstance(sec, dict) and sec.get('note') for sec in cov.values()), f'覆盖率各节 note 存在（前端 :title="mdText(sec.note)" 的数据源）：{list(cov)}'
assert any('**' in str(sec.get('note')) for sec in cov.values()), f'覆盖率 note 原样带 **（后端事实）：{[sec.get("note")[:40] for sec in cov.values()]}'
_format_ts = read_text(os.path.join(_PANEL_DIR, 'format.ts'))
_qc_view = read_text(os.path.join(_PANEL_DIR, 'PathQcView.vue'))
_qc_java = read_text(os.path.join('modules', 'medtech', 'src', 'main', 'java', 'cn', 'hip', 'medtech', 'web', 'PathQcController.java'))


def md_text_rules(ts):
    """format.ts 里 export function mdText 函数体内的全部 .replace(/re/flags, '…') 规则（剥注释后、括号配对截函数体）"""
    code = strip_js_comments(ts)
    m = re.search(r'export\s+function\s+mdText\s*\(', code)
    assert m, 'format.ts 没有 export function mdText('
    open_ = code.index('{', m.end())
    depth, quote, close = 0, None, None
    for i in range(open_, len(code)):
        ch = code[i]
        if quote:
            if ch == '\\':
                continue
            if ch == quote:
                quote = None
            continue
        if ch in ('\'', '"', '`'):
            quote = ch
        elif ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                close = i
                break
    assert close, 'mdText 函数体括号不配对'
    body = code[open_ + 1:close]
    return re.findall(r"\.replace\(\s*/((?:\\.|[^/\\\n])+)/([a-z]*)\s*,\s*'((?:\\.|[^'\\])*)'\s*\)", body)


def md_apply(rules, s):
    for regex, flags, repl in rules:
        fl = (re.M if 'm' in flags else 0) | (re.I if 'i' in flags else 0) | (re.S if 's' in flags else 0)
        s = re.sub(regex, repl if '$' in repl else repl.replace('\\', '\\\\'), s, count=0 if 'g' in flags else 1, flags=fl)
    return s


rules = md_text_rules(_format_ts)
assert len(rules) >= 3 and sum(1 for r in rules if '\\*\\*' in r[0]) == 1, f'mdText 至少三条规则（** / 反引号 / 行首列表符）、恰一条 ** 规则：{rules}'
assert md_apply(rules, '**a** b') == 'a b' and md_apply(rules, '`x` y') == 'x y' and md_apply(rules, '- 项一\n- 项二') == '项一\n项二', f'规则回放：{rules}'
assert md_apply(rules, 'a - b（行中破折号不是列表符）') == 'a - b（行中破折号不是列表符）' and md_apply(rules, '纯文本原样') == '纯文本原样'
for c in list(caveats) + [str(sec.get('note')) for sec in cov.values()] + [str(ind.get('caveat')), str(ind_body.get('windowAnchorNote'))]:
    plain = md_apply(rules, c)
    assert '**' not in plain and '`' not in plain, f'**去标记后零星号零反引号**：{plain!r}'
for c in caveats:   # 对照组同 JUnit (e)：catalog/indicators 的 caveats 去掉的只有标记、文字一字不少
    assert len(md_apply(rules, c)) == len(c.replace('**', '').replace('`', '')), f'只去标记不动文字：{c!r}'
no_star = [r for r in rules if '\\*\\*' not in r[0]]
assert md_apply(no_star, '**a** b') == '**a** b', '探针：去掉 ** 规则星号就留在屏幕上（修复前的样子）'
# PathQcView 剥注释后：模板不用 v-html、{{ c }} / {{ f }} 只经 mdText、九个口径表达式每处上屏绑定都包在 mdText( 里（照抄 V60DeptDimensionTest.mdViolations）
_tpl = _qc_view[:_qc_view.index('<script')]
_tpl = re.sub(r'v-(?:else-)?if="[^"]*"', '', strip_html_comments(_tpl))
assert 'v-html' not in _tpl, '口径文本一律纯文本插值，不用 v-html'
assert not re.search(r'\{\{\s*c\s*\}\}', _tpl) and re.search(r'\{\{\s*mdText\(\s*c\s*\)\s*\}\}', _tpl), '**caveats 不再 {{ c }} 直出、改 {{ mdText(c) }}**（修复前形态）'
assert not re.search(r'\{\{\s*f\s*\}\}', _tpl) and re.search(r'\{\{\s*mdText\(\s*f\s*\)\s*\}\}', _tpl), 'missingFields 同理'
for e in ('sec.note', 'thresholds.holidayNote', 'thresholds.receiveThresholdNote', 'ind.unavailableReason', 'ind.anchor', 'ind.caveat',
          'ind.rowsTruncatedNote', 'detailUnavailable', 'detailCaveat'):
    total = len(re.findall(r'(?<![\w.$])' + re.escape(e) + r'\b', _tpl))
    wrapped = len(re.findall(r'mdText\(\s*' + re.escape(e) + r'\b', _tpl))
    assert total > 0 and wrapped == total, f'**{e}：{total} 处上屏绑定只有 {wrapped} 处经 mdText**（修复前 :title="sec.note" 等直出）'
assert 'stripStars' not in strip_js_comments(_qc_view[_qc_view.index('<script'):]), '旧的 stripStars 已由 mdText 取代，不留两套'
# 前端 ZH ⊇ 后端 zh()：本指标真返回的每个键 + 车道 B 契约列（照抄 e2e-v59-audit [4] 的两侧解析）


def backend_label(key, src=_qc_java):
    m = re.search(r'case\s+"' + re.escape(key) + r'"\s*->\s*"([^"]*)"', src)
    return m.group(1) if m else None


def zh_label(key, src=_format_ts):
    m = re.search(r'(?m)^\s*' + re.escape(key) + r"\s*:\s*'([^']*)'", src)
    return m.group(1) if m else None


for key in list(rows[0]) + list(summ) + ['stage', 'sampled_block_count', 'blocks_derived', 'attached_stain_name']:
    be, fe = backend_label(key), zh_label(key)
    assert be, f'后端 zh() 缺 case "{key}"'
    assert fe == be, f'**前端 ZH.{key} 须与后端 zh() 同名 case 逐字一致**：前端 {fe!r} 后端 {be!r}'
assert backend_label('in_progress') == '在办数' and backend_label('stage') == '办理阶段' and zh_label('blocks_derived') == '关联蜡块', '契约措辞'
assert zh_label('attached_stain') == zh_label('attached_stain_name') == '挂接切片染色', '编码版与中文版同一个表头叫法'
assert backend_label('zz_probe_none') is None and zh_label('zz_probe_none') is None, '探针：不存在的键两侧都解析不出'
print('[3] 2576 尾 OK（**三部位（一拒收）：第 1 部位签发 orderExecuted=false、partsPending=1、医生站仍 CHARGED；中途仍可登记第 4 部位；'
      '第 2 部位签发 → EXECUTED、partsPending=0** / 重复 · 拒收部位签发 5261 / EXECUTED 后登记 5201 / 单部位签发即 EXECUTED / '
      f'**WORKLOAD_DEPT 本科室行 {mine}、与 WORKLOAD_REGISTER 同分母、穿透按 dept 过滤且 stage 三态、CSV 表头中文** / '
      '**后端 caveats · coverage note 原样带 **（事实），mdText 规则回放后零星号；PathQcView 九个口径绑定全经 mdText、无 v-html** / ZH ⊇ zh()）')

# ===========================================================================
# 4) 边界：V167 零回填——剥注释后无顶层 update / insert 形态；对照组 V22:40 / V161 抓得到；探针证明扫描器在咬
# ===========================================================================
_mig = os.path.join(_root, 'server', 'src', 'main', 'resources', 'db', 'migration')


def read_sql(name):
    with open(os.path.join(_mig, name), encoding='utf-8') as f:
        return f.read()


def strip_sql_comments(sql):
    """先剥块注释（斜杠星 … 星斜杠），再剥每行 -- 之后的部分——照抄 V60ResampleProgressTest.stripSqlComments"""
    no_block = re.sub(r'/\*.*?\*/', '', sql, flags=re.S)
    return '\n'.join(ln.split('--', 1)[0] for ln in no_block.splitlines())


# 只认**语法形态**：行首（允许缩进）update <表> set / insert into <表>。不匹配裸 token——注释里「零条 update」这句话本身就含它
TOP_LEVEL_UPDATE = re.compile(r'^\s*update\s+\w+\s+set\b', re.I | re.M)
TOP_LEVEL_INSERT = re.compile(r'^\s*insert\s+into\s+\w+', re.I | re.M)
v167 = read_sql('V167__path_block_tech_order.sql')
body = strip_sql_comments(v167)
assert 'alter table path_block add column tech_order_id bigint references path_tech_order (id)' in body, '读到的不是 V167 本尊：path_block.tech_order_id 外键列'
assert 'create index idx_path_block_tech_order on path_block (tech_order_id) where tech_order_id is not null' in body, '部分索引'
assert '零条 update' in v167, 'V167 注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本'
assert '永远为 NULL' in v167, '头注释必须写明零回填后果：历史补取材块永远 NULL'
assert not TOP_LEVEL_UPDATE.search(body), '**V167 不许有任何 update 语句**：历史补取材块不按时间 / 描述反猜医嘱，历史医嘱不回写 block_id'
assert not TOP_LEVEL_INSERT.search(body), '**V167 不许 insert 任何行**'
# 活的对照组：V22 第 40 行 `update md_drug set abx_level = 1 where antibiotic;`——扫描器抓不到就是扫描器坏了；V161 确有 insert into sys_config
v22 = read_sql('V22__phase25_mgmt.sql')
line40 = v22.splitlines()[39]
assert line40.startswith('update md_drug set abx_level = 1'), f'对照组 V22:40 不是预期那条 update：{line40!r}'
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v22)), '对照组 V22 确有 update md_drug set …，扫描器没抓到就是扫描器坏了'
assert TOP_LEVEL_INSERT.search(strip_sql_comments(read_sql('V161__timeliness_gate_seed.sql'))), '对照组 V161 确有 insert into sys_config，扫描器没抓到就是扫描器坏了'
# 探针：扫描器真的在咬
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v167 + '\nupdate path_block set tech_order_id = (select id from path_tech_order limit 1);\n')), '补一条回填语句后必须被抓到'
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v167 + '\n    UPDATE path_tech_order SET block_id = 1;\n')), '大小写与缩进不影响'
assert TOP_LEVEL_INSERT.search(strip_sql_comments(v167 + "\ninsert into path_block(specimen_id, block_no, block_code) values (1, 1, 'x');\n")), 'insert 同样抓得到'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(v167 + '\n-- update path_block set tech_order_id = 1;\n')), '行注释里的不算'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(v167 + '\n/* update path_block\n   set tech_order_id = 1; */\n')), '块注释里的不算'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments("comment on column path_block.tech_order_id is 'update set 之类的字样';")), '裸 token 不算'
assert not TOP_LEVEL_INSERT.search(strip_sql_comments(v167 + '\n-- insert into path_block ...\n')), '行注释里的 insert 不算'
print('[4] 边界 OK（**V167 剥注释后零顶层 update、零 insert** / 对照组 V22:40 与 V161 抓得到 / 探针证明扫描器在咬）')

print('\ne2e-v60-audit 全部通过 ✅')
