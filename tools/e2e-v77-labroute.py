# -*- coding: utf-8 -*-
"""v77 E2E：检验申请按流向动态确定执行科室（偏离表 1016★「根据流向自动获取执行科室」）在**真实服务**上走一遍。

JUnit（V77LabRouteTest）直接调控制器方法、跑在一个回滚事务里——线格式、序列化、JWT、@PreAuthorize、
跨端点状态机一个都没走过。本套用 HTTP 把规划节「接口契约」逐条钉住：
  [1] 规则维护：POST/GET/PUT/DELETE /masterdata/lab-route-rules，五个错误码（5910–5914）在 HTTP 线上原样回；
      specimenType 落库规范化（"全 血" → "全血"）、列表带 execDeptName/chargeItemCode；/resolve 试算与开单同口径；
  [2] 开单落值：POST /outpatient/doctor/{rid}/orders 的 LAB 行返回体带 execDeptId/execDeptName（命中规则科室），
      无规则且字典无执行科室时两键不出现（@JsonInclude(NON_NULL)，返回体与 v76 逐字相同）；
  [3] 三条共享读路径：/lis/pending?deptId= 与 /lis/samples?deptId= 分流可见/不可见、行多 exec_dept_id/exec_dept_name 两列；
      /print/doc/guide-sheet 与 lab-request 行的 exec_dept_name = 规则科室（优先于字典）；
  [4] 落值即快照 + 停用回落：停用规则后再开一条同项目医嘱 → 回落收费项目字典（本库该项目无字典科室 → 键不出现），
      而此前开出的那条医嘱 exec_dept_name 仍是规则科室；
  [5] /auth/me 带 deptId/deptName 两键（admin 无科室 → null）。
自成一体：建自己的患者、自己的两个 MEDTECH 科室、自己的规则；收尾删规则、停用科室（科室无 DELETE 端点）。
时间一律从 e2elib.today_bj() 取，不写墙钟字面量。
"""
import time

from e2elib import call, login, new_patient, ok, today_bj  # noqa: E402

t = login()
today = today_bj()


def api(m, p, b=None, **kw):
    return call(m, p, b, t, **kw)


def uniq(pre):
    return pre + str(int(time.time() * 1000) % 100000000)


def fail_code(r, code, step):
    assert r['code'] == code, f'{step}: 应返回 {code}，实际 {r}'


def visited(pid, dept_id):
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': dept_id, 'scheduleDate': today.isoformat(), 'fee': '0.00', 'capacity': 50}), '排班')
    reg = ok(api('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}), '挂号')
    ok(api('POST', f"/outpatient/doctor/{reg['id']}/start", {}), '接诊')
    return reg['id']


def lab_order(rid, item_id, specimen):
    orders = ok(api('POST', f'/outpatient/doctor/{rid}/orders',
                    {'lines': [{'orderType': 'LAB', 'itemId': item_id, 'qty': 1, 'specimenType': specimen}]}),
                '开检验医嘱')
    assert len(orders) == 1, orders
    return orders[0]


def rows_of(doc_type, rid):
    return ok(api('GET', f'/print/doc/{doc_type}/{rid}'), doc_type)['rows']


# ---------- 前置：科室 / 项目 ----------
depts = ok(api('GET', '/system/depts'), '科室')
clinic = next(d for d in depts if d['type'] == 'CLINICAL' and d.get('enabled', True))
dx = ok(api('POST', '/system/depts', {'name': '流向E2E检验甲' + uniq(''), 'code': uniq('E77X'), 'type': 'MEDTECH', 'sortNo': 99}),
        '建执行科室甲')
dy = ok(api('POST', '/system/depts', {'name': '流向E2E检验乙' + uniq(''), 'code': uniq('E77Y'), 'type': 'MEDTECH', 'sortNo': 99}),
        '建执行科室乙')
items = ok(api('GET', '/masterdata/charge-items?keyword=%E8%A1%80%E5%B8%B8%E8%A7%84&category=LAB'), 'LAB 项目')
assert items, '主数据无「血常规」检验项目（主数据缺项，非功能缺陷）'
lab = items[0]
exam = next(i for i in ok(api('GET', '/masterdata/charge-items?category=EXAM'), 'EXAM 项目'))
dict_dept = lab.get('execDeptId')     # 全新库恒 None；历史库可能已配，断言按它派生

# 本地脏库复跑自净：上一次中途失败留下的 E2E 规则会让下面的 5913 判定误触，先清掉（全新 CI 库此处恒为空）
for stale in ok(api('GET', '/masterdata/lab-route-rules?includeDisabled=true'), '规则列表'):
    if str(stale.get('name', '')).startswith('E2E '):
        ok(api('DELETE', f"/masterdata/lab-route-rules/{stale['id']}"), '清理残留 E2E 规则')

# ---------- [1] 规则维护 + 错误码 ----------
rid_rule = ok(api('POST', '/masterdata/lab-route-rules',
                  {'name': 'E2E 血常规全血→甲', 'chargeItemId': lab['id'], 'specimenType': ' 全 血 ',
                   'execDeptId': dx['id'], 'priority': 50, 'remark': 'e2e'}), '建规则')['id']
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': '撞键', 'chargeItemId': lab['id'], 'specimenType': '全血', 'execDeptId': dy['id']}), 5913, '同键重复')
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': 'x', 'chargeItemId': lab['id'], 'execDeptId': 987654321}), 5911, '执行科室不存在')
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': 'x', 'chargeItemId': exam['id'], 'execDeptId': dx['id']}), 5912, '非检验类项目')
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': 'x', 'chargeItemId': lab['id'], 'orderDeptId': 987654321, 'execDeptId': dx['id']}), 5914, '开单科室不存在')
fail_code(api('PUT', '/masterdata/lab-route-rules/987654321', {'name': 'x', 'execDeptId': dx['id']}), 5910, '规则不存在')
fail_code(api('POST', '/masterdata/lab-route-rules', {'name': '', 'execDeptId': dx['id']}), 4000, '名称缺失')

rules = ok(api('GET', '/masterdata/lab-route-rules'), '规则列表')
mine = next(r for r in rules if r['id'] == rid_rule)
assert mine['specimenType'] == '全血', f'标本类型须落规范化值：{mine}'
assert mine['execDeptName'] == dx['name'] and mine['chargeItemCode'] == lab['code'] and mine['priority'] == 50, mine
for k in ('itemCategory', 'orderDeptId', 'orderDeptName', 'enabled', 'remark', 'updatedAt'):
    assert k in mine, f'列表缺键 {k}：{sorted(mine)}'

# 通配规则（项目键，具体度 4）→ 乙；带标本的（具体度 6）→ 甲；试算须按具体度取甲
rid_wild = ok(api('POST', '/masterdata/lab-route-rules',
                  {'name': 'E2E 血常规任意→乙', 'chargeItemId': lab['id'], 'execDeptId': dy['id']}), '建通配规则')['id']
rs = ok(api('GET', f"/masterdata/lab-route-rules/resolve?chargeItemId={lab['id']}&specimenType=%E5%85%A8%20%E8%A1%80"
               f"&orderDeptId={clinic['id']}"), '试算 全血')
assert rs['source'] == 'RULE' and rs['execDeptId'] == dx['id'] and rs['ruleId'] == rid_rule and rs['execDeptName'] == dx['name'], rs
rs2 = ok(api('GET', f"/masterdata/lab-route-rules/resolve?chargeItemId={lab['id']}&specimenType=%E5%B0%BF%E6%B6%B2"), '试算 尿液')
assert rs2['source'] == 'RULE' and rs2['execDeptId'] == dy['id'] and rs2['ruleId'] == rid_wild, rs2
fail_code(api('GET', '/masterdata/lab-route-rules/resolve?chargeItemId=987654321'), 5912, '试算项目不存在')
print(f'[1] 规则维护 OK（5910/5911/5912/5913/5914/4000 线上原样回；规范化落库；试算按具体度取 {dx["name"]}）')

# ---------- [2] 开单落值 ----------
pid = new_patient(t, '流向E2E' + uniq(''), 'F')['id']
rid = visited(pid, clinic['id'])
o1 = lab_order(rid, lab['id'], '全血')
assert o1.get('execDeptId') == dx['id'], f'LAB 开单返回体 execDeptId 须为规则科室：{o1}'
assert o1.get('execDeptName') == dx['name'], f'LAB 开单返回体 execDeptName 须回显规则科室名：{o1}'
o2 = lab_order(rid, lab['id'], '尿液')
assert o2.get('execDeptId') == dy['id'] and o2.get('execDeptName') == dy['name'], f'尿液 → 通配规则 → 乙：{o2}'
# EXAM 行本轮不落值
oe = ok(api('POST', f'/outpatient/doctor/{rid}/orders',
            {'lines': [{'orderType': 'EXAM', 'itemId': exam['id'], 'qty': 1}]}), '开检查医嘱')[0]
assert oe.get('execDeptId') is None and 'execDeptName' not in oe, f'EXAM 不落值、瞬态键不出现：{oe}'
print(f'[2] 开单落值 OK（全血→{dx["name"]} / 尿液→{dy["name"]} / EXAM 不落值）')

# ---------- [3] 三条共享读路径 ----------
ok(api('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}), '结算')
pend_x = ok(api('GET', f"/lis/pending?deptId={dx['id']}"), '待采样 甲')
pend_y = ok(api('GET', f"/lis/pending?deptId={dy['id']}"), '待采样 乙')
pend_all = ok(api('GET', '/lis/pending'), '待采样 全部')
ids = lambda rows: {r['order_id'] for r in rows}  # noqa: E731
assert o1['id'] in ids(pend_x) and o1['id'] not in ids(pend_y), '甲队列须只见甲的申请'
assert o2['id'] in ids(pend_y) and o2['id'] not in ids(pend_x), '乙队列须只见乙的申请'
assert {o1['id'], o2['id']} <= ids(pend_all), '不传 deptId 两条都在'
row1 = next(r for r in pend_all if r['order_id'] == o1['id'])
assert row1['exec_dept_id'] == dx['id'] and row1['exec_dept_name'] == dx['name'], row1
for k in ('group_no', 'item_name', 'patient_name', 'sex', 'specimen_type', 'sampling_site', 'urgent', 'remark'):
    assert k in row1, f'待采样队列既有列 {k} 不得丢'
assert ok(api('GET', '/lis/pending?deptId=987654321'), '待采样 不存在科室') == [], '不存在的科室 → 空'

guide = {r['id']: r for r in rows_of('guide-sheet', rid)}
assert guide[o1['id']]['exec_dept_name'] == dx['name'], f'导诊单 exec_dept_name 须为规则科室：{guide[o1["id"]]}'
assert guide[o2['id']]['exec_dept_name'] == dy['name'], guide[o2['id']]
labreq = {r['id']: r for r in rows_of('lab-request', rid)}
assert labreq[o1['id']]['exec_dept_name'] == dx['name'] and labreq[o2['id']]['exec_dept_name'] == dy['name'], labreq

bc = ok(api('POST', f"/lis/samples?orderId={o1['id']}", {}), '采样')['barcode']
smp_x = ok(api('GET', f"/lis/samples?deptId={dx['id']}"), '标本队列 甲')
smp_y = ok(api('GET', f"/lis/samples?deptId={dy['id']}"), '标本队列 乙')
sx = next((r for r in smp_x if r['barcode'] == bc), None)
assert sx and sx['exec_dept_id'] == dx['id'] and sx['exec_dept_name'] == dx['name'], f'标本队列甲须见刚采的标本并带两列：{smp_x[-3:]}'
assert all(r['barcode'] != bc for r in smp_y), '标本队列乙不得见甲的标本'
assert any(r['barcode'] == bc for r in ok(api('GET', '/lis/samples'), '标本队列 全部')), '不传 deptId 仍见'
print('[3] 三条读路径 OK（/lis/pending、/lis/samples 按 deptId 分流 + 两新列；导诊单/检验申请单取规则科室）')

# ---------- [4] 停用回落 + 落值即快照 ----------
ok(api('PUT', f'/masterdata/lab-route-rules/{rid_rule}/enabled?enabled=false'), '停用规则甲')
ok(api('PUT', f'/masterdata/lab-route-rules/{rid_wild}/enabled?enabled=false'), '停用规则乙')
assert rid_rule not in {r['id'] for r in ok(api('GET', '/masterdata/lab-route-rules'), '只列启用')}
assert rid_rule in {r['id'] for r in ok(api('GET', '/masterdata/lab-route-rules?includeDisabled=true'), '含停用')}
rs3 = ok(api('GET', f"/masterdata/lab-route-rules/resolve?chargeItemId={lab['id']}&specimenType=%E5%85%A8%E8%A1%80"), '试算 停用后')
assert rs3['source'] == ('ITEM' if dict_dept else 'NONE') and rs3['execDeptId'] == dict_dept and rs3['ruleId'] is None, rs3
rid2 = visited(pid, clinic['id'])
o3 = lab_order(rid2, lab['id'], '全血')
assert o3.get('execDeptId') == dict_dept, f'停用后回落字典（本库 {dict_dept}）：{o3}'
if dict_dept is None:
    assert 'execDeptName' not in o3 and 'execDeptId' in o3 and o3['execDeptId'] is None, \
        f'无值时 execDeptName 不出现（返回体与 v76 同形）：{o3}'
# 快照：此前开出的 o1 不受规则停用影响
guide_again = {r['id']: r for r in rows_of('guide-sheet', rid)}
assert guide_again[o1['id']]['exec_dept_name'] == dx['name'], '落值即快照：停用规则不回改已开医嘱'
# 启用回来再停用一条、删另一条，验证 5913 在启用路径同样守着
ok(api('PUT', f'/masterdata/lab-route-rules/{rid_rule}/enabled?enabled=true'), '重新启用甲')
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': 'E2E 同键', 'chargeItemId': lab['id'], 'specimenType': '全血', 'execDeptId': dy['id']}), 5913, '启用后同键再撞')
print('[4] 停用回落 + 落值即快照 OK')

# ---------- [5] /auth/me ----------
me = ok(api('GET', '/auth/me'), '/auth/me')
assert 'deptId' in me and 'deptName' in me, f'/auth/me 须带 deptId/deptName：{sorted(me)}'
print(f"[5] /auth/me OK（deptId={me['deptId']} deptName={me['deptName']}）")

# ---------- 清理 ----------
for rule_id in (rid_rule, rid_wild):
    ok(api('DELETE', f'/masterdata/lab-route-rules/{rule_id}'), '删规则')
fail_code(api('DELETE', f'/masterdata/lab-route-rules/{rid_rule}'), 5910, '删过的再删')
for d in (dx, dy):
    ok(api('PUT', f"/system/depts/{d['id']}/enabled?enabled=false"), '停用 E2E 科室')
print('\ne2e-v77-labroute 全部通过 ✅')
