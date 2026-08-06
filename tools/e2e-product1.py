# -*- coding: utf-8 -*-
"""产品化一期 E2E：模块开关双路生效 / 字典 CSV 导入 / 病区床位批量创建"""
import urllib.error
import urllib.request
from e2elib import BASE, call, login, ok, q  # noqa: E402

t = login()

# 1 模块开关：默认启用 → 停用后菜单消失 + API 404 → 恢复
me = ok(call('GET', '/auth/me', token=t), '当前用户')
assert any(m['path'] == '/drg' for m in me['menus']), '默认应有 DRG 菜单'
ok(call('GET', '/drg/groups', token=t), 'DRG 默认可访问')

ok(call('PUT', '/config/module.drg.enabled?value=0', token=t), '停用 DRG')
try:
    me2 = ok(call('GET', '/auth/me', token=t), '当前用户2')
    assert not any(m['path'] == '/drg' for m in me2['menus']), '停用后菜单应消失'
    try:
        call('GET', '/drg/groups', token=t)
        raise AssertionError('停用后 API 应 404')
    except urllib.error.HTTPError as e:
        assert e.code == 404, e
finally:
    ok(call('PUT', '/config/module.drg.enabled?value=1', token=t), '恢复 DRG')
me3 = ok(call('GET', '/auth/me', token=t), '当前用户3')
assert any(m['path'] == '/drg' for m in me3['menus'])
ok(call('GET', '/drg/groups', token=t), 'DRG 恢复可访问')
print('[产品化-1] 模块开关 OK（停用→菜单消失+API 404，恢复→即刻回归）')

# 2 字典 CSV 导入：药品 + 收费项目（含一条坏行拦截）
drugs_csv = ("code,name,spec,unit,dose_form,price,stock,antibiotic\n"
             "E2EP1D1,产品化验证药,0.5g*12片,盒,片剂,9.90,10,0\n"
             "BADROW,坏行\n")
r = ok(call('POST', '/masterdata/drugs/import', token=t, text=drugs_csv), '药品导入')
assert r['imported'] == 1 and r['errorCount'] == 1, r
found = ok(call('GET', '/masterdata/drugs?keyword=' + q('产品化验证药'), token=t), '药品检索')
assert any(d['code'] == 'E2EP1D1' for d in found)

items_csv = ("code,name,category,unit,price\n"
             "E2EP1C1,产品化验证项目,LAB,次,15.00\n")
r2 = ok(call('POST', '/masterdata/charge-items/import', token=t, text=items_csv), '项目导入')
assert r2['imported'] == 1 and r2['errorCount'] == 0, r2
print('[产品化-2] 字典 CSV 导入 OK（药品 1 入库 1 拦截，项目 1 入库，检索可见）')

# 3 病区床位批量创建（找护理病区补 1 张，重复号自动跳过）
wards = [d for d in ok(call('GET', '/system/depts', token=t), '科室') if d['type'] == 'NURSING']
assert wards, '应有护理病区'
before = len(ok(call('GET', f"/inpatient/beds?wardId={wards[0]['id']}", token=t), '床位前'))
r3 = ok(call('POST', '/inpatient/beds', {'wardId': wards[0]['id'], 'count': 1}, t), '补床位')
after = len(ok(call('GET', f"/inpatient/beds?wardId={wards[0]['id']}", token=t), '床位后'))
assert r3['created'] == 1 and after == before + 1, (r3, before, after)
print(f"[产品化-3] 床位批量创建 OK（{wards[0]['name']} {before}→{after} 张）")

print('\n=== 产品化一期 E2E 全部通过 ===')
