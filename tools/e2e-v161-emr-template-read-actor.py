# -*- coding: utf-8 -*-
"""Current employee entry gates and all five template-head read contracts.

Only ordered public requests run here. The Java V161 suite proves in-flight
revocation and statement-snapshot boundaries; this script does not claim that.
There is no SQL, write retry or HTTP action at import time.
"""
import json
import time
import urllib.error

from e2elib import call, login, provision_user


def main():
    suffix = str(time.time_ns())[-13:]
    prefix = 'V161模拟' + suffix
    username, real_name = 'v161_read_' + suffix, prefix + '读取人'
    administrator = login()
    token = provision_user(administrator, username, 'ADMIN', real_name)

    def api(method, path, body=None, *, actor=administrator, code=0):
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
    owner = api('GET', '/auth/me')
    department = api('POST', '/system/depts', {
        'code': 'V161' + suffix, 'name': prefix + '科室', 'type': 'CLINICAL', 'sortNo': 9999,
    })
    record_type = 'V161_' + suffix[-10:]
    assert len(record_type) <= 16
    template_id = api('POST', '/emr-templates/scoped', {
        'name': prefix + '当前员工读取', 'content': prefix + '模拟正文', 'templateType': 'EMR',
        'scope': 'DEPT', 'deptId': department['id'], 'recordType': record_type,
    })
    assert isinstance(template_id, int) and not isinstance(template_id, bool)
    api('PUT', '/emr-templates/' + str(template_id) + '/default')
    api('POST', '/emr-templates/' + str(template_id) + '/grants', {
        'granteeType': 'USER', 'granteeId': person['id'],
    })

    expected_use = api('GET', '/emr-templates/' + str(template_id))
    expected_head = {key: value for key, value in expected_use.items() if key != 'scopeName'}
    expected_grants = api('GET', '/emr-templates/' + str(template_id) + '/grants')
    assert set(expected_head) == {
        'id', 'dept_id', 'name', 'content', 'template_type', 'enabled', 'scope', 'owner_id',
        'record_type', 'is_default', 'created_by', 'created_at',
    }
    assert set(expected_use) == set(expected_head) | {'scopeName'}
    assert all(set(row) == {'id', 'template_id', 'grantee_type', 'grantee_id', 'granted_at',
                            'grantee_name', 'granted_by_name'} for row in expected_grants)
    independent_head = dict(id=template_id, dept_id=department['id'], name=prefix + '当前员工读取',
                            content=prefix + '模拟正文', template_type='EMR', enabled=True, scope='DEPT',
                            owner_id=owner['id'], record_type=record_type, is_default=True, created_by=owner['id'])
    assert {key: expected_head[key] for key in independent_head} == independent_head
    assert expected_head['created_at'] is not None and expected_use['scopeName'] == '科室'
    assert expected_head['is_default'] and len(expected_grants) == 2
    assert {(row['grantee_type'], row['grantee_id']) for row in expected_grants} == {
        ('DEPT', department['id']), ('USER', person['id']),
    }
    for row in expected_grants:
        assert row['id'] is not None and row['granted_at'] is not None and row['template_id'] == template_id
        assert row['granted_by_name'] == owner['realName']
        assert row['grantee_name'] == (department['name'] if row['grantee_type'] == 'DEPT' else real_name)
    paths = {
        'legacy': '/emr-templates?deptId=' + str(department['id']) + '&type=EMR',
        'visible': '/emr-templates/visible?type=EMR&recordType=' + record_type + '&scope=DEPT',
        'use': '/emr-templates/' + str(template_id),
        'grants': '/emr-templates/' + str(template_id) + '/grants',
        'default': '/emr-templates/default?deptId=' + str(department['id']) + '&recordType=' + record_type,
    }

    def employee(roles):
        updated = api('PUT', '/system/users/' + str(person['id']), {
            'username': username, 'realName': real_name, 'deptId': department['id'],
            'title': None, 'phone': None, 'roleCodes': roles,
        })
        assert updated['id'] == person['id'] and updated['enabled']
        assert updated['deptId'] == department['id'] and set(updated['roleCodes']) == set(roles)

    def snapshot():
        headers = api('GET', '/emr-templates')
        return headers, {row['id']: api('GET', '/emr-templates/' + str(row['id']) + '/grants') for row in headers}

    def no_internal_keys(value):
        if isinstance(value, dict):
            assert not any(key.startswith('__') for key in value), value
            for item in value.values():
                no_internal_keys(item)
        elif isinstance(value, list):
            for item in value:
                no_internal_keys(item)

    allowed, refusals = [], []

    def permitted(label):
        before = snapshot()
        for entry, path in paths.items():
            value = api('GET', path, actor=token)
            no_internal_keys(value)
            if entry == 'use':
                assert value == expected_use
            elif entry == 'default':
                assert value == expected_head
            elif entry == 'grants':
                assert value == expected_grants
            else:
                selected = [row for row in value if row['id'] == template_id]
                assert len(selected) == 1
                row = selected[0]
                if entry == 'legacy':
                    assert row == expected_head
                else:
                    assert {key: row[key] for key in expected_head} == expected_head
                    assert set(row) == set(expected_head) | {'owner_name', 'dept_name', 'grant_count', 'editable', 'scopeName'}
                    assert row['scopeName'] == expected_use['scopeName'] and row['dept_name'] == department['name']
                    assert row['grant_count'] == 2 and isinstance(row['editable'], bool)
                    assert row['editable'] == (label == 'ADMIN')
            allowed.append([label, entry])
        assert snapshot() == before

    def denied(label, code):
        before = snapshot()
        for entry, path in paths.items():
            api('GET', path, actor=token, code=code)
            refusals.append([label, entry, code])
        assert snapshot() == before

    for role in ('ADMIN', 'DOCTOR_OUTP', 'NURSE', 'QUALITY', 'TECHNICIAN'):
        employee([role])
        permitted(role)
    for label, roles in (('CASHIER', ['CASHIER']), ('NO_ROLES', [])):
        employee(roles)
        denied(label, 1005)
    employee(['QUALITY'])
    api('PUT', '/system/users/' + str(person['id']) + '/enabled?enabled=false')
    denied('DISABLED_BEFORE_JWT', 1000)
    api('PUT', '/system/users/' + str(person['id']) + '/enabled?enabled=true')
    employee(['QUALITY'])
    permitted('RESTORED_QUALITY')
    assert len(allowed) == 30 and len(refusals) == 15
    print('PASS v161 template read actor:', json.dumps({
        'known_allowed_reads': len(allowed), 'known_refusals': len(refusals),
        'read_entries': list(paths), 'same_jwt_after_staff_changes': True,
        'template_id': template_id, 'record_type': record_type, 'inflight_revocation_claim': False,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
