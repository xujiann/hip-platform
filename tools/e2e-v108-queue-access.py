# -*- coding: utf-8 -*-
"""Real JWT queue duties, shared same-day cover and unchanged registration accounting.

Uses an isolated clinical department so call-next never consumes another E2E's queue.
Call-log atomicity and actual lock waits are covered by V108QueueRaceTest; no SQL is
used here, and HTTP assertions do not pretend to observe the internal log table.
"""
import json
import time
import urllib.error

from e2elib import call, login, ok, provision_user, today_bj


admin = login()
suffix = str(time.time_ns())[-11:]
tokens = {'admin': admin}
identities = {}
roles = {
    'doctor': 'DOCTOR_OUTP', 'assigned': 'DOCTOR_OUTP', 'disabled': 'DOCTOR_OUTP',
    'nurse': 'NURSE', 'technician': 'TECHNICIAN', 'cashier': 'CASHIER',
    'quality': 'QUALITY', 'pharmacist': 'PHARMACIST', 'operation': 'OPERATION',
}
for who, role in roles.items():
    tokens[who] = provision_user(admin, 'v108' + who + suffix, role, 'V108' + who + suffix)
    identities[who] = ok(call('GET', '/auth/me', token=tokens[who]), 'identity ' + who)


def expect(code, method, path, body=None, who='admin'):
    try:
        response = call(method, path, body, tokens[who])
    except urllib.error.HTTPError as error:
        assert error.code == (401 if code == 1000 else 403), (who, path, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


department = expect(0, 'POST', '/system/depts', {
    'code': 'V108Q' + suffix, 'name': 'V108叫号验收' + suffix, 'type': 'CLINICAL', 'sortNo': 9999,
})
department_id = department['id']
queue_path = f'/outpatient/queue?deptId={department_id}'
call_path = f'/outpatient/queue/call-next?deptId={department_id}'
readers = ['admin', 'doctor', 'nurse', 'technician', 'cashier']
nonwriters = ['nurse', 'technician', 'cashier', 'quality', 'pharmacist', 'operation', 'portal']
unsupported_readers = ['quality', 'pharmacist', 'operation', 'portal']

patient = expect(0, 'POST', '/patients', {
    'name': 'V108候诊' + suffix, 'sex': 'U', 'phone': '13800001111',
})
tokens['portal'] = ok(call('POST', '/portal/login', {
    'patientNo': patient['patientNo'], 'phone': '13800001111',
}), 'portal login')['token']
expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")

day = today_bj().isoformat()
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department_id, 'doctorId': identities['assigned']['id'],
    'scheduleDate': day, 'capacity': 3, 'fee': 5,
})
assert identities['doctor']['id'] != identities['assigned']['id']
assert identities['doctor'].get('deptId') != department_id

# An empty authorized queue keeps its existing 3301 response.
for who in ['admin', 'doctor']:
    expect(3301, 'POST', call_path, who=who)
registration = expect(0, 'POST', '/outpatient/registrations', {
    'patientId': patient['id'], 'scheduleId': schedule['id'],
}, who='cashier')
registration_id = registration['id']
number = registration['regNo']
pass_path = f'/outpatient/queue/pass?registrationId={registration_id}'
recall_path = f'/outpatient/queue/recall?registrationId={registration_id}'


def registration_row():
    return next(row for row in expect(0, 'GET', '/outpatient/registrations?date=' + day)
                if row['id'] == registration_id)


def schedule_row():
    return next(row for row in expect(0, 'GET', '/outpatient/schedules?date=' + day)
                if row['id'] == schedule['id'])


def fee_rows():
    return expect(0, 'GET', f'/outpatient/doctor/{registration_id}/workspace')['orders']


original_registration = registration_row()
original_schedule = schedule_row()
original_fees = fee_rows()
assert original_registration['doctorName'] == identities['assigned']['realName']
assert original_registration['fee'] == 5 and number == 1
assert original_schedule['booked'] == 1
assert len(original_fees) == 1 and original_fees[0]['orderType'] == 'REG'
assert original_fees[0]['status'] == 'CREATED' and original_fees[0]['amount'] == 5


def assert_state(status):
    expected = dict(original_registration, status=status)
    assert registration_row() == expected
    assert schedule_row() == original_schedule
    assert fee_rows() == original_fees
    board = expect(0, 'GET', queue_path)
    assert board['deptName'] == department['name']
    section = {'REGISTERED': 'waiting', 'CALLED': 'called', 'PASSED': 'passed', 'VISITED': 'visiting'}[status]
    for key in ['waiting', 'called', 'passed', 'visiting']:
        if key == section:
            assert board[key] == [{
                'registrationId': registration_id, 'regNo': number, 'status': status,
                'patientName': patient['name'],
            }], board
        else:
            assert board[key] == [], board
    return board


def denied_writes(path, status):
    before = assert_state(status)
    for who in nonwriters:
        expect(1005, 'POST', path, who=who)
        assert expect(0, 'GET', queue_path) == before, who
    expect(1000, 'POST', path, who='disabled')
    assert_state(status)


# The five original reading roles share today's board; other roles and portal do not.
board = assert_state('REGISTERED')
for who in readers:
    assert expect(0, 'GET', queue_path, who=who) == board
for who in unsupported_readers:
    expect(1005, 'GET', queue_path, who=who)
expect(1000, 'GET', queue_path, who='disabled')
denied_writes(call_path, 'REGISTERED')
expect(3302, 'POST', pass_path, who='doctor')
expect(3303, 'POST', recall_path)
assert_state('REGISTERED')

# ADMIN and an enabled covering doctor may manage another doctor's same-day patient.
called = expect(0, 'POST', call_path)
assert called == {'registrationId': registration_id, 'regNo': number,
                  'status': 'CALLED', 'patientName': patient['name']}
denied_writes(pass_path, 'CALLED')
expect(0, 'POST', pass_path, who='doctor')
assert_state('PASSED')
expect(3302, 'POST', pass_path)
denied_writes(recall_path, 'PASSED')
recalled = expect(0, 'POST', recall_path, who='doctor')
assert recalled == called
assert_state('CALLED')
expect(3303, 'POST', recall_path)

# Covering reception must survive subsequent queue requests without changing ownership or fees.
started = expect(0, 'POST', f'/outpatient/doctor/{registration_id}/start', who='doctor')
assert started == {'registrationId': registration_id, 'status': 'VISITED'}
assert_state('VISITED')
expect(3302, 'POST', pass_path, who='doctor')
expect(3303, 'POST', recall_path)
expect(3301, 'POST', call_path, who='doctor')
assert_state('VISITED')

# A business/role refusal must not invalidate an otherwise usable employee token.
for who in readers + ['quality', 'pharmacist', 'operation', 'assigned']:
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
registrations = expect(0, 'GET', '/portal/my/registrations', who='portal')
assert any(row['id'] == registration_id and row['status'] == 'VISITED' for row in registrations)
print('e2e-v108-queue-access passed: five reading duties, role/portal/disabled JWT rejection, '
      'ADMIN and same-day covering doctor call/pass/recall/start, stable number/owner/capacity/fee; '
      'one synthetic VISITED registration and its uncharged fee retained, no payment or admission')
