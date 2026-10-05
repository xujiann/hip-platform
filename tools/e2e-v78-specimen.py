# -*- coding: utf-8 -*-
"""v78 E2E：标本类型字典（V176 md_specimen_type + /masterdata/specimen-types）与流向规则的字典校验在**真实服务**上走一遍。

JUnit（V78SpecimenDictTest）直接调控制器、跑在回滚事务里——线格式、JWT、@PreAuthorize、跨端点状态机一个都没走过。
本套用 HTTP 把规划节「接口契约」逐条钉住：
  [1] 字典 CRUD：GET（默认只返启用项 / all=true 全部，键 id,code,name,sortNo,enabled,ruleCount）、POST → {id}（编码大写落库、名称 strip）、
      PUT /{id} {name,sortNo}（编码不可改）、PUT /{id}/enabled?enabled=、DELETE；5915 重码/重名、5916 不存在、4000 字段；
      写权限 ADMIN/TECHNICIAN 放行、DOCTOR_OUTP 403；
  [2] 规则引用字典项：建规则（标本填带空白变体）→ 落库规范化值、ruleCount=1 → 停用/删除/改名字典项撞 5917；
  [3] 规则引用字典外值 5916、引用已停用字典项 5917；
  [4] 开单填字典外值：OrderLine.specimenType 仍是自由文本（后端不校验）→ 规则不命中、回落收费项目字典（与 /resolve 同口径）；
  [5] 清理：停规则 → ruleCount 归零 → 删规则 → 删字典项；再删 5916。
自成一体：建自己的科室、患者、字典项、规则；收尾删规则/字典项、停用科室（科室无 DELETE 端点）。演示库可复跑（残留自净）。
时间一律从 e2elib.today_bj() 取，不写墙钟字面量。
"""
import time
import urllib.error

from e2elib import call, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj()


def api(m, p, b=None, tok=None, **kw):
    return call(m, p, b, tok or t, **kw)


def uniq(pre):
    return pre + str(int(time.time() * 1000) % 100000000)


def fail_code(r, code, step):
    assert r['code'] == code, f'{step}: 应返回 {code}，实际 {r}'


def dict_rows(all_=False):
    return ok(api('GET', f"/masterdata/specimen-types?all={'true' if all_ else 'false'}"), '字典列表')


def dict_row(sid, all_=True):
    return next((r for r in dict_rows(all_) if r['id'] == sid), None)


def visited(pid, dept_id):
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': dept_id, 'scheduleDate': today.isoformat(), 'fee': '0.00', 'capacity': 50}), '排班')
    reg = ok(api('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}), '挂号')
    ok(api('POST', f"/outpatient/doctor/{reg['id']}/start", {}), '接诊')
    return reg['id']


# ---------- 残留自净（演示库复跑；全新 CI 库恒为空） ----------
for stale in ok(api('GET', '/masterdata/lab-route-rules?includeDisabled=true'), '规则列表'):
    if str(stale.get('name', '')).startswith('E2E78 '):
        ok(api('DELETE', f"/masterdata/lab-route-rules/{stale['id']}"), '清理残留 E2E78 规则')
for stale in dict_rows(True):
    if str(stale.get('code', '')).startswith('E78'):
        ok(api('DELETE', f"/masterdata/specimen-types/{stale['id']}"), '清理残留 E78 字典项')

# ---------- 前置：科室 / 项目 ----------
depts = ok(api('GET', '/system/depts'), '科室')
clinic = next(d for d in depts if d['type'] == 'CLINICAL' and d.get('enabled', True))
dx = ok(api('POST', '/system/depts', {'name': '标本E2E检验' + uniq(''), 'code': uniq('E78X'), 'type': 'MEDTECH', 'sortNo': 99}),
        '建执行科室')
items = ok(api('GET', '/masterdata/charge-items?keyword=%E8%A1%80%E5%B8%B8%E8%A7%84&category=LAB'), 'LAB 项目')
assert items, '主数据无「血常规」检验项目（主数据缺项，非功能缺陷）'
lab = items[0]

# ---------- [1] 字典 CRUD + 错误码 + 权限 ----------
seed = dict_rows(False)
assert seed and all(r['enabled'] for r in seed), '默认只返启用项'
for k in ('id', 'code', 'name', 'sortNo', 'enabled', 'ruleCount'):
    assert k in seed[0], f'列表缺键 {k}：{sorted(seed[0])}'
assert any(r['code'] == 'WB' and r['name'] == '全血' for r in seed), 'V176 种子 WB 全血 须在'
assert len(dict_rows(True)) >= len(seed)

code = uniq('e78a')                                    # 小写送入 → 大写落库
name = 'E2E标本' + code[-5:]
sid = ok(api('POST', '/masterdata/specimen-types', {'code': ' ' + code + ' ', 'name': '  ' + name + '  ', 'sortNo': 500}), '建字典项')['id']
row = dict_row(sid)
assert row and row['code'] == code.upper() and row['name'] == name and row['sortNo'] == 500 and row['enabled'] is True \
    and row['ruleCount'] == 0, f'新建行：{row}'
fail_code(api('POST', '/masterdata/specimen-types', {'code': code.upper(), 'name': '别的名'}), 5915, '重码')
fail_code(api('POST', '/masterdata/specimen-types', {'code': uniq('E78B'), 'name': name}), 5915, '重名')
fail_code(api('POST', '/masterdata/specimen-types', {'code': uniq('E78B'), 'name': name[:3] + ' ' + name[3:]}), 5915, '重名（只差空白）')
fail_code(api('POST', '/masterdata/specimen-types', {'code': 'wb', 'name': 'x'}), 5915, '种子码大小写不同也挡')
fail_code(api('POST', '/masterdata/specimen-types', {'code': '', 'name': 'x'}), 4000, '编码缺失')
fail_code(api('POST', '/masterdata/specimen-types', {'code': uniq('E78B'), 'name': ''}), 4000, '名称缺失')
fail_code(api('POST', '/masterdata/specimen-types', {'code': uniq('E78B'), 'name': 'x' * 33}), 4000, '名称超长')
fail_code(api('PUT', '/masterdata/specimen-types/987654321', {'name': 'x'}), 5916, '编辑不存在')
fail_code(api('PUT', '/masterdata/specimen-types/987654321/enabled?enabled=false'), 5916, '启停不存在')
fail_code(api('DELETE', '/masterdata/specimen-types/987654321'), 5916, '删除不存在')

ok(api('PUT', f'/masterdata/specimen-types/{sid}', {'name': name + '改', 'sortNo': 501}), '编辑')
row = dict_row(sid)
assert row['name'] == name + '改' and row['sortNo'] == 501 and row['code'] == code.upper(), f'编辑后：{row}'
ok(api('PUT', f'/masterdata/specimen-types/{sid}/enabled?enabled=false'), '停用')
assert dict_row(sid, all_=False) is None and dict_row(sid)['enabled'] is False, '停用后默认列表不可见、all=true 可见'
ok(api('PUT', f'/masterdata/specimen-types/{sid}/enabled?enabled=true'), '启用')
ok(api('PUT', f'/masterdata/specimen-types/{sid}/enabled?enabled=true'), '重复启用幂等')
ok(api('PUT', f'/masterdata/specimen-types/{sid}', {'name': name, 'sortNo': 500}), '改回原名')

tech = provision_user(t, 'e2e78tech', 'TECHNICIAN', 'E2E78技师')
tcode = uniq('E78T')
tid = ok(api('POST', '/masterdata/specimen-types', {'code': tcode, 'name': '技师建' + tcode[-5:]}, tok=tech), '技师建字典项')['id']
ok(api('DELETE', f'/masterdata/specimen-types/{tid}', tok=tech), '技师删字典项')
doc = provision_user(t, 'e2e78doc', 'DOCTOR_OUTP', 'E2E78医生')
assert ok(api('GET', '/masterdata/specimen-types', tok=doc), '医生读字典'), '读登录即可'
try:
    api('POST', '/masterdata/specimen-types', {'code': uniq('E78D'), 'name': '医生越权'}, tok=doc)
    raise AssertionError('医生写字典须被 @PreAuthorize 拒绝（HTTP 403）')
except urllib.error.HTTPError as e:
    assert e.code == 403, f'医生写字典应 403，实际 {e.code}'
print(f'[1] 字典 CRUD OK（{code.upper()} 建/改/启停；5915/5916/4000 线上原样回；技师可写、医生只读 403）')

# ---------- [2] 规则引用字典项 → 5917 守卫 ----------
rid_rule = ok(api('POST', '/masterdata/lab-route-rules',
                  {'name': 'E2E78 血常规·' + name + '→X', 'chargeItemId': lab['id'], 'specimenType': ' ' + name[:3] + ' ' + name[3:] + ' ',
                   'execDeptId': dx['id'], 'priority': 50}), '建规则（标本带空白变体）')['id']
mine = next(r for r in ok(api('GET', '/masterdata/lab-route-rules'), '规则列表') if r['id'] == rid_rule)
assert mine['specimenType'] == name.upper(), f'规则落库规范化值（去空白、大写）：{mine["specimenType"]}'
assert dict_row(sid)['ruleCount'] == 1, f'ruleCount 须为 1：{dict_row(sid)}'
fail_code(api('PUT', f'/masterdata/specimen-types/{sid}/enabled?enabled=false'), 5917, '被引用项停用')
fail_code(api('DELETE', f'/masterdata/specimen-types/{sid}'), 5917, '被引用项删除')
fail_code(api('PUT', f'/masterdata/specimen-types/{sid}', {'name': name + '另名', 'sortNo': 500}), 5917, '被引用项改名')
ok(api('PUT', f'/masterdata/specimen-types/{sid}', {'name': name, 'sortNo': 502}), '被引用项只改排序放行')
assert dict_row(sid)['enabled'] is True and dict_row(sid)['name'] == name
print('[2] 规则引用 → 停用/删除/改名 5917 守卫 OK（ruleCount=1、落库规范化值）')

# ---------- [3] 规则引用字典外值 5916 / 已停用项 5917 ----------
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': 'E2E78 字典外', 'chargeItemId': lab['id'], 'specimenType': 'E78字典外标本', 'execDeptId': dx['id']}), 5916, '字典外值')
fail_code(api('PUT', f'/masterdata/lab-route-rules/{rid_rule}',
              {'name': 'E2E78 改成字典外', 'chargeItemId': lab['id'], 'specimenType': 'E78字典外标本', 'execDeptId': dx['id']}), 5916, '编辑成字典外值')
offc = uniq('E78O')
off_id = ok(api('POST', '/masterdata/specimen-types', {'code': offc, 'name': '停用项' + offc[-5:]}), '建将停用项')['id']
ok(api('PUT', f'/masterdata/specimen-types/{off_id}/enabled?enabled=false'), '停用它')
fail_code(api('POST', '/masterdata/lab-route-rules',
              {'name': 'E2E78 停用项', 'chargeItemId': lab['id'], 'specimenType': '停用项' + offc[-5:], 'execDeptId': dx['id']}), 5917, '引用已停用项')
# 种子项照旧放行（全新库无 血常规+尿液 规则；演示库若有则 5913——两种都证明字典校验已过，不是 5916）
r_seed = api('POST', '/masterdata/lab-route-rules',
             {'name': 'E2E78 种子尿液', 'chargeItemId': lab['id'], 'specimenType': '尿 液', 'execDeptId': dx['id']})
assert r_seed['code'] in (0, 5913), f'种子项须过字典校验：{r_seed}'
rid_seed = r_seed['data']['id'] if r_seed['code'] == 0 else None
assert all(not str(r['name']).startswith('E2E78 字典外') and not str(r['name']).startswith('E2E78 停用项')
           for r in ok(api('GET', '/masterdata/lab-route-rules?includeDisabled=true'), '规则列表')), '被拒零落库'
print('[3] 规则引用字典外值 5916 / 已停用项 5917 OK')

# ---------- [4] 开单填字典外值 → 不命中规则、回落字典（与 /resolve 同口径） ----------
rs = ok(api('GET', f"/masterdata/lab-route-rules/resolve?chargeItemId={lab['id']}&specimenType=E78%E5%AD%97%E5%85%B8%E5%A4%96%E6%A0%87%E6%9C%AC"
               f"&orderDeptId={clinic['id']}"), '试算 字典外值')
assert rs['source'] in ('ITEM', 'NONE') and rs['ruleId'] is None, f'字典外值不命中带标本键的规则：{rs}'
rs_hit = ok(api('GET', f"/masterdata/lab-route-rules/resolve?chargeItemId={lab['id']}&specimenType={q(name)}"
                   f"&orderDeptId={clinic['id']}"), '试算 字典项')
assert rs_hit['source'] == 'RULE' and rs_hit['ruleId'] == rid_rule and rs_hit['execDeptId'] == dx['id'], f'字典项命中：{rs_hit}'
pid = new_patient(t, '标本E2E' + uniq(''), 'F')['id']
rid = visited(pid, clinic['id'])
orders = ok(api('POST', f'/outpatient/doctor/{rid}/orders',
                {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'specimenType': 'E78字典外标本'},
                           {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'specimenType': name}]}), '开两条检验医嘱')
o_out, o_in = orders
assert o_out.get('execDeptId') == rs['execDeptId'], f'字典外值开单回落字典（与试算同口径 {rs["execDeptId"]}）：{o_out}'
if rs['execDeptId'] is None:
    assert 'execDeptName' not in o_out, f'无值时 execDeptName 不出现：{o_out}'
assert o_in.get('execDeptId') == dx['id'] and o_in.get('execDeptName') == dx['name'], f'字典项开单命中规则：{o_in}'
print(f'[4] 开单 OK（字典外值回落 {rs["source"]}，字典项命中 {dx["name"]}；OrderLine.specimenType 仍自由文本）')

# ---------- [5] 清理 ----------
ok(api('PUT', f'/masterdata/lab-route-rules/{rid_rule}/enabled?enabled=false'), '停用规则')
assert dict_row(sid)['ruleCount'] == 0, '停用规则不算引用'
ok(api('PUT', f'/masterdata/specimen-types/{sid}/enabled?enabled=false'), '规则停用后字典项可停用')
ok(api('PUT', f'/masterdata/specimen-types/{sid}/enabled?enabled=true'), '再启用')
for rule_id in (rid_rule, rid_seed):
    if rule_id:
        ok(api('DELETE', f'/masterdata/lab-route-rules/{rule_id}'), '删规则')
for d_id in (sid, off_id):
    ok(api('DELETE', f'/masterdata/specimen-types/{d_id}'), '删字典项')
fail_code(api('DELETE', f'/masterdata/specimen-types/{sid}'), 5916, '删过的再删')
assert not any(str(r.get('code', '')).startswith('E78') for r in dict_rows(True)), '字典无残留'
ok(api('PUT', f"/system/depts/{dx['id']}/enabled?enabled=false"), '停用 E2E 科室')
print('\ne2e-v78-specimen 全部通过 ✅')
