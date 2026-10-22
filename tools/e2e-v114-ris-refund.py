# -*- coding: utf-8 -*-
"""Real JWT checks for RIS writers and the source-order refund boundary.

Private employees, departments, patients and prices are created with public APIs.
There is no SQL fixture repair and no shared configuration change. Java tests
separately prove PostgreSQL lock waits, full verifier/time attribution and source
types/states that the public API cannot legitimately manufacture. This script
keeps saved reports and critical facts after a legal refund; neither is execution.
"""
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, ok, provision_user, today_bj


suffix = str(time.time_ns())[-11:]
tokens = {'admin': provision_user(login(), 'v114admin' + suffix, 'ADMIN')}
machine_identity = None
for who, role in [('technician', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'),
                  ('nurse', 'NURSE'), ('cashier', 'CASHIER'),
                  ('pharmacist', 'PHARMACIST'), ('interface', 'INTERFACE'),
                  ('disabled', 'TECHNICIAN')]:
    username = 'v114' + who + suffix
    real_name = 'V114' + who + suffix
    # Pure machine accounts cannot use employee change-password. Initialize as
    # a private employee first, then assign the final sole machine duty.
    tokens[who] = provision_user(tokens['admin'], username,
                                 'TECHNICIAN' if role == 'INTERFACE' else role, real_name)
    if role == 'INTERFACE':
        employee = ok(call('GET', '/auth/me', token=tokens[who]), 'initial machine employee')
        machine_identity = ok(call('PUT', f"/system/users/{employee['id']}", {
            'username': username, 'realName': real_name, 'roleCodes': ['INTERFACE'],
        }, tokens['admin']), 'assign private interface duty')
        assert machine_identity['id'] == employee['id']
        assert machine_identity['roleCodes'] == ['INTERFACE'] and machine_identity['enabled']
        tokens[who] = login(username, 'Work@1234')


def expect(code, method, path, body=None, who='admin', text=None):
    try:
        response = call(method, path, body, tokens.get(who), text=text)
    except urllib.error.HTTPError as error:
        assert (code, error.code) in [(1005, 403), (1000, 401)], (
            who, method, path, code, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


identities = {who: expect(0, 'GET', '/auth/me', who=who) for who in tokens if who != 'interface'}
identities['interface'] = machine_identity
assert len({person['id'] for person in identities.values()}) == len(tokens)
for who in ['admin', 'technician', 'doctor', 'nurse', 'cashier', 'pharmacist']:
    expect(0, 'GET', '/stats/overview', who=who)
expect(1005, 'GET', '/auth/me', who='interface')
expect(1005, 'GET', '/stats/overview', who='interface')

department = expect(0, 'POST', '/system/depts', {
    'code': 'V114D' + suffix, 'name': 'V114临床模拟' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
exam_department = expect(0, 'POST', '/system/depts', {
    'code': 'V114E' + suffix, 'name': 'V114影像模拟' + suffix,
    'type': 'MEDTECH', 'sortNo': 9999,
})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': today_bj().isoformat(),
    'fee': 0, 'capacity': 30,
})
item_code = 'V114EXAM' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V114模拟超声{suffix},EXAM,次,1.00,,,{exam_department["code"]}\n')
assert imported['imported'] == 1 and imported['errorCount'] == 0, imported
exam_item = next(row for row in expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
                 if row['code'] == item_code)
portal_tokens = {}


def source(case):
    orders = expect(0, 'GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")['orders']
    return next(row for row in orders if row['id'] == case['order']['id'])


def bill(case):
    return next(row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
                if row['chargeId'] == case['charge']['id'])


def worklist(case, who='admin', modality=None):
    query = '' if modality is None else '?modality=' + modality
    return [row for row in expect(0, 'GET', '/ris/worklist' + query, who=who)
            if row['group_no'] == case['order']['groupNo']]


def alerts(case):
    return [row for row in expect(0, 'GET', '/outpatient/critical-alerts?status=NEW')
            if row['patientNo'] == case['patient']['patientNo']]


def recent(case):
    return expect(0, 'GET', f"/ris/recent-exams?patientId={case['patient']['id']}")


def portal_reports(case):
    return ok(call('GET', '/portal/my/exam-reports',
                   token=portal_tokens[case['patient']['id']]), 'private patient reports')


def snapshot(case):
    return {'source': source(case), 'bill': bill(case), 'exam': worklist(case),
            'alerts': alerts(case), 'recent': recent(case), 'portal': portal_reports(case),
            'executionReports': expect(0, 'GET', '/outpatient/exec/reports?registrationId=' +
                                       str(case['registration']['id']))}


def visit(label):
    phone = '139' + str(time.time_ns())[-8:]
    patient = expect(0, 'POST', '/patients', {'name': 'V114' + label + suffix,
                                           'sex': 'U', 'phone': phone})
    reg = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
    created = expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [{
        'orderType': 'EXAM', 'itemId': exam_item['id'], 'qty': 1,
        'clinicalSummary': 'V114模拟摘要', 'examPurpose': 'V114模拟目的',
        'notice': 'V114模拟注意', 'urgent': True,
    }]}, who='doctor')
    assert len(created) == 1 and created[0]['status'] == 'CREATED', created
    case = {'patient': patient, 'registration': reg, 'order': created[0]}
    charge = expect(0, 'POST', '/outpatient/charges/settle', {
        'registrationId': reg['id'], 'payMethod': 'CASH',
    }, who='cashier')
    assert charge['status'] == 'PAID' and Decimal(str(charge['totalAmount'])) == Decimal('1.00')
    assert charge['cashierId'] == identities['cashier']['id']
    case['charge'] = charge
    assert source(case)['status'] == 'CHARGED' and source(case)['chargeId'] == charge['id']
    rows = worklist(case)
    assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED', rows
    case['exam_id'] = rows[0]['id']
    portal_tokens[patient['id']] = ok(call('POST', '/portal/login', {
        'patientNo': patient['patientNo'], 'phone': phone,
    }), 'private patient login')['token']
    assert recent(case) == [] and portal_reports(case) == [] and alerts(case) == []
    return case


def report_body(label):
    label = str(label)
    return {'findings': 'V114模拟所见' + label, 'impression': 'V114模拟结论' + label}


def write(case, action, who='technician', code=0, body=None):
    if action == 'report' and body is None:
        body = report_body(who)
    if action == 'critical' and body is None:
        body = {'note': 'V114模拟危急征象'}
    return expect(code, 'PUT', f"/ris/exams/{case['exam_id']}/{action}", body, who=who)


def assert_refused(case, action, code, who='technician', body=None):
    before = snapshot(case)
    write(case, action, who, code, body)
    assert snapshot(case) == before, ('RIS rejection changed clinical/billing facts', action, who)


def unchanged_source(before, after, status):
    assert after['status'] == status, after
    assert {key: value for key, value in before.items() if key != 'status'} == {
        key: value for key, value in after.items() if key != 'status'
    }, ('RIS changed source metadata or billing ownership', before, after)


def refund(case, code=0):
    before = snapshot(case)
    returned = expect(code, 'POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier')
    if code:
        assert snapshot(case) == before, 'refund rejection changed clinical/billing facts'
    else:
        assert returned['status'] == 'REFUNDED' and bill(case)['status'] == 'REFUNDED'
        assert source(case)['status'] == 'CREATED' and source(case)['chargeId'] is None
        assert worklist(case) == [dict(before['exam'][0], order_status='CREATED')]
        assert alerts(case) == before['alerts'] and recent(case) == before['recent']
        assert portal_reports(case) == before['portal'] == []


expected_keys = {'id', 'status', 'modality', 'findings', 'impression', 'item_name', 'group_no',
                 'clinical_summary', 'exam_purpose', 'notice', 'urgent', 'patient_name',
                 'diagnosis', 'order_status'}
# Positive baseline: all four existing roles can arrive, write and mark critical;
# a different existing role can continue a draft. The latest author cannot self-review.
roles = ['admin', 'technician', 'doctor', 'nurse']
for index, who in enumerate(roles):
    case = visit('岗位正向' + who)
    before_order = source(case)
    for reader in roles:
        row = worklist(case, reader)[0]
        assert set(row) == expected_keys, row
        assert row['order_status'] == 'CHARGED' and row['modality'] == 'US'
        assert row['clinical_summary'] == 'V114模拟摘要' and row['exam_purpose'] == 'V114模拟目的'
        assert row['notice'] == 'V114模拟注意' and row['urgent'] is True
        assert row['patient_name'] == case['patient']['name']
        assert worklist(case, reader, 'US') == [row] and worklist(case, reader, 'ECG') == []
    assert_refused(case, 'verify', 9945, who)
    assert_refused(case, 'critical', 9945, who)
    write(case, 'arrive', who)
    assert worklist(case)[0]['status'] == 'ARRIVED'
    assert_refused(case, 'arrive', 9944, who)
    write(case, 'report', who)
    editor = roles[(index + 1) % len(roles)]
    final_body = report_body('他人续写' + editor)
    write(case, 'report', editor, body=final_body)
    row = worklist(case)[0]
    assert row['status'] == 'REPORTED' and row['findings'] == final_body['findings']
    assert row['impression'] == final_body['impression']
    unchanged_source(before_order, source(case), 'CHARGED')
    assert_refused(case, 'verify', 9947, editor)
    assert_refused(case, 'critical', 9948, who, {'note': '  '})
    # First three exercise draft critical facts; the nurse case also proves the
    # VERIFIED path without asking the nurse's deliberately narrower share API.
    if who != 'nurse':
        write(case, 'critical', who)
        unchanged_source(before_order, source(case), 'CHARGED')
    write(case, 'verify', who)
    unchanged_source(before_order, source(case), 'EXECUTED')
    assert worklist(case) == [] and bill(case)['status'] == 'PAID'
    assert len(recent(case)) == 1 and recent(case)[0]['impression'] == final_body['impression']
    actual = portal_reports(case)
    assert len(actual) == 1 and actual[0]['report_type'] == 'EXAM'
    assert actual[0]['detail'] == final_body['findings'] and actual[0]['conclusion'] == final_body['impression']
    assert actual[0]['report_date'] and recent(case)[0]['verified_at']
    if who == 'nurse':
        write(case, 'critical', who)
    assert len(alerts(case)) == 1 and '【影像危急值】' in alerts(case)[0]['content']
    notified = [row for row in expect(0, 'GET', '/outpatient/critical-alerts/my-pending', who='doctor')
                if row['patient_name'] == case['patient']['name']]
    assert len(notified) == 1 and notified[0]['source'] == 'RIS'
    assert_refused(case, 'critical', 9949, who)
    assert_refused(case, 'verify', 9945, who)
    assert_refused(case, 'report', 9944, editor)
    assert_refused(case, 'arrive', 9944, who)
    refund(case, 5005)
print('[1] Four existing roles, cross-employee drafts, self-review, critical uniqueness and verified refund guard passed')

pending = visit('岗位拒绝')
for who in ['cashier', 'pharmacist', 'interface', None]:
    code = 1000 if who is None else 1005
    for action in ['arrive', 'report', 'verify', 'critical']:
        assert_refused(pending, action, code, who)
    if who is not None:
        expect(1005 if who == 'interface' else 0, 'GET', '/auth/me', who=who)
expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")
for action in ['arrive', 'report', 'verify', 'critical']:
    assert_refused(pending, action, 1000, 'disabled')
expect(1000, 'GET', '/auth/me', who='disabled')
missing_id = 9000000000000000
for action, code in [('arrive', 9944), ('report', 9944), ('verify', 9945), ('critical', 9945)]:
    body = report_body('不存在') if action == 'report' else {'note': 'V114模拟'} if action == 'critical' else None
    before = snapshot(pending)
    expect(code, 'PUT', f'/ris/exams/{missing_id}/{action}', body, who='technician')
    assert snapshot(pending) == before
print('[2] Real JWT wrong-duty, disabled, anonymous and absent-exam rejections preserve all projected facts')

# Refund wins before arrival. A later stale report/verify/critical must not turn
# the retained REGISTERED exam into a clinical publication or a critical alert.
registered_refund = visit('登记态退费')
refund(registered_refund)
for action, code in [('arrive', 9944), ('report', 9944), ('verify', 9945), ('critical', 9945)]:
    assert_refused(registered_refund, action, code)

# A legitimate draft is not execution. Refund keeps its contents and original
# source association; source rejection takes precedence even for its own author.
draft_refund = visit('草稿先退费')
write(draft_refund, 'report', 'doctor', body=report_body('保留旧草稿'))
refund(draft_refund)
for action, code in [('arrive', 9944), ('report', 9944), ('verify', 9945), ('critical', 9945)]:
    assert_refused(draft_refund, action, code, 'doctor')
assert worklist(draft_refund)[0]['status'] == 'REPORTED'
assert worklist(draft_refund)[0]['findings'] == report_body('保留旧草稿')['findings']

# A critical draft is still refundable. Its existing alert survives the legal
# refund, but stale marking cannot duplicate it and stale review cannot publish.
critical_refund = visit('危急草稿退费')
write(critical_refund, 'report', 'doctor')
write(critical_refund, 'critical', 'nurse')
assert source(critical_refund)['status'] == 'CHARGED' and len(alerts(critical_refund)) == 1
refund(critical_refund)
for action, code in [('arrive', 9944), ('report', 9944), ('verify', 9945), ('critical', 9945)]:
    assert_refused(critical_refund, action, code)
assert len(alerts(critical_refund)) == 1
for who in roles:
    for case in [registered_refund, draft_refund, critical_refund]:
        row = worklist(case, who)[0]
        assert row['order_status'] == 'CREATED' and set(row) == expected_keys
        assert row['group_no'] == case['order']['groupNo']
print('[3] Refund-first REGISTERED/REPORTED/critical drafts reject all four stale writes without changing retained facts')

# Actual repayment makes the existing exam actionable again; this is a real new
# cash bill, not an SQL restoration of refunded ownership or clinical history.
old_charge_id = registered_refund['charge']['id']
registered_refund['charge'] = expect(0, 'POST', '/outpatient/charges/settle', {
    'registrationId': registered_refund['registration']['id'], 'payMethod': 'CASH',
}, who='cashier')
assert registered_refund['charge']['id'] != old_charge_id
assert next(row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
            if row['chargeId'] == old_charge_id)['status'] == 'REFUNDED'
assert source(registered_refund)['chargeId'] == registered_refund['charge']['id']
assert worklist(registered_refund)[0]['status'] == 'REGISTERED'
write(registered_refund, 'arrive', 'nurse')
write(registered_refund, 'report', 'doctor')
write(registered_refund, 'verify', 'technician')
assert source(registered_refund)['status'] == 'EXECUTED' and len(portal_reports(registered_refund)) == 1
refund(registered_refund, 5005)

# Preserve collaboration with the generic execution station. A valid already
# EXECUTED EXAM may still finish its distinct RIS report and review workflow.
executed = visit('执行态兼容')
expect(0, 'POST', f"/outpatient/exec/{executed['order']['id']}",
       {'resultText': 'V114模拟通用执行'}, who='technician')
before_executed = source(executed)
assert before_executed['status'] == 'EXECUTED'
before_reports = snapshot(executed)['executionReports']
assert len(before_reports) == 1
write(executed, 'arrive', 'nurse')
write(executed, 'report', 'doctor')
write(executed, 'critical', 'technician')
write(executed, 'verify', 'admin')
assert source(executed) == before_executed
assert snapshot(executed)['executionReports'] == before_reports
assert len(portal_reports(executed)) == 1 and len(alerts(executed)) == 1
refund(executed, 5005)
print('[4] Actual repayment recovery and pre-existing EXECUTED source compatibility passed')

print('e2e-v114-ris-refund passed: real JWT RIS writer duties, current source worklist, '
      'draft/critical preservation, other-employee review, refund/publication order and '
      'repayment/EXECUTED compatibility. No SQL state mutation or shared configuration change. '
      'Lock waits and non-public verifier/actor cases are verified separately by Java tests.')
