# -*- coding: utf-8 -*-
"""Clinical inpatient reads: real JWT scope, parent relation and role usability."""
import json
import time
import urllib.error
from e2elib import call, login, ok, provision_user, new_patient, find_free_bed, discharge_cleanup

t = login()
suffix = str(time.time_ns())[-10:]
a = provision_user(t, 'e2e86a' + suffix, 'DOCTOR_OUTP', 'V86主管A')
b = provision_user(t, 'e2e86b' + suffix, 'DOCTOR_OUTP', 'V86接手B')
n = provision_user(t, 'e2e86n' + suffix, 'NURSE', 'V86护士')
aid = ok(call('GET', '/auth/me', token=a), 'A身份')['id']
bid = ok(call('GET', '/auth/me', token=b), 'B身份')['id']
admissions = []


def admit():
    p = new_patient(t, 'V86读取验证' + str(time.time_ns()), sex='U')
    bed = find_free_bed(t)
    adm = ok(call('POST', '/inpatient/admissions', {
        'patientId': p['id'], 'deptId': 1, 'bedId': bed['id'],
        'doctorId': aid, 'deposit': 0, 'payMethod': 'CASH'}, t), '入院')['id']
    admissions.append(adm)
    return adm


def denied(path, token):
    try:
        response = call('GET', path, token=token)
    except urllib.error.HTTPError as ex:
        assert ex.code == 403, ex
        response = json.load(ex)
    assert response['code'] == 1005 and response.get('data') is None, response


try:
    adm = admit()
    other = admit()
    base = f'/inpatient/admissions/{adm}'
    rid = ok(call('POST', base + '/records/round', {
        'roundLevel': 'RESIDENT', 'roundOpinion': 'V86虚构查房正文'}, a), '查房')['id']
    ok(call('POST', f'{base}/records/{rid}/sign', {}, a), '签名')
    ok(call('POST', f'{base}/records/{rid}/amend', {
        'amendText': 'V86虚构补正正文', 'reason': '权限验收'}, a), '补正')
    paths = [base + '/records', base + '/records/rounds',
             f'{base}/records/{rid}/amendments', base + '/workspace', base + '/print/discharge-summary']
    for path in paths:
        denied(path, b)
        for token in [a, n, t]:
            ok(call('GET', path, token=token), '合法读取')
    mismatch = f'/inpatient/admissions/{other}/records/{rid}/amendments'
    for token in [a, n, t]:
        response = call('GET', mismatch, token=token)
        assert response['code'] == 9102 and response.get('data') is None, response
    assert ok(call('GET', paths[2], token=a), '本人补正')[0]['amend_text'] == 'V86虚构补正正文'
    ok(call('PUT', base + '/attending-doctor', {'doctorId': bid}, a), '移交')
    for path in paths:
        denied(path, a)
        ok(call('GET', path, token=b), '新主管读取')
    assert ok(call('GET', '/auth/me', token=a), '拒绝后登录保持')['id'] == aid
    ok(call('POST', base + '/vitals', {'temperature': 36.5, 'pulse': 80}, n), '护理仍可用')
    print('e2e-v86-inp-read-access 全部通过：五入口对象范围、补正父子关系、主管移交及护理/管理员可用')
finally:
    for adm in reversed(admissions):
        discharge_cleanup(t, adm)
