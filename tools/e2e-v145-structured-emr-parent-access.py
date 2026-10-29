# -*- coding: utf-8 -*-
"""Public clinical structured saves inherit current parent-template use rights.

All staff, departments, patients, schedules, beds and templates are private
public-API fixtures. A known refusal freezes the complete exposed clinical,
version, diagnosis-assistant and selected-template DTOs, not arbitrary SQL rows.
The Java JWT / named PostgreSQL holder suite separately proves actual waits,
all-column tables and audit attribution. No start barrier claims old race RED.
Import is safe: there is no HTTP, fixture, login or SQL action before main().
"""
import json
import time
import urllib.error

from e2elib import call, login, q, today_bj

MISSING = 9000000000000000


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V145模拟' + self.suffix
        self.tokens = {'admin': login()}
        self.names, self.identities, self.templates = {}, {}, {}
        self.sequence, self.refusals, self.unknown_writes = 0, 0, []
        self.identities['admin'] = self.api('GET', '/auth/me')
        for who, role in [('doctor', 'DOCTOR_OUTP'), ('owner', 'DOCTOR_OUTP'),
                          ('other', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                          ('quality', 'QUALITY'), ('tech', 'TECHNICIAN'), ('cashier', 'CASHIER')]:
            self.provision(who, role)
        self.departments = {}
        for label, kind in [('A', 'CLINICAL'), ('B', 'CLINICAL'), ('W', 'NURSING')]:
            dept = self.api('POST', '/system/depts', {'code': 'V145' + self.suffix + label,
                'name': self.prefix + '科室' + label, 'type': kind, 'sortNo': 9999})
            self.departments[label] = dept['id']
        for who in ['doctor', 'owner', 'nurse']:
            self.set_actor(who, dept=self.departments['A'])
        for who in ['other', 'quality', 'tech', 'cashier']:
            self.set_actor(who, dept=self.departments['B'])
        self.api('POST', '/inpatient/beds', {'wardId': self.departments['W'], 'count': 1})
        beds = self.api('GET', '/inpatient/beds?wardId=' + str(self.departments['W']))
        assert len(beds) == 1 and beds[0]['status'] == 'FREE', 'Use only the newly created private bed'
        outp = self.api('POST', '/patients', {'name': self.prefix + '门诊患者', 'sex': 'U'})
        inp = self.api('POST', '/patients', {'name': self.prefix + '住院患者', 'sex': 'U'})
        self.outp_patient, self.inp_patient = outp['id'], inp['id']
        schedule = self.api('POST', '/outpatient/schedules', {'deptId': self.departments['A'],
            'doctorId': self.identities['doctor']['id'], 'scheduleDate': today_bj().isoformat(),
            'fee': 10, 'capacity': 9})
        registration = self.api('POST', '/outpatient/registrations',
            {'patientId': self.outp_patient, 'scheduleId': schedule['id']})
        self.registration = registration['id']
        self.api('POST', self.outp_path + '/start', {}, 'doctor')
        admission = self.api('POST', '/inpatient/admissions', {'patientId': self.inp_patient,
            'deptId': self.departments['A'], 'bedId': beds[0]['id'],
            'doctorId': self.identities['doctor']['id'], 'deposit': 100, 'payMethod': 'CASH'})
        self.admission = admission['id']
        self.save('OUTP', None, None, text='V145原门诊自由文本')
        self.save('INP', None, None, text='V145原住院自由文本')

    @property
    def outp_path(self):
        return '/outpatient/doctor/' + str(self.registration)

    @property
    def inp_path(self):
        return '/inpatient/admissions/' + str(self.admission)

    def api(self, method, path, body=None, who='admin', code=0):
        """One transport only. A known refusal never causes an automatic retry."""
        def unknown():
            if method in ['POST', 'PUT', 'PATCH', 'DELETE']:
                self.unknown_writes.append({'method': method, 'path': path})
        try:
            response = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, code) not in [(401, 1000), (403, 1005)]:
                unknown()
                raise
            try:
                response = json.load(error)
            except Exception:
                unknown()
                raise
        except Exception:
            unknown()
            raise
        if not isinstance(response, dict) or 'code' not in response:
            unknown()
            raise AssertionError('Original response lacked a known envelope')
        assert response['code'] == code, (who, method, path, code, response.get('code'), response.get('message'))
        if code:
            assert response.get('data') is None, 'A refused save exposed clinical/template payload'
        return response.get('data')

    def provision(self, who, role):
        name = 'v145_' + who + '_' + self.suffix
        self.names[who] = name
        self.api('POST', '/system/users', {'username': name, 'password': 'Init@1234',
            'realName': self.prefix + who, 'roleCodes': [role]})
        initial = self.api('POST', '/auth/login', {'username': name, 'password': 'Init@1234'})
        self.tokens[who] = initial['token']
        self.api('POST', '/auth/change-password', {'oldPassword': 'Init@1234', 'newPassword': 'Work@1234'}, who)
        working = self.api('POST', '/auth/login', {'username': name, 'password': 'Work@1234'})
        self.tokens[who] = working['token']
        self.identities[who] = self.api('GET', '/auth/me', who=who)

    def set_actor(self, who, dept=None, roles=None):
        person = self.identities[who]
        expected_roles = roles if roles is not None else person.get('roleCodes', person.get('roles'))
        expected_dept = dept if dept is not None else person.get('deptId')
        assert expected_roles
        value = self.api('PUT', '/system/users/' + str(person['id']), {'username': self.names[who],
            'realName': person['realName'], 'deptId': expected_dept, 'phone': person.get('phone'),
            'title': person.get('title'), 'roleCodes': expected_roles})
        assert value['id'] == person['id'] and value['enabled'] and value['deptId'] == expected_dept
        assert set(value['roleCodes']) == set(expected_roles)
        self.identities[who] = value

    def template(self, label, scope='PERSONAL', who='owner', dept=None, required=False, datatype='TEXT'):
        self.sequence += 1
        body = {'name': self.prefix + label, 'content': 'V145模拟模板正文', 'templateType': 'EMR',
            'scope': scope, 'deptId': dept, 'recordType': 'OUTP'}
        tid = self.api('POST', '/emr-templates/scoped', body, who)
        assert isinstance(tid, int) and tid > 0
        field = {'fieldCode': 'f', 'label': 'V145模拟元素', 'datatype': datatype,
            'required': required, 'sortNo': 1, 'valueSet': ['甲', '乙'] if datatype == 'RADIO' else [],
            'placeholder': '模拟输入', 'unit': None, 'enabled': True}
        created = self.api('POST', f'/emr/templates/{tid}/fields', field, who)
        assert created['fieldCode'] == 'f' and created['templateId'] == tid
        self.templates[tid] = {'owner': who, 'body': body, 'field': created}
        return tid

    def template_snapshot(self, tid):
        item = self.templates[tid]
        who = item['owner']
        rows = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword=' + q(item['body']['name']), who=who)
        matching = [row for row in rows if row['id'] == tid]
        assert len(matching) == 1
        return {'head': matching[0], 'fields': self.api('GET', f'/emr/templates/{tid}/fields?includeDisabled=true', who=who),
            'grants': self.api('GET', f'/emr-templates/{tid}/grants', who=who)}

    def versions(self, kind, rid):
        path = f'/emr/versions/{kind}/{rid}'
        listed = self.api('GET', path + '?limit=200&offset=0')
        assert isinstance(listed.get('items'), list)
        assert listed['total'] == len(listed['items']), 'A private version chain must be completely read back'
        return {'list': listed, 'contents': [self.api('GET', path + '/versions/' + str(v['versionNo']))
            for v in listed['items']]}

    def clinical_snapshot(self):
        out = self.api('GET', self.outp_path + '/workspace')
        records = self.api('GET', self.inp_path + '/records')
        doctor = self.identities['doctor']
        roles = doctor.get('roleCodes', doctor.get('roles'))
        favorite_reader = 'doctor' if doctor.get('enabled', True) and ('DOCTOR_OUTP' in roles or 'ADMIN' in roles) else 'admin'
        return {'outpatient': out, 'outpatientVersions': self.versions('OUTP', out['emr']['id']),
            'diagnosisVersions': self.api('GET', '/emr/versions/diagnoses/' + str(self.registration)),
            'diagnosisAssistant': self.api('GET', '/outpatient/doctor/diagnosis-assist?patientId=' + str(self.outp_patient), who=favorite_reader),
            'diagnosisAssistantReader': favorite_reader,
            'inpatient': self.api('GET', self.inp_path + '/workspace'), 'records': records,
            'inpatientVersions': {str(row['id']): self.versions('INP', row['id']) for row in records},
            'publicConfig': self.api('GET', '/config/public'),
            'versionSettings': self.api('GET', '/emr/versions/settings')}

    def snapshot(self, tid):
        return {'clinical': self.clinical_snapshot(), 'template': self.template_snapshot(tid)}

    def payload(self, kind, tid, fields, text):
        if kind == 'OUTP':
            body = {'emr': {'chiefComplaint': 'V145模拟主诉', 'presentIllness': text,
                'pastHistory': 'V145原既往史', 'physicalExam': 'V145原体格检查', 'advice': 'V145模拟处理'},
                'diagnoses': [{'icdCode': '', 'icdName': 'V145模拟自定义诊断', 'diagSystem': 'TCM',
                    'prefix': None, 'suffix': None, 'certainty': None, 'customName': None}]}
        else:
            body = {'recordType': 'PROGRESS', 'title': 'V145模拟病程记录', 'content': text}
        # Explicit null exercises the original free-text branch; non-null {} is
        # deliberately a structured request even though this page may omit it.
        body.update(templateId=tid, fields=fields)
        return body

    def save(self, kind, tid, fields, who='doctor', code=0, text='V145模拟结构化正文', actor_hint=None):
        method, path = ('PUT', self.outp_path + '/emr') if kind == 'OUTP' else ('POST', self.inp_path + '/records')
        body = self.payload(kind, tid, fields, text)
        if actor_hint is not None:
            if kind == 'OUTP':
                body['emr']['doctorId'] = actor_hint  # Existing entity key; never an authority source.
            else:
                # Original SaveRecordRequest has neither key; Jackson ignores
                # these attacker-controlled hints, without a new DTO contract.
                body.update(doctorId=actor_hint, actorId=actor_hint)
        return self.api(method, path, body, who, code)

    def deny(self, kind, tid, who='doctor', code=4066, fields=None, request_tid=None, spoof_actor=False):
        before = self.snapshot(tid)
        values = {'f': '不得保存的缓存值'} if fields is None else fields
        hint = self.identities[self.templates[tid]['owner']]['id'] if spoof_actor else None
        self.save(kind, tid if request_tid is None else request_tid, values, who, code, 'V145不得改写正文', actor_hint=hint)
        assert self.snapshot(tid) == before, (kind, who, code, 'Known refusal changed an exposed business snapshot')
        self.refusals += 1

    def known_save(self, kind, tid, fields=None, who='doctor'):
        values = {'f': 'V145合法结构化值'} if fields is None else fields
        saved = self.save(kind, tid, values, who)
        assert saved['templateId'] == tid and saved.get('contentJson') is not None
        expected_author = self.identities[who if kind == 'INP' else 'doctor']['id']
        assert saved['doctorId'] == expected_author, 'Authority and authorship must come from the actual JWT clinical actor'
        if values:
            content = saved['presentIllness'] if kind == 'OUTP' else saved['content']
            assert values['f'] in content
        return saved

    def grant(self, tid, kind, grantee):
        who = self.templates[tid]['owner']
        before = self.template_snapshot(tid)
        self.api('POST', f'/emr-templates/{tid}/grants', {'granteeType': kind, 'granteeId': grantee}, who)
        after = self.template_snapshot(tid)
        added = [row for row in after['grants'] if row['id'] not in {g['id'] for g in before['grants']}]
        assert len(added) == 1 and added[0]['grantee_type'] == kind and added[0]['grantee_id'] == grantee
        assert after['fields'] == before['fields']
        assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] + 1)
        assert [g for g in after['grants'] if g['id'] != added[0]['id']] == before['grants']
        return added[0]['id']

    def revoke(self, tid, gid):
        before = self.template_snapshot(tid)
        self.api('DELETE', f'/emr-templates/{tid}/grants/{gid}', who=self.templates[tid]['owner'])
        after = self.template_snapshot(tid)
        assert after['fields'] == before['fields']
        assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] - 1)
        assert after['grants'] == [row for row in before['grants'] if row['id'] != gid]

    def enabled(self, tid, enabled):
        before = self.template_snapshot(tid)
        self.api('PUT', f"/emr-templates/{tid}/{'enable' if enabled else 'disable'}", who=self.templates[tid]['owner'])
        after = self.template_snapshot(tid)
        assert after == dict(before, head=dict(before['head'], enabled=enabled))


def main():
    f = Fixture()
    try:
        hidden = f.template('他人个人')
        other_dept = f.template('跨科室', 'DEPT', 'other', f.departments['B'])
        for kind in ['OUTP', 'INP']:
            for who in ['doctor', 'admin']:
                f.deny(kind, hidden, who, spoof_actor=who == 'doctor')
                f.deny(kind, hidden, who, fields={})
            f.deny(kind, other_dept)
            f.deny(kind, other_dept, fields={})
            f.deny(kind, hidden, request_tid=MISSING)
            f.deny(kind, hidden, fields={}, request_tid=MISSING)
            # Preserve the original missing argument validation. A non-null
            # missing/invisible parent above shares the existing 4066 boundary.
            before = f.snapshot(hidden)
            f.save(kind, None, {}, code=4024)
            assert f.snapshot(hidden) == before
            f.refusals += 1
        print('[1] Both clinical save routes reject invisible/missing parents, including empty fields; ADMIN has no PERSONAL bypass')

        own = f.template('本人个人', who='doctor')
        same_dept = f.template('本科室', 'DEPT', dept=f.departments['A'])
        shared = [f.template(scope, scope, 'admin') for scope in ['GLOBAL', 'HOSPITAL']]
        for kind in ['OUTP', 'INP']:
            for tid in [own, same_dept, *shared]:
                f.known_save(kind, tid)
            f.known_save(kind, own, {})
            f.known_save(kind, shared[0], who='admin')
        print('[2] Enabled GLOBAL/HOSPITAL, own PERSONAL and same-department DEPT retain structured saves; empty optional fields and original recordType remain valid')

        # Real USER/DEPT grants on PERSONAL expand use, not maintenance.
        # Keep the cached public field response across revoke and reuse that
        # same request payload; only an explicit regrant permits a later save.
        for grant_kind, target in [('USER', f.identities['doctor']['id']), ('DEPT', f.departments['A'])]:
            gid = f.grant(hidden, grant_kind, target)
            cached = f.api('GET', f'/emr/templates/{hidden}/fields', who='doctor')
            assert cached == f.template_snapshot(hidden)['fields'] and cached
            for kind in ['OUTP', 'INP']:
                f.known_save(kind, hidden)
            f.revoke(hidden, gid)
            for kind in ['OUTP', 'INP']:
                f.deny(kind, hidden)
                f.deny(kind, hidden, fields={})
            gid = f.grant(hidden, grant_kind, target)
            for kind in ['OUTP', 'INP']:
                f.known_save(kind, hidden)
            f.revoke(hidden, gid)
        print('[3] Real USER/DEPT grant, cached fields, revoke refusal and explicit restored save remain consistent on both routes')

        gid = f.grant(hidden, 'USER', f.identities['doctor']['id'])
        f.enabled(hidden, False)
        # Historical field interpretation is deliberately still readable.
        historical_fields = f.api('GET', f'/emr/templates/{hidden}/fields', who='doctor')
        assert historical_fields == f.template_snapshot(hidden)['fields']
        for kind in ['OUTP', 'INP']:
            f.deny(kind, hidden)
            f.deny(kind, hidden, fields={})
        f.enabled(hidden, True)
        for kind in ['OUTP', 'INP']:
            f.known_save(kind, hidden)
        f.revoke(hidden, gid)
        print('[4] Disabled parent prevents new structured saves; historical field reading and re-enabled use retain their original contracts')

        # No fields follows the original branch even if templateId is stale or
        # invisible. OUTP preserves the earlier sidecar; new INP text stays null.
        for kind in ['OUTP', 'INP']:
            saved = f.known_save(kind, own)
            old_records = f.api('GET', f.inp_path + '/records')
            free = f.save(kind, hidden, None, text='V145仍可自由文本暂存')
            if kind == 'OUTP':
                assert free['contentJson'] == saved['contentJson'] and free['templateId'] == own
            else:
                assert free['contentJson'] is None and free['templateId'] is None
                after = f.api('GET', f.inp_path + '/records')
                assert [row for row in after if row['id'] != free['id']] == old_records
        print('[5] fields=null preserves old free-text behavior and existing OUTP sidecars; INP history and signatures stay intact')

        required = f.template('必填', who='doctor', required=True)
        choices = f.template('值域', who='doctor', datatype='RADIO')
        for kind in ['OUTP', 'INP']:
            f.deny(kind, required, code=4025, fields={})
            f.deny(kind, choices, code=4026, fields={'f': '域外'})
            f.deny(kind, own, code=4024, fields={'unknown': '无定义'})
            for who, code in [('anonymous', 1000), ('nurse', 1005), ('quality', 1005), ('tech', 1005), ('cashier', 1005)]:
                f.deny(kind, hidden, who, code)
        print('[6] Original field 4024/4025/4026 and clinical ADMIN/DOCTOR duty boundaries remain readable and rollback-safe')

        # A changed department affects parent use while clinical ownership stays
        # with this same doctor. Restoring it makes the exact original request
        # usable again; no login/token refresh is used to conceal stale facts.
        original_token = f.tokens['doctor']
        f.set_actor('doctor', dept=f.departments['B'])
        for kind in ['OUTP', 'INP']:
            f.deny(kind, same_dept)
        f.set_actor('doctor', dept=f.departments['A'])
        for kind in ['OUTP', 'INP']:
            f.known_save(kind, same_dept)
        before = f.snapshot(hidden)
        f.set_actor('doctor', roles=['CASHIER'])
        for kind in ['OUTP', 'INP']:
            f.deny(kind, hidden, code=1005)
        f.set_actor('doctor', roles=['DOCTOR_OUTP'])
        assert f.snapshot(hidden) == before
        assert f.tokens['doctor'] == original_token
        for kind in ['OUTP', 'INP']:
            f.known_save(kind, own)
        assert not f.unknown_writes
        print('[7] Same JWT observes current department and duty; explicit restoration preserves legal clinical use')
        print(f'PASS v145 public structured parent use: {f.refusals} frozen refusals, two clinical routes, no unknown write replay')
    finally:
        # Preserve private clinical evidence and private capacity. Never delete
        # or resubmit an uncertain write to manufacture a successful outcome.
        if f.unknown_writes:
            print('UNKNOWN_WRITES (not replayed/cleaned): ' + json.dumps(f.unknown_writes, ensure_ascii=False))


if __name__ == '__main__':
    main()
