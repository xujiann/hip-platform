# -*- coding: utf-8 -*-
"""Public HTTP regression for patient-owned report publication eligibility.

No SQL, config mutation or manufactured VERIFIED status is used here. Historic
VERIFIED rows with refunded/changed-type/CHARGED sources need dedicated Java and
private browser fixtures; this sequential public run does not prove PG races.
The import-safe fixture is also reused by the private browser seed helper.
"""
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, provision_user, today_bj


REPORT_KEYS = {'report_type', 'item_name', 'conclusion', 'detail', 'report_date'}


class Fixture:
    def __init__(self, prefix='v120'):
        self.suffix = str(time.time_ns())[-11:]
        self.prefix = prefix
        self.tokens = {'admin': provision_user(login(), prefix + '_admin_' + self.suffix, 'ADMIN')}
        self.users = {'admin': prefix + '_admin_' + self.suffix}
        self.identities = {'admin': self.api('GET', '/auth/me')}
        for who, role in [('doctor', 'DOCTOR_OUTP'), ('tech', 'TECHNICIAN'), ('cashier', 'CASHIER')]:
            username = prefix + '_' + who + '_' + self.suffix
            self.tokens[who] = provision_user(self.tokens['admin'], username, role)
            self.users[who] = username
            self.identities[who] = self.api('GET', '/auth/me', who=who)
        self.department = self.api('POST', '/system/depts', {
            'code': 'V120D' + self.suffix, 'name': 'V120模拟患者门诊' + self.suffix,
            'type': 'CLINICAL', 'sortNo': 9999})
        self.exam_department = self.api('POST', '/system/depts', {
            'code': 'V120E' + self.suffix, 'name': 'V120模拟检查科' + self.suffix,
            'type': 'MEDTECH', 'sortNo': 9999})
        self.schedule = self.api('POST', '/outpatient/schedules', {
            'deptId': self.department['id'], 'scheduleDate': today_bj().isoformat(),
            'fee': 0, 'capacity': 12})
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'V120US{self.suffix},V120模拟超声{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n' +
            f'V120PATH{self.suffix},V120模拟病理组织{self.suffix},EXAM,次,1.00,,,{self.exam_department["code"]}\n')
        assert imported['imported'] == 2 and imported['errorCount'] == 0
        items = self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
        self.item = next(x for x in items if x['code'] == 'V120US' + self.suffix)
        self.path_item = next(x for x in items if x['code'] == 'V120PATH' + self.suffix)
        # Deliberately synthetic phone values, only for the named test patients.
        self.owner = self.api('POST', '/patients', {
            'name': 'V120模拟报告本人' + self.suffix, 'sex': 'U', 'phone': '13900001200'})
        self.other = self.api('POST', '/patients', {
            'name': 'V120模拟报告旁人' + self.suffix, 'sex': 'U', 'phone': '13900001201'})
        self.portal_login('owner', self.owner, '13900001200')
        self.portal_login('other', self.other, '13900001201')
        self.registration = self.api('POST', '/outpatient/registrations', {
            'patientId': self.owner['id'], 'scheduleId': self.schedule['id']}, who='cashier')
        self.api('POST', f"/outpatient/doctor/{self.registration['id']}/start", who='doctor')
        self.cases = {}

    def api(self, method, path, body=None, who='admin', code=0, text=None):
        try:
            response = call(method, path, body, self.tokens.get(who), text=text)
        except urllib.error.HTTPError as error:
            assert (code, error.code) in [(1005, 403), (1000, 401)], (method, path, code, error.code)
            response = json.load(error)
        assert response['code'] == code, (method, path, code, response.get('code'), response.get('message'))
        if code:
            assert response.get('data') is None, 'Refusal returned a clinical payload'
        return response.get('data')

    def portal_login(self, who, patient, phone):
        answer = self.api('POST', '/portal/login', {'patientNo': patient['patientNo'], 'phone': phone}, who=None)
        assert answer['patientNo'] == patient['patientNo'] and answer['patientName'] == patient['name']
        self.tokens[who] = answer['token']

    def reports(self, who='owner', suffix=''):
        rows = self.api('GET', '/portal/my/exam-reports' + suffix, who=who)
        assert all(set(row) == REPORT_KEYS for row in rows)
        return rows

    def source(self, case):
        workspace = self.api('GET', f"/outpatient/doctor/{self.registration['id']}/workspace")
        return next(row for row in workspace['orders'] if row['id'] == case['order']['id'])

    def order(self, label, pathology=False, paid=True):
        item = self.path_item if pathology else self.item
        lines = self.api('POST', f"/outpatient/doctor/{self.registration['id']}/orders", {
            'lines': [{'orderType': 'EXAM', 'itemId': item['id'], 'qty': 1,
                       'clinicalSummary': 'V120模拟摘要', 'examPurpose': 'V120模拟检查目的'}]}, who='doctor')
        assert len(lines) == 1 and lines[0]['status'] == 'CREATED'
        case = {'order': lines[0], 'label': label, 'item': item,
                'findings': 'V120模拟所见-' + label, 'impression': 'V120模拟结论-' + label}
        if paid:
            charge = self.api('POST', '/outpatient/charges/settle', {
                'registrationId': self.registration['id'], 'payMethod': 'CASH'}, who='cashier')
            assert charge['status'] == 'PAID' and Decimal(str(charge['totalAmount'])) == Decimal('1.00'), 'Only the new order is settled'
            assert charge['cashierId'] == self.identities['cashier']['id']
            case['charge'] = charge
            assert self.source(case)['status'] == 'CHARGED'
        self.cases[label] = case
        return case

    def exam(self, label, verified=True):
        case = self.order(label)
        rows = [r for r in self.api('GET', '/ris/worklist') if r['group_no'] == case['order']['groupNo']]
        assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED'
        case['exam_id'] = rows[0]['id']
        self.api('PUT', f"/ris/exams/{case['exam_id']}/report", {
            'findings': case['findings'], 'impression': case['impression']}, who='doctor')
        self.api('PUT', f"/ris/exams/{case['exam_id']}/verify", who='doctor', code=9947)
        if verified:
            self.api('PUT', f"/ris/exams/{case['exam_id']}/verify", who='tech')
            assert self.source(case)['status'] == 'EXECUTED'
            actual = next(r for r in self.api('GET', f"/ris/recent-exams?patientId={self.owner['id']}")
                          if r['id'] == case['exam_id'])
            case['verified_at'] = actual['verified_at']
        return case

    def manual_path(self, label, source=None):
        case = source or self.order(label, pathology=True, paid=False)
        assert self.source(case)['status'] == 'CREATED' and self.source(case)['chargeId'] is None
        result = self.api('POST', '/pathology/registry/specimens/manual', {
            'orderId': case['order']['id'], 'partNo': 1, 'specimenType': 'ROUTINE',
            'specimenDesc': 'V120模拟手工实物', 'samplingSite': 'V120模拟部位',
            'clinicalDiagnosis': 'V120模拟待查', 'fixative': '模拟固定液',
            'applyDept': 'V120模拟门诊', 'applyDoctor': 'V120模拟申请人', 'note': 'V120物理实物救济'}, who='tech')
        assert result['manual'] and result['status'] == 'RECEIVED'
        case['specimen'] = result
        case['diagnosis'] = 'V120模拟病理结论-' + label
        case['micro'] = 'V120模拟镜下-' + label
        self.api('PUT', f"/pathology/specimens/{result['barcode']}/diagnose", {
            'grossFinding': 'V120模拟大体', 'microFinding': case['micro'], 'diagnosis': case['diagnosis']}, who='doctor')
        assert not any(row['conclusion'] == case['diagnosis'] for row in self.reports()), 'Unissued PATH draft is not public'
        self.api('PUT', f"/pathology/report/{result['id']}/first-sign", who='doctor')
        self.api('PUT', f"/pathology/report/{result['id']}/second-sign", who='admin')
        issued = self.api('PUT', f"/pathology/report/{result['id']}/issue", who='doctor')
        assert issued['doubleSignComplete'] is True and issued['orderExecuted'] is False
        case['issued_at'] = issued['reportIssuedAt']
        assert self.source(case)['status'] == 'CREATED' and self.source(case)['chargeId'] is None
        row = next(row for row in self.reports() if row['conclusion'] == case['diagnosis'])
        assert row == {'report_type': 'PATH', 'item_name': self.path_item['name'],
                       'conclusion': case['diagnosis'], 'detail': case['micro'], 'report_date': case['issued_at']}
        return case

    def public_snapshot(self):
        return {'workspace': self.api('GET', f"/outpatient/doctor/{self.registration['id']}/workspace"),
                'charges': [r for r in self.api('GET', '/outpatient/charges/recent', who='cashier')
                            if r['registrationId'] == self.registration['id']],
                'execution': self.api('GET', '/outpatient/exec/reports?registrationId=' + str(self.registration['id'])),
                'specimens': [r for r in self.api('GET', '/pathology/specimens')
                              if r.get('order_id') in {c['order']['id'] for c in self.cases.values()}],
                'pathReports': {str(c['specimen']['id']): self.api('GET', f"/pathology/report/{c['specimen']['id']}/reports")
                                for c in self.cases.values() if 'specimen' in c}}


def main():
    f = Fixture()
    assert f.reports() == f.reports('other') == []
    verified = f.exam('已审核合法')
    draft = f.exam('未审核草稿', verified=False)
    rows = f.reports()
    assert rows == [{'report_type': 'EXAM', 'item_name': f.item['name'],
                     'conclusion': verified['impression'], 'detail': verified['findings'],
                     'report_date': verified['verified_at']}]
    before = f.public_snapshot()
    assert f.reports('other') == []
    assert f.reports(suffix='?patientId=' + str(f.other['id'])) == rows, 'Client patientId cannot replace the signed subject'
    for who in ['admin', 'doctor', 'tech', 'cashier', None]:
        f.api('GET', '/portal/my/exam-reports', who=who, code=1000 if who is None else 1005)
    assert f.public_snapshot() == before
    print('[1] Real patient JWT ownership, original five fields, exact verification time and wrong-domain refusals passed')

    # Pay all intended charged orders before any legal refund restores CREATED;
    # later settlement must not accidentally re-charge a deliberate refund case.
    path_refunded = f.order('退款后实物', pathology=True)
    refund = f.api('POST', f"/outpatient/charges/{path_refunded['charge']['id']}/refund", who='cashier')
    assert refund['status'] == 'REFUNDED'
    refund = f.api('POST', f"/outpatient/charges/{draft['charge']['id']}/refund", who='cashier')
    assert refund['status'] == 'REFUNDED' and f.source(draft)['status'] == 'CREATED'
    before = f.public_snapshot()
    assert f.reports() == rows and f.reports('other') == []
    f.api('POST', f"/outpatient/charges/{verified['charge']['id']}/refund", who='cashier', code=5005)
    assert f.public_snapshot() == before
    print('[2] Unreviewed/refunded draft stays hidden; a live reviewed execution still cannot be refunded')

    never_paid = f.manual_path('从未付费手工实物')
    f.manual_path('退款后实物', source=path_refunded)
    final = f.reports()
    assert len(final) == 3 and [r['report_type'] for r in final] == ['PATH', 'PATH', 'EXAM']
    assert [r['report_date'] for r in final] == sorted([r['report_date'] for r in final], reverse=True)
    assert f.reports('other') == []
    assert any(r['conclusion'] == never_paid['diagnosis'] for r in final)
    before = f.public_snapshot()
    assert f.reports() == final and f.public_snapshot() == before
    print('[3] Real two-employee signed unpaid/refunded manual PATH remains published; mixed ordering and read-only public projections passed')
    print('e2e-v120-portal-report-read passed: real portal login/ownership, reviewed EXAM contract, draft/refund filtering, '
          'unchanged manual PATH rescue and public projection equality. Historical VERIFIED source mutation and '
          'complete-column/browser timing evidence belong to separate acceptance, not this sequential HTTP run.')


if __name__ == '__main__':
    main()
