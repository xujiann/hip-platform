# -*- coding: utf-8 -*-
"""Public JWT compatibility for the legacy template creation route.

The ordered HTTP requests use fresh current employee state after public staff
changes. In-flight authorization changes are exercised by V158's Java suite.
This script performs no SQL, retries or HTTP actions at import time.
"""
import json
import time

from e2elib import call, login, provision_user


def main():
    suffix = str(time.time_ns())[-13:]
    prefix = 'V158模拟' + suffix
    username = 'v158_legacy_' + suffix
    real_name = prefix + '维护人'
    admin = login()
    token = provision_user(admin, username, 'ADMIN', real_name)

    def api(method, path, body=None, *, actor=admin, code=0):
        result = call(method, path, body, actor)
        assert result['code'] == code, (method, path, code, result)
        if code:
            assert result.get('data') is None
        return result.get('data')

    person = api('GET', '/auth/me', actor=token)
    departments = {}
    for label in ('A', 'B'):
        departments[label] = api('POST', '/system/depts', {
            'code': 'V158' + label + suffix, 'name': prefix + '科室' + label,
            'type': 'CLINICAL', 'sortNo': 9999,
        })

    def employee(roles):
        updated = api('PUT', '/system/users/' + str(person['id']), {
            'username': username, 'realName': real_name, 'deptId': departments['A']['id'],
            'title': None, 'phone': None, 'roleCodes': roles,
        })
        assert updated['id'] == person['id'] and updated['enabled']
        current = api('GET', '/auth/me', actor=token)
        assert current['id'] == person['id'] and current['deptId'] == departments['A']['id']
        assert set(current['roles']) == set(roles)

    def headers():
        return api('GET', '/emr-templates')

    def grants(template_id):
        return api('GET', '/emr-templates/' + str(template_id) + '/grants')

    created = []

    def create(label, department, template_type=None):
        before = headers()
        payload = {'deptId': None if department is None else departments[department]['id'],
                   'name': prefix + label, 'content': prefix + label + '正文',
                   'templateType': template_type}
        assert api('POST', '/emr-templates', payload, actor=token) is None
        after = headers()
        added = [row for row in after if row['id'] not in {r['id'] for r in before}]
        assert len(added) == 1
        row = added[0]
        assert [r for r in after if r['id'] != row['id']] == before
        assert row['name'] == payload['name'] and row['content'] == payload['content']
        assert row['dept_id'] == payload['deptId']
        assert row['scope'] == ('HOSPITAL' if department is None else 'DEPT')
        assert row['owner_id'] == row['created_by'] == person['id']
        assert row['template_type'] == (template_type if template_type and template_type.strip() else 'EMR')
        assert row['enabled'] and not row['is_default'] and row['record_type'] is None
        rows = grants(row['id'])
        if department is None:
            assert rows == []
        else:
            assert len(rows) == 1
            grant = rows[0]
            assert (grant['template_id'], grant['grantee_type'], grant['grantee_id']) == (
                row['id'], 'DEPT', departments[department]['id'])
            assert grant['grantee_name'] == departments[department]['name']
            assert grant['granted_by_name'] == real_name
        created.append(row['id'])
        return row

    def refuse(label, department):
        before = headers()
        prior_grants = {template_id: grants(template_id) for template_id in created}
        api('POST', '/emr-templates', {
            'deptId': None if department is None else departments[department]['id'],
            'name': prefix + label, 'content': prefix + '拒绝正文', 'templateType': 'EMR',
        }, actor=token, code=4066)
        assert headers() == before
        assert {template_id: grants(template_id) for template_id in created} == prior_grants

    employee(['ADMIN'])
    create('全院模板', None)
    create('管理员他科模板', 'B', 'RIS')
    employee(['QUALITY'])
    refuse('降岗后全院模板', None)
    refuse('降岗后他科模板', 'B')
    create('质控本科模板', 'A', ' ')
    employee(['TECHNICIAN'])
    create('技师本科模板', 'A', 'RIS')
    refuse('技师全院模板', None)
    assert len(created) == 4
    print('PASS v158 legacy create compatibility:', json.dumps({
        'known_creates': 4, 'known_refusals': 3, 'same_jwt_after_staff_changes': True,
        'template_ids': created, 'inflight_race_claim': False,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
