# -*- coding: utf-8 -*-
"""Real JWT regression for both pathology registration producers and refunds.

Every patient, employee, item and visit is private and created through the public
APIs. No SQL, shared configuration or clinical-state fabrication is used here.
The Java suite separately proves actual PostgreSQL waits, strong source locks,
identity changes during waits, stale managed entities and transaction rollback.
"""
import datetime
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, ok, provision_user, q, today_bj


suffix = str(time.time_ns())[-11:]
tokens = {'admin': provision_user(login(), 'v116admin' + suffix, 'ADMIN')}
machine_identity = None
for who, role in [('technician', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'),
                  ('nurse', 'NURSE'), ('cashier', 'CASHIER'),
                  ('pharmacist', 'PHARMACIST'), ('interface', 'INTERFACE'),
                  ('disabled', 'TECHNICIAN')]:
    username, name = 'v116' + who + suffix, 'V116' + who + suffix
    # A pure machine identity cannot initialize through employee change-password.
    tokens[who] = provision_user(tokens['admin'], username,
                                 'TECHNICIAN' if role == 'INTERFACE' else role, name)
    if role == 'INTERFACE':
        person = ok(call('GET', '/auth/me', token=tokens[who]), 'initial employee')
        machine_identity = ok(call('PUT', f"/system/users/{person['id']}", {
            'username': username, 'realName': name, 'roleCodes': ['INTERFACE'],
        }, tokens['admin']), 'private machine duty')
        assert machine_identity['id'] == person['id'] and machine_identity['enabled']
        assert machine_identity['roleCodes'] == ['INTERFACE']
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


identities = {who: expect(0, 'GET', '/auth/me', who=who)
              for who in tokens if who != 'interface'}
identities['interface'] = machine_identity
assert len({person['id'] for person in identities.values()}) == len(tokens)
for who in ['admin', 'technician', 'doctor', 'nurse', 'cashier', 'pharmacist']:
    expect(0, 'GET', '/stats/overview', who=who)
expect(1005, 'GET', '/auth/me', who='interface')
expect(1005, 'GET', '/stats/overview', who='interface')

clinic = expect(0, 'POST', '/system/depts', {
    'code': 'V116D' + suffix, 'name': 'V116病理模拟' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': clinic['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 30,
})
item_code, other_code = 'V116PATH' + suffix, 'V116OTHER' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V116模拟病理{suffix},EXAM,次,1.00,,,{clinic["code"]}\n' +
                  f'{other_code},V116模拟检查{suffix},EXAM,次,1.00,,,{clinic["code"]}\n')
assert imported['imported'] == 2 and imported['errorCount'] == 0, imported
items = expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
path_item = next(row for row in items if row['code'] == item_code)
other_item = next(row for row in items if row['code'] == other_code)


def source(case):
    workspace = expect(0, 'GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")
    return next(row for row in workspace['orders'] if row['id'] == case['order']['id'])


def bills(case):
    return [row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
            if row['chargeId'] in case['charge_ids']]


def specimens(case):
    # These just-created cases stay within the legacy list's latest-100 cap;
    # the independent search confirms that a cap never silently hides history.
    rows = [row for row in expect(0, 'GET', '/pathology/specimens')
            if row['order_id'] == case['order']['id']]
    search = expect(0, 'GET', '/pathology/registry/specimens/search?patientName=' +
                    q(case['patient']['name']))
    assert search['truncated'] is False
    assert {row['id'] for row in rows} == {row['id'] for row in search['items']}
    return sorted(rows, key=lambda row: row['id'])


def pending(case, registered=False):
    result = expect(0, 'GET', '/pathology/registry/pending?source=OUTP&keyword=' +
                    q(case['patient']['name']) + '&includeRegistered=' + str(registered).lower())
    assert result['truncated'] is False
    return [row for row in result['items'] if row['order_id'] == case['order']['id']]


def legacy_pending(case):
    return [row for row in expect(0, 'GET', '/pathology/pending')
            if row['order_id'] == case['order']['id']]


def trail(specimen_id):
    return expect(0, 'GET', f'/pathology/process/trail/{specimen_id}')


def snapshot(case):
    rows = specimens(case)
    # Exclude the trail's computed elapsed-since-now fields; all stored nodes,
    # actors, recorded times and remarks remain in this projected comparison.
    return {'source': source(case), 'bills': bills(case), 'specimens': rows,
            'pending': pending(case), 'includingRegistered': pending(case, True),
            'legacyPending': legacy_pending(case),
            'nodes': {str(row['id']): trail(row['id'])['nodes'] for row in rows},
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


def visit(label, charged=True, item=None):
    patient = expect(0, 'POST', '/patients', {'name': 'V116' + label + suffix, 'sex': 'U'})
    registration = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
    created = expect(0, 'POST', f"/outpatient/doctor/{registration['id']}/orders", {'lines': [{
        'orderType': 'EXAM', 'itemId': (item or path_item)['id'], 'qty': 1,
        'specimenType': '模拟组织', 'samplingSite': '模拟部位',
        'remark': 'V116模拟登记',
    }]}, who='doctor')
    assert len(created) == 1 and created[0]['status'] == 'CREATED', created
    case = {'patient': patient, 'registration': registration, 'order': created[0], 'charge_ids': []}
    if charged:
        settle(case)
    assert specimens(case) == []
    return case


result_keys = {'id', 'barcode', 'pathNo', 'partNo', 'source', 'specimenType', 'status', 'manual'}


def register(case, who='technician', code=0, part=1, manual=False):
    result = expect(code, 'POST', '/pathology/registry/specimens' + ('/manual' if manual else ''), {
        'orderId': case['order']['id'], 'partNo': part, 'specimenType': 'ROUTINE',
        'specimenDesc': 'V116模拟送检组织', 'samplingSite': 'V116模拟部位',
        'clinicalDiagnosis': 'V116模拟待查', 'fixative': '模拟固定液', 'urgent': True,
        **({'applyDept': 'V116模拟临床', 'applyDoctor': 'V116模拟申请人',
            'note': 'V116实物已送达模拟救济'} if manual else {}),
    }, who=who)
    if code == 0:
        assert set(result) == result_keys and result['source'] == 'OUTP'
        assert result['partNo'] == part and result['specimenType'] == 'ROUTINE'
        assert result['barcode'].startswith('PB') and result['pathNo']
        assert result['manual'] is manual
        assert result['status'] == ('RECEIVED' if manual else 'COLLECTED')
    return result


def collect(case, who='technician', code=0, description='V116旧入口模拟组织'):
    path = f"/pathology/specimens?orderId={case['order']['id']}"
    if description is not None:
        path += '&specimenDesc=' + q(description)
    result = expect(code, 'POST', path, who=who)
    if code == 0:
        assert set(result) == {'barcode'} and result['barcode'].startswith('PB')
    return result


def refused(case, entry, code, who='technician', **kwargs):
    before = snapshot(case)
    if entry == 'legacy':
        collect(case, who, code, **kwargs)
    else:
        register(case, who, code, manual=(entry == 'manual'), **kwargs)
    assert snapshot(case) == before, ('Registration refusal changed projected facts', entry, who)


def refund(case, code=0):
    before = snapshot(case)
    result = expect(code, 'POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier')
    if code:
        assert snapshot(case) == before, 'Blocked refund changed projected clinical/billing facts'
        return
    assert result['id'] == case['charge']['id'] and result['status'] == 'REFUNDED'
    after = snapshot(case)
    assert after['source']['status'] == 'CREATED' and after['source']['chargeId'] is None
    assert {k: v for k, v in after['source'].items() if k not in {'status', 'chargeId'}} == {
        k: v for k, v in before['source'].items() if k not in {'status', 'chargeId'}}
    assert after['specimens'] == before['specimens'] and after['nodes'] == before['nodes']
    assert after['executionReports'] == before['executionReports'] == []
    assert after['pending'] == after['includingRegistered'] == after['legacyPending'] == []
    assert next(row for row in after['bills'] if row['chargeId'] == case['charge']['id'])['status'] == 'REFUNDED'


def reject(case, specimen_id, who='technician'):
    before = next(row for row in specimens(case) if row['id'] == specimen_id)
    expect(0, 'PUT', f'/pathology/registry/specimens/{specimen_id}/reject', {
        'reason': 'V116模拟标本固定不合格',
    }, who=who)
    actual = next(row for row in specimens(case) if row['id'] == specimen_id)
    assert actual['status'] == before['status']
    assert actual['rejected_by'] == identities[who]['id'] and actual['rejected_at']
    assert actual['reject_reason'] == 'V116模拟标本固定不合格'
    assert {k: v for k, v in actual.items() if k not in {'reject_reason', 'rejected_at', 'rejected_by'}} == {
        k: v for k, v in before.items() if k not in {'reject_reason', 'rejected_at', 'rejected_by'}}
    node = trail(specimen_id)['nodes'][-1]
    assert node['node'] == 'REJECT' and node['operator_id'] == identities[who]['id']
    assert node['occurred_at'] == actual['rejected_at']


duties = ['admin', 'technician', 'doctor']
for index, who in enumerate(duties):
    case = visit('新版岗位' + who)
    before_order, before_bills = source(case), bills(case)
    assert len(pending(case)) == len(legacy_pending(case)) == 1
    first = register(case, who)
    second = register(case, duties[(index + 1) % len(duties)], part=2)
    rows = specimens(case)
    assert {row['id'] for row in rows} == {first['id'], second['id']}
    assert len({row['barcode'] for row in rows}) == len({row['path_no'] for row in rows}) == 2
    assert all(row['status'] == 'COLLECTED' and row['received_at'] is None for row in rows)
    assert source(case) == before_order and bills(case) == before_bills
    assert pending(case) == legacy_pending(case) == []
    assert pending(case, True)[0]['registered_parts'] == 2
    refused(case, 'registry', 5202, who)
    refused(case, 'legacy', 4550, who)
    refund(case, 5005)

    legacy = visit('旧版岗位' + who)
    legacy_before = source(legacy), bills(legacy)
    collected = collect(legacy, who)
    row = specimens(legacy)[0]
    assert row['barcode'] == collected['barcode'] and row['part_no'] == 1
    assert row['status'] == 'COLLECTED' and row['received_at'] is None
    assert row['specimen_desc'] == 'V116旧入口模拟组织'
    assert (source(legacy), bills(legacy)) == legacy_before
    refused(legacy, 'legacy', 4550, who)
    refused(legacy, 'registry', 5202, who)
    refund(legacy, 5005)
print('[1] Three real writer duties, both ordinary producers, multipart history and active-specimen refund 5005 passed')

denied = visit('三入口岗位拒绝')
for who in ['nurse', 'cashier', 'pharmacist', 'interface', None]:
    for entry in ['registry', 'manual', 'legacy']:
        refused(denied, entry, 1000 if who is None else 1005, who)
    if who is not None:
        expect(1005 if who == 'interface' else 0, 'GET', '/auth/me', who=who)
expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")
for entry in ['registry', 'manual', 'legacy']:
    refused(denied, entry, 1000, 'disabled')
expect(1000, 'GET', '/auth/me', who='disabled')
print('[2] Wrong-duty, sole machine role, anonymous and disabled JWT refusals leave projected facts unchanged')

for label, refunded in [('从未收费', False), ('退款先行', True)]:
    case = visit(label, charged=refunded)
    if refunded:
        refund(case)
    refused(case, 'registry', 5201)
    refused(case, 'legacy', 4550)
    assert source(case)['status'] == 'CREATED' and source(case)['chargeId'] is None
    assert specimens(case) == [] and pending(case) == legacy_pending(case) == []
print('[3] Unpaid and refund-first sources reject both ordinary producers with 5201/4550 and zero projected writes')

recovery = visit('拒收退款重送')
first = register(recovery, part=1)
second = register(recovery, part=2)
reject(recovery, first['id'])
refund(recovery, 5005)  # A different active part still protects the entire paid bill.
reject(recovery, second['id'], 'doctor')
refused(recovery, 'legacy', 4550)  # Legacy producer retains its stricter any-history rule.
refund(recovery)
history = specimens(recovery)
refused(recovery, 'registry', 5201)
settle(recovery)
replacement = register(recovery, 'doctor', part=1)
assert [row for row in specimens(recovery) if row['id'] != replacement['id']] == history
assert len(specimens(recovery)) == 3
assert replacement['barcode'] not in {row['barcode'] for row in history}
assert replacement['pathNo'] not in {row['path_no'] for row in history}
refund(recovery, 5005)
print('[4] Partial/all-part rejection, actual refund and repayment preserve history and permit same-part re-registration')

for index, who in enumerate(duties):
    manual = visit('实物救济' + who, charged=(index == 1), item=other_item)
    if index == 1:
        refund(manual)
    original_order, original_bills = source(manual), bills(manual)
    result = register(manual, who, manual=True)
    row = specimens(manual)[0]
    assert row['id'] == result['id'] and row['status'] == 'RECEIVED'
    assert row['received_at'] and row['rejected_at'] is None
    nodes = trail(result['id'])['nodes']
    assert len(nodes) == 1 and nodes[0]['node'] == 'RECEIVE'
    assert nodes[0]['operator_id'] == identities[who]['id']
    assert nodes[0]['operator_name'] == identities[who]['realName']
    assert nodes[0]['occurred_at'] == row['received_at']
    assert datetime.datetime.fromisoformat(row['received_at']) >= datetime.datetime.fromisoformat(row['collected_at'])
    assert '手工登记（无电子病理申请单）' in nodes[0]['remark']
    assert 'V116模拟临床' in nodes[0]['remark'] and 'V116实物已送达模拟救济' in nodes[0]['remark']
    assert source(manual) == original_order and bills(manual) == original_bills
    refused(manual, 'manual', 5202, who)
    before = snapshot(manual)
    expect(5208, 'PUT', f"/pathology/registry/specimens/{result['id']}/receive-check", {}, who)
    assert snapshot(manual) == before, 'Manual receipt must not gain a duplicate RECEIVE node'
print('[5] All three duties retain unpaid/refunded physical rescue, real RECEIVE identity/time and original billing facts')

validation = visit('原参数校验')
for description in [None, '', '   ']:
    refused(validation, 'legacy', 4554, description=description)
refused(validation, 'registry', 5204, part=0)
refused(validation, 'registry', 5204, part=100)
before = snapshot(validation)
expect(5201, 'POST', '/pathology/registry/specimens', {
    'orderId': 9000000000000000, 'specimenType': 'ROUTINE',
})
expect(4550, 'POST', '/pathology/specimens?orderId=9000000000000000&specimenDesc=' + q('V116模拟组织'))
assert snapshot(validation) == before
print('[6] Original legacy description, part range and absent-source business codes passed without new policy restrictions')

print('e2e-v116-pathology-register-refund passed: actual JWT writer duties, ordinary '
      'source/refund order, multipart and rejected-part recovery, unpaid/refunded '
      'manual physical rescue with real receiver/time. No SQL mutation or shared '
      'configuration writes. Java tests separately verify real PostgreSQL lock '
      'waits, rollback, stale entities and live actors while waiting.')
