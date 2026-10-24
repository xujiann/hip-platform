# -*- coding: utf-8 -*-
"""Public HTTP regression for idempotent RIS worklist auto-registration.

Only public APIs create sources and clinical reports; no SQL or shared config is
changed. Client HTTP intervals demonstrate overlapping requests; deterministic
PostgreSQL unique-conflict waits and full-column evidence are separate acceptance.
"""
import json
import time
import urllib.error
from decimal import Decimal
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from urllib.parse import urlencode

from e2elib import call, login, provision_user, today_bj

REPORT_KEYS = {'id', 'item_name', 'modality', 'impression', 'verified_at'}


class Fixture:
    def __init__(self, prefix='v123'):
        self.prefix, self.suffix = prefix, str(time.time_ns())[-11:]
        self.users = {'admin': prefix + '_admin_' + self.suffix}
        self.tokens = {'admin': provision_user(login(), self.users['admin'], 'ADMIN')}
        self.identities = {'admin': self.api('GET', '/auth/me')}
        for who, role in [('tech', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                          ('cashier', 'CASHIER'), ('pharmacist', 'PHARMACIST'),
                          ('interface', 'INTERFACE'), ('disabled', 'TECHNICIAN')]:
            username = prefix + '_' + who + '_' + self.suffix
            self.users[who] = username
            self.tokens[who] = provision_user(self.tokens['admin'], username,
                                             'TECHNICIAN' if role == 'INTERFACE' else role)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
            assert not self.identities[who].get('mustChangePassword', False)
            if role == 'INTERFACE':
                updated = self.api('PUT', f"/system/users/{self.identities[who]['id']}", {
                    'username': username, 'realName': username, 'roleCodes': ['INTERFACE']})
                assert updated['roleCodes'] == ['INTERFACE'] and updated['enabled']
                self.tokens[who] = login(username, 'Work@1234')
                # INTERFACE deliberately cannot consume staff /auth/me. Keep
                # the authoritative admin update DTO as its role evidence;
                # the renewed machine JWT is used for wrong-domain assertions.
                self.identities[who] = updated
        self.department = self.api('POST', '/system/depts', {
            'code': 'V123D' + self.suffix, 'name': 'V123模拟互认门诊' + self.suffix,
            'type': 'CLINICAL', 'sortNo': 9999})
        self.exam_department = self.api('POST', '/system/depts', {
            'code': 'V123E' + self.suffix, 'name': 'V123模拟互认影像' + self.suffix,
            'type': 'MEDTECH', 'sortNo': 9999})
        for who, role, dept in [('tech', 'TECHNICIAN', self.exam_department),
                                ('nurse', 'NURSE', self.exam_department),
                                ('doctor', 'DOCTOR_OUTP', self.department)]:
            person = self.identities[who]
            self.api('PUT', f"/system/users/{person['id']}", {
                'username': self.users[who], 'realName': self.users[who],
                'deptId': dept['id'], 'roleCodes': [role]})
            self.identities[who] = self.api('GET', '/auth/me', who=who)
            assert self.identities[who]['deptId'] == dept['id']
        self.schedule = self.api('POST', '/outpatient/schedules', {
            'deptId': self.department['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 30})
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'V123US{self.suffix},V123模拟超声{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n' +
            f'V123CT{self.suffix},V123模拟CT{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n')
        assert imported['imported'] == 2 and imported['errorCount'] == 0
        items = self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
        self.items = [next(x for x in items if x['code'] == 'V123' + kind + self.suffix) for kind in ['US', 'CT']]
        self.visits, self.cases = {}, {}
        self.owner = self.visit('本人')
        self.other = self.visit('另一患者')

    def api(self, method, path, body=None, who='admin', code=0, text=None):
        try:
            answer = call(method, path, body, self.tokens.get(who), text=text)
        except urllib.error.HTTPError as error:
            assert (code, error.code) in [(1005, 403), (1000, 401)], (method, path, code, error.code)
            answer = json.load(error)
        assert answer['code'] == code, (method, path, code, answer.get('code'), answer.get('message'))
        if code:
            assert answer.get('data') is None, 'Refusal leaked a clinical payload'
        return answer.get('data')

    def visit(self, label):
        patient = self.api('POST', '/patients', {
            'name': 'V123模拟' + label + self.suffix, 'sex': 'U', 'phone': '13900001230'})
        registration = self.api('POST', '/outpatient/registrations', {
            'patientId': patient['id'], 'scheduleId': self.schedule['id']}, who='cashier')
        self.api('POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
        visit = {'patient': patient, 'registration': registration}
        self.visits[label] = visit
        return visit

    def source(self, case):
        ws = self.api('GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")
        return next(x for x in ws['orders'] if x['id'] == case['order']['id'])

    def order(self, label, visit=None, item=0, paid=True):
        visit = visit or self.owner
        selected = self.items[item]
        lines = self.api('POST', f"/outpatient/doctor/{visit['registration']['id']}/orders", {
            'lines': [{'orderType': 'EXAM', 'itemId': selected['id'], 'qty': 1,
                       'clinicalSummary': 'V123模拟临床摘要', 'examPurpose': 'V123模拟检查目的'}]}, who='doctor')
        assert len(lines) == 1 and lines[0]['status'] == 'CREATED'
        case = {**visit, 'order': lines[0], 'label': label, 'item': selected,
                'findings': 'V123模拟所见-' + label, 'impression': 'V123模拟结论-' + label}
        if paid:
            bill = self.api('POST', '/outpatient/charges/settle', {
                'registrationId': visit['registration']['id'], 'payMethod': 'CASH'}, who='cashier')
            assert bill['status'] == 'PAID' and Decimal(str(bill['totalAmount'])) == Decimal('1.00')
            assert bill['cashierId'] == self.identities['cashier']['id']
            case['charge'] = bill
            assert self.source(case)['status'] == 'CHARGED'
        self.cases[label] = case
        return case

    def register(self, case):
        rows = [x for x in self.api('GET', '/ris/worklist') if x['group_no'] == case['order']['groupNo']]
        assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED'
        case['exam_id'] = rows[0]['id']
        return case

    def exam(self, label, visit=None, item=0, verified=True):
        case = self.register(self.order(label, visit=visit, item=item))
        self.api('PUT', f"/ris/exams/{case['exam_id']}/report", {
            'findings': case['findings'], 'impression': case['impression']}, who='doctor')
        self.api('PUT', f"/ris/exams/{case['exam_id']}/verify", who='doctor', code=9947)
        if verified:
            self.api('PUT', f"/ris/exams/{case['exam_id']}/verify", who='tech')
            assert self.source(case)['status'] == 'EXECUTED'
            row = next(x for x in self.recent(case['patient']['id']) if x['id'] == case['exam_id'])
            assert row == {'id': case['exam_id'], 'item_name': case['item']['name'],
                           'modality': 'US' if item == 0 else 'GENERAL',
                           'impression': case['impression'], 'verified_at': row['verified_at']}
            assert row['verified_at']
            case['verified_at'] = row['verified_at']
        return case

    def recent(self, patient=None, item_name=None, who='admin', code=0):
        params = {'patientId': patient or self.owner['patient']['id']}
        if item_name is not None:
            params['itemName'] = item_name
        rows = self.api('GET', '/ris/recent-exams?' + urlencode(params), who=who, code=code)
        if not code:
            assert isinstance(rows, list) and all(set(x) == REPORT_KEYS for x in rows)
            assert len(rows) <= 20 and [x['verified_at'] for x in rows] == sorted([x['verified_at'] for x in rows], reverse=True)
        return rows

    def public_snapshot(self):
        regs = {v['registration']['id'] for v in self.visits.values()}
        groups = {c['order']['groupNo'] for c in self.cases.values()}
        return {'workspaces': {str(x): self.api('GET', f'/outpatient/doctor/{x}/workspace') for x in regs},
                'charges': [x for x in self.api('GET', '/outpatient/charges/recent', who='cashier') if x['registrationId'] in regs],
                'queue': [x for x in self.api('GET', '/ris/worklist') if x['group_no'] in groups],
                'execution': {str(x): self.api('GET', f'/outpatient/exec/reports?registrationId={x}') for x in regs}}


QUEUE_KEYS = {'id', 'status', 'modality', 'findings', 'impression', 'item_name', 'group_no',
              'order_status', 'clinical_summary', 'exam_purpose', 'notice', 'urgent', 'patient_name', 'diagnosis'}


def queue(f, who='admin', modality=None, code=0):
    path = '/ris/worklist' + ('' if modality is None else '?' + urlencode({'modality': modality}))
    rows = f.api('GET', path, who=who, code=code)
    if code:
        return rows
    groups = {c['order']['groupNo'] for c in f.cases.values()}
    own = [x for x in rows if x['group_no'] in groups]
    assert all(set(x) == QUEUE_KEYS for x in own)
    assert [x['id'] for x in own] == sorted(x['id'] for x in own)
    assert len({x['group_no'] for x in own}) == len(own), 'Duplicate selected-order queue entry'
    return own


def financial_snapshot(f):
    regs = {v['registration']['id'] for v in f.visits.values()}
    return {'workspaces': {str(x): f.api('GET', f'/outpatient/doctor/{x}/workspace') for x in regs},
            'charges': [x for x in f.api('GET', '/outpatient/charges/recent', who='cashier') if x['registrationId'] in regs],
            'execution': {str(x): f.api('GET', f'/outpatient/exec/reports?registrationId={x}') for x in regs}}


def concurrent_queue(f):
    actors = ['admin', 'tech', 'doctor', 'nurse'] * 2
    barrier = Barrier(len(actors))
    def request(who):
        barrier.wait(timeout=20)
        begin = time.monotonic()
        rows = queue(f, who=who)
        return begin, time.monotonic(), rows
    with ThreadPoolExecutor(max_workers=len(actors)) as pool:
        results = list(pool.map(request, actors))
    events = sorted([(a, 1) for a, _, _ in results] + [(b, -1) for _, b, _ in results])
    active = peak = 0
    for _, delta in events:
        active += delta; peak = max(peak, active)
    assert peak >= 2, 'HTTP call lifetimes did not overlap; do not claim concurrent acceptance'
    assert all(x[2] == results[0][2] for x in results)
    print('Concurrent client HTTP calls:', len(results), 'peak overlapping call lifetimes:', peak,
          '(not proof of a database unique-conflict wait)')
    return results[0][2]


def main():
    f = Fixture()
    original = f.exam('原已审核报告')
    draft = f.exam('原未审核草稿', verified=False)
    arrived = f.register(f.order('原已到检申请'))
    f.api('PUT', f"/ris/exams/{arrived['exam_id']}/arrive", who='nurse')
    existing = queue(f)
    assert {x['status'] for x in existing} == {'REPORTED', 'ARRIVED'}
    assert all(x['id'] != original['exam_id'] for x in existing)
    baseline = financial_snapshot(f)
    imported = f.api('POST', '/masterdata/charge-items/import', text=
        'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
        ''.join(f'V123M{i}{f.suffix},{name}{f.suffix},EXAM,次,1.00,,,{f.exam_department["code"]}\n'
                for i, name in enumerate(['V123模拟心电镜超声', 'V123模拟内镜超声', 'V123模拟彩超', 'V123模拟CT'])))
    assert imported['imported'] == 4 and imported['errorCount'] == 0
    all_items = f.api('GET', '/masterdata/charge-items?keyword=' + f.suffix)
    for i in range(4):
        f.items.append(next(x for x in all_items if x['code'] == f'V123M{i}{f.suffix}'))
    fresh = [f.order('并发登记' + str(i), item=i + 2) for i in range(4)]
    before = financial_snapshot(f)
    rows = concurrent_queue(f)
    assert financial_snapshot(f) == before
    assert [x for x in rows if x['id'] in {r['id'] for r in existing}] == existing
    for case, modality in zip(fresh, ['ECG', 'ENDO', 'US', 'GENERAL']):
        matches = [x for x in rows if x['group_no'] == case['order']['groupNo']]
        assert len(matches) == 1
        row = matches[0]; case['exam_id'] = row['id']
        assert row['status'] == 'REGISTERED' and row['order_status'] == 'CHARGED' and row['modality'] == modality
        assert row['findings'] is None and row['impression'] is None and row['diagnosis'] is None
        assert row['patient_name'] == case['patient']['name'] and row['item_name'] == case['item']['name']
        assert row['clinical_summary'] == 'V123模拟临床摘要' and row['exam_purpose'] == 'V123模拟检查目的'
        assert row['notice'] is None and row['urgent'] is False
    assert concurrent_queue(f) == rows and queue(f) == rows
    assert financial_snapshot(f) == before
    print('[1] Eight overlapping real-JWT readers share one queue row per new source; existing draft/arrived and source/fee facts preserved')

    filtered = f.order('空模态仍登记全部来源')
    before = financial_snapshot(f)
    assert queue(f, modality='') == []
    all_rows = queue(f)
    made = [x for x in all_rows if x['group_no'] == filtered['order']['groupNo']]
    assert len(made) == 1 and made[0]['modality'] == 'US' and made[0]['status'] == 'REGISTERED'
    filtered['exam_id'] = made[0]['id']
    for modality in ['US', 'ECG', 'ENDO', 'GENERAL']:
        assert queue(f, modality=modality) == [x for x in all_rows if x['modality'] == modality]
    assert queue(f, modality='us') == queue(f, modality='UNKNOWN') == []
    assert financial_snapshot(f) == before
    unpaid = f.order('最后造未收费不登记', paid=False)
    assert f.source(unpaid)['status'] == 'CREATED'
    baseline = financial_snapshot(f); stable = queue(f)
    assert all(x['group_no'] != unpaid['order']['groupNo'] for x in stable)
    portal = f.api('POST', '/portal/login', {'patientNo': f.owner['patient']['patientNo'], 'phone': '13900001230'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    for who in ['cashier', 'pharmacist', 'interface', 'portal', 'disabled', None]:
        queue(f, who=who, code=1000 if who in ['disabled', None] else 1005)
    assert queue(f) == stable and financial_snapshot(f) == baseline
    assert f.recent() == [{'id': original['exam_id'], 'item_name': original['item']['name'], 'modality': 'US',
                           'impression': original['impression'], 'verified_at': original['verified_at']}]
    print('[2] Original fourteen fields, mapping precedence and exact/blank modality semantics retained; wrong domains refused, unpaid remains unregistered and verified report intact')
    print('e2e-v123-ris-worklist-register passed: real four-duty overlapping HTTP readers, selected-order unique queue results, original source/fee and existing-report projections, and modality/filter compatibility. Deterministic PostgreSQL conflict waits, rollback and all-column evidence are separate acceptance; no server lock race is inferred from client scheduling.')


if __name__ == '__main__':
    main()
