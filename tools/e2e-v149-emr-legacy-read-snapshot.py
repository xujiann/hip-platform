# -*- coding: utf-8 -*-
"""Original legacy-list public contract using exclusive staff/depts/templates.

Public full head/grant/config projections are frozen for each sequential read;
they are not arbitrary SQL/audit facts or deterministic JDBC concurrency proof.
The original full t.* DTO, literal type, dept-or-null, enabled and id ordering
stay distinct from /visible metadata. Legal delivered client caches stay legal.
Unknown writes are retained and never replayed. No clinical fixture or SQL.
"""
import json
import time
import urllib.error
from e2elib import call, login, q
GRANT_KEYS = {'id','template_id','grantee_type','grantee_id','granted_at','grantee_name','granted_by_name'}

class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V149模拟' + self.suffix
        self.tokens, self.identities, self.names, self.templates = {'admin': login()}, {}, {}, {}
        self.unknown_writes, self.refusals, self.reads = [], 0, 0
        self.identities['admin'] = self.api('GET', '/auth/me')
        for who, role in [('doctor', 'DOCTOR_OUTP'), ('peer', 'DOCTOR_OUTP'), ('other', 'DOCTOR_OUTP'),
                          ('nurse', 'NURSE'), ('quality', 'QUALITY'), ('tech', 'TECHNICIAN'),
                          ('manager', 'ADMIN'), ('cashier', 'CASHIER')]:
            self.provision(who, role)
        self.departments = {}
        for label in ['A', 'B']:
            self.departments[label] = self.api('POST', '/system/depts', {
                'code': 'V149' + self.suffix + label, 'name': self.prefix + '科室' + label,
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
            assert response.get('data') is None, 'Refused read disclosed body/grant data'
        return response.get('data')

    def provision(self, who, role):
        name = 'v149_' + who + '_' + self.suffix
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

    def template(self, label, scope='PERSONAL', who='doctor', dept=None):
        body = {'name': self.prefix + label, 'content': self.prefix + '原合法正文' + label,
            'templateType': 'EMR', 'scope': scope, 'deptId': dept, 'recordType': 'OUTP'}
        tid = self.api('POST', '/emr-templates/scoped', body, who)
        assert isinstance(tid, int) and tid > 0
        self.templates[tid] = {'owner': who, 'body': body}
        return tid

    def snapshot(self, tid):
        fixture = self.templates[tid]
        rows = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword=' + q(fixture['body']['name']), who=fixture['owner'])
        found = [row for row in rows if row['id'] == tid]
        assert len(found) == 1, 'Exact private fixture owner must retain their original visible head'
        grants = self.api('GET', f'/emr-templates/{tid}/grants', who=fixture['owner'])
        assert all(set(row) == GRANT_KEYS for row in grants), 'Internal parent/CTE sentinels must not enter the seven-key DTO'
        assert grants == sorted(grants, key=lambda row: (row['grantee_type'], row['id']))
        return {'head': found[0], 'grants': grants, 'publicConfig': self.api('GET', '/config/public'),
            'versionSettings': self.api('GET', '/emr/versions/settings')}



    def grant(self, tid, kind, target):
        before = self.snapshot(tid)
        self.api('POST', f'/emr-templates/{tid}/grants', {'granteeType': kind, 'granteeId': target}, self.templates[tid]['owner'])
        after = self.snapshot(tid)
        added = [row for row in after['grants'] if row['id'] not in {g['id'] for g in before['grants']}]
        assert len(added) == 1 and added[0]['grantee_type'] == kind and added[0]['grantee_id'] == target
        assert [row for row in after['grants'] if row['id'] != added[0]['id']] == before['grants']
        assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] + 1)
        return added[0]['id']

    def revoke(self, tid, gid):
        before = self.snapshot(tid)
        self.api('DELETE', f'/emr-templates/{tid}/grants/{gid}', who=self.templates[tid]['owner'])
        after = self.snapshot(tid)
        assert after['grants'] == [row for row in before['grants'] if row['id'] != gid]
        assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] - 1)

    def update(self, tid, **changes):
        item = self.templates[tid]
        body = dict(item['body'], **changes)
        before = self.snapshot(tid)
        self.api('PUT', f'/emr-templates/{tid}', body, item['owner'])
        item['body'] = body
        after = self.snapshot(tid)
        assert after['head']['scope'] == body['scope'] and after['head']['content'] == body['content']
        for key in ['owner_id', 'created_by', 'created_at']:
            assert after['head'][key] == before['head'][key]

    def enabled(self, tid, value):
        before = self.snapshot(tid)
        self.api('PUT', f"/emr-templates/{tid}/{'enable' if value else 'disable'}", who=self.templates[tid]['owner'])
        after = self.snapshot(tid)
        assert after == dict(before, head=dict(before['head'], enabled=value))

HEAD_KEYS = {'id','dept_id','name','content','template_type','enabled','scope','owner_id','record_type',
             'is_default','created_by','created_at'}
VISIBLE_KEYS = HEAD_KEYS | {'owner_name','dept_name','grant_count','editable','scopeName'}


class LegacyFixture(Fixture):
    def template(self, label, scope='PERSONAL', who='doctor', dept=None, kind='RIS'):
        body = {'name': self.prefix + label, 'content': self.prefix + '原合法影像正文' + label,
                'templateType': kind, 'scope': scope, 'deptId': dept, 'recordType': 'OUTP'}
        tid = self.api('POST', '/emr-templates/scoped', body, who)
        assert isinstance(tid, int) and tid > 0
        self.templates[tid] = {'owner': who, 'body': body}
        return tid

    def snapshot(self, tid):
        item = self.templates[tid]
        rows = self.api('GET', '/emr-templates/visible?includeDisabled=true&type=' + q(item['body']['templateType'])
                        + '&keyword=' + q(item['body']['name']), who=item['owner'])
        found = [row for row in rows if row['id'] == tid]
        assert len(found) == 1 and set(found[0]) == VISIBLE_KEYS
        grants = self.api('GET', f'/emr-templates/{tid}/grants', who=item['owner'])
        assert all(set(g) == GRANT_KEYS for g in grants)
        return {'head': found[0], 'grants': grants, 'publicConfig': self.api('GET', '/config/public'),
                'versionSettings': self.api('GET', '/emr/versions/settings')}

    def all_snapshot(self):
        return {tid: self.snapshot(tid) for tid in self.templates}

    def legacy(self, who='doctor', code=0, **filters):
        before = self.all_snapshot()
        query = '&'.join(q(key) + '=' + q(str(value)) for key, value in filters.items() if value is not None)
        actual = self.api('GET', '/emr-templates' + ('?' + query if query else ''), who=who, code=code)
        if code:
            assert actual is None; self.refusals += 1
        else:
            assert isinstance(actual, list) and all(set(row) == HEAD_KEYS for row in actual)
            assert [row['id'] for row in actual] == sorted(row['id'] for row in actual)
            user = self.identities[who]; uid, department = user['id'], user.get('deptId')
            admin = 'ADMIN' in user.get('roleCodes', user.get('roles', []))
            expected = []
            for tid, fixture in before.items():
                row, grants = fixture['head'], fixture['grants']; scope = row['scope']
                permitted = scope in ['GLOBAL','HOSPITAL'] or row['owner_id'] == uid
                permitted = permitted or scope == 'DEPT' and (admin or department is not None and row['dept_id'] == department)
                permitted = permitted or any(g['grantee_type'] == 'USER' and g['grantee_id'] == uid or
                    g['grantee_type'] == 'DEPT' and department is not None and g['grantee_id'] == department for g in grants)
                if not row['enabled'] or not permitted: continue
                if filters.get('deptId') is not None and row['dept_id'] not in [None, int(filters['deptId'])]: continue
                # Unlike /visible, legacy's nonblank type is deliberately literal.
                if str(filters.get('type') or '').strip() and row['template_type'] != str(filters['type']): continue
                expected.append({key: value for key, value in row.items() if key in HEAD_KEYS})
            expected.sort(key=lambda row: row['id'])
            owned = [row for row in actual if row['id'] in self.templates]
            assert owned == expected, (who, filters, owned, expected)
            self.reads += 1
        assert self.all_snapshot() == before, 'Legacy read changed a complete public head/grant/config projection'
        return actual


def main():
    f = LegacyFixture(); a, b = f.departments['A'], f.departments['B']
    glob = f.template('01全局', 'GLOBAL', 'manager')
    hospital = f.template('02全院', 'HOSPITAL', 'manager')
    dept = f.template('03科室', 'DEPT', 'doctor', a)
    personal = f.template('04个人', 'PERSONAL', 'doctor')
    own = {who: f.template(label, 'PERSONAL', who) for label,who in
           [('05护理','nurse'),('06质控','quality'),('07医技','tech')]}
    emr = f.template('08普通病历', 'HOSPITAL', 'manager', kind='EMR')
    for who in ['doctor','peer','other','nurse','quality','tech','manager']:
        for department in [None, a, b]: f.legacy(who, type='RIS', deptId=department)
    f.legacy('anonymous', code=1000, type='RIS')
    f.legacy('cashier', code=1005, type='RIS')
    # USER and DEPT grants extend read, even on PERSONAL; no metadata is added.
    gid = f.grant(personal, 'USER', f.identities['tech']['id'])
    cached = next(row for row in f.legacy('tech', type='RIS') if row['id'] == personal)
    f.revoke(personal, gid)
    assert not any(row['id'] == personal for row in f.legacy('tech', type='RIS'))
    assert cached['content'] == f.templates[personal]['body']['content'], 'Previously legal cache is not an instantaneous-revocation contract'
    f.grant(personal, 'USER', f.identities['tech']['id']); f.legacy('tech', type='RIS')
    dg = f.grant(personal, 'DEPT', b); f.legacy('quality', type='RIS'); f.revoke(personal, dg); f.legacy('quality', type='RIS')
    # These are ordinary sequential public calls, not the Java JDBC race proof.
    f.set_actor('other', dept=a); f.legacy('other', type='RIS'); f.set_actor('other', dept=b); f.legacy('other', type='RIS')
    f.legacy('manager', type='RIS'); f.set_actor('manager', roles=['TECHNICIAN']); f.legacy('manager', type='RIS')
    f.set_actor('manager', roles=['ADMIN']); f.legacy('manager', type='RIS')
    f.enabled(dept, False); f.legacy('doctor', type='RIS'); f.enabled(dept, True); f.legacy('doctor', type='RIS')
    # A default flag does not replace the original legacy order-by-id policy.
    f.api('PUT', f'/emr-templates/{dept}/default?replace=false', who='doctor')
    f.legacy('doctor', type='RIS')
    f.api('DELETE', f'/emr-templates/{dept}/default', who='doctor'); f.legacy('doctor', type='RIS')
    for kind in [None, '', '  ', 'RIS', 'ris', ' RIS ', 'EMR', 'UNKNOWN', 'RIS%']:
        f.legacy('doctor', type=kind)
    f.legacy('doctor', type='RIS', deptId=899999999999999)
    f.legacy('doctor', code=4000, type='RIS', deptId='not-a-long')
    assert f.reads >= 40 and f.refusals == 3 and not f.unknown_writes
    assert len(f.templates) == 8 and len(own) == 3 and glob != hospital != emr
    print('V149 legacy template public contract passed:', f.reads, 'complete-DTo observations,',
          f.refusals, 'original refusals; no clinical fixture, SQL, arbitrary audit freeze or JDBC-race claim')


if __name__ == '__main__':
    main()
