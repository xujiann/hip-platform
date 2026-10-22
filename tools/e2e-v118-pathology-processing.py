# -*- coding: utf-8 -*-
"""Real JWT pathology grossing, physical processing and technical-order regression.

Only public HTTP endpoints create clinical facts. Comparisons below cover the
publicly exposed stored projections; Java tests separately prove PostgreSQL lock
waits, full-column rollback and exact concurrent winners. This script makes no
claim that sequential HTTP calls establish a database race.
"""
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, provision_user, q, today_bj


suffix = str(time.time_ns())[-11:]
tokens = {'admin': provision_user(login(), 'v118admin' + suffix, 'ADMIN')}
identities = {}


def expect(code, method, path, body=None, who='admin', text=None):
    try:
        response = call(method, path, body, tokens.get(who), text=text)
    except urllib.error.HTTPError as error:
        assert (code, error.code) in [(1005, 403), (1000, 401)], (who, path, code, error.code)
        response = json.load(error)
    assert response['code'] == code, (who, method, path, response)
    return response.get('data')


for who, role in [('tech', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                  ('cashier', 'CASHIER'), ('pharmacist', 'PHARMACIST'),
                  ('machine', 'INTERFACE'), ('disabled', 'TECHNICIAN')]:
    username = 'v118' + who + suffix
    tokens[who] = provision_user(tokens['admin'], username,
                                 'TECHNICIAN' if role == 'INTERFACE' else role)
    identities[who] = expect(0, 'GET', '/auth/me', who=who)
    if role == 'INTERFACE':
        changed = expect(0, 'PUT', f"/system/users/{identities[who]['id']}", {
            'username': username, 'realName': username, 'roleCodes': ['INTERFACE']})
        assert changed['enabled'] and changed['roleCodes'] == ['INTERFACE']
        tokens[who] = login(username, 'Work@1234')
identities['admin'] = expect(0, 'GET', '/auth/me')
assert len({person['id'] for person in identities.values()}) == len(tokens)

department = expect(0, 'POST', '/system/depts', {
    'code': 'V118D' + suffix, 'name': 'V118模拟病理' + suffix,
    'type': 'CLINICAL', 'sortNo': 9999})
schedule = expect(0, 'POST', '/outpatient/schedules', {
    'deptId': department['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 20})
item_code, other_code = 'V118PATH' + suffix, 'V118OTHER' + suffix
imported = expect(0, 'POST', '/masterdata/charge-items/import', text=
                  'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
                  f'{item_code},V118模拟病理{suffix},EXAM,次,1.00,,,{department["code"]}\n' +
                  f'{other_code},V118普通模拟检查{suffix},EXAM,次,1.00,,,{department["code"]}\n')
assert imported['imported'] == 2 and imported['errorCount'] == 0
items = expect(0, 'GET', '/masterdata/charge-items?keyword=' + suffix)
path_item = next(row for row in items if row['code'] == item_code)
other_item = next(row for row in items if row['code'] == other_code)


def source(case):
    if 'admission' in case:
        rows = expect(0, 'GET', f"/inpatient/admissions/{case['admission']['id']}/workspace")['orders']
    else:
        rows = expect(0, 'GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")['orders']
    return next(row for row in rows if row['id'] == case['order']['id'])


def grossing(case):
    return expect(0, 'GET', f"/pathology/process/grossing/{case['specimen']['id']}")


def tech_rows(case):
    answer = expect(0, 'GET', f"/pathology/report/tech-orders?specimenId={case['specimen']['id']}")
    assert answer['truncated'] is False
    return answer['items']


def slide_rows(case):
    answer = expect(0, 'GET', f"/pathology/process/slides/search?specimenId={case['specimen']['id']}")
    assert answer['truncated'] is False
    return sorted(answer['items'], key=lambda row: row['id'])


def snapshot(case):
    sid = case['specimen']['id']
    report = expect(0, 'GET', f'/pathology/report/{sid}/reports')
    charges = [] if 'admission' in case else [row for row in expect(
        0, 'GET', '/outpatient/charges/recent', who='cashier')
        if row['registrationId'] == case['registration']['id']]
    return {'source': source(case), 'charges': charges, 'gross': grossing(case), 'slides': slide_rows(case),
            'tech': [{k: v for k, v in row.items() if k != 'hours_since_ordered'} for row in tech_rows(case)],
            'reports': {key: report[key] for key in ['specimen', 'primary', 'supplements', 'process']},
            'nodes': expect(0, 'GET', f'/pathology/process/trail/{sid}')['nodes']}


def register(case, manual=False):
    body = {'specimenType': 'ROUTINE', 'specimenDesc': 'V118模拟送检组织',
            'samplingSite': 'V118模拟部位'}
    body['inpOrderId' if 'admission' in case else 'orderId'] = case['order']['id']
    if manual:
        body.update({'applyDept': 'V118模拟临床', 'applyDoctor': 'V118模拟送检人',
                     'note': 'V118实物模拟救济'})
    result = expect(0, 'POST', '/pathology/registry/specimens' + ('/manual' if manual else ''), body, 'tech')
    case['specimen'] = result
    if not manual:
        expect(0, 'PUT', f"/pathology/registry/specimens/{result['id']}/receive-check", {}, 'tech')
    return case


def visit(label, paid=True, refunded=False, manual=False):
    patient = expect(0, 'POST', '/patients', {'name': 'V118' + label + suffix, 'sex': 'U'})
    registration = expect(0, 'POST', '/outpatient/registrations', {
        'patientId': patient['id'], 'scheduleId': schedule['id']}, who='cashier')
    expect(0, 'POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
    orders = expect(0, 'POST', f"/outpatient/doctor/{registration['id']}/orders", {'lines': [{
        'orderType': 'EXAM', 'itemId': (other_item if manual else path_item)['id'], 'qty': 1,
        'specimenType': '模拟组织', 'samplingSite': '模拟部位'}]}, 'doctor')
    assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
    case = {'patient': patient, 'registration': registration, 'order': orders[0]}
    if paid:
        case['charge'] = expect(0, 'POST', '/outpatient/charges/settle', {
            'registrationId': registration['id'], 'payMethod': 'CASH'}, 'cashier')
        assert Decimal(str(case['charge']['totalAmount'])) == Decimal('1.00')
        assert source(case)['status'] == 'CHARGED'
    if refunded:
        answer = expect(0, 'POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier')
        assert answer['status'] == 'REFUNDED' and source(case)['status'] == 'CREATED'
    return register(case, manual)


def write(case, action, who='tech', code=0, body=None):
    sid = case['specimen']['id']
    bid = case.get('block', {}).get('id', 9223372036854770000)
    slid = case.get('slide', {}).get('id', 9223372036854770000)
    tid = case.get('tech', {}).get('id', 9223372036854770000)
    routes = {
        'gross': ('POST', '/pathology/process/grossing', {
            'specimenId': sid, 'grossText': 'V118模拟首次大体所见',
            'blocks': [{'tissueDesc': 'V118模拟第一块'}, {'tissueDesc': 'V118模拟第二块'}]}),
        'revise': ('PUT', f'/pathology/process/grossing/{sid}/fields', {'grossText': 'V118模拟修订大体所见'}),
        'dehydrate': ('PUT', '/pathology/process/blocks/dehydrate-batch', {
            'batchNo': 'V118-B' + suffix, 'blockIds': [bid], 'remark': 'V118模拟分篮'}),
        'embed': ('PUT', f'/pathology/process/blocks/{bid}/embed', None),
        'slides': ('POST', '/pathology/process/slides', {'blockId': bid, 'count': 3, 'stainType': 'HE'}),
        'stain': ('PUT', f'/pathology/process/slides/{slid}/stain', {'quality': 'GOOD'}),
        'batch': ('PUT', '/pathology/process/slides/batch-complete', {'slideIds': [slid], 'quality': 'GOOD'}),
        'new-tech': ('POST', '/pathology/report/tech-orders', {
            'specimenId': sid, 'blockId': bid, 'techType': 'IHC', 'techItem': 'CK7', 'reason': 'V118模拟加做依据'}),
        'done': ('PUT', f'/pathology/report/tech-orders/{tid}/done', None),
        'cancel': ('PUT', f'/pathology/report/tech-orders/{tid}/cancel', {'reason': 'V118模拟取消依据'}),
    }
    method, route, default = routes[action]
    return expect(code, method, route, default if body is None else body, who)


def denied(case, action, code, who='tech', body=None):
    before = snapshot(case)
    write(case, action, who, code, body)
    assert snapshot(case) == before, ('Refusal changed stored public projections', action, code, who)


def node(case, name, who, contains=None):
    nodes = expect(0, 'GET', f"/pathology/process/trail/{case['specimen']['id']}")['nodes']
    found = [row for row in nodes if row['node'] == name and
             (contains is None or contains in (row['remark'] or ''))]
    assert found and found[-1]['operator_id'] == identities[who]['id'] and found[-1]['occurred_at'], (name, found)
    return found[-1]


actions = ['gross', 'revise', 'dehydrate', 'embed', 'slides', 'stain', 'batch', 'new-tech', 'done', 'cancel']
security = visit('岗位边界')
security['block'] = write(security, 'gross')['blocks'][0]
security['slide'] = write(security, 'slides')['slides'][0]
security['tech'] = write(security, 'new-tech')
for who in ['nurse', 'cashier', 'pharmacist', 'machine', 'anonymous']:
    for action in actions:
        denied(security, action, 1000 if who == 'anonymous' else 1005, who)
disabled_id = identities['disabled']['id']
expect(0, 'PUT', f'/system/users/{disabled_id}/enabled?enabled=false')
page = 0
while True:
    records = expect(0, 'GET', f'/system/users?page={page}&size=100')['records']
    disabled = next((person for person in records if person['id'] == disabled_id), None)
    if disabled is not None:
        break
    assert records, 'Private disabled employee missing from paginated public list'
    page += 1
assert disabled['enabled'] is False, 'Dedicated enabled endpoint must really change employee state'
for action in actions:
    denied(security, action, 1000, 'disabled')
print('[1] Ten real JWT boundaries: wrong duty, pure machine, anonymous and disabled employees; zero projected writes')


def complete_chain(case, who):
    original_source = source(case)
    original_charges = snapshot(case)['charges']
    answer = write(case, 'gross', who)
    assert answer['totalBlockCount'] == 2 and answer['grossRevisionSeq'] == 1
    assert [row['block_no'] for row in answer['blocks']] == [1, 2]
    case['block'] = answer['blocks'][0]
    node(case, 'GROSSING', who)
    assert all(row['created_by'] == identities[who]['id'] for row in grossing(case)['blocks'])
    denied(case, 'gross', 5223, who)
    revised = write(case, 'revise', who)
    assert revised['revisionSeq'] == 2 and revised['grossFinding'] == 'V118模拟修订大体所见'
    assert grossing(case)['revisions'][-1]['changedBy'] == identities[who]['id']
    dehydrate = write(case, 'dehydrate', who)
    assert dehydrate['blockCount'] == 1 and dehydrate['specimenCount'] == 1
    node(case, 'DEHYDRATE', who)
    embedded = write(case, 'embed', who)
    assert embedded['embeddedBy'] == identities[who]['id'] and embedded['warnings'] == []
    assert node(case, 'EMBED', who)['occurred_at'] == embedded['embeddedAt']
    denied(case, 'embed', 5241, who)
    denied(case, 'dehydrate', 5241, who)
    tech = write(case, 'new-tech', who)
    case['tech'] = tech
    node(case, 'TECH_ORDER', who, f"#{tech['id']} ")
    slides = write(case, 'slides', who, body={
        'blockId': case['block']['id'], 'count': 3, 'stainType': 'IHC', 'stainItem': 'CK7',
        'techOrderId': tech['id'], 'remark': 'V118模拟原医嘱制片'})['slides']
    assert [row['slide_no'] for row in slides] == [1, 2, 3]
    assert all(row['tech_order_id'] == tech['id'] for row in slides)
    node(case, 'SECTION', who)
    case['slide'] = slides[0]
    stained = write(case, 'stain', who)
    assert stained['stainedBy'] == identities[who]['id'] and stained['stain_item'] == 'CK7'
    assert node(case, 'STAIN', who)['occurred_at'] == stained['stained_at']
    denied(case, 'stain', 5245, who)
    denied(case, 'batch', 5245, who, {'slideIds': [slides[0]['id'], slides[1]['id']], 'quality': 'GOOD'})
    batch = write(case, 'batch', who, body={'slideIds': [row['id'] for row in slides[1:]], 'quality': 'GOOD'})
    assert batch['requested'] == batch['completed'] == 2 and batch['specimenCount'] == 1
    assert all(row['stained_at'] and row['stained_by'] == identities[who]['id'] for row in slide_rows(case))
    done = write(case, 'done', who)
    assert done['techDoneGate'] == 'warn', 'This public suite requires the restored fresh-CI default'
    assert done['slideCount'] == done['stainedCount'] == 3 and done['sampledBlockCount'] == 0
    assert done['stainedComplete'] and done['doneGap'] is None and done['warnings'] == []
    assert node(case, 'TECH_DONE', who, f"#{tech['id']} ")['remark'] == done['doneRemark']
    row = next(row for row in tech_rows(case) if row['id'] == tech['id'])
    assert row['done_by'] == identities[who]['id'] and row['done_at'] == done['done_at']
    denied(case, 'done', 5268, who)
    denied(case, 'cancel', 5268, who)
    denied(case, 'slides', 5272, who, {'blockId': case['block']['id'], 'count': 1,
           'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': tech['id']})
    case['tech'] = write(case, 'new-tech', who)
    denied(case, 'cancel', 5271, who, {'reason': '  '})
    cancelled = write(case, 'cancel', who)
    assert cancelled['cancelled_by'] == identities[who]['id'] and cancelled['cancelled_at']
    assert cancelled['cancel_reason'] == 'V118模拟取消依据' and cancelled['reason'] == 'V118模拟加做依据'
    node(case, 'TECH_CANCEL', who, f"#{case['tech']['id']} ")
    denied(case, 'done', 5268, who)
    denied(case, 'slides', 5272, who, {'blockId': case['block']['id'], 'count': 1,
           'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': case['tech']['id']})
    assert source(case) == original_source, 'Physical processing must not execute or alter source finances'
    assert snapshot(case)['charges'] == original_charges
    return case


for actor in ['admin', 'tech', 'doctor']:
    complete_chain(visit('合法完整链' + actor), actor)
print('[2] ADMIN/TECHNICIAN/DOCTOR_OUTP retain all ten writes, exact actor/time, sequential numbers and refusal projections')

# Stale UI requests use the same public endpoints here; the browser separately
# holds real drafts open. Reject after creation, then prove every producer stops.
rejected = visit('拒收旧草稿')
rejected['block'] = write(rejected, 'gross')['blocks'][0]
rejected['slide'] = write(rejected, 'slides')['slides'][0]
rejected['tech'] = write(rejected, 'new-tech')
expect(0, 'PUT', f"/pathology/registry/specimens/{rejected['specimen']['id']}/reject", {'reason': 'V118模拟固定不合格'}, 'doctor')
for action, code in [('gross', 5221), ('revise', 5221), ('dehydrate', 5241), ('embed', 5241),
                     ('slides', 5241), ('stain', 5245), ('batch', 5245), ('new-tech', 5261), ('done', 5261)]:
    denied(rejected, action, code)
# Cancellation is intentional rejected-specimen housekeeping, without new work.
original = snapshot(rejected)
cancelled = write(rejected, 'cancel', 'doctor')
assert cancelled['reason'] == 'V118模拟加做依据' and cancelled['cancelled_by'] == identities['doctor']['id']
assert source(rejected) == original['source'] and grossing(rejected) == original['gross']
assert slide_rows(rejected) == original['slides']
print('[3] Rejected objects stop nine producers; pending technical orders retain cancellation housekeeping and original reason')

gate_key = 'emr.gate.pathology.techdone'
# All three preceding real completions asserted the fresh-CI default. Config
# has no private single-key GET endpoint; restoration is proved by a new real
# completion below rather than guessing such a read contract.
gate_original = 'warn'
gate_case = visit('完成证据')
gate_case['block'] = write(gate_case, 'gross')['blocks'][0]


def gate(value):
    expect(0, 'PUT', '/config/' + gate_key + '?value=' + q(value))


def new_resample(case):
    return write(case, 'new-tech', body={'specimenId': case['specimen']['id'], 'techType': 'RESAMPLE',
                                       'reason': 'V118模拟需要补取材'})


try:
    for tier in ['off', 'warn', 'block']:
        gate(tier)
        gate_case['tech'] = write(gate_case, 'new-tech')
        if tier == 'block':
            denied(gate_case, 'done', 5273)
            slides = write(gate_case, 'slides', body={'blockId': gate_case['block']['id'], 'count': 2,
                'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': gate_case['tech']['id']})['slides']
            gate_case['slide'] = slides[0]
            write(gate_case, 'stain')
            denied(gate_case, 'done', 5273)
            gate_case['slide'] = slides[1]
            write(gate_case, 'stain')
        done = write(gate_case, 'done')
        assert done['techDoneGate'] == tier and done['sampledBlockCount'] == 0
        assert done['slideCount'] == done['stainedCount'] == (2 if tier == 'block' else 0)
        assert bool(done['warnings']) is (tier == 'warn')
        assert (done['doneGap'] is None) is (tier == 'block')
        assert node(gate_case, 'TECH_DONE', 'tech', f"#{gate_case['tech']['id']} ")['remark'] == done['doneRemark']
    # SAMPLED is real evidence even with no slides. Both append operations remain
    # legal and allocate max+1 under the same specimen; a completed order cannot
    # acquire new blocks, while an unrelated append remains compatible.
    gate_case['tech'] = new_resample(gate_case)
    initial_blocks = grossing(gate_case)['blockCount']
    for offset in [1, 2]:
        added = write(gate_case, 'gross', body={'specimenId': gate_case['specimen']['id'], 'append': True,
            'techOrderId': gate_case['tech']['id'], 'blocks': [{'tissueDesc': 'V118模拟补取材' + str(offset)}]})
        assert added['blocks'][0]['block_no'] == initial_blocks + offset
        assert added['blocks'][0]['tech_order_id'] == gate_case['tech']['id']
    sampled = next(row for row in tech_rows(gate_case) if row['id'] == gate_case['tech']['id'])
    assert sampled['progress'] == 'SAMPLED' and sampled['sampled_block_count'] == 2
    done = write(gate_case, 'done')
    assert done['techDoneGate'] == 'block' and done['sampledBlockCount'] == 2
    assert done['slideCount'] == done['stainedCount'] == 0 and done['stainedComplete'] is False
    assert done['doneGap'] is None and done['warnings'] == []
    assert node(gate_case, 'TECH_DONE', 'tech', f"#{gate_case['tech']['id']} ")['remark'] == done['doneRemark']
    denied(gate_case, 'gross', 5275, body={'specimenId': gate_case['specimen']['id'], 'append': True,
        'techOrderId': gate_case['tech']['id'], 'blocks': [{'tissueDesc': 'V118不应写入'}]})
    gate_case['tech'] = new_resample(gate_case)
    write(gate_case, 'cancel')
    denied(gate_case, 'gross', 5275, body={'specimenId': gate_case['specimen']['id'], 'append': True,
        'techOrderId': gate_case['tech']['id'], 'blocks': [{'tissueDesc': 'V118不应写入'}]})
finally:
    gate(gate_original)
# The next actual completion, rather than a permissive read assertion, proves
# restoration really invalidated the application cache.
gate_case['tech'] = write(gate_case, 'new-tech')
restored = write(gate_case, 'done')
assert restored['techDoneGate'] == 'warn' and len(restored['warnings']) == 1
print('[4] off/warn/block use actual slide/stain/block evidence; real SAMPLED completion and restored gate pass')

# Independent-source binding errors preserve all existing facts.
other = complete_chain(visit('异标本'), 'tech')
denied(gate_case, 'new-tech', 5269, body={'specimenId': gate_case['specimen']['id'],
       'blockId': other['block']['id'], 'techType': 'IHC', 'techItem': 'CK7'})
gate_case['tech'] = write(gate_case, 'new-tech')
denied(gate_case, 'slides', 5272, body={'blockId': other['block']['id'], 'count': 1,
       'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': gate_case['tech']['id']})
denied(gate_case, 'slides', 5274, body={'blockId': gate_case['block']['id'], 'count': 1,
       'stainType': 'HE', 'techOrderId': gate_case['tech']['id']})
for paid, refunded, label in [(False, False, '从未付款'), (True, True, '已退款')]:
    physical = visit(label, paid=paid, refunded=refunded, manual=True)
    complete_chain(physical, 'tech')
    assert source(physical)['status'] == 'CREATED' and source(physical)['chargeId'] is None
print('[5] Cross-specimen/tech-item guards and never-paid/refunded manual physical processing preserve source finances')

# Existing post-diagnosis/post-issue append is a physical compatibility rule:
# the gross description can grow; diagnosis, signatures and issue facts do not.
issued = visit('签发后物理补取材')
issued['block'] = write(issued, 'gross')['blocks'][0]
expect(0, 'PUT', f"/pathology/specimens/{issued['specimen']['barcode']}/diagnose", {
    'diagnosis': 'V118模拟病理诊断', 'microFinding': 'V118模拟镜下所见'}, 'doctor')
expect(0, 'PUT', f"/pathology/report/{issued['specimen']['id']}/issue", who='doctor')
before = snapshot(issued)
added = write(issued, 'gross', body={'specimenId': issued['specimen']['id'], 'append': True,
    'grossText': 'V118签发后实物补取材追加', 'blocks': [{'tissueDesc': 'V118签发后补块'}]})
after = snapshot(issued)
assert added['totalBlockCount'] == 3 and after['source'] == before['source']
assert after['source']['status'] == 'EXECUTED'
for key in ['diagnosis', 'microFinding', 'diagnosedAt', 'reportIssuedAt']:
    assert after['reports']['primary'][key] == before['reports']['primary'][key]
assert after['reports']['primary']['grossFinding'].startswith(before['reports']['primary']['grossFinding'])
assert 'V118签发后实物补取材追加' in after['reports']['primary']['grossFinding']
denied(issued, 'revise', 5221)
print('[6] Existing issued-specimen physical append remains legal; diagnosis/issue facts and source stay intact')

ward = expect(0, 'POST', '/system/depts', {'code': 'V118W' + suffix,
    'name': 'V118模拟病区' + suffix, 'type': 'NURSING', 'sortNo': 9999})
expect(0, 'POST', '/inpatient/beds', {'wardId': ward['id'], 'count': 1})
bed = expect(0, 'GET', f"/inpatient/beds?wardId={ward['id']}")[0]
patient = expect(0, 'POST', '/patients', {'name': 'V118住院实物' + suffix, 'sex': 'U'})
admission = expect(0, 'POST', '/inpatient/admissions', {'patientId': patient['id'],
    'deptId': department['id'], 'bedId': bed['id'], 'doctorId': identities['doctor']['id'],
    'deposit': 0, 'payMethod': 'CASH'})
inp_order = None
try:
    orders = expect(0, 'POST', f"/inpatient/admissions/{admission['id']}/orders", {
        'lines': [{'orderType': 'EXAM', 'itemId': path_item['id'], 'qty': 1}]}, 'doctor')
    assert len(orders) == 1
    inp_order = orders[0]
    inpatient = register({'patient': patient, 'admission': admission, 'order': inp_order})
    assert inpatient['specimen']['source'] == 'INP'
    complete_chain(inpatient, 'doctor')
    assert source(inpatient)['status'] == 'CREATED'
finally:
    if inp_order is not None:
        expect(0, 'PUT', f"/inpatient/orders/{inp_order['id']}/cancel", who='doctor')
    expect(0, 'POST', f"/inpatient/admissions/{admission['id']}/discharge", {})
assert expect(0, 'GET', f"/inpatient/beds?wardId={ward['id']}")[0]['status'] == 'FREE'
for who in ['admin', 'tech', 'doctor', 'nurse', 'cashier', 'pharmacist']:
    expect(0, 'GET', '/auth/me', who=who)
    expect(0, 'GET', '/stats/overview', who=who)
expect(1005, 'GET', '/auth/me', who='machine')
expect(1005, 'GET', '/stats/overview', who='machine')
print('e2e-v118-pathology-processing passed: ten real JWT boundaries, three clinical duties, '
      'exact refusal projections, physical processing and technical-order evidence, restored gate, '
      'unpaid/refunded/manual and INP compatibility, issued physical append; private bed released')
