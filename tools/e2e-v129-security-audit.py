# -*- coding: utf-8 -*-
"""Real-HTTP audit accuracy across method/chain refusals and ordinary requests.

All employees and the one clinical case are private public-API fixtures. No SQL
or shared configuration mutation is used. The seven public audit columns are
checked: known actor/method/path/status, positive allocated ID, the actual local
HTTP socket address, and a bounded UTC request/HTTP-Date time window. Every old
row returned for that employee is retained exactly. Anonymous/login checks use
a bounded global tail window because the API has no NULL-username/path filter.
There is no new lock/concurrency claim. The browser/full-ledger acceptance is
separate from these sequential HTTP checks.
"""
import datetime as dt
from email.utils import parsedate_to_datetime
import http.client
import ipaddress
import json
import time
import urllib.error
from urllib.parse import urlencode, urlsplit

from e2elib import BASE, call, login, provision_user, today_bj

AUDIT_KEYS = {'id', 'username', 'method', 'path', 'http_status', 'client_ip', 'created_at'}
CLOCK_TOLERANCE = dt.timedelta(seconds=2)


class Fixture:
    def __init__(self, prefix='v129'):
        self.suffix = str(time.time_ns())[-11:]
        self.users = {'admin': prefix + '_admin_' + self.suffix}
        self.tokens = {'admin': provision_user(login(), self.users['admin'], 'ADMIN')}
        self.identities = {'admin': self.api('GET', '/auth/me')}
        for who, role in [('tech', 'TECHNICIAN'), ('cashier', 'CASHIER'), ('interface', 'INTERFACE')]:
            name = prefix + '_' + who + '_' + self.suffix
            self.users[who] = name
            self.tokens[who] = provision_user(self.tokens['admin'], name, 'TECHNICIAN' if role == 'INTERFACE' else role)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
            if role == 'INTERFACE':
                self.api('PUT', f"/system/users/{self.identities[who]['id']}", {
                    'username': name, 'realName': name, 'roleCodes': ['INTERFACE']})
                self.tokens[who] = login(name, 'Work@1234')
        clinic = self.api('POST', '/system/depts', {
            'code': 'V129D' + self.suffix, 'name': 'V129模拟门诊' + self.suffix, 'type': 'CLINICAL', 'sortNo': 9999})
        medtech = self.api('POST', '/system/depts', {
            'code': 'V129E' + self.suffix, 'name': 'V129模拟影像科' + self.suffix, 'type': 'MEDTECH', 'sortNo': 9999})
        schedule = self.api('POST', '/outpatient/schedules', {
            'deptId': clinic['id'], 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': 5})
        code = 'V129EXAM' + self.suffix
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'{code},V129模拟超声{self.suffix},EXAM,次,1.00,,,{medtech["code"]}\n')
        assert imported['imported'] == 1 and imported['errorCount'] == 0
        item = next(row for row in self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix) if row['code'] == code)
        patient = self.api('POST', '/patients', {
            'name': 'V129模拟审计' + self.suffix, 'sex': 'U', 'phone': '13900001290'})
        reg = self.api('POST', '/outpatient/registrations', {'patientId': patient['id'], 'scheduleId': schedule['id']})
        self.api('POST', f"/outpatient/doctor/{reg['id']}/start")
        orders = self.api('POST', f"/outpatient/doctor/{reg['id']}/orders", {'lines': [{
            'orderType': 'EXAM', 'itemId': item['id'], 'qty': 1,
            'clinicalSummary': 'V129模拟摘要', 'examPurpose': 'V129模拟目的'}]})
        assert len(orders) == 1
        charge = self.api('POST', '/outpatient/charges/settle', {'registrationId': reg['id'], 'payMethod': 'CASH'})
        assert charge['status'] == 'PAID'
        rows = [row for row in self.api('GET', '/ris/worklist') if row['group_no'] == orders[0]['groupNo']]
        assert len(rows) == 1 and rows[0]['status'] == 'REGISTERED'
        self.case = {'patient': patient, 'registration': reg, 'order': orders[0], 'charge': charge, 'exam_id': rows[0]['id']}
        self.api('PUT', f"/ris/exams/{rows[0]['id']}/arrive")
        portal = self.api('POST', '/portal/login', {'patientNo': patient['patientNo'], 'phone': '13900001290'}, who=None)
        self.tokens['portal'] = portal['token']

    def api(self, method, path, body=None, who='admin', code=0, text=None):
        try:
            result = call(method, path, body, self.tokens.get(who), text=text)
        except urllib.error.HTTPError as error:
            assert (code, error.code) in [(1000, 401), (1005, 403)], (method, path, code, error.code)
            result = json.load(error)
        assert result['code'] == code, (method, path, code, result.get('code'), result.get('message'))
        if code:
            assert result.get('data') is None
        return result.get('data')

    def clinical(self):
        c = self.case
        workspace = self.api('GET', f"/outpatient/doctor/{c['registration']['id']}/workspace")
        return {
            'source': next(row for row in workspace['orders'] if row['id'] == c['order']['id']),
            'bill': [row for row in self.api('GET', '/outpatient/charges/recent') if row['chargeId'] == c['charge']['id']],
            'exam': [row for row in self.api('GET', '/ris/worklist') if row['id'] == c['exam_id']],
            'results': self.api('GET', f"/outpatient/lab-results?orderId={c['order']['id']}"),
            'execution': self.api('GET', f"/outpatient/exec/reports?registrationId={c['registration']['id']}"),
            'alerts': [row for row in self.api('GET', '/outpatient/critical-alerts?status=NEW') if row['patientNo'] == c['patient']['patientNo']],
            'cdr': self.api('GET', f"/cdr/patients/{c['patient']['id']}/documents"),
        }


def timestamp(value):
    assert isinstance(value, str)
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    assert result.tzinfo is not None, 'Audit timestamp must identify its timezone'
    return result.astimezone(dt.timezone.utc)


class Audit:
    def __init__(self, fixture):
        self.f = fixture
        self.initial = {}
        self.observed = []

    def page(self, username=None, page=1):
        params = {'page': page, 'size': 200}
        if username is not None:
            params['username'] = username
        data = self.f.api('GET', '/audit/logs?' + urlencode(params))
        assert set(data) == {'list', 'total'} and type(data['total']) is int and data['total'] >= 0
        assert isinstance(data['list'], list) and all(set(row) == AUDIT_KEYS for row in data['list'])
        ids = [row['id'] for row in data['list']]
        assert all(type(value) is int and value > 0 for value in ids) and ids == sorted(set(ids), reverse=True)
        return data

    def employee_rows(self, username):
        first = self.page(username)
        rows = first['list']
        for page in range(2, (first['total'] + 199) // 200 + 1):
            following = self.page(username, page)
            assert following['total'] == first['total'], 'Employee audit changed during pagination'
            rows.extend(following['list'])
        assert len(rows) == first['total'] and all(row['username'] == username for row in rows)
        assert len({row['id'] for row in rows}) == len(rows)
        return {'total': first['total'], 'list': rows}

    def before(self, username):
        data = self.page() if username is None else self.employee_rows(username)
        self.initial.setdefault(username, {row['id']: row for row in data['list']})
        return data

    def after(self, username, before, count):
        if username is not None:
            data = self.employee_rows(username)
        else:
            data = self.page()
            # There is no server-side NULL-username or path filter. Retain every
            # previously observed tail row, including any row pushed to page 2.
            low = min((row['id'] for row in before['list']), default=0)
            page = 1
            while low and data['list'] and min(row['id'] for row in data['list']) > low:
                page += 1
                more = self.page(page=page)
                assert more['total'] == data['total'], 'Global tail changed during pagination'
                assert more['list'], 'Previously observed audit row disappeared'
                data['list'].extend(more['list'])
        old = {row['id']: row for row in before['list']}
        current = {row['id']: row for row in data['list']}
        assert len(current) == len(data['list'])
        assert all(current.get(key) == row for key, row in old.items()), 'An old seven-column audit row changed'
        assert data['total'] == before['total'] + count, ('Unexpected audit cardinality', username, before['total'], data['total'], count)
        maximum = max(old, default=0)
        added = [row for row in data['list'] if row['id'] > maximum]
        assert len(added) == count, ('Request must allocate exactly its expected audit rows', username, count, added)
        return added

    def request(self, method, path, who, code=0, status=200, body=None, recorded_path=None, count=1, actor=None):
        username = self.f.users.get(who) if actor is None and who is not None else actor
        if who == 'portal':
            username = 'portal:' + str(self.f.case['patient']['id'])
        before = self.before(username)
        target = urlsplit(BASE)
        connection_type = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
        connection = connection_type(target.hostname, target.port, timeout=20)
        headers = {'Content-Type': 'application/json; charset=utf-8'}
        if who is not None:
            headers['Authorization'] = 'Bearer ' + self.f.tokens[who]
        data = None if body is None else json.dumps(body).encode('utf8')
        try:
            connection.connect()
            expected_ip = ipaddress.ip_address(connection.sock.getsockname()[0])
            start = dt.datetime.now(dt.timezone.utc)
            connection.request(method, target.path.rstrip('/') + path, body=data, headers=headers)
            response = connection.getresponse()
            actual_status = response.status
            date = response.getheader('Date')
            result = json.loads(response.read().decode('utf8'))
            end = dt.datetime.now(dt.timezone.utc)
        finally:
            connection.close()
        assert actual_status == status and result['code'] == code, (method, path, actual_status, status, result.get('code'), code)
        if code:
            assert result.get('data') is None, 'Refusal must not return clinical payload'
        added = self.after(username, before, count)
        for row in added:
            assert row['username'] == username and row['method'] == method
            assert row['path'] == (recorded_path or '/api' + path)
            assert type(row['http_status']) is int and row['http_status'] == status
            assert isinstance(row['client_ip'], str) and ipaddress.ip_address(row['client_ip']) == expected_ip
            instant = timestamp(row['created_at'])
            assert start - CLOCK_TOLERANCE <= instant <= end + CLOCK_TOLERANCE, 'Audit time is outside this request UTC window'
            assert date, 'A real HTTP Date is required for the separate server-clock bound'
            server_date = parsedate_to_datetime(date).astimezone(dt.timezone.utc)
            assert server_date - CLOCK_TOLERANCE <= instant <= server_date + dt.timedelta(seconds=1) + CLOCK_TOLERANCE
            self.observed.append(row)
        return result.get('data')

    def verify_old_rows(self):
        for username, old in self.initial.items():
            if username is None:
                # Later, already allocated requests legitimately added rows.
                # Here check retention only, without miscounting them as new
                # requests or weakening the per-request cardinality checks.
                data = self.page()
                current = {row['id']: row for row in data['list']}
                page = 1
                while not set(old).issubset(current):
                    page += 1
                    following = self.page(page=page)
                    assert following['total'] == data['total'] and following['list']
                    current.update({row['id']: row for row in following['list']})
                assert all(current[key] == row for key, row in old.items())
            else:
                current = {row['id']: row for row in self.employee_rows(username)['list']}
                assert all(current.get(key) == row for key, row in old.items())


def main():
    f = Fixture()
    audit = Audit(f)
    c = f.case
    report_path = f"/ris/exams/{c['exam_id']}/report"
    body = {'findings': 'V129模拟所见' + f.suffix, 'impression': 'V129模拟结论' + f.suffix}
    initial = f.clinical()
    audit.request('PUT', report_path, 'cashier', 1005, 403, body)
    assert f.clinical() == initial
    audit.request('GET', '/audit/logs', 'cashier', 1005, 403)
    assert f.clinical() == initial
    print('[1] Real method-level cashier RIS/audit-query refusals each leave one terminal 403 audit row and preserve clinical/financial projections')

    audit.request('PUT', report_path, 'tech', body=body)
    saved = f.clinical()
    assert saved == {**initial, 'exam': [{**initial['exam'][0], 'status': 'REPORTED', **body}]}
    audit.request('PUT', '/ris/exams/9000000000000000/report', 'tech', 9944, 200, body)
    assert f.clinical() == saved
    audit.request('GET', '/ris/pacs-info', 'tech', count=0)
    print('[2] Legal report success and ordinary business refusal keep one HTTP-200 audit each; non-sensitive successful GET stays unlogged')

    for who in ['portal', 'interface']:
        audit.request('PUT', report_path, who, 1005, 403, body)
        assert f.clinical() == saved
    print('[3] Real patient-subject and machine-only tokens retain chain-level 403 attribution without duplicate rows or clinical payload')

    for raw, masked in [('13900001290', '139****1290'), ('110101199001010019', '1101****0019')]:
        audit.request('GET', '/patients?keyword=' + raw, 'admin', recorded_path='/api/patients?keyword=' + masked)
    # Login remains an unauthenticated request: retain the original NULL actor;
    # never invent employee attribution from a credential body's username.
    logged_in = audit.request('POST', '/auth/login', None, body={'username': f.users['tech'], 'password': 'Work@1234'})
    assert isinstance(logged_in['token'], str) and logged_in['token']
    print('[4] Sensitive read query masking and normal unauthenticated login audit semantics retain all seven exact columns')

    # Respect the existing production-default per-IP 10-second anonymous window.
    # No system property/config key is changed. Only these two harmless denied
    # requests are sent after a quiet cooldown, and the second must be throttled.
    time.sleep(10.1)
    start = time.monotonic()
    audit.request('PUT', report_path, None, 1000, 401, body)
    audit.request('PUT', report_path, None, 1000, 401, body, count=0)
    assert time.monotonic() - start < 10, 'Throttle probe exceeded the existing default window'
    assert f.clinical() == saved
    audit.verify_old_rows()
    print('[5] Anonymous 401 still observes the unchanged default IP throttle; every previously observed employee/global-tail audit row keeps all seven original values')
    print('e2e-v129-security-audit passed: actual HTTP method/chain refusals, success/business-error/read/login/throttle boundaries, exact per-request audit cardinality and all seven audit columns with explicit socket-IP and UTC/HTTP-Date bounds. Only public fixtures; no SQL, shared configuration mutation or new concurrency claim. The anonymous/login scope is the complete new global tail and every previously observed tail row, not an unbounded historical-table snapshot.')


if __name__ == '__main__':
    main()
