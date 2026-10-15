# -*- coding: utf-8 -*-
"""CDR real JWT regression: each document follows source encounter ownership."""
import datetime
import time
from e2elib import call, login, new_patient, ok, provision_user, today_bj, find_free_bed, discharge_cleanup, q

t = login()
suffix = str(time.time_ns())[-10:]
marker = 'V84文档' + suffix
a = provision_user(t, 'e2e84a' + suffix, 'DOCTOR_OUTP', 'V84医生A')
b = provision_user(t, 'e2e84b' + suffix, 'DOCTOR_OUTP', 'V84医生B')
quality = provision_user(t, 'e2e84q' + suffix, 'QUALITY', 'V84质控')
aid = ok(call('GET', '/auth/me', token=a), 'A身份')['id']
bid = ok(call('GET', '/auth/me', token=b), 'B身份')['id']
p = new_patient(t, 'V84患者' + suffix, sex='U')

def visit(day, doctor_id, token, text):
    s = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'doctorId': doctor_id, 'scheduleDate': str(day), 'fee': 0, 'capacity': 5}, t), '排班')
    rid = ok(call('POST', '/outpatient/registrations', {'patientId': p['id'], 'scheduleId': s['id']}, t), '挂号')['id']
    ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, token), '接诊')
    ok(call('PUT', f'/outpatient/doctor/{rid}/emr', {'emr': {'chiefComplaint': marker + text}, 'diagnoses': []}, token), '病历')
    return rid

own = visit(today_bj(), aid, a, '本人可见正文')
foreign = visit(today_bj() - datetime.timedelta(days=1), bid, b, '他人应隐藏正文')
lab_item = next(x for x in ok(call('GET', '/masterdata/charge-items', token=t), '检验项目') if x['category'] == 'LAB' and x.get('enabled', True))
lab = ok(call('POST', f'/outpatient/doctor/{foreign}/orders', {'lines': [{'orderType': 'LAB', 'itemId': lab_item['id'], 'qty': 1}]}, b), '检验开单')[0]['id']
ok(call('POST', '/outpatient/charges/settle', {'registrationId': foreign, 'payMethod': 'CASH'}, t), '检验收费')
ok(call('POST', f'/outpatient/exec/{lab}', {'resultText': marker + '检验隐私'}, t), '检验执行')
bed = find_free_bed(t)
adm = ok(call('POST', '/inpatient/admissions', {'patientId': p['id'], 'deptId': 1, 'bedId': bed['id'], 'doctorId': bid, 'deposit': 0, 'payMethod': 'CASH'}, t), '住院登记')['id']
try:
    legacy = ok(call('POST', '/cdr/legacy-documents', {'patientNo': p['patientNo'], 'category': 'OUTP', 'legacyKey': marker, 'title': marker + '老系统隐私', 'docDate': str(today_bj()), 'content': '老系统正文'}, t), '老系统导入')
    ok(call('POST', '/cdr/sync', {}, t), 'CDR同步')
    rows = ok(call('GET', f"/cdr/patients/{p['id']}/documents", token=t), '管理员时间轴')
    by_source = {(d['docType'], d['refId']): d['id'] for d in rows}
    own_id = by_source[('OUTP_ENCOUNTER', own)]
    foreign_id = by_source[('OUTP_ENCOUNTER', foreign)]
    lab_id = by_source[('LAB_REPORT', lab)]
    inp_id = by_source[('INP_SUMMARY', adm)]
    legacy_id = by_source[(legacy['docType'], legacy['refId'])]
    mine = ok(call('GET', f"/cdr/patients/{p['id']}/documents", token=a), 'A时间轴')
    assert [d['id'] for d in mine] == [own_id], mine
    assert 'content' not in mine[0], mine
    for doc_id in [foreign_id, lab_id, inp_id, legacy_id]:
        denied = call('GET', f'/cdr/documents/{doc_id}', token=a)
        assert denied['code'] == 1005 and denied['data'] is None, denied
    assert ok(call('GET', f'/cdr/documents/{own_id}', token=a), '本人文档')['id'] == own_id
    hits = ok(call('GET', '/cdr/search?keyword=' + q(marker), token=a), 'A全文检索')
    assert [d['id'] for d in hits] == [own_id], hits
    for doc_id in [foreign_id, lab_id, inp_id]:
        assert ok(call('GET', f'/cdr/documents/{doc_id}', token=b), 'B本人文档')['id'] == doc_id
    for privileged in [t, quality]:
        assert len(ok(call('GET', f"/cdr/patients/{p['id']}/documents", token=privileged), '横向调阅')) == len(rows)
        ok(call('GET', f'/cdr/documents/{legacy_id}', token=privileged), '老系统文档可查')
    # Denial must not invalidate the doctor's login.
    assert ok(call('GET', '/auth/me', token=a), '拒绝后登录仍有效')['id'] == aid
    print('e2e-v84-cdr-access 全部通过：同患者跨就诊隔离、门诊/检验/住院/老系统、三入口与管理员/质控反向回归')
finally:
    discharge_cleanup(t, adm)
