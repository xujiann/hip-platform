# -*- coding: utf-8 -*-
"""Public JWT regression for RIS active-queue source eligibility.

Only public APIs create sources and perform real refund/recharge. Historical
wrong-type/status and deterministic PostgreSQL waits are separate acceptance.
No shared configuration or database mutation is used in this script.
"""
import json
import time
import urllib.error
from decimal import Decimal
from urllib.parse import urlencode

from e2elib import call, login, provision_user, today_bj

REPORT_KEYS = {'id', 'item_name', 'modality', 'impression', 'verified_at'}


class Fixture:
    def __init__(self, prefix='v124'):
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
            'code': 'V124D' + self.suffix, 'name': 'V124模拟互认门诊' + self.suffix,
            'type': 'CLINICAL', 'sortNo': 9999})
        self.exam_department = self.api('POST', '/system/depts', {
            'code': 'V124E' + self.suffix, 'name': 'V124模拟互认影像' + self.suffix,
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
            f'V124US{self.suffix},V124模拟超声{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n' +
            f'V124CT{self.suffix},V124模拟CT{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n')
        assert imported['imported'] == 2 and imported['errorCount'] == 0
        items = self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
        self.items = [next(x for x in items if x['code'] == 'V124' + kind + self.suffix) for kind in ['US', 'CT']]
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
            'name': 'V124模拟' + label + self.suffix, 'sex': 'U', 'phone': '13900001240'})
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
                       'clinicalSummary': 'V124模拟临床摘要', 'examPurpose': 'V124模拟检查目的'}]}, who='doctor')
        assert len(lines) == 1 and lines[0]['status'] == 'CREATED'
        case = {**visit, 'order': lines[0], 'label': label, 'item': selected,
                'findings': 'V124模拟所见-' + label, 'impression': 'V124模拟结论-' + label}
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


def main():
    f = Fixture()
    refunded = f.exam('真实退费原草稿', verified=False)
    charged = f.exam('另一患者合法已收费', visit=f.other, verified=False)
    executed = f.exam('合法通用执行原草稿', visit=f.visit('通用执行后待审核'), verified=False)
    f.api('POST', f"/outpatient/exec/{executed['order']['id']}", {'resultText': 'V124模拟通用执行记录'}, who='tech')
    assert f.source(executed)['status'] == 'EXECUTED'
    arrived = f.register(f.order('合法到检', visit=f.visit('到检患者')))
    f.api('PUT', f"/ris/exams/{arrived['exam_id']}/arrive", who='nurse')
    verified = f.exam('合法已审核', visit=f.visit('已审核患者'))
    # Keep the unpaid source on its own visit so the later recharge cannot pay it.
    unpaid = f.order('隔离从未收费', visit=f.visit('未收费患者'), paid=False)
    initial = queue(f)
    assert {x['id'] for x in initial} == {c['exam_id'] for c in [refunded, charged, executed, arrived]}
    assert all(x['id'] != verified['exam_id'] for x in initial)
    assert all(x['group_no'] != unpaid['order']['groupNo'] for x in initial)
    assert next(x for x in initial if x['id'] == executed['exam_id'])['order_status'] == 'EXECUTED'
    baseline = f.public_snapshot()
    for who in ['admin', 'tech', 'doctor', 'nurse']:
        assert queue(f, who=who) == initial
        for modality in ['US', 'GENERAL', 'UNKNOWN', 'us', '']:
            assert queue(f, who=who, modality=modality) == [x for x in initial if x['modality'] == modality]
    assert f.public_snapshot() == baseline
    print('[1] Four cross-department duties retain fourteen fields, exact/blank modality and real EXECUTED-but-unverified reports; verified/unpaid are absent')

    portal = f.api('POST', '/portal/login', {'patientNo': f.owner['patient']['patientNo'], 'phone': '13900001240'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    baseline = f.public_snapshot()
    for who in ['cashier', 'pharmacist', 'interface', 'portal', 'disabled', None]:
        queue(f, who=who, code=1000 if who in ['disabled', None] else 1005)
    assert f.public_snapshot() == baseline

    original_row = next(x for x in initial if x['id'] == refunded['exam_id'])
    returned = f.api('POST', f"/outpatient/charges/{refunded['charge']['id']}/refund", who='cashier')
    assert returned['status'] == 'REFUNDED' and returned['refundBy'] == f.identities['cashier']['id']
    assert f.source(refunded)['status'] == 'CREATED' and f.source(refunded)['chargeId'] is None
    after_refund = [x for x in initial if x['id'] != refunded['exam_id']]
    baseline = f.public_snapshot()
    for who in ['admin', 'tech', 'doctor', 'nurse']:
        assert queue(f, who=who) == after_refund
    for action, code, body in [('arrive', 9944, None), ('report', 9944, {'findings': '不得覆盖', 'impression': '不得覆盖'}),
                               ('verify', 9945, None), ('critical', 9945, {'note': '不得产生危急值'})]:
        f.api('PUT', f"/ris/exams/{refunded['exam_id']}/{action}", body, who='tech', code=code)
    assert f.public_snapshot() == baseline
    print('[2] Genuine refund removes the source for all four roles; original write refusals preserve billing and clinical projections')

    new_charge = f.api('POST', '/outpatient/charges/settle', {'registrationId': refunded['registration']['id'], 'payMethod': 'CASH'}, who='cashier')
    assert new_charge['id'] != refunded['charge']['id'] and new_charge['status'] == 'PAID'
    assert Decimal(str(new_charge['totalAmount'])) == Decimal('1.00') and new_charge['cashierId'] == f.identities['cashier']['id']
    assert f.source(refunded)['status'] == 'CHARGED' and f.source(refunded)['chargeId'] == new_charge['id']
    bills = f.api('GET', '/outpatient/charges/recent', who='cashier')
    assert next(x for x in bills if x['chargeId'] == refunded['charge']['id'])['status'] == 'REFUNDED'
    restored = queue(f)
    assert restored == initial and next(x for x in restored if x['id'] == refunded['exam_id']) == original_row
    baseline = f.public_snapshot()
    for case in [executed, verified]:
        f.api('POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier', code=5005)
    assert f.public_snapshot() == baseline and f.source(unpaid)['status'] == 'CREATED'
    print('[3] Genuine recharge restores the same original E/content; old bill stays REFUNDED and executed refunds remain 5005')
    print('e2e-v124-ris-worklist-source-read passed: real JWT four duties, active-source queue, generic execution, exact modality/keys, real refund and same-report recharge, original write boundaries and read-only public projections. Historical wrong-type/status, full database columns and deterministic PG waits are separate acceptance.')


if __name__ == '__main__':
    main()


