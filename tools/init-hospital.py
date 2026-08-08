# -*- coding: utf-8 -*-
"""产品化一期：医院初始化工具（生产版，替代演示口径的 bootstrap-demo）。

用法：python tools/init-hospital.py <配置.json> [--base http://localhost:8080]
配置模板：tools/init-templates/hospital.example.json
幂等：重复执行安全（科室/账号按名跳过，字典 upsert，配置覆盖）。

步骤：登录 → 院名/品牌 → 模块开关 → 科室与病区床位 → 业务账号 → 字典导入 → 强改 admin 口令
"""
import json
import sys
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    cfg_path = Path(sys.argv[1])
    base = 'http://localhost:8080'
    if '--base' in sys.argv:
        base = sys.argv[sys.argv.index('--base') + 1]
    api = base.rstrip('/') + '/api'
    cfg = json.loads(cfg_path.read_text(encoding='utf-8'))

    def call(method, path, body=None, token=None, text=None):
        req = urllib.request.Request(api + path, method=method)
        if token:
            req.add_header('Authorization', 'Bearer ' + token)
        if text is not None:
            req.add_header('Content-Type', 'text/plain; charset=utf-8')
            data = text.encode('utf-8')
        else:
            req.add_header('Content-Type', 'application/json')
            data = json.dumps(body).encode('utf-8') if body is not None else None
        with urllib.request.urlopen(req, data=data) as resp:
            return json.loads(resp.read().decode('utf-8'))

    def ok(r, step):
        assert r.get('code') == 0, f'{step} 失败: {r}'
        return r.get('data')

    new_pwd = cfg.get('adminNewPassword')
    assert new_pwd and new_pwd != 'admin123', '配置须提供 adminNewPassword（生产初始化必须更换默认口令）'

    # 1 登录
    t = ok(call('POST', '/auth/login', {'username': 'admin',
                                        'password': cfg.get('adminCurrentPassword', 'admin123')}), '登录')['token']
    print('[1] 登录 OK')

    # 2 院名与品牌
    hosp = cfg.get('hospital', {})
    for key, val in {'hospital_name': hosp.get('name'), **hosp.get('extraConfig', {})}.items():
        if val:
            ok(call('PUT', f'/config/{key}?value=' + urllib.parse.quote(str(val)), token=t), f'配置 {key}')
    print(f"[2] 机构配置 OK（{hosp.get('name', '未改院名')}）")

    # 3 模块开关
    modules = cfg.get('modules', {})
    for key, enabled in modules.items():
        ok(call('PUT', f'/config/module.{key}.enabled?value=' + ('1' if enabled else '0'), token=t), f'开关 {key}')
    off = [k for k, v in modules.items() if not v]
    print(f"[3] 模块开关 OK（关闭：{'、'.join(off) if off else '无'}）")

    # 4 科室与病区床位（按名幂等）
    existing = {d['name']: d for d in ok(call('GET', '/system/depts', token=t), '查科室')}
    created_depts = beds_created = 0
    for dept in cfg.get('depts', []):
        d = existing.get(dept['name'])
        if d is None:
            d = ok(call('POST', '/system/depts', {'name': dept['name'], 'code': dept.get('code'),
                                                  'type': dept.get('type', 'CLINIC')}, t), f"建科室 {dept['name']}")
            created_depts += 1
        if dept.get('type') == 'NURSING' and dept.get('beds'):
            # 只补差额：后端跳过已存在床号后会继续建满 count 张，
            # 无条件调用会让重跑一次就多出 count 张幽灵床（可占用的真床位）
            have = len(ok(call('GET', f"/inpatient/beds?wardId={d['id']}", token=t), '查床位'))
            need = dept['beds'] - have
            if need > 0:
                r = ok(call('POST', '/inpatient/beds', {'wardId': d['id'], 'count': need}, t),
                       f"建床位 {dept['name']}")
                beds_created += r['created']
    print(f"[4] 科室 OK（新建 {created_depts}，补床位 {beds_created} 张）")

    # 5 业务账号（按用户名幂等）
    users = ok(call('GET', '/system/users?page=0&size=200', token=t), '查用户')['records']
    known = {u['username'] for u in users}
    created_users = 0
    for acc in cfg.get('accounts', []):
        if acc['username'] in known:
            continue
        ok(call('POST', '/system/users', {'username': acc['username'], 'password': acc['password'],
                                          'realName': acc['realName'], 'title': acc.get('title'),
                                          'deptId': acc.get('deptId'), 'phone': acc.get('phone'),
                                          'roleCodes': acc.get('roleCodes', [])}, t), f"建账号 {acc['username']}")
        created_users += 1
    print(f"[5] 账号 OK（新建 {created_users}）")

    # 6 字典导入（CSV 路径相对配置文件）
    dicts = cfg.get('dictionaries', {})
    for label, path_key, endpoint in [('药品', 'drugsCsv', '/masterdata/drugs/import'),
                                      ('收费项目', 'chargeItemsCsv', '/masterdata/charge-items/import'),
                                      ('医保对照', 'ybCatalogCsv', '/insurance/catalog/import')]:
        p = dicts.get(path_key)
        if not p:
            continue
        csv_text = (cfg_path.parent / p).read_text(encoding='utf-8-sig')
        r = ok(call('POST', endpoint, token=t, text=csv_text), f'导入{label}')
        print(f"[6] {label}导入 OK（入库 {r['imported']}，错误 {r['errorCount']}）"
              + (f" 错误示例: {r['errors'][:2]}" if r['errorCount'] else ''))

    # 7 强改 admin 口令（安全基线：生产库不留 admin123）
    admin = next(u for u in users if u['username'] == 'admin')
    ok(call('PUT', f"/system/users/{admin['id']}", {'username': 'admin', 'realName': admin['realName'],
                                                    'password': new_pwd, 'roleCodes': admin['roleCodes']}, t),
       '改 admin 口令')
    ok(call('POST', '/auth/login', {'username': 'admin', 'password': new_pwd}), '新口令验证')
    print('[7] admin 口令已更换并验证')

    print('\n=== 医院初始化完成 ===')
    print('提醒：生产部署须设置环境变量 HIP_JWT_SECRET（≥32 位随机串）；演示数据请勿在生产库运行 bootstrap-demo')


import urllib.parse  # noqa: E402

if __name__ == '__main__':
    main()
