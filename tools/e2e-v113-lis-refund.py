# -*- coding: utf-8 -*-
"""Real JWT / optional MLLP checks for LIS publication and source-order refunds.

All employees, departments, patients, prices and orders belong to this invocation.
No SQL, shared configuration changes or historical record deletion is used. The
Java tests separately prove real PostgreSQL lock waits and verifier attribution;
those fields are deliberately absent from the existing public read projections.
An EXECUTED order continues accepting extra ORU results. Multi-OBR messages keep
their established per-group commits, including when a later group is rejected.
"""
import json
import os
import socket
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, ok, provision_user, today_bj


suffix = str(time.time_ns())[-11:]
bootstrap = login()
tokens = {'admin': provision_user(bootstrap, 'v113admin' + suffix, 'ADMIN')}
machine_identity = None
for who, role in [('technician', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'),
                  ('nurse', 'NURSE'), ('cashier', 'CASHIER'),
                  ('pharmacist', 'PHARMACIST'), ('interface', 'INTERFACE'),
                  ('disabled', 'TECHNICIAN')]:
    # The machine-only whitelist intentionally rejects employee password change.
    # Finish human account initialization before assigning the final machine duty.
    tokens[who] = provision_user(tokens['admin'], 'v113' + who + suffix,
                                 'TECHNICIAN' if role == 'INTERFACE' else role,
                                 'V113' + who + suffix)
    if role == 'INTERFACE':
        employee = ok(call('GET', '/auth/me', token=tokens[who]), 'initial machine employee')
        machine_identity = ok(call('PUT', f"/system/users/{employee['id']}", {
            'username': 'v113' + who + suffix, 'realName': 'V113' + who + suffix,
            'roleCodes': ['INTERFACE'],
        }, tokens['admin']), 'assign private interface duty')
        assert machine_identity['id'] == employee['id']
        assert machine_identity['roleCodes'] == ['INTERFACE'] and machine_identity['enabled']
        tokens[who] = login('v113' + who + suffix, 'Work@1234')


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
    'code': 'V113D' + suffix, 'name': 'V113检验模拟' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
lab_department = expect(0, 'POST', '/system/depts', {
    'code': 'V113L' + suffix, 'name': 'V113执行模拟' + suffix,
    'type': 'MEDTECH', 'sortNo': 9999,
})
other_department = expect(0, 'POST', '/system/depts', {
    'code': 'V113O' + suffix, 'name': 'V113空执行模拟' + suffix,
    'type': 'MEDTECH', 'sortNo': 9999,
})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': today_bj().isoformat(),
    'fee': 0, 'capacity': 30,
})
item_code = 'V113LAB' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V113模拟检验{suffix},LAB,次,1.00,,,{lab_department["code"]}\n')
assert imported['imported'] == 1 and imported['errorCount'] == 0, imported
lab = next(row for row in expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
           if row['code'] == item_code)


def orders(case):
    return expect(0, 'GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")['orders']


def source(case):
    return next(row for row in orders(case) if row['id'] == case['order']['id'])


def bill(case):
    return next(row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
                if row['chargeId'] == case['charge']['id'])


def results(case):
    return expect(0, 'GET', f"/outpatient/lab-results?orderId={case['order']['id']}")


def alerts(case):
    return [row for row in expect(0, 'GET', '/outpatient/critical-alerts?status=NEW')
            if row['patientNo'] == case['patient']['patientNo']]


def samples(case, who='admin', dept_id=None):
    query = '' if dept_id is None else '?deptId=' + str(dept_id)
    return [row for row in expect(0, 'GET', '/lis/samples' + query, who=who)
            if row['order_id'] == case['order']['id']]


def snapshot(case):
    return {'source': source(case), 'bill': bill(case), 'samples': samples(case),
            'results': results(case), 'alerts': alerts(case)}


def visit(label, collect=True, receive=True):
    patient = expect(0, 'POST', '/patients', {'name': 'V113' + label + suffix, 'sex': 'U'})
    reg = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
    created = expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [{
        'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1,
        'specimenType': '模拟血清', 'samplingSite': '模拟部位',
        'remark': 'V113模拟标本', 'urgent': True,
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
    if collect:
        case['barcode'] = expect(0, 'POST', f"/lis/samples?orderId={created[0]['id']}",
                                 who='nurse')['barcode']
        assert len(samples(case)) == 1 and samples(case)[0]['status'] == 'COLLECTED'
        if receive:
            expect(0, 'PUT', f"/lis/samples/{case['barcode']}/receive", who='technician')
            assert samples(case)[0]['status'] == 'RECEIVED'
    return case


def result_line(label, flag='N'):
    # A private code and explicit flag avoid depending on shared reference ranges.
    return {'code': 'V113' + label + suffix, 'name': 'V113' + label,
            'value': '9.0', 'unit': '模拟单位', 'refRange': '1-5', 'flag': flag}


def publish(case, lines, who='technician', code=0):
    return expect(code, 'POST', f"/lis/samples/{case['barcode']}/publish",
                  {'results': lines}, who=who)


def unchanged_except_status(before, after, status):
    assert after['status'] == status, after
    assert {key: value for key, value in before.items() if key != 'status'} == {
        key: value for key, value in after.items() if key != 'status'
    }, ('LIS changed source metadata or billing ownership', before, after)


def assert_executed(case, before, count, critical_count):
    unchanged_except_status(before, source(case), 'EXECUTED')
    assert len(results(case)) == count, results(case)
    assert len(alerts(case)) == critical_count, alerts(case)
    assert bill(case)['status'] == 'PAID'
    assert source(case)['chargeId'] == case['charge']['id']


def refused_refund(case):
    before = snapshot(case)
    expect(5005, 'POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier')
    assert snapshot(case) == before, 'executed refund rejection must have zero clinical/billing writes'


def oru(groups, message_id):
    body = f'MSH|^~\\&|V113LIS|TEST|HIP|TEST|20261022000000||ORU^R01|{message_id}|P|2.5\r'
    for index, (group_no, lines) in enumerate(groups, 1):
        body += f'OBR|{index}|{group_no}||{item_code}^V113模拟检验\r'
        for position, line in enumerate(lines, 1):
            body += (f'OBX|{position}|ST|{line["code"]}^{line["name"]}|{position}|'
                     f'{line["value"]}|{line["unit"]}|{line["refRange"]}|{line["flag"]}\r')
    return body


# Positive baseline: every existing manual-publishing role still succeeds. No
# department or creator restriction is silently added to this shared worklist.
baseline = visit('手工正向')
normal_lines = [result_line('手工危急', 'HH'), result_line('手工正常')]
before = source(baseline)
publish(baseline, normal_lines)
assert_executed(baseline, before, 2, 1)
assert samples(baseline) == [], 'PUBLISHED samples leave the established worklist'
actual = results(baseline)
assert [(row['itemCode'], row['resultValue'], row['abnormalFlag']) for row in actual] == [
    (line['code'], line['value'], line['flag']) for line in normal_lines]
assert 'V113手工危急' in alerts(baseline)[0]['content']
notified = [row for row in expect(0, 'GET', '/outpatient/critical-alerts/my-pending', who='doctor')
            if row['patient_name'] == baseline['patient']['name']]
assert len(notified) == 1 and notified[0]['source'] == 'LAB'
report = expect(0, 'GET', f"/print/lab-report/{baseline['order']['id']}")
assert report['group_no'] == baseline['order']['groupNo'] and len(report['results']) == 2
refused_refund(baseline)
baseline_published = snapshot(baseline)
publish(baseline, normal_lines, code=9942)
assert snapshot(baseline) == baseline_published, 'duplicate manual publication must not append results/alerts'
for who in ['admin', 'doctor', 'nurse']:
    case = visit('岗位正向' + who)
    before = source(case)
    publish(case, [result_line(who)], who)
    assert_executed(case, before, 1, 0)
    assert samples(case, who) == []
print('[1] Existing manual roles, normal/HH results, doctor notification and refund guard passed')

pending = visit('岗位拒绝')
for who in ['cashier', 'pharmacist', 'interface', None]:
    before = snapshot(pending)
    denied_code = 1000 if who is None else 1005
    publish(pending, [result_line('越权')], who, denied_code)
    expect(denied_code, 'GET', '/lis/samples', who=who)
    assert snapshot(pending) == before
    if who is not None:
        expect(1005 if who == 'interface' else 0, 'GET', '/auth/me', who=who)
for who in ['technician', 'doctor', 'nurse', 'cashier', 'pharmacist']:
    before = snapshot(pending)
    expect(1005, 'POST', '/integration/hl7/oru', who=who,
           text=oru([(pending['order']['groupNo'], [result_line('越权ORU')])], 'V113DENY' + suffix))
    assert snapshot(pending) == before
    expect(0, 'GET', '/auth/me', who=who)
# The machine credential can use ORU, but cannot enter clinical/financial/admin APIs.
for path in ['/outpatient/charges/recent', '/system/users',
             f"/outpatient/doctor/{pending['registration']['id']}/workspace"]:
    expect(1005, 'GET', path, who='interface')
expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")
before = snapshot(pending)
publish(pending, [result_line('停用')], 'disabled', 1000)
expect(1000, 'GET', '/lis/samples', who='disabled')
assert snapshot(pending) == before
publish(pending, [], code=9943)
assert snapshot(pending) == before
collected = visit('未核收', receive=False)
before_collected = snapshot(collected)
publish(collected, [result_line('未核收')], code=9942)
assert snapshot(collected) == before_collected
expect(9942, 'POST', '/lis/samples/V113MISSING' + suffix + '/publish',
       {'results': [result_line('不存在')]})
print('[2] Real JWT role/disabled/anonymous boundaries and unchanged rejection facts passed')

# Refund commits first. The actual RECEIVED row remains readable, carrying its
# current source status; stale publishing must not claim it as PUBLISHED.
refunded = visit('退费先行')
refund = expect(0, 'POST', f"/outpatient/charges/{refunded['charge']['id']}/refund", who='cashier')
assert refund['status'] == 'REFUNDED'
assert source(refunded)['status'] == 'CREATED' and source(refunded)['chargeId'] is None
assert bill(refunded)['status'] == 'REFUNDED'
before = snapshot(refunded)
publish(refunded, [result_line('已退危急', 'HH')], code=9942)
assert snapshot(refunded) == before, 'stale manual publication must preserve the RECEIVED sample and all facts'
expect(7000, 'POST', '/integration/hl7/oru', who='interface',
       text=oru([(refunded['order']['groupNo'], [result_line('已退回传', 'HH')])], 'V113REF' + suffix))
assert snapshot(refunded) == before, 'refunded ORU must not create results or alerts or revive charge ownership'
expected_keys = {'id', 'barcode', 'status', 'collected_at', 'substitute', 'substitute_name',
                 'order_id', 'item_name', 'group_no', 'specimen_type', 'sampling_site',
                 'urgent', 'remark', 'patient_name', 'exec_dept_id', 'exec_dept_name', 'order_status'}
for who in ['admin', 'technician', 'doctor', 'nurse']:
    row = samples(refunded, who)[0]
    assert set(row) == expected_keys, row
    assert row['status'] == 'RECEIVED' and row['order_status'] == 'CREATED'
    assert row['barcode'] == refunded['barcode'] and row['group_no'] == refunded['order']['groupNo']
    assert row['specimen_type'] == '模拟血清' and row['sampling_site'] == '模拟部位'
    assert row['urgent'] is True and row['remark'] == 'V113模拟标本'
    assert row['patient_name'] == refunded['patient']['name']
    assert row['exec_dept_id'] == lab_department['id'] and row['exec_dept_name'] == lab_department['name']
    assert samples(refunded, who, lab_department['id']) == [row]
    assert samples(refunded, who, other_department['id']) == []
    assert samples(pending, who)[0]['order_status'] == 'CHARGED'
print('[3] Refund-first manual/HTTP ORU rejections and current source/dept worklist contract passed')

# HTTP machine role retains normal submission and EXECUTED append semantics.
machine = visit('HTTP机器', collect=False)
before = source(machine)
machine_line = result_line('机器追加', 'HH')
message = oru([(machine['order']['groupNo'], [machine_line])], 'V113HTTP' + suffix)
for count in [1, 2]:
    ack = expect(0, 'POST', '/integration/hl7/oru', who='interface', text=message)
    assert ack == {'groupNo': machine['order']['groupNo'], 'items': 1}, ack
    assert_executed(machine, before, count, count)
refused_refund(machine)
# A published sample does not prevent an additional ORU on the same EXECUTED O.
expect(0, 'POST', '/integration/hl7/oru', who='admin', text=oru([
    (baseline['order']['groupNo'], [result_line('手工后追加')])], 'V113EXTRA' + suffix))
assert len(results(baseline)) == 3 and len(alerts(baseline)) == 1 and samples(baseline) == []

first, second = visit('多OBR甲', collect=False), visit('多OBR乙', collect=False)
first_line, second_line = result_line('甲独有'), result_line('乙独有', 'HH')
ack = expect(0, 'POST', '/integration/hl7/oru', who='interface', text=oru([
    (first['order']['groupNo'], [first_line]), (second['order']['groupNo'], [second_line]),
], 'V113MULTI' + suffix))
assert ack == {'groupNo': first['order']['groupNo'], 'items': 2}
assert [row['itemCode'] for row in results(first)] == [first_line['code']]
assert [row['itemCode'] for row in results(second)] == [second_line['code']]
assert len(alerts(first)) == 0 and len(alerts(second)) == 1
assert source(first)['status'] == source(second)['status'] == 'EXECUTED'

partial = visit('分组部分提交', collect=False)
before_partial, before_refunded = source(partial), snapshot(refunded)
partial_line = result_line('部分提交', 'HH')
expect(7000, 'POST', '/integration/hl7/oru', who='interface', text=oru([
    (partial['order']['groupNo'], [partial_line]),
    (refunded['order']['groupNo'], [result_line('后组已退', 'HH')]),
], 'V113PART' + suffix))
assert_executed(partial, before_partial, 1, 1)
assert snapshot(refunded) == before_refunded
empty = visit('空组不串号', collect=False)
empty_before = snapshot(empty)
ack = expect(0, 'POST', '/integration/hl7/oru', who='interface', text=oru([
    (empty['order']['groupNo'], []), (partial['order']['groupNo'], [result_line('非空组追加')]),
], 'V113EMPTY' + suffix))
assert ack == {'groupNo': empty['order']['groupNo'], 'items': 1}
assert snapshot(empty) == empty_before and len(results(partial)) == 2 and len(alerts(partial)) == 1
print('[4] Machine JWT, EXECUTED append, multi-OBR mapping, empty groups and partial commits passed')


def mllp_send(payload):
    with socket.create_connection(('127.0.0.1', int(os.environ['HIP_E2E_MLLP_PORT'])), timeout=10) as connection:
        connection.sendall(b'\x0b' + payload.encode('utf-8') + b'\x1c\x0d')
        buffer = b''
        while b'\x1c\x0d' not in buffer:
            chunk = connection.recv(4096)
            assert chunk, 'MLLP connection closed without a complete ACK frame'
            buffer += chunk
        assert buffer.startswith(b'\x0b'), buffer
        return buffer[1:].split(b'\x1c\x0d', 1)[0].decode('utf-8')


if os.environ.get('HIP_E2E_MLLP_PORT'):
    # MLLP intentionally has no human JWT. Only this machine transport is skipped
    # for a release Jar started with MLLP disabled; all HTTP cases above still run.
    tcp = visit('MLLP机器', collect=False)
    before_tcp = source(tcp)
    control_id = 'V113TCP' + suffix
    payload = oru([(tcp['order']['groupNo'], [result_line('TCP危急', 'HH')])], control_id)
    assert 'MSA|AA|' + control_id in mllp_send(payload)
    assert_executed(tcp, before_tcp, 1, 1)
    assert 'MSA|AA|' + control_id in mllp_send(payload)
    assert_executed(tcp, before_tcp, 2, 2)
    refused_refund(tcp)
    before_refunded = snapshot(refunded)
    control_id = 'V113TCPREF' + suffix
    ack = mllp_send(oru([(refunded['order']['groupNo'], [result_line('TCP拒绝', 'HH')])], control_id))
    assert 'MSA|AE|' + control_id in ack, ack
    assert snapshot(refunded) == before_refunded
    print('[5] Anonymous machine MLLP AA/append and refunded-source AE/zero writes passed')
else:
    print('[5] MLLP not run: HIP_E2E_MLLP_PORT is unset; HTTP/JWT cases all executed')

print('e2e-v113-lis-refund passed: real JWT manual roles, refund/publication order, '
      'current source contract, HTTP ORU append and per-group transaction semantics. '
      'Synthetic PAID/EXECUTED and REFUNDED/RECEIVED histories are retained.')
