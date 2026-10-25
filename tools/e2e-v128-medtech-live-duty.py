# -*- coding: utf-8 -*-
"""Public compatibility for the eight manual LIS/RIS and worklist entry points.

Only public APIs create private employees, departments, patients, orders and
clinical facts. Sequential role changes here prove the ordinary request gate;
they are NOT evidence of an in-flight PostgreSQL wait. Dedicated Java/private
browser acceptance proves current-duty checks after a named lock holder waits.
No SQL or shared configuration mutation is used by this public script.
"""
import importlib.util
from decimal import Decimal
from pathlib import Path

from e2elib import login, provision_user, today_bj

_spec = importlib.util.spec_from_file_location(
    'v128_lab_fixture', Path(__file__).with_name('e2e-v121-lab-print-read.py'))
_lab = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_lab)

ROLES = ['admin', 'tech', 'doctor', 'nurse']
WORKLIST_KEYS = {'id', 'status', 'modality', 'findings', 'impression', 'item_name',
                 'group_no', 'order_status', 'clinical_summary', 'exam_purpose',
                 'notice', 'urgent', 'patient_name', 'diagnosis'}


class Fixture(_lab.Fixture):
    """Importable API fixture, also used to seed the isolated browser database."""
    def __init__(self, prefix='v128'):
        super().__init__(prefix)
        name = prefix + '_quality_' + self.suffix
        self.users['quality'] = name
        self.tokens['quality'] = provision_user(self.tokens['admin'], name, 'QUALITY')
        self.identities['quality'] = self.api('GET', '/auth/me', who='quality')
        self.other_department = self.api('POST', '/system/depts', {
            'code': 'V128O' + self.suffix, 'name': 'V128模拟另一执行科' + self.suffix,
            'type': 'MEDTECH', 'sortNo': 9999})
        for who, role in [('tech', 'TECHNICIAN'), ('nurse', 'NURSE'), ('doctor', 'DOCTOR_OUTP')]:
            dept = self.department if who == 'doctor' else self.other_department
            self.set_roles(who, [role], dept['id'])
            self.identities[who] = self.api('GET', '/auth/me', who=who)
            assert self.identities[who]['deptId'] != self.lab_department['id']
        code = 'V128EXAM' + self.suffix
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'{code},V128模拟超声{self.suffix},EXAM,次,1.00,,,{self.lab_department["code"]}\n')
        assert imported['imported'] == 1 and imported['errorCount'] == 0
        self.exam_item = next(row for row in self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
                              if row['code'] == code)

    def set_roles(self, who, roles, dept_id=None):
        person = self.identities[who]
        result = self.api('PUT', f"/system/users/{person['id']}", {
            'username': self.users[who], 'realName': self.users[who],
            'deptId': person.get('deptId') if dept_id is None else dept_id,
            'roleCodes': roles})
        assert result['id'] == person['id'] and result['enabled']
        assert set(result['roleCodes']) == set(roles)
        return result

    def visit(self, label, kind='LAB', paid=True, register=True):
        if kind == 'LAB':
            case = super().visit(label, paid)
        else:
            assert kind == 'EXAM'
            patient = self.api('POST', '/patients', {
                'name': 'V128模拟' + label + self.suffix, 'sex': 'U', 'phone': '13900001280'})
            reg = self.api('POST', '/outpatient/registrations', {
                'patientId': patient['id'], 'scheduleId': self.schedule['id']}, who='cashier')
            self.api('POST', f"/outpatient/doctor/{reg['id']}/start", who='doctor')
            orders = self.api('POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [{
                'orderType': 'EXAM', 'itemId': self.exam_item['id'], 'qty': 1,
                'clinicalSummary': 'V128模拟摘要', 'examPurpose': 'V128模拟目的',
                'notice': 'V128模拟注意', 'urgent': True}]}, who='doctor')
            assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
            case = {'patient': patient, 'registration': reg, 'order': orders[0], 'label': label}
            if paid:
                case['charge'] = self.api('POST', '/outpatient/charges/settle', {
                    'registrationId': reg['id'], 'payMethod': 'CASH'}, who='cashier')
                assert case['charge']['status'] == 'PAID'
                assert Decimal(str(case['charge']['totalAmount'])) == Decimal('1.00')
                assert case['charge']['cashierId'] == self.identities['cashier']['id']
            self.cases[label] = case
        case['kind'] = kind
        if kind == 'EXAM' and paid and register:
            rows = self.worklist(case)
            assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED'
            case['exam_id'] = rows[0]['id']
        return case

    def worklist(self, case, who='admin', modality=None):
        query = '' if modality is None else '?modality=' + modality
        rows = self.api('GET', '/ris/worklist' + query, who=who)
        assert all(set(row) == WORKLIST_KEYS for row in rows)
        return [row for row in rows if row['group_no'] == case['order']['groupNo']]

    def facts(self, case):
        result = super().snapshot(case)
        result['cdr'] = self.api('GET', f"/cdr/patients/{case['patient']['id']}/documents")
        if case['kind'] == 'EXAM':
            result['exam'] = self.worklist(case)
            result['recent'] = self.api('GET', f"/ris/recent-exams?patientId={case['patient']['id']}")
        return result

    def collect(self, case, who='nurse', code=0):
        result = self.api('POST', f"/lis/samples?orderId={case['order']['id']}", who=who, code=code)
        if not code:
            assert set(result) == {'barcode'} and result['barcode'].startswith('BC')
            case['barcode'] = result['barcode']
        return result

    def receive(self, case, who='tech', code=0):
        return self.api('PUT', f"/lis/samples/{case['barcode']}/receive", who=who, code=code)

    def publish(self, case, who='tech', code=0, lines=None):
        return self.api('POST', f"/lis/samples/{case['barcode']}/publish", {
            'results': lines if lines is not None else [self.line('手工发布')]}, who=who, code=code)

    def write(self, case, action, who='tech', code=0, body=None):
        if body is None:
            body = report_body(who) if action == 'report' else {'note': 'V128模拟危急征象'} if action == 'critical' else None
        return self.api('PUT', f"/ris/exams/{case['exam_id']}/{action}", body, who=who, code=code)

    def refused(self, case, fn):
        before = self.facts(case)
        fn()
        assert self.facts(case) == before, 'Refusal changed a public clinical/financial projection'


def report_body(label='草稿'):
    return {'findings': 'V128模拟所见' + str(label), 'impression': 'V128模拟结论' + str(label)}


def main():
    f = Fixture()
    for index, who in enumerate(ROLES):
        lab = f.visit('检验四岗' + who)
        before = f.facts(lab)
        f.collect(lab, who)
        assert f.samples(lab)[0]['status'] == 'COLLECTED'
        assert f.receive(lab, who) is None
        assert f.samples(lab)[0]['status'] == 'RECEIVED'
        assert f.source(lab) == before['source']
        f.refused(lab, lambda: f.collect(lab, who, 9940))
        f.refused(lab, lambda: f.receive(lab, who, 9941))
        f.refused(lab, lambda: f.publish(lab, who, 9943, []))
        lines = [f.line('四岗' + who), f.line('危急' + who, 'HH', True)]
        assert f.publish(lab, who, lines=lines) is None
        after = f.facts(lab)
        assert after['source'] == {**before['source'], 'status': 'EXECUTED'}
        assert after['charges'] == before['charges'] and not after['samples']
        assert len(after['results']) == 2 and [row['itemName'] for row in after['results']] == [line['name'] for line in lines]
        assert [row['resultValue'] for row in after['results']] == [line['value'] for line in lines]
        assert len(after['alerts']) == 1 and after['cdr'] == before['cdr']
        assert after['execution'] == before['execution']
        f.refused(lab, lambda: f.publish(lab, who, 9942))
        f.refused(lab, lambda: f.api('POST', f"/outpatient/charges/{lab['charge']['id']}/refund", who='cashier', code=5005))

        exam = f.visit('影像四岗' + who, 'EXAM')
        baseline = f.facts(exam)
        row = f.worklist(exam, who)[0]
        assert row['modality'] == 'US' and row['order_status'] == 'CHARGED'
        assert row['patient_name'] == exam['patient']['name'] and row['clinical_summary'] == 'V128模拟摘要'
        assert row['exam_purpose'] == 'V128模拟目的' and row['notice'] == 'V128模拟注意' and row['urgent'] is True
        assert f.worklist(exam, who, 'US') == [row] and f.worklist(exam, who, 'ECG') == []
        f.refused(exam, lambda: f.write(exam, 'verify', who, 9945))
        f.refused(exam, lambda: f.write(exam, 'critical', who, 9945))
        assert f.write(exam, 'arrive', who) is None
        f.refused(exam, lambda: f.write(exam, 'arrive', who, 9944))
        assert f.write(exam, 'report', who) is None
        editor = ROLES[(index + 1) % len(ROLES)]
        body = report_body('跨员工续写' + editor)
        assert f.write(exam, 'report', editor, body=body) is None
        f.refused(exam, lambda: f.write(exam, 'verify', editor, 9947))
        f.refused(exam, lambda: f.write(exam, 'critical', who, 9948, {'note': '  '}))
        assert f.write(exam, 'critical', who) is None
        f.refused(exam, lambda: f.write(exam, 'critical', who, 9949))
        draft = f.facts(exam)
        assert draft['source'] == baseline['source'] and draft['charges'] == baseline['charges']
        assert draft['exam'][0]['findings'] == body['findings'] and draft['exam'][0]['impression'] == body['impression']
        assert not draft['results'] and not draft['execution'] and draft['cdr'] == baseline['cdr']
        assert len(draft['alerts']) == 1 and '【影像危急值】' in draft['alerts'][0]['content']
        assert f.write(exam, 'verify', who) is None
        published = f.facts(exam)
        assert published['source'] == {**baseline['source'], 'status': 'EXECUTED'}
        assert published['charges'] == baseline['charges'] and not published['exam']
        assert len(published['recent']) == 1 and published['recent'][0]['impression'] == body['impression']
        assert published['alerts'] == draft['alerts'] and not published['results'] and not published['execution']
        f.refused(exam, lambda: f.write(exam, 'report', editor, 9944))
        f.refused(exam, lambda: f.api('POST', f"/outpatient/charges/{exam['charge']['id']}/refund", who='cashier', code=5005))
    print('[1] Original four duties, cross-department/employee collaboration, eight entries, fourteen worklist keys, original business refusals, double signature and clinical side effects retain their contracts; money is unchanged')

    lab, exam = f.visit('拒绝检验'), f.visit('拒绝影像', 'EXAM')
    f.collect(lab); f.receive(lab)
    # Every request is made with a real independent employee token, never a mock role.
    def deny_all(who, code):
        f.refused(lab, lambda: f.collect(lab, who, code))
        f.refused(lab, lambda: f.receive(lab, who, code))
        f.refused(lab, lambda: f.publish(lab, who, code))
        for action in ['arrive', 'report', 'verify', 'critical']:
            f.refused(exam, lambda action=action: f.write(exam, action, who, code))
        f.refused(exam, lambda: f.api('GET', '/ris/worklist', who=who, code=code))
    for who in ['cashier', 'pharmacist', 'interface', 'quality', None]:
        deny_all(who, 1000 if who is None else 1005)
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    deny_all('disabled', 1000)
    print('[2] All eight entries reject wrong-duty/anonymous/disabled identities without clinical payload or projected business changes')

    f.set_roles('tech', ['CASHIER'])
    deny_all('tech', 1005)
    f.set_roles('tech', ['NURSE', 'CASHIER'])
    assert f.receive(lab, 'tech', 9941) is None, 'Another legal current duty must not be rejected as lost TECHNICIAN'
    f.set_roles('tech', ['NURSE'])
    assert f.write(exam, 'arrive', 'tech') is None
    f.set_roles('tech', ['TECHNICIAN'])
    assert f.write(exam, 'report', 'tech', body=report_body('恢复同一检查')) is None
    assert f.worklist(exam)[0]['id'] == exam['exam_id']
    assert f.publish(lab, 'tech') is None
    f.set_roles('tech', ['TECHNICIAN', 'CASHIER'])
    assert f.worklist(exam, 'tech')[0]['id'] == exam['exam_id']
    f.set_roles('tech', ['TECHNICIAN'])
    print('[3] Ordinary sequential API duty removal/refusal, legal-duty substitution, mixed duties and restoration keep the same clinical objects; in-flight revocation belongs to deterministic Java/private-browser evidence')
    print('e2e-v128-medtech-live-duty passed: real-API eight-entry/four-duty compatibility, original contracts/double signature/side effects, unchanged financial projections and sequential duty recovery. No SQL, configuration mutation, fabricated clinical histories or claimed public PostgreSQL contention. Raw timestamps/actors/full ledgers and actual wait-then-revoke are dedicated Java/private-browser acceptance.')


if __name__ == '__main__':
    main()
