# -*- coding: utf-8 -*-
"""v82 真实 HTTP 双医生/护士回归：越权写零副作用，代班首写及护理体征不受影响。"""
import datetime
import time
import urllib.error
from e2elib import call, login, new_patient, ok, provision_user, today_bj, find_free_bed, discharge_cleanup

t = login()
suffix = str(time.time_ns())[-10:]
a = provision_user(t, 'e2e82a' + suffix, 'DOCTOR_OUTP', 'V82书写医生')
b = provision_user(t, 'e2e82b' + suffix, 'DOCTOR_OUTP', 'V82其他医生')
nurse = provision_user(t, 'e2e82n' + suffix, 'NURSE', 'V82护士')
aid = ok(call('GET', '/auth/me', token=a), 'A身份')['id']
bid = ok(call('GET', '/auth/me', token=b), 'B身份')['id']
today = today_bj().isoformat()
yesterday = (today_bj() - datetime.timedelta(days=1)).isoformat()
body = {'emr': {'chiefComplaint': 'V82原正文', 'presentIllness': 'V82原现病史'}, 'diagnoses': []}
changed = {'emr': {'chiefComplaint': 'V82本人修改', 'presentIllness': 'V82修改现病史'}, 'diagnoses': []}

def denied(method, path, payload, token, code):
    result = call(method, path, payload, token)
    assert result['code'] == code, (path, result)

def role_denied(method, path, payload):
    try:
        result = call(method, path, payload, nurse)
    except urllib.error.HTTPError as ex:
        assert ex.code == 403, ex
    else:
        assert result['code'] != 0, (path, result)

def visit(day):
    p = new_patient(t, 'V82门诊' + suffix + day[-2:], sex='M')
    schedule = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'doctorId': aid, 'scheduleDate': day, 'fee': 0, 'capacity': 10}, t), '排班')
    rid = ok(call('POST', '/outpatient/registrations', {'patientId': p['id'], 'scheduleId': schedule['id']}, t), '挂号')['id']
    ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, a), 'A接诊')
    return rid

rid = visit(yesterday)
base = f'/outpatient/doctor/{rid}'
ok(call('PUT', base + '/emr', body, a), 'A写历史病历')
item = next(x for x in ok(call('GET', '/masterdata/charge-items', token=t), '项目') if x['category'] == 'LAB' and x.get('enabled', True))
orders = ok(call('POST', base + '/orders', {'lines': [{'orderType': 'LAB', 'itemId': item['id'], 'qty': 1}]}, a), 'A开单')
order_id = orders[0]['id']
before = ok(call('GET', base + '/workspace', token=a), '写前快照')
denied('POST', base + '/start', {}, b, 4036)
denied('PUT', base + '/emr', changed, b, 4036)
denied('POST', base + '/emr/sign', {}, b, 4036)
denied('POST', base + '/emr/amend', {'amendText': '越权', 'reason': '测试'}, b, 4036)
denied('POST', base + '/orders', {'lines': []}, b, 4036)
denied('PUT', f'/outpatient/doctor/orders/{order_id}/cancel', None, b, 4036)
after = ok(call('GET', base + '/workspace', token=a), '写后快照')
assert after['emr'] == before['emr'], (before['emr'], after['emr'])
assert after['orders'] == before['orders'], '越权作废不得修改医嘱'
ok(call('PUT', f'/outpatient/doctor/orders/{order_id}/cancel', None, a), '本人作废仍可用')
print('[1] 历史就诊六种写入口均拒绝，病历和医嘱零变化')

rid = visit(today)
base = f'/outpatient/doctor/{rid}'
first = ok(call('PUT', base + '/emr', body, b), '代班B首写')
assert first['doctorId'] == bid
denied('PUT', base + '/emr', changed, a, 4023)
saved = ok(call('PUT', base + '/emr', changed, t), '管理员修改')
assert saved['doctorId'] == bid, '管理员修改不能接管书写人'
ok(call('POST', base + '/emr/sign', {}, b), 'B签名')
denied('PUT', base + '/emr', changed, b, 4008)
denied('POST', base + '/emr/amend', {'amendText': '他人补正', 'reason': '测试'}, a, 4023)
ok(call('POST', base + '/emr/amend', {'amendText': '本人补正', 'reason': '验证'}, b), 'B补正')
print('[2] 代班首写可用；草稿不被接管、管理员保留书写人、签后原业务码保持')

bed = find_free_bed(t)
p = new_patient(t, 'V82住院' + suffix, sex='F')
adm = ok(call('POST', '/inpatient/admissions', {'patientId': p['id'], 'deptId': 1, 'bedId': bed['id'], 'doctorId': aid, 'deposit': 0, 'payMethod': 'CASH'}, t), '住院登记')['id']
base = f'/inpatient/admissions/{adm}'
try:
    denied('POST', base + '/records', {'content': '非主管创建'}, b, 9130)
    role_denied('POST', base + '/records', {'content': '护士不能写医师病历'})
    record = ok(call('POST', base + '/records', {'recordType': 'PROGRESS', 'content': 'V82住院原文'}, a), '主管创建病历')
    path = base + f"/records/{record['id']}"
    denied('PUT', path, {'content': '越权覆盖'}, b, 9130)
    denied('POST', path + '/sign', {}, b, 9130)
    role_denied('PUT', path, {'content': '护士越权覆盖'})
    ok(call('POST', base + '/vitals', {'temperature': 36.5, 'pulse': 80}, nurse), '护士体征记录')
    saved = ok(call('PUT', path, {'content': 'V82本人住院修改'}, a), '本人修改病历')
    assert saved['doctorId'] == aid
    ok(call('POST', path + '/sign', {}, a), '本人签名')
    denied('PUT', path, {'content': '签后覆盖'}, a, 9103)
    denied('POST', path + '/amend', {'amendText': '他人补正', 'reason': '测试'}, b, 9130)
    ok(call('POST', path + '/amend', {'amendText': '本人补正', 'reason': '验证'}, a), '本人补正')
    kept = next(x for x in ok(call('GET', base + '/records', token=a), '最终病历') if x['id'] == record['id'])
    assert kept['content'] == 'V82本人住院修改' and kept['signature'] and kept['doctorId'] == aid
    print('[3] 住院创建/修改/签名/补正按身份拒绝，护士体征记录正常')
finally:
    discharge_cleanup(t, adm)
print('e2e-v82-write-access 全部通过')
