# -*- coding: utf-8 -*-
"""Real JWT checks for LIS collection/receipt and the source-refund boundary.

All fixtures use public APIs and private employees, patients, prices and visits.
Collection and receipt retain physical history but are not order execution.
Real PostgreSQL waits, hidden received/verifier timestamps, cached identities and
managed stale entities are separately tested by the Java regression suite.

No shared configuration is changed. The migration default and preceding CI
phase3940 leave lis_allow_substitute=true. For an independently configured server
set HIP_E2E_LIS_SUBSTITUTE_ALLOWED=false to test its existing forbidden setting.
"""
import json
import os
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, ok, provision_user, q, today_bj


suffix = str(time.time_ns())[-11:]
tokens = {'admin': provision_user(login(), 'v115admin' + suffix, 'ADMIN')}
machine_identity = None
for who, role in [('technician', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'),
                  ('nurse', 'NURSE'), ('cashier', 'CASHIER'),
                  ('pharmacist', 'PHARMACIST'), ('interface', 'INTERFACE'),
                  ('disabled', 'TECHNICIAN')]:
    username, name = 'v115' + who + suffix, 'V115' + who + suffix
    # Pure machine accounts cannot use employee change-password. Complete the
    # normal employee initialization before assigning the sole machine role.
    tokens[who] = provision_user(tokens['admin'], username,
                                 'TECHNICIAN' if role == 'INTERFACE' else role, name)
    if role == 'INTERFACE':
        person = ok(call('GET', '/auth/me', token=tokens[who]), 'machine initial employee')
        machine_identity = ok(call('PUT', f"/system/users/{person['id']}", {
            'username': username, 'realName': name, 'roleCodes': ['INTERFACE'],
        }, tokens['admin']), 'private machine duty')
        assert machine_identity['id'] == person['id']
        assert machine_identity['enabled'] and machine_identity['roleCodes'] == ['INTERFACE']
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

clinic = expect(0, 'POST', '/system/depts', {
    'code': 'V115D' + suffix, 'name': 'V115临床模拟' + suffix, 'type': 'CLINICAL', 'sortNo': 9999,
})
lab_dept = expect(0, 'POST', '/system/depts', {
    'code': 'V115L' + suffix, 'name': 'V115执行模拟' + suffix, 'type': 'MEDTECH', 'sortNo': 9999,
})
other_dept = expect(0, 'POST', '/system/depts', {
    'code': 'V115O' + suffix, 'name': 'V115空执行模拟' + suffix, 'type': 'MEDTECH', 'sortNo': 9999,
})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': clinic['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 30,
})
item_code, exam_code = 'V115LAB' + suffix, 'V115EXAM' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V115模拟检验{suffix},LAB,次,1.00,,,{lab_dept["code"]}\n' +
                  f'{exam_code},V115模拟影像{suffix},EXAM,次,1.00,,,{lab_dept["code"]}\n')
assert imported['imported'] == 2 and imported['errorCount'] == 0, imported
items = expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
lab = next(row for row in items if row['code'] == item_code)
exam = next(row for row in items if row['code'] == exam_code)


def source(case):
    orders = expect(0, 'GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")['orders']
    return next(row for row in orders if row['id'] == case['order']['id'])


def bills(case):
    return [row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
            if row['chargeId'] in case['charge_ids']]


def samples(case, who='admin', dept_id=None):
    query = '' if dept_id is None else '?deptId=' + str(dept_id)
    return [row for row in expect(0, 'GET', '/lis/samples' + query, who=who)
            if row['order_id'] == case['order']['id']]


def pending(case, who='admin', dept_id=None):
    query = '' if dept_id is None else '?deptId=' + str(dept_id)
    return [row for row in expect(0, 'GET', '/lis/pending' + query, who=who)
            if row['order_id'] == case['order']['id']]


def snapshot(case):
    return {'source': source(case), 'bills': bills(case), 'samples': samples(case),
            'pending': pending(case),
            'results': expect(0, 'GET', f"/outpatient/lab-results?orderId={case['order']['id']}"),
            'alerts': [row for row in expect(0, 'GET', '/outpatient/critical-alerts?status=NEW')
                       if row['patientNo'] == case['patient']['patientNo']],
            'executionReports': expect(0, 'GET', '/outpatient/exec/reports?registrationId=' +
                                       str(case['registration']['id']))}


def settle(case):
    charge = expect(0, 'POST', '/outpatient/charges/settle', {
        'registrationId': case['registration']['id'], 'payMethod': 'CASH',
    }, who='cashier')
    assert charge['status'] == 'PAID' and Decimal(str(charge['totalAmount'])) == Decimal('1.00')
    assert charge['cashierId'] == identities['cashier']['id']
    assert charge['id'] not in case['charge_ids']
    case['charge_ids'].append(charge['id'])
    case['charge'] = charge
    assert source(case)['status'] == 'CHARGED' and source(case)['chargeId'] == charge['id']
    return charge


def visit(label, charged=True, order_type='LAB'):
    patient = expect(0, 'POST', '/patients', {'name': 'V115' + label + suffix, 'sex': 'U'})
    reg = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
    created = expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [{
        'orderType': order_type, 'itemId': (lab if order_type == 'LAB' else exam)['id'], 'qty': 1,
        'specimenType': '模拟血清', 'samplingSite': '模拟部位', 'urgent': True, 'remark': 'V115模拟标本',
    }]}, who='doctor')
    assert len(created) == 1 and created[0]['status'] == 'CREATED', created
    case = {'patient': patient, 'registration': reg, 'order': created[0], 'charge_ids': []}
    if charged:
        settle(case)
    assert samples(case) == []
    return case


def collect(case, who='technician', code=0, substitute=None):
    path = f"/lis/samples?orderId={case['order']['id']}"
    if substitute is not None:
        path += '&substituteName=' + q(substitute)
    response = expect(code, 'POST', path, who=who)
    if code == 0:
        assert set(response) == {'barcode'} and response['barcode'].startswith('BC'), response
        case['barcode'] = response['barcode']
    return response


def receive(case, who='technician', code=0):
    return expect(code, 'PUT', f"/lis/samples/{case['barcode']}/receive", who=who)


def refused(case, action, code, who='technician', substitute=None):
    before = snapshot(case)
    if action == 'collect':
        collect(case, who, code, substitute)
    else:
        receive(case, who, code)
    assert snapshot(case) == before, ('LIS refusal changed projected clinical/billing facts', action, who)


def refund(case):
    before = snapshot(case)
    response = expect(0, 'POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier')
    assert response['status'] == 'REFUNDED'
    actual = source(case)
    assert actual['status'] == 'CREATED' and actual['chargeId'] is None
    assert {k: v for k, v in actual.items() if k not in {'status', 'chargeId'}} == {
        k: v for k, v in before['source'].items() if k not in {'status', 'chargeId'}}
    assert next(row for row in bills(case) if row['chargeId'] == case['charge']['id'])['status'] == 'REFUNDED'
    assert samples(case) == [dict(row, order_status='CREATED') for row in before['samples']]
    assert pending(case) == []
    after = snapshot(case)
    for name in ['results', 'alerts', 'executionReports']:
        assert after[name] == before[name], name


sample_keys = {'id', 'barcode', 'status', 'collected_at', 'substitute', 'substitute_name',
               'order_id', 'item_name', 'group_no', 'specimen_type', 'sampling_site',
               'urgent', 'remark', 'patient_name', 'exec_dept_id', 'exec_dept_name', 'order_status'}
pending_keys = {'order_id', 'group_no', 'item_name', 'patient_name', 'sex', 'specimen_type',
                'sampling_site', 'urgent', 'remark', 'exec_dept_id', 'exec_dept_name'}
roles = ['admin', 'technician', 'doctor', 'nurse']
for index, who in enumerate(roles):
    case = visit('岗位正向' + who)
    before_order, before_bills = source(case), bills(case)
    for reader in roles:
        row = pending(case, reader)[0]
        assert set(row) == pending_keys and row['patient_name'] == case['patient']['name'], row
        assert row['exec_dept_id'] == lab_dept['id'] and row['exec_dept_name'] == lab_dept['name']
        assert pending(case, reader, lab_dept['id']) == [row] and pending(case, reader, other_dept['id']) == []
    collect(case, who)
    assert pending(case) == []
    row = samples(case)[0]
    assert row['status'] == 'COLLECTED' and row['order_status'] == 'CHARGED'
    assert row['collected_at'] and row['substitute'] is False and row['substitute_name'] is None
    assert row['specimen_type'] == '模拟血清' and row['sampling_site'] == '模拟部位'
    assert row['urgent'] is True and row['remark'] == 'V115模拟标本'
    assert set(row) == sample_keys
    refused(case, 'collect', 9940, who)
    receiver = roles[(index + 1) % len(roles)]
    receive(case, receiver)
    assert samples(case) == [dict(row, status='RECEIVED')]
    for reader in roles:
        assert samples(case, reader, lab_dept['id']) == samples(case)
        assert samples(case, reader, other_dept['id']) == []
    refused(case, 'receive', 9941, receiver)
    assert source(case) == before_order and bills(case) == before_bills
    assert snapshot(case)['results'] == snapshot(case)['alerts'] == snapshot(case)['executionReports'] == []
    # Even a received specimen is not execution: the genuine cashier can still
    # refund and its original specimen and timestamp remain available to staff.
    refund(case)
    refused(case, 'collect', 9940, who)
    refused(case, 'receive', 9941, receiver)
print('[1] Four original duties, cross-employee receipt, duplicate business codes and refundable physical history passed')

pending_case, receipt_case = visit('采样岗位拒绝'), visit('核收岗位拒绝')
collect(receipt_case, 'nurse')
for who in ['cashier', 'pharmacist', 'interface', None]:
    code = 1000 if who is None else 1005
    refused(pending_case, 'collect', code, who)
    refused(receipt_case, 'receive', code, who)
    if who is not None:
        expect(1005 if who == 'interface' else 0, 'GET', '/auth/me', who=who)
expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")
refused(pending_case, 'collect', 1000, 'disabled')
refused(receipt_case, 'receive', 1000, 'disabled')
expect(1000, 'GET', '/auth/me', who='disabled')
before_pending, before_receipt = snapshot(pending_case), snapshot(receipt_case)
expect(9940, 'POST', '/lis/samples?orderId=9000000000000000', who='nurse')
expect(9941, 'PUT', '/lis/samples/V115MISSING' + suffix + '/receive', who='doctor')
assert snapshot(pending_case) == before_pending and snapshot(receipt_case) == before_receipt
unpaid, nonlab = visit('未缴费拒绝', charged=False), visit('影像源拒绝', order_type='EXAM')
refused(unpaid, 'collect', 9940)
refused(nonlab, 'collect', 9940)
print('[2] Actual JWT wrong-duty/anonymous/disabled and absent/unpaid/non-LAB source rejections have zero projected writes')

uncollected, collected = visit('退款先于采样'), visit('退款先于核收')
collect(collected, 'doctor')
original_sample = samples(collected)[0]
refund(uncollected)
refund(collected)
refused(uncollected, 'collect', 9940)
refused(collected, 'receive', 9941)
for who in roles:
    assert pending(uncollected, who) == []
    assert samples(collected, who) == [dict(original_sample, order_status='CREATED')]

for case in [uncollected, collected]:
    old_charge_id = case['charge']['id']
    new_charge = settle(case)
    assert new_charge['id'] != old_charge_id
    assert next(row for row in bills(case) if row['chargeId'] == old_charge_id)['status'] == 'REFUNDED'
    if case is uncollected:
        assert len(pending(case)) == 1
        collect(case, 'nurse')
    else:
        assert samples(case) == [original_sample]
        refused(case, 'collect', 9940)
    before_order = source(case)
    receive(case, 'technician')
    assert source(case) == before_order and source(case)['status'] == 'CHARGED'
    assert samples(case)[0]['status'] == 'RECEIVED'
    assert snapshot(case)['results'] == snapshot(case)['alerts'] == snapshot(case)['executionReports'] == []
    refund(case)
print('[3] Refund wins before either writer; actual repayment restores pending collection or retained-specimen receipt')

# Existing machine results may execute an already-collected LAB before its
# separate receipt step. Receipt must preserve that legitimate EXECUTED source.
executed = visit('结果先回传后核收')
collect(executed, 'nurse')
message = (f'MSH|^~\\&|V115LIS|TEST|HIP|TEST|20261022000000||ORU^R01|V115{suffix}|P|2.5\r'
           f'OBR|1|{executed["order"]["groupNo"]}||{item_code}^V115模拟检验\r'
           f'OBX|1|ST|V115{suffix}^V115模拟正常|1|2|模拟单位|1-5|N\r')
ack = expect(0, 'POST', '/integration/hl7/oru', who='interface', text=message)
assert ack == {'groupNo': executed['order']['groupNo'], 'items': 1}
before_exec = snapshot(executed)
assert before_exec['source']['status'] == 'EXECUTED' and len(before_exec['results']) == 1
assert before_exec['samples'][0]['status'] == 'COLLECTED'
refused(executed, 'collect', 9940)
receive(executed, 'doctor')
after_exec = snapshot(executed)
assert after_exec == dict(before_exec, samples=[dict(before_exec['samples'][0], status='RECEIVED')])
before_refund = snapshot(executed)
expect(5005, 'POST', f"/outpatient/charges/{executed['charge']['id']}/refund", who='cashier')
assert snapshot(executed) == before_refund
print('[4] An existing EXECUTED/collected LAB still receives; machine facts, original source and executed refund guard stay intact')

substitute = visit('原替检参数')
allow_setting = os.environ.get('HIP_E2E_LIS_SUBSTITUTE_ALLOWED', 'true').lower()
assert allow_setting in {'true', 'false', '0', '1'}, 'Explicit original substitute setting must be boolean'
if allow_setting in {'false', '0'}:
    refused(substitute, 'collect', 4770, substitute='V115原参数模拟替检')
    collect(substitute, 'nurse')
    assert samples(substitute)[0]['substitute'] is False
else:
    collect(substitute, 'nurse', substitute='V115原参数模拟替检')
    assert samples(substitute)[0]['substitute'] is True
    assert samples(substitute)[0]['substitute_name'] == 'V115原参数模拟替检'
before_substitute = source(substitute)
receive(substitute, 'technician')
assert source(substitute) == before_substitute
refund(substitute)
print('[5] Existing substitute setting honored without changing shared configuration; specimen history remains refundable')

print('e2e-v115-lis-sampling-refund passed: real JWT writer duties, LAB source validity, '
      'refund/collection/receipt order, repayment recovery and EXECUTED receipt compatibility. '
      'No SQL mutation or shared configuration writes. Java tests separately verify real '
      'PostgreSQL lock waits, rollback, live actor checks and non-public timestamps.')
