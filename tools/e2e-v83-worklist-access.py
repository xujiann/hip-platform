# -*- coding: utf-8 -*-
"""v83: real JWT doctor identities, queue membership must agree with workspace access."""
import datetime
import time
from e2elib import call, login, new_patient, ok, provision_user, today_bj

t = login()
suffix = str(time.time_ns())[-10:]
a = provision_user(t, 'e2e83a' + suffix, 'DOCTOR_OUTP', 'V83医生A')
b = provision_user(t, 'e2e83b' + suffix, 'DOCTOR_OUTP', 'V83医生B')
aid = ok(call('GET', '/auth/me', token=a), 'A身份')['id']
bid = ok(call('GET', '/auth/me', token=b), 'B身份')['id']
today = today_bj()
yesterday = today - datetime.timedelta(days=1)

def visit(day, doctor_id):
    patient = new_patient(t, 'V83队列' + suffix + str(day), sex='U')
    body = {'deptId': 1, 'scheduleDate': str(day), 'fee': 0, 'capacity': 10}
    if doctor_id is not None:
        body['doctorId'] = doctor_id
    schedule = ok(call('POST', '/outpatient/schedules', body, t), '排班')
    return ok(call('POST', '/outpatient/registrations', {'patientId': patient['id'], 'scheduleId': schedule['id']}, t), '挂号')['id']

def ids(query, token):
    return [r['registrationId'] for r in ok(call('GET', '/outpatient/doctor/worklist?' + query, token=token), '队列')]

hidden = visit(yesterday, aid)
owned = visit(yesterday, bid)
shared = visit(today, aid)
unassigned = visit(yesterday, None)
for query in [f'date={yesterday}', f'from={yesterday}&to={today}',
              f'from={yesterday}&to={today}&doctorId={aid}']:
    assert hidden not in ids(query, b), query
assert call('GET', f'/outpatient/doctor/{hidden}/workspace', token=b)['code'] == 4036
rows = ids(f'from={yesterday}&to={today}', b)
assert owned in rows and shared in rows and unassigned in rows
assert rows == sorted(rows, reverse=True)
assert owned in ids(f'from={yesterday}&to={today}&mine=true', b)
assert shared not in ids(f'from={yesterday}&to={today}&mine=true', b)
assert hidden in ids(f'date={yesterday}', a)
assert hidden in ids(f'date={yesterday}', t)
for rid in [owned, shared, unassigned]:
    ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=b), '可见行工作区')
print('e2e-v83-worklist-access 全部通过：历史行过滤、指定医生不可扩权、当日共享、本人、管理员、无归属号与倒序保持')
