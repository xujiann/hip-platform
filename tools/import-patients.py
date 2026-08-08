# -*- coding: utf-8 -*-
"""数据迁移：老系统患者 CSV 批量导入。
有证件号走 EMPI 同证件号幂等；**无证件号行**按「姓名+手机号」在客户端查重（后端不查），
仍有同名同号风险时请先补全证件号。
CSV 列：姓名,证件号,手机号,住址,医保类型   （表头必须为这五列，见 migrate-templates/patients.csv）
用法：python tools/import-patients.py <csv路径> [接口地址]
"""
import csv
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

src = sys.argv[1]
base = sys.argv[2] if len(sys.argv) > 2 else 'http://localhost:8080/api'


def call(method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, data=json.dumps(body).encode() if body else None) as r:
        return json.loads(r.read().decode())


token = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})['data']['token']


def already_exists(name, phone):
    """无证件号行的去重：后端仅按证件号幂等，空证件号会重复建档。"""
    hits = call('GET', '/patients?keyword=' + urllib.parse.quote(name) + '&size=50', token=token)['data']['records']
    return any(h.get('name') == name and (not phone or h.get('phone') == phone) for h in hits)


ok = skip = fail = 0
with open(src, encoding='utf-8-sig') as f:
    for row in csv.DictReader(f):
        name = (row.get('姓名') or '').strip()
        id_no = (row.get('证件号') or '').strip()
        phone = (row.get('手机号') or '').strip()
        try:
            if not name:
                raise ValueError('姓名为空')
            if not id_no and already_exists(name, phone):
                skip += 1
                print(f'跳过（无证件号但同名同手机号已存在）: {name}')
                continue
            r = call('POST', '/patients', {
                'name': name, 'idType': 'ID_CARD', 'idNo': id_no or None,
                'phone': phone or None, 'address': row.get('住址') or None,
                'insuranceType': row.get('医保类型') or 'SELF'}, token)
            if r['code'] != 0:
                raise RuntimeError(r)
            ok += 1
        except urllib.error.HTTPError as e:
            fail += 1
            print(f"失败: {name} - HTTP {e.code} {e.read().decode('utf-8', 'replace')[:120]}")
        except Exception as e:
            fail += 1
            print(f"失败: {name} - {e}")
print(f'导入完成：成功 {ok}，跳过 {skip}，失败 {fail}')
sys.exit(1 if fail else 0)
