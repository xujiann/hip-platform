# -*- coding: utf-8 -*-
"""Real JWT doctor writes: shared-day cover, authorship, history and charged cancellation.

Only public APIs and isolated patients/schedules are used. Actual PostgreSQL lock waits
and transaction rollback races live in V110DoctorWriteRaceTest; this HTTP suite does not
claim to reproduce those races. Synthetic VISITED/PASSED registrations remain as history;
all created clinical orders are cancelled and the one cash charge is refunded at the end.
"""
import datetime
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, ok, provision_user, today_bj


tokens = {'admin': login()}
suffix = str(time.time_ns())[-11:]
identities = {}
for who, role in [('owner', 'DOCTOR_OUTP'), ('cover', 'DOCTOR_OUTP'),
                  ('cashier', 'CASHIER'), ('nurse', 'NURSE')]:
    tokens[who] = provision_user(tokens['admin'], 'v110' + who + suffix, role,
                                 'V110' + who + suffix)
    identities[who] = ok(call('GET', '/auth/me', token=tokens[who]), 'identity ' + who)


def expect(code, method, path, body=None, who='admin'):
    try:
        response = call(method, path, body, tokens[who])
    except urllib.error.HTTPError as error:
        assert error.code == 403 and code == 1005, (who, method, path, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


day = today_bj().isoformat()
yesterday = (today_bj() - datetime.timedelta(days=1)).isoformat()
department = expect(0, 'POST', '/system/depts', {
    'code': 'V110D' + suffix, 'name': 'V110医生写入' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
assert identities['cover'].get('deptId') != department['id']
owner_id, cover_id = identities['owner']['id'], identities['cover']['id']
assert owner_id != cover_id
# A small existing LAB item keeps refund below the normal approval threshold without
# changing shared configuration or any seeded item's price/status.
items = expect(0, 'GET', '/masterdata/charge-items')
item = min((row for row in items if row['category'] == 'LAB' and row.get('enabled', True)
            and Decimal('0') < Decimal(str(row['price'])) < Decimal('100')),
           key=lambda row: Decimal(str(row['price'])))
line_body = {'lines': [{'orderType': 'LAB', 'itemId': item['id'], 'qty': 1}]}
draft = {'emr': {'chiefComplaint': 'V110原主诉' + suffix, 'presentIllness': 'V110现病史',
                 'pastHistory': '无', 'physicalExam': '一般情况可', 'advice': '复诊随访'},
         'diagnoses': [{'icdCode': 'J06.9', 'icdName': '急性上呼吸道感染',
                        'certainty': 'CONFIRMED', 'diagSystem': 'ICD10'}]}
changed = {'emr': dict(draft['emr'], chiefComplaint='V110本人修改' + suffix),
           'diagnoses': draft['diagnoses']}
amend = {'amendText': 'V110补充随访意见' + suffix, 'reason': '补充记录'}


def new_schedule(when, capacity):
    return expect(0, 'POST', '/outpatient/schedules', {
        'deptId': department['id'], 'doctorId': owner_id, 'scheduleDate': when,
        'fee': 0, 'capacity': capacity,
    })


def register(schedule, label):
    patient = expect(0, 'POST', '/patients', {'name': 'V110' + label + suffix, 'sex': 'U'})
    result = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    assert result['status'] == 'REGISTERED' and result['fee'] == 0
    assert result['doctorName'] == identities['owner']['realName']
    return result


def base(registration):
    return f"/outpatient/doctor/{registration['id']}"


def workspace(registration):
    return expect(0, 'GET', base(registration) + '/workspace')


def registration_row(registration):
    return next(row for row in expect(0, 'GET', '/outpatient/registrations?date=' + registration['visitDate'])
                if row['id'] == registration['id'])


def schedule_row(schedule):
    return next(row for row in expect(0, 'GET', '/outpatient/schedules?date=' + schedule['scheduleDate'])
                if row['id'] == schedule['id'])


def snapshot(registration, schedule):
    ws = workspace(registration)
    return {
        'workspace': ws,
        'registration': registration_row(registration),
        'schedule': schedule_row(schedule),
        'amendments': expect(0, 'GET', base(registration) + '/emr/amendments'),
        'emrVersions': (expect(0, 'GET', f"/emr/versions/OUTP/{ws['emr']['id']}")
                        if ws['emr'] else None),
        'diagnosisVersions': expect(0, 'GET', f"/emr/versions/diagnoses/{registration['id']}"),
    }


def six_writes(registration, order_id):
    root = base(registration)
    return [
        ('POST', root + '/start', None),
        ('PUT', root + '/emr', changed),
        ('POST', root + '/emr/sign', None),
        ('POST', root + '/emr/amend', amend),
        ('POST', root + '/orders', line_body),
        ('PUT', f'/outpatient/doctor/orders/{order_id}/cancel', None),
    ]


schedule = new_schedule(day, 3)
current = register(schedule, '当日代班')
admin_visit = register(schedule, '管理员接诊')
passed = register(schedule, '过号兼容')
for registration, who in [(current, 'cover'), (admin_visit, 'admin')]:
    started = expect(0, 'POST', base(registration) + '/start', who=who)
    assert started == {'registrationId': registration['id'], 'status': 'VISITED'}
    kept = registration_row(registration)
    assert kept['doctorName'] == identities['owner']['realName']
    assert workspace(registration)['orders'] == [], 'zero registration fee must create no fee order'

# PASSED startVisit is a legacy no-op, for an administrator as well as a covering doctor.
queue = f"/outpatient/queue/call-next?deptId={department['id']}"
called = expect(0, 'POST', queue, who='cover')
assert called['registrationId'] == passed['id']
expect(0, 'POST', f"/outpatient/queue/pass?registrationId={passed['id']}", who='cover')
passed_before = snapshot(passed, schedule)
for who in ['cover', 'admin']:
    assert expect(0, 'POST', base(passed) + '/start', who=who) == {
        'registrationId': passed['id'], 'status': 'PASSED',
    }
    assert snapshot(passed, schedule) == passed_before

first = expect(0, 'PUT', base(current) + '/emr', draft, who='cover')
assert first['doctorId'] == cover_id and first['signature'] is None
orders = expect(0, 'POST', base(current) + '/orders', line_body, who='cover')
assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
order_id = orders[0]['id']

# Method-role refusals keep all clinical/accounting snapshots and each employee's token.
for who in ['cashier', 'nurse']:
    before = snapshot(current, schedule)
    for method, path, body in six_writes(current, order_id):
        expect(1005, method, path, body, who=who)
        assert snapshot(current, schedule) == before, (who, path)
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)

# Same-day shared reception does not grant another doctor authorship of an existing EMR.
before = snapshot(current, schedule)
for method, tail, body in [('PUT', '/emr', changed), ('POST', '/emr/sign', None),
                           ('POST', '/emr/amend', amend)]:
    expect(4023, method, base(current) + tail, body, who='owner')
    assert snapshot(current, schedule) == before
saved = expect(0, 'PUT', base(current) + '/emr', changed, who='cover')
assert saved['doctorId'] == cover_id
saved = expect(0, 'PUT', base(current) + '/emr', draft)
assert saved['doctorId'] == cover_id, 'administrator edits must retain the original author'
expect(4023, 'POST', base(current) + '/emr/sign')
signature = expect(0, 'POST', base(current) + '/emr/sign', who='cover')
assert set(signature) == {'signature', 'signedAt'} and signature['signature']
signed_before = snapshot(current, schedule)
expect(4008, 'PUT', base(current) + '/emr', changed, who='cover')
assert snapshot(current, schedule) == signed_before
expect(4023, 'POST', base(current) + '/emr/amend', amend, who='owner')
assert snapshot(current, schedule) == signed_before
expect(0, 'POST', base(current) + '/emr/amend', amend, who='cover')
assert workspace(current) == signed_before['workspace'], 'amendment must preserve the signed original'
amendments = expect(0, 'GET', base(current) + '/emr/amendments')
assert len(amendments) == 1 and amendments[0]['amended_by'] == cover_id
assert amendments[0]['amend_text'] == amend['amendText']

# Cancel only this unpaid order; an exact repeat retains the existing 4007 business code.
expect(0, 'PUT', f'/outpatient/doctor/orders/{order_id}/cancel', who='cover')
assert workspace(current)['orders'][0]['status'] == 'CANCELLED'
cancelled_before = snapshot(current, schedule)
expect(4007, 'PUT', f'/outpatient/doctor/orders/{order_id}/cancel', who='cover')
assert snapshot(current, schedule) == cancelled_before

# A real CASH settlement must survive rejected doctor cancellation byte-for-byte.
bill_order = expect(0, 'POST', base(current) + '/orders', line_body, who='cover')[0]
charge = expect(0, 'POST', '/outpatient/charges/settle', {
    'registrationId': current['id'], 'payMethod': 'CASH',
}, who='cashier')
assert charge['status'] == 'PAID' and charge['cashierId'] == identities['cashier']['id']
assert Decimal(str(charge['totalAmount'])) == Decimal(str(bill_order['amount']))
charged_before = snapshot(current, schedule)
charged_order = next(row for row in charged_before['workspace']['orders'] if row['id'] == bill_order['id'])
assert charged_order['status'] == 'CHARGED' and charged_order['chargeId'] == charge['id']
expect(4007, 'PUT', f"/outpatient/doctor/orders/{bill_order['id']}/cancel", who='cover')
assert snapshot(current, schedule) == charged_before
recent = next(row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
              if row['chargeId'] == charge['id'])
assert recent['status'] == 'PAID' and Decimal(str(recent['totalAmount'])) == Decimal(str(charge['totalAmount']))

# Yesterday's assigned visit is not shared with a different doctor. Each of the six
# boundaries denies before clinical writes; the order payload has a real item and qty.
history_schedule = new_schedule(yesterday, 1)
history = register(history_schedule, '历史归属')
expect(0, 'POST', base(history) + '/start', who='owner')
expect(0, 'PUT', base(history) + '/emr', draft, who='owner')
history_order = expect(0, 'POST', base(history) + '/orders', line_body, who='owner')[0]
before = snapshot(history, history_schedule)
for method, path, body in six_writes(history, history_order['id']):
    expect(4036, method, path, body, who='cover')
    assert snapshot(history, history_schedule) == before, path
expect(4036, 'GET', base(history) + '/workspace', who='cover')
expect(0, 'PUT', f"/outpatient/doctor/orders/{history_order['id']}/cancel", who='owner')

# Return the actual money before cancelling its now-unpaid order. These are clinical
# VISITED/PASSED histories, so the test does not pretend they can be withdrawn as REGISTERED.
refunded = expect(0, 'POST', f"/outpatient/charges/{charge['id']}/refund", who='cashier')
assert refunded['status'] == 'REFUNDED' and refunded['refundBy'] == identities['cashier']['id']
returned_order = next(row for row in workspace(current)['orders'] if row['id'] == bill_order['id'])
assert returned_order['status'] == 'CREATED' and returned_order['chargeId'] is None
expect(0, 'PUT', f"/outpatient/doctor/orders/{bill_order['id']}/cancel", who='cover')
for registration in [current, admin_visit, passed, history]:
    assert all(row['status'] == 'CANCELLED' and row['chargeId'] is None
               for row in workspace(registration)['orders'])
    unpaid = expect(0, 'GET', f"/outpatient/charges/unpaid?registrationId={registration['id']}", who='cashier')
    assert unpaid['orders'] == [] and Decimal(str(unpaid['total'])) == 0
assert schedule_row(schedule)['booked'] == 3 and schedule_row(history_schedule)['booked'] == 1
assert registration_row(passed)['status'] == 'PASSED'
for who in tokens:
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
print('e2e-v110-doctor-write passed: real ADMIN/covering doctor reception, PASSED no-op, '
      'EMR author/admin-retention/signature freeze/amendment, six-role and six-historical '
      'write refusals with unchanged clinical/version/accounting snapshots; unpaid cancel '
      'and repeat 4007, settled cancel 4007 preserves charge/amount, CASH refunded and '
      'clinical orders cancelled; four synthetic VISITED/PASSED registrations retained, no admission')
