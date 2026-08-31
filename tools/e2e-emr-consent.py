# -*- coding: utf-8 -*-
"""v34 EMR 合规文书轮 E2E：知情同意书双签 + 手术同意 gate(warn→block→放行) + 三级查房 + 病历时限扩展。
自成一体（自建患者/入院），末尾复位 gate 为 warn 不污染其他套件。"""
import sys
from e2elib import call, ensure_not_admitted, find_free_bed, login, new_patient, ok  # noqa: E402

t = login()

# 入院一个患者（自建，收尾出院）
pid = new_patient(t, '合规文书E2E', sex='M')['id']
free = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions',
              {'patientId': pid, 'deptId': 2, 'bedId': free['id'], 'deposit': 0, 'payMethod': 'CASH'}, t), '入院')
admId = adm['id']

# ---- 1) 知情同意书双签流转 ----
assert call('POST', '/emr/consents', {'admissionId': admId, 'consentType': 'NOPE', 'content': 'x'}, t)['code'] == 9110, '类型非法'
assert call('POST', '/emr/consents', {'admissionId': admId, 'consentType': 'PROXY', 'content': '委托'}, t)['code'] == 9113, '委托信息缺失'
cid = ok(call('POST', '/emr/consents',
              {'admissionId': admId, 'consentType': 'SURGERY', 'content': '拟行阑尾切除术，已告知风险'}, t), '建同意书')['id']
# 未患者签先医师签 → 9114
assert call('POST', f'/emr/consents/{cid}/doctor-sign', {}, t)['code'] == 9114, '须先患者签'
ok(call('POST', f'/emr/consents/{cid}/patient-sign', {'patientSign': '患者本人'}, t), '患者签')
ok(call('POST', f'/emr/consents/{cid}/doctor-sign', {}, t), '医师CA签')
detail = ok(call('GET', f'/emr/consents/{cid}', token=t), '详情')
assert detail['status'] == 'SIGNED' and detail['doctor_sign'], '双签后应生效'
print('[1] 知情同意书双签流转 OK（DRAFT→患者签→医师CA签→SIGNED）')

# ---- 2) 手术同意 gate：warn 放行(带warning) → block 拦(9116) → 有同意书放行 ----
warn = ok(call('POST', '/inpatient/surgeries',
               {'admissionId': admId, 'procedureName': '阑尾切除术', 'anesthesiaType': '全麻'}, t), '手术申请warn')
# 该住院已有 SIGNED 手术同意书，warn 下无 warning；换个无同意书的住院验 warning
pid2 = new_patient(t, '无同意书E2E', sex='F')['id']
free2 = find_free_bed(t)
adm2 = ok(call('POST', '/inpatient/admissions',
               {'patientId': pid2, 'deptId': 2, 'bedId': free2['id'], 'deposit': 0, 'payMethod': 'CASH'}, t), '入院2')
w2 = ok(call('POST', '/inpatient/surgeries',
             {'admissionId': adm2['id'], 'procedureName': '疝修补术', 'anesthesiaType': '局麻'}, t), '手术warn2')
assert 'warning' in w2, f'无同意书 warn 模式应带 warning: {w2}'
# 收紧为 block
ok(call('PUT', '/config/emr.gate.consent.surgery?value=block', token=t), '置block')
assert call('POST', '/inpatient/surgeries',
            {'admissionId': adm2['id'], 'procedureName': '疝修补术', 'anesthesiaType': '局麻'}, t)['code'] == 9116, 'block 应拦'
# adm（有 SIGNED 手术同意书）在 block 下仍放行
ok(call('POST', '/inpatient/surgeries',
        {'admissionId': admId, 'procedureName': '阑尾切除术', 'anesthesiaType': '全麻'}, t), 'block下有同意书放行')
# 复位为 warn，避免污染其他套件
ok(call('PUT', '/config/emr.gate.consent.surgery?value=warn', token=t), '复位warn')
print('[2] 手术同意 gate OK（warn 带warning放行 / block 9116拦 / 有同意书放行 / 已复位warn）')

# ---- 3) 三级查房结构化 ----
assert call('POST', f'/inpatient/admissions/{admId}/records/round',
            {'roundLevel': 'BOSS', 'roundOpinion': 'x'}, t)['code'] == 9119, '级别非法'
assert call('POST', f'/inpatient/admissions/{admId}/records/round',
            {'roundLevel': 'CHIEF', 'roundOpinion': '  '}, t)['code'] == 9120, '意见空'
for lv, op in [('CHIEF', '病情平稳'), ('ATTENDING', '调整用药'), ('RESIDENT', '患者好转')]:
    ok(call('POST', f'/inpatient/admissions/{admId}/records/round', {'roundLevel': lv, 'roundOpinion': op}, t), f'查房{lv}')
rounds = ok(call('GET', f'/inpatient/admissions/{admId}/records/rounds', token=t), '查房列表')
assert len(rounds) == 3, f'三级查房 3 条: {len(rounds)}'
assert len(ok(call('GET', f'/inpatient/admissions/{admId}/records/rounds?level=CHIEF', token=t), '主任查房')) == 1
# 泛型读取兼容：ROUND 纳入病历列表
recs = ok(call('GET', f'/inpatient/admissions/{admId}/records', token=t), '病历列表')
assert any(r['recordType'] == 'ROUND' for r in recs), '查房应纳入病历列表'
print('[3] 三级查房结构化 OK（三级录入/级别过滤/9119/9120/泛型读兼容）')

# ---- 4) 病历时限质控扩展键 ----
tl = ok(call('GET', '/quality/emr-timeliness', token=t), '时限质控')
for k in ['missingFirstProgress', 'missingRound', 'roundCheckEnabled', 'progressContinuityDefect',
          'rescueLateRecord', 'defectBreakdown', 'defectTotal']:
    assert k in tl, f'时限质控缺键 {k}'
ok(call('GET', '/quality/emr-timeliness/board', token=t), '时限看板')
print('[4] 病历时限质控扩展 OK（7 项 + 缺陷明细 + 科室看板）')

# ---- 5) 出院/归档完整性 gate（v35）：预检缺项 → block 拦出院(9124) → warn 放行 ----
rep = ok(call('GET', f'/inpatient/admissions/{admId}/emr-integrity', token=t), '完整性预检')
assert rep['complete'] is False and rep['missing'], f'该住院无入院/出院记录应不完整: {rep}'
assert '缺入院记录' in rep['missing']
ok(call('PUT', '/config/emr.gate.discharge?value=block', token=t), '置 discharge block')
# adm 无入院/出院小结 → block 下拦
r = call('POST', f'/inpatient/admissions/{admId}/discharge', {'payMethod': 'CASH'}, t)
assert r['code'] == 9124, f'block 下不完整病历应拦出院: {r}'
# 复位 warn（默认），出院放行
ok(call('PUT', '/config/emr.gate.discharge?value=warn', token=t), '复位 warn')
print('[5] 出院完整性 gate OK（预检列缺项 / block 拦 9124 / warn 放行；已复位）')

# 收尾出院释放床位
ensure_not_admitted(t, pid)
ensure_not_admitted(t, pid2)
print('\ne2e-emr-consent 全部通过 ✅')
