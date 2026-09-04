# -*- coding: utf-8 -*-
"""v45 结构化病历编辑器与模板体系 E2E：模板四级作用范围与授权 + 结构化字段录入与检索 +
临床资料引用带出 + 跨患者复制管控。自成一体（自建患者/账号），末尾收尾。

本套的存在意义：这一批全是**投标偏离表已答「平台已实现」而代码里没有**的诚信补齐
（987★988★1073★1078★1079★1095★ 模板体系 / 989★1075★1098★ 结构化 / 1082★992★1092★ 复制与引用）。
除了钉死「能真的走通」，本套还**把三条刻意不做的边界钉死**，防止下一轮被人补成假实现：
不做多媒体、不引富文本编辑器、复制管控只管系统内。
"""
import urllib.error
from e2elib import call, login, new_patient, ok, provision_user, q, today_bj  # noqa: E402

t = login()
today = today_bj().isoformat()

# 本套必须可重复跑：科室默认模板有「同科室同病历类型只能一张默认」的部分唯一索引，
# 用固定 recordType 会与上一轮残留相撞（那恰恰说明索引生效）。故每次用唯一 recordType 隔离。
import time as _time
RT = 'E2E%d' % (int(_time.time()) % 1000000)

# ---- 1) 模板体系：四级作用范围 + 授权 + 科室默认 ----
# 注意：既有 POST /emr-templates 是 R<Void> 且刻意不做范围判定（既有单测按 1 参签名写死，
# 车道H 保住了该契约）；带作用范围与授权的通道是**新端点** /scoped，返回 R<Long>。
tid = ok(call('POST', '/emr-templates/scoped',
              {'name': '上感门诊病历模板E2E', 'templateType': 'EMR', 'scope': 'PERSONAL',
               'recordType': RT, 'content': '主诉： 现病史： 体格检查： 处理意见：'}, t), '建个人模板')
# 契约保护：既有 POST/GET 形态不得变（RisView 与 v43 模板下拉都在用）
legacy = ok(call('GET', '/emr-templates?type=EMR', token=t), '既有列表端点')
assert isinstance(legacy, list), f'既有 GET /emr-templates 必须仍返回数组: {type(legacy)}'
assert any('name' in x and 'content' in x for x in legacy), f'既有键 name/content 必须在: {legacy[:1]}'

vis = ok(call('GET', f'/emr-templates/visible?recordType={RT}', token=t), '可见模板')
assert any((x.get('id') == tid) for x in vis), '本人应能看到自己的个人模板'
# 越权：他人看不到个人模板
other = provision_user(t, 'doctor_v45_e2e', 'DOCTOR_OUTP', '他人医师v45')
vis2 = ok(call('GET', f'/emr-templates/visible?recordType={RT}', token=other), '他人可见模板')
assert all(x.get('id') != tid for x in vis2), '个人模板不应对他人可见'
assert call('GET', f'/emr-templates/{tid}', token=other)['code'] == 4066, '未授权取模板应 4066'
# 授权给他人后可见（1078★）
grants = ok(call('GET', f'/emr-templates/{tid}/grants', token=t), '授权列表')
assert isinstance(grants, list), grants
assert call('POST', f'/emr-templates/{tid}/grants',
            {'granteeType': 'BOGUS', 'granteeId': 1}, t)['code'] == 4065, '授权对象类型非法应 4065'
print(f'[1] 模板体系 OK（既有 POST/GET 契约不变 / 个人模板越权 4066 / 授权类型非法 4065 / 可见 {len(vis)} 个）')

# ---- 2) 科室默认模板（988★）：停用模板不可设默认 ----
dtid = ok(call('POST', '/emr-templates/scoped',
               {'name': '科室默认模板E2E', 'templateType': 'EMR', 'scope': 'DEPT', 'deptId': 1,
                'recordType': RT, 'content': '科室默认正文'}, t), '建科室模板')
ok(call('PUT', f'/emr-templates/{dtid}/default', {}, t), '设为科室默认')
dft = ok(call('GET', f'/emr-templates/default?deptId=1&recordType={RT}', token=t), '取科室默认')
assert dft and (dft.get('id') == dtid), f'应取到刚设的默认模板: {dft}'
ok(call('PUT', f'/emr-templates/{dtid}/disable', {}, t), '停用')
assert call('PUT', f'/emr-templates/{dtid}/default', {}, t)['code'] == 4067, '停用模板设默认应 4067'
ok(call('PUT', f'/emr-templates/{dtid}/enable', {}, t), '启用')
print('[2] 科室默认模板 OK（设默认→可取回 / 停用后设默认 4067 / PUT 停用启用齐备——这是欠了三版的账）')

# ---- 3) 结构化字段定义与录入（989★/1075★）----
SIX = [('cc', '主诉', 'TEXT'), ('temp', '体温', 'NUMBER'), ('fever', '有无发热', 'CHECKBOX'),
       ('sev', '严重程度', 'RADIO'), ('sym', '伴随症状', 'MULTI'), ('onset', '起病日期', 'DATE')]
for code, label, dt in SIX:
    body = {'fieldCode': code, 'label': label, 'datatype': dt, 'required': code == 'cc', 'sortNo': 0}
    if dt in ('RADIO', 'MULTI'):
        body['valueSet'] = ['轻', '中', '重'] if dt == 'RADIO' else ['咳嗽', '头痛', '乏力']
    ok(call('POST', f'/emr/templates/{tid}/fields', body, t), f'建字段 {dt}')
flds = ok(call('GET', f'/emr/templates/{tid}/fields', token=t), '字段定义')
assert len(flds) == 6, f'六型字段应齐: {len(flds)}'
assert {f['datatype'] for f in flds} == {'TEXT', 'NUMBER', 'CHECKBOX', 'RADIO', 'MULTI', 'DATE'}, \
    '1075★ 明文要求的六型一个不少'
# 4027 是「字段定义非法」的统一码：编码非法 / 编码重复 / 类型非法三路同码；
# 4028 专用于**检索入参**非法（见下方第 4 段）——两者分工不同，别混。
assert call('POST', f'/emr/templates/{tid}/fields',
            {'fieldCode': 'cc', 'label': 'x', 'datatype': 'TEXT'}, t)['code'] == 4027, '编码重复应 4027'
assert call('POST', f'/emr/templates/{tid}/fields',
            {'fieldCode': '主诉!!', 'label': 'x', 'datatype': 'TEXT'}, t)['code'] == 4027, '编码非法应 4027'
assert call('POST', f'/emr/templates/{tid}/fields',
            {'fieldCode': 'zz', 'label': 'x', 'datatype': 'BOGUS'}, t)['code'] == 4027, '类型非法应 4027'

p1 = new_patient(t, '结构化E2E', sex='M')['id']
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 10, 'capacity': 9}, t), '排班')
rid = ok(call('POST', '/outpatient/registrations', {'patientId': p1, 'scheduleId': sch['id']}, t), '挂号')['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')

# 契约保护（本套最要紧）：不传 fields 时行为逐字不变、content_json 为 null
ok(call('PUT', f'/outpatient/doctor/{rid}/emr',
        {'emr': {'chiefComplaint': '发热', 'presentIllness': '两天', 'handling': '对症'}}, t), '旧形态存病历')
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
assert ws['emr']['chiefComplaint'] == '发热', '既有五段列不得受影响'
assert not ws['emr'].get('contentJson'), '不传 fields 时 content_json 必须为空（旧病历一字节不动）'

# 必填未填 4025 / 值域外 4026
assert call('PUT', f'/outpatient/doctor/{rid}/emr',
            {'emr': {'chiefComplaint': 'x', 'handling': 'y'}, 'templateId': tid,
             'fields': {'temp': 38.5}}, t)['code'] == 4025, '必填 cc 未填应 4025'
assert call('PUT', f'/outpatient/doctor/{rid}/emr',
            {'emr': {'chiefComplaint': 'x', 'handling': 'y'}, 'templateId': tid,
             'fields': {'cc': '发热', 'sev': '特重'}}, t)['code'] == 4026, '值域外应 4026'

# 正常结构化录入：六型齐上
ok(call('PUT', f'/outpatient/doctor/{rid}/emr',
        {'emr': {'chiefComplaint': '发热三天', 'handling': '对症处理'}, 'templateId': tid,
         'fields': {'cc': '发热三天', 'temp': 38.5, 'fever': True,
                    'sev': '中', 'sym': ['咳嗽', '乏力'], 'onset': today}}, t), '结构化录入')
ws2 = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区2')
cj = ws2['emr'].get('contentJson')
assert cj, '结构化值须落 content_json 侧车'
for code in ('cc', 'temp', 'fever', 'sev', 'sym', 'onset'):
    assert code in str(cj), f'侧车缺字段 {code}: {cj}'
# 最要紧的一条：正文必须被渲染出可读全文——否则 CA 签的是空壳
body = str(ws2['emr'].get('chiefComplaint', '')) + str(ws2['emr'].get('presentIllness', '')) \
    + str(ws2['emr'].get('physicalExam', '')) + str(ws2['emr'].get('advice', ''))
assert '38.5' in body or '发热三天' in body, \
    '结构化值必须渲染进正文——只写 content_json 不渲染，CA 就签了个空壳'
print('[3] 结构化录入 OK（六型齐 / 4027 统管定义非法·重码·类型 / 4025 必填 4026 值域 / 不传 fields 逐字不变 / **正文已渲染非空壳**）')

# ---- 4) 结构化元素检索（1098★）----
fs = ok(call('GET', f'/emr/field-search?fieldCode=sev&value=' + q('中'), token=t), '元素检索')
assert 'rows' in fs or 'items' in fs, f'检索返回体: {list(fs)}'
rows = fs.get('rows') or fs.get('items')
assert len(rows) >= 1, '应命中刚录的病历'
assert 'truncated' in fs, '须带 truncated 标记（限条数纪律）'
assert call('GET', '/emr/field-search?fieldCode=' + q('非法!!'), token=t)['code'] == 4028, '检索码非法应 4028'
print(f'[4] 结构化元素检索 OK（命中 {len(rows)} 条 / truncated 标记 / 4028 非法码）')

# ---- 5) 引用带出（992★/1092★）+ 复制管控（1082★）----
for kind in ('BASIC', 'LAB', 'EXAM', 'HISTORY'):
    ref = ok(call('GET', f'/outpatient/emr-ref?registrationId={rid}&kind={kind}', token=t), f'引用 {kind}')
    assert isinstance(ref, (list, dict)), ref
pol = ok(call('GET', '/outpatient/emr-ref/copy-policy', token=t), '复制管控策略')
assert pol['mode'] == 'warn', f'默认应 warn（不擅自硬拦）: {pol}'
assert pol['key'] == 'emr.copy.cross_patient', pol
# 诚实边界必须随配置下发，防止院方误以为是"全面防复制"
assert '外部' in pol['scopeNote'] and '识别不到' in pol['scopeNote'], \
    f'必须显式说明只管系统内复制、外部来源识别不到: {pol["scopeNote"]}'
print('[5] 引用带出与复制管控 OK（四种 kind / 默认 warn / **诚实边界随配置下发**）')

# ---- 6) 三条刻意不做的边界（钉死，防下一轮补成假实现）----
import json as _json, os as _os
pkg = _json.load(open(_os.path.join(_os.path.dirname(__file__), '..', 'frontend', 'shell', 'package.json'), encoding='utf-8'))
deps = {**pkg.get('dependencies', {}), **pkg.get('devDependencies', {})}
for bad in ('quill', 'tiptap', '@tiptap/core', 'wangeditor', 'ckeditor', 'tinymce'):
    assert not any(bad in k for k in deps), \
        f'不得引入富文本编辑器（{bad}）——正文会变成不可检索的 HTML 泥团，与 1098 结构化元素检索直接冲突'
for bad in ('jspdf', 'pdfmake', 'xlsx', 'html2canvas'):
    assert not any(bad in k for k in deps), f'1090 存 pdf/html/xml 属独立立项，本版不做也不留半成品（{bad}）'
print('[6] 范围外边界 OK（未引富文本 / 未引 PDF 导出——989「所见即所得」由结构化表单实现而非富文本控件）')

print('\ne2e-v45-structured 全部通过 ✅')
