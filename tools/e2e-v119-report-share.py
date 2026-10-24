# -*- coding: utf-8 -*-
"""Real JWT regression for RIS report sharing, revocation and anonymous reads.

All patients, orders, payments and two-employee reviewed reports are produced by
public HTTP. No database fixture mutation, shared config change or SQL dependency
is required, including when this script runs from a fresh release package.
Java tests and the separate private browser helper cover historical VERIFIED +
CHARGED/non-EXAM/refunded sources, full-column counters, actual PostgreSQL lock
waits and rollback. Sequential HTTP below does not claim to prove those races.
"""
import json
import re
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, provision_user, today_bj


suffix = str(time.time_ns())[-11:]
tokens = {'admin': provision_user(login(), 'v119admin' + suffix, 'ADMIN')}


def expect(code, method, path, body=None, who='admin', text=None):
    try:
        response = call(method, path, body, tokens.get(who), text=text)
    except urllib.error.HTTPError as error:
        assert (code, error.code) in [(1005, 403), (1000, 401)], (
            who, method, path.split('/share/')[0], code, error.code)
        response = json.load(error)
    # Do not echo JWTs or generated public bearer links into logs on failures.
    assert response['code'] == code, (who, method, path.split('/share/')[0], code,
                                      response.get('code'), response.get('message'))
    if code:
        assert response.get('data') is None, 'Refusal leaked a report response'
    return response.get('data')


identities = {'admin': expect(0, 'GET', '/auth/me')}
for who, role in [('tech', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                  ('cashier', 'CASHIER'), ('pharmacist', 'PHARMACIST'),
                  ('machine', 'INTERFACE'), ('disabled', 'TECHNICIAN')]:
    username = 'v119' + who + suffix
    tokens[who] = provision_user(tokens['admin'], username,
                                 'TECHNICIAN' if role == 'INTERFACE' else role)
    identities[who] = expect(0, 'GET', '/auth/me', who=who)
    if role == 'INTERFACE':
        updated = expect(0, 'PUT', f"/system/users/{identities[who]['id']}", {
            'username': username, 'realName': username, 'roleCodes': ['INTERFACE']})
        assert updated['roleCodes'] == ['INTERFACE'] and updated['enabled']
        tokens[who] = login(username, 'Work@1234')
assert len({person['id'] for person in identities.values()}) == len(tokens)

department = expect(0, 'POST', '/system/depts', {
    'code': 'V119D' + suffix, 'name': 'V119模拟分享门诊' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999})
exam_department = expect(0, 'POST', '/system/depts', {
    'code': 'V119E' + suffix, 'name': 'V119模拟分享影像' + suffix,
    'type': 'MEDTECH', 'sortNo': 9999})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 12})
item_code = 'V119EXAM' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V119模拟超声{suffix},EXAM,次,1.00,,,{exam_department["code"]}\n')
assert imported['imported'] == 1 and imported['errorCount'] == 0
item = next(row for row in expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
            if row['code'] == item_code)


def source(case):
    return next(row for row in expect(0, 'GET',
                f"/outpatient/doctor/{case['registration']['id']}/workspace")['orders']
                if row['id'] == case['order']['id'])


def worklist(case):
    return [row for row in expect(0, 'GET', '/ris/worklist')
            if row['group_no'] == case['order']['groupNo']]


def snapshot(case):
    return {'source': source(case), 'worklist': worklist(case),
            'charges': [row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
                        if row['registrationId'] == case['registration']['id']],
            'reports': expect(0, 'GET', f"/ris/recent-exams?patientId={case['patient']['id']}"),
            'executionReports': expect(0, 'GET', '/outpatient/exec/reports?registrationId=' +
                                       str(case['registration']['id']))}


def visit(label, verified=True):
    patient = expect(0, 'POST', '/patients', {'name': 'V119' + label + suffix, 'sex': 'U'})
    reg = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id']}, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
    orders = expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [{
        'orderType': 'EXAM', 'itemId': item['id'], 'qty': 1,
        'clinicalSummary': 'V119模拟临床摘要', 'examPurpose': 'V119模拟检查目的'}]}, who='doctor')
    assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
    charge = expect(0, 'POST', '/outpatient/charges/settle', {
        'registrationId': reg['id'], 'payMethod': 'CASH'}, who='cashier')
    assert charge['status'] == 'PAID' and Decimal(str(charge['totalAmount'])) == Decimal('1.00')
    assert charge['cashierId'] == identities['cashier']['id']
    case = {'patient': patient, 'registration': reg, 'order': orders[0], 'charge': charge}
    rows = worklist(case)
    assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED'
    case['exam_id'] = rows[0]['id']
    case['findings'], case['impression'] = 'V119模拟所见' + label, 'V119模拟结论' + label
    if verified:
        report(case)
        expect(0, 'PUT', f"/ris/exams/{case['exam_id']}/verify", who='tech')
        assert source(case)['status'] == 'EXECUTED' and worklist(case) == []
        actual = expect(0, 'GET', f"/ris/recent-exams?patientId={patient['id']}")
        assert len(actual) == 1 and actual[0]['id'] == case['exam_id'] and actual[0]['verified_at']
        case['verified_at'] = actual[0]['verified_at']
    return case


def report(case):
    expect(0, 'PUT', f"/ris/exams/{case['exam_id']}/report", {
        'findings': case['findings'], 'impression': case['impression']}, who='doctor')
    assert worklist(case)[0]['status'] == 'REPORTED'
    # Real separate employee review is mandatory; never manufacture VERIFIED.
    expect(9947, 'PUT', f"/ris/exams/{case['exam_id']}/verify", who='doctor')


def share(case, who='tech', minutes=None, code=0):
    query = '' if minutes is None else '?expireMinutes=' + str(minutes)
    answer = expect(code, 'POST', f"/ris/exams/{case['exam_id']}/share" + query, who=who)
    if code == 0:
        assert set(answer) == {'token', 'url', 'expireMinutes'}
        assert re.fullmatch(r'[0-9a-f]{32}', answer['token'])
        assert answer['url'] == '/api/share/' + answer['token']
        assert answer['expireMinutes'] == (4320 if minutes is None else minutes)
    return answer


def read(case, link, code=0):
    answer = expect(code, 'GET', '/share/' + link['token'], who=None)
    if code == 0:
        assert set(answer) == {'patientName', 'itemName', 'modality', 'findings', 'impression', 'verifiedAt'}
        assert answer['patientName'] == case['patient']['name'][0] + '**'
        assert answer['patientName'] != case['patient']['name']
        assert answer['itemName'] == item['name'] and answer['modality'] == 'US'
        assert answer['findings'] == case['findings'] and answer['impression'] == case['impression']
        # SQL Timestamp.toString and JDBC JSON offsets intentionally differ;
        # exact raw format belongs to the dedicated Java contract regression.
        assert answer['verifiedAt'] and 'null' != answer['verifiedAt']
    return answer


def revoke(case, who='tech', code=0):
    answer = expect(code, 'DELETE', f"/ris/exams/{case['exam_id']}/share", who=who)
    if code == 0:
        assert set(answer) == {'revoked'} and type(answer['revoked']) is int
    return answer


# Start the shortest legitimate link first; the remaining cases run during its
# validity interval. The final natural-expiry check waits only the remainder.
expiry_case = visit('自然过期')
expiry_link = share(expiry_case, minutes=1)
expiry_started = time.monotonic()
before = snapshot(expiry_case)
read(expiry_case, expiry_link)
assert snapshot(expiry_case) == before

for who in ['admin', 'tech', 'doctor']:
    case = visit('分享岗位' + who)
    before = snapshot(case)
    first = share(case, who)
    second = share(case, who, 30 * 24 * 60)
    assert first['token'] != second['token']
    assert read(case, first) == read(case, second)
    assert snapshot(case) == before, 'Sharing/anonymous reading changed clinical or billing projections'
    assert revoke(case, who)['revoked'] == 2
    read(case, first, 4662)
    read(case, second, 4662)
    assert revoke(case, who)['revoked'] == 0
    # Revocation must not unpublish the clinical report or permanently prohibit
    # creating a fresh, independently valid link to the same reviewed report.
    renewed = share(case, who, 1)
    assert renewed['token'] not in {first['token'], second['token']}
    read(case, renewed)
    assert revoke(case, who)['revoked'] == 1
    read(case, renewed, 4662)
    assert snapshot(case) == before
print('[1] ADMIN/TECHNICIAN/DOCTOR_OUTP public share, mask, original keys, TTL and revoke/re-share passed')

security = visit('岗位拒绝')
security_link = share(security)
before = snapshot(security)
for who in ['nurse', 'cashier', 'pharmacist', 'machine', None]:
    code = 1000 if who is None else 1005
    share(security, who, code=code)
    revoke(security, who, code)
    read(security, security_link)
    assert snapshot(security) == before, 'Rejected share/revoke changed stored public clinical projections'
    if who not in {'machine', None}:
        expect(0, 'GET', '/auth/me', who=who)
expect(1005, 'GET', '/auth/me', who='machine')
expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")
share(security, 'disabled', code=1000)
revoke(security, 'disabled', 1000)
expect(1000, 'GET', '/auth/me', who='disabled')
read(security, security_link)
assert snapshot(security) == before
for minutes in [0, -1, 43201, 52560000]:
    share(security, minutes=minutes, code=4663)
    assert snapshot(security) == before
assert revoke(security)['revoked'] == 1
read(security, security_link, 4662)
print('[2] Wrong-duty/anonymous/disabled JWT writes and invalid TTL rejected; existing valid link remains available')

missing = {'exam_id': 9000000000000000}
share(missing, code=4660)
assert revoke(missing)['revoked'] == 0
expect(4661, 'GET', '/share/v119-missing-' + suffix, who=None)
pending = visit('登记态不可分享', verified=False)
before = snapshot(pending)
share(pending, code=4660)
assert revoke(pending)['revoked'] == 0 and snapshot(pending) == before
report(pending)
before = snapshot(pending)
share(pending, code=4660)
assert snapshot(pending) == before
refund = expect(0, 'POST', f"/outpatient/charges/{pending['charge']['id']}/refund", who='cashier')
assert refund['status'] == 'REFUNDED'
assert source(pending)['status'] == 'CREATED' and source(pending)['chargeId'] is None
assert worklist(pending) == [], 'v124 filters refunded sources from the active worklist'
# Retained draft columns and real recharge restoring the same E are covered by
# the v124 source-read tests and public refund/recharge regression.
before = snapshot(pending)
share(pending, code=4660)
assert revoke(pending)['revoked'] == 0 and snapshot(pending) == before
# The live refund policy prevents refunding an EXECUTED, reviewed source. Keep
# that policy: old VERIFIED+refunded rows are not created through an illicit API.
before = snapshot(expiry_case)
expect(5005, 'POST', f"/outpatient/charges/{expiry_case['charge']['id']}/refund", who='cashier')
assert snapshot(expiry_case) == before
print('[3] Missing/unreviewed/legally refunded draft refusals, no leaked report and verified refund policy passed')

# Use monotonic elapsed time solely to await the real public minimum TTL. Do not
# revoke or replace this token to simulate natural expiry. Database tests cover
# clock_timestamp after actual lock waits; this is an ordinary HTTP TTL check.
remaining = 61.0 - (time.monotonic() - expiry_started)
if remaining > 0:
    time.sleep(remaining)
before = snapshot(expiry_case)
read(expiry_case, expiry_link, 4662)
read(expiry_case, expiry_link, 4662)
assert revoke(expiry_case)['revoked'] == 0
assert snapshot(expiry_case) == before
print('[4] Genuine one-minute natural expiry returns 4662 repeatedly without exposing report content')

print('e2e-v119-report-share passed: real JWT duties, live employees, reviewed EXAM report share/revoke, '
      'anonymous masked contract, TTL bounds/natural expiry, draft/refund guards and public projection equality. '
      'Historical source/association mutation, counters, transaction rollback and PostgreSQL lock races '
      'are separately verified with Java and dedicated browser fixtures; no SQL mutation here.')
