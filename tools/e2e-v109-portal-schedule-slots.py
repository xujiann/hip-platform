# -*- coding: utf-8 -*-
"""Real portal/staff JWTs: stopped schedules hide slots without losing history or returns.

Only public APIs are used. Slot/schedule occupancy and receipts are read back after each
refusal/return; the next successful receipt proves rejected requests did not issue a number.
"""
import json
import time
import urllib.error

from e2elib import call, login, ok, provision_user, today_bj

admin = login()
suffix = str(time.time_ns())[-11:]
tokens = {'admin': admin}
tokens['cashier'] = provision_user(admin, 'v109cash' + suffix, 'CASHIER', 'V109时段验收' + suffix)


def expect(code, method, path, body=None, who='admin'):
    try:
        response = call(method, path, body, tokens[who])
    except urllib.error.HTTPError as error:
        assert error.code in (401, 403), (who, method, path, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


department = expect(0, 'POST', '/system/depts', {
    'code': 'V109S' + suffix, 'name': 'V109患者时段' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
day = today_bj().isoformat()
base = '/outpatient/schedules'
schedule = expect(0, 'POST', base, {
    'deptId': department['id'], 'scheduleDate': day, 'capacity': 2, 'fee': 5,
})
sid = schedule['id']
staff_slots_path = f'{base}/{sid}/slots'
portal_slots_path = f'/portal/schedules/{sid}/slots'
# Creation order deliberately differs from display order.
expect(0, 'POST', staff_slots_path, {'slots': [
    {'timeBegin': '09:00', 'timeEnd': '09:30', 'capacity': 1},
    {'timeBegin': '08:00', 'timeEnd': '08:30', 'capacity': 1},
]})

patients = {}
for who, label in [('a', '原预约患者'), ('b', '另一患者')]:
    patients[who] = expect(0, 'POST', '/patients', {
        'name': 'V109' + label + suffix, 'sex': 'U', 'phone': '13800001111',
    })
    tokens[who] = ok(call('POST', '/portal/login', {
        'patientNo': patients[who]['patientNo'], 'phone': '13800001111',
    }), 'portal login ' + who)['token']

keys = {'id', 'time_begin', 'time_end', 'capacity', 'booked', 'remaining', 'enabled'}
rows = expect(0, 'GET', portal_slots_path, who='a')
assert len(rows) == 2 and all(set(row) == keys for row in rows), rows
assert [str(row['time_begin'])[:5] for row in rows] == ['08:00', '09:00'], rows
assert all(row['enabled'] and row['remaining'] == 1 for row in rows), rows
slot1, slot2 = [row['id'] for row in rows]


def schedule_row():
    return next(row for row in expect(0, 'GET', base + '?date=' + day + '&includeDisabled=true')
                if row['id'] == sid)


def pools(total, first, second):
    current = schedule_row()
    assert current['booked'] == total and current['remaining'] == 2 - total, current
    slots = expect(0, 'GET', staff_slots_path, who='cashier')
    assert len(slots) == 2 and all(set(row) == keys for row in slots), slots
    assert [row['id'] for row in slots] == [slot1, slot2], slots
    assert [row['booked'] for row in slots] == [first, second], slots
    assert [row['remaining'] for row in slots] == [1 - first, 1 - second], slots
    assert all(row['enabled'] and row['capacity'] == 1 for row in slots), slots
    return current, slots


def my_appointments(who='a'):
    return expect(0, 'GET', '/portal/my/appointments', who=who)


def receipt(appt_id, who='a'):
    return next(row for row in my_appointments(who) if row['id'] == appt_id)


def catalog_contains(who='a'):
    return any(row['id'] == sid for row in expect(0, 'GET', '/portal/schedules?date=' + day, who=who))


# Cross-surface roles remain isolated even though the slot projections share a service.
expect(1005, 'GET', portal_slots_path, who='cashier')
expect(1005, 'GET', staff_slots_path, who='a')
assert expect(0, 'GET', '/portal/schedules/-109/slots', who='a') == []
assert catalog_contains()
assert expect(0, 'GET', portal_slots_path, who='b') == rows
pools(0, 0, 0)

first = expect(0, 'POST', '/portal/appointments', {'slotId': slot1}, who='a')
assert first['apptNo'] == 1
first_id = first['id']
initial_receipt = receipt(first_id)
assert initial_receipt['status'] == 'BOOKED' and initial_receipt['source'] == 'PORTAL'
assert initial_receipt['registrationId'] is None
full_rows = expect(0, 'GET', portal_slots_path, who='a')
assert len(full_rows) == 2 and full_rows[0]['remaining'] == 0 and full_rows[1]['remaining'] == 1, full_rows
pools(1, 1, 0)

# Disabling the parent is a read restriction, not cancellation of an existing receipt.
expect(0, 'PUT', f'{base}/{sid}/enabled?enabled=false')
assert not catalog_contains('a') and not catalog_contains('b')
stopped_snapshot = pools(1, 1, 0)
assert not stopped_snapshot[0]['enabled']
for who in ['a', 'b']:
    assert expect(0, 'GET', portal_slots_path, who=who) == []
    assert expect(0, 'GET', portal_slots_path + '?includeDisabled=true&onlyEnabled=false', who=who) == []
assert receipt(first_id) == initial_receipt
assert my_appointments('b') == []

# An eligible different patient uses a non-full slot, isolating the stopped-parent gate.
expect(3111, 'POST', '/portal/appointments', {'slotId': slot2}, who='b')
expect(3003, 'POST', '/portal/register', {'scheduleId': sid}, who='b')
expect(9506, 'POST', f'/portal/my/appointments/{first_id}/cancel', who='b')
assert pools(1, 1, 0) == stopped_snapshot
assert receipt(first_id) == initial_receipt
assert my_appointments('b') == []
assert expect(0, 'GET', '/portal/my/registrations', who='b') == []
assert expect(0, 'GET', '/portal/my/pending-bill', who='b') == []

expect(0, 'POST', f'/portal/my/appointments/{first_id}/cancel', who='a')
pools(0, 0, 0)
returned = receipt(first_id)
assert returned['status'] == 'CANCELLED' and returned['apptNo'] == 1
for key in ['id', 'source', 'scheduleDate', 'timeBegin', 'timeEnd', 'createdAt', 'registrationId']:
    assert returned[key] == initial_receipt[key], (key, returned)
assert expect(0, 'GET', portal_slots_path, who='a') == []

# Reopening exposes the original slots; failed requests/returns did not reuse or issue numbers.
expect(0, 'PUT', f'{base}/{sid}/enabled?enabled=true')
assert catalog_contains()
assert expect(0, 'GET', portal_slots_path, who='a') == rows
second = expect(0, 'POST', '/portal/appointments', {'slotId': slot1}, who='a')
assert second['apptNo'] == 2, second
pools(1, 1, 0)
expect(0, 'POST', f"/portal/my/appointments/{second['id']}/cancel", who='a')
pools(0, 0, 0)
assert sorted(row['apptNo'] for row in my_appointments()) == [1, 2]
assert all(row['status'] == 'CANCELLED' for row in my_appointments())
assert expect(0, 'GET', portal_slots_path, who='a') == rows
for who in ['a', 'b']:
    assert expect(0, 'GET', '/portal/my/registrations', who=who) == []
    assert expect(0, 'GET', '/portal/my/pending-bill', who=who) == []
    assert expect(0, 'GET', '/portal/my/charges', who=who) == []
for who in ['admin', 'cashier']:
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
print('e2e-v109-portal-schedule-slots passed: parent stop hides known-ID slots and query flags cannot widen access; '
      'staff retains occupancy reads, full slot remains visible while enabled, own history/stop-time cancellation '
      'and cross-person refusal preserved; reopening keeps slots and issues number 2, both pools returned, '
      'no registration, fee, payment or admission created')
