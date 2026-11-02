# -*- coding: utf-8 -*-
"""Public compatibility for template scope/department updates in the actual package.

Private staff/departments/templates/grants are created only through public APIs.
This sequential HTTP check proves normal and refused package behavior, not the
grant-row race: V155EmrTemplateUpdateGrantWaitTest proves the named PostgreSQL
holder, committed employee change, whole-state rollback and fresh department.
No clinical rows are created or changed; no concurrent-write claim is made here.
Import performs no HTTP, login or fixture work. Unknown writes are never replayed.
"""
import json
import time
import urllib.error

from e2elib import call, login, provision_user, q


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V155模拟' + self.suffix
        self.tokens, self.people, self.names, self.departments = {}, {}, {}, {}
        self.unknown_writes = []
        self.refusals = 0

    def api(self, method, path, body=None, who='admin', code=0):
        def unknown():
            if method in ('POST', 'PUT', 'PATCH', 'DELETE'):
                self.unknown_writes.append({'method': method, 'path': path})
        try:
            result = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if (error.code, code) not in ((401, 1000), (403, 1005)):
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
        if not isinstance(result, dict) or set(result) != {'code', 'message', 'data'}:
            unknown()
            raise AssertionError('Original HTTP response lacked the expected envelope')
        assert result['code'] == code, (who, method, path, code, result)
        if code:
            assert result['data'] is None, 'Refusal must not disclose a template payload'
        return result['data']

    def setup(self):
        self.tokens['admin'] = login()
        for who, duty in [('owner', 'DOCTOR_OUTP'), ('editor', 'ADMIN'), ('reader', 'NURSE')]:
            self.names[who] = 'v155_' + who + '_' + self.suffix
            try:
                self.tokens[who] = provision_user(self.tokens['admin'], self.names[who], duty,
                                                  self.prefix + who)
            except Exception:
                self.unknown_writes.append({'method': 'provision_user', 'username': self.names[who]})
                raise
            self.people[who] = self.api('GET', '/auth/me', who=who)
        for label in ('A', 'B'):
            self.departments[label] = self.api('POST', '/system/depts', {
                'code': 'V155' + label + self.suffix, 'name': self.prefix + '科室' + label,
                'type': 'CLINICAL', 'sortNo': 9999})
        self.set_actor('owner', ['DOCTOR_OUTP'], 'A')
        self.set_actor('editor', ['ADMIN'], 'B')
        self.set_actor('reader', ['NURSE'], 'B')

    def set_actor(self, who, roles, department):
        person = self.people[who]
        body = {'username': self.names[who], 'realName': person['realName'],
                'title': person.get('title'), 'phone': person.get('phone'),
                'deptId': self.departments[department]['id'], 'roleCodes': roles}
        result = self.api('PUT', '/system/users/' + str(person['id']), body)
        assert result['id'] == person['id'] and result['enabled']
        assert result['deptId'] == body['deptId'] and set(result['roleCodes']) == set(roles)
        self.people[who] = result

    def head(self, tid, who='owner'):
        rows = self.api('GET', '/emr-templates/visible?type=EMR&includeDisabled=true&keyword='
                        + q(self.prefix), who=who)
        return next((row for row in rows if row['id'] == tid), None)

    def snapshot(self, tid):
        head = self.head(tid)
        assert head is not None, 'The real owner must retain this private fixture'
        return {'head': head, 'grants': self.api('GET', f'/emr-templates/{tid}/grants', who='owner')}

    def payload(self, head, **changes):
        return dict({'name': head['name'], 'content': head['content'],
                     'templateType': head['template_type'], 'scope': head['scope'],
                     'deptId': head['dept_id'], 'recordType': head['record_type']}, **changes)

    def deny(self, tid, body, who, code):
        before = self.snapshot(tid)
        self.api('PUT', f'/emr-templates/{tid}', body, who, code)
        assert self.snapshot(tid) == before, 'A refused scope update changed a complete public head/grant snapshot'
        self.refusals += 1

    def auto_grant(self, snapshot, kind, beneficiary, previous):
        rows = snapshot['grants']
        assert len(rows) == 1
        grant = rows[0]
        assert set(grant) == {'id', 'template_id', 'grantee_type', 'grantee_id', 'granted_at',
                              'grantee_name', 'granted_by_name'}
        assert grant['id'] not in {row['id'] for row in previous['grants']}
        assert grant['template_id'] == snapshot['head']['id']
        assert (grant['grantee_type'], grant['grantee_id']) == (kind, beneficiary)
        assert grant['granted_at'] is not None
        assert grant['granted_by_name'] == self.people['owner']['realName'], 'Automatic grant belongs to the owner'

    def changed(self, tid, body, scope, department, who='owner'):
        before = self.snapshot(tid)
        self.api('PUT', f'/emr-templates/{tid}', body, who)
        after = self.snapshot(tid)
        expected = dict(before['head'], name=body['name'].strip(), content=body['content'],
                        scope=scope, scopeName={'PERSONAL': '个人', 'DEPT': '科室'}[scope],
                        dept_id=self.departments[department]['id'] if department else None,
                        dept_name=self.departments[department]['name'] if department else None,
                        is_default=False, grant_count=1)
        assert after['head'] == expected, 'Scope conversion may change only the specified head/default/count fields'
        beneficiary = self.departments[department]['id'] if department else self.people['owner']['id']
        self.auto_grant(after, 'DEPT' if department else 'USER', beneficiary, before)
        return after


def main():
    f = Fixture()
    try:
        f.setup()
        tid = f.api('POST', '/emr-templates/scoped', {
            'name': f.prefix + '个人', 'content': 'V155模拟原正文', 'templateType': 'EMR',
            'scope': 'PERSONAL', 'recordType': 'V155_SLOT'}, 'owner')
        first = f.snapshot(tid)
        assert first['head']['owner_id'] == f.people['owner']['id']
        assert [(g['grantee_type'], g['grantee_id']) for g in first['grants']] == [
            ('USER', f.people['owner']['id'])]
        # A fresh normal request uses the owner's new department. The Java suite,
        # rather than this sequential HTTP call, proves the in-flight GR wait.
        old_token = f.tokens['owner']
        f.set_actor('owner', ['QUALITY'], 'B')
        request = f.payload(first['head'], scope='DEPT', name=f.prefix + '新科室')
        request.pop('deptId')
        current = f.changed(tid, request, 'DEPT', 'B')
        assert f.tokens['owner'] == old_token
        f.api('POST', f'/emr-templates/{tid}/grants',
              {'granteeType': 'USER', 'granteeId': f.people['reader']['id']}, 'owner')
        f.api('PUT', f'/emr-templates/{tid}/default', who='owner')
        print('[1] PERSONAL owner converts to current DEPT and the original owner receives automatic grant attribution')

        for duty in ('ADMIN', 'DOCTOR_OUTP', 'NURSE', 'QUALITY', 'TECHNICIAN'):
            f.set_actor('owner', [duty], 'B')
            before = f.snapshot(tid)
            assert before['head']['is_default'] and len(before['grants']) == 2
            body = f.payload(before['head'], name=f.prefix + duty, content='V155合法正文' + duty)
            body.pop('deptId')
            f.api('PUT', f'/emr-templates/{tid}', body, 'owner')
            assert f.snapshot(tid) == dict(before, head=dict(before['head'], name=body['name'], content=body['content']))
        print('[2] All original five owner duties retain unchanged-scope updates, the default flag and every grant')

        before = f.snapshot(tid)
        request = f.payload(before['head'], scope='PERSONAL', name=f.prefix + '不得写入')
        f.set_actor('editor', ['QUALITY'], 'B')
        assert f.head(tid, 'editor')['editable'] is False
        f.deny(tid, request, 'editor', 4066)
        f.set_actor('editor', ['TECHNICIAN'], 'B')
        f.deny(tid, request, 'editor', 4066)
        f.set_actor('editor', ['CASHIER'], 'B')
        f.deny(tid, request, 'editor', 1005)
        f.set_actor('editor', [], 'B')
        f.deny(tid, request, 'editor', 1005)
        f.set_actor('editor', ['ADMIN'], 'B')
        uid = f.people['editor']['id']
        f.api('PUT', f'/system/users/{uid}/enabled?enabled=false')
        f.deny(tid, request, 'editor', 1000)  # Already disabled before JWT entry: original 401/1000.
        f.api('PUT', f'/system/users/{uid}/enabled?enabled=true')
        print('[3] Fresh requests observe lost object duty, missing maintenance duties and disabled JWT entry without business writes')

        before = f.snapshot(tid)
        invalid = f.payload(before['head'], name=' ', content=' ', scope='INVALID')
        f.deny(tid, invalid, 'owner', 4061)
        invalid['name'] = f.prefix + '非法正文'
        f.deny(tid, invalid, 'owner', 4062)
        invalid['content'] = '原参数优先级'
        f.deny(tid, invalid, 'owner', 4065)
        print('[4] Original name/content/scope validation priority remains compatible')

        before = f.snapshot(tid)
        moved = f.changed(tid, f.payload(before['head'], deptId=f.departments['A']['id']),
                          'DEPT', 'A', 'editor')
        assert not moved['head']['is_default']
        assert f.head(tid, 'reader') is None, 'Obsolete USER and old DEPT grants must both be cleared'
        personal = f.changed(tid, f.payload(moved['head'], scope='PERSONAL', deptId=None),
                             'PERSONAL', None, 'editor')
        assert personal['head']['owner_id'] == f.people['owner']['id']
        assert f.head(tid, 'editor') is None, 'ADMIN editor never becomes another owner of PERSONAL'
        f.deny(tid, f.payload(personal['head'], name=f.prefix + '不得越权'), 'editor', 4066)
        assert not f.unknown_writes
        print('[5] ADMIN department/scope conversion releases the default and rebuilds only owner/department automatic grants')
        print(f'PASS v155 public scope-update compatibility: {f.refusals} frozen refusals; '
              'one private template; no clinical writes; PG contention is covered only by the Java suite')
    finally:
        # Preserve all private fixtures. No unknown write is retried or cleaned up.
        if f.unknown_writes:
            print('UNKNOWN_WRITES (not replayed/cleaned up): ' + json.dumps(f.unknown_writes, ensure_ascii=False))


if __name__ == '__main__':
    main()
