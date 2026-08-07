# -*- coding: utf-8 -*-
"""E2E 公共库：15+ 套 e2e-*.py 共用的 HTTP 调用与业务辅助。
同目录 import（python tools/e2e-xxx.py 时脚本目录自动入 sys.path），CI 无需改动。
前提：后端运行于 localhost:8080。
"""
import json
import os
import sys
import urllib.parse
import urllib.request

# 可用 HIP_E2E_BASE 指向其他实例（如并行验证 8081），子进程经环境变量继承
BASE = os.environ.get('HIP_E2E_BASE', 'http://localhost:8080/api')
q = urllib.parse.quote

# Windows 控制台默认 GBK，统一切 UTF-8
sys.stdout.reconfigure(encoding='utf-8')


def call(method, path, body=None, token=None, raw=False, text=None):
    """统一 API 调用：JSON 请求/响应；text= 发送纯文本体；raw=True 返回原始字符串（CSV 等）"""
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if text is not None:
        req.add_header('Content-Type', 'text/plain; charset=utf-8')
        data = text.encode('utf-8')
    else:
        req.add_header('Content-Type', 'application/json')
        data = json.dumps(body).encode('utf-8') if body is not None else None
    with urllib.request.urlopen(req, data=data) as resp:
        body_bytes = resp.read()
        return body_bytes.decode('utf-8') if raw else json.loads(body_bytes.decode('utf-8'))


def ok(r, step):
    assert r['code'] == 0, f'{step} 失败: {r}'
    return r['data']


def login(username='admin', password='admin123'):
    return ok(call('POST', '/auth/login', {'username': username, 'password': password}), f'登录 {username}')['token']


def new_patient(token, name, sex='F', **extra):
    """建 E2E 专用患者（同患者同排班重复挂号会被 3002 拦截，各期造数据须新建专用患者）"""
    return ok(call('POST', '/patients', {'name': name, 'sex': sex, **extra}, token), '建患者')


def find_free_bed(token, ward_type='NURSING'):
    """遍历护理单元找空床；找不到说明有 E2E 在院患者未收尾出院"""
    wards = [d for d in ok(call('GET', '/system/depts', token=token), '科室') if d['type'] == ward_type]
    for w in wards:
        beds = ok(call('GET', f"/inpatient/beds?wardId={w['id']}", token=token), '查床')
        free = next((b for b in beds if b['status'] == 'FREE'), None)
        if free:
            return free
    raise AssertionError('无空床（请先清理在院测试数据）')


def discharge_cleanup(token, admission_id, pay_method=None):
    """收尾出院释放床位，防止 E2E 在院患者耗尽病区空床"""
    suffix = f'?payMethod={pay_method}' if pay_method else ''
    return ok(call('POST', f'/inpatient/admissions/{admission_id}/discharge{suffix}', {}, token), '收尾出院')
