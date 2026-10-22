# -*- coding: utf-8 -*-
"""Real JWT pharmacy return / infusion boundaries, using public APIs only.

Every employee, patient, schedule and drug is private to this invocation. The suite
does not change shared stock, gates or prices. V112ReturnInfusionRaceTest proves
transaction rollback and actual PostgreSQL lock waits; this sequential HTTP suite
checks the resulting role, inventory and retained-care contracts through JWT.

Inventory HTTP reads expose aggregate quantities and batch operator_id. Aggregate
operator attribution is asserted by the Java tests, because its existing HTTP
projection deliberately has no operator field. No response contract is invented.
Used simulated drugs and completed care remain as real test histories: no unsafe
return, inventory adjustment or history deletion is used to manufacture cleanup.
"""
import datetime
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, provision_user, q, today_bj


suffix = str(time.time_ns())[-11:]
bootstrap = login()
tokens = {'admin': provision_user(bootstrap, 'v112admin' + suffix, 'ADMIN')}
for who, role in [('pharmacist', 'PHARMACIST'), ('cover', 'PHARMACIST'),
                  ('nurse', 'NURSE'), ('doctor', 'DOCTOR_OUTP'),
                  ('cashier', 'CASHIER'), ('technician', 'TECHNICIAN'),
                  ('disabled', 'PHARMACIST')]:
    tokens[who] = provision_user(tokens['admin'], 'v112' + who + suffix, role,
                                 'V112' + who + suffix)


def expect(code, method, path, body=None, who='admin', text=None):
    try:
        response = call(method, path, body, tokens[who], text=text)
    except urllib.error.HTTPError as error:
        assert (code, error.code) in [(1005, 403), (1000, 401)], (
            who, method, path, code, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


identities = {who: expect(0, 'GET', '/auth/me', who=who) for who in tokens}
assert len({person['id'] for person in identities.values()}) == len(tokens)
for who in tokens:
    expect(0, 'GET', '/stats/overview', who=who)

department = expect(0, 'POST', '/system/depts', {
    'code': 'V112D' + suffix, 'name': 'V112发退药模拟' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': today_bj().isoformat(),
    'fee': 0, 'capacity': 3,
})

# Two batch-managed dictionaries start at zero. The non-managed sibling uses the
# established aggregate-only path, so both supported inventory models are covered.
drug_codes = {kind: 'V112' + kind + suffix for kind in ['BASE', 'PENDING', 'USED', 'PLAIN']}
csv = 'code,name,spec,unit,dose_form,price,stock,antibiotic\n' + '\n'.join(
    f'{code},V112{kind}模拟液{suffix},模拟1ml,支,注射剂,1.00,{10 if kind == "PLAIN" else 0},0'
    for kind, code in drug_codes.items())
imported = expect(0, 'POST', '/masterdata/drugs/import', text=csv)
assert imported['imported'] == 4 and imported['errorCount'] == 0, imported
drugs = {kind: next(row for row in expect(0, 'GET', '/masterdata/drugs?keyword=' + suffix)
                    if row['code'] == code) for kind, code in drug_codes.items()}
assert all(drug['enabled'] for drug in drugs.values())

item_code = 'V112TREAT' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price\n' +
                  f'{item_code},V112模拟治疗{suffix},TREAT,次,1.00\n')
assert imported['imported'] == 1 and imported['errorCount'] == 0, imported
treatment = next(row for row in expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
                 if row['code'] == item_code)


def stock_in(kind, name, qty, days):
    result = expect(0, 'POST', '/pharm/stock/stock-in', {
        'drugId': drugs[kind]['id'], 'qty': qty, 'batchNo': 'V112' + name + suffix,
        'producedOn': (today_bj() - datetime.timedelta(days=10)).isoformat(),
        'expireOn': (today_bj() + datetime.timedelta(days=days)).isoformat(),
        'supplier': 'V112模拟供应商', 'purchasePrice': '0.50',
    }, who='pharmacist')
    assert result['drugId'] == drugs[kind]['id'] and result['qty'] == qty
    return result['batchId']


base_batch = stock_in('BASE', 'BASE', 5, 365)
pending_a = stock_in('PENDING', 'PA', 1, 365)
pending_b = stock_in('PENDING', 'PB', 4, 730)
used_batch = stock_in('USED', 'USED', 5, 365)


def drug_line(kind, qty=1):
    return {'orderType': 'DRUG', 'itemId': drugs[kind]['id'], 'qty': qty,
            'usageRoute': '静脉滴注', 'frequency': 'qd', 'dosePerTime': '模拟1ml', 'days': 1}


def workspace(case):
    return expect(0, 'GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")


def orders(case):
    return workspace(case)['orders']


def order(case, kind):
    return next(row for row in orders(case) if row['orderType'] == 'DRUG'
                and row['itemId'] == drugs[kind]['id'])


def visit(label, lines):
    patient = expect(0, 'POST', '/patients', {'name': 'V112' + label + suffix, 'sex': 'U'})
    reg = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    case = {'registration': reg, 'patient': patient}
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
    expect(0, 'POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': lines}, who='doctor')
    before = orders(case)
    assert all(row['status'] == 'CREATED' and row['chargeId'] is None for row in before)
    charge = expect(0, 'POST', '/outpatient/charges/settle', {
        'registrationId': reg['id'], 'payMethod': 'CASH',
    }, who='cashier')
    assert charge['status'] == 'PAID' and charge['cashierId'] == identities['cashier']['id']
    assert Decimal(str(charge['totalAmount'])) == sum(Decimal(str(row['amount'])) for row in before)
    assert Decimal(str(charge['totalAmount'])) < Decimal('10')
    assert all(row['status'] == 'CHARGED' and row['chargeId'] == charge['id'] for row in orders(case))
    case['charge'] = charge
    return case


def batch_flows(kind):
    return expect(0, 'GET', f"/pharm/stock/flows?drugId={drugs[kind]['id']}")


def inventory():
    current = expect(0, 'GET', '/masterdata/drugs?keyword=' + suffix)
    return {kind: {
        'stock': next(row['stock'] for row in current if row['id'] == drug['id']),
        'batches': expect(0, 'GET', f"/pharm/stock/balance/by-batch?drugId={drug['id']}"),
        'flows': batch_flows(kind),
        'transactions': expect(0, 'GET', f"/inventory/transactions?drugId={drug['id']}"),
    } for kind, drug in drugs.items()}


def care(case):
    return [row for row in expect(0, 'GET', '/outpatient/nurse/infusions')
            if row['patient_name'] == case['patient']['name']]


def dispensed(case, who='admin'):
    return expect(0, 'GET', '/outpatient/dispense/dispensed?registrationId=' +
                  str(case['registration']['id']), who=who)


def queued(case, who='admin'):
    return next((row for row in expect(0, 'GET', '/outpatient/dispense/worklist', who=who)
                 if row['registrationId'] == case['registration']['id']), None)


def snapshot(case):
    bill = next(row for row in expect(0, 'GET', '/outpatient/charges/recent', who='cashier')
                if row['chargeId'] == case['charge']['id'])
    return {'orders': orders(case), 'care': care(case), 'bill': bill,
            'dispensed': dispensed(case), 'queued': queued(case), 'inventory': inventory()}


def assert_delta(before, after, case, kind, delta, flow_type, actor, batch_deltas):
    """One order changes one aggregate ledger and exactly its allocated batches."""
    source = order(case, kind)
    for other in drugs:
        if other != kind:
            assert before[other] == after[other], ('unrelated inventory changed', other)
    old, new = before[kind], after[kind]
    assert new['stock'] == old['stock'] + delta, (kind, old, new)
    assert len(new['transactions']) == len(old['transactions']) + 1
    assert new['transactions'][1:] == old['transactions']
    transaction = new['transactions'][0]
    assert transaction['type'] == flow_type and transaction['qty'] == delta, transaction
    assert transaction['stockAfter'] == new['stock'] and transaction['refNo'] == source['groupNo']
    old_ids = {row['id'] for row in old['flows']}
    added = [row for row in new['flows'] if row['id'] not in old_ids]
    assert [row for row in new['flows'] if row['id'] in old_ids] == old['flows']
    assert len(added) == len(batch_deltas), (kind, added, batch_deltas)
    assert {row['batch_id']: row['qty_delta'] for row in added} == batch_deltas
    assert all(row['flow_type'] == flow_type and row['operator_id'] == identities[actor]['id']
               and row['ref_no'] == source['groupNo'] for row in added), added
    old_qty = {row['batch_id']: row['qty'] for row in old['batches']}
    new_qty = {row['batch_id']: row['qty'] for row in new['batches']}
    assert new_qty == {batch: qty + batch_deltas.get(batch, 0) for batch, qty in old_qty.items()}
    assert all(row['qty_after'] == new_qty[row['batch_id']] for row in added)


def give(case, who):
    result = expect(0, 'POST', f"/outpatient/dispense/{case['registration']['id']}", who=who)
    assert {row['id'] for row in result} == {row['id'] for row in dispensed(case)}
    assert all(row['status'] == 'DISPENSED' for row in result)
    assert queued(case) is None


def return_one(case, kind, who):
    source = order(case, kind)
    result = expect(0, 'POST', f"/outpatient/dispense/orders/{source['id']}/return", who=who)
    assert result['id'] == source['id'] and result['status'] == 'CHARGED'
    assert order(case, kind)['chargeId'] == case['charge']['id']
    assert source['id'] not in {row['id'] for row in dispensed(case)}
    assert source['id'] in {row['id'] for row in queued(case)['orders']}


def create_infusion(case, kind):
    source = order(case, kind)
    before_ids = {row['id'] for row in care(case)}
    expect(0, 'POST', f"/outpatient/nurse/infusions?orderId={source['id']}", who='nurse')
    created = [row for row in care(case) if row['id'] not in before_ids]
    assert len(created) == 1 and created[0]['status'] == 'PENDING'
    assert created[0]['started_at'] is None and created[0]['finished_at'] is None
    return created[0]['id']


def refuse_return(case, kind, who='pharmacist'):
    before = snapshot(case)
    expect(6004, 'POST', f"/outpatient/dispense/orders/{order(case, kind)['id']}/return", who=who)
    assert snapshot(case) == before, '6004 must preserve order, bill, care, both stocks and all ledgers'


# Positive baseline: no infusion permits a normal batch-managed return. All five
# pharmacy endpoints reject clinical/cashier roles without invalidating their JWT.
baseline = visit('正常发退', [drug_line('BASE')])
for who in ['admin', 'pharmacist', 'cover']:
    row = queued(baseline, who)
    assert set(row) == {'registrationId', 'patientNo', 'patientName', 'orders'}
    assert {item['id'] for item in row['orders']} == {order(baseline, 'BASE')['id']}
    assert dispensed(baseline, who) == []
    patients = expect(0, 'GET', '/outpatient/dispense/registrations?date=' +
                      today_bj().isoformat(), who=who)
    selected = next(row for row in patients if row['id'] == baseline['registration']['id'])
    assert set(selected) == {'id', 'patientName', 'patientNo', 'deptName'}, selected
    assert selected['patientName'] == baseline['patient']['name']
    assert selected['patientNo'] == baseline['patient']['patientNo']
    assert selected['deptName'] == department['name']
for who in ['pharmacist', 'cover']:
    expect(1005, 'GET', '/outpatient/registrations?date=' + today_bj().isoformat(), who=who)
for who in ['doctor', 'nurse', 'cashier', 'technician']:
    before = snapshot(baseline)
    for method, path in [
        ('GET', '/outpatient/dispense/worklist'),
        ('GET', '/outpatient/dispense/registrations?date=' + today_bj().isoformat()),
        ('GET', '/outpatient/dispense/dispensed?registrationId=' + str(baseline['registration']['id'])),
        ('POST', '/outpatient/dispense/' + str(baseline['registration']['id'])),
        ('POST', '/outpatient/dispense/orders/' + str(order(baseline, 'BASE')['id']) + '/return'),
    ]:
        expect(1005, method, path, who=who)
        assert snapshot(baseline) == before
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)

expect(0, 'PUT', f"/system/users/{identities['disabled']['id']}/enabled?enabled=false")
before = snapshot(baseline)
for method, path in [
    ('GET', '/outpatient/dispense/worklist'),
    ('GET', '/outpatient/dispense/registrations?date=' + today_bj().isoformat()),
    ('GET', '/outpatient/dispense/dispensed?registrationId=' + str(baseline['registration']['id'])),
    ('POST', '/outpatient/dispense/' + str(baseline['registration']['id'])),
    ('POST', '/outpatient/dispense/orders/' + str(order(baseline, 'BASE')['id']) + '/return'),
]:
    expect(1000, method, path, who='disabled')
    assert snapshot(baseline) == before

before = inventory()
give(baseline, 'pharmacist')
assert_delta(before, inventory(), baseline, 'BASE', -1, 'OUT', 'pharmacist', {base_batch: -1})
before = snapshot(baseline)
expect(6001, 'POST', f"/outpatient/dispense/{baseline['registration']['id']}", who='cover')
assert snapshot(baseline) == before
before = inventory()
return_one(baseline, 'BASE', 'admin')
assert_delta(before, inventory(), baseline, 'BASE', 1, 'RET', 'admin', {base_batch: 1})
refuse_return(baseline, 'BASE', 'cover')
print('[v112-1] shared ADMIN/PHARMACIST reads/writes and private patient selector; '
      'registration desk stays denied to pharmacists; five wrong-role routes 1005, '
      'disabled JWT 1000; normal OUT/RET quantities and real batch actors, duplicate 6001/6004 OK')

# PENDING is not proof of administration. Return restores the actual issued A/B
# allocation even after a newer, earlier-expiry C batch enters the stock room.
pending = visit('待输原批', [drug_line('PENDING', 3)])
pending_id = create_infusion(pending, 'PENDING')
before = inventory()
give(pending, 'pharmacist')
assert_delta(before, inventory(), pending, 'PENDING', -3, 'OUT', 'pharmacist',
             {pending_a: -1, pending_b: -2})
pending_c = stock_in('PENDING', 'PC', 2, 30)
before = inventory()
return_one(pending, 'PENDING', 'cover')
assert_delta(before, inventory(), pending, 'PENDING', 3, 'RET', 'cover',
             {pending_a: 1, pending_b: 2})
assert care(pending)[0]['id'] == pending_id and care(pending)[0]['status'] == 'PENDING'
assert care(pending)[0]['order_status'] == 'CHARGED'
assert next(row['qty'] for row in inventory()['PENDING']['batches']
            if row['batch_id'] == pending_c) == 2
refuse_return(pending, 'PENDING')

# Retained PENDING care must not block legitimate re-dispensing. Its second return
# also uses that second issue's real allocation, without double-returning A/B.
# Read the configured pick rule rather than changing shared FEFO/FIFO settings.
second_plan = expect(0, 'GET', f"/pharm/stock/pick-preview?drugId={drugs['PENDING']['id']}&qty=3")
assert second_plan['satisfied'] and second_plan['allocated'] == 3
second_allocation = {row['batchId']: row['picked'] for row in second_plan['lines']}
before = inventory()
give(pending, 'admin')
assert_delta(before, inventory(), pending, 'PENDING', -3, 'OUT', 'admin',
             {batch: -qty for batch, qty in second_allocation.items()})
before = inventory()
return_one(pending, 'PENDING', 'pharmacist')
assert_delta(before, inventory(), pending, 'PENDING', 3, 'RET', 'pharmacist',
             second_allocation)
before = inventory()
expect(0, 'PUT', f'/outpatient/nurse/infusions/{pending_id}/start', who='nurse')
row = care(pending)[0]
assert row['status'] == 'RUNNING' and row['order_status'] == 'CHARGED' and row['started_at']
assert inventory() == before
expect(0, 'POST', f'/outpatient/nurse/infusions/{pending_id}/checks?note=' + q('V112模拟实际巡视'), who='nurse')
expect(0, 'PUT', f'/outpatient/nurse/infusions/{pending_id}/finish', who='nurse')
assert care(pending)[0]['status'] == 'DONE' and care(pending)[0]['checks'] == 1
assert care(pending)[0]['finished_at'] and inventory() == before
refuse_return(pending, 'PENDING')
before = snapshot(pending)
expect(5005, 'POST', f"/outpatient/charges/{pending['charge']['id']}/refund", who='cashier')
assert snapshot(pending) == before
print('[v112-2] PENDING return restores original A/B; re-dispense and its exact-allocation return '
      'remain legal; return-first CHARGED nursing start still succeeds without stock change OK')

# The sibling order proves the guard is tied to the actual source order, not every
# infusion under this registration. RUNNING and DONE block only the used source.
used = visit('已输拒退', [drug_line('USED'), drug_line('PLAIN'),
                       {'orderType': 'TREAT', 'itemId': treatment['id'], 'qty': 1}])
before = snapshot(used)
non_drug = next(row for row in orders(used) if row['orderType'] == 'TREAT')
expect(6004, 'POST', f"/outpatient/dispense/orders/{non_drug['id']}/return", who='pharmacist')
expect(6006, 'POST', '/outpatient/dispense/orders/-1/return', who='admin')
assert snapshot(used) == before
before_inventory = inventory()
give(used, 'cover')
after_inventory = inventory()
assert after_inventory['USED']['stock'] == 4 and after_inventory['PLAIN']['stock'] == 9
for kind in ['BASE', 'PENDING']:
    assert before_inventory[kind] == after_inventory[kind]
for kind in ['USED', 'PLAIN']:
    old, new = before_inventory[kind], after_inventory[kind]
    assert new['transactions'][1:] == old['transactions']
    assert new['transactions'][0]['type'] == 'OUT' and new['transactions'][0]['qty'] == -1
    assert new['transactions'][0]['stockAfter'] == new['stock']
    assert new['transactions'][0]['refNo'] == order(used, kind)['groupNo']
assert after_inventory['PLAIN']['batches'] == [] and after_inventory['PLAIN']['flows'] == []
old_flow_ids = {row['id'] for row in before_inventory['USED']['flows']}
added = [row for row in after_inventory['USED']['flows'] if row['id'] not in old_flow_ids]
assert len(added) == 1 and added[0]['batch_id'] == used_batch
assert added[0]['flow_type'] == 'OUT' and added[0]['qty_delta'] == -1
assert added[0]['operator_id'] == identities['cover']['id']
assert added[0]['ref_no'] == order(used, 'USED')['groupNo']
used_id = create_infusion(used, 'USED')
expect(0, 'PUT', f'/outpatient/nurse/infusions/{used_id}/start', who='nurse')
assert care(used)[0]['status'] == 'RUNNING' and care(used)[0]['order_status'] == 'DISPENSED'
for who in ['admin', 'pharmacist', 'cover']:
    refuse_return(used, 'USED', who)
before = inventory()
return_one(used, 'PLAIN', 'cover')
assert_delta(before, inventory(), used, 'PLAIN', 1, 'RET', 'cover', {})
assert order(used, 'USED')['status'] == 'DISPENSED'
assert care(used)[0]['status'] == 'RUNNING'
expect(0, 'POST', f'/outpatient/nurse/infusions/{used_id}/checks?note=' + q('V112已发模拟液实际巡视'), who='nurse')
expect(0, 'PUT', f'/outpatient/nurse/infusions/{used_id}/finish', who='admin')
assert care(used)[0]['status'] == 'DONE' and care(used)[0]['finished_at']
for who in ['admin', 'pharmacist']:
    refuse_return(used, 'USED', who)
before = snapshot(used)
expect(5004, 'POST', f"/outpatient/charges/{used['charge']['id']}/refund", who='cashier')
assert snapshot(used) == before
assert len(dispensed(used, 'pharmacist')) == 1
assert dispensed(used, 'cover')[0]['id'] == order(used, 'USED')['id']
print('[v112-3] RUNNING/DONE 6004 preserve source, whole bill, care, aggregate/batch stock '
      'and all ledgers; same-visit unused sibling can return; DISPENSED refund 5004 retained OK')

# The baseline has no administered care, so its one CASH bill can be refunded and
# its now-unpaid order cancelled. The two genuinely cared-for histories stay PAID.
refund = expect(0, 'POST', f"/outpatient/charges/{baseline['charge']['id']}/refund", who='cashier')
assert refund['status'] == 'REFUNDED' and refund['refundBy'] == identities['cashier']['id']
expect(0, 'PUT', f"/outpatient/doctor/orders/{order(baseline, 'BASE')['id']}/cancel", who='doctor')
assert order(baseline, 'BASE')['status'] == 'CANCELLED'
assert care(baseline) == []
for kind in ['BASE', 'PENDING', 'USED']:
    reconciliation = expect(0, 'GET', f"/pharm/stock/reconcile?drugId={drugs[kind]['id']}")
    assert reconciliation['balanced'] is True and reconciliation['mismatched'] == 0, reconciliation
assert {kind: value['stock'] for kind, value in inventory().items()} == {
    'BASE': 5, 'PENDING': 7, 'USED': 4, 'PLAIN': 10,
}
for who in tokens:
    if who != 'disabled':
        expect(0, 'GET', '/auth/me', who=who)
        expect(0, 'GET', '/stats/overview', who=who)
print('e2e-v112-pharmacy-infusion-return passed: real enabled pharmacy roles and batch '
      'actors, original-batch return, exact-once RET, retained PENDING re-dispense, '
      'CHARGED start compatibility, RUNNING/DONE refusal with zero inventory/ledger '
      'writes; one safe CASH refund/cancellation, two PAID histories and two DONE '
      'infusions retained, one own simulated issued unit consumed, no admission')
