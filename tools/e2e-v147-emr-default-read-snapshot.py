# -*- coding: utf-8 -*-
"""Public compatibility of default-template reads, using private DEPT fixtures.

Only public staff/department/template/grant APIs are used. These sequential
requests verify complete exposed DTOs and known refusal/recovery, not the real
JDBC statement-boundary regression (the new Java suite proves that separately).
No clinical rows are created or written. No arbitrary SQL/audit-table freeze,
browser execution or retroactive revocation of legally delivered data is claimed.
Importing the module does not log in or create fixtures; unknown writes never replay.
"""
import json
import time
import urllib.error

from e2elib import call, login, q

JOINED = {'owner_name', 'dept_name', 'grant_count', 'editable', 'scopeName'}


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V147模拟' + self.suffix
        self.tokens, self.users, self.usernames, self.templates = {'admin': login()}, {}, {}, {}
        self.reads, self.refusals, self.unknown_writes = 0, 0, []
        self.users['admin'] = self.api('GET', '/auth/me')
        self.depts = {}
        for label in ['A', 'B']:
            self.depts[label] = self.api('POST', '/system/depts', {
                'code': 'V147' + self.suffix + label, 'name': self.prefix + '科室' + label,
                'type': 'CLINICAL', 'sortNo': 9999})['id']
        for who, role, dept in [('owner', 'DOCTOR_OUTP', 'A'), ('peer', 'DOCTOR_OUTP', 'A'),
                                 ('other', 'DOCTOR_OUTP', 'B'), ('nurse', 'NURSE', 'A'),
                                 ('quality', 'QUALITY', 'A'), ('tech', 'TECHNICIAN', 'A'),
                                 ('manager', 'ADMIN', 'B'), ('cashier', 'CASHIER', 'B')]:
            name = 'v147_' + who + '_' + self.suffix; self.usernames[who] = name
            self.api('POST', '/system/users', {'username': name, 'password': 'Init@1234',
                'realName': self.prefix + who, 'roleCodes': [role]})
            first = self.api('POST', '/auth/login', {'username': name, 'password': 'Init@1234'})
            self.tokens[who] = first['token']
            self.api('POST', '/auth/change-password', {'oldPassword': 'Init@1234', 'newPassword': 'Work@1234'}, who)
            self.tokens[who] = self.api('POST', '/auth/login', {'username': name, 'password': 'Work@1234'})['token']
            self.users[who] = self.api('GET', '/auth/me', who=who)
            # Public update establishes the actual employee department.
            self.actor(who, self.depts[dept], [role])

    def api(self, method, path, body=None, who='admin', code=0):
        write = method in ['POST', 'PUT', 'PATCH', 'DELETE']
        try:
            response = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, code) not in [(401, 1000), (403, 1005)]:
                if write: self.unknown_writes.append({'method': method, 'path': path})
                raise
            try:
                response = json.load(error)
            except Exception:
                if write: self.unknown_writes.append({'method': method, 'path': path})
                raise
        except Exception:
            if write: self.unknown_writes.append({'method': method, 'path': path})
            raise
        if not isinstance(response, dict) or 'code' not in response:
            if write: self.unknown_writes.append({'method': method, 'path': path})
            raise AssertionError('Original response lacked a known envelope')
        assert response['code'] == code, (who, method, path, code, response.get('code'), response.get('message'))
        if code: assert response.get('data') is None, 'A refused read returned private default data'
        return response.get('data')

    def actor(self, who, dept, roles):
        person = self.users[who]
        current = self.api('PUT', '/system/users/' + str(person['id']), {
            'username': self.usernames[who], 'realName': person['realName'], 'phone': person.get('phone'),
            'title': person.get('title'), 'deptId': dept, 'roleCodes': roles})
        assert current['enabled'] and current['deptId'] == dept and set(current['roleCodes']) == set(roles)
        self.users[who] = current

    def template(self, label, record='OUTP', kind='EMR'):
        body = {'name': self.prefix + label, 'content': self.prefix + '完整原正文' + label,
            'templateType': kind, 'scope': 'DEPT', 'deptId': self.depts['A'], 'recordType': record}
        tid = self.api('POST', '/emr-templates/scoped', body, 'owner')
        assert isinstance(tid, int) and tid > 0; self.templates[tid] = body
        return tid

    def snapshot(self):
        heads = {}
        for tid, item in self.templates.items():
            rows = self.api('GET', '/emr-templates/visible?type=' + q(item['templateType'])
                + '&includeDisabled=true&keyword=' + q(item['name']), who='owner')
            exact = [row for row in rows if row['id'] == tid]; assert len(exact) == 1
            heads[tid] = {'head': exact[0], 'grants': self.api('GET', f'/emr-templates/{tid}/grants', who='owner')}
        return {'templates': heads, 'publicConfig': self.api('GET', '/config/public'),
            'versionSettings': self.api('GET', '/emr/versions/settings')}

    def read_path(self, path, who='owner', expected=None, code=0):
        before = self.snapshot(); result = self.api('GET', path, who=who, code=code)
        if code:
            assert result is None; self.refusals += 1
        elif expected is None:
            assert result is None, 'No enabled candidate must remain null, not an empty or sentinel row'
        else:
            original = {k: v for k, v in before['templates'][expected]['head'].items() if k not in JOINED}
            assert result == original, 'Default returns the complete original select* row, without private SQL columns'
            assert '__default_visible' not in result and 'scopeName' not in result and result['enabled'] and result['is_default']
            assert result['content'] == self.templates[expected]['content']
        assert self.snapshot() == before, 'A GET changed a complete exposed template/config projection'
        self.reads += 1
        return result

    def read(self, who='owner', expected=None, code=0, record='OUTP', dept=None):
        dept = self.depts['A'] if dept is None else dept
        return self.read_path('/emr-templates/default?deptId=' + str(dept) + '&recordType=' + q(record), who, expected, code)

    def default(self, tid, enabled=True, replace=False, code=0):
        before = self.snapshot()
        self.api('PUT' if enabled else 'DELETE', f'/emr-templates/{tid}/default'
            + ('?replace=true' if replace else ''), who='owner', code=code)
        after = self.snapshot()
        if code: assert after == before
        else:
            assert after['templates'][tid]['head']['is_default'] is enabled
            for other in self.templates:
                old = before['templates'][other]; new = after['templates'][other]
                changed = old['head']['is_default'] != new['head']['is_default']
                assert new == dict(old, head=dict(old['head'], is_default=new['head']['is_default']))
                if changed: assert other == tid or replace

    def enabled(self, tid, enabled):
        before = self.snapshot()
        self.api('PUT', f"/emr-templates/{tid}/{'enable' if enabled else 'disable'}", who='owner')
        after = self.snapshot()
        expected = dict(before['templates'][tid], head=dict(before['templates'][tid]['head'], enabled=enabled))
        assert after == dict(before, templates={**before['templates'], tid: expected})

    def grant(self, tid, kind, target):
        before = self.snapshot(); self.api('POST', f'/emr-templates/{tid}/grants', {'granteeType': kind, 'granteeId': target}, 'owner')
        after = self.snapshot(); old = before['templates'][tid]; new = after['templates'][tid]
        added = [g for g in new['grants'] if g['id'] not in {v['id'] for v in old['grants']}]
        assert len(added) == 1 and added[0]['grantee_type'] == kind and added[0]['grantee_id'] == target
        assert [g for g in new['grants'] if g['id'] != added[0]['id']] == old['grants']
        assert new['head'] == dict(old['head'], grant_count=old['head']['grant_count'] + 1)
        return added[0]['id']

    def revoke(self, tid, gid):
        before = self.snapshot(); self.api('DELETE', f'/emr-templates/{tid}/grants/{gid}', who='owner')
        after = self.snapshot(); old = before['templates'][tid]; new = after['templates'][tid]
        assert new['grants'] == [g for g in old['grants'] if g['id'] != gid]
        assert new['head'] == dict(old['head'], grant_count=old['head']['grant_count'] - 1)


def main():
    f = Fixture()
    try:
        tid = f.template('原默认')
        f.read(); f.read('other'); f.default(tid)
        for who in ['owner', 'peer', 'nurse', 'quality', 'tech', 'admin', 'manager']:
            f.read(who, tid)
        f.read('owner', tid, record=' outp ')
        f.read('other', code=4066)
        f.read('anonymous', code=1000); f.read('cashier', code=1005)
        print('[1] Original five duties retain the complete default row; no candidate is null, hidden candidate4066, security refusals payload-free')

        f.read_path('/emr-templates/default?deptId=' + str(f.depts['A']), code=4000)
        f.read_path('/emr-templates/default?recordType=OUTP', code=4000)
        f.read(record=' ', code=4067)
        f.read(record='NO_DEFAULT'); f.read(dept=f.depts['B'])
        print('[2] HTTP missing required params remains4000; present blank recordType remains4067; case/trim and empty lookups retain original semantics')

        for kind, target in [('USER', f.users['other']['id']), ('DEPT', f.depts['B'])]:
            gid = f.grant(tid, kind, target); cached = f.read('other', tid)
            f.revoke(tid, gid); f.read('other', code=4066)
            assert cached['content'] == f.templates[tid]['content'], 'Legally received client bytes are not retroactively new leakage'
            gid = f.grant(tid, kind, target); f.read('other', tid); f.revoke(tid, gid)
        print('[3] USER and DEPT grants permit current reads; known revoke blocks new reads and explicit restore succeeds without replaying writes')

        token = f.tokens['other']; f.actor('other', f.depts['A'], ['DOCTOR_OUTP']); f.read('other', tid)
        f.actor('other', f.depts['B'], ['DOCTOR_OUTP']); f.read('other', code=4066); assert f.tokens['other'] == token
        token = f.tokens['manager']; f.actor('manager', f.depts['B'], ['TECHNICIAN']); f.read('manager', code=4066)
        f.actor('manager', f.depts['B'], ['ADMIN']); f.read('manager', tid); assert f.tokens['manager'] == token
        print('[4] Existing JWT uses current employee department/DB ADMIN facts while the original entry role gate stays unchanged')

        f.enabled(tid, False); f.read(); f.read('other'); f.enabled(tid, True); f.read(expected=tid)
        f.default(tid, False); f.read(); f.read('other'); f.default(tid)
        second = f.template('替换默认'); f.default(second, code=4067); f.read(expected=tid)
        f.default(second, replace=True); f.read(expected=second); f.read('other', code=4066)
        print('[5] Disabled or unset defaults remain null; enable restores selection; existing default conflict4067 and public replacement preserve original full rows')

        ris = f.template('报告原类型', record='RISCASE', kind='RIS'); f.default(ris); f.read(expected=ris, record='RISCASE')
        requested = 'V147READLONGTYPEXX'; long_type = f.template('原开放类型', record=requested); f.default(long_type)
        actual = f.snapshot()['templates'][long_type]['head']['record_type']; assert actual == requested[:16]
        f.read(expected=long_type, record=actual); f.read(record=requested)
        print('[6] Original default lookup has no new EMR-type filter or read-side type truncation; public fixtures are DEPT only, other historical scopes belong to Java fixtures')
        assert not f.unknown_writes
        print(f'PASS v147 public default reads: {f.reads} complete projection checks, {f.refusals} known refused GETs; no clinical write, SQL or unknown-write replay')
    finally:
        if f.unknown_writes: print('UNKNOWN_WRITES (not replayed/cleaned): ' + json.dumps(f.unknown_writes, ensure_ascii=False))


if __name__ == '__main__':
    main()
