# -*- coding: utf-8 -*-
"""存量医院切换：老 HIS 历史文书批量入 CDR（多医院部署操作指南 §六.4）。

门诊病历、出院小结、报告文本等历史文书按本工具导入平台数据中心，切换后
医生在患者 360 视图可回看老系统病史。**幂等**：以「类别+老单号」为键，
断点续跑与修正重导都是覆盖更新，不产生重复文书。

CSV 列（表头必须一致，见 migrate-templates/legacy-documents.csv）：
    证件号,患者号,类别,老单号,标题,日期,正文
    - 证件号与患者号至少填一个（优先证件号；命中多人时改用患者号）
    - 类别：OUTP=门诊文书 INP=住院文书 REPORT=报告 OTHER=其他
    - 日期：YYYY-MM-DD；正文可含换行（标准 CSV 引号包裹）
前置：患者索引已经 import-patients.py 建档。

用法：python tools/import-documents.py <文书csv> [--base http://localhost:8080/api]
      [--user admin] [--password admin123]
"""
import argparse
import csv
import json
import sys
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument('docs_csv')
ap.add_argument('--base', default='http://localhost:8080/api')
ap.add_argument('--user', default='admin')
ap.add_argument('--password', default='admin123')
args = ap.parse_args()

EXPECTED = ['证件号', '患者号', '类别', '老单号', '标题', '日期', '正文']


def call(method, path, body=None, token=None):
    req = urllib.request.Request(args.base + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data=data) as resp:
        return json.loads(resp.read().decode())


login = call('POST', '/auth/login', {'username': args.user, 'password': args.password})
assert login['code'] == 0, f"登录失败: {login}"
token = login['data']['token']

created = updated = failed = 0
with open(args.docs_csv, encoding='utf-8-sig', newline='') as f:
    reader = csv.DictReader(f)
    assert reader.fieldnames == EXPECTED, f"表头必须为 {','.join(EXPECTED)}，实际 {reader.fieldnames}"
    for i, row in enumerate(reader, start=2):
        body = {
            'idNo': row['证件号'].strip(),
            'patientNo': row['患者号'].strip(),
            'category': row['类别'].strip(),
            'legacyKey': row['老单号'].strip(),
            'title': row['标题'].strip(),
            'docDate': row['日期'].strip(),
            'content': row['正文'],
        }
        r = call('POST', '/cdr/legacy-documents', body, token)
        if r['code'] == 0:
            if r['data']['updated']:
                updated += 1
            else:
                created += 1
        else:
            failed += 1
            print(f"第 {i} 行失败（老单号 {body['legacyKey']}）: [{r['code']}] {r['message']}")

print(f"完成：新建 {created}，覆盖更新 {updated}，失败 {failed}")
sys.exit(1 if failed else 0)
