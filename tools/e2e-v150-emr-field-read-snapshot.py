# -*- coding: utf-8 -*-
"""Sequential public JWT compatibility for current field-read permission snapshots.

Exclusive staff/departments/parents/fields/grants are created only through APIs.
Every checked read freezes full public parent/field/grant/config projections;
this does not claim arbitrary SQL/audit facts or deterministic JDBC scheduling.
The Java suite separately proves the actor/payload and empty-result windows.
Legal previously delivered definitions remain a legal local cache. No clinical
fixture, save, SQL, mock actor fallback, retry or unknown-write replay is used.
Import defines helpers only: it performs no login, HTTP or fixture creation.
"""
import json
import time
import urllib.error
from e2elib import call, login, q

MISSING = 9000000000000000
DTO_KEYS = {'id', 'templateId', 'fieldCode', 'label', 'datatype', 'required',
            'sortNo', 'valueSet', 'placeholder', 'unit', 'enabled'}
GRANT_KEYS = {'id', 'template_id', 'grantee_type', 'grantee_id', 'granted_at', 'grantee_name', 'granted_by_name'}
VISIBLE_KEYS = {'id', 'dept_id', 'name', 'content', 'template_type', 'enabled', 'scope', 'owner_id',
                'record_type', 'is_default', 'created_by', 'created_at', 'owner_name', 'dept_name',
                'grant_count', 'editable', 'scopeName'}

class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V150模拟' + self.suffix
        self.tokens, self.identities, self.names, self.templates = {'admin': login()}, {}, {}, {}
        self.unknown_writes, self.refusals, self.reads = [], 0, 0
        self.sequence = 0
        self.identities['admin'] = self.api('GET', '/auth/me')
        for who, role in [('doctor', 'DOCTOR_OUTP'), ('peer', 'DOCTOR_OUTP'), ('other', 'DOCTOR_OUTP'),
                          ('nurse', 'NURSE'), ('quality', 'QUALITY'), ('tech', 'TECHNICIAN'),
                          ('manager', 'ADMIN'), ('cashier', 'CASHIER')]:
            self.provision(who, role)
        self.departments = {}
        for label in ['A', 'B']:
            self.departments[label] = self.api('POST', '/system/depts', {
                'code': 'V150' + self.suffix + label, 'name': self.prefix + '科室' + label,
                'type': 'CLINICAL', 'sortNo': 9999})['id']
        for who in ['doctor', 'peer', 'nurse']:
            self.set_actor(who, dept=self.departments['A'])
        for who in ['other', 'quality', 'tech', 'manager', 'cashier']:
            self.set_actor(who, dept=self.departments['B'])


    def api(self, method, path, body=None, who='admin', code=0):
        def unknown():
            if method in ['POST', 'PUT', 'PATCH', 'DELETE']:
                self.unknown_writes.append({'method': method, 'path': path})
        try:
            response = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, code) not in [(401, 1000), (403, 1005), (400, 4000)]:
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
            assert response.get('data') is None, 'Refused read disclosed body/grant data'
        return response.get('data')


    def provision(self, who, role):
        name = 'v150_' + who + '_' + self.suffix
        self.names[who] = name
        self.api('POST', '/system/users', {'username': name, 'password': 'Init@1234',
            'realName': self.prefix + who, 'roleCodes': [role]})
        first = self.api('POST', '/auth/login', {'username': name, 'password': 'Init@1234'})
        self.tokens[who] = first['token']
        self.api('POST', '/auth/change-password', {'oldPassword': 'Init@1234', 'newPassword': 'Work@1234'}, who)
        current = self.api('POST', '/auth/login', {'username': name, 'password': 'Work@1234'})
        self.tokens[who] = current['token']
        self.identities[who] = self.api('GET', '/auth/me', who=who)


    def set_actor(self, who, dept=None, roles=None):
        person = self.identities[who]
        actual_roles = roles if roles is not None else person.get('roleCodes', person.get('roles'))
        actual_dept = dept if dept is not None else person.get('deptId')
        assert actual_roles
        person = self.api('PUT', '/system/users/' + str(person['id']), {'username': self.names[who],
            'realName': person['realName'], 'phone': person.get('phone'), 'title': person.get('title'),
            'deptId': actual_dept, 'roleCodes': actual_roles})
        assert person['enabled'] and person['deptId'] == actual_dept and set(person['roleCodes']) == set(actual_roles)
        self.identities[who] = person


    @staticmethod
    def field_body(code='V150_TEXT', **changes):
        body = {'fieldCode': code, 'label': 'V150模拟元素', 'datatype': 'TEXT',
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


    def create_field(self, tid, who=None, **changes):
        self.sequence += 1
        actor = who or self.templates[tid]['owner']
        body = self.field_body('V150_' + str(self.sequence), **changes)
        before = self.snapshot(tid)
        created = self.dto(self.api('POST', f'/emr/templates/{tid}/fields', body, actor), tid)
        after = self.snapshot(tid)
        assert created == dict(body, id=created['id'], templateId=tid)
        assert after['head'] == before['head'] and after['grants'] == before['grants']
        assert after['fields'] == sorted(before['fields'] + [created], key=lambda row: (row['sortNo'], row['id']))
        return created


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


    def template(self, label, scope='PERSONAL', who='doctor', dept=None, kind='EMR', record='OUTP'):
        body = {'name': self.prefix + label, 'content': self.prefix + '原合法正文' + label,
                'templateType': kind, 'scope': scope, 'deptId': dept, 'recordType': record}
        tid = self.api('POST', '/emr-templates/scoped', body, who)
        assert isinstance(tid, int) and tid > 0
        self.templates[tid] = {'owner': who, 'body': body}
        assert self.snapshot(tid)['fields'] == []
        return tid

    def snapshot(self, tid):
        fixture = self.templates[tid]
        rows = self.api('GET', '/emr-templates/visible?includeDisabled=true&type=' + q(fixture['body']['templateType'])
                        + '&keyword=' + q(fixture['body']['name']), who=fixture['owner'])
        heads = [row for row in rows if row['id'] == tid]
        assert len(heads) == 1 and set(heads[0]) == VISIBLE_KEYS
        grants = self.api('GET', f'/emr-templates/{tid}/grants', who=fixture['owner'])
        assert all(set(row) == GRANT_KEYS for row in grants)
        return {'head': heads[0], 'fields': self.fields(tid, fixture['owner']), 'grants': grants,
                'publicConfig': self.api('GET', '/config/public'),
                'versionSettings': self.api('GET', '/emr/versions/settings')}

    def check(self, tid, who='doctor', code=0, variants=None):
        before = self.snapshot(tid)
        variants = variants if variants is not None else ['', '?includeDisabled=false', '?includeDisabled=true']
        returned = None
        for query in variants:
            returned = self.api('GET', f'/emr/templates/{tid}/fields' + query, who=who, code=code)
            if code:
                assert returned is None
                self.refusals += 1
            else:
                include = query.split('=', 1)[-1].lower() in ['true', '1', 'on', 'yes'] if '=' in query else False
                expected = before['fields'] if include else [row for row in before['fields'] if row['enabled']]
                assert returned == expected, (who, tid, query, returned, expected)
                for row in returned: self.dto(row, tid)
                assert returned == sorted(returned, key=lambda row: (row['sortNo'], row['id']))
                self.reads += 1
            assert self.snapshot(tid) == before, 'Field GET changed a complete public head/field/grant/config projection'
        return returned

    def reject_path(self, tid, path, who='doctor', code=4066):
        before = self.snapshot(tid)
        assert self.api('GET', path, who=who, code=code) is None
        assert self.snapshot(tid) == before
        self.refusals += 1

    def enabled(self, tid, enabled):
        before = self.snapshot(tid)
        self.api('PUT', f"/emr-templates/{tid}/{'enable' if enabled else 'disable'}", who=self.templates[tid]['owner'])
        assert self.snapshot(tid) == dict(before, head=dict(before['head'], enabled=enabled))


def main():
    f = Fixture(); a, b = f.departments['A'], f.departments['B']
    global_id = f.template('01全局', 'GLOBAL', 'admin')
    hospital = f.template('02全院', 'HOSPITAL', 'admin')
    dept = f.template('03科室', 'DEPT', dept=a)
    personal = f.template('04个人', 'PERSONAL')
    empty = f.template('05合法空字段', 'HOSPITAL', 'admin')
    disabled_only = f.template('06仅停用字段', 'HOSPITAL', 'admin')
    other_kind = f.template('07影像进展父', 'HOSPITAL', 'admin', kind='RIS', record='PROGRESS')
    own = {who: f.template('个人岗位' + who, who=who) for who in ['nurse', 'quality']}
    for tid in [global_id, hospital, dept, personal, other_kind] + list(own.values()):
        f.create_field(tid)
    f.create_field(disabled_only, enabled=False)
    typed = []
    for n, datatype in enumerate(['TEXT', 'NUMBER', 'CHECKBOX', 'RADIO', 'MULTI', 'DATE']):
        typed.append(f.create_field(dept, datatype=datatype, label=f.prefix + datatype, required=n % 2 == 0,
            sortNo=[5, 1, 2, 2, 3, 4][n], valueSet=['低', '高'] if datatype in ['RADIO', 'MULTI'] else [],
            placeholder='模拟提示' if n % 2 else None, unit='℃' if datatype == 'NUMBER' else None))
    disabled = f.create_field(dept, label=f.prefix + '停用元素', enabled=False, sortNo=0)
    assert len(typed) == 6 and {row['datatype'] for row in typed} == {'TEXT','NUMBER','CHECKBOX','RADIO','MULTI','DATE'}
    complete = f.snapshot(dept)['fields']
    assert complete[0] == disabled and complete == sorted(complete, key=lambda row: (row['sortNo'], row['id']))
    assert [row['id'] for row in complete if row['sortNo'] == 2] == [typed[2]['id'], typed[3]['id']]

    for who in ['admin', 'doctor', 'nurse', 'quality']:
        f.check(global_id, who); f.check(hospital, who); f.check(empty, who); f.check(disabled_only, who)
    for who, tid in own.items(): f.check(tid, who)
    f.check(personal)
    for who in ['admin', 'other', 'quality', 'nurse']: f.check(personal, who, 4066)
    for who in ['doctor', 'peer', 'nurse', 'admin']: f.check(dept, who)
    for who in ['other', 'quality']: f.check(dept, who, 4066)
    for who, code in [('anonymous',1000), ('tech',1005), ('cashier',1005)]:
        f.check(dept, who, code)
    print('[1] Four original duties, full DTO11/six types, sort_no/id, child filtering, legal empty[] and hidden4066 remain distinct')

    for tid in [personal, dept]:
        grant = f.grant(tid, 'USER', f.identities['other']['id'])
        cached = f.check(tid, 'other')
        assert cached and all(set(row) == DTO_KEYS for row in cached)
        f.revoke(tid, grant); f.check(tid, 'other', 4066)
        # The previously delivered object remains a legal local cache; this is a
        # new explicit read after known grant restoration, not a JDBC schedule.
        assert cached and all(set(row) == DTO_KEYS for row in cached)
        restored = f.grant(tid, 'USER', f.identities['other']['id']); assert restored != grant
        assert f.check(tid, 'other') == cached
        f.revoke(tid, restored)
        department_grant = f.grant(tid, 'DEPT', b)
        f.check(tid, 'quality'); f.check(tid, 'other')
        f.revoke(tid, department_grant); f.check(tid, 'quality', 4066)
    print('[2] USER and DEPT grants also apply to PERSONAL; explicit revoke denies fresh reads and regrant restores definitions')

    peer_token = f.tokens['peer']; manager_token = f.tokens['manager']
    f.set_actor('peer', dept=b); f.check(dept, 'peer', 4066)
    f.set_actor('peer', dept=a); f.check(dept, 'peer')
    f.check(dept, 'manager')
    f.set_actor('manager', roles=['QUALITY']); f.check(dept, 'manager', 4066); f.check(hospital, 'manager')
    f.set_actor('manager', roles=['TECHNICIAN']); f.check(hospital, 'manager', 1005)
    f.set_actor('manager', roles=['ADMIN']); f.check(dept, 'manager')
    f.set_actor('peer', roles=['TECHNICIAN']); f.check(dept, 'peer', 1005)
    f.set_actor('peer', roles=['DOCTOR_OUTP']); f.check(dept, 'peer')
    peer_id = f.identities['peer']['id']
    f.api('PUT', f'/system/users/{peer_id}/enabled?enabled=false'); f.check(dept, 'peer', 1000)
    f.api('PUT', f'/system/users/{peer_id}/enabled?enabled=true'); f.check(dept, 'peer')
    assert f.tokens['peer'] == peer_token and f.tokens['manager'] == manager_token
    print('[3] Unchanged JWT strings observe current department/ADMIN/four-duty/enabled facts; no trusted missing-actor fallback is created')

    f.enabled(dept, False)
    for who in ['doctor', 'peer', 'nurse', 'admin']: f.check(dept, who)
    f.check(dept, 'other', 4066); f.enabled(dept, True)
    f.check(other_kind, 'nurse')
    f.check(dept, variants=['?includeDisabled=', '?includeDisabled=1', '?includeDisabled=on', '?includeDisabled=yes',
                           '?includeDisabled=0', '?includeDisabled=off', '?includeDisabled=no'])
    f.reject_path(dept, f'/emr/templates/{MISSING}/fields')
    f.reject_path(dept, '/emr/templates/not-a-long/fields', code=4000)
    f.reject_path(dept, f'/emr/templates/{dept}/fields?includeDisabled=maybe', code=4000)
    # Actor rejection precedes hidden-parent handling for existing real TECH.
    f.check(personal, 'tech', 1005)
    assert f.reads >= 100 and f.refusals >= 30 and not f.unknown_writes
    print('[4] Parent enabled/type/recordType do not restrict field reads; Boolean aliases and original4000/4066 boundaries remain intact')
    print(json.dumps({'public_field_read_snapshot_suite': True, 'checked_reads': f.reads,
        'known_refusals': f.refusals, 'exclusive_templates': len(f.templates), 'dto_keys': 11,
        'clinical_setup_or_write': False, 'deterministic_jdbc_claimed': False,
        'unknown_writes': f.unknown_writes}, ensure_ascii=False))


if __name__ == '__main__':
    main()
