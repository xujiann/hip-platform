# -*- coding: utf-8 -*-
"""v80 审阅修补 E2E：门诊医生站患者资料的对象级权限（v80 复核·方案三 D1 → 审阅修补二）+ 历次就诊追加诊断五键（D8）。

修前（审计实测）：doctor02（外科，非接诊医生）按 patientId 读得到 doctor01 患者的历史诊断、主诉与处理意见。
D1 初版只看挂号归属，两位复核反驳者指出：①接诊队列可跨 92 天列出他人往次挂号，逐个打开工作区仍读得到全部病历（N-1）；
②代班医生接诊他人名下的号并写了病历后读不到该患者既往，科室号接诊前也读不到（R2-1 / N-2）。

审阅修补二统一口径「当日就诊队列共享、往次就诊归本人」：
- 就诊级（工作区、补正历史）：ADMIN 全看；当日就诊 / 归属为空 / 归属是我 / 我写过这次病历，任一成立放行，否则 4036；
- 患者级（诊断助手历史段、患者历次就诊）：ADMIN 全看；该患者有一条未退号挂号满足 归属是我 / 当日 / 我写过病历，否则 4036；
  不设「归属为空放行」。只传 keyword 的常用 / 高频不是患者数据，不受影响。
因此「他人不可读」的步骤一律用**昨天**的就诊造（今天的就诊按口径全院可读）。

自成一体：自建两名门诊医生、自建患者。库里有演示账号 doctor01 / doctor02 与张三（bootstrap-demo 种过）时，再按审计复现路径验一次。
"""
import datetime

from e2elib import call, login, new_patient, ok, provision_user, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()
yday = (today_bj() - datetime.timedelta(days=1)).isoformat()
sfx = str(int(__import__('time').time()) % 1000000)

doc_a = provision_user(t, 'e2e80a' + sfx, 'DOCTOR_OUTP', 'E2E80接诊医生')
doc_b = provision_user(t, 'e2e80b' + sfx, 'DOCTOR_OUTP', 'E2E80他人医生')
a_id = ok(call('GET', '/auth/me', token=doc_a), 'A 身份')['id']
b_id = ok(call('GET', '/auth/me', token=doc_b), 'B 身份')['id']

# ---- 夹具：医生 A 接诊**昨天**的科室号并写病历（含疑诊与前后缀）——接诊即成为归属医生 ----
pid = new_patient(t, 'E2E80权限', sex='F')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': yday, 'fee': 0, 'capacity': 9}, t), '昨日排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号（昨天）')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, doc_a), 'A 接诊（科室号归属为 A）')
ok(call('PUT', f'/outpatient/doctor/{rid}/emr',
        {'emr': {'chiefComplaint': 'E2E80咽痛两天', 'presentIllness': '受凉后', 'advice': 'E2E80对症'},
         'diagnoses': [{'icdCode': 'J02.900', 'icdName': '急性咽炎', 'primaryDiag': True, 'prefix': '复发性',
                        'suffix': '伴发热', 'certainty': 'SUSPECTED', 'customName': '咽痛待查', 'diagSystem': 'ICD10'}]},
        doc_a), 'A 写病历')

assist_url = f'/outpatient/doctor/diagnosis-assist?patientId={pid}'
hist_url = f'/outpatient/doctor/patient/{pid}/history'
ws_url = f'/outpatient/doctor/{rid}/workspace'
amend_url = f'/outpatient/doctor/{rid}/emr/amendments'


def denied(r, what):
    assert r['code'] == 4036, f'{what} 须 4036: {r}'
    assert r.get('data') is None, f'{what} 被拒时不得带数据: {r}'
    assert 'E2E80咽痛两天' not in str(r), f'{what} 被拒时不得带回主诉: {r}'


# ---- [1] 非接诊医生 B（患者只有 A 的往次就诊）：患者级两端点、就诊级两端点均 4036 且不带数据；只传 keyword 照常 ----
denied(call('GET', assist_url, token=doc_b), 'B 读诊断助手历史')
denied(call('GET', hist_url, token=doc_b), 'B 读历次就诊')
denied(call('GET', ws_url, token=doc_b), 'B 打开 A 的往次就诊工作区')
denied(call('GET', amend_url, token=doc_b), 'B 读 A 的往次就诊补正历史')
kw = ok(call('GET', '/outpatient/doctor/diagnosis-assist?keyword=J02', token=doc_b), 'B 只传 keyword')
assert kw['history'] == [] and isinstance(kw['favorite'], list) and isinstance(kw['frequent'], list), kw
print('[1] 他人往次就诊 4036 OK（诊断助手历史 / 历次就诊 / 往次工作区 / 补正历史均不带数据；只传 keyword 的常用、高频照常）')

# ---- [2] 接诊医生 A 与管理员照常；历次就诊追加五键（D8），既有三键不动 ----
mine = ok(call('GET', assist_url, token=doc_a), 'A 读诊断助手')
assert any(h['icdCode'] == 'J02.900' for h in mine['history']), mine
hist = ok(call('GET', hist_url, token=doc_a), 'A 读历次就诊')
assert hist[0]['chiefComplaint'] == 'E2E80咽痛两天', hist[0]
d = hist[0]['diagnoses'][0]
assert d['icdCode'] == 'J02.900' and d['icdName'] == '急性咽炎' and d['primaryDiag'] is True, d
assert (d['prefix'], d['suffix'], d['certainty'], d['customName'], d['diagSystem']) == \
    ('复发性', '伴发热', 'SUSPECTED', '咽痛待查', 'ICD10'), f'历次就诊须带前缀/后缀/疑诊/自定义/体系: {d}'
assert ok(call('GET', ws_url, token=doc_a), 'A 打开本人往次工作区')['emr']['chiefComplaint'] == 'E2E80咽痛两天'
ok(call('GET', assist_url, token=t), '管理员读诊断助手')
ok(call('GET', hist_url, token=t), '管理员读历次就诊')
ok(call('GET', ws_url, token=t), '管理员打开往次工作区')
print('[2] 接诊医生与管理员照常 OK（历次就诊诊断追加 prefix/suffix/certainty/customName/diagSystem）')

# ---- [3] B 名下有该患者的（往次）号即可读患者级（已挂号未接诊也算）；退号不算 ----
sch_b = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': yday, 'fee': 0, 'capacity': 9,
                                                  'doctorId': b_id}, t), 'B 昨日排班')
rid_b = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch_b['id']}, t), '挂 B 的号')['id']
ok(call('GET', hist_url, token=doc_b), 'B 名下有号后读历次就诊')
ok(call('GET', assist_url, token=doc_b), 'B 名下有号后读诊断助手')
denied(call('GET', ws_url, token=doc_b), 'B 名下有号也不得打开 A 的往次工作区（就诊级只看这一次）')
ok(call('PUT', f'/outpatient/registrations/{rid_b}/cancel', token=t), '退 B 的号')
denied(call('GET', hist_url, token=doc_b), 'B 退号后读历次就诊')
print('[3] 归属判据 OK（本人名下未退号挂号即可读患者级；退号后恢复 4036；他人往次工作区仍 4036）')

# ---- [4] 代班：今天挂在 A 名下的号由 B 接诊并写病历——当日队列共享，B 读患者既往照常 ----
sch_t = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 9,
                                                  'doctorId': a_id}, t), 'A 今日排班')
rid_t = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch_t['id']}, t), '挂 A 的号（今天）')['id']
ok(call('GET', f'/outpatient/doctor/{rid_t}/workspace', token=doc_b), 'B 接诊前打开当日工作区')
ok(call('GET', hist_url, token=doc_b), 'B 接诊前读历次就诊（当日队列共享）')
ok(call('POST', f'/outpatient/doctor/{rid_t}/start', {}, doc_b), 'B 代班接诊')
ok(call('PUT', f'/outpatient/doctor/{rid_t}/emr',
        {'emr': {'chiefComplaint': 'E2E80代班复诊', 'advice': '继续观察'},
         'diagnoses': [{'icdCode': 'J02.900', 'icdName': '急性咽炎', 'diagSystem': 'ICD10'}]}, doc_b), 'B 写病历')
hb = ok(call('GET', hist_url, token=doc_b), 'B 代班后读历次就诊')
assert [h['registrationId'] for h in hb][:2] == [rid_t, rid], f'历次就诊须按就诊日期倒序（今天在前）: {hb}'
assert any(h['icdCode'] == 'J02.900' for h in ok(call('GET', assist_url, token=doc_b), 'B 代班后读诊断助手')['history'])
ok(call('GET', f'/outpatient/doctor/{rid_t}/emr/amendments', token=doc_b), 'B 读当日补正历史')
denied(call('GET', ws_url, token=doc_b), 'B 代班后仍不得打开 A 的往次工作区')
print('[4] 代班 OK（当日号 B 接诊前后均可读工作区与患者既往；历次就诊今天在前；A 的往次工作区仍 4036）')

# ---- [5] 审计复现路径：doctor02 读 doctor01 的患者张三（演示库有这两个账号与张三时）----
# bootstrap-demo 给张三挂了今天的号（doctor01 的五张单据），按口径当日队列共享——doctor02 读张三的患者级资料**放行**；
# 张三昨天那次就诊（doctor01 接诊并写病历）是往次，doctor02 打开它的工作区仍须 4036。
d1 = call('POST', '/auth/login', {'username': 'doctor01', 'password': 'Demo1234'})
d2 = call('POST', '/auth/login', {'username': 'doctor02', 'password': 'Demo1234'})
zs = (call('GET', '/patients?keyword=510181199003078511&page=0&size=5', token=t).get('data') or {}).get('records') or []
if d1.get('code') == 0 and d2.get('code') == 0 and zs:
    zid = zs[0]['id']
    t1, t2 = d1['data']['token'], d2['data']['token']
    owned = call('GET', f'/outpatient/doctor/patient/{zid}/history', token=t1)
    if owned['code'] == 0 and owned['data']:
        has_today = any(h['visitDate'] == today for h in owned['data'])
        past = [h['registrationId'] for h in owned['data'] if h['visitDate'] != today]
        if has_today:
            ok(call('GET', f'/outpatient/doctor/patient/{zid}/history', token=t2), 'doctor02 读张三历次就诊（当日队列共享）')
            ok(call('GET', f'/outpatient/doctor/diagnosis-assist?patientId={zid}', token=t2), 'doctor02 读张三诊断助手')
            print('[5a] 张三今天有号：doctor02 读张三患者级资料放行（当日就诊队列全院共享）')
        else:
            denied(call('GET', f'/outpatient/doctor/patient/{zid}/history', token=t2), 'doctor02 读张三历次就诊')
            print('[5a] 张三今天无号：doctor02 读张三患者级资料 4036')
        if past:
            denied(call('GET', f'/outpatient/doctor/{past[0]}/workspace', token=t2), 'doctor02 打开张三往次就诊工作区')
            ok(call('GET', f'/outpatient/doctor/{past[0]}/workspace', token=t1), 'doctor01 打开本人接诊的往次工作区')
            print('[5b] 审计复现 OK（doctor02 打开 doctor01 接诊的张三往次就诊工作区 4036；doctor01 照常）')
        else:
            print('[5b] 跳过：张三无往次就诊')
    else:
        print(f"[5] 跳过：doctor01 读不到张三的就诊（{owned.get('code')}）")
else:
    print('[5] 跳过：库里没有 doctor01 / doctor02 / 张三（未跑 bootstrap-demo）')

print('\ne2e-v80-access 全部通过 ✅')
