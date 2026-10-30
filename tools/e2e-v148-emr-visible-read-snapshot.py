# -*- coding: utf-8 -*-
"""Public visible-list contract: private staff/departments/templates only.

Sequential API observations freeze complete public template/grant/config
projections, not arbitrary clinical SQL tables or audit rows. The Java suite
separately proves the actual JDBC actor/payload boundary. Client cache already
legally delivered is not a new disclosure. Unknown writes are never replayed.
"""
import json
import re
import time
import urllib.error
from e2elib import call, login, q
GRANT_KEYS = {'id','template_id','grantee_type','grantee_id','granted_at','grantee_name','granted_by_name'}
SCOPES = {'GLOBAL':'全局','HOSPITAL':'全院','DEPT':'科室','PERSONAL':'个人'}

class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V148模拟' + self.suffix
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
                'code': 'V148' + self.suffix + label, 'name': self.prefix + '科室' + label,
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
        name = 'v148_' + who + '_' + self.suffix
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


ROW_KEYS = {'id','dept_id','name','content','template_type','enabled','scope','owner_id','record_type',
            'is_default','created_by','created_at','owner_name','dept_name','grant_count','editable','scopeName'}


class VisibleFixture(Fixture):
    def snapshot(self, tid):
        item = self.templates[tid]
        rows = self.api('GET', '/emr-templates/visible?type=' + q(item['body']['templateType'])
            + '&includeDisabled=true&keyword=' + q(item['body']['name']), who=item['owner'])
        found = [row for row in rows if row['id'] == tid]
        assert len(found) == 1 and set(found[0]) == ROW_KEYS
        grants = self.api('GET', f'/emr-templates/{tid}/grants', who=item['owner'])
        assert all(set(g) == GRANT_KEYS for g in grants)
        return {'head': found[0], 'grants': grants, 'publicConfig': self.api('GET', '/config/public'),
            'versionSettings': self.api('GET', '/emr/versions/settings')}

    def all_snapshot(self):
        return {tid: self.snapshot(tid) for tid in self.templates}

    def visible(self, who='doctor', code=0, **filters):
        before = self.all_snapshot()
        params = {'keyword': self.prefix, **filters}
        query = '&'.join(q(k) + '=' + q(str(v).lower() if isinstance(v, bool) else str(v)) for k,v in params.items())
        actual = self.api('GET', '/emr-templates/visible?' + query, who=who, code=code)
        if code:
            assert actual is None; self.refusals += 1
        else:
            user = self.identities[who]; uid, department = user['id'], user.get('deptId')
            admin = 'ADMIN' in user.get('roleCodes', user.get('roles', []))
            expected = []
            for tid, item in before.items():
                row = dict(item['head']); scope, owner = row['scope'], row['owner_id']
                grants = item['grants']
                permitted = scope in ['GLOBAL','HOSPITAL'] or owner == uid or scope == 'DEPT' and (admin or row['dept_id'] == department)
                permitted = permitted or any(g['grantee_type'] == 'USER' and g['grantee_id'] == uid or
                    g['grantee_type'] == 'DEPT' and department is not None and g['grantee_id'] == department for g in grants)
                if not permitted or not params.get('includeDisabled', False) and not row['enabled']: continue
                if any(str(params[k]).strip() and row[column] != str(params[k]).strip().upper()
                       for k,column in [('type','template_type'),('recordType','record_type'),('scope','scope')] if k in params): continue
                keyword = str(params.get('keyword','')).strip()
                pattern = '.*' + ''.join('.*' if c=='%' else '.' if c=='_' else re.escape(c) for c in keyword) + '.*'
                if keyword and not re.fullmatch(pattern, row['name'], flags=re.S): continue
                row['editable'] = (owner == uid if scope == 'PERSONAL' else owner == uid or admin if scope == 'DEPT' else admin)
                expected.append(row)
            expected.sort(key=lambda row: (not row['is_default'], {'GLOBAL':0,'HOSPITAL':1,'DEPT':2}.get(row['scope'],3), row['name'], row['id']))
            assert actual == expected, ('Complete visible-list DTO/order differed', who, params, actual, expected)
            assert all(set(row) == ROW_KEYS and type(row['editable']) is bool and type(row['grant_count']) is int
                and row['scopeName'] == SCOPES[row['scope']] and '__visible_editable' not in row for row in actual)
            self.reads += 1
        assert self.all_snapshot() == before, 'List GET changed a complete exposed template/grant/config projection'
        return actual


def main():
    f = VisibleFixture()
    try:
        hospital = f.template('02-hospital', 'HOSPITAL', 'admin')
        global_id = f.template('01-global', 'GLOBAL', 'admin')
        dept = f.template('03-dept', 'DEPT', dept=f.departments['A'])
        personal = f.template('04-personal', 'PERSONAL')
        for index, who in enumerate(['nurse','quality','tech'],5): f.template(str(index).zfill(2)+'-own', who=who)
        for who in ['admin','manager','doctor','peer','other','nurse','quality','tech']:
            rows = f.visible(who); ids = {row['id'] for row in rows}
            assert {hospital,global_id} <= ids
            if who in ['admin','manager','other']: assert personal not in ids
        f.visible('anonymous',1000); f.visible('cashier',1005)
        print('[1] Five original duties retain complete visible DTOs/metadata; hidden rows are absent from code0 lists, not4066; security remains401/403')

        for kind, target in [('USER', f.identities['other']['id']),('DEPT', f.departments['B'])]:
            gid = f.grant(personal,kind,target); legal = f.visible('other')
            assert next(row for row in legal if row['id']==personal)['editable'] is False
            f.revoke(personal,gid); assert personal not in {row['id'] for row in f.visible('other')}
            assert next(row for row in legal if row['id']==personal)['content']==f.templates[personal]['body']['content']
            gid = f.grant(personal,kind,target); assert personal in {row['id'] for row in f.visible('other')}; f.revoke(personal,gid)
        print('[2] USER/DEPT grants overlay PERSONAL without maintenance rights; revoke/explicit refresh/restore preserve legal cached bytes without pretending a JDBC race')

        token = f.tokens['other']; f.set_actor('other',dept=f.departments['A']); assert dept in {row['id'] for row in f.visible('other')}
        f.set_actor('other',dept=f.departments['B']); assert dept not in {row['id'] for row in f.visible('other')}; assert f.tokens['other']==token
        token = f.tokens['manager']; f.set_actor('manager',roles=['TECHNICIAN']); rows=f.visible('manager')
        assert dept not in {row['id'] for row in rows} and all(not row['editable'] for row in rows)
        f.set_actor('manager',roles=['ADMIN']); assert next(row for row in f.visible('manager') if row['id']==dept)['editable']; assert f.tokens['manager']==token
        print('[3] A new read with the same JWT reflects current department/DB ADMIN for both visibility and editable metadata')

        f.enabled(personal,False); assert personal not in {row['id'] for row in f.visible()}
        assert next(row for row in f.visible(includeDisabled=True) if row['id']==personal)['enabled'] is False
        f.enabled(personal,True); f.visible()
        f.api('PUT',f'/emr-templates/{dept}/default',who='doctor'); ordered=f.visible(); assert ordered[0]['id']==dept
        f.api('DELETE',f'/emr-templates/{dept}/default',who='doctor'); assert f.visible()[0]['id']==global_id
        print('[4] Original includeDisabled and default/scope/name/id ordering remain unchanged')

        f.update(personal,recordType='  custom  ')
        f.visible(type=' emr '); f.visible(recordType=' custom '); f.visible(scope=' personal ')
        f.visible(type=' '); f.visible(recordType=' '); f.visible(scope=' ')
        assert f.visible(type='NO_SUCH_TYPE')==[] and f.visible(scope='NO_SUCH_SCOPE')==[] and f.visible(recordType='NO_SUCH_RECORD')==[]
        f.visible(keyword=f.prefix+'0%'); f.visible(keyword=f.prefix+'0_-'); assert f.visible(keyword=f.prefix+'NOT_FOUND')==[]
        f.api('GET','/emr-templates/visible?includeDisabled=not-a-boolean',who='doctor',code=4000)
        print('[5] Original optional trim/uppercase filters, unescaped LIKE wildcards, empty results and bad boolean4000 remain compatible')
        assert not f.unknown_writes
        print(f'PASS v148 public visible-list reads: {f.reads} complete public projection checks, {f.refusals} security refusals; no clinical/SQL write or unknown write replay')
    finally:
        if f.unknown_writes: print('UNKNOWN_WRITES (not replayed/cleaned): '+json.dumps(f.unknown_writes,ensure_ascii=False))


if __name__ == '__main__':
    main()
