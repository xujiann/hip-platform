# -*- coding: utf-8 -*-
"""Public compatibility of HEAD body/grant reads and their original matrix.

Private public-API staff/departments/templates only. Observations cover complete
public head/grant DTOs and public config projections, not arbitrary SQL tables.
The real-JWT Java suite separately schedules the exact real JDBC boundary to
prove the old mixed-snapshot failure; this sequential HTTP suite does not claim
that a request barrier or already delivered client cache is a new disclosure.
Import does not login, request, create fixtures or execute SQL.
"""
import json
import time
import urllib.error

from e2elib import call, login, q

MISSING = 9000000000000000
GRANT_KEYS = {'id', 'template_id', 'grantee_type', 'grantee_id', 'granted_at', 'grantee_name', 'granted_by_name'}
SCOPES = {'GLOBAL': '全局', 'HOSPITAL': '全院', 'DEPT': '科室', 'PERSONAL': '个人'}


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V146模拟' + self.suffix
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
                'code': 'V146' + self.suffix + label, 'name': self.prefix + '科室' + label,
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
        name = 'v146_' + who + '_' + self.suffix
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

    def read(self, tid, who, enabled=True):
        before = self.snapshot(tid)
        body = self.api('GET', f'/emr-templates/{tid}', who=who, code=0 if enabled else 4066)
        grants = self.api('GET', f'/emr-templates/{tid}/grants', who=who)
        assert grants == before['grants']
        if enabled:
            joined = {'owner_name', 'dept_name', 'grant_count', 'editable'}
            assert body == {key: value for key, value in before['head'].items() if key not in joined}
            assert body['scopeName'] == SCOPES[body['scope']] and body['enabled']
            assert body['content'] == self.templates[tid]['body']['content']
        else:
            assert body is None and before['head']['enabled'] is False
        assert self.snapshot(tid) == before, 'Original GET changed an exposed template/config projection'
        self.reads += 2
        return body, grants

    def deny(self, tid, who, code=4066, request_tid=None):
        before = self.snapshot(tid)
        target = tid if request_tid is None else request_tid
        for path in [f'/emr-templates/{target}', f'/emr-templates/{target}/grants']:
            assert self.api('GET', path, who=who, code=code) is None
            assert self.snapshot(tid) == before
            self.refusals += 1

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


def main():
    f = Fixture()
    try:
        private = {}
        for who in ['admin', 'doctor', 'nurse', 'quality', 'tech']:
            private[who] = f.template(who + '个人', who=who)
            f.read(private[who], who)
        tid = private['doctor']
        for who in ['admin', 'manager', 'other', 'peer', 'nurse', 'quality', 'tech']:
            f.deny(tid, who)
        f.deny(tid, 'doctor', request_tid=MISSING)
        for who, code in [('anonymous', 1000), ('cashier', 1005)]:
            f.deny(tid, who, code)
        print('[1] Original five HEAD duties read own PERSONAL; ADMIN has no foreign PERSONAL bypass; missing and security boundaries remain payload-free')

        for scope in ['GLOBAL', 'HOSPITAL']:
            public = f.template(scope, scope, 'admin')
            for who in ['admin', 'manager', 'doctor', 'other', 'nurse', 'quality', 'tech']:
                body, grants = f.read(public, who)
                assert body['scope'] == scope and grants == [], 'Visible zero-grant parent is [] rather than missing or a sentinel DTO'
        dept = f.template('科室', 'DEPT', dept=f.departments['A'])
        for who in ['doctor', 'peer', 'nurse', 'admin', 'manager']:
            f.read(dept, who)
        for who in ['other', 'quality', 'tech']:
            f.deny(dept, who)
        print('[2] GLOBAL/HOSPITAL empty grants and original DEPT owner/same-department/ADMIN rules preserve complete DTOs and ordering')

        for kind, target, readers in [('USER', f.identities['other']['id'], ['other']),
                                      ('DEPT', f.departments['B'], ['other', 'quality', 'tech', 'manager'])]:
            gid = f.grant(tid, kind, target)
            cached = {who: f.read(tid, who) for who in readers}
            f.revoke(tid, gid)
            for who in readers:
                f.deny(tid, who)
                assert cached[who][0]['content'] == f.templates[tid]['body']['content']
            gid = f.grant(tid, kind, target)
            for who in readers:
                f.read(tid, who)
            f.revoke(tid, gid)
        print('[3] Real USER/DEPT grants overlay PERSONAL, including department grant; known revoke blocks new reads and explicit restore permits them; legal cached data is not relabeled a leak')

        gid = f.grant(tid, 'USER', f.identities['other']['id'])
        f.enabled(tid, False)
        f.read(tid, 'doctor', enabled=False); f.read(tid, 'other', enabled=False)
        f.enabled(tid, True); f.read(tid, 'other'); f.revoke(tid, gid)
        print('[4] Disabled visible parents refuse new body use4066 while original grants remain readable; enable restores legal body use')

        cached, _ = f.read(dept, 'peer')
        f.update(dept, scope='PERSONAL', deptId=None, content=f.prefix + '撤回范围后的新私密正文')
        f.deny(dept, 'peer'); f.deny(dept, 'admin'); f.read(dept, 'doctor')
        assert cached['content'] != f.templates[dept]['body']['content']
        f.update(dept, scope='DEPT', deptId=f.departments['A'], content=f.prefix + '重新公开的合法正文')
        body, _ = f.read(dept, 'peer'); assert body['scopeName'] == '科室'
        print('[5] Public scope/content changes are observed coherently by later reads, without pretending a sequential HTTP check reproduces the internal JDBC window')

        token = f.tokens['other']
        f.set_actor('other', dept=f.departments['A']); f.read(dept, 'other')
        f.set_actor('other', dept=f.departments['B']); f.deny(dept, 'other')
        assert f.tokens['other'] == token
        token = f.tokens['manager']
        f.set_actor('manager', roles=['TECHNICIAN']); f.deny(dept, 'manager')
        f.set_actor('manager', roles=['ADMIN']); f.read(dept, 'manager')
        assert f.tokens['manager'] == token and not f.unknown_writes
        print('[6] Existing JWT requests use current department/admin database facts without changing the original HEAD role/enabled policy')
        print(f'PASS v146 public HEAD reads: {f.reads} successful/disabled paired reads, {f.refusals} known refused GETs; no clinical/field write or unknown write replay')
    finally:
        if f.unknown_writes:
            print('UNKNOWN_WRITES (not replayed/cleaned): ' + json.dumps(f.unknown_writes, ensure_ascii=False))


if __name__ == '__main__':
    main()
