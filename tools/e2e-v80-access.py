# -*- coding: utf-8 -*-
"""v80 审阅修补 E2E：诊断助手历史段与患者历次就诊的对象级权限（v80 复核·方案三 D1）+ 历次就诊追加诊断五键（D8）。

修前（审计实测）：doctor02（外科，非接诊医生）按 patientId 读得到 doctor01 患者的历史诊断、主诉与处理意见。
口径与 v80 版本端点同：ADMIN / QUALITY 全看；其余须本人名下至少有一条该患者未退号的挂号，否则 4036 且不带数据；
只传 keyword 的常用 / 高频不是患者数据，不受影响。

自成一体：自建两名门诊医生、自建患者（医生 A 接诊科室号即成为归属医生）。
库里有演示账号 doctor01 / doctor02 与张三（bootstrap-demo 种过）时，再按审计复现路径各验一次。
"""
from e2elib import call, login, new_patient, ok, provision_user, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()
sfx = str(int(__import__('time').time()) % 1000000)

doc_a = provision_user(t, 'e2e80a' + sfx, 'DOCTOR_OUTP', 'E2E80接诊医生')
doc_b = provision_user(t, 'e2e80b' + sfx, 'DOCTOR_OUTP', 'E2E80他人医生')

# ---- [1] 医生 A 接诊并写病历（含疑诊与前后缀）----
pid = new_patient(t, 'E2E80权限', sex='F')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 9}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, doc_a), 'A 接诊（科室号归属为 A）')
ok(call('PUT', f'/outpatient/doctor/{rid}/emr',
        {'emr': {'chiefComplaint': 'E2E80咽痛两天', 'presentIllness': '受凉后', 'advice': 'E2E80对症'},
         'diagnoses': [{'icdCode': 'J02.900', 'icdName': '急性咽炎', 'primaryDiag': True, 'prefix': '复发性',
                        'suffix': '伴发热', 'certainty': 'SUSPECTED', 'customName': '咽痛待查', 'diagSystem': 'ICD10'}]},
        doc_a), 'A 写病历')

assist_url = f'/outpatient/doctor/diagnosis-assist?patientId={pid}'
hist_url = f'/outpatient/doctor/patient/{pid}/history'


def denied(r, what):
    assert r['code'] == 4036, f'{what} 须 4036: {r}'
    assert r.get('data') is None, f'{what} 被拒时不得带数据: {r}'
    assert 'E2E80咽痛两天' not in str(r), f'{what} 被拒时不得带回主诉: {r}'


# ---- [1] 非接诊医生 B：两端点 4036 且不带数据；只传 keyword 照常 ----
denied(call('GET', assist_url, token=doc_b), 'B 读诊断助手历史')
denied(call('GET', hist_url, token=doc_b), 'B 读历次就诊')
kw = ok(call('GET', '/outpatient/doctor/diagnosis-assist?keyword=J02', token=doc_b), 'B 只传 keyword')
assert kw['history'] == [] and isinstance(kw['favorite'], list) and isinstance(kw['frequent'], list), kw
print('[1] 非接诊医生 4036 OK（诊断助手历史段 / 患者历次就诊均不带数据；只传 keyword 的常用、高频照常）')

# ---- [2] 接诊医生 A 与管理员照常；历次就诊追加五键（D8），既有三键不动 ----
mine = ok(call('GET', assist_url, token=doc_a), 'A 读诊断助手')
assert any(h['icdCode'] == 'J02.900' for h in mine['history']), mine
hist = ok(call('GET', hist_url, token=doc_a), 'A 读历次就诊')
assert hist[0]['chiefComplaint'] == 'E2E80咽痛两天', hist[0]
d = hist[0]['diagnoses'][0]
assert d['icdCode'] == 'J02.900' and d['icdName'] == '急性咽炎' and d['primaryDiag'] is True, d
assert (d['prefix'], d['suffix'], d['certainty'], d['customName'], d['diagSystem']) == \
    ('复发性', '伴发热', 'SUSPECTED', '咽痛待查', 'ICD10'), f'历次就诊须带前缀/后缀/疑诊/自定义/体系: {d}'
ok(call('GET', assist_url, token=t), '管理员读诊断助手')
ok(call('GET', hist_url, token=t), '管理员读历次就诊')
print('[2] 接诊医生与管理员照常 OK（历次就诊诊断追加 prefix/suffix/certainty/customName/diagSystem）')

# ---- [3] B 名下有该患者的号即可读（已挂号未接诊也算）；退号不算 ----
sch_b = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 9,
                                                  'doctorId': ok(call('GET', '/auth/me', token=doc_b), 'B 身份')['id']},
                t), 'B 排班')
rid_b = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch_b['id']}, t), '挂 B 的号')['id']
ok(call('GET', hist_url, token=doc_b), 'B 名下有号后读历次就诊')
ok(call('GET', assist_url, token=doc_b), 'B 名下有号后读诊断助手')
ok(call('PUT', f'/outpatient/registrations/{rid_b}/cancel', token=t), '退 B 的号')
denied(call('GET', hist_url, token=doc_b), 'B 退号后读历次就诊')
print('[3] 归属判据 OK（本人名下未退号挂号即可；退号后恢复 4036）')

# ---- [4] 审计复现路径：doctor02 读 doctor01 的患者张三（演示库有这两个账号与张三时）----
d1 = call('POST', '/auth/login', {'username': 'doctor01', 'password': 'Demo1234'})
d2 = call('POST', '/auth/login', {'username': 'doctor02', 'password': 'Demo1234'})
zs = (call('GET', '/patients?keyword=510181199003078511&page=0&size=5', token=t).get('data') or {}).get('records') or []
if d1.get('code') == 0 and d2.get('code') == 0 and zs:
    zid = zs[0]['id']
    t1, t2 = d1['data']['token'], d2['data']['token']
    owned = call('GET', f'/outpatient/doctor/patient/{zid}/history', token=t1)
    if owned['code'] == 0 and owned['data']:
        denied(call('GET', f'/outpatient/doctor/diagnosis-assist?patientId={zid}', token=t2), 'doctor02 读张三诊断助手')
        denied(call('GET', f'/outpatient/doctor/patient/{zid}/history', token=t2), 'doctor02 读张三历次就诊')
        print('[4] 审计复现 OK（doctor02 读 doctor01 的患者张三：诊断助手历史 / 历次就诊均 4036；doctor01 照常）')
    else:
        print(f"[4] 跳过：doctor01 名下无张三的就诊（{owned.get('code')}）")
else:
    print('[4] 跳过：库里没有 doctor01 / doctor02 / 张三（未跑 bootstrap-demo）')

print('\ne2e-v80-access 全部通过 ✅')
