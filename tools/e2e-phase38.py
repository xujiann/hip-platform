# -*- coding: utf-8 -*-
"""三十八期 E2E：多角色菜单矩阵与演示账号（试点就绪）"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from e2elib import BASE, call, login, ok, q  # noqa: E402



# 引导演示账号（幂等）
env = dict(os.environ, PYTHONIOENCODING='utf-8')
r = subprocess.run([sys.executable, 'tools/bootstrap-demo.py'], capture_output=True, text=True,
                   encoding='utf-8', errors='replace', env=env)
assert '完成' in r.stdout, r.stdout + r.stderr
print('[卅八-0] 演示账号引导 OK')


def menus_of(username):
    t = ok(call('POST', '/auth/login', {'username': username, 'password': 'Demo1234'}), username + ' 登录')['token']
    me = ok(call('GET', '/auth/me', token=t), username + ' me')
    return {m['name'] for m in me['menus']}, me['roles']


# 医生：有临床，无系统管理/收费
m, roles = menus_of('doctor01')
assert 'DOCTOR_OUTP' in roles
assert '门诊医生站' in m and 'CDSS 提醒' in m and '患者360视图' in m, m
assert '用户管理' not in m and '门诊收费' not in m and '运维中心' not in m, m
print(f"[卅八-1] 医生角色 OK（{len(m)} 个菜单：含医生站/CDSS，无系统管理/收费）")

# 护士：护理与移动端，无药房/系统
m, _ = menus_of('nurse01')
assert '门诊护士站' in m and '移动工作台' in m and '护理白板' in m and '用血管理' in m, m
assert '药房发药' not in m and '用户管理' not in m, m
print(f"[卅八-2] 护士角色 OK（{len(m)} 个菜单：护理/移动/用血，无药房与系统管理）")

# 收费员：挂号收费支付日结，无医生站
m, _ = menus_of('cashier01')
assert '门诊收费' in m and '扫码支付台' in m and '日结报表' in m and '门诊挂号' in m, m
assert '门诊医生站' not in m and '用户管理' not in m, m
print(f"[卅八-3] 收费员角色 OK（{len(m)} 个菜单）")

# 药师：药房审方药事，无收费
m, _ = menus_of('pharm01')
assert '药房发药' in m and '审方工作台' in m and '药事分析' in m, m
assert '门诊收费' not in m, m
print(f"[卅八-4] 药师角色 OK（{len(m)} 个菜单）")

# 医技：LIS/RIS/专科，无住院护理
m, _ = menus_of('tech01')
assert 'LIS工作台' in m and 'RIS报告' in m and '专科流程' in m and '预约与叫号' in m, m
assert '护士执行' not in m, m
print(f"[卅八-5] 医技角色 OK（{len(m)} 个菜单）")

# 质控院感：质控/院感/DRG/病案，无收费药房
m, _ = menus_of('quality01')
assert '院感专项' in m and 'DRG 分析' in m and '病案统计' in m and '质控中心' in m, m
assert '药房发药' not in m and '门诊收费' not in m, m
print(f"[卅八-6] 质控院感角色 OK（{len(m)} 个菜单）")

# 运营后勤：设备人事药事，无临床
m, _ = menus_of('ops01')
assert '设备管理' in m and '人事管理' in m and '固定资产' in m, m
assert '门诊医生站' not in m and 'LIS工作台' not in m, m
print(f"[卅八-7] 运营后勤角色 OK（{len(m)} 个菜单）")

# 权限硬校验：非 ADMIN 调管理接口应被拒（403）
t_doc = ok(call('POST', '/auth/login', {'username': 'doctor01', 'password': 'Demo1234'}), '医生登录')['token']
try:
    call('GET', '/ops/health-overview', token=t_doc)
    raise AssertionError('医生不应能访问运维接口')
except urllib.error.HTTPError as e:
    assert e.code in (401, 403), e
print('[卅八-8] 接口级权限 OK（医生访问运维接口被拒）')

print('\n=== 三十八期 E2E 全部通过 ===')
