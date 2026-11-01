# -*- coding: utf-8 -*-
"""Public JWT history-list/body/copy policy, using exclusive API fixtures only.

Read/copy refusals freeze all exposed fixture clinical workspaces/records and
template/grant projections. These are public DTOs, not arbitrary SQL/audit
facts. Deterministic SQL snapshot windows are proved by the separate Java
suite. No SQL, retries, missing-user actors or HTTP actions at import time.
"""
import copy
import datetime
import json
import time
import urllib.error
from e2elib import call, login, q, today_bj

MISSING = 9000000000000000
LIST_KEYS = {'source', 'record_id', 'record_type', 'title', 'created_at', 'visit_no', 'dept_name', 'doctor_name', 'content_length'}
CONTENT_KEYS = {'source', 'recordId', 'recordType', 'title', 'content', 'createdAt'}
COPY_KEYS = {'id', 'scope', 'recordType', 'contentLength', 'sourceLength', 'truncated', 'maxContent'}


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V152模拟' + self.suffix
        self.tokens, self.identities, self.names = {'admin': login()}, {}, {}
        self.sequence, self.reads, self.refusals = 0, 0, 0
        self.unknown_writes, self.known_templates = [], []
        self.identities['admin'] = self.api('GET', '/auth/me')
        self.today = today_bj()
        self.past = self.today - datetime.timedelta(days=7)
        for who, roles in [('doctor', ['DOCTOR_OUTP']), ('other', ['DOCTOR_OUTP']),
                           ('author', ['ADMIN', 'DOCTOR_OUTP']), ('recordauthor', ['ADMIN', 'DOCTOR_OUTP']),
                           ('nurse', ['NURSE']), ('quality', ['QUALITY']), ('tech', ['TECHNICIAN']),
                           ('mixed', ['DOCTOR_OUTP', 'NURSE'])]:
            self.provision(who, roles)
        self.dept = self.api('POST', '/system/depts', {'code': 'V152C' + self.suffix,
            'name': self.prefix + '临床科', 'type': 'CLINICAL', 'sortNo': 9999})['id']
        self.ward = self.api('POST', '/system/depts', {'code': 'V152W' + self.suffix,
            'name': self.prefix + '护理单元', 'type': 'NURSING', 'sortNo': 9999})['id']
        for who in self.names:
            self.set_roles(who, self.identities[who]['roles'])
        self.api('POST', '/inpatient/beds', {'wardId': self.ward, 'count': 4})
        beds = self.api('GET', '/inpatient/beds?wardId=' + str(self.ward))
        assert len(beds) == 4 and all(b['status'] == 'FREE' for b in beds)
        self.cases, self.sources = {}, {}
        for key, date, doctor, author in [('past', self.past, 'other', 'author'),
                                          ('own', self.past, 'doctor', 'admin'),
                                          ('today', self.today, 'other', 'admin')]:
            pid = self.patient(key)
            reg = self.register(pid, date, doctor)
            self.api('POST', '/outpatient/doctor/' + str(reg['id']) + '/start', {})
            emr = {'chiefComplaint': self.prefix + key + '主诉', 'presentIllness': self.prefix + key + '现病史',
                   'pastHistory': '', 'physicalExam': self.prefix + key + '查体', 'advice': self.prefix + key + '处理'}
            saved = self.api('PUT', '/outpatient/doctor/' + str(reg['id']) + '/emr', {'emr': emr, 'diagnoses': []}, author)
            assert saved['doctorId'] == self.identities[author]['id']
            case = {'patient': pid, 'registration': reg, 'outp': saved, 'emr': emr}
            self.cases[key] = case
            content = '\n'.join(label + emr[field] for field, label in [('chiefComplaint', '主诉：'),
                ('presentIllness', '现病史：'), ('pastHistory', '既往史：'), ('physicalExam', '体格检查：'), ('advice', '处理意见：')] if emr[field])
            self.sources[(key, 'OUTP')] = {'id': saved['id'], 'body': content, 'recordType': 'OUTP'}
        self.cases['null'] = {'patient': self.patient('无主管')}
        for key, bed in zip(['past', 'own', 'today', 'null'], beds):
            case = self.cases[key]
            admission = self.api('POST', '/inpatient/admissions', {'patientId': case['patient'], 'deptId': self.dept,
                'bedId': bed['id'], 'doctorId': None if key == 'null' else self.identities['other']['id'], 'deposit': 100, 'payMethod': 'CASH'})
            case['admission'] = admission['id']
            body = self.prefix + key + '原住院正文'
            record = self.add_record(key, body)
            self.sources[(key, 'INP')] = {'id': record['id'], 'body': body, 'recordType': 'PROGRESS'}
        # A true OUTP-author-only relation: author wrote the past foreign visit
        # while ADMIN, then loses ADMIN. There is no today/own-registration
        # shortcut. INP authorship alone is independently NOT a patient grant.
        inp_authored = self.add_record('past', self.prefix + '仅住院书写人', who='recordauthor')
        assert inp_authored['doctorId'] == self.identities['recordauthor']['id']
        self.set_roles('author', ['DOCTOR_OUTP'])
        self.set_roles('recordauthor', ['DOCTOR_OUTP'])
        self.register(self.cases['own']['patient'], self.past, 'mixed')
        self.long_record = self.add_record('past', (self.prefix + '长正文。') * 220)
        assert len(self.long_record['content']) > 4000
        self.cases['empty'] = {'patient': self.patient('空门诊')}
        reg = self.register(self.cases['empty']['patient'], self.today, 'other')
        self.api('POST', '/outpatient/doctor/' + str(reg['id']) + '/start', {})
        saved = self.api('PUT', '/outpatient/doctor/' + str(reg['id']) + '/emr', {'emr': {
            'chiefComplaint': '', 'presentIllness': '', 'pastHistory': '', 'physicalExam': '', 'advice': ''}, 'diagnoses': []})
        self.cases['empty'].update(registration=reg, outp=saved)
        self.sources[('empty', 'OUTP')] = {'id': saved['id'], 'body': '', 'recordType': 'OUTP'}
        self.empty_patient = self.patient('无病历')
        for key in ['past', 'own']:
            assert self.cases[key]['registration']['visitDate'] == self.past.isoformat() != self.today.isoformat()
        assert self.cases['today']['registration']['visitDate'] == self.today.isoformat()

    def api(self, method, path, body=None, who='admin', code=0):
        try:
            response = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, code) not in [(401, 1000), (403, 1005), (400, 4000)]:
                if method != 'GET': self.unknown_writes.append({'method': method, 'path': path})
                raise
            response = json.load(error)
        except Exception:
            if method != 'GET': self.unknown_writes.append({'method': method, 'path': path})
            raise
        assert isinstance(response, dict) and 'code' in response, 'Known envelope required; never retry a write'
        assert response['code'] == code, (who, method, path, code, response)
        if code: assert response.get('data') is None, 'A known refusal must disclose no clinical/template data'
        return response.get('data')

    def provision(self, who, roles):
        name = 'v152_' + who + '_' + self.suffix
        self.names[who] = name
        self.api('POST', '/system/users', {'username': name, 'password': 'Init@1234', 'realName': self.prefix + who, 'roleCodes': roles})
        first = self.api('POST', '/auth/login', {'username': name, 'password': 'Init@1234'})
        self.tokens[who] = first['token']
        self.api('POST', '/auth/change-password', {'oldPassword': 'Init@1234', 'newPassword': 'Work@1234'}, who)
        self.tokens[who] = self.api('POST', '/auth/login', {'username': name, 'password': 'Work@1234'})['token']
        self.identities[who] = self.api('GET', '/auth/me', who=who)

    def set_roles(self, who, roles):
        person = self.identities[who]
        self.api('PUT', '/system/users/' + str(person['id']), {'username': self.names[who], 'realName': person['realName'],
            'title': None, 'phone': None, 'deptId': self.dept, 'roleCodes': roles})
        self.identities[who] = self.api('GET', '/auth/me', who=who)
        assert set(self.identities[who]['roles']) == set(roles)

    def patient(self, label):
        return self.api('POST', '/patients', {'name': self.prefix + label, 'sex': 'U'})['id']

    def register(self, pid, date, doctor):
        schedule = self.api('POST', '/outpatient/schedules', {'deptId': self.dept, 'doctorId': self.identities[doctor]['id'],
            'scheduleDate': date.isoformat(), 'fee': 0, 'capacity': 5})
        registered = self.api('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': schedule['id']})
        assert registered['visitDate'] == date.isoformat()
        assert registered['doctorName'] == self.identities[doctor]['realName']
        return registered

    def add_record(self, key, body, who='admin'):
        return self.api('POST', '/inpatient/admissions/' + str(self.cases[key]['admission']) + '/records',
            {'recordType': 'PROGRESS', 'title': self.prefix + key + '病程', 'content': body}, who)

    def listing(self, key, who, code=0, tail=''):
        rows = self.api('GET', '/emr-templates/prior-records?patientId=' + str(self.cases[key]['patient']) + tail, who=who, code=code)
        if code == 0:
            assert isinstance(rows, list)
            assert all(set(row) == LIST_KEYS for row in rows)
        self.reads += 1
        return rows

    def body(self, key, source, who, code=0, record_id=None, patient_id=None):
        case = self.cases[key]
        rid = self.sources[(key, source)]['id'] if record_id is None else record_id
        pid = case['patient'] if patient_id is None else patient_id
        row = self.api('GET', '/emr-templates/prior-records/content?patientId=' + str(pid) + '&source=' + q(source) + '&recordId=' + str(rid), who=who, code=code)
        if code == 0:
            assert set(row) == CONTENT_KEYS and row['recordId'] == rid and row['source'] == source.strip().upper()
            if record_id is None: assert row['content'] == self.sources[(key, source)]['body']
        self.reads += 1
        return row

    def copy_body(self, key, source, **changes):
        self.sequence += 1
        body = {'source': source, 'recordId': self.sources[(key, source)]['id'], 'patientId': self.cases[key]['patient'],
                'name': self.prefix + '模板' + str(self.sequence), 'scope': 'PERSONAL', 'deptId': None, 'recordType': None}
        body.update(changes)
        assert len(body) == 7
        return body

    def snapshot(self):
        clinical = {}
        for key, case in self.cases.items():
            clinical[key] = {}
            if 'registration' in case:
                clinical[key]['outp'] = self.api('GET', '/outpatient/doctor/' + str(case['registration']['id']) + '/workspace')
            if 'admission' in case:
                path = '/inpatient/admissions/' + str(case['admission'])
                clinical[key]['inp'] = self.api('GET', path + '/workspace')
                clinical[key]['records'] = self.api('GET', path + '/records')
        visible, owners = {}, {}
        for who in self.tokens:
            rows = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword=' + q(self.prefix), who=who)
            visible[who] = rows
            for row in rows: owners[row['id']] = next((name for name, u in self.identities.items() if u['id'] == row['owner_id']), 'admin')
        grants = {str(tid): self.api('GET', '/emr-templates/' + str(tid) + '/grants', who=owner) for tid, owner in owners.items()}
        return {'clinical': clinical, 'visible': visible, 'grants': grants}

    def refuse(self, key, source, who, code=4036, copy_changes=None, record_id=None):
        before = self.snapshot()
        self.body(key, source, who, code, record_id=record_id)
        body = self.copy_body(key, source, **(copy_changes or {}))
        if record_id is not None: body['recordId'] = record_id
        self.api('POST', '/emr-templates/from-record', body, who, code)
        assert self.snapshot() == before, 'Known read/copy refusal changed exposed clinical/template/grant facts'
        self.refusals += 2

    def known_copy(self, key, source, who, **changes):
        body = self.copy_body(key, source, **changes)
        before = self.snapshot()
        result = self.api('POST', '/emr-templates/from-record', body, who)
        assert set(result) == COPY_KEYS and result['maxContent'] == 4000
        head = self.api('GET', '/emr-templates/' + str(result['id']), who=who)
        grants = self.api('GET', '/emr-templates/' + str(result['id']) + '/grants', who=who)
        source_body = self.sources[(key, source)]['body']
        assert head['content'] == source_body[:4000]
        assert result['sourceLength'] == len(source_body) and result['contentLength'] == min(4000, len(source_body))
        assert result['truncated'] == (len(source_body) > 4000)
        assert result['recordType'] == (body['recordType'] or self.sources[(key, source)]['recordType'])
        assert head['owner_id'] == self.identities[who]['id'] and head['template_type'] == 'EMR'
        assert len(grants) == 1
        if result['scope'] == 'DEPT': assert grants[0]['grantee_type'] == 'DEPT' and grants[0]['grantee_id'] == self.dept
        else: assert grants[0]['grantee_type'] == 'USER' and grants[0]['grantee_id'] == self.identities[who]['id']
        after = self.snapshot(); assert after['clinical'] == before['clinical']
        for actor in after['visible']:
            assert [r for r in after['visible'][actor] if r['id'] != result['id']] == before['visible'][actor]
        assert {k: v for k, v in after['grants'].items() if k != str(result['id'])} == before['grants']
        self.known_templates.append(result['id'])
        return result


def main():
    f = Fixture()
    for who in ['admin', 'quality']:
        for key in ['past', 'own', 'today', 'null']:
            rows = f.listing(key, who)
            assert {r['source'] for r in rows} == ({'INP'} if key == 'null' else {'INP', 'OUTP'})
            for source in ['INP'] if key == 'null' else ['INP', 'OUTP']: f.body(key, source, who)
    for who, key in [('doctor', 'own'), ('doctor', 'today'), ('author', 'past')]:
        assert {r['source'] for r in f.listing(key, who)} == {'INP', 'OUTP'}
        for source in ['OUTP', 'INP']:
            f.body(key, source, who); f.known_copy(key, source, who)
    # Same department is not a patient grant. No current/noncancelled visit is
    # accidentally present for these negative subjects.
    for who in ['doctor', 'recordauthor']:
        f.listing('past', who, 4036)
        for source in ['OUTP', 'INP']: f.refuse('past', source, who)
    f.listing('null', 'doctor', 4036); f.refuse('null', 'INP', 'doctor')
    for key in ['past', 'own', 'today', 'null', 'empty']:
        rows = f.listing(key, 'nurse'); assert all(r['source'] == 'INP' for r in rows)
    f.body('past', 'INP', 'nurse'); f.known_copy('past', 'INP', 'nurse')
    f.refuse('past', 'OUTP', 'nurse')
    f.listing('past', 'tech', 1005)
    for source in ['OUTP', 'INP']: f.refuse('past', source, 'tech', 1005)
    # Role unions use the same real JWT after committed public role changes.
    f.listing('past', 'mixed'); f.refuse('past', 'OUTP', 'mixed')
    assert {r['source'] for r in f.listing('own', 'mixed')} == {'OUTP', 'INP'}
    f.set_roles('mixed', ['DOCTOR_OUTP', 'TECHNICIAN'])
    f.listing('past', 'mixed', 4036); f.refuse('past', 'INP', 'mixed')
    f.body('own', 'OUTP', 'mixed')
    f.set_roles('mixed', ['NURSE', 'TECHNICIAN'])
    assert all(r['source'] == 'INP' for r in f.listing('past', 'mixed'))
    f.refuse('past', 'OUTP', 'mixed')
    f.set_roles('mixed', ['QUALITY', 'TECHNICIAN'])
    assert {r['source'] for r in f.listing('past', 'mixed')} == {'OUTP', 'INP'}
    # A distinct INP attending relation grants both sources, then its public
    # revocation makes all three entry points deny again.
    path = '/inpatient/admissions/' + str(f.cases['past']['admission']) + '/attending-doctor'
    f.api('PUT', path, {'doctorId': f.identities['doctor']['id']})
    for source in ['OUTP', 'INP']: f.body('past', source, 'doctor'); f.known_copy('past', source, 'doctor')
    f.api('PUT', path, {'doctorId': f.identities['other']['id']})
    f.listing('past', 'doctor', 4036); f.refuse('past', 'OUTP', 'doctor')
    qualification = f.register(f.cases['past']['patient'], f.today, 'doctor')
    f.body('past', 'OUTP', 'doctor')
    f.api('PUT', '/outpatient/registrations/' + str(qualification['id']) + '/cancel')
    f.listing('past', 'doctor', 4036); f.refuse('past', 'INP', 'doctor')
    # Original input/membership and empty-body boundaries, without probing an
    # unavailable record under an unauthorized patient to distinguish existence.
    f.refuse('past', 'OUTP', 'doctor', record_id=MISSING)
    for changes in [{'patientId': None}, {'source': 'PACS'}, {'recordId': None}, {'recordId': MISSING}, {'patientId': f.cases['own']['patient']}]:
        before = f.snapshot(); body = f.copy_body('past', 'OUTP', **changes)
        f.api('POST', '/emr-templates/from-record', body, code=4068)
        assert f.snapshot() == before
    f.body('past', 'OUTP', 'admin', 4068, record_id=MISSING)
    f.body('past', 'INP', 'admin', 4068, patient_id=f.cases['own']['patient'])
    for suffix in ['', '&limit=0', '&limit=-1', '&limit=201']:
        assert f.listing('past', 'admin', tail=suffix) == f.listing('past', 'admin')
    assert len(f.listing('past', 'admin', tail='&limit=1')) == 1
    f.api('GET', '/emr-templates/prior-records?patientId=bad', code=4000)
    assert f.api('GET', '/emr-templates/prior-records?patientId=' + str(f.empty_patient)) == []
    assert f.api('GET', '/emr-templates/prior-records?patientId=' + str(f.empty_patient), who='nurse') == []
    assert f.body('empty', 'OUTP', 'admin')['content'] == ''
    before = f.snapshot()
    f.api('POST', '/emr-templates/from-record', f.copy_body('empty', 'OUTP'), code=4062)
    assert f.snapshot() == before
    f.known_copy('own', 'OUTP', 'doctor', scope='DEPT', deptId=f.dept)
    before = f.snapshot()
    f.api('POST', '/emr-templates/from-record', f.copy_body('own', 'OUTP', scope='HOSPITAL'), 'doctor', 4066)
    assert f.snapshot() == before
    original = f.sources[('past', 'INP')]
    f.sources[('past', 'INP')] = {'id': f.long_record['id'], 'body': f.long_record['content'], 'recordType': 'PROGRESS'}
    f.known_copy('past', 'INP', 'quality', name=None, scope=None, recordType='PREOP')
    f.sources[('past', 'INP')] = original
    # Source normalization is legal and its six DTO keys remain original.
    source = f.api('GET', '/emr-templates/prior-records/content?patientId=' + str(f.cases['past']['patient'])
        + '&source=' + q(' inp ') + '&recordId=' + str(original['id']))
    assert set(source) == CONTENT_KEYS and source['source'] == 'INP' and source['content'] == original['body']
    assert f.today == today_bj(), 'Fixture crossed the Beijing business-day boundary; do not classify as a policy failure'
    assert not f.unknown_writes
    print('PASS v152 public real JWT history policy:', json.dumps({'checked_reads': f.reads,
        'known_read_copy_refusals': f.refusals, 'known_templates': len(f.known_templates), 'unknown_writes': f.unknown_writes,
        'source_keys': 6, 'list_keys': 9, 'copy_request_keys': 7, 'copy_result_keys': 7,
        'sql_or_jdbc_race_claim': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
