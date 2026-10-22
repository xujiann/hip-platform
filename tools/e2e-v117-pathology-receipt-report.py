# -*- coding: utf-8 -*-
"""Real JWT regression for pathology receipt, rejection and report writes.

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
tokens = {'admin': provision_user(login(), 'v117admin' + suffix, 'ADMIN')}
machine_identity = None
for who, role in [('technician', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'), ('doctor2', 'DOCTOR_OUTP'),
                  ('nurse', 'NURSE'), ('cashier', 'CASHIER'),
                  ('pharmacist', 'PHARMACIST'), ('interface', 'INTERFACE'),
                  ('disabled', 'TECHNICIAN')]:
    username, name = 'v117' + who + suffix, 'V117' + who + suffix
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
    'code': 'V117D' + suffix, 'name': 'V117病理模拟' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999,
})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': clinic['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 30,
})
item_code, other_code = 'V117PATH' + suffix, 'V117OTHER' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V117模拟病理{suffix},EXAM,次,1.00,,,{clinic["code"]}\n' +
                  f'{other_code},V117模拟检查{suffix},EXAM,次,1.00,,,{clinic["code"]}\n')
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
            'reports': {str(row['id']): expect(0, 'GET', f"/pathology/report/{row['id']}/reports") for row in rows},
            'grossing': {str(row['id']): expect(0, 'GET', f"/pathology/process/grossing/{row['id']}") for row in rows},
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
    patient = expect(0, 'POST', '/patients', {'name': 'V117' + label + suffix, 'sex': 'U'})
    registration = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id'],
    }, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
    created = expect(0, 'POST', f"/outpatient/doctor/{registration['id']}/orders", {'lines': [{
        'orderType': 'EXAM', 'itemId': (item or path_item)['id'], 'qty': 1,
        'specimenType': '模拟组织', 'samplingSite': '模拟部位',
        'remark': 'V117模拟登记',
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
        'specimenDesc': 'V117模拟送检组织', 'samplingSite': 'V117模拟部位',
        'clinicalDiagnosis': 'V117模拟待查', 'fixative': '模拟固定液', 'urgent': True,
        **({'applyDept': 'V117模拟临床', 'applyDoctor': 'V117模拟申请人',
            'note': 'V117实物已送达模拟救济'} if manual else {}),
    }, who=who)
    if code == 0:
        assert set(result) == result_keys and result['source'] == 'OUTP'
        assert result['partNo'] == part and result['specimenType'] == 'ROUTINE'
        assert result['barcode'].startswith('PB') and result['pathNo']
        assert result['manual'] is manual
        assert result['status'] == ('RECEIVED' if manual else 'COLLECTED')
    return result


def collect(case, who='technician', code=0, description='V117旧入口模拟组织'):
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
        'reason': 'V117模拟标本固定不合格',
    }, who=who)
    actual = next(row for row in specimens(case) if row['id'] == specimen_id)
    assert actual['status'] == before['status']
    assert actual['rejected_by'] == identities[who]['id'] and actual['rejected_at']
    assert actual['reject_reason'] == 'V117模拟标本固定不合格'
    assert {k: v for k, v in actual.items() if k not in {'reject_reason', 'rejected_at', 'rejected_by'}} == {
        k: v for k, v in before.items() if k not in {'reject_reason', 'rejected_at', 'rejected_by'}}
    node = trail(specimen_id)['nodes'][-1]
    assert node['node'] == 'REJECT' and node['operator_id'] == identities[who]['id']
    assert node['occurred_at'] == actual['rejected_at']


def specimen(case, result):
    return next(row for row in specimens(case) if row['id'] == result['id'])


def report(result):
    return expect(0, 'GET', f"/pathology/report/{result['id']}/reports")


def write(result, action, who='doctor', code=0, body=None):
    sid, barcode = result['id'], result['barcode']
    routes = {
        'receive': ('PUT', f'/pathology/specimens/{barcode}/receive', None),
        'receive-check': ('PUT', f'/pathology/registry/specimens/{sid}/receive-check', {}),
        'reject': ('PUT', f'/pathology/registry/specimens/{sid}/reject', {'reason': 'V117模拟固定不合格'}),
        'diagnose': ('PUT', f'/pathology/specimens/{barcode}/diagnose',
                     {'grossFinding': 'V117模拟大体所见', 'microFinding': 'V117模拟镜下所见', 'diagnosis': 'V117模拟病理诊断'}),
        'first-sign': ('PUT', f'/pathology/report/{sid}/first-sign', None),
        'second-sign': ('PUT', f'/pathology/report/{sid}/second-sign', None),
        'issue': ('PUT', f'/pathology/report/{sid}/issue', None),
        'supplement': ('POST', f'/pathology/report/{sid}/supplement',
                       {'content': 'V117模拟补充病理诊断', 'reason': 'V117模拟追加依据'}),
    }
    method, path, default = routes[action]
    return expect(code, method, path, default if body is None else body, who=who)


def denied(case, result, action, code, who='technician', body=None):
    before = snapshot(case)
    write(result, action, who, code, body)
    assert snapshot(case) == before, ('Rejected write changed projected facts', action, who, code)


def receive(case, result, who='technician', legacy=False):
    before = specimen(case, result)
    old_nodes = trail(result['id'])['nodes']
    write(result, 'receive' if legacy else 'receive-check', who)
    actual = specimen(case, result)
    assert actual['status'] == 'RECEIVED' and actual['received_at']
    assert {k: v for k, v in actual.items() if k not in {'status', 'received_at'}} == {
        k: v for k, v in before.items() if k not in {'status', 'received_at'}}
    nodes = trail(result['id'])['nodes']
    if legacy:
        assert nodes == old_nodes, 'Legacy receipt must not invent a RECEIVE process'
    else:
        assert len(nodes) == len(old_nodes) + 1
        node = nodes[-1]
        assert node['node'] == 'RECEIVE' and node['operator_id'] == identities[who]['id']
        assert node['occurred_at'] == actual['received_at']


def diagnose(case, result, who='technician', body=None):
    before_order, before_bills = source(case), bills(case)
    answer = write(result, 'diagnose', who, body=body)
    assert set(answer) == {'specimenId', 'grossKept', 'microKept', 'grossRevised'}
    assert answer['specimenId'] == result['id']
    current = specimen(case, result)
    assert current['status'] == 'DIAGNOSED' and current['diagnosed_at']
    assert current['pathologist_id'] == identities[who]['id']
    assert current['report_issued_at'] is None
    assert source(case) == before_order and bills(case) == before_bills
    if body is None:
        assert answer['grossRevised'] is True
        revisions = expect(0, 'GET', f"/pathology/process/grossing/{result['id']}")['revisions']
        assert revisions[-1]['source'] == 'DIAGNOSE' and revisions[-1]['changedBy'] == identities[who]['id']
    return answer


def sign(result, action, who):
    before = report(result)
    answer = write(result, action, who)
    after = report(result)
    name = 'first' if action == 'first-sign' else 'second'
    assert answer[name + 'SignerId'] == identities[who]['id']
    assert after['specimen'][name + '_signer_id'] == identities[who]['id']
    assert after['specimen'][name + '_signed_at']
    assert len(after['process']) == len(before['process']) + 1
    node = after['process'][-1]
    assert node['node'] == ('FIRST_SIGN' if name == 'first' else 'SECOND_SIGN')
    assert node['operator_id'] == identities[who]['id']
    assert node['occurred_at'] == after['specimen'][name + '_signed_at']


def issue(case, result, executed, pending_parts, complete, who='doctor'):
    before = report(result)
    before_order, before_bills = source(case), bills(case)
    answer = write(result, 'issue', who)
    assert answer['orderExecuted'] is executed and answer['partsPending'] == pending_parts
    assert answer['doubleSignComplete'] is complete
    after = report(result)
    assert after['primary']['diagnosedAt'] == before['primary']['diagnosedAt']
    assert after['primary']['grossFinding'] == before['primary']['grossFinding']
    assert after['primary']['microFinding'] == before['primary']['microFinding']
    assert after['primary']['diagnosis'] == before['primary']['diagnosis']
    assert after['specimen']['status'] == before['specimen']['status'] == 'DIAGNOSED'
    assert after['primary']['reportIssuedAt'] == answer['reportIssuedAt']
    node = after['process'][-1]
    assert node['node'] == 'ISSUE' and node['operator_id'] == identities[who]['id']
    assert node['occurred_at'] == answer['reportIssuedAt']
    assert after['supplements'] == before['supplements']
    after_order = source(case)
    assert after_order['status'] == ('EXECUTED' if executed else before_order['status'])
    assert {k: v for k, v in after_order.items() if k != 'status'} == {
        k: v for k, v in before_order.items() if k != 'status'}
    assert bills(case) == before_bills
    return answer


def supplement(case, result, who='doctor'):
    before = report(result)
    answer = write(result, 'supplement', who)
    after = report(result)
    assert after['primary'] == before['primary'] and after['specimen'] == before['specimen']
    assert after['supplements'][:-1] == before['supplements']
    assert len(after['supplements']) == len(before['supplements']) + 1
    new = after['supplements'][-1]
    assert new['seq_no'] == answer['seqNo'] == len(after['supplements'])
    assert new['signer_id'] == identities[who]['id'] == answer['signerId']
    assert new['signed_at'] == answer['signedAt']
    node = after['process'][-1]
    assert node['node'] == 'SUPPLEMENT' and node['operator_id'] == identities[who]['id']


# Public JWT boundaries, with all eight requests targeted at a genuine live S.
security_case = visit('真实岗位边界')
security_specimen = register(security_case)
actions = ['receive', 'receive-check', 'reject', 'diagnose',
           'first-sign', 'second-sign', 'issue', 'supplement']
for who in ['nurse', 'cashier', 'pharmacist', 'interface']:
    for action in actions:
        denied(security_case, security_specimen, action, 1005, who)
    if who != 'interface':
        expect(0, 'GET', '/auth/me', who=who)
        expect(0, 'GET', '/stats/overview', who=who)
for action in ['first-sign', 'second-sign', 'issue', 'supplement']:
    denied(security_case, security_specimen, action, 1005, 'technician')
for action in actions:
    denied(security_case, security_specimen, action, 1000, None)
disabled = identities['disabled']
expect(0, 'PUT', f"/system/users/{disabled['id']}", {
    'username': disabled['username'], 'realName': disabled['realName'],
    'enabled': False, 'roleCodes': ['TECHNICIAN'],
})
for action in actions:
    denied(security_case, security_specimen, action, 1000, 'disabled')
print('[1] Real JWT eight-write wrong-duty, anonymous, machine and disabled refusals have zero projected writes')

# Both receipt producers keep their exact state and history behavior.
for who in ['admin', 'technician', 'doctor']:
    case = visit('新核收' + who)
    result = register(case)
    denied(case, result, 'receive-check', 5210, who, {'receivedAt': 'not-a-time'})
    denied(case, result, 'receive-check', 5210, who, {
        'receivedAt': (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=1)).isoformat()})
    receive(case, result, who)
    denied(case, result, 'receive-check', 5208, who)
    denied(case, result, 'receive', 4551, who)
    rejected = register(case, part=2)
    denied(case, rejected, 'reject', 5207, who, {'reason': '  '})
    denied(case, rejected, 'reject', 5210, who, {'reason': '模拟', 'rejectedAt': 'bad-time'})
    reject(case, rejected['id'], who)
    denied(case, rejected, 'reject', 5208, who)
    denied(case, rejected, 'receive-check', 5208, who)
    denied(case, rejected, 'receive', 4551, who)
    denied(case, rejected, 'diagnose', 4553, who)
    for action in ['first-sign', 'second-sign', 'issue', 'supplement']:
        denied(case, rejected, action, 5261, 'doctor')
    legacy_case = visit('旧核收' + who)
    legacy = collect(legacy_case, who)
    legacy_result = specimens(legacy_case)[0]
    legacy_result = {'id': legacy_result['id'], 'barcode': legacy['barcode']}
    receive(legacy_case, legacy_result, who, legacy=True)
    diagnose(legacy_case, legacy_result, who)
    denied(legacy_case, legacy_result, 'diagnose', 4553, who)
    denied(legacy_case, legacy_result, 'reject', 5208, who)
    refund(legacy_case, 5005)
print('[2] Three original clinical duties retain new/legacy receipt, diagnosis, rejection and exact process attribution')

absent = {'id': 9223372036854770000, 'barcode': 'V117-ABSENT-' + suffix}
for action, code in [('receive', 4551), ('receive-check', 5206), ('reject', 5206),
                     ('diagnose', 4553), ('first-sign', 5260), ('second-sign', 5260),
                     ('issue', 5260), ('supplement', 5260)]:
    denied(security_case, absent, action, code, 'doctor')
for action, code in [('diagnose', 4553), ('first-sign', 5262), ('second-sign', 5264),
                     ('issue', 5262), ('supplement', 5262)]:
    denied(security_case, security_specimen, action, code, 'doctor')

# Write actual gross evidence, then prove the blank-preservation diagnosis path.
case = visit('双签完整')
result = register(case)
receive(case, result)
gross = expect(0, 'POST', '/pathology/process/grossing', {
    'specimenId': result['id'], 'grossText': 'V117实际模拟取材原始所见',
    'blocks': [{'tissueDesc': 'V117模拟组织块'}],
}, who='technician')
before_gross = expect(0, 'GET', f"/pathology/process/grossing/{result['id']}")
answer = diagnose(case, result, 'technician', {
    'grossFinding': '  ', 'microFinding': 'V117实际模拟镜下所见', 'diagnosis': 'V117实际模拟首次诊断'})
assert answer['grossKept'] is True and answer['microKept'] is False and answer['grossRevised'] is False
after_gross = expect(0, 'GET', f"/pathology/process/grossing/{result['id']}")
assert after_gross['revisions'] == before_gross['revisions']
assert report(result)['primary']['grossFinding'] == gross['grossFinding']
supplement(case, result, 'doctor2')
assert report(result)['primary']['reportIssuedAt'] is None, 'Supplement before issue remains legal'
denied(case, result, 'second-sign', 5264, 'doctor2')
sign(result, 'first-sign', 'doctor')
denied(case, result, 'first-sign', 5261, 'doctor2')
denied(case, result, 'second-sign', 5263, 'doctor')
sign(result, 'second-sign', 'doctor2')
denied(case, result, 'second-sign', 5261, 'doctor')
answer = issue(case, result, True, 0, True)
assert answer['warnings'] == []
for action in ['first-sign', 'second-sign', 'issue']:
    denied(case, result, action, 5261, 'doctor2')
supplement(case, result, 'doctor')
supplement(case, result, 'admin')
refund(case, 5005)
print('[3] Preserved gross evidence, pre-issue supplement, different-person signatures, issue and append-only seq 1/2/3 passed')

# The final active part alone changes the real source order to EXECUTED.
parts_case = visit('两部位签发')
parts = [register(parts_case, part=n) for n in [1, 2]]
for index, entry in enumerate(parts):
    receive(parts_case, entry)
    diagnose(parts_case, entry)
    sign(entry, 'first-sign', 'doctor' if index == 0 else 'admin')
    sign(entry, 'second-sign', 'admin' if index == 0 else 'doctor2')
issue(parts_case, parts[0], False, 1, True, who='admin')
issue(parts_case, parts[1], True, 0, True, who='doctor2')
reject_case = visit('最后部位拒收')
first, last = register(reject_case), register(reject_case, part=2)
receive(reject_case, first)
diagnose(reject_case, first)
sign(first, 'first-sign', 'doctor')
sign(first, 'second-sign', 'doctor2')
issue(reject_case, first, False, 1, True)
reject(reject_case, last['id'])
assert source(reject_case)['status'] == 'EXECUTED'
refund(reject_case, 5005)
all_rejected = visit('全拒可退')
for n in [1, 2]:
    rejected = register(all_rejected, part=n)
    reject(all_rejected, rejected['id'])
assert source(all_rejected)['status'] == 'CHARGED'
refund(all_rejected)
print('[4] Last issue/last rejection settles all parts, while all rejected parts permit genuine refund')

# Shared gate is left untouched; default fresh-CI warn is asserted explicitly.
assert report(result)['doubleSignGate'] == 'warn', 'This suite expects original CI default warn; no config changes'
for state in ['unpaid', 'refunded']:
    case = visit('手工实物' + state, charged=(state == 'refunded'), item=other_item)
    if state == 'refunded':
        refund(case)
    result = register(case, manual=True)
    denied(case, result, 'receive-check', 5208)
    diagnose(case, result, 'doctor2')
    answer = issue(case, result, False, 0, False)
    assert answer['doubleSignGate'] == 'warn' and answer['warnings']
    assert '缺初诊签名、复诊签名' in report(result)['process'][-1]['remark']
    assert source(case)['status'] == 'CREATED' and source(case)['chargeId'] is None
    supplement(case, result, 'doctor2')
print('[5] Never-paid/refunded manual physical specimens retain diagnosis, warn issue and supplemental reports without changing financial state')

# A private ward/bed prevents reliance on shared occupancy; every admission exits.
ward = expect(0, 'POST', '/system/depts', {
    'code': 'V117W' + suffix, 'name': 'V117模拟病区' + suffix, 'type': 'NURSING', 'sortNo': 9999})
expect(0, 'POST', '/inpatient/beds', {'wardId': ward['id'], 'count': 1})
bed = expect(0, 'GET', f"/inpatient/beds?wardId={ward['id']}")[0]
inp_patient = expect(0, 'POST', '/patients', {'name': 'V117住院来源' + suffix, 'sex': 'U'})
admission = expect(0, 'POST', '/inpatient/admissions', {
    'patientId': inp_patient['id'], 'deptId': clinic['id'], 'bedId': bed['id'],
    'doctorId': identities['doctor']['id'], 'deposit': 0, 'payMethod': 'CASH'})
inp_order = None
try:
    inp_orders = expect(0, 'POST', f"/inpatient/admissions/{admission['id']}/orders", {
        'lines': [{'orderType': 'EXAM', 'itemId': path_item['id'], 'qty': 1}]}, who='doctor')
    assert len(inp_orders) == 1
    inp_order = inp_orders[0]
    result = expect(0, 'POST', '/pathology/registry/specimens', {
        'inpOrderId': inp_order['id'], 'specimenType': 'ROUTINE', 'specimenDesc': 'V117住院模拟组织'}, who='technician')
    assert result['source'] == 'INP'
    write(result, 'receive-check', 'technician')
    write(result, 'diagnose', 'technician')
    sign(result, 'first-sign', 'doctor')
    sign(result, 'second-sign', 'doctor2')
    before = expect(0, 'GET', f"/inpatient/admissions/{admission['id']}/workspace")['orders']
    answer = write(result, 'issue', 'doctor2')
    after = expect(0, 'GET', f"/inpatient/admissions/{admission['id']}/workspace")['orders']
    assert before == after and answer['orderExecuted'] is False and answer['partsPending'] == 0
    assert next(row for row in after if row['id'] == inp_order['id'])['status'] == 'CREATED'
finally:
    if inp_order is not None:
        expect(0, 'PUT', f"/inpatient/orders/{inp_order['id']}/cancel", who='doctor')
    expect(0, 'POST', f"/inpatient/admissions/{admission['id']}/discharge", {})
assert expect(0, 'GET', f"/inpatient/beds?wardId={ward['id']}")[0]['status'] == 'FREE'
for who in ['admin', 'technician', 'doctor', 'doctor2', 'nurse', 'cashier', 'pharmacist']:
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
print('e2e-v117-pathology-receipt-report passed: eight real JWT boundaries, new/legacy receipts, exact refusal projections, '
      'diagnosis evidence, different-person signatures, multipart settlement, genuine all-rejected refund, '
      'pre/post-issue sequential supplements, unpaid/refunded physical rescue and unchanged INP execution; private bed released')


