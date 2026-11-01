# -*- coding: utf-8 -*-
"""Real public JWT clinical field-search contract; no SQL or race claims.

Only exclusive public API fixtures are written. Read-only checks freeze the
complete exposed fixture workspaces, medical-record lists and template/field/
grant DTOs, not arbitrary SQL/audit/config tables. Audit is an intentional read
side effect and is proved independently by Java. No HTTP action runs at import
or in --selfcheck. Failed/unknown writes are never retried or cleaned up.
"""
import datetime
import json
import re
import sys
import time
import urllib.error
from urllib.parse import parse_qs, urlencode, urlsplit

from e2elib import call, login, q, today_bj

OUTER = {'fieldCode', 'items', 'total', 'limit', 'truncated'}
HIT = {'emrType', 'emrId', 'visitId', 'visitNo', 'patientId', 'patientName',
       'deptName', 'title', 'templateId', 'templateName', 'fieldValue', 'recordedAt'}
PARAMS = {'fieldCode', 'value', 'from', 'to', 'emrType'}
KINDS = ['TEXT', 'NUMBER', 'CHECKBOX', 'RADIO', 'MULTI', 'DATE']


def search_path(criteria):
    assert set(criteria) <= PARAMS
    return '/emr/field-search?' + urlencode({k: v for k, v in criteria.items() if v is not None})


def field_body(code_token, kind_token, position):
    assert kind_token in KINDS
    return {'fieldCode': code_token, 'label': 'V153模拟' + kind_token,
            'datatype': kind_token, 'required': False, 'sortNo': position,
            'valueSet': ['中', '重'] if kind_token == 'RADIO' else ['咳嗽', '咽痛'] if kind_token == 'MULTI' else [],
            'placeholder': 'V153模拟输入', 'unit': None, 'enabled': True}


def outp_body(template_id, field_values, label):
    return {'emr': {'chiefComplaint': label + '主诉', 'presentIllness': label + '现病史',
                    'pastHistory': '', 'physicalExam': label + '查体', 'advice': label + '处理'},
            'diagnoses': [], 'templateId': template_id, 'fields': field_values}


def inp_body(template_id, field_values, label):
    return {'recordType': 'PROGRESS', 'title': label + '病程', 'content': label + '正文',
            'templateId': template_id, 'fields': field_values}


def stamp(text):
    # PG/JSON may retain1..6 fractional digits; Windows Python accepts fewer forms.
    text = re.sub(r'\.(\d{1,6})(?=[+-]\d\d:\d\d$)', lambda m: '.' + m[1].ljust(6, '0'), text.replace('Z', '+00:00'))
    result = datetime.datetime.fromisoformat(text)
    return result.replace(tzinfo=datetime.timezone.utc) if result.tzinfo is None else result


def selfcheck():
    # Exercise the actual side-effect-free payload builders, never a business
    # permission fake or a stub transport. Fixture.__init__ is not invoked.
    for criteria in [{}, {'fieldCode': 'v153_x'}, {'fieldCode': 'v153_x', 'value': '100%_\\中'},
                     {'fieldCode': 'v153_x', 'emrType': ' inp '}, {'fieldCode': 'bad code', 'value': None},
                     {'fieldCode': 'v153_x', 'from': '2030-01-01', 'to': '2020-01-01'}]:
        parsed = parse_qs(urlsplit(search_path(criteria)).query, keep_blank_values=True)
        assert parsed == {k: [str(v)] for k, v in criteria.items() if v is not None}
    values = {'t': '中%_\\', 'n': 38.5, 'c': True, 'r': '中', 'm': ['咳嗽'], 'd': '2026-11-01'}
    for number, kind in enumerate(KINDS):
        built = field_body('v153_' + str(number), kind, number)
        assert len(built) == 9 and built['datatype'] == kind
        assert json.loads(json.dumps(built, ensure_ascii=False)) == built
    for built in [outp_body(71, values, '模拟'), inp_body(71, values, '模拟')]:
        assert built['templateId'] == 71 and built['fields'] is values
        assert json.loads(json.dumps(built, ensure_ascii=False)) == built
    for fraction in ['1', '12', '123', '1234', '12345', '123456']:
        assert stamp('2026-11-01T07:00:00.' + fraction + '+00:00').microsecond == int(fraction.ljust(6, '0'))
    print('PASS v153 pure payload/query/timestamp selfcheck; no fixture/HTTP/SQL instantiated')


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V153模拟' + self.suffix
        self.tokens, self.identities, self.names = {'admin': login()}, {}, {}
        self.unknown_writes, self.reads = [], 0
        self.identities['admin'] = self.api('GET', '/auth/me')
        self.today = today_bj()
        self.past = self.today - datetime.timedelta(days=7)
        for actor, duties in [('doctor', ['DOCTOR_OUTP']), ('other', ['DOCTOR_OUTP']),
                              ('author', ['ADMIN', 'DOCTOR_OUTP']), ('recordauthor', ['ADMIN', 'DOCTOR_OUTP']),
                              ('nurse', ['NURSE']), ('quality', ['QUALITY']), ('tech', ['TECHNICIAN']),
                              ('mixed', ['DOCTOR_OUTP', 'NURSE'])]:
            self.provision(actor, duties)
        self.dept = self.api('POST', '/system/depts', {'code': 'V153C' + self.suffix,
            'name': self.prefix + '临床科', 'type': 'CLINICAL', 'sortNo': 9999})['id']
        self.ward = self.api('POST', '/system/depts', {'code': 'V153W' + self.suffix,
            'name': self.prefix + '护理单元', 'type': 'NURSING', 'sortNo': 9999})['id']
        for actor in self.names:
            self.set_roles(actor, self.identities[actor]['roles'])
        self.api('POST', '/inpatient/beds', {'wardId': self.ward, 'count': 5})
        beds = self.api('GET', '/inpatient/beds?wardId=' + str(self.ward))
        assert len(beds) == 5 and all(row['status'] == 'FREE' for row in beds)
        self.template_request = {'name': self.prefix + '结构化模板', 'content': self.prefix + '模板原文',
            'templateType': 'EMR', 'scope': 'HOSPITAL', 'deptId': None, 'recordType': 'OUTP'}
        self.template = self.api('POST', '/emr-templates/scoped', self.template_request)
        assert type(self.template) is int
        self.codes = {kind: 'f153_' + self.suffix + '_' + str(index) for index, kind in enumerate(KINDS)}
        self.bulk_code = 'f153_' + self.suffix + '_bulk'
        for index, kind in enumerate(KINDS):
            self.api('POST', f'/emr/templates/{self.template}/fields', field_body(self.codes[kind], kind, index))
        self.api('POST', f'/emr/templates/{self.template}/fields', field_body(self.bulk_code, 'TEXT', 99))
        self.cases, self.records = {}, {}
        for key, date, doctor, writer in [('hidden', self.past, 'other', 'admin'),
                                           ('own', self.past, 'doctor', 'admin'),
                                           ('today', self.today, 'other', 'admin'),
                                           ('author', self.past, 'other', 'author')]:
            patient = self.patient(key)
            registration = self.register(patient, date, doctor)
            self.api('POST', f"/outpatient/doctor/{registration['id']}/start", {})
            values = self.values(key)
            saved = self.api('PUT', f"/outpatient/doctor/{registration['id']}/emr", outp_body(self.template, values, self.prefix + key), writer)
            assert saved['doctorId'] == self.identities[writer]['id']
            self.cases[key] = {'patient': patient, 'registration': registration}
            self.remember(key, 'OUTP', saved, values)
        self.cases['null'] = {'patient': self.patient('null')}
        for (key, case), bed in zip(self.cases.items(), beds):
            admission = self.api('POST', '/inpatient/admissions', {'patientId': case['patient'], 'deptId': self.dept,
                'bedId': bed['id'], 'doctorId': None if key == 'null' else self.identities['other']['id'], 'deposit': 0, 'payMethod': 'CASH'})
            case['admission'] = admission['id']
            self.record(key, self.values(key))
        authored = self.record('hidden', self.values('INP_AUTHOR_ONLY'), 'recordauthor')
        assert authored['doctorId'] == self.identities['recordauthor']['id']
        self.set_roles('author', ['DOCTOR_OUTP'])
        self.set_roles('recordauthor', ['DOCTOR_OUTP'])
        self.register(self.cases['own']['patient'], self.past, 'mixed')

    def api(self, method, path, body=None, who='admin', expected_code=0):
        def unknown():
            if method != 'GET': self.unknown_writes.append({'method': method, 'path': path})
        try:
            reply = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, expected_code) not in [(401, 1000), (403, 1005), (400, 4000)]:
                unknown(); raise
            try: reply = json.load(error)
            except Exception: unknown(); raise
        except Exception:
            unknown(); raise
        if not isinstance(reply, dict) or 'code' not in reply:
            unknown(); raise AssertionError('Known envelope required; never retry unknown writes')
        assert reply['code'] == expected_code, (who, method, path, expected_code, reply)
        if expected_code: assert reply.get('data') is None
        return reply.get('data')

    def provision(self, actor_key, duties):
        username = 'v153_' + actor_key + '_' + self.suffix
        self.names[actor_key] = username
        self.api('POST', '/system/users', {'username': username, 'password': 'Init@1234', 'realName': self.prefix + actor_key, 'roleCodes': duties})
        self.tokens[actor_key] = self.api('POST', '/auth/login', {'username': username, 'password': 'Init@1234'})['token']
        self.api('POST', '/auth/change-password', {'oldPassword': 'Init@1234', 'newPassword': 'Work@1234'}, actor_key)
        self.tokens[actor_key] = self.api('POST', '/auth/login', {'username': username, 'password': 'Work@1234'})['token']
        self.identities[actor_key] = self.api('GET', '/auth/me', who=actor_key)

    def set_roles(self, actor_key, duties):
        person = self.identities[actor_key]
        self.api('PUT', '/system/users/' + str(person['id']), {'username': self.names[actor_key], 'realName': person['realName'],
            'title': person.get('title'), 'phone': person.get('phone'), 'deptId': self.dept, 'roleCodes': duties})
        self.identities[actor_key] = self.api('GET', '/auth/me', who=actor_key)
        assert set(self.identities[actor_key]['roles']) == set(duties)

    def patient(self, label):
        return self.api('POST', '/patients', {'name': self.prefix + label, 'sex': 'U'})['id']

    def register(self, patient_id, date, doctor_key):
        schedule = self.api('POST', '/outpatient/schedules', {'deptId': self.dept, 'doctorId': self.identities[doctor_key]['id'],
            'scheduleDate': date.isoformat(), 'fee': 0, 'capacity': 5})
        registered = self.api('POST', '/outpatient/registrations', {'patientId': patient_id, 'scheduleId': schedule['id']})
        assert registered['visitDate'] == date.isoformat()
        assert registered['doctorName'] == self.identities[doctor_key]['realName']
        return registered

    def values(self, label):
        return {self.codes['TEXT']: self.prefix + label + '100%_\\临床值', self.codes['NUMBER']: 38.5,
                self.codes['CHECKBOX']: True, self.codes['RADIO']: '中', self.codes['MULTI']: ['咳嗽', '咽痛'], self.codes['DATE']: self.today.isoformat()}

    def remember(self, case_key, source_kind, record, values):
        self.records[(source_kind, record['id'])] = {'case': case_key, 'values': dict(values), 'record': record}

    def record(self, case_key, values, who='admin'):
        saved = self.api('POST', f"/inpatient/admissions/{self.cases[case_key]['admission']}/records",
            inp_body(self.template, values, self.prefix + case_key), who)
        self.remember(case_key, 'INP', saved, values)
        return saved

    def ids(self, case_keys, source_kinds=('OUTP', 'INP'), code_token=None):
        code_token = code_token or self.codes['TEXT']
        return {key for key, value in self.records.items() if value['case'] in case_keys and key[0] in source_kinds and code_token in value['values']}

    def search(self, criteria, who='admin', expected_code=0):
        result = self.api('GET', search_path(criteria), who=who, expected_code=expected_code)
        self.reads += 1
        if expected_code: return None
        assert set(result) == OUTER and result['fieldCode'] == criteria['fieldCode'].strip()
        assert result['limit'] == 200 and type(result['truncated']) is bool
        assert result['total'] == len(result['items']) <= 200
        instants = []
        for row in result['items']:
            assert set(row) == HIT and row['emrType'] in ['OUTP', 'INP']
            assert type(row['visitNo']) is (int if row['emrType'] == 'OUTP' else str)
            assert type(row['fieldValue']) is str and type(row['recordedAt']) is str
            instants.append(stamp(row['recordedAt']))
            known = self.records[(row['emrType'], row['emrId'])]
            assert row['patientId'] == self.cases[known['case']]['patient']
            assert row['templateId'] == self.template and row['templateName'] == self.template_request['name']
            expected = known['values'][result['fieldCode']]
            if isinstance(expected, list): assert json.loads(row['fieldValue']) == expected
            elif type(expected) is bool: assert row['fieldValue'] == ('true' if expected else 'false')
            else: assert row['fieldValue'] == str(expected)
        assert instants == sorted(instants, reverse=True)
        return result

    def hits(self, expected_ids, who='admin', criteria=None, truncated=False):
        result = self.search(criteria or {'fieldCode': self.codes['TEXT']}, who)
        assert {(row['emrType'], row['emrId']) for row in result['items']} == expected_ids
        assert result['truncated'] is truncated
        return result

    def snapshot(self):
        clinical = {}
        for case_key, case in self.cases.items():
            clinical[case_key] = {'inp': self.api('GET', f"/inpatient/admissions/{case['admission']}/workspace"),
                'records': self.api('GET', f"/inpatient/admissions/{case['admission']}/records")}
            if 'registration' in case:
                clinical[case_key]['outp'] = self.api('GET', f"/outpatient/doctor/{case['registration']['id']}/workspace")
        heads = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword=' + q(self.prefix))
        own_head = [row for row in heads if row['id'] == self.template]
        assert len(own_head) == 1, 'Read the exact owner-visible metadata, including a disabled parent'
        return {'clinical': clinical, 'template': own_head[0],
            'fields': self.api('GET', f'/emr/templates/{self.template}/fields?includeDisabled=true'),
            'grants': self.api('GET', f'/emr-templates/{self.template}/grants')}


def main():
    selfcheck()
    f = Fixture()
    all_cases = ['hidden', 'own', 'today', 'author', 'null']
    all_ids = f.ids(all_cases)
    before = f.snapshot()
    for who in ['admin', 'quality']: f.hits(all_ids, who)
    f.hits(f.ids(['own', 'today']), 'doctor')
    f.hits(f.ids(['author', 'today']), 'author')
    f.hits(f.ids(['today']), 'recordauthor')
    f.hits(f.ids(all_cases, ['INP']), 'nurse')
    f.hits(set(), 'nurse', {'fieldCode': f.codes['TEXT'], 'emrType': 'OUTP'})
    f.search({'fieldCode': f.codes['TEXT']}, 'tech', 1005)
    assert f.snapshot() == before
    for duties, cases, sources in [(['DOCTOR_OUTP', 'NURSE'], all_cases, ['INP']),
                                    (['DOCTOR_OUTP', 'TECHNICIAN'], ['own', 'today'], ['OUTP', 'INP']),
                                    (['NURSE', 'TECHNICIAN'], all_cases, ['INP']),
                                    (['QUALITY', 'TECHNICIAN'], all_cases, ['OUTP', 'INP'])]:
        f.set_roles('mixed', duties)
        expected = f.ids(cases, sources)
        if duties == ['DOCTOR_OUTP', 'NURSE']: expected |= f.ids(['own', 'today'])
        before = f.snapshot(); f.hits(expected, 'mixed'); assert f.snapshot() == before
    # Public relationship changes happen outside each read-only observation.
    attending = f"/inpatient/admissions/{f.cases['hidden']['admission']}/attending-doctor"
    f.api('PUT', attending, {'doctorId': f.identities['doctor']['id']})
    before = f.snapshot(); f.hits(f.ids(['hidden', 'own', 'today']), 'doctor'); assert f.snapshot() == before
    f.api('PUT', attending, {'doctorId': f.identities['other']['id']})
    before = f.snapshot(); f.hits(f.ids(['own', 'today']), 'doctor'); assert f.snapshot() == before
    qualification = f.register(f.cases['hidden']['patient'], f.today, 'doctor')
    before = f.snapshot(); f.hits(f.ids(['hidden', 'own', 'today']), 'doctor'); assert f.snapshot() == before
    f.api('PUT', '/outpatient/registrations/' + str(qualification['id']) + '/cancel')
    before = f.snapshot(); f.hits(f.ids(['own', 'today']), 'doctor'); assert f.snapshot() == before
    # All six value kinds remain textual and the public matching/filter contract stays original.
    before = f.snapshot()
    for code_token in f.codes.values(): f.hits(all_ids, criteria={'fieldCode': code_token})
    f.hits(f.ids(all_cases, ['OUTP']), criteria={'fieldCode': f.codes['TEXT'], 'emrType': ' outp '})
    f.hits(f.ids(all_cases, ['INP']), criteria={'fieldCode': f.codes['TEXT'], 'emrType': ' inp '})
    for literal in ['%', '_', '\\']:
        f.hits(all_ids, criteria={'fieldCode': f.codes['TEXT'], 'value': literal})
    for literal in ["' or 1=1 --", "'; drop table outp_emr; --"]:
        f.hits(set(), criteria={'fieldCode': f.codes['TEXT'], 'value': literal})
    f.hits(all_ids, criteria={'fieldCode': ' ' + f.codes['TEXT'] + ' ', 'value': ' 100%_\\ '})
    f.hits(all_ids, criteria={'fieldCode': f.codes['TEXT'], 'from': '2000-01-01', 'to': '2100-01-01'})
    f.hits(set(), criteria={'fieldCode': f.codes['TEXT'], 'from': (f.today + datetime.timedelta(days=3)).isoformat()})
    f.hits(set(), criteria={'fieldCode': f.codes['TEXT'], 'to': (f.today - datetime.timedelta(days=3)).isoformat()})
    f.hits(set(), criteria={'fieldCode': 'unused_' + f.suffix})
    for criteria in [{'fieldCode': ''}, {'fieldCode': 'bad code'}, {'fieldCode': 'a' * 65},
                     {'fieldCode': "x'; drop table outp_emr; --"}, {'fieldCode': f.codes['TEXT'], 'value': 'x' * 129},
                     {'fieldCode': f.codes['TEXT'], 'emrType': 'EMERGENCY'},
                     {'fieldCode': f.codes['TEXT'], 'from': f.today.isoformat(), 'to': f.past.isoformat()}]:
        f.search(criteria, expected_code=4028)
    for criteria in [{}, {'fieldCode': f.codes['TEXT'], 'from': 'not-a-date'}]: f.search(criteria, expected_code=4000)
    assert f.snapshot() == before
    # One allowed and202 hidden matches prove public count/truncation behavior.
    # POST order does not prove wall-clock order on this host. The strict
    # timestamp/cutoff counterexample belongs to the independent Java suite.
    # No SQL insertion, shared bed, sleep, barrier or race claim.
    legal = f.record('own', {f.bulk_code: f.prefix + '合法'})
    for index in range(199): f.record('hidden', {f.bulk_code: f.prefix + '隐藏' + str(index)})
    before = f.snapshot()
    f.hits({('INP', legal['id'])}, 'doctor', {'fieldCode': f.bulk_code})
    f.hits(f.ids(all_cases, ['INP'], f.bulk_code), 'nurse', {'fieldCode': f.bulk_code})
    assert f.snapshot() == before
    for index in range(199, 202): f.record('hidden', {f.bulk_code: f.prefix + '隐藏' + str(index)})
    before = f.snapshot()
    f.hits({('INP', legal['id'])}, 'doctor', {'fieldCode': f.bulk_code})
    limited = f.search({'fieldCode': f.bulk_code, 'emrType': 'INP'}, 'nurse')
    assert limited['total'] == 200 and limited['truncated'] is True
    assert {(r['emrType'], r['emrId']) for r in limited['items']} <= f.ids(all_cases, ['INP'], f.bulk_code)
    f.hits(set(), 'nurse', {'fieldCode': f.bulk_code, 'emrType': 'OUTP'})
    assert f.snapshot() == before
    # Historical clinical values retain their original parent metadata even
    # when the current template is private and disabled for future use.
    private = dict(f.template_request); private['scope'] = 'PERSONAL'
    f.api('PUT', '/emr-templates/' + str(f.template), private)
    f.api('PUT', f'/emr-templates/{f.template}/disable')
    before = f.snapshot()
    f.api('GET', '/emr-templates/' + str(f.template), expected_code=4066)
    f.api('GET', '/emr-templates/' + str(f.template), who='nurse', expected_code=4066)
    f.hits(f.ids(all_cases, ['INP']), 'nurse')
    f.hits(f.ids(['own', 'today']), 'doctor')
    assert f.snapshot() == before
    assert f.today == today_bj(), 'Crossed the Beijing business day; do not classify as a policy failure'
    assert not f.unknown_writes
    print('PASS v153 public JWT field-search: ' + json.dumps({'checked_reads': f.reads, 'outer_keys': 5, 'hit_keys': 12,
        'bulk_hidden_rows': 202, 'bulk_allowed_rows': 1, 'unknown_writes': f.unknown_writes,
        'clinical_dto_snapshots_only': True, 'sql_or_jdbc_race_claim': False}, ensure_ascii=False))


if __name__ == '__main__':
    assert sys.argv[1:] in [[], ['--selfcheck']]
    selfcheck() if sys.argv[1:] else main()
