# -*- coding: utf-8 -*-
"""v58 核账 E2E：病理三条契约（2530 字段级取材 / 2563 执行进度 + 完成 gate / 2576 演示造数）在**真实服务**上逐条对账。

本套的由来与 e2e-v57-audit.py 同：v58 四条车道（A 2530 V164、B 2563 V165、C 2576 demo 脚本、D 扫描器）
各自的 JUnit 都绿了，但 JUnit 直接调控制器方法、跑在一个回滚事务里——**线格式、序列化、JWT、跨端点状态机、
sys_config 缓存失效**一个都没走过。本套用 HTTP 把三条契约再钉一遍，断言全是**修复前主控实测过的反向事实**：
  [1] 2530 ——修复前取材把有序字段拼成一段文本就完了，path_gross_field / path_gross_revision **两张表不存在**，
             读端点没有 fieldsAvailable / fields / revisions 三键；诊断覆盖前的原文没有任何地方留着；
  [2] 2563 ——修复前清单只有手工三态 status、没有 progress；DONE 仅靠手点、不校验任何切片存在；
             技术医嘱的建 / 完 / 取消在 path_process 里**零行**（'TECH_ORDER' 直接撞 chk_path_process_node）；
  [3] 2576 ——修复前仓库零病理种子，QUALITY 打开质控页三项全 0、系统内无任何造数路径；
  [4] 边界 ——V164 / V165 零回填：剥注释后无 `^\\s*update <表> set` 形态（对照组 V22:40 那条真 update 能被抓到）。

助手逐字抄自 e2e-v57-audit.py（已验证能跑通）；set_gate 抄 e2e-v55-reach.py；调用形态照抄
V58GrossFieldsTest / V58TechProgressTest 钉住的 HTTP 契约，不凭印象猜契约——本仓已因猜契约返工多次
（驼峰/蛇形、rows/items/groups、排班是 POST 建、开药前须先 start、登记只认 CHARGED）。
时间一律从服务端响应或业务时间线派生，**不写墙钟字面量**。
"""
import datetime
import os
import re
import subprocess
import sys
import time
import urllib.parse as _u

from e2elib import BASE, call, login, new_patient, ok, today_bj  # noqa: E402

t = login()
today = today_bj()
TECH_DONE_GATE = 'emr.gate.pathology.techdone'


def api(m, p, b=None, **kw):
    return call(m, p, b, t, **kw)


def q(s):
    return _u.quote(s, safe='')


def uniq(pre):
    return pre + str(int(time.time() * 1000) % 100000000)


def iso(mins):
    return (datetime.datetime.now(datetime.timezone.utc)
            + datetime.timedelta(minutes=mins)).isoformat().replace('+00:00', 'Z')


def set_gate(v):
    """抄 e2e-v55-reach.py：PUT /config/{key}?value= 只改既有行（V165 已 seed），配置端点自己 evict 缓存"""
    ok(api('PUT', f'/config/{TECH_DONE_GATE}?value={v}'), f'置 {TECH_DONE_GATE}={v}')


def visited(pid, dept_id=1):
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': dept_id, 'scheduleDate': today.isoformat(), 'fee': '0.00', 'capacity': 50}), '排班')
    reg = ok(api('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}), '挂号')
    ok(api('POST', f"/outpatient/doctor/{reg['id']}/start", {}), '接诊')
    return reg['id']


def pathology_order(pid, name):
    """自建一条已收费的门诊病理申请，返回 order_id。
    在待取材队列里**按 patient_name 认领自己的**（既有 GET /pathology/pending 只回
    order_id/item_name/group_no/patient_name 四键，没有 patient_id；患者名带 uniq 后缀），
    不拿 pend[0]——脏库里 pend[0] 可能是别人的。"""
    rid = visited(pid)
    items = ok(api('GET', '/masterdata/charge-items'), '收费项目')
    cand = next((i for i in items if '病理' in (i.get('name') or '') or '活检' in (i.get('name') or '')), None)
    assert cand, '主数据无病理收费项目（主数据缺项，非功能缺陷）'
    ok(api('POST', f'/outpatient/doctor/{rid}/orders',
           {'lines': [{'orderType': 'EXAM', 'itemId': cand['id'], 'qty': 1}]}), '开病理医嘱')
    ok(api('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}), '结算')
    pend = ok(api('GET', '/pathology/pending'), '待取材')
    oid = next((p['order_id'] for p in pend if p.get('order_id') and p.get('patient_name') == name), None)
    assert oid, f'待取材队列里没有患者 {name} 刚开的病理医嘱：{pend[:3]}'
    return oid


def register(oid, part, desc):
    """登记 + 接收核对 → (specimenId, barcode)。
    **同一申请的各部位须在任一部位正式签发（PUT /pathology/report/{id}/issue）之前全部登记完**：
    v59 起签发才把 outp_order 置 EXECUTED（此前是 diagnose——医生站在报告尚未签发时就显示「已执行」），
    而登记只认 CHARGED（5201）。本套各段都是先登记完全部部位再 diagnose，口径变更不影响步骤顺序。"""
    reg = ok(api('POST', '/pathology/registry/specimens',
                 {'orderId': oid, 'partNo': part, 'specimenType': 'ROUTINE', 'specimenDesc': desc,
                  'samplingSite': '左乳', 'clinicalDiagnosis': '待查', 'fixative': '福尔马林',
                  'fixedAt': iso(-30), 'urgent': False}), f'登记部位 {part}')
    sid = reg.get('id') or reg.get('specimenId')
    bc = reg.get('barcode') or reg.get('barCode')
    assert sid and bc, f'登记返回体应带 id 与条码：{reg}'
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
    for k in ('fieldsAvailable', 'fields', 'revisions', 'grossFinding'):
        assert k in v, f'取材视图缺键 {k}（修复前根本没有这三个键）：{sorted(v)}'
    return v


def tech_row(sid, tid):
    rows = ok(api('GET', f'/pathology/report/tech-orders?specimenId={sid}'), '技术医嘱清单').get('items') or []
    row = next((r for r in rows if r.get('id') == tid), None)
    assert row, f'清单里找不到技术医嘱 {tid}：{rows}'
    return row


def assert_progress(sid, tid, progress, name, slides, stained):
    """标本清单 + 全院清单（status=ALL，按条码 keyword）两处同口径——照抄 V58TechProgressTest.assertProgress"""
    row = tech_row(sid, tid)
    assert row.get('progress') == progress and row.get('progress_name') == name, (
        f'标本清单 progress 应为 {progress}/{name}（修复前清单只有手工三态 status、没有 progress）：{row}')
    assert row.get('slide_count') == slides and row.get('stained_count') == stained, (
        f'标本清单 slide_count/stained_count 应为 {slides}/{stained}：{row}')
    wide = ok(api('GET', f"/pathology/report/tech-orders?status=ALL&keyword={q(row['barcode'])}&limit=200"),
              '全院清单').get('items') or []
    wrow = next((r for r in wide if r.get('id') == tid), None)
    assert wrow, f'全院清单 status=ALL 按条码应能搜到医嘱 {tid}'
    assert (wrow.get('progress'), wrow.get('slide_count'), wrow.get('stained_count')) == (progress, slides, stained), (
        f'全院清单与标本清单须同口径：{wrow}')
    return row


def tech_order(sid, block_id, tech_type, item, reason=None):
    body = {'specimenId': sid, 'blockId': block_id, 'techType': tech_type, 'techItem': item}
    if reason:
        body['reason'] = reason
    c = ok(api('POST', '/pathology/report/tech-orders', body), f'下 {tech_type} 医嘱 {item}')
    assert c.get('progress') == 'PENDING_SECTION' and c.get('progressName') == '待切片', (
        f'刚下达的医嘱返回体应带派生进度 PENDING_SECTION/待切片：{c}')
    return c['id']


def attach_slides(block_id, tid, count, item):
    sl = ok(api('POST', '/pathology/process/slides',
                {'blockId': block_id, 'count': count, 'stainType': 'IHC', 'stainItem': item, 'techOrderId': tid}),
            f'挂接 {count} 片到医嘱 {tid}')
    assert sl.get('techOrderId') == tid and len(sl['slides']) == count, f'切片返回体：{sl}'
    assert all(s.get('tech_order_id') == tid for s in sl['slides']), f'每张 path_slide.tech_order_id 都须等于医嘱 id：{sl}'
    return [s['id'] for s in sl['slides']]


def stain(slide_id):
    ok(api('PUT', f'/pathology/process/slides/{slide_id}/stain', {'quality': 'GOOD'}), f'染色 {slide_id}')


# ===========================================================================
# 1) 2530：取材字段按入参顺序落库 + 修订留痕（V164）
# ===========================================================================
n1 = '2530字段' + uniq('')
p1 = new_patient(t, n1, sex='F')['id']
o1 = pathology_order(p1, n1)
s1, b1 = register(o1, 1, 'v58 字段顺序 + 诊断覆盖')
s2, b2 = register(o1, 2, 'v58 空白保留')
# 会被字符序打乱的三个中文字段名：质(U+8D28) > 大(U+5927)，颜(U+989C) 最大——字符序是 大小 → 质地 → 颜色
FIELDS = {'质地': '质硬', '大小': '3×2×1cm', '颜色': '灰白'}
LABELS = list(FIELDS)
assert sorted(LABELS) != LABELS, '对照：这三个字段名按字符序排会被打乱，否则本段的顺序断言什么都证明不了'
FREE = '切面实性 ' + uniq('F')
EXPECTED_G = '；'.join(f'{k}：{v}' for k, v in FIELDS.items()) + '。' + FREE

gr = ok(api('POST', '/pathology/process/grossing',
            {'specimenId': s1, 'gross': FIELDS, 'grossText': FREE, 'blocks': [{'tissueDesc': '肿物中心'}]}), '取材（三字段 + 自由文本）')
G = gr.get('grossFinding')
assert G == EXPECTED_G, f'拼好的文本契约与 v57 前逐字一致（字段按入参序、「；」相连、「。」接自由文本）：{G!r}'
assert gr.get('grossFindingWritten') is True and gr.get('grossFieldCount') == 3, (
    f'返回体应带 grossFindingWritten=true、grossFieldCount=3（修复前没有 grossFieldCount 这个键）：{gr}')

v1 = grossing_view(s1)
assert v1['fieldsAvailable'] is True, f'**取材传了字段就必须 fieldsAvailable=true**（修复前字段落库即丢失）：{v1}'
assert v1['grossFinding'] == G
fields = v1['fields']
assert [f.get('label') for f in fields] == LABELS, (
    f'**fields 顺序须与入参一致（seq 序），不是字符序**：期望 {LABELS}，实际 {[f.get("label") for f in fields]}')
assert [f.get('label') for f in fields] != sorted(LABELS), '对照：读出来的顺序确实不是字符序'
assert [f.get('seq') for f in fields] == [1, 2, 3], f'seq 从 1 连续：{fields}'
assert [f.get('value') for f in fields] == list(FIELDS.values()), f'值原样：{fields}'
assert all(f.get('createdAt') and f.get('operatorId') and f.get('operatorName') for f in fields), f'每行带时刻与操作人：{fields}'
revs = v1['revisions']
assert len(revs) == 1, f'取材首写恰好一条修订（修复前 path_gross_revision 表不存在）：{revs}'
assert revs[0].get('seq') == 1 and revs[0].get('oldText') is None and revs[0].get('newText') == G, f'首写 old=null new=拼好的文本：{revs[0]}'
assert revs[0].get('source') == 'GROSSING' and revs[0].get('changedBy') and revs[0].get('changedByName'), f'source=GROSSING 且带人：{revs[0]}'

# diagnose 传不同的 G2 → 覆盖 + 修订 seq2(old=G, new=G2, DIAGNOSE)、grossRevised=true
G2 = '报告修订大体 ' + uniq('R')
dg = ok(api('PUT', f'/pathology/specimens/{b1}/diagnose',
            {'grossFinding': '  ' + G2 + '  ', 'microFinding': '镜下 v58', 'diagnosis': 'v58 诊断'}), 'diagnose 传不同大体')
assert dg.get('grossRevised') is True and dg.get('grossKept') is False, (
    f'覆盖成不同文本 → grossRevised=true、grossKept=false（修复前返回体没有 grossRevised）：{dg}')
assert dg.get('specimenId') == s1 and 'microKept' in dg, f'v57 三个既有键不动：{dg}'
v1b = grossing_view(s1)
assert v1b['grossFinding'] == G2, f'显式传值仍覆盖且 trim（v57 契约不变）：{v1b["grossFinding"]!r}'
revs = v1b['revisions']
assert len(revs) == 2, f'取材首写 + 诊断覆盖 = 两版：{revs}'
assert [r.get('source') for r in revs] == ['GROSSING', 'DIAGNOSE'], f'来源顺序：{revs}'
assert revs[1].get('seq') == 2 and revs[1].get('oldText') == G and revs[1].get('newText') == G2, (
    f'**第 2 条 old_text 必须是覆盖前的原文、new_text 是覆盖后的值**（修复前覆盖前的原文没有任何地方留着）：{revs[1]}')
assert revs[1].get('changedBy') and revs[1].get('changedByName'), f'修订带人：{revs[1]}'
assert v1b['fieldsAvailable'] is True and [f.get('label') for f in v1b['fields']] == LABELS, '字段行是取材时的事实，不因诊断覆盖而动'
# 已诊断再诊断仍 4553（状态守卫未动），且不再多出修订——仍 2 条
assert api('PUT', f'/pathology/specimens/{b1}/diagnose', {'grossFinding': '再改一次', 'diagnosis': 'x'})['code'] == 4553, '已诊断仍 4553'
assert len(grossing_view(s1)['revisions']) == 2, '4553 路径不写修订，仍 2 条'

# 另一标本：取材同三字段 → 1 条；diagnose 空白 → grossKept=true、grossRevised=false、修订不增
gr2 = ok(api('POST', '/pathology/process/grossing',
             {'specimenId': s2, 'gross': FIELDS, 'grossText': FREE, 'blocks': [{'tissueDesc': '肿物周边'}]}), '取材（第二标本）')
assert gr2.get('grossFinding') == G
assert len(grossing_view(s2)['revisions']) == 1
dg2 = ok(api('PUT', f'/pathology/specimens/{b2}/diagnose',
             {'grossFinding': '  \t ', 'microFinding': 'm2', 'diagnosis': 'v58 空白保留'}), 'diagnose 传空白大体')
assert dg2.get('grossKept') is True and dg2.get('grossRevised') is False, (
    f'空白入参 → 保留原值、grossKept=true、grossRevised=false（没有变化就没有版本）：{dg2}')
v2 = grossing_view(s2)
assert v2['grossFinding'] == G, f'**取材写的大体所见不得被空白入参抹掉**（v57 2530 契约不变）：{v2["grossFinding"]!r}'
assert len(v2['revisions']) == 1 and v2['revisions'][0].get('source') == 'GROSSING', f'空白保留：修订仍只有取材那一条：{v2["revisions"]}'
assert v2['fieldsAvailable'] is True and [f.get('label') for f in v2['fields']] == LABELS
assert api('GET', '/pathology/process/grossing/999999999')['code'] == 5220, '查无此标本沿用既有 5220'
print('[1] 2530 OK（**fields 按入参序而非字符序 / fieldsAvailable=true / 首写 GROSSING 一条** / '
      '**diagnose 覆盖 → seq2 old==G new==G2 DIAGNOSE、grossRevised=true** / 4553 与空白路径修订不增、grossKept=true）')

# ===========================================================================
# 2) 2563：执行进度五态派生 + 完成校验 gate（off/warn/block）+ 建/完/取消进流转节点（V165）
# ===========================================================================
n2 = '2563进度' + uniq('')
p2 = new_patient(t, n2, sex='M')['id']
o2 = pathology_order(p2, n2)
sA, bA = register(o2, 1, 'v58 进度')
blockA = grossing(sA, 'v58 取材 A')
R1 = 'HE 见腺样结构，查 CK7 定来源 ' + uniq('R')

# ① 下 IHC 医嘱 → PENDING_SECTION（0/0）
t1 = tech_order(sA, blockA, 'IHC', 'CK7', R1)
assert_progress(sA, t1, 'PENDING_SECTION', '待切片', 0, 0)
# ② 切 2 片挂接 → SECTIONING（2/0）；染 1 片仍 SECTIONING（2/1）；染完 → STAINED（2/2）
slides1 = attach_slides(blockA, t1, 2, 'CK7')
assert_progress(sA, t1, 'SECTIONING', '切片中', 2, 0)
stain(slides1[0])
assert_progress(sA, t1, 'SECTIONING', '切片中', 2, 1)
stain(slides1[1])
assert_progress(sA, t1, 'STAINED', '已染色待确认', 2, 2)
# ③ 染完后 done（gate 出厂 warn）→ code 0、warnings 为空、两个事实随体
d1 = ok(api('PUT', f'/pathology/report/tech-orders/{t1}/done', {}), '完成（染完）')
assert d1.get('warnings') == [], f'染完再确认：warnings 必须是空数组（不是 null）：{d1}'
assert d1.get('slideCount') == 2 and d1.get('stainedCount') == 2 and d1.get('stainedComplete') is True, (
    f'返回体带 slideCount/stainedCount/stainedComplete 事实（修复前 DONE 不看任何切片）：{d1}')
assert d1.get('status') == 'DONE' and d1.get('progress') == 'DONE' and d1.get('techDoneGate') == 'warn', f'{d1}'
assert_progress(sA, t1, 'DONE', '已完成', 2, 2)
# ④ 另一条 0 片直接 done（gate 出厂 warn）→ code 0 且 warnings 非空、slideCount=0
t2 = tech_order(sA, blockA, 'IHC', 'CK20')
d2 = ok(api('PUT', f'/pathology/report/tech-orders/{t2}/done', {}), '完成（0 片，warn）')
assert d2.get('slideCount') == 0 and d2.get('stainedCount') == 0 and d2.get('stainedComplete') is False, f'{d2}'
assert isinstance(d2.get('warnings'), list) and len(d2['warnings']) == 1, (
    f'**warn 且 0 片：放行但 warnings 恰一条**（修复前 DONE 仅靠手点、什么都不说）：{d2}')
assert d2['warnings'][0].startswith('无已染色挂接切片即确认完成（gate=warn 放行）'), f'告警文案：{d2["warnings"]}'
assert d2.get('techDoneGate') == 'warn' and d2.get('status') == 'DONE', f'{d2}'
assert_progress(sA, t2, 'DONE', '已完成', 0, 0)
# ⑤ gate=block：第三条 0 片 done → 5273、清单仍 ORDERED/PENDING_SECTION；第四条「有片未染完」是 5273 的第二条路径
t3 = tech_order(sA, blockA, 'IHC', 'Ki-67')
t4 = tech_order(sA, blockA, 'IHC', 'P53')
slides4 = attach_slides(blockA, t4, 2, 'P53')
stain(slides4[0])
try:
    set_gate('block')
    r = api('PUT', f'/pathology/report/tech-orders/{t3}/done', {})
    assert r['code'] == 5273, f'**block 且无挂接切片必须 5273**（修复前任何档位都直接 DONE）：{r}'
    assert '=block' in (r.get('message') or ''), f'5273 消息应说明 gate=block：{r}'
    row3 = assert_progress(sA, t3, 'PENDING_SECTION', '待切片', 0, 0)
    assert row3.get('status') == 'ORDERED' and row3.get('done_at') is None and row3.get('done_by') is None, (
        f'被拦后行仍 ORDERED、done 三列为空：{row3}')
    r4 = api('PUT', f'/pathology/report/tech-orders/{t4}/done', {})
    assert r4['code'] == 5273 and '尚未染色' in (r4.get('message') or ''), f'挂接 2 片染 1 片：5273 且指出未染色片数：{r4}'
    assert_progress(sA, t4, 'SECTIONING', '切片中', 2, 1)
    # 同一档位染完即放行：证明 5273 拦的是事实缺口，不是档位本身
    stain(slides4[1])
    d4 = ok(api('PUT', f'/pathology/report/tech-orders/{t4}/done', {}), '完成（block，染完）')
    assert d4.get('warnings') == [] and d4.get('techDoneGate') == 'block', f'block 档染完即放行、无告警：{d4}'
finally:
    set_gate('warn')
# 恢复 warn 后：0 片 done 又回到「放行 + 告警」——证明恢复真的生效（否则后面的套件全被 block 拦）
t5 = tech_order(sA, blockA, 'IHC', 'CD20')
d5 = ok(api('PUT', f'/pathology/report/tech-orders/{t5}/done', {}), '完成（恢复 warn 后 0 片）')
assert d5.get('techDoneGate') == 'warn' and len(d5.get('warnings') or []) == 1, f'恢复 warn 须真的生效：{d5}'
# ⑥ 取消 t3（带原因）→ CANCELLED 进度
C3 = '临床撤回 ' + uniq('C')
c3 = ok(api('PUT', f'/pathology/report/tech-orders/{t3}/cancel', {'reason': C3}), '取消（带原因）')
assert c3.get('status') == 'CANCELLED' and c3.get('progress') == 'CANCELLED' and c3.get('progressName') == '已取消', f'{c3}'
assert_progress(sA, t3, 'CANCELLED', '已取消', 0, 0)
# ⑦ 流转轨迹：TECH_ORDER / TECH_DONE / TECH_CANCEL 三类节点都查得到（修复前 path_process 里零行）
tr = ok(api('GET', f'/pathology/process/trail/{sA}'), '流转轨迹')
nodes = tr.get('nodes') or []
by_node = {n: [x for x in nodes if x.get('node') == n] for n in ('TECH_ORDER', 'TECH_DONE', 'TECH_CANCEL')}


def has(node, tid):
    """按 remark 里的「#id 」定位（「#12 」不会误配「#123 」）——照抄 V58TechProgressTest.nodeOrNull"""
    return next((x for x in by_node[node] if f'#{tid} ' in (x.get('remark') or '')), None)


assert len(by_node['TECH_ORDER']) == 5, f'五次下达 = 五条 TECH_ORDER（修复前零行）：{by_node["TECH_ORDER"]}'
assert len(by_node['TECH_DONE']) == 4, f'四次成功完成（t1/t2/t4/t5）= 四条 TECH_DONE：{by_node["TECH_DONE"]}'
assert len(by_node['TECH_CANCEL']) == 1, f'一次取消 = 一条 TECH_CANCEL：{by_node["TECH_CANCEL"]}'
for tid in (t1, t2, t3, t4, t5):
    assert has('TECH_ORDER', tid), f'医嘱 {tid} 的 TECH_ORDER 节点缺失：{by_node["TECH_ORDER"]}'
o1n = has('TECH_ORDER', t1)
assert 'IHC' in o1n['remark'] and 'CK7' in o1n['remark'] and R1 in o1n['remark'], f'TECH_ORDER 备注带「#id 类型 项目」与下达原因：{o1n}'
done1, done2 = has('TECH_DONE', t1), has('TECH_DONE', t2)
assert done1 and '挂接 2 片 / 已染色 2' in done1['remark'] and 'gate=warn' not in done1['remark'], f'染完的 TECH_DONE 备注只带事实：{done1}'
assert done2 and '挂接 0 片' in done2['remark'] and 'gate=warn 放行' in done2['remark'], (
    f'**warn 放行的 TECH_DONE 必须写明缺口与放行**（放行不等于没发生过）：{done2}')
assert has('TECH_DONE', t3) is None, f'**5273 被拦不写 TECH_DONE 节点**：{by_node["TECH_DONE"]}'
cn = has('TECH_CANCEL', t3)
assert cn and C3 in cn['remark'] and '免疫组化' in cn['remark'], f'TECH_CANCEL 备注带「#id 类型」与取消原因：{cn}'
for x in by_node['TECH_ORDER'] + by_node['TECH_DONE'] + by_node['TECH_CANCEL']:
    assert x.get('operator_id') and x.get('operator_name') and x.get('occurred_at'), f'节点须带操作人与时刻：{x}'
    assert x.get('node_name') in ('下达特检医嘱', '确认完成特检医嘱', '取消特检医嘱'), f'三类节点须有中文名：{x}'
assert tr.get('lastNode') == 'TECH_CANCEL', f'最后一个节点是刚才的取消：{tr.get("lastNode")}'
print('[2] 2563 OK（**PENDING_SECTION → SECTIONING → STAINED → DONE 由挂接切片派生** / 染完 done 无告警 / '
      '**warn 0 片放行带 warnings、slideCount=0** / **block 0 片 5273 仍 ORDERED、有片未染完 5273** / '
      '恢复 warn 生效 / **trail 里 TECH_ORDER×5、TECH_DONE×4、TECH_CANCEL×1，被拦不留节点**）')

# ===========================================================================
# 3) 2576：演示造数脚本纯走 API 跑通（--count 3 --quiet 退出码 0），质控概览三项非零
# ===========================================================================
DEMO = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'demo-pathology.py')
env = dict(os.environ)
env['HIP_E2E_BASE'] = BASE   # 子进程继承本套的目标实例（e2elib 在 import 时读它）
proc = subprocess.run([sys.executable, DEMO, '--count', '3', '--quiet'], env=env,
                      capture_output=True, text=True, encoding='utf-8', errors='replace')
assert proc.returncode == 0, (
    f'demo-pathology.py --count 3 --quiet 须退出码 0（1=业务步骤失败，2=造了数但质控页看不见）：'
    f'rc={proc.returncode}\nstdout:\n{proc.stdout[-2000:]}\nstderr:\n{proc.stderr[-2000:]}')
summary = next((ln for ln in proc.stdout.splitlines() if ln.startswith('demo-pathology OK')), None)
assert summary, f'--quiet 须只打印一行成功摘要：{proc.stdout!r}'
m = re.search(r'specimens=(\d+)', summary)
assert m and int(m.group(1)) == 4, f'--count 3 = 3 个阶梯标本 + 固定 1 个拒收 = 4：{summary}'
qc = ok(api('GET', '/path-qc/indicators'), '质控概览（默认时间窗）')
by = {i.get('code'): i for i in (qc.get('indicators') or [])}
three = {}
for code, key in (('WORKLOAD_REGISTER', 'registered'), ('WORKLOAD_BLOCK', 'blocks'), ('WORKLOAD_REPORT', 'issued_reports')):
    ind = by.get(code) or {}
    assert ind.get('available') is True, f'{code} 应 available:true：{ind}'
    v = (ind.get('summary') or {}).get(key)
    assert isinstance(v, (int, float)) and v > 0, (
        f'**{code}.summary.{key} 必须非零**（修复前空库质控页三项全 0、系统内无造数路径）：{ind.get("summary")}')
    three[f'{code}.{key}'] = v
print(f'[3] 2576 OK（**demo-pathology --count 3 --quiet 退出码 0、4 个标本** / 质控概览三项非零 {three}）\n    {summary}')

# ===========================================================================
# 4) 边界：V164 / V165 零回填——剥注释后无顶层 update 形态；对照组 V22:40 那条真 update 能被抓到
# ===========================================================================
_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_mig = os.path.join(_root, 'server', 'src', 'main', 'resources', 'db', 'migration')


def read_sql(name):
    with open(os.path.join(_mig, name), encoding='utf-8') as f:
        return f.read()


def strip_sql_comments(sql):
    """先剥块注释（斜杠星 … 星斜杠），再剥每行 -- 之后的部分——照抄 V58GrossFieldsTest.stripSqlComments"""
    no_block = re.sub(r'/\*.*?\*/', '', sql, flags=re.S)
    return '\n'.join(ln.split('--', 1)[0] for ln in no_block.splitlines())


# 只认**语法形态**：行首（允许缩进）update <表> set。不匹配裸 token——注释里「零条 update」这句话本身就含它
TOP_LEVEL_UPDATE = re.compile(r'^\s*update\s+\w+\s+set\b', re.I | re.M)
TOP_LEVEL_INSERT = re.compile(r'^\s*insert\s+into\s+\w+', re.I | re.M)
v164, v165 = read_sql('V164__path_gross_fields.sql'), read_sql('V165__path_tech_progress.sql')
assert 'create table path_gross_field' in v164 and 'create table path_gross_revision' in v164, '读到的不是 V164 本尊'
assert 'chk_path_process_node' in v165 and "'TECH_ORDER'" in v165 and "'emr.gate.pathology.techdone', 'warn'" in v165, '读到的不是 V165 本尊'
for name, sql in (('V164', v164), ('V165', v165)):
    assert '零条 update' in sql, f'{name} 注释里必须写明零回填纪律——这句本身含 update 一词，正是剥注释要处理的活样本'
    assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(sql)), f'**{name} 不许有任何 update 语句**（历史标本 / 历史医嘱零回填）'
assert not TOP_LEVEL_INSERT.search(strip_sql_comments(v164)), 'V164 不许 insert 任何行：不从 gross_finding 反解析字段行、不伪造修订历史'
assert 'on conflict (cfg_key) do nothing' in strip_sql_comments(v165), 'V165 唯一的 insert 是 gate 种子，形态须 on conflict (cfg_key) do nothing'
# 活的对照组：V22 第 40 行 `update md_drug set abx_level = 1 where antibiotic;`——扫描器抓不到就是扫描器坏了
v22 = read_sql('V22__phase25_mgmt.sql')
line40 = v22.splitlines()[39]
assert line40.startswith('update md_drug set abx_level = 1'), f'对照组 V22:40 不是预期那条 update：{line40!r}'
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v22)), '对照组 V22 确有 update md_drug set …，扫描器没抓到就是扫描器坏了'
# 探针：扫描器真的在咬
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v164 + '\nupdate path_specimen set gross_finding = btrim(gross_finding);\n')), '补一条回填语句后必须被抓到'
assert TOP_LEVEL_UPDATE.search(strip_sql_comments(v165 + '\n    UPDATE sys_config SET cfg_value = \'block\';\n')), '大小写与缩进不影响'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(v164 + '\n-- update path_specimen set gross_finding = null;\n')), '行注释里的不算'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments(v165 + '\n/* update path_process\n   set node = \'X\'; */\n')), '块注释里的不算'
assert not TOP_LEVEL_UPDATE.search(strip_sql_comments("comment on column path_gross_field.value is 'update set 之类的字样';")), '裸 token 不算'
print('[4] 边界 OK（**V164/V165 剥注释后零顶层 update、V164 零 insert** / 对照组 V22:40 抓得到 / 探针证明扫描器在咬）')

print('\ne2e-v58-audit 全部通过 ✅')
