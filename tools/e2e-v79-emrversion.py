# -*- coding: utf-8 -*-
"""v79 E2E（偏离表 994★）：病历留痕的读出在**真实服务**上走一遍。

JUnit（V79EmrVersionReadTest）跑在回滚事务里、用 MockMvc 冒充登录——JWT、线格式、真实提交后的读出都没走过。
本套用 HTTP 把规划节车道 C 的契约逐条钉住：
  [1] 诊断留痕读出：建患者 → 挂号 → 接诊 → 保存病历带诊断 A（疑似…急性期，疑诊）→ 改为诊断 B 再保存
      → GET /emr/versions/diagnoses/{挂号id} 得两版：倒序、每版明细照写入时快照、操作人姓名、较上一版增删；
  [2] 门诊正文版本列表随体带回 registrationId（版本页据此接诊断变更块），且与本次挂号一致；
  [3] 跨就诊不串：同一患者另一次就诊的诊断读出不含本次诊断；该就诊零版本时空态只陈述事实（不含「上线之前」类断言）；
  [4] 挂号不存在 4001、id 非法 4000；
  [5] 住院医生站入口的目标有数据：新建住院记录 → GET /emr/versions/INP/{记录id} 至少一版（MANUAL），签名后多一版（SUBMIT）；
      收尾出院释放床位。
自成一体：自建患者、排班、挂号、入院；时间一律从 e2elib.today_bj() 取，不写墙钟字面量。
"""
from e2elib import call, discharge_cleanup, find_free_bed, login, new_patient, ok, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()
UNPROVABLE = ('上线之前', '上线前书写', '本就没有')


def api(m, p, b=None):
    return call(m, p, b, t)


me = ok(api('GET', '/auth/me'), '当前用户')
my_name = me.get('realName') or me.get('real_name')

# ============ [1] 诊断改一次 → 读出两版 ============
pa = new_patient(t, 'V79留痕', sex='F')
sch = ok(api('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 30}), '排班')
rid = ok(api('POST', '/outpatient/registrations', {'patientId': pa['id'], 'scheduleId': sch['id']}), '挂号')['id']
ok(api('POST', f'/outpatient/doctor/{rid}/start', {}), '接诊')

emr_a = ok(api('PUT', f'/outpatient/doctor/{rid}/emr', {
    'emr': {'chiefComplaint': '咳嗽三天', 'presentIllness': '受凉后咳嗽'},
    'diagnoses': [{'icdCode': 'J18.9', 'icdName': '肺炎', 'prefix': '疑似', 'suffix': '急性期',
                   'certainty': 'SUSPECTED', 'diagSystem': 'ICD10'}]}), '保存病历（诊断 A）')
emr_id = emr_a['id']
ok(api('PUT', f'/outpatient/doctor/{rid}/emr', {
    'emr': {'chiefComplaint': '咳嗽三天，伴发热', 'presentIllness': '受凉后咳嗽'},
    'diagnoses': [{'icdCode': 'J20.9', 'icdName': '急性支气管炎', 'certainty': 'CONFIRMED'}]}), '保存病历（诊断 B）')

d = ok(api('GET', f'/emr/versions/diagnoses/{rid}'), '诊断版本读出')
assert d['registrationId'] == rid and d['total'] == 2, f'A → B 应两版: {d}'
v2, v1 = d['items']
assert (v2['versionNo'], v1['versionNo']) == (2, 1), f'应按版本倒序: {[i["versionNo"] for i in d["items"]]}'
a1 = v1['diagnoses'][0]
assert (a1['icdCode'], a1['icdName'], a1['prefix'], a1['suffix'], a1['certainty'], a1['diagSystem'], a1['primary']) \
    == ('J18.9', '肺炎', '疑似', '急性期', 'SUSPECTED', 'ICD10', True), f'第 1 版须照写入时快照读回: {a1}'
assert v1['changes'] is None, f'首版不比: {v1}'
assert v2['diagnoses'][0]['icdName'] == '急性支气管炎' and v2['diagnoses'][0]['certainty'] == 'CONFIRMED', v2
assert any('肺炎' in x and '疑似' in x for x in v2['changes']['removed']), f'第 2 版须点名去掉的 A: {v2["changes"]}'
assert any('急性支气管炎' in x for x in v2['changes']['added']), f'第 2 版须点名加上的 B: {v2["changes"]}'
assert v2['changes']['primaryChanged'] is True, v2['changes']
for v in d['items']:
    assert v['changedBy'] == me['id'] and v['changedByName'] == my_name, f'操作人须是本次登录人: {v}'
    assert v['changedAt'], f'须有时间: {v}'
assert not d.get('notice'), f'有版本时不挂空态提示: {d}'
print(f"[1] 诊断留痕读出 OK：2 版倒序，第 1 版「{a1['display']}」→ 第 2 版「{v2['diagnoses'][0]['display']}」，"
      f"操作人 {my_name}")

# ============ [2] 正文版本列表带回挂号 id ============
lst = ok(api('GET', f'/emr/versions/OUTP/{emr_id}'), '正文版本列表')
assert lst['registrationId'] == rid, f'门诊正文版本列表须带回本次挂号 id: {lst.get("registrationId")} != {rid}'
assert lst['total'] >= 2 and not lst.get('notice'), f'两次保存（正文有变）应至少两版: {lst["total"]}'
print(f"[2] 正文版本 {lst['total']} 版，registrationId={rid} 随体带回 OK")

# ============ [3] 跨就诊不串 + 零版本空态 ============
sch2 = ok(api('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 30}), '排班2')
rid2 = ok(api('POST', '/outpatient/registrations', {'patientId': pa['id'], 'scheduleId': sch2['id']}), '挂号2')['id']
ok(api('POST', f'/outpatient/doctor/{rid2}/start', {}), '接诊2')
ok(api('PUT', f'/outpatient/doctor/{rid2}/emr', {'emr': {'chiefComplaint': '复诊'}, 'diagnoses': []}), '保存病历（无诊断）')
d2 = ok(api('GET', f'/emr/versions/diagnoses/{rid2}'), '另一次就诊诊断读出')
assert d2['total'] == 0 and d2['items'] == [], f'另一次就诊不得串入本次诊断: {d2}'
assert '肺炎' not in str(d2) and '支气管炎' not in str(d2), d2
notice = d2.get('notice') or ''
assert '暂无' in notice and not any(x in notice for x in UNPROVABLE), f'空态只陈述事实: {notice}'
print(f"[3] 跨就诊不串 OK；空态说明：{notice[:40]}…")

# ============ [4] 错误码 ============
r = api('GET', f'/emr/versions/diagnoses/{rid2 + 10_000_000}')
assert r['code'] == 4001, f'挂号不存在应 4001: {r}'
r = api('GET', '/emr/versions/diagnoses/0')
assert r['code'] == 4000, f'id 非法应 4000: {r}'
print('[4] 挂号不存在 4001 / id 非法 4000 OK')

# ============ [5] 住院医生站「版本留痕」入口的目标有数据 ============
pb = new_patient(t, 'V79住院留痕', sex='M')
bed = find_free_bed(t)
adm = ok(api('POST', '/inpatient/admissions', {'patientId': pb['id'], 'deptId': 1, 'bedId': bed['id'],
                                               'deposit': 1000, 'payMethod': 'CASH'}), '入院')
aid = adm['id']
try:
    rec = ok(api('POST', f'/inpatient/admissions/{aid}/records',
                 {'recordType': 'PROGRESS', 'title': '病程记录', 'content': 'V79 病程：体温平稳。'}), '新建住院记录')
    iv = ok(api('GET', f"/emr/versions/INP/{rec['id']}"), '住院版本列表')
    assert iv['total'] == 1 and iv['items'][0]['source'] == 'MANUAL', f'新建记录应落一版 MANUAL: {iv}'
    assert 'registrationId' not in iv, '住院列表不带挂号 id（诊断变更块只对门诊）'
    ok(api('POST', f"/inpatient/admissions/{aid}/records/{rec['id']}/sign", {}), '签名')
    iv2 = ok(api('GET', f"/emr/versions/INP/{rec['id']}"), '住院版本列表（签名后）')
    assert iv2['total'] == 2 and iv2['items'][0]['source'] == 'SUBMIT', f'签名后应多一版 SUBMIT: {iv2}'
    print(f"[5] 住院记录 #{rec['id']} 版本：MANUAL → SUBMIT 共 {iv2['total']} 版 OK")
finally:
    discharge_cleanup(t, aid, 'CASH')

print('\n=== v79 病历留痕读出 E2E（诊断版本读出 + 空态 + 住院入口目标）全部通过 ===')
