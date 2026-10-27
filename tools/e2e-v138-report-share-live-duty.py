# -*- coding: utf-8 -*-
"""Public JWT compatibility for report-share current employee duties.

Only public APIs create private staff, visits, billing and independently reviewed
EXAM reports. Role changes below happen between requests: the dedicated Java
suite proves actual O/E/share PostgreSQL waits and rollback. Sequential HTTP
does not claim to reproduce those races. No SQL or shared configuration changes.
The main guard makes this fixture safe to import without creating any data.
"""
import importlib.util
import json
import re
import time
import urllib.error
from decimal import Decimal
from pathlib import Path

from e2elib import call, provision_user

_spec = importlib.util.spec_from_file_location(
    'v138_staff_fixture', Path(__file__).with_name('e2e-v121-lab-print-read.py'))
_staff = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_staff)


class Fixture(_staff.Fixture):
    def __init__(self, prefix='v138'):
        super().__init__(prefix)
        for who, role in [('mover', 'TECHNICIAN'), ('quality', 'QUALITY')]:
            self.users[who] = prefix + '_' + who + '_' + self.suffix
            self.tokens[who] = provision_user(self.tokens['admin'], self.users[who], role)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
        item_code = 'V138EXAM' + self.suffix
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'{item_code},V138模拟超声{self.suffix},EXAM,次,1.00,,,{self.lab_department["code"]}\n')
        assert imported['imported'] == 1 and imported['errorCount'] == 0
        self.exam_item = next(row for row in self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
                              if row['code'] == item_code)
        # Preserve the original cross-department clinical collaboration policy.
        for who, role in [('tech', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP')]:
            self.set_roles(who, [role], self.department['id'])
            assert self.identities[who]['deptId'] != self.lab_department['id']

    def api(self, method, path, body=None, who='admin', code=0, text=None):
        # Public bearer links and JWTs must never appear in an assertion log.
        safe_path = path.split('/share/')[0]
        try:
            result = call(method, path, body, self.tokens.get(who), text=text)
        except urllib.error.HTTPError as error:
            assert (code, error.code) in [(1005, 403), (1000, 401)], (who, method, safe_path, code, error.code)
            result = json.load(error)
        assert result['code'] == code, (who, method, safe_path, code, result.get('code'), result.get('message'))
        if code:
            assert result.get('data') is None, 'Refusal leaked a report payload'
        return result.get('data')

    def set_roles(self, who, roles, dept_id=None):
        person = self.identities[who]
        result = self.api('PUT', f"/system/users/{person['id']}", {
            'username': self.users[who], 'realName': self.users[who],
            'deptId': person.get('deptId') if dept_id is None else dept_id, 'roleCodes': roles})
        assert result['id'] == person['id'] and result['enabled']
        assert set(result['roleCodes']) == set(roles)
        self.identities[who] = result
        return result

    def worklist(self, case):
        return [row for row in self.api('GET', '/ris/worklist') if row['group_no'] == case['order']['groupNo']]

    def snapshot(self, case):
        return {'source': self.source(case), 'worklist': self.worklist(case),
                'charges': [row for row in self.api('GET', '/outpatient/charges/recent', who='cashier')
                            if row['registrationId'] == case['registration']['id']],
                'reports': self.api('GET', f"/ris/recent-exams?patientId={case['patient']['id']}"),
                'execution': self.api('GET', '/outpatient/exec/reports?registrationId=' + str(case['registration']['id']))}

    def visit(self, label, verified=True):
        patient = self.api('POST', '/patients', {'name': 'V138模拟' + label + self.suffix, 'sex': 'U'})
        registration = self.api('POST', '/outpatient/registrations', {
            'patientId': patient['id'], 'scheduleId': self.schedule['id']}, who='cashier')
        self.api('POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
        orders = self.api('POST', f"/outpatient/doctor/{registration['id']}/orders", {'lines': [{
            'orderType': 'EXAM', 'itemId': self.exam_item['id'], 'qty': 1,
            'clinicalSummary': 'V138模拟临床摘要', 'examPurpose': 'V138模拟检查目的'}]}, who='doctor')
        assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
        charge = self.api('POST', '/outpatient/charges/settle', {
            'registrationId': registration['id'], 'payMethod': 'CASH'}, who='cashier')
        assert charge['status'] == 'PAID' and Decimal(str(charge['totalAmount'])) == Decimal('1.00')
        assert charge['cashierId'] == self.identities['cashier']['id']
        case = {'patient': patient, 'registration': registration, 'order': orders[0], 'charge': charge, 'label': label,
                'findings': 'V138模拟所见' + label, 'impression': 'V138模拟结论' + label}
        rows = self.worklist(case)
        assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED'
        case['exam_id'] = rows[0]['id']; self.cases[label] = case
        if verified:
            self.report(case)
            self.api('PUT', f"/ris/exams/{case['exam_id']}/verify", who='tech')
            assert self.source(case)['status'] == 'EXECUTED' and self.worklist(case) == []
            actual = self.api('GET', f"/ris/recent-exams?patientId={patient['id']}")
            assert len(actual) == 1 and actual[0]['id'] == case['exam_id'] and actual[0]['verified_at']
        return case

    def report(self, case):
        self.api('PUT', f"/ris/exams/{case['exam_id']}/report", {
            'findings': case['findings'], 'impression': case['impression']}, who='doctor')
        assert self.worklist(case)[0]['status'] == 'REPORTED'
        before = self.snapshot(case)
        self.api('PUT', f"/ris/exams/{case['exam_id']}/verify", who='doctor', code=9947)
        assert self.snapshot(case) == before, 'Same employee verification changed report/billing projections'
        assert self.identities['doctor']['id'] != self.identities['tech']['id']

    def share(self, case, who='tech', minutes=None, code=0):
        query = '' if minutes is None else '?expireMinutes=' + str(minutes)
        answer = self.api('POST', f"/ris/exams/{case['exam_id']}/share" + query, who=who, code=code)
        if not code:
            assert set(answer) == {'token', 'url', 'expireMinutes'}
            assert re.fullmatch(r'[0-9a-f]{32}', answer['token'])
            assert answer['url'] == '/api/share/' + answer['token']
            assert answer['expireMinutes'] == (4320 if minutes is None else minutes)
        return answer

    def revoke(self, case, who='tech', code=0):
        answer = self.api('DELETE', f"/ris/exams/{case['exam_id']}/share", who=who, code=code)
        if not code:
            assert set(answer) == {'revoked'} and type(answer['revoked']) is int
        return answer

    def read(self, case, link, code=0):
        answer = self.api('GET', '/share/' + link['token'], who=None, code=code)
        if not code:
            assert set(answer) == {'patientName', 'itemName', 'modality', 'findings', 'impression', 'verifiedAt'}
            assert answer['patientName'] == case['patient']['name'][0] + '**'
            assert answer['patientName'] != case['patient']['name']
            assert answer['itemName'] == self.exam_item['name'] and answer['modality'] == 'US'
            assert answer['findings'] == case['findings'] and answer['impression'] == case['impression']
            assert answer['verifiedAt'] and answer['verifiedAt'] != 'null'
        return answer


def main():
    f = Fixture()
    expiry = f.visit('自然过期'); expiry_link = f.share(expiry, minutes=1); started = time.monotonic()
    before = f.snapshot(expiry); f.read(expiry, expiry_link); assert f.snapshot(expiry) == before
    for who in ['admin', 'tech', 'doctor']:
        case = f.visit('分享岗位' + who); before = f.snapshot(case)
        first, second = f.share(case, who), f.share(case, who, 30 * 24 * 60)
        assert first['token'] != second['token'] and f.read(case, first) == f.read(case, second)
        assert f.snapshot(case) == before
        assert f.revoke(case, who)['revoked'] == 2
        f.read(case, first, 4662); f.read(case, second, 4662)
        assert f.revoke(case, who)['revoked'] == 0
        renewed = f.share(case, who, 1); f.read(case, renewed)
        assert renewed['token'] not in {first['token'], second['token']}
        assert f.revoke(case, who)['revoked'] == 1
        f.read(case, renewed, 4662); assert f.snapshot(case) == before
    print('[1] Original ADMIN/TECHNICIAN/DOCTOR_OUTP share/revoke, six masked keys, default/max TTL and re-share preserved')

    case = f.visit('岗位拒绝'); link = f.share(case); before = f.snapshot(case)
    for who in ['nurse', 'cashier', 'pharmacist', 'quality', 'interface', None]:
        code = 1000 if who is None else 1005
        f.share(case, who, code=code); f.revoke(case, who, code)
        f.read(case, link); assert f.snapshot(case) == before
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    f.share(case, 'disabled', code=1000); f.revoke(case, 'disabled', 1000)
    f.api('GET', '/auth/me', who='disabled', code=1000)
    mover_token = f.tokens['mover']
    f.set_roles('mover', ['NURSE'])
    assert f.tokens['mover'] == mover_token
    f.share(case, 'mover', code=1005); f.revoke(case, 'mover', 1005)
    f.read(case, link); assert f.snapshot(case) == before
    for roles in [['TECHNICIAN'], ['ADMIN'], ['DOCTOR_OUTP'], ['PHARMACIST', 'TECHNICIAN']]:
        f.set_roles('mover', roles)
        assert f.tokens['mover'] == mover_token
        other = f.share(case, 'mover'); f.read(case, other)
        assert f.revoke(case, 'mover')['revoked'] == (2 if roles == ['TECHNICIAN'] else 1)
        f.read(case, other, 4662); assert f.snapshot(case) == before
    print('[2] Wrong-duty/anonymous/disabled writes rejected; same issued JWT follows TECH→NURSE denial and original three-duty/multi-role compatibility')

    for minutes in [0, -1, 43201, 52560000]:
        f.share(case, minutes=minutes, code=4663); assert f.snapshot(case) == before
    missing = {'exam_id': 9000000000000000}
    f.share(missing, code=4660); assert f.revoke(missing)['revoked'] == 0
    f.api('GET', '/share/v138-missing-' + f.suffix, who=None, code=4661)
    pending = f.visit('未审核', verified=False); before = f.snapshot(pending)
    f.share(pending, code=4660); assert f.revoke(pending)['revoked'] == 0 and f.snapshot(pending) == before
    f.report(pending); before = f.snapshot(pending)
    f.share(pending, code=4660); assert f.snapshot(pending) == before
    refund = f.api('POST', f"/outpatient/charges/{pending['charge']['id']}/refund", who='cashier')
    assert refund['status'] == 'REFUNDED'
    assert f.source(pending)['status'] == 'CREATED' and f.source(pending)['chargeId'] is None and f.worklist(pending) == []
    before = f.snapshot(pending)
    f.share(pending, code=4660); assert f.revoke(pending)['revoked'] == 0 and f.snapshot(pending) == before
    before = f.snapshot(expiry)
    f.api('POST', f"/outpatient/charges/{expiry['charge']['id']}/refund", who='cashier', code=5005)
    assert f.snapshot(expiry) == before
    print('[3] Same-employee verification, missing/unreviewed/refunded sources, invalid TTL and reviewed-source refund policy retained')

    remaining = 61.0 - (time.monotonic() - started)
    if remaining > 0:
        time.sleep(remaining)
    before = f.snapshot(expiry)
    f.read(expiry, expiry_link, 4662); f.read(expiry, expiry_link, 4662)
    assert f.revoke(expiry)['revoked'] == 0 and f.snapshot(expiry) == before
    print('[4] Actual minimum-TTL natural expiry refuses repeatedly without disclosing report content')
    print('e2e-v138-report-share-live-duty passed: real public JWT compatibility; no SQL, configuration mutation or claim of PostgreSQL race proof.')


if __name__ == '__main__':
    main()
