# -*- coding: utf-8 -*-
"""Public HTTP regression for RIS recent-report qualification and its old contract.

Only public clinical APIs create the reports below; no SQL or shared config is
changed. Historical VERIFIED rows with a changed source type/status and exact
window/limit cases are separate Java/private-fixture acceptance. This endpoint
currently has no frontend consumer; this script does not claim a UI reminder.
"""
import json
import time
import urllib.error
from decimal import Decimal
from urllib.parse import urlencode

from e2elib import call, login, provision_user, today_bj

REPORT_KEYS = {'id', 'item_name', 'modality', 'impression', 'verified_at'}


class Fixture:
    def __init__(self, prefix='v122'):
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
                self.identities[who] = self.api('GET', '/auth/me', who=who)
        self.department = self.api('POST', '/system/depts', {
            'code': 'V122D' + self.suffix, 'name': 'V122模拟互认门诊' + self.suffix,
            'type': 'CLINICAL', 'sortNo': 9999})
        self.exam_department = self.api('POST', '/system/depts', {
            'code': 'V122E' + self.suffix, 'name': 'V122模拟互认影像' + self.suffix,
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
            f'V122US{self.suffix},V122模拟超声{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n' +
            f'V122CT{self.suffix},V122模拟CT{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n')
        assert imported['imported'] == 2 and imported['errorCount'] == 0
        items = self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
        self.items = [next(x for x in items if x['code'] == 'V122' + kind + self.suffix) for kind in ['US', 'CT']]
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
            'name': 'V122模拟' + label + self.suffix, 'sex': 'U', 'phone': '13900001220'})
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
                       'clinicalSummary': 'V122模拟临床摘要', 'examPurpose': 'V122模拟检查目的'}]}, who='doctor')
        assert len(lines) == 1 and lines[0]['status'] == 'CREATED'
        case = {**visit, 'order': lines[0], 'label': label, 'item': selected,
                'findings': 'V122模拟所见-' + label, 'impression': 'V122模拟结论-' + label}
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


def main():
    f = Fixture()
    assert f.recent() == f.recent(f.other['patient']['id']) == []
    first = f.exam('本人超声一')
    second = f.exam('本人CT', item=1)
    third = f.exam('本人超声二')
    other = f.exam('另一患者报告', visit=f.other)
    draft = f.exam('未审核草稿', verified=False)
    queue = f.register(f.order('已登记未报告'))
    unpaid = f.order('从未收费', paid=False)
    assert f.source(unpaid)['status'] == 'CREATED'
    expected = [third['exam_id'], second['exam_id'], first['exam_id']]
    baseline = f.public_snapshot()
    for who in ['admin', 'tech', 'doctor', 'nurse']:
        assert [x['id'] for x in f.recent(who=who)] == expected
        assert [x['id'] for x in f.recent(f.other['patient']['id'], who=who)] == [other['exam_id']]
    assert f.recent(item_name='') == f.recent(item_name='  ') == f.recent()
    assert [x['id'] for x in f.recent(item_name=f.items[0]['name'])] == [third['exam_id'], first['exam_id']]
    assert f.recent(item_name=f.items[0]['name'][:-1]) == f.recent(item_name=' ' + f.items[0]['name']) == []
    assert f.recent(9223372036854775807) == []
    assert f.public_snapshot() == baseline
    print('[1] Four original clinical duties, selected real patient, original five fields/times, exact/blank item filters and order retained')

    portal = f.api('POST', '/portal/login', {'patientNo': f.owner['patient']['patientNo'], 'phone': '13900001220'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    baseline = f.public_snapshot()
    for who in ['cashier', 'pharmacist', 'interface', 'portal', 'disabled', None]:
        f.recent(who=who, code=1000 if who in ['disabled', None] else 1005)
    assert f.public_snapshot() == baseline
    print('[2] Wrong duty, patient/machine domains, disabled and anonymous refusals preserve clinical projections')

    f.api('POST', f"/outpatient/charges/{draft['charge']['id']}/refund", who='cashier')
    assert f.source(draft)['status'] == 'CREATED'
    baseline = f.public_snapshot()
    assert [x['id'] for x in f.recent()] == expected
    assert draft['exam_id'] not in expected and queue['exam_id'] not in expected
    f.api('POST', f"/outpatient/charges/{first['charge']['id']}/refund", who='cashier', code=5005)
    assert f.public_snapshot() == baseline
    print('[3] Draft, refunded draft, merely registered and never-paid orders stay unpublished; verified execution still blocks refund 5005')
    print('e2e-v122-ris-recent-read passed: real JWT original four duties, five-key selected-patient report contract, exact item filters, public clinical publication and read-only projections. Historical changed-type/status, date window/20-limit and full-column/browser compatibility evidence are separate acceptance; no frontend recent-report reminder or PG race is claimed.')


if __name__ == '__main__':
    main()
