# -*- coding: utf-8 -*-
"""v57 核账 E2E：病理四条契约（2530 / 2563 / 2576 / 2558）在**真实服务**上逐条对账。

本套的由来不是需求，是核账：v57 三条车道（A 2530、C 2563、D 2576）各自的 JUnit 都绿了，
但 JUnit 直接调控制器方法、跑在一个回滚事务里——**线格式、序列化、JWT、跨端点状态机**
一个都没走过。本套用 HTTP 把四条契约再钉一遍，断言全是**修复前主控实测过的反向事实**：
  [1] 2530 ——修复前 diagnose 传空 grossFinding 会把取材写的大体所见**静默抹掉**；
  [2] 2563 ——修复前取消不收 body、cancel_reason/cancelled_at 三列不存在，切片与医嘱互不认识；
  [3] 2576 ——修复前 status=DIAGNOSED 即出现在患者端，未签发的诊断患者先于医生签字看到；
  [4] 2558 ——前端 utils/date 只对**带偏移**的线格式换算到 Asia/Shanghai；后端 timestamptz
             实测线格式 2026-09-08T05:00:44.229+00:00，若哪天改成无偏移，这里先红。

助手逐字抄自 e2e-v55-reach.py（已验证能跑通）；调用形态照抄 e2e-v48-pathology.py 与
e2e-v40-linkup.py（门户登录），不凭印象猜契约——本仓已因猜契约返工多次
（驼峰/蛇形、rows/items/groups、排班是 POST 建、开药前须先 start、登记只认 CHARGED）。
时间一律从服务端响应或业务时间线派生，**不写墙钟字面量**。
"""
import datetime
import re
import time
import urllib.parse as _u

from e2elib import call, login, new_patient, ok, today_bj  # noqa: E402

t = login()
today = today_bj()
# 线格式偏移后缀：+00:00 / +08:00 / Z 都算带偏移；裸 2026-09-08T05:00:44 不算
OFFSET_RE = re.compile(r'(Z|[+-]\d{2}:\d{2})$')


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
    而登记只认 CHARGED（5201）。本套各段都是先登记完全部部位再 diagnose / issue，口径变更不影响步骤顺序。"""
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


def primary_of(sid):
    rep = ok(api('GET', f'/pathology/report/{sid}/reports'), '报告抽屉')
    assert rep.get('primary'), f'报告抽屉应带 primary：{rep}'
    return rep['primary']


def tech_row(sid, tid):
    rows = ok(api('GET', f'/pathology/report/tech-orders?specimenId={sid}'), '技术医嘱清单').get('items') or []
    row = next((r for r in rows if r.get('id') == tid), None)
    assert row, f'清单里找不到技术医嘱 {tid}：{rows}'
    return row


def assert_offset(label, v):
    assert isinstance(v, str) and OFFSET_RE.search(v), (
        f'{label} 线格式须带偏移（+00:00 或 Z）——前端 utils/date 的 fmtDateTime 只对带偏移的值换算到 '
        f'Asia/Shanghai，裸格式会被当本地时间原样显示、差 8 小时：{v!r}')


# ===========================================================================
# 1) 2530：diagnose 空白即保留原值；显式传值仍覆盖；4552/4553 不变
# ===========================================================================
n1 = '2530核账' + uniq('')
p1 = new_patient(t, n1, sex='F')['id']
o1 = pathology_order(p1, n1)
s1, b1 = register(o1, 1, '2530 空白保留')
s2, b2 = register(o1, 2, '2530 显式覆盖')
G = '灰白组织一块 ' + uniq('G')
G2 = '报告修订大体 ' + uniq('R')
grossing(s1, G)
grossing(s2, G)
dg = ok(api('PUT', f'/pathology/specimens/{b1}/diagnose',
            {'grossFinding': '', 'microFinding': '镜下 2530', 'diagnosis': '2530 诊断'}), 'diagnose 传空大体')
assert dg.get('specimenId') == s1, f'返回体应带 specimenId：{dg}'
assert dg.get('grossKept') is True, f'入参空白且原值非空 → grossKept 必须为 true：{dg}'
assert dg.get('microKept') is False, f'镜下所见显式传了 → microKept 必须为 false：{dg}'
pr = primary_of(s1)
assert pr.get('grossFinding') == G, (
    f'**取材写的大体所见不得被空白入参抹掉**——修复前这条 update 无条件覆盖，'
    f'专科流程页传空即静默清空且无历史可回：期望 {G!r}，实际 {pr.get("grossFinding")!r}')
assert pr.get('microFinding') == '镜下 2530'
# 对照：显式传值（带首尾空白）→ trim 后覆盖，grossKept=false
dg2 = ok(api('PUT', f'/pathology/specimens/{b2}/diagnose',
             {'grossFinding': '  ' + G2 + '  ', 'microFinding': 'm2', 'diagnosis': '2530 对照'}), 'diagnose 显式传大体')
assert dg2.get('grossKept') is False, f'显式传值不算保留：{dg2}'
assert primary_of(s2).get('grossFinding') == G2, '显式传值须按 trim 后内容覆盖——工作台预填再编辑的合法路径契约不变'
assert api('PUT', f'/pathology/specimens/{b2}/diagnose', {'diagnosis': ''})['code'] == 4552, '诊断为空仍 4552'
assert api('PUT', f'/pathology/specimens/{b1}/diagnose', {'diagnosis': 'x'})['code'] == 4553, '已诊断（非 RECEIVED）仍 4553'
print('[1] 2530 OK（**空白入参保留取材大体所见 / grossKept=true** / 显式传值 trim 后覆盖 / 4552、4553 不变）')

# ===========================================================================
# 2) 2563：技术医嘱取消留痕（5271 / reason 不动）+ 切片挂接医嘱（5272 / tech_order_id / slide_count）
# ===========================================================================
n2 = '2563核账' + uniq('')
p2 = new_patient(t, n2, sex='M')['id']
o2 = pathology_order(p2, n2)
sA, bA = register(o2, 1, '2563 A')
sB, bB = register(o2, 2, '2563 B')
blockA = grossing(sA, '2563 取材 A')
R0 = '下达原因 ' + uniq('R')
tA1 = ok(api('POST', '/pathology/report/tech-orders',
             {'specimenId': sA, 'blockId': blockA, 'techType': 'IHC', 'techItem': 'CK7', 'reason': R0}), '下 IHC 医嘱')['id']
# 取消无 body → 5271，仍 ORDERED
r = api('PUT', f'/pathology/report/tech-orders/{tA1}/cancel')
assert r['code'] == 5271, f'取消原因缺失必须 5271（修复前不收 body 直接取消、原因无处留痕）：{r}'
assert api('PUT', f'/pathology/report/tech-orders/{tA1}/cancel', {'reason': '   '})['code'] == 5271, '空白原因 5271'
assert api('PUT', f'/pathology/report/tech-orders/{tA1}/cancel', {'reason': 'x' * 256})['code'] == 5271, '超 255 字 5271'
row = tech_row(sA, tA1)
assert row['status'] == 'ORDERED', f'5271 三条路径都不得改状态：{row}'
assert row.get('cancelled_at') is None and row.get('cancel_reason') is None, f'未取消不得有取消留痕：{row}'
assert row.get('reason') == R0, f'下达原因应原样：{row}'
# 带 reason → 三列落值，reason 原值不动
C = '免疫组化改做 CK20 ' + uniq('C')
c = ok(api('PUT', f'/pathology/report/tech-orders/{tA1}/cancel', {'reason': C}), '取消（带原因）')
assert c['status'] == 'CANCELLED' and c.get('cancel_reason') == C and c.get('cancelled_at'), f'取消返回体应带三列：{c}'
assert c.get('reason') == R0, f'**取消原因写 cancel_reason，下达原因 reason 一个字节不动**：{c}'
crow = tech_row(sA, tA1)
assert crow['status'] == 'CANCELLED' and crow.get('cancel_reason') == C and crow.get('cancelled_at'), f'清单行应带取消留痕：{crow}'
assert crow.get('reason') == R0, f'清单行 reason 原值不变：{crow}'
assert crow.get('cancelled_by') and crow.get('cancelled_by_name'), f'取消人须留痕（id + 姓名）：{crow}'
assert api('PUT', f'/pathology/report/tech-orders/{tA1}/cancel', {'reason': '再取消'})['code'] == 5268, '非 ORDERED 仍 5268'
assert api('PUT', '/pathology/report/tech-orders/999999999/cancel', {'reason': 'x'})['code'] == 5268, '不存在仍 5268'
# 切片挂接：另一标本的医嘱 / 已取消的医嘱 / 不存在 → 5272，一张片也不插
tA2 = ok(api('POST', '/pathology/report/tech-orders',
             {'specimenId': sA, 'blockId': blockA, 'techType': 'IHC', 'techItem': 'CK20'}), '再下一条 IHC')['id']
tB1 = ok(api('POST', '/pathology/report/tech-orders',
             {'specimenId': sB, 'techType': 'IHC', 'techItem': 'Ki-67'}), 'B 标本的医嘱')['id']
for bad, why in ((tB1, '另一标本的医嘱'), (tA1, '已取消的医嘱'), (999999999, '不存在的医嘱')):
    r = api('POST', '/pathology/process/slides', {'blockId': blockA, 'count': 2, 'stainType': 'IHC', 'techOrderId': bad})
    assert r['code'] == 5272, f'切片挂接{why}必须 5272：{r}'
assert tech_row(sA, tA2)['slide_count'] == 0, '5272 三条路径一张片也不得插'
assert tech_row(sB, tB1)['slide_count'] == 0
sl = ok(api('POST', '/pathology/process/slides',
            {'blockId': blockA, 'count': 3, 'stainType': 'IHC', 'stainItem': 'CK20', 'techOrderId': tA2}), '挂接切片')
assert sl['slideCount'] == 3 and sl.get('techOrderId') == tA2, f'切片返回体：{sl}'
assert all(s.get('tech_order_id') == tA2 for s in sl['slides']), f'**每张 path_slide.tech_order_id 都须等于医嘱 id**：{sl["slides"]}'
a2 = tech_row(sA, tA2)
assert a2['slide_count'] == 3, f'清单 slide_count 应等于张数：{a2}'
assert a2['status'] == 'ORDERED', f'挂接不自动置 DONE（切了片不等于做完）：{a2}'
plain = ok(api('POST', '/pathology/process/slides', {'blockId': blockA, 'count': 1, 'stainType': 'HE'}), '普通切片')
assert plain.get('techOrderId') is None and all(s.get('tech_order_id') is None for s in plain['slides']), (
    f'不传 techOrderId = 旧行为，tech_order_id 为 null：{plain}')
assert tech_row(sA, tA2)['slide_count'] == 3, '普通切片不计入任何医嘱'
print('[2] 2563 OK（**无 body 5271 仍 ORDERED** / 带原因三列落值、reason 不动 / **跨标本 5272** / 挂接逐张相等、slide_count 相等 / 不传为 null）')

# ===========================================================================
# 3) 2576：患者门户按「签发」发布——diagnose 后无、签发后有、report_date 就是签发时刻；旁人看不到
# ===========================================================================
PHONE, PHONE4 = '13900002576', '13900002577'
n3 = '2576核账' + uniq('')
p3 = new_patient(t, n3, sex='F', phone=PHONE)
pt = ok(call('POST', '/portal/login', {'patientNo': p3['patientNo'], 'phone': PHONE}), '患者端登录')['token']
p4 = new_patient(t, '2576旁人' + uniq(''), sex='M', phone=PHONE4)
pt4 = ok(call('POST', '/portal/login', {'patientNo': p4['patientNo'], 'phone': PHONE4}), '旁人登录')['token']
o3 = pathology_order(p3['id'], n3)
s3, b3 = register(o3, 1, '2576')
DX = '2576 诊断 ' + uniq('D')
ok(api('PUT', f'/pathology/specimens/{b3}/diagnose',
       {'grossFinding': 'g', 'microFinding': '镜下 2576', 'diagnosis': DX}), '书写诊断')


def portal_path_row(token):
    rows = ok(call('GET', '/portal/my/exam-reports', token=token), '患者端报告')
    return next((r for r in rows if r.get('report_type') == 'PATH' and r.get('conclusion') == DX), None)


assert portal_path_row(pt) is None, (
    '**已诊断未签发的报告不得出现在患者端**——修复前 where 是 status=DIAGNOSED，'
    '医生还没签字患者先看到了草稿')
issued = ok(api('PUT', f'/pathology/report/{s3}/issue', {}), '签发（gate=warn 缺双签放行）')
assert issued.get('reportIssuedAt'), f'签发响应应带 reportIssuedAt：{issued}'
# v59：「报告出了」= 正式签发——门诊申请在签发时才置 EXECUTED（此前 diagnose 就置了）；返回体回带这一事实
assert issued.get('orderExecuted') is True, f'签发须把门诊申请置 EXECUTED 并回带 orderExecuted=true：{issued}'
prow = portal_path_row(pt)
assert prow, '签发后患者端必须能看到该报告'
assert prow.get('report_date') == issued['reportIssuedAt'], (
    f'report_date 须就是签发时刻（修复前取 diagnosed_at）：{prow.get("report_date")!r} != {issued["reportIssuedAt"]!r}')
assert prow.get('detail') == '镜下 2576' and prow.get('item_name'), f'其余列不动：{prow}'
assert portal_path_row(pt4) is None, '**旁人的令牌不得看到他人的病理报告**（身份只从令牌主体取）'
print('[3] 2576 OK（**diagnose 后患者端无** / 签发后有且 report_date == reportIssuedAt / 旁人看不到）')

# ===========================================================================
# 4) 2558 线契约：timestamptz 字段以 +00:00 或 Z 结尾（前端 fmtDateTime 换算的前提）
# ===========================================================================
wl = ok(api('GET', f'/pathology/report/worklist?scope=any&keyword={q(b3)}&limit=50'), '阅片 any')
wrow = next((x for x in (wl.get('items') or []) if x.get('id') == s3), None)
assert wrow, f'scope=any 按条码应能搜到刚签发的标本：{wl}'
for k in ('collected_at', 'received_at', 'diagnosed_at', 'report_issued_at'):
    assert_offset(f'worklist.{k}', wrow.get(k))
assert_offset('issue.reportIssuedAt', issued['reportIssuedAt'])
assert_offset('portal.report_date', prow['report_date'])
assert_offset('techOrder.cancelled_at', crow['cancelled_at'])
assert_offset('techOrder.ordered_at', crow.get('ordered_at'))
assert_offset('reports.primary.reportIssuedAt', primary_of(s3).get('reportIssuedAt'))
print('[4] 2558 OK（worklist / issue / portal / tech-orders / reports 的 timestamptz 线格式全部带偏移）')

print('\ne2e-v57-audit 全部通过 ✅')
