# -*- coding: utf-8 -*-
"""数据迁移：老系统患者 CSV 批量导入（走 EMPI 建档接口，天然幂等去重）。
CSV 列：姓名,证件号,手机号,住址,医保类型   （表头必须为这五列，见 migrate-templates/patients.csv）
用法：python tools/import-patients.py <csv路径> [接口地址]
"""
import csv
import json
import sys
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
ok = fail = 0
with open(src, encoding='utf-8-sig') as f:
    for row in csv.DictReader(f):
        try:
            r = call('POST', '/patients', {
                'name': row['姓名'], 'idType': 'ID_CARD', 'idNo': row.get('证件号') or None,
                'phone': row.get('手机号') or None, 'address': row.get('住址') or None,
                'insuranceType': row.get('医保类型') or 'SELF'}, token)
            assert r['code'] == 0, r
            ok += 1
        except Exception as e:
            fail += 1
            print(f"失败: {row.get('姓名')} - {e}")
print(f'导入完成：成功 {ok}，失败 {fail}')
