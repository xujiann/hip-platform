# -*- coding: utf-8 -*-
"""Public scoped creation with current employee roles and automatic grants.

Ordered HTTP requests cover committed public staff changes, not in-flight races.
The Java suite proves the after-JWT and actor-query interleavings separately.
No SQL, write retry or HTTP action runs at import time.
"""
import json
import time
import urllib.error

from e2elib import call, login, provision_user


def main():
    suffix = str(time.time_ns())[-13:]
    prefix = 'V159模拟' + suffix
    username, real_name = 'v159_scoped_' + suffix, prefix + '维护人'
    admin = login()
    token = provision_user(admin, username, 'ADMIN', real_name)

    def api(method, path, body=None, *, actor=admin, code=0):
        try:
            result = call(method, path, body, actor)
        except urllib.error.HTTPError as error:
            assert (error.code, code) in ((401, 1000), (403, 1005)), (method, path, error.code, code)
            result = json.load(error)
        assert isinstance(result, dict) and result['code'] == code, (method, path, code, result)
        if code:
            assert result.get('data') is None
        return result.get('data')

    person = api('GET', '/auth/me', actor=token)
    observer = api('GET', '/auth/me')
    departments = {}
    for label in ('A', 'B'):
        departments[label] = api('POST', '/system/depts', {
            'code': 'V159' + label + suffix, 'name': prefix + '科室' + label,
            'type': 'CLINICAL', 'sortNo': 9999,
        })

    def employee(roles, department='A'):
        dept_id = departments[department]['id'] if department else None
        updated = api('PUT', '/system/users/' + str(person['id']), {
            'username': username, 'realName': real_name, 'deptId': dept_id,
            'title': None, 'phone': None, 'roleCodes': roles,
        })
        assert updated['id'] == person['id'] and updated['enabled']
        assert updated['deptId'] == dept_id and set(updated['roleCodes']) == set(roles)
        current = api('GET', '/auth/me', actor=token)
        assert current['id'] == person['id'] and current['deptId'] == dept_id
        assert set(current['roles']) == set(roles)

    def headers():
        return api('GET', '/emr-templates')

    def grants(template_id, actor=admin):
        return api('GET', '/emr-templates/' + str(template_id) + '/grants', actor=actor)

    created, refusals = [], []

    def payload(label, scope='DEPT', department=None):
        return dict(name=prefix + label, content=prefix + label + '正文', scope=scope,
                    deptId=departments[department]['id'] if department else None,
                    templateType=None, recordType=None)

    def create(body, scope, department=None, template_type='EMR', record_type=None):
        before = headers()
        template_id = api('POST', '/emr-templates/scoped', body, actor=token)
        assert isinstance(template_id, int) and not isinstance(template_id, bool) and template_id > 0
        row = api('GET', '/emr-templates/' + str(template_id), actor=token)
        expected = dict(name=body['name'].strip()[:64], content=body['content'], scope=scope,
                        dept_id=departments[department]['id'] if department else None,
                        owner_id=person['id'], created_by=person['id'], enabled=True,
                        is_default=False, template_type=template_type, record_type=record_type)
        assert {key: row[key] for key in expected} == expected, (expected, row)
        rows = grants(template_id, actor=token)
        if scope in ('HOSPITAL', 'GLOBAL'):
            assert rows == []
        else:
            assert len(rows) == 1
            grant = rows[0]
            grantee_type, grantee_id, grantee_name = (
                ('DEPT', departments[department]['id'], departments[department]['name']) if scope == 'DEPT'
                else ('USER', person['id'], real_name))
            assert (grant['template_id'], grant['grantee_type'], grant['grantee_id']) == (
                template_id, grantee_type, grantee_id)
            assert grant['grantee_name'] == grantee_name and grant['granted_by_name'] == real_name
        if scope == 'PERSONAL':
            # Even ADMIN cannot read another person's PERSONAL template. First
            # verify its sole automatic grant as owner, then explicitly grant
            # the stable observer read access for later full refusal snapshots.
            api('POST', '/emr-templates/' + str(template_id) + '/grants', {
                'granteeType': 'USER', 'granteeId': observer['id'],
            }, actor=token)
            observed = grants(template_id)
            assert len(observed) == 2 and rows[0] in observed
            added_grant = next(g for g in observed if g['id'] != rows[0]['id'])
            assert (added_grant['grantee_type'], added_grant['grantee_id']) == ('USER', observer['id'])
            assert added_grant['grantee_name'] == observer['realName']
            assert added_grant['granted_by_name'] == real_name
        after = headers()
        added = [head for head in after if head['id'] == template_id]
        assert len(added) == 1 and [r for r in after if r['id'] != template_id] == before
        assert {key: added[0][key] for key in expected} == expected
        created.append(template_id)

    def refuse(body, code=4066):
        before = headers()
        prior_grants = {template_id: grants(template_id) for template_id in created}
        api('POST', '/emr-templates/scoped', body, actor=token, code=code)
        assert headers() == before
        assert {template_id: grants(template_id) for template_id in created} == prior_grants
        refusals.append(code)

    employee(['ADMIN'])
    body = payload('全院', ' hospital ', 'B')
    body.update(templateType=' ris ', recordType=' abcdefghijklmnopqrstuv ')
    create(body, 'HOSPITAL', template_type='RIS', record_type='ABCDEFGHIJKLMNOP')
    create(payload('全局', 'GLOBAL', 'A'), 'GLOBAL')
    body = payload('管理员他科', department='B')
    body.update(templateType=' ', recordType=' ')
    create(body, 'DEPT', 'B')

    for role in ('DOCTOR_OUTP', 'NURSE', 'QUALITY', 'TECHNICIAN'):
        employee([role])
        create(payload(role + '缺省本科'), 'DEPT', 'A')
        create(payload(role + '个人', ' personal ', 'B'), 'PERSONAL')
        for scope in ('HOSPITAL', 'GLOBAL'):
            refuse(payload(role + scope + '拒绝', scope))
        refuse(payload(role + '他科拒绝', department='B'))

    employee(['QUALITY', 'CASHIER'])
    body = payload('混合合法岗', department='A')
    body.update(name='  ' + prefix + '长名称' * 30 + '  ', content='  保留正文空白\n',
                templateType=' consent ', recordType=' inp ')
    create(body, 'DEPT', 'A', 'CONSENT', 'INP')
    employee(['ADMIN', 'CASHIER'])
    create(payload('混合管理员', 'GLOBAL'), 'GLOBAL')

    employee(['QUALITY'], 'B')
    refuse(payload('调科后旧科拒绝', department='A'))
    create(payload('调科后新科缺省'), 'DEPT', 'B')
    employee(['QUALITY'], None)
    refuse(payload('无科室缺省拒绝'), 4065)
    create(payload('无科室个人', 'PERSONAL', 'A'), 'PERSONAL')
    employee(['QUALITY'])
    for changes, code in (({'name': ' '}, 4061), ({'content': ' '}, 4062),
                          ({'scope': None}, 4065), ({'scope': 'INVALID'}, 4065)):
        body = payload('参数拒绝')
        body.update(changes)
        refuse(body, code)
    for roles in (['CASHIER'], []):
        employee(roles)
        refuse(payload('丢失维护岗拒绝'), 1005)
    employee(['QUALITY'])
    api('PUT', '/system/users/' + str(person['id']) + '/enabled?enabled=false')
    refuse(payload('停用拒绝'), 1000)
    api('PUT', '/system/users/' + str(person['id']) + '/enabled?enabled=true')
    employee(['QUALITY'])
    create(payload('恢复后可用', 'PERSONAL'), 'PERSONAL')
    print('PASS v159 scoped create:', json.dumps({
        'known_creates': len(created), 'known_refusals': len(refusals),
        'refusal_codes': refusals, 'same_jwt_after_staff_changes': True,
        'template_ids': created, 'inflight_race_claim': False,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
