# -*- coding: utf-8 -*-
"""Real JWT execution / infusion / CASH refund boundaries, using public APIs only.

All patients, schedules, employees and imported test items carry a unique V111 marker.
No shared employee, configuration, drug price or stock is changed. PostgreSQL lock-wait
and rollback races are covered by V111ExecutionRefundRaceTest / V111InfusionRefundRaceTest;
this sequential HTTP suite verifies the resulting contracts through the JWT chain.
Completed services cannot legitimately be refunded: their synthetic PAID histories and
one actually dispensed test unit remain. The refundable PENDING case is refunded and its
unpaid orders cancelled. We never call the known unsafe already-infused drug-return path
to manufacture an apparently clean inventory or erase nursing history.
"""
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, provision_user, q, today_bj


tokens = {'admin': login()}
suffix = str(time.time_ns())[-11:]
identities = {}
for who, role in [('technician', 'TECHNICIAN'), ('nurse', 'NURSE'),
                  ('cover', 'NURSE'), ('doctor', 'DOCTOR_OUTP'),
                  ('cashier', 'CASHIER'), ('pharmacist', 'PHARMACIST')]:
    tokens[who] = provision_user(tokens['admin'], 'v111' + who + suffix, role,
                                 'V111' + who + suffix)


def expect(code, method, path, body=None, who='admin', text=None):
    try:
        response = call(method, path, body, tokens[who], text=text)
    except urllib.error.HTTPError as error:
        assert error.code == 403 and code == 1005, (who, method, path, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


for who in tokens:
    identities[who] = expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
assert len({person['id'] for person in identities.values()}) == len(tokens)

department = expect(0, 'POST', '/system/depts', {
    'code': 'V111D' + suffix, 'name': 'V111执行护理模拟' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
day = today_bj().isoformat()
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': day, 'fee': 0, 'capacity': 4,
})
fee_schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': day, 'shift': 'AM',
    'fee': 1, 'capacity': 1,
})

# Imported simulation dictionaries make money and inventory assertions independent of
# older suites' prices, batch coverage or consumptions. These are explicitly test items,
# not a clinical drug/route policy or a claim that a test name is a real pharmaceutical.
drug_codes = {'plain': 'V111P' + suffix, 'skin': 'V111S' + suffix}
csv = 'code,name,spec,unit,dose_form,price,stock,antibiotic\n' + '\n'.join([
    f"{drug_codes['plain']},V111非皮试模拟液{suffix},模拟1ml,支,注射剂,1.00,30,0",
    f"{drug_codes['skin']},V111西林类模拟液{suffix},模拟1ml,支,注射剂,1.00,30,0",
])
imported = expect(0, 'POST', '/masterdata/drugs/import', text=csv)
assert imported['imported'] == 2 and imported['errorCount'] == 0, imported
drugs = {kind: next(row for row in expect(0, 'GET', '/masterdata/drugs?keyword=' + suffix)
                    if row['code'] == code) for kind, code in drug_codes.items()}
assert all(row['stock'] == 30 and row['enabled'] for row in drugs.values())

item_codes = {kind: 'V111' + kind + suffix for kind in ['LAB', 'EXAM', 'TREAT']}
csv = 'code,name,category,unit,price\n' + '\n'.join(
    f'{code},V111模拟{kind}{suffix},{kind},次,1.00' for kind, code in item_codes.items())
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=csv)
assert imported['imported'] == 3 and imported['errorCount'] == 0, imported
all_items = expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
items = {kind: next(row for row in all_items if row['code'] == code)
         for kind, code in item_codes.items()}


def line(kind, drug='plain'):
    if kind != 'DRUG':
        return {'orderType': kind, 'itemId': items[kind]['id'], 'qty': 1}
    return {'orderType': 'DRUG', 'itemId': drugs[drug]['id'], 'qty': 1,
            'usageRoute': '静脉滴注', 'frequency': 'qd', 'dosePerTime': '模拟1ml', 'days': 1}


def visit(label, lines, selected_schedule=schedule):
    patient = expect(0, 'POST', '/patients', {'name': 'V111' + label + suffix, 'sex': 'U'})
    reg = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': selected_schedule['id'],
    }, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': lines}, who='doctor')
    orders = workspace(reg)['orders']
    assert all(row['status'] == 'CREATED' and row['chargeId'] is None for row in orders)
    charge = expect(0, 'POST', '/outpatient/charges/settle', {
        'registrationId': reg['id'], 'payMethod': 'CASH',
    }, who='cashier')
    assert charge['status'] == 'PAID' and charge['payMethod'] == 'CASH'
    assert charge['cashierId'] == identities['cashier']['id']
    assert Decimal(str(charge['totalAmount'])) == sum(Decimal(str(row['amount'])) for row in orders)
    assert Decimal(str(charge['totalAmount'])) < Decimal('10')
    assert all(row['status'] == 'CHARGED' and row['chargeId'] == charge['id']
               for row in workspace(reg)['orders'])
    return {'registration': reg, 'patient': patient, 'charge': charge}


def workspace(reg):
    return expect(0, 'GET', f"/outpatient/doctor/{reg['id']}/workspace")


def reports(case, who='admin'):
    return expect(0, 'GET', '/outpatient/exec/reports?registrationId=' +
                  str(case['registration']['id']), who=who)


def orders(case):
    return workspace(case['registration'])['orders']


def order(case, kind='DRUG'):
    return next(row for row in orders(case) if row['orderType'] == kind)


def infusion_rows(case):
    return [row for row in expect(0, 'GET', '/outpatient/nurse/infusions')
            if row['patient_name'] == case['patient']['name']]


def infusion(case):
    rows = infusion_rows(case)
    assert len(rows) == 1, rows
    return rows[0]


def stock():
    return {kind: next(row['stock'] for row in expect(0, 'GET', '/masterdata/drugs?keyword=' + suffix)
                       if row['code'] == code) for kind, code in drug_codes.items()}


def charge_row(case):
    return next(row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
                if row['chargeId'] == case['charge']['id'])


def snapshot(case):
    return {'orders': orders(case), 'reports': reports(case),
            'infusions': infusion_rows(case), 'charge': charge_row(case), 'stock': stock()}


def candidate(order_id, who='nurse'):
    return any(row['order_id'] == order_id for row in
               expect(0, 'GET', '/outpatient/nurse/infusion-candidates', who=who))


def create_infusion(case, who='nurse'):
    source = order(case)
    assert candidate(source['id'], who)
    expect(0, 'POST', f"/outpatient/nurse/infusions?orderId={source['id']}", who=who)
    row = infusion(case)
    assert set(row) == {'id', 'status', 'started_at', 'finished_at', 'item_name',
                        'usage_route', 'patient_name', 'checks', 'order_status'}, row
    assert row['status'] == 'PENDING' and row['order_status'] == source['status']
    assert row['started_at'] is None and row['finished_at'] is None and row['checks'] == 0
    assert not candidate(source['id'], who)
    before = snapshot(case)
    expect(4501, 'POST', f"/outpatient/nurse/infusions?orderId={source['id']}", who=who)
    assert snapshot(case) == before, 'duplicate creation must retain the one real infusion'
    return row['id']


def refuse_refund(case, code):
    before = snapshot(case)
    expect(code, 'POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier')
    assert snapshot(case) == before, 'rejected refund must preserve the entire bill and care facts'
    assert charge_row(case)['status'] == 'PAID'


# Every existing execution role reads the same shared queue and may write its own report.
executed = visit('执行三类', [line('LAB'), line('EXAM'), line('TREAT')])
ids = {row['id'] for row in orders(executed)}
for who in ['admin', 'technician', 'nurse']:
    queue = expect(0, 'GET', '/outpatient/exec/worklist', who=who)
    assert ids <= {row['orderId'] for row in queue}
    assert reports(executed, who) == []
for who in ['doctor', 'cashier', 'pharmacist']:
    before = snapshot(executed)
    for method, path, body in [
        ('GET', '/outpatient/exec/worklist', None),
        ('GET', '/outpatient/exec/reports?registrationId=' + str(executed['registration']['id']), None),
        ('POST', '/outpatient/exec/' + str(order(executed, 'LAB')['id']), {'resultText': '越岗结果'}),
    ]:
        expect(1005, method, path, body, who=who)
        assert snapshot(executed) == before
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)

before = snapshot(executed)
expect(4000, 'POST', '/outpatient/exec/' + str(order(executed, 'LAB')['id']), {'resultText': 'x' * 2001})
assert snapshot(executed) == before
for kind, who, result in [('LAB', 'admin', None), ('EXAM', 'technician', ''),
                          ('TREAT', 'nurse', 'V111模拟治疗完成' + suffix)]:
    source = order(executed, kind)
    actual = expect(0, 'POST', f"/outpatient/exec/{source['id']}", {'resultText': result}, who=who)
    assert actual == {'orderId': source['id'], 'status': 'EXECUTED'}
    saved = next(row for row in reports(executed, who) if row['orderId'] == source['id'])
    assert set(saved) == {'id', 'orderId', 'resultText', 'executorId', 'executedAt'}
    assert saved['resultText'] == result and saved['executorId'] == identities[who]['id']
    assert saved['executedAt']
    before = snapshot(executed)
    expect(7002, 'POST', f"/outpatient/exec/{source['id']}", {'resultText': '不能覆盖'}, who=who)
    assert snapshot(executed) == before
assert len(reports(executed)) == 3
assert not ids & {row['orderId'] for row in expect(0, 'GET', '/outpatient/exec/worklist')}
refuse_refund(executed, 5005)
print('[v111-1] ADMIN/TECHNICIAN/NURSE execute LAB/EXAM/TREAT; null/empty results, '
      'unique attributed reports, 4000 length, 7002 repeat, wrong-role 1005 and live tokens OK')

# A retained PENDING infusion is not evidence that its source order is still paid.
pending = visit('待输退款', [line('DRUG')], fee_schedule)
pending_id = create_infusion(pending)
for kind in ['DRUG', 'REG']:
    before = snapshot(pending)
    expect(7003, 'POST', f"/outpatient/exec/{order(pending, kind)['id']}", {'resultText': ''})
    assert snapshot(pending) == before
for who in ['technician', 'doctor', 'cashier', 'pharmacist']:
    before = snapshot(pending)
    for method, path in [
        ('GET', '/outpatient/nurse/infusion-candidates'),
        ('GET', '/outpatient/nurse/infusions'),
        ('POST', f"/outpatient/nurse/infusions?orderId={order(pending)['id']}"),
        ('PUT', f'/outpatient/nurse/infusions/{pending_id}/start'),
        ('POST', f'/outpatient/nurse/infusions/{pending_id}/checks?note=' + q('越岗巡视')),
        ('PUT', f'/outpatient/nurse/infusions/{pending_id}/finish'),
    ]:
        expect(1005, method, path, who=who)
        assert snapshot(pending) == before
for who in ['technician', 'doctor', 'nurse', 'pharmacist']:
    before = snapshot(pending)
    expect(1005, 'POST', f"/outpatient/charges/{pending['charge']['id']}/refund", who=who)
    assert snapshot(pending) == before
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
refunded = expect(0, 'POST', f"/outpatient/charges/{pending['charge']['id']}/refund", who='cashier')
assert refunded['status'] == 'REFUNDED' and refunded['refundBy'] == identities['cashier']['id']
assert all(row['status'] == 'CREATED' and row['chargeId'] is None for row in orders(pending))
row = infusion(pending)
assert row['id'] == pending_id and row['status'] == 'PENDING' and row['order_status'] == 'CREATED'
assert row['started_at'] is None and row['finished_at'] is None and row['checks'] == 0
before = snapshot(pending)
expect(4501, 'PUT', f'/outpatient/nurse/infusions/{pending_id}/start', who='cover')
expect(4501, 'POST', f"/outpatient/nurse/infusions?orderId={order(pending)['id']}", who='nurse')
expect(7002, 'POST', f"/outpatient/exec/{order(pending)['id']}", {'resultText': ''})
expect(5003, 'POST', f"/outpatient/charges/{pending['charge']['id']}/refund", who='cashier')
assert snapshot(pending) == before
for source in orders(pending):
    expect(0, 'PUT', f"/outpatient/doctor/orders/{source['id']}/cancel", who='doctor')
assert all(row['status'] == 'CANCELLED' and row['chargeId'] is None for row in orders(pending))
assert infusion(pending)['order_status'] == 'CANCELLED' and infusion(pending)['status'] == 'PENDING'
assert stock() == {'plain': 30, 'skin': 30}
print('[v111-2] DRUG/REG 7003; all nursing/refund wrong-role 1005; PENDING CASH refunded, '
      'order_status=CREATED retained and stale start 4501, unpaid orders then cancelled OK')

# The whole mixed bill remains paid when just one infusion has begun or finished.
running = visit('已输整单', [line('DRUG'), line('LAB')])
running_id = create_infusion(running, 'admin')
before_stock = stock()
expect(0, 'PUT', f'/outpatient/nurse/infusions/{running_id}/start', who='nurse')
row = infusion(running)
assert row['status'] == 'RUNNING' and row['order_status'] == 'CHARGED' and row['started_at']
assert row['finished_at'] is None and row['checks'] == 0
assert stock() == before_stock
refuse_refund(running, 5005)
before = snapshot(running)
expect(4501, 'PUT', f'/outpatient/nurse/infusions/{running_id}/start', who='cover')
for note in ['', ' ', 'x' * 501]:
    expect(4000, 'POST', f'/outpatient/nurse/infusions/{running_id}/checks?note=' + q(note), who='cover')
assert snapshot(running) == before
for who in ['technician', 'doctor', 'cashier', 'pharmacist']:
    for method, tail in [('POST', '/checks?note=' + q('越岗巡视')), ('PUT', '/finish')]:
        expect(1005, method, f'/outpatient/nurse/infusions/{running_id}' + tail, who=who)
        assert snapshot(running) == before
expect(0, 'POST', f'/outpatient/nurse/infusions/{running_id}/checks?note=' +
       q('V111模拟巡视，患者无新增诉求' + suffix), who='cover')
assert infusion(running)['checks'] == 1
expect(0, 'PUT', f'/outpatient/nurse/infusions/{running_id}/finish', who='cover')
row = infusion(running)
assert row['status'] == 'DONE' and row['finished_at'] and row['checks'] == 1
assert row['order_status'] == 'CHARGED' and stock() == before_stock
assert all(row['status'] == 'CHARGED' and row['chargeId'] == running['charge']['id'] for row in orders(running))
refuse_refund(running, 5005)
before = snapshot(running)
expect(4501, 'POST', f'/outpatient/nurse/infusions/{running_id}/checks?note=' + q('迟到巡视'), who='nurse')
expect(4501, 'PUT', f'/outpatient/nurse/infusions/{running_id}/start', who='admin')
expect(4501, 'PUT', f'/outpatient/nurse/infusions/{running_id}/finish', who='admin')
assert snapshot(running) == before
print('[v111-3] ADMIN creates and nurses cooperate across start/check/finish; RUNNING/DONE '
      '5005 preserve mixed bill, 4000 invalid notes, DONE late writes 4501, DRUG/stock unchanged OK')

# DISPENSED keeps its established 5004 precedence. Only dispensing changes this test
# drug's inventory; subsequent nursing operations do not turn the DRUG into EXECUTED.
dispensed = visit('发药护理', [line('DRUG')])
before_stock = stock()
given = expect(0, 'POST', f"/outpatient/dispense/{dispensed['registration']['id']}", who='pharmacist')
assert len(given) == 1 and given[0]['id'] == order(dispensed)['id'] and given[0]['status'] == 'DISPENSED'
expected_stock = dict(before_stock, plain=before_stock['plain'] - 1)
assert stock() == expected_stock
dispensed_id = create_infusion(dispensed, 'admin')
expect(0, 'PUT', f'/outpatient/nurse/infusions/{dispensed_id}/start', who='admin')
assert infusion(dispensed)['status'] == 'RUNNING' and infusion(dispensed)['order_status'] == 'DISPENSED'
refuse_refund(dispensed, 5004)
expect(0, 'POST', f'/outpatient/nurse/infusions/{dispensed_id}/checks?note=' + q('V111发药后实际巡视'), who='admin')
expect(0, 'PUT', f'/outpatient/nurse/infusions/{dispensed_id}/finish', who='nurse')
assert infusion(dispensed)['status'] == 'DONE' and order(dispensed)['status'] == 'DISPENSED'
assert stock() == expected_stock
refuse_refund(dispensed, 5004)
print('[v111-4] PHARMACIST dispenses one own test unit; ADMIN/NURSE care keeps DISPENSED '
      'and stock, RUNNING/DONE refund 5004 precedence preserved OK')

# Keep the existing same-visit / same-category ANY NEG rule. A different category NEG
# cannot cover a penicillin-class order; a later POS does not silently introduce a new
# latest-result policy in this permission/concurrency release.
skin = visit('同类皮试', [line('DRUG', 'skin')])
skin_id = create_infusion(skin, 'nurse')


def skin_result(name, result):
    path = '/outpatient/nurse/skin-tests?registrationId=' + str(skin['registration']['id'])
    before_ids = {row['id'] for row in expect(0, 'GET', path, who='nurse')}
    expect(0, 'POST', '/outpatient/nurse/skin-tests', {
        'registrationId': skin['registration']['id'], 'drugName': name,
    }, who='nurse')
    created = [row for row in expect(0, 'GET', path, who='nurse') if row['id'] not in before_ids]
    assert len(created) == 1 and created[0]['result'] == 'PENDING'
    expect(0, 'PUT', f"/outpatient/nurse/skin-tests/{created[0]['id']}/result?result={result}", who='cover')


before = snapshot(skin)
expect(4502, 'PUT', f'/outpatient/nurse/infusions/{skin_id}/start', who='nurse')
assert snapshot(skin) == before
skin_result('V111头孢模拟皮试', 'NEG')
before = snapshot(skin)
expect(4502, 'PUT', f'/outpatient/nurse/infusions/{skin_id}/start', who='nurse')
assert snapshot(skin) == before
skin_result('V111青霉素模拟皮试', 'NEG')
skin_result('V111西林模拟皮试', 'POS')
before_stock = stock()
expect(0, 'PUT', f'/outpatient/nurse/infusions/{skin_id}/start', who='nurse')
expect(0, 'POST', f'/outpatient/nurse/infusions/{skin_id}/checks?note=' + q('V111同类皮试后实际巡视'), who='cover')
expect(0, 'PUT', f'/outpatient/nurse/infusions/{skin_id}/finish', who='admin')
assert infusion(skin)['status'] == 'DONE' and infusion(skin)['order_status'] == 'CHARGED'
assert order(skin)['status'] == 'CHARGED' and stock() == before_stock
refuse_refund(skin, 5005)

for who in tokens:
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
assert stock() == {'plain': 29, 'skin': 30}
assert charge_row(pending)['status'] == 'REFUNDED'
assert all(charge_row(case)['status'] == 'PAID' for case in [executed, running, dispensed, skin])
print('[v111-5] absent/wrong-category 4502, same-visit same-category ANY NEG retained; '
      'plain drugs need no skin test; every employee still opens home OK')
print('e2e-v111-execution-infusion-refund passed: real JWT role boundaries, unique generic '
      'reports, nursing cooperation and source order_status, PENDING refund/stale-start '
      'refusal, RUNNING/DONE whole-bill 5005, DISPENSED 5004, unchanged nursing inventory; '
      'one CASH bill refunded and cancelled, four synthetic PAID histories plus three DONE '
      'infusions retained, one own dispensed test unit consumed, no admission')
