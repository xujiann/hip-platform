# -*- coding: utf-8 -*-
"""Public HTTP regression for eligible laboratory report print datasets.

No SQL or configuration mutation. Real manual publication and unsampled machine
ORU create printable reports. Generic execution can legally produce EXECUTED
without lab-result rows, and must not manufacture a laboratory report. Historic
refund/type changes and same-group sibling fixtures belong to dedicated Java /
private browser acceptance; ordinary LAB ordering assigns separate groupNos.
"""
import json
import time
import urllib.error
from decimal import Decimal

from e2elib import call, login, provision_user, today_bj

ROOT_KEYS = {'id', 'item_name', 'group_no', 'created_at', 'patient_name', 'sex', 'patient_no', 'results'}
RESULT_KEYS = {'item_name', 'result_value', 'unit', 'ref_range', 'abnormal_flag'}


class Fixture:
    def __init__(self, prefix='v121'):
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
            person = self.api('GET', '/auth/me', who=who)
            assert not person.get('mustChangePassword', False)
            self.identities[who] = person
            if role == 'INTERFACE':
                updated = self.api('PUT', f"/system/users/{person['id']}", {
                    'username': username, 'realName': username, 'roleCodes': ['INTERFACE']})
                assert updated['roleCodes'] == ['INTERFACE'] and updated['enabled']
                self.tokens[who] = login(username, 'Work@1234')
        self.department = self.api('POST', '/system/depts', {
            'code': 'V121D' + self.suffix, 'name': 'V121模拟检验门诊' + self.suffix,
            'type': 'CLINICAL', 'sortNo': 9999})
        self.lab_department = self.api('POST', '/system/depts', {
            'code': 'V121L' + self.suffix, 'name': 'V121模拟检验科' + self.suffix,
            'type': 'MEDTECH', 'sortNo': 9999})
        self.schedule = self.api('POST', '/outpatient/schedules', {
            'deptId': self.department['id'], 'scheduleDate': today_bj().isoformat(),
            'fee': 0, 'capacity': 30})
        self.item_code = 'V121LAB' + self.suffix
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'{self.item_code},V121模拟检验{self.suffix},LAB,次,1.00,,,{self.lab_department["code"]}\n')
        assert imported['imported'] == 1 and imported['errorCount'] == 0
        self.item = next(row for row in self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
                         if row['code'] == self.item_code)
        self.cases = {}

    def api(self, method, path, body=None, who='admin', code=0, text=None):
        try:
            response = call(method, path, body, self.tokens.get(who), text=text)
        except urllib.error.HTTPError as error:
            assert (code, error.code) in [(1005, 403), (1000, 401)], (method, path, code, error.code)
            response = json.load(error)
        assert response['code'] == code, (method, path, code, response.get('code'), response.get('message'))
        if code:
            assert response.get('data') is None, 'Refusal returned a print/clinical payload'
        return response.get('data')

    def visit(self, label, paid=True):
        patient = self.api('POST', '/patients', {
            'name': 'V121模拟' + label + self.suffix, 'sex': 'U', 'phone': '13900001210'})
        registration = self.api('POST', '/outpatient/registrations', {
            'patientId': patient['id'], 'scheduleId': self.schedule['id']}, who='cashier')
        self.api('POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
        orders = self.api('POST', f"/outpatient/doctor/{registration['id']}/orders", {'lines': [{
            'orderType': 'LAB', 'itemId': self.item['id'], 'qty': 1,
            'specimenType': '模拟血清', 'samplingSite': '模拟采样部位'}]}, who='doctor')
        assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
        case = {'patient': patient, 'registration': registration, 'order': orders[0], 'label': label}
        if paid:
            charge = self.api('POST', '/outpatient/charges/settle', {
                'registrationId': registration['id'], 'payMethod': 'CASH'}, who='cashier')
            assert charge['status'] == 'PAID' and Decimal(str(charge['totalAmount'])) == Decimal('1.00')
            assert charge['cashierId'] == self.identities['cashier']['id']
            case['charge'] = charge
        self.cases[label] = case
        return case

    def source(self, case):
        workspace = self.api('GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")
        return next(row for row in workspace['orders'] if row['id'] == case['order']['id'])

    def results(self, case):
        return self.api('GET', f"/outpatient/lab-results?orderId={case['order']['id']}")

    def samples(self, case):
        return [row for row in self.api('GET', '/lis/samples') if row['order_id'] == case['order']['id']]

    def snapshot(self, case):
        return {'source': self.source(case), 'results': self.results(case), 'samples': self.samples(case),
                'charges': [row for row in self.api('GET', '/outpatient/charges/recent', who='cashier')
                            if row['registrationId'] == case['registration']['id']],
                'execution': self.api('GET', '/outpatient/exec/reports?registrationId=' + str(case['registration']['id'])),
                'alerts': [row for row in self.api('GET', '/outpatient/critical-alerts?status=NEW')
                           if row['patientNo'] == case['patient']['patientNo']]}

    def line(self, label, flag='N', nullable=False):
        return {'code': 'V121' + str(len(self.cases)) + label.encode('utf8').hex()[-10:] + self.suffix,
                'name': 'V121模拟' + label, 'value': '99' if flag in {'HH', 'LL'} else '阴性',
                'unit': None if nullable else '模拟单位', 'refRange': None if nullable else '模拟范围', 'flag': flag}

    def publish(self, case, lines):
        barcode = self.api('POST', f"/lis/samples?orderId={case['order']['id']}", who='nurse')['barcode']
        case['barcode'] = barcode
        self.api('PUT', f'/lis/samples/{barcode}/receive', who='tech')
        self.api('POST', f'/lis/samples/{barcode}/publish', {'results': lines}, who='tech')
        assert self.source(case)['status'] == 'EXECUTED' and not self.samples(case)
        case['expected_lines'] = lines

    def oru(self, groups):
        message = f'MSH|^~\\&|V121LIS|TEST|HIP|TEST|20261024000000||ORU^R01|V121{time.time_ns()}|P|2.5\r'
        total = 0
        for position, (case, lines) in enumerate(groups, 1):
            message += f'OBR|{position}|{case["order"]["groupNo"]}||{self.item_code}^V121模拟检验\r'
            for index, line in enumerate(lines, 1):
                total += 1
                message += (f'OBX|{index}|ST|{line["code"]}^{line["name"]}|{index}|{line["value"]}|'
                            f'{line["unit"] or ""}|{line["refRange"] or ""}|{line["flag"] or ""}\r')
        answer = self.api('POST', '/integration/hl7/oru', who='interface', text=message)
        assert answer == {'groupNo': groups[0][0]['order']['groupNo'], 'items': total}
        for case, _ in groups:
            assert self.source(case)['status'] == 'EXECUTED'

    def report(self, case, who='tech', code=0, lines=None):
        report = self.api('GET', f"/print/lab-report/{case['order']['id']}", who=who, code=code)
        if code:
            return None
        assert set(report) == ROOT_KEYS
        assert report['id'] == case['order']['id'] and report['group_no'] == case['order']['groupNo']
        assert report['item_name'] == self.item['name'] and report['patient_name'] == case['patient']['name']
        assert report['patient_no'] == case['patient']['patientNo'] and report['sex'] == 'U' and report['created_at']
        assert report['results'] and all(set(row) == RESULT_KEYS for row in report['results'])
        if lines is not None:
            assert report['results'] == [{'item_name': row['name'], 'result_value': row['value'],
                'unit': row['unit'], 'ref_range': row['refRange'], 'abnormal_flag': row['flag']} for row in lines]
        return report


def main():
    f = Fixture()
    pending = f.visit('已付未执行')
    empty = f.visit('执行站空结果')
    f.api('POST', f"/outpatient/exec/{empty['order']['id']}", {'resultText': 'V121模拟通用执行记录'}, who='tech')
    assert f.source(empty)['status'] == 'EXECUTED' and not f.results(empty)
    never_paid = f.visit('未付申请', paid=False)
    refunded = f.visit('真实退费')
    f.api('POST', f"/outpatient/charges/{refunded['charge']['id']}/refund", who='cashier')
    assert f.source(refunded)['status'] == 'CREATED' and f.source(refunded)['chargeId'] is None
    for case in [pending, empty, never_paid, refunded]:
        before = f.snapshot(case); f.report(case, code=9932); assert f.snapshot(case) == before
    f.api('GET', '/print/lab-report/9000000000000000', code=9932)
    print('[1] Charged/unpaid/refunded sources and legal generic execution without laboratory results refuse 9932 without public projection changes')

    manual = f.visit('手工打印')
    lines = [f.line('正常'), f.line('危急', 'HH', True), f.line('空元数据', None, True)]
    f.publish(manual, lines)
    before = f.snapshot(manual)
    report = f.report(manual, lines=lines)
    for who in ['admin', 'cashier', 'doctor', 'nurse', 'tech']:
        assert f.report(manual, who, lines=lines) == report
        assert f.snapshot(manual) == before
    portal = f.api('POST', '/portal/login', {'patientNo': manual['patient']['patientNo'], 'phone': '13900001210'}, who=None)
    f.tokens['portal'] = portal['token']
    for who in ['pharmacist', 'interface', 'portal', None]:
        f.report(manual, who, code=1000 if who is None else 1005)
        assert f.snapshot(manual) == before
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    f.report(manual, 'disabled', code=1000)
    assert f.snapshot(manual) == before
    print('[2] Original five print duties and exact manual report values/nullable metadata retained; wrong-duty/portal/machine/anonymous/disabled reads refused')

    machine, second = f.visit('无样本机器打印'), f.visit('独立申请机器结果')
    machine_lines, second_lines = [f.line('机器低危急', 'LL'), f.line('机器正常')], [f.line('另申请独有')]
    f.oru([(machine, machine_lines), (second, second_lines)])
    assert not f.samples(machine) and not f.samples(second)
    assert f.report(machine, lines=machine_lines)['results'] != f.report(second, lines=second_lines)['results']
    duplicate = [lines[0], lines[0]]
    f.oru([(manual, duplicate)])
    f.report(manual, lines=lines + duplicate)
    for case in [manual, machine, second]:
        before = f.snapshot(case); f.report(case); assert f.snapshot(case) == before
        f.api('POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier', code=5005)
        assert f.snapshot(case) == before
    print('[3] Unsampled machine ORU, multi-OBR order isolation, original duplicate append order and executed-refund policy retained')
    print('e2e-v121-lab-print-read passed: real JWT duties, published LAB report contract, unsampled machine compatibility, selected-order results and read-only public projections. Historical relation/source mutations, same-group sibling fixtures and browser print preflight timing are separate acceptance; no physical print job is issued.')


if __name__ == '__main__':
    main()
