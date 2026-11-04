# -*- coding: utf-8 -*-
"""Public legacy-create compatibility around the transaction boundary.

Only ordered, public HTTP requests run here. The Java V160 suite injects a real
grant-write failure and proves rollback; this script does not claim to do so.
There is no SQL, write retry or HTTP action at import time.
"""
import json
import time
import urllib.error

from e2elib import call, login, provision_user


def main():
    suffix = str(time.time_ns())[-13:]
    prefix = 'V160模拟' + suffix
    username, real_name = 'v160_legacy_' + suffix, prefix + '维护人'
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
    departments = {}
    for label in ('A', 'B'):
        departments[label] = api('POST', '/system/depts', {
            'code': 'V160' + label + suffix, 'name': prefix + '科室' + label,
            'type': 'CLINICAL', 'sortNo': 9999,
        })

    def employee(roles):
        updated = api('PUT', '/system/users/' + str(person['id']), {
            'username': username, 'realName': real_name, 'deptId': departments['A']['id'],
            'title': None, 'phone': None, 'roleCodes': roles,
        })
        assert updated['id'] == person['id'] and updated['enabled']
        assert updated['deptId'] == departments['A']['id'] and set(updated['roleCodes']) == set(roles)
        current = api('GET', '/auth/me', actor=token)
        assert current['id'] == person['id'] and current['deptId'] == departments['A']['id']
        assert set(current['roles']) == set(roles)

    def headers():
        return api('GET', '/emr-templates')

    def grants(template_id):
        return api('GET', '/emr-templates/' + str(template_id) + '/grants')

    def snapshot():
        rows = headers()
        return rows, {row['id']: grants(row['id']) for row in rows}

    def payload(label, department='A', template_type=None):
        return dict(deptId=departments[department]['id'] if department else None,
                    name=prefix + label, content=prefix + label + '正文', templateType=template_type)

    created, refusals = [], []

    def create(body):
        before, old_grants = snapshot()
        assert set(body) == {'deptId', 'name', 'content', 'templateType'}
        assert api('POST', '/emr-templates', body, actor=token) is None
        after = headers()
        added = [row for row in after if row['id'] not in {r['id'] for r in before}]
        assert len(added) == 1
        row = added[0]
        assert [r for r in after if r['id'] != row['id']] == before
        assert {template_id: grants(template_id) for template_id in old_grants} == old_grants
        expected = dict(dept_id=body['deptId'], name=body['name'], content=body['content'],
                        template_type=body['templateType'] if body['templateType'] and body['templateType'].strip() else 'EMR',
                        scope='DEPT' if body['deptId'] is not None else 'HOSPITAL',
                        owner_id=person['id'], created_by=person['id'], enabled=True,
                        is_default=False, record_type=None)
        assert {key: row[key] for key in expected} == expected
        rows = grants(row['id'])
        if body['deptId'] is None:
            assert rows == []
        else:
            assert len(rows) == 1
            grant = rows[0]
            assert (grant['template_id'], grant['grantee_type'], grant['grantee_id']) == (
                row['id'], 'DEPT', body['deptId'])
            assert grant['grantee_name'] == next(d['name'] for d in departments.values() if d['id'] == body['deptId'])
            assert grant['granted_by_name'] == real_name
        created.append(row['id'])

    def refuse(body, code):
        before = snapshot()
        api('POST', '/emr-templates', body, actor=token, code=code)
        assert snapshot() == before
        refusals.append(code)

    employee(['ADMIN'])
    create(payload('管理员本科'))
    create(payload('管理员全院', None, 'RIS'))
    create(payload('管理员他科', 'B', 'CONSENT'))
    for role in ('DOCTOR_OUTP', 'NURSE', 'QUALITY', 'TECHNICIAN'):
        employee([role])
        body = payload(role + '本科', template_type=' ' if role == 'NURSE' else None)
        if role == 'DOCTOR_OUTP':
            body.update(name='  ' + prefix + '保留名称空白  ', content='  原正文\n', templateType=' ris ')
        create(body)
        if role == 'DOCTOR_OUTP':
            refuse(payload('非管理员全院拒绝', None), 4066)
            refuse(payload('非管理员他科拒绝', 'B'), 4066)

    employee(['QUALITY'])
    body = payload('旧通道空白不增校验', template_type='\t ')
    body.update(name='   ', content=' \n ')
    create(body)
    employee(['CASHIER'])
    refuse(payload('丢失维护岗拒绝'), 1005)
    employee(['QUALITY'])
    api('PUT', '/system/users/' + str(person['id']) + '/enabled?enabled=false')
    refuse(payload('停用拒绝'), 1000)
    api('PUT', '/system/users/' + str(person['id']) + '/enabled?enabled=true')
    employee(['QUALITY'])
    create(payload('恢复后可用'))
    assert len(created) == 9 and len(refusals) == 4
    print('PASS v160 legacy create compatibility:', json.dumps({
        'known_creates': len(created), 'known_refusals': len(refusals),
        'refusal_codes': refusals, 'same_jwt_after_staff_changes': True,
        'template_ids': created, 'grant_failure_rollback_claim': False,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
