# -*- coding: utf-8 -*-
"""v79 车道A E2E：临床资料引用扩到微生物与病理（992★ / 1019★ 病理项）+ 开单长度后端校验。自成一体。

走真实 HTTP，自建患者，不连库：
  [1] 空态：新患者 MICRO / PATH 两段 count=0、snippet 空串
  [2] 微生物：开检验 → 收费 → 采样 → 核收 → 录微生物（含药敏）→ **未发布时仍不可引用** → 审核发布 → MICRO 有行
      （菌名 / 革兰 / 药敏 S·R 文字），时间区间外（明天起）为 0
  [3] 病理：开病理 → 登记 → 核收 → 写诊断 → **未签发时仍不可引用** → 初签 → 复签（另一人）→ 签发 → PATH 有行
  [4] 跨患者不串：另一新患者两段皆空
  [5] 开单超长：临床摘要 501 字 → 4000「第 1 行临床摘要超过 500 字（当前 501 字）」，医嘱数不变；500 字放行
"""
import datetime
import time

from e2elib import call, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj()
SUF = str(int(time.time() * 1000) % 100000000)


def iso(mins):
    return (datetime.datetime.now(datetime.timezone.utc)
            + datetime.timedelta(minutes=mins)).isoformat().replace('+00:00', 'Z')


def visit(pid):
    sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today.isoformat(), 'fee': 0,
                                                    'capacity': 5}, t), '排班')
    rid = ok(call('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}, t), '挂号')['id']
    ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
    return rid


def ref(rid, kind, frm=None, to=None):
    p = f'/outpatient/emr-ref?registrationId={rid}&kind={kind}'
    if frm:
        p += f'&from={frm}'
    if to:
        p += f'&to={to}'
    return ok(call('GET', p, token=t), f'引用 {kind}')


def item(keyword, category, must_contain):
    rows = ok(call('GET', f'/masterdata/charge-items?category={category}&keyword=' + q(keyword), token=t), '收费项目')
    hit = next((r for r in rows if must_contain in (r.get('name') or '')), None)
    assert hit, f'主数据无「{keyword}」类收费项目（主数据缺项）：{rows[:3]}'
    return hit


pid = new_patient(t, f'引用微病E2E{SUF}', sex='M')['id']
rid = visit(pid)

# ---- [1] 空态 ----
for k in ('MICRO', 'PATH'):
    seg = ref(rid, k)
    assert seg['kind'] == k and seg['count'] == 0 and seg['snippet'] == '' and seg['items'] == [], seg
bad = call('GET', f'/outpatient/emr-ref?registrationId={rid}&kind=NOPE', token=t)
assert bad['code'] == 4000 and 'MICRO' in bad['message'] and 'PATH' in bad['message'], bad
print('[1] 空态 OK（MICRO / PATH count=0、snippet 空；非法 kind 4000 且列出新两类）')

# ---- [2] 微生物 ----
lab = item('尿常规', 'LAB', '尿常规')
path_item = item('病理', 'EXAM', '病理')
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders',
                 {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'specimenType': '中段尿'},
                            {'orderType': 'EXAM', 'itemId': path_item['id'], 'qty': 1,
                             'clinicalSummary': '胃镜见胃窦黏膜粗糙'}]}, t), '开单')
lab_oid = next(o['id'] for o in orders if o['orderType'] == 'LAB')
path_oid = next(o['id'] for o in orders if o['orderType'] == 'EXAM')
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')
bc = ok(call('POST', f'/lis/samples?orderId={lab_oid}', {}, t), '采样')['barcode']
ok(call('PUT', f'/lis/samples/{bc}/receive', token=t), '核收')
ok(call('POST', f'/lis/micro/{bc}', {'specimen': '中段尿', 'organism': '大肠埃希菌', 'gram': 'NEG',
                                    'colonyCount': '>10^5 CFU/mL',
                                    'ast': [{'antibiotic': '头孢曲松', 'method': 'MIC', 'micValue': '1', 'sir': 'S'},
                                            {'antibiotic': '左氧氟沙星', 'method': 'MIC', 'micValue': '8', 'sir': 'R'}]},
        t), '录微生物')
assert ref(rid, 'MICRO')['count'] == 0, '标本未发布时微生物结果不得被引用'
ok(call('POST', f'/lis/samples/{bc}/publish',
        {'results': [{'code': 'UWBC', 'name': '尿白细胞', 'value': '3+', 'unit': '', 'refRange': '阴性', 'flag': 'H'}]},
        t), '审核发布')
seg = ref(rid, 'MICRO')
assert seg['count'] == 1, seg
txt = seg['items'][0]['text']
for frag in ('培养：大肠埃希菌', '革兰阴性', '菌落计数 >10^5 CFU/mL', '头孢曲松 MIC 1 敏感(S)', '左氧氟沙星 MIC 8 耐药(R)'):
    assert frag in txt, f'缺「{frag}」：{txt}'
assert seg['items'][0]['currentVisit'] is True and len(seg['items'][0]['raw']['ast']) == 2, seg['items'][0]
assert seg['snippet'].startswith('【微生物培养与药敏】'), seg['snippet']
tomorrow = (today + datetime.timedelta(days=1)).isoformat()
assert ref(rid, 'MICRO', frm=tomorrow)['count'] == 0, '区间（明天起）外不应有行'
assert ref(rid, 'MICRO', frm=today.isoformat(), to=today.isoformat())['count'] == 1, '当天区间应有行'
print('[2] 微生物 OK（未发布不可引用 → 发布后 1 行，菌名/革兰/药敏 S·R 齐；时间区间生效）')

# ---- [3] 病理 ----
reg = ok(call('POST', '/pathology/registry/specimens',
              {'orderId': path_oid, 'partNo': 1, 'specimenType': 'ROUTINE', 'specimenDesc': '胃窦黏膜组织 2 粒',
               'samplingSite': '胃窦', 'clinicalDiagnosis': '慢性胃炎？', 'fixative': '10%中性福尔马林',
               'fixedAt': iso(-30), 'urgent': False}, t), '病理登记')
sid = reg.get('id') or reg.get('specimenId')
pbc = reg.get('barcode') or reg.get('barCode')
ok(call('PUT', f'/pathology/registry/specimens/{sid}/receive-check', {}, t), '病理核收')
ok(call('PUT', f'/pathology/specimens/{pbc}/diagnose',
        {'grossFinding': '灰白碎组织 2 粒，直径 0.2cm', 'microFinding': '胃窦黏膜慢性炎细胞浸润，腺体轻度萎缩',
         'diagnosis': f'（胃窦）慢性萎缩性胃炎（E2E{SUF}）'}, t), '病理诊断')
assert ref(rid, 'PATH')['count'] == 0, '已诊断未签发的病理报告不得被引用'
t2 = provision_user(t, 'e2e_v79_signer', 'DOCTOR_OUTP', 'E2E复诊医师')
ok(call('PUT', f'/pathology/report/{sid}/first-sign', {}, t), '初诊签名')
ok(call('PUT', f'/pathology/report/{sid}/second-sign', {}, t2), '复诊签名')
ok(call('PUT', f'/pathology/report/{sid}/issue', {}, t), '正式签发')
seg = ref(rid, 'PATH')
assert seg['count'] == 1, seg
txt = seg['items'][0]['text']
for frag in (f'病理诊断：（胃窦）慢性萎缩性胃炎（E2E{SUF}）', '镜下所见：胃窦黏膜慢性炎细胞浸润', '取材部位：胃窦'):
    assert frag in txt, f'缺「{frag}」：{txt}'
assert seg['items'][0]['source'] == 'OUTP' and seg['snippet'].startswith('【病理报告】'), seg
assert ref(rid, 'PATH', to=(today - datetime.timedelta(days=1)).isoformat())['count'] == 0, '区间（到昨天）外不应有行'
print('[3] 病理 OK（未签发不可引用 → 双签签发后 1 行，诊断/镜下/取材部位齐；时间区间生效）')

# ---- [4] 跨患者不串 ----
other = visit(new_patient(t, f'引用他人E2E{SUF}', sex='F')['id'])
for k in ('MICRO', 'PATH'):
    assert ref(other, k)['count'] == 0, f'{k} 串到了他人'
print('[4] 跨患者不串 OK')

# ---- [5] 开单长度后端校验 ----
before = len(ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')['orders'])
r = call('POST', f'/outpatient/doctor/{rid}/orders',
         {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'clinicalSummary': '摘' * 501}]}, t)
assert r['code'] == 4000 and '第 1 行临床摘要超过 500 字（当前 501 字）' in r['message'], r
r = call('POST', f'/outpatient/doctor/{rid}/orders',
         {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1},
                    {'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'specimenType': '标' * 33}]}, t)
assert r['code'] == 4000 and '第 2 行标本类型超过 32 字（当前 33 字）' in r['message'], r
assert len(ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')['orders']) == before, \
    '超长被拒的整单不得有任何一行落库'
ok(call('POST', f'/outpatient/doctor/{rid}/orders',
        {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1, 'clinicalSummary': '摘' * 500,
                    'samplingSite': '位' * 32}]}, t), '恰好上限放行')
print('[5] 开单长度校验 OK（501 字/33 字 → 4000 点名行与字段、零落库；恰好上限放行）')

print('\ne2e-v79-emrref 全部通过 ✅')
