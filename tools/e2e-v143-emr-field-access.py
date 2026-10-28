# -*- coding: utf-8 -*-
"""Public JWT compatibility for field definitions inheriting parent template ACL.

Only public HTTP APIs create private staff, departments, templates and fields.
Each refusal is surrounded by full field DTO/head/grant reads by the owner;
security/audit records are deliberately outside this business-state snapshot.
The HTTP start barrier checks the public outcome, not PostgreSQL contention:
the Java JWT/PG suite independently proves the named-holder lock interleaving.
Import is safe: no login, fixture, HTTP call or database access runs on import.
"""
import json
import threading
import time
import urllib.error
from concurrent.futures import ThreadPoolExecutor

from e2elib import call, login, provision_user, q

MISSING = 9000000000000000
DTO_KEYS = {'id', 'templateId', 'fieldCode', 'label', 'datatype', 'required',
            'sortNo', 'valueSet', 'placeholder', 'unit', 'enabled'}


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V143模拟' + self.suffix
        self.tokens = {'admin': login()}
        self.identities, self.names, self.templates = {}, {}, {}
        self.unknown_writes, self.refusals = [], 0
        self.sequence = 0
        for who, role in [('doctor', 'DOCTOR_OUTP'), ('peer', 'DOCTOR_OUTP'),
                          ('other', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                          ('quality', 'QUALITY'), ('tech', 'TECHNICIAN')]:
            self.names[who] = 'v143_' + who + '_' + self.suffix
            self.tokens[who] = provision_user(self.tokens['admin'], self.names[who], role)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
        self.departments = {}
        for label in ['A', 'B']:
            self.departments[label] = self.api('POST', '/system/depts', {
                'code': 'V143' + label + self.suffix, 'name': self.prefix + '科室' + label,
                'type': 'CLINICAL', 'sortNo': 9999})['id']
        for who in ['doctor', 'peer', 'nurse']:
            self.set_actor(who, dept=self.departments['A'])
        for who in ['other', 'quality', 'tech']:
            self.set_actor(who, dept=self.departments['B'])

    def api(self, method, path, body=None, who='admin', code=0):
        """Known refusals never disclose payload; unknown writes are never replayed."""
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
            raise AssertionError('Original response did not contain a known envelope')
        assert response['code'] == code, (who, method, path, code, response.get('code'), response.get('message'))
        if code:
            assert response.get('data') is None, 'Refusal disclosed a field/template payload'
        return response.get('data')

    def set_actor(self, who, dept=None, roles=None):
        person = self.identities[who]
        expected_roles = roles if roles is not None else person.get('roleCodes', person.get('roles'))
        assert expected_roles, 'Preserve the current public identity duty when assigning a private department'
        expected_dept = dept if dept is not None else person.get('deptId')
        result = self.api('PUT', '/system/users/' + str(person['id']), {
            'username': self.names[who], 'realName': person['realName'],
            'title': person.get('title'), 'phone': person.get('phone'),
            'deptId': expected_dept, 'roleCodes': expected_roles})
        assert result['id'] == person['id'] and result['enabled']
        assert result['deptId'] == expected_dept and set(result['roleCodes']) == set(expected_roles)
        self.identities[who] = result

    def template(self, label, scope, who='doctor', dept=None):
        name = self.prefix + label
        body = {'name': name, 'content': 'V143模拟模板正文', 'templateType': 'EMR',
                'scope': scope, 'deptId': dept, 'recordType': 'OUTP'}
        tid = self.api('POST', '/emr-templates/scoped', body, who)
        assert isinstance(tid, int) and tid > 0
        self.templates[tid] = {'owner': who, 'name': name, 'body': body}
        snapshot = self.snapshot(tid)
        assert snapshot['head']['scope'] == scope and snapshot['head']['enabled']
        assert snapshot['fields'] == []
        return tid

    @staticmethod
    def field_body(code='V143_TEXT', **changes):
        body = {'fieldCode': code, 'label': 'V143模拟元素', 'datatype': 'TEXT',
                'required': False, 'sortNo': 10, 'valueSet': [],
                'placeholder': '模拟输入', 'unit': None, 'enabled': True}
        body.update(changes)
        assert len(body) == 9, 'Original FieldReq remains nine components'
        return body

    @staticmethod
    def dto(row, tid):
        assert set(row) == DTO_KEYS and len(row) == 11, row
        assert row['templateId'] == tid and isinstance(row['id'], int)
        assert isinstance(row['valueSet'], list) and isinstance(row['enabled'], bool)
        return row

    def fields(self, tid, who=None, include_disabled=True):
        actor = who or self.templates[tid]['owner']
        tail = '?includeDisabled=true' if include_disabled else ''
        rows = self.api('GET', f'/emr/templates/{tid}/fields' + tail, who=actor)
        for row in rows:
            self.dto(row, tid)
        assert rows == sorted(rows, key=lambda row: (row['sortNo'], row['id']))
        return rows

    def snapshot(self, tid):
        fixture = self.templates[tid]
        owner = fixture['owner']
        heads = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword=' + q(fixture['name']), who=owner)
        heads = [row for row in heads if row['id'] == tid]
        assert len(heads) == 1, 'Owner lost their exact private fixture head'
        return {'fields': self.fields(tid, owner), 'head': heads[0],
                'grants': self.api('GET', f'/emr-templates/{tid}/grants', who=owner)}

    def read(self, tid, who):
        before = self.snapshot(tid)
        assert self.fields(tid, who) == before['fields']
        assert self.fields(tid, who, False) == [row for row in before['fields'] if row['enabled']]
        assert self.snapshot(tid) == before

    def deny(self, tid, method, path, body=None, who='other', code=4066):
        before = self.snapshot(tid)
        self.api(method, path, body, who, code)
        assert self.snapshot(tid) == before, (who, method, path, 'Refusal changed business state')
        self.refusals += 1

    def deny_writes(self, tid, fid, who, code=4066):
        self.sequence += 1
        body = self.field_body('V143_DENY_' + str(self.sequence), label='不得写入')
        for method, path, payload in [
            ('POST', f'/emr/templates/{tid}/fields', body),
            ('PUT', f'/emr/fields/{fid}', body),
            ('DELETE', f'/emr/fields/{fid}', None)]:
            self.deny(tid, method, path, payload, who, code)

    def create_field(self, tid, who=None, **changes):
        self.sequence += 1
        actor = who or self.templates[tid]['owner']
        body = self.field_body('V143_' + str(self.sequence), **changes)
        before = self.snapshot(tid)
        created = self.dto(self.api('POST', f'/emr/templates/{tid}/fields', body, actor), tid)
        after = self.snapshot(tid)
        assert created == dict(body, id=created['id'], templateId=tid)
        assert after['head'] == before['head'] and after['grants'] == before['grants']
        assert after['fields'] == sorted(before['fields'] + [created], key=lambda row: (row['sortNo'], row['id']))
        return created

    def update_field(self, tid, row, who=None, **changes):
        actor = who or self.templates[tid]['owner']
        # Historical fieldCode is immutable even when a different value is sent.
        body = self.field_body('IMMUTABLE', label=row['label'] + '修订', **changes)
        before = self.snapshot(tid)
        updated = self.dto(self.api('PUT', f"/emr/fields/{row['id']}", body, actor), tid)
        assert updated == dict(body, id=row['id'], templateId=tid, fieldCode=row['fieldCode'])
        after = self.snapshot(tid)
        assert after['head'] == before['head'] and after['grants'] == before['grants']
        expected = [updated if item['id'] == row['id'] else item for item in before['fields']]
        assert after['fields'] == sorted(expected, key=lambda item: (item['sortNo'], item['id']))
        return updated

    def disable_field(self, tid, row, who=None):
        actor = who or self.templates[tid]['owner']
        before = self.snapshot(tid)
        assert self.api('DELETE', f"/emr/fields/{row['id']}", who=actor) is None
        after = self.snapshot(tid)
        assert after['head'] == before['head'] and after['grants'] == before['grants']
        expected = [dict(item, enabled=False) if item['id'] == row['id'] else item for item in before['fields']]
        assert after['fields'] == expected
        assert row['id'] not in [item['id'] for item in self.fields(tid, actor, False)]

    def grant(self, tid, kind, identity):
        owner = self.templates[tid]['owner']
        before = self.snapshot(tid)
        assert self.api('POST', f'/emr-templates/{tid}/grants', {'granteeType': kind, 'granteeId': identity}, owner) is None
        after = self.snapshot(tid)
        assert after['fields'] == before['fields']
        expected_head = dict(before['head'], grant_count=before['head']['grant_count'] + 1)
        assert after['head'] == expected_head
        added = [row for row in after['grants'] if row['id'] not in {item['id'] for item in before['grants']}]
        assert len(added) == 1 and added[0]['grantee_type'] == kind and added[0]['grantee_id'] == identity
        assert [row for row in after['grants'] if row not in added] == before['grants']
        return added[0]['id']

    def revoke(self, tid, grant_id):
        owner = self.templates[tid]['owner']
        before = self.snapshot(tid)
        assert self.api('DELETE', f'/emr-templates/{tid}/grants/{grant_id}', who=owner) is None
        after = self.snapshot(tid)
        assert after['fields'] == before['fields']
        assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] - 1)
        assert after['grants'] == [row for row in before['grants'] if row['id'] != grant_id]


def main():
    f = Fixture()
    try:
        # The original four duties retain their own PERSONAL CRUD, not just reads.
        personal = {}
        for who in ['admin', 'doctor', 'nurse', 'quality']:
            tid = f.template(who + '个人', 'PERSONAL', who)
            row = f.create_field(tid)
            row = f.update_field(tid, row)
            f.read(tid, who)
            f.disable_field(tid, row)
            f.deny(tid, 'DELETE', f"/emr/fields/{row['id']}", who=who, code=4024)
            row = f.create_field(tid)
            personal[who] = (tid, row)
        tid, row = personal['doctor']
        for who, code in [('anonymous', 1000), ('tech', 1005)]:
            f.deny(tid, 'GET', f'/emr/templates/{tid}/fields', who=who, code=code)
            f.deny_writes(tid, row['id'], who, code)
        # ADMIN must not bypass somebody else's PERSONAL scope.
        for who in ['admin', 'other', 'quality', 'nurse']:
            f.deny(tid, 'GET', f'/emr/templates/{tid}/fields?includeDisabled=true', who=who)
            f.deny_writes(tid, row['id'], who)
        print('[1] Original four duties retain PERSONAL CRUD; anonymous/TECH and unrelated ADMIN cannot bypass the parent ACL')

        for scope in ['GLOBAL', 'HOSPITAL']:
            shared = f.template(scope, scope, 'admin')
            shared_row = f.create_field(shared)
            for who in ['admin', 'doctor', 'nurse', 'quality']:
                f.read(shared, who)
            for who in ['doctor', 'nurse', 'quality']:
                f.deny_writes(shared, shared_row['id'], who)
            f.update_field(shared, shared_row)
        department = f.template('本科室', 'DEPT', dept=f.departments['A'])
        dept_row = f.create_field(department)
        for who in ['doctor', 'peer', 'nurse', 'admin']:
            f.read(department, who)
        for who in ['peer', 'nurse']:
            f.deny_writes(department, dept_row['id'], who)
        for who in ['other', 'quality']:
            f.deny(department, 'GET', f'/emr/templates/{department}/fields', who=who)
            f.deny_writes(department, dept_row['id'], who)
        dept_row = f.update_field(department, dept_row, 'admin')
        print('[2] GLOBAL/HOSPITAL remain broadly readable and ADMIN editable; DEPT retains same-department reads and owner/ADMIN maintenance')

        # Both grant kinds expand canSee, never canEdit. Exercise both on a
        # PERSONAL parent too, matching the existing canSee/hasGrant semantics.
        for grant_tid, grant_row in [(tid, row), (department, dept_row)]:
            user_grant = f.grant(grant_tid, 'USER', f.identities['other']['id'])
            f.read(grant_tid, 'other')
            f.deny_writes(grant_tid, grant_row['id'], 'other')
            f.revoke(grant_tid, user_grant)
            f.deny(grant_tid, 'GET', f'/emr/templates/{grant_tid}/fields', who='other')
            f.grant(grant_tid, 'USER', f.identities['other']['id'])
            f.read(grant_tid, 'other')
            dept_grant = f.grant(grant_tid, 'DEPT', f.departments['B'])
            f.read(grant_tid, 'quality')
            f.deny_writes(grant_tid, grant_row['id'], 'quality')
            f.revoke(grant_tid, dept_grant)
            f.deny(grant_tid, 'GET', f'/emr/templates/{grant_tid}/fields', who='quality')
        print('[3] USER and DEPT grants expand reads only; revoke denies a fresh read and regrant restores the original field DTO')

        # Existing JWT strings stay unchanged while current DB membership,
        # duty and enabled status are reloaded on the next request.
        peer_token = f.tokens['peer']
        f.set_actor('peer', dept=f.departments['B'])
        f.deny(department, 'GET', f'/emr/templates/{department}/fields', who='peer')
        f.deny_writes(department, dept_row['id'], 'peer')
        f.set_actor('peer', dept=f.departments['A'])
        f.read(department, 'peer')
        f.set_actor('peer', roles=['TECHNICIAN'])
        f.deny(department, 'GET', f'/emr/templates/{department}/fields', who='peer', code=1005)
        f.deny_writes(department, dept_row['id'], 'peer', 1005)
        f.set_actor('peer', roles=['DOCTOR_OUTP'])
        f.read(department, 'peer')
        peer_id = f.identities['peer']['id']
        f.api('PUT', f'/system/users/{peer_id}/enabled?enabled=false')
        f.deny(department, 'GET', f'/emr/templates/{department}/fields', who='peer', code=1000)
        f.deny_writes(department, dept_row['id'], 'peer', 1000)
        f.api('PUT', f'/system/users/{peer_id}/enabled?enabled=true')
        assert f.tokens['peer'] == peer_token
        f.read(department, 'peer')
        print('[4] The same JWT observes changed department, withdrawn duty and disabled account; restoring current facts restores legal reads')

        # A disabled head cannot be applied as a new template; field definitions
        # still explain historical records and retain legal owner maintenance.
        f.api('PUT', f'/emr-templates/{tid}/disable', who='doctor')
        f.deny(tid, 'GET', f'/emr-templates/{tid}', who='doctor')
        f.read(tid, 'doctor')
        disabled_head_row = f.create_field(tid)
        disabled_head_row = f.update_field(tid, disabled_head_row)
        f.disable_field(tid, disabled_head_row)
        f.read(tid, 'other')  # previously granted USER remains valid on fields
        f.deny_writes(tid, row['id'], 'other')
        f.api('PUT', f'/emr-templates/{tid}/enable', who='doctor')
        assert f.api('GET', f'/emr-templates/{tid}', who='doctor')['id'] == tid

        # Original validation/status contracts are applied only after the parent
        # ACL, with no new parent-enabled restriction or public DTO component.
        for datatype in ['TEXT', 'NUMBER', 'CHECKBOX', 'RADIO', 'MULTI', 'DATE']:
            candidates = ['甲', '乙'] if datatype in ['RADIO', 'MULTI'] else []
            typed = f.create_field(tid, datatype=datatype, valueSet=candidates, sortNo=50)
            assert typed['datatype'] == datatype and typed['valueSet'] == candidates
        for changes, code in [({'fieldCode': 'bad-code'}, 4027), ({'label': ''}, 4027),
                              ({'datatype': 'UNKNOWN'}, 4027), ({'datatype': 'RADIO'}, 4026),
                              ({'datatype': 'MULTI', 'valueSet': [' ', '']}, 4026)]:
            f.deny(tid, 'POST', f'/emr/templates/{tid}/fields', f.field_body(**changes), 'doctor', code)
        for changes, code in [({'label': ''}, 4027), ({'datatype': 'UNKNOWN'}, 4027),
                              ({'datatype': 'RADIO'}, 4026)]:
            f.deny(tid, 'PUT', f"/emr/fields/{row['id']}", f.field_body(**changes), 'doctor', code)
        f.deny(tid, 'POST', f'/emr/templates/{tid}/fields', f.field_body(row['fieldCode']), 'doctor', 4027)
        f.deny(tid, 'GET', f'/emr/templates/{MISSING}/fields', who='doctor')
        f.deny(tid, 'POST', f'/emr/templates/{MISSING}/fields', f.field_body(), 'doctor', 4024)
        f.deny(tid, 'PUT', f'/emr/fields/{MISSING}', f.field_body(), 'doctor', 4024)
        f.deny(tid, 'DELETE', f'/emr/fields/{MISSING}', who='doctor', code=4024)
        # Validation must not reveal a hidden parent's existence or definitions.
        f.deny(tid, 'POST', f'/emr/templates/{tid}/fields', f.field_body(label='', datatype='UNKNOWN'), 'admin')
        f.deny(tid, 'PUT', f"/emr/fields/{row['id']}", f.field_body(label='', datatype='UNKNOWN'), 'admin')
        print('[5] Disabled-head field history/maintenance, DTO11/FieldReq9, six data types, immutable codes and original 4024/4026/4027 remain intact')

        before = f.snapshot(tid)
        barrier = threading.Barrier(2)
        race_body = f.field_body('V143_SAME_CODE', label='V143模拟并发元素', sortNo=70)

        def competing_create():
            barrier.wait(timeout=10)
            # Either connection may be first. Preserve each original response;
            # do not retry a refused or uncertain write to manufacture success.
            try:
                response = call('POST', f'/emr/templates/{tid}/fields', race_body, f.tokens['doctor'])
                assert isinstance(response, dict) and 'code' in response
                return response
            except Exception:
                f.unknown_writes.append({'method': 'POST', 'path': f'/emr/templates/{tid}/fields', 'case': 'same-code'})
                raise

        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(competing_create) for _ in range(2)]
            replies = [future.result(timeout=45) for future in futures]
        assert sorted(reply['code'] for reply in replies) == [0, 4027], replies
        success = f.dto(next(reply['data'] for reply in replies if reply['code'] == 0), tid)
        assert success['fieldCode'] == race_body['fieldCode']
        assert next(reply for reply in replies if reply['code'] == 4027).get('data') is None
        after = f.snapshot(tid)
        assert after['head'] == before['head'] and after['grants'] == before['grants']
        assert after['fields'] == sorted(before['fields'] + [success], key=lambda item: (item['sortNo'], item['id']))
        f.deny(tid, 'POST', f'/emr/templates/{tid}/fields', race_body, 'doctor', 4027)
        assert not f.unknown_writes
        print('[6] Public same-code start-barrier check: one success, one original 4027, exactly one new full DTO; no claim of deterministic old RED/PG wait')
        print(f'PASS v143 public field ACL: {f.refusals} individually frozen refusals; {len(f.templates)} private templates; no unknown write replay')
    finally:
        # No cleanup write: private rows preserve evidence and cannot consume
        # beds, schedules or any clinical capacity. In particular never guess
        # the result of a transport failure by resending the mutating request.
        if f.unknown_writes:
            print('UNKNOWN_WRITES (not replayed or cleaned up): ' + json.dumps(f.unknown_writes, ensure_ascii=False))


if __name__ == '__main__':
    main()
