# -*- coding: utf-8 -*-
"""Public compatibility for current-actor HEAD template maintenance.

Only public HTTP APIs create private staff/departments/templates/grants. Full
visible head and grant DTO snapshots freeze every known refusal. The Java JWT /
named-holder PostgreSQL suite separately proves the actual wait interleavings;
this script does not claim that an HTTP start barrier proves old-source RED.
Import is safe: no login, HTTP, fixture, or database work runs on import.
"""
import json
import time
import urllib.error

from e2elib import call, login, provision_user, q

MISSING = 9000000000000000


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V144模拟' + self.suffix
        self.tokens = {'admin': login()}
        self.identities, self.names, self.templates = {}, {}, {}
        self.unknown_writes, self.refusals, self.sequence = [], 0, 0
        for who, role in [('doctor', 'DOCTOR_OUTP'), ('peer', 'DOCTOR_OUTP'),
                          ('other', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                          ('quality', 'QUALITY'), ('tech', 'TECHNICIAN'),
                          ('cashier', 'CASHIER')]:
            self.names[who] = 'v144_' + who + '_' + self.suffix
            self.tokens[who] = provision_user(self.tokens['admin'], self.names[who], role, self.prefix + who)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
        self.departments = {}
        for label in ['A', 'B']:
            self.departments[label] = self.api('POST', '/system/depts', {
                'code': 'V144' + self.suffix + label,
                'name': self.prefix + '科室' + label, 'type': 'CLINICAL', 'sortNo': 9999})['id']
        for who in ['doctor', 'peer', 'nurse']:
            self.set_actor(who, dept=self.departments['A'])
        for who in ['other', 'quality', 'tech', 'cashier']:
            self.set_actor(who, dept=self.departments['B'])

    def api(self, method, path, body=None, who='admin', code=0):
        def unknown():
            if method in ['POST', 'PUT', 'PATCH', 'DELETE']:
                self.unknown_writes.append({'method': method, 'path': path})
        try:
            result = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, code) not in [(401, 1000), (403, 1005)]:
                unknown()
                raise
            try:
                result = json.load(error)
            except Exception:
                unknown()
                raise
        except Exception:
            unknown()
            raise
        if not isinstance(result, dict) or 'code' not in result:
            unknown()
            raise AssertionError('Original response lacked a known envelope')
        assert result['code'] == code, (who, method, path, code, result.get('code'), result.get('message'))
        if code:
            assert result.get('data') is None, 'Refusal disclosed a template payload'
        return result.get('data')

    def set_actor(self, who, dept=None, roles=None):
        person = self.identities[who]
        expected_roles = roles if roles is not None else person.get('roleCodes', person.get('roles'))
        assert expected_roles, 'A real public fixture must retain its assigned duty'
        expected_dept = dept if dept is not None else person.get('deptId')
        result = self.api('PUT', '/system/users/' + str(person['id']), {
            'username': self.names[who], 'realName': person['realName'], 'title': person.get('title'),
            'phone': person.get('phone'), 'deptId': expected_dept, 'roleCodes': expected_roles})
        assert result['id'] == person['id'] and result['enabled']
        assert result['deptId'] == expected_dept and set(result['roleCodes']) == set(expected_roles)
        self.identities[who] = result

    def template(self, label, scope='PERSONAL', who='doctor', dept=None, record_type=None):
        self.sequence += 1
        body = {'name': self.prefix + label, 'content': 'V144模拟模板正文', 'templateType': 'EMR',
                'scope': scope, 'deptId': dept, 'recordType': record_type or ('V144_' + str(self.sequence))}
        tid = self.api('POST', '/emr-templates/scoped', body, who)
        assert isinstance(tid, int) and tid > 0
        self.templates[tid] = {'owner': who, 'body': body}
        head = self.snapshot(tid)['head']
        assert head['scope'] == scope and head['enabled'] and not head['is_default']
        return tid

    def head(self, tid, who):
        rows = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword=' + q(self.prefix), who=who)
        return next((row for row in rows if row['id'] == tid), None)

    def snapshot(self, tid):
        observer = self.templates[tid].get('reader', self.templates[tid]['owner'])
        head = self.head(tid, observer)
        assert head is not None, 'Owner lost their exact private fixture'
        return {'head': head, 'grants': self.api('GET', f'/emr-templates/{tid}/grants', who=observer)}

    def deny(self, tid, method, path, body=None, who='other', code=4066):
        before = self.snapshot(tid)
        self.api(method, path, body, who, code)
        assert self.snapshot(tid) == before, (who, method, path, 'Refusal changed template business state')
        self.refusals += 1

    def deny_writes(self, tid, who, code=4066):
        fixture = self.templates[tid]
        grant_id = self.snapshot(tid)['grants'][0]['id'] if self.snapshot(tid)['grants'] else MISSING
        update = dict(fixture['body'], name=self.prefix + '不应改写', content='不得写入')
        for method, path, body in [
            ('PUT', f'/emr-templates/{tid}', update),
            ('PUT', f'/emr-templates/{tid}/disable', None),
            ('PUT', f'/emr-templates/{tid}/enable', None),
            ('POST', f'/emr-templates/{tid}/grants', {'granteeType': 'USER', 'granteeId': self.identities['other']['id']}),
            ('DELETE', f'/emr-templates/{tid}/grants/{grant_id}', None),
            ('PUT', f'/emr-templates/{tid}/default?replace=false', None),
            ('PUT', f'/emr-templates/{tid}/default?replace=true', None),
            ('DELETE', f'/emr-templates/{tid}/default', None)]:
            self.deny(tid, method, path, body, who, code)

    def update(self, tid, who=None, **changes):
        owner = self.templates[tid]['owner']
        body = dict(self.templates[tid]['body'], **changes)
        before = self.snapshot(tid)
        assert self.api('PUT', f'/emr-templates/{tid}', body, who or owner) is None
        self.templates[tid]['body'] = body
        after = self.snapshot(tid)
        assert after['head']['name'] == body['name'].strip() and after['head']['content'] == body['content']
        assert after['head']['scope'] == body['scope']
        assert after['head']['owner_id'] == before['head']['owner_id']
        assert after['head']['created_by'] == before['head']['created_by']
        assert after['head']['created_at'] == before['head']['created_at']
        if body['scope'] == before['head']['scope'] and after['head']['dept_id'] == before['head']['dept_id']:
            assert after['grants'] == before['grants']
        return after

    def enabled(self, tid, enabled, who=None):
        before = self.snapshot(tid)
        assert self.api('PUT', f"/emr-templates/{tid}/{'enable' if enabled else 'disable'}",
                        who=who or self.templates[tid]['owner']) is None
        after = self.snapshot(tid)
        assert after['head'] == dict(before['head'], enabled=enabled) and after['grants'] == before['grants']

    def grant(self, tid, kind, target, who=None):
        before = self.snapshot(tid)
        assert self.api('POST', f'/emr-templates/{tid}/grants', {'granteeType': kind, 'granteeId': target},
                        who or self.templates[tid]['owner']) is None
        after = self.snapshot(tid)
        matches = [g for g in after['grants'] if g['grantee_type'] == kind and g['grantee_id'] == target]
        assert len(matches) == 1
        existing = [g for g in before['grants'] if g['grantee_type'] == kind and g['grantee_id'] == target]
        if existing:
            assert after == before, 'Duplicate grant is a complete idempotent no-op'
        else:
            assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] + 1)
            assert [g for g in after['grants'] if g['id'] != matches[0]['id']] == before['grants']
        return matches[0]['id']

    def revoke(self, tid, grant_id, who=None):
        before = self.snapshot(tid)
        assert self.api('DELETE', f'/emr-templates/{tid}/grants/{grant_id}',
                        who=who or self.templates[tid]['owner']) is None
        after = self.snapshot(tid)
        assert after['head'] == dict(before['head'], grant_count=before['head']['grant_count'] - 1)
        assert after['grants'] == [g for g in before['grants'] if g['id'] != grant_id]


def main():
    f = Fixture()
    try:
        personal = {}
        for who in ['admin', 'doctor', 'nurse', 'quality', 'tech']:
            tid = f.template(who + '个人', who=who)
            f.update(tid, content='V144五岗合法正文' + who)
            f.enabled(tid, False)
            f.update(tid, content='V144已停用模板仍可合法维护' + who)
            f.enabled(tid, True)
            gid = f.grant(tid, 'USER', f.identities['peer']['id'])
            f.grant(tid, 'USER', f.identities['peer']['id'])
            f.revoke(tid, gid)
            personal[who] = tid
        private = personal['doctor']
        for who in ['admin', 'other', 'nurse', 'quality', 'tech']:
            assert f.head(private, who) is None
            f.deny_writes(private, who)
        for who, code in [('anonymous', 1000), ('cashier', 1005)]:
            f.deny_writes(private, who, code)
        print('[1] All five original duties retain own PERSONAL maintenance; unrelated ADMIN, anonymous and other duties cannot bypass it')

        for scope in ['GLOBAL', 'HOSPITAL']:
            tid = f.template(scope, scope, 'admin')
            for who in ['doctor', 'nurse', 'quality', 'tech']:
                assert f.head(tid, who)['editable'] is False
                f.deny_writes(tid, who)
            f.update(tid, content='V144合法院级维护')
            f.enabled(tid, False); f.enabled(tid, True)
        dept = f.template('科室', 'DEPT', dept=f.departments['A'])
        for who in ['peer', 'nurse', 'admin']:
            assert f.head(dept, who) is not None
        for who in ['peer', 'nurse', 'other', 'quality', 'tech']:
            f.deny_writes(dept, who)
        f.update(dept, 'admin', content='V144管理员合法维护科室模板')
        f.enabled(dept, False, 'admin'); f.enabled(dept, True, 'admin')
        print('[2] GLOBAL/HOSPITAL remain ADMIN maintained; DEPT owner and ADMIN maintenance remains usable without granting peers edit access')

        for tid in [private, dept]:
            gid = f.grant(tid, 'USER', f.identities['other']['id'])
            assert f.head(tid, 'other')['editable'] is False
            f.deny_writes(tid, 'other'); f.revoke(tid, gid)
            assert f.head(tid, 'other') is None
            gid = f.grant(tid, 'DEPT', f.departments['B'])
            for who in ['other', 'quality', 'tech']:
                assert f.head(tid, who)['editable'] is False
                f.deny_writes(tid, who)
            f.revoke(tid, gid)
            for who in ['other', 'quality', 'tech']:
                assert f.head(tid, who) is None
        self_grant = f.snapshot(private)['grants'][0]['id']
        f.deny(private, 'DELETE', f'/emr-templates/{private}/grants/{self_grant}', who='doctor', code=4065)
        f.deny(private, 'DELETE', f'/emr-templates/{private}/grants/{MISSING}', who='doctor', code=4065)
        f.deny(private, 'POST', f'/emr-templates/{private}/grants', {'granteeType': 'BAD', 'granteeId': 1}, 'doctor', 4065)
        f.deny(private, 'POST', f'/emr-templates/{private}/grants', {'granteeType': 'USER', 'granteeId': MISSING}, 'doctor', 4065)
        print('[3] Real USER/DEPT grants expand visibility only; idempotence, original 4065 and protected auto-grants remain intact')

        # The same JWT string is reused. The existing authentication filter
        # already reloads DB roles/enabled on every fresh request.
        # Independent real granted reader can freeze every refusal even while
        # the owner is disabled or loses all permitted template duties.
        f.grant(private, 'USER', f.identities['peer']['id'])
        f.templates[private]['reader'] = 'peer'
        old_token = f.tokens['doctor']
        f.set_actor('doctor', roles=['CASHIER'])
        f.deny_writes(private, 'doctor', 1005)
        f.set_actor('doctor', roles=['TECHNICIAN'])
        f.update(private, content='V144岗位切为TECH仍在原五岗')
        f.set_actor('doctor', roles=['DOCTOR_OUTP'])
        uid = f.identities['doctor']['id']
        f.api('PUT', f'/system/users/{uid}/enabled?enabled=false')
        f.deny_writes(private, 'doctor', 1000)
        f.api('PUT', f'/system/users/{uid}/enabled?enabled=true')
        assert f.tokens['doctor'] == old_token
        f.update(private, content='V144恢复后合法维护')
        print('[4] Fresh requests with the same JWT observe withdrawn duty and disabled account; original TECH duty and restored access remain valid')

        converted = f.template('范围转换', 'DEPT', dept=f.departments['A'])
        f.grant(converted, 'USER', f.identities['other']['id'])
        owner_id = f.snapshot(converted)['head']['owner_id']
        after = f.update(converted, 'admin', scope='PERSONAL', deptId=None)
        assert after['head']['owner_id'] == owner_id and after['head']['dept_id'] is None
        assert [(g['grantee_type'], g['grantee_id']) for g in after['grants']] == [('USER', owner_id)]
        assert f.head(converted, 'admin') is None and f.head(converted, 'other') is None
        f.deny_writes(converted, 'admin')
        f.update(converted, scope='DEPT', deptId=f.departments['A'])
        auto = f.snapshot(converted)['grants']
        assert [(g['grantee_type'], g['grantee_id']) for g in auto] == [('DEPT', f.departments['A'])]
        print('[5] Public scope conversion preserves owner, clears obsolete grants and regrants the real owner/department, never the ADMIN editor')

        slot = 'V144_SLOT'
        a = f.template('默认甲', 'DEPT', dept=f.departments['A'], record_type=slot)
        b = f.template('默认乙', 'DEPT', who='peer', dept=f.departments['A'], record_type=slot)
        f.api('PUT', f'/emr-templates/{a}/default', who='doctor')
        assert f.snapshot(a)['head']['is_default']
        f.deny(b, 'PUT', f'/emr-templates/{b}/default', who='peer', code=4067)
        before_a, before_b = f.snapshot(a), f.snapshot(b)
        f.api('PUT', f'/emr-templates/{b}/default?replace=true', who='peer')
        assert f.snapshot(a) == dict(before_a, head=dict(before_a['head'], is_default=False))
        assert f.snapshot(b) == dict(before_b, head=dict(before_b['head'], is_default=True))
        # A target owner may replace the slot even without edit rights over
        # the old occupant. This is the original public replace contract.
        assert f.head(a, 'peer')['editable'] is False
        f.enabled(b, False, 'peer')
        assert f.snapshot(b)['head']['is_default']  # deliberate historical flag
        f.deny(b, 'PUT', f'/emr-templates/{b}/default', who='peer', code=4067)
        f.api('PUT', f'/emr-templates/{a}/default', who='doctor')
        f.deny(b, 'PUT', f'/emr-templates/{b}/enable', who='peer', code=4067)
        f.api('DELETE', f'/emr-templates/{b}/default', who='peer')
        f.enabled(b, True, 'peer')
        f.api('DELETE', f'/emr-templates/{a}/default', who='doctor')
        assert not f.snapshot(a)['head']['is_default'] and not f.snapshot(b)['head']['is_default']
        f.deny(private, 'PUT', f'/emr-templates/{private}/default', who='doctor', code=4067)
        # Changing the slot vacates a default marker before changing identity.
        f.api('PUT', f'/emr-templates/{a}/default', who='doctor')
        f.update(a, recordType='V144_MOVED')
        assert not f.snapshot(a)['head']['is_default']
        for method, path, payload in [
            ('PUT', f'/emr-templates/{MISSING}', f.templates[private]['body']),
            ('PUT', f'/emr-templates/{MISSING}/enable', None),
            ('PUT', f'/emr-templates/{MISSING}/disable', None),
            ('POST', f'/emr-templates/{MISSING}/grants', {'granteeType': 'USER', 'granteeId': uid}),
            ('DELETE', f'/emr-templates/{MISSING}/grants/{MISSING}', None),
            ('PUT', f'/emr-templates/{MISSING}/default', None),
            ('DELETE', f'/emr-templates/{MISSING}/default', None)]:
            f.deny(private, method, path, payload, 'doctor')
        assert not f.unknown_writes
        print('[6] Default replace, disabled historical flags, unique-slot 4067, moving-slot release and missing-target 4066 remain public-compatible')
        print(f'PASS v144 public template maintenance: {f.refusals} frozen refusals; {len(f.templates)} private templates; no unknown write replay')
    finally:
        # Preserve private evidence. Never guess an uncertain write by replaying
        # it or deleting its rows; no clinical capacity is consumed here.
        if f.unknown_writes:
            print('UNKNOWN_WRITES (not replayed/cleaned up): ' + json.dumps(f.unknown_writes, ensure_ascii=False))


if __name__ == '__main__':
    main()
