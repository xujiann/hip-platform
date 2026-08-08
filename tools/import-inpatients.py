# -*- coding: utf-8 -*-
"""存量医院切换：在院患者批量重建（多医院部署操作指南 §六.4）。

老系统中期结算后，按本工具把在院患者重建进 HIP：入院登记（占床）→ 押金余额转入 →
迁移备注病历（留档原住院号）→ 可选长期医嘱重开。**幂等**：同证件号患者已在院则整行跳过。

主表 CSV 列（表头必须一致，见 migrate-templates/inpatients.csv）：
    老住院号,姓名,证件号,科室,病区,床号,入院诊断ICD,入院诊断名称,押金余额
医嘱 CSV 列（可选，见 migrate-templates/inpatient-orders.csv）：
    老住院号,类型,项目名称,数量,用法,频次,单次量        （类型：DRUG=药品，ITEM=诊疗项目）
    仅对本次新建的住院重开医嘱（跳过行视为此前已建完，防重复计费）。

用法：python tools/import-inpatients.py <在院患者csv> [医嘱csv] [--base 接口地址]
      [--user admin] [--password admin123]
注意：患者索引应先经 import-patients.py 导入；证件号为历史假号的医院先关
      sys_config.empi_idcard_checksum 再补建档。
"""
import argparse
import csv
import json
import sys
import urllib.parse
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument('patients_csv')
ap.add_argument('orders_csv', nargs='?')
ap.add_argument('--base', default='http://localhost:8080/api')
ap.add_argument('--user', default='admin')
ap.add_argument('--password', default='admin123')
args = ap.parse_args()


def call(method, path, body=None, token=None):
    req = urllib.request.Request(args.base + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, data=json.dumps(body).encode() if body else None) as r:
        return json.loads(r.read().decode())


def q(s):
    return urllib.parse.quote(s)


t = call('POST', '/auth/login', {'username': args.user, 'password': args.password})['data']['token']

# 基础字典：科室/病区（按名称解析）、在院患者（幂等判据）
depts = call('GET', '/system/depts', token=t)['data']
dept_by_name = {d['name']: d for d in depts}
in_hospital = call('GET', '/inpatient/admissions', token=t)['data']
inhosp_patient_ids = {a['patientId'] for a in in_hospital}
beds_cache: dict = {}


def ward_beds(ward_id):
    if ward_id not in beds_cache:
        beds_cache[ward_id] = {b['bedNo']: b for b in call('GET', f'/inpatient/beds?wardId={ward_id}', token=t)['data']}
    return beds_cache[ward_id]


def find_patient(name, id_no):
    for p in call('GET', f'/patients?keyword={q(id_no or name)}&size=50', token=t)['data']['records']:
        if id_no and p.get('idNo') == id_no:
            return p
        if not id_no and p.get('name') == name:
            return p
    return None


created = {}   # 老住院号 -> 新 admission id（医嘱重开只针对本次新建）
ok = skip = fail = 0
with open(args.patients_csv, encoding='utf-8-sig') as f:
    for row in csv.DictReader(f):
        legacy_no = row['老住院号'].strip()
        try:
            p = find_patient(row['姓名'].strip(), row.get('证件号', '').strip())
            assert p, f"患者索引未找到（先跑 import-patients.py）: {row['姓名']}"
            if p['id'] in inhosp_patient_ids:
                skip += 1
                print(f"跳过（已在院）: {row['姓名']} 原住院号 {legacy_no}")
                continue
            dept = dept_by_name.get(row['科室'].strip())
            ward = dept_by_name.get(row['病区'].strip())
            assert dept, f"科室不存在: {row['科室']}"
            assert ward, f"病区不存在: {row['病区']}"
            bed = ward_beds(ward['id']).get(row['床号'].strip())
            assert bed, f"床号不存在: {row['病区']}/{row['床号']}"
            assert bed['status'] == 'FREE', f"床位非空闲({bed['status']}): {row['病区']}/{row['床号']}"

            r = call('POST', '/inpatient/admissions', {
                'patientId': p['id'], 'deptId': dept['id'], 'bedId': bed['id'],
                'diagIcd': row.get('入院诊断ICD') or None, 'diagName': row.get('入院诊断名称') or None,
                'deposit': float(row.get('押金余额') or 0), 'payMethod': 'CASH'}, t)
            assert r['code'] == 0, r
            adm_id = r['data']['id']
            call('POST', f'/inpatient/admissions/{adm_id}/records', {
                'recordType': 'PROGRESS', 'title': '迁移备注',
                'content': f'存量系统切换迁移：原住院号 {legacy_no}；押金余额按老系统中期结算单转入。'}, t)
            created[legacy_no] = adm_id
            inhosp_patient_ids.add(p['id'])
            bed['status'] = 'OCCUPIED'
            ok += 1
            print(f"重建: {row['姓名']} 原住院号 {legacy_no} -> {r['data'].get('admissionNo')}")
        except Exception as e:
            fail += 1
            print(f"失败: {row.get('姓名')} 原住院号 {legacy_no} - {e}")

print(f'在院重建完成：新建 {ok}，跳过 {skip}，失败 {fail}')

# ---- 可选：长期医嘱重开（仅本次新建的住院） ----
if args.orders_csv:
    def find_item(kind, name):
        path = '/masterdata/drugs' if kind == 'DRUG' else '/masterdata/charge-items'
        hits = [x for x in call('GET', f'{path}?keyword={q(name)}', token=t)['data'] if x['name'] == name]
        assert hits, f'字典未找到: {name}'
        return hits[0]

    o_ok = o_skip = o_fail = 0
    with open(args.orders_csv, encoding='utf-8-sig') as f:
        for row in csv.DictReader(f):
            legacy_no = row['老住院号'].strip()
            if legacy_no not in created:
                o_skip += 1
                continue
            try:
                kind = (row.get('类型') or 'DRUG').strip().upper()
                item = find_item(kind, row['项目名称'].strip())
                r = call('POST', f"/inpatient/admissions/{created[legacy_no]}/orders", {'lines': [{
                    'orderType': 'DRUG' if kind == 'DRUG' else item.get('category') or 'TREAT',
                    'itemId': item['id'], 'qty': int(row.get('数量') or 1),
                    'usageRoute': row.get('用法') or None, 'frequency': row.get('频次') or None,
                    'dosePerTime': row.get('单次量') or None}]}, t)
                assert r['code'] == 0, r
                o_ok += 1
            except Exception as e:
                o_fail += 1
                print(f"医嘱失败: 原住院号 {legacy_no} {row.get('项目名称')} - {e}")
    print(f'医嘱重开完成：成功 {o_ok}，跳过（非本次新建）{o_skip}，失败 {o_fail}')

sys.exit(1 if fail else 0)
