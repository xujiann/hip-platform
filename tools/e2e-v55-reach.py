# -*- coding: utf-8 -*-
"""v55 可达性收口 E2E：后端做了、前端够不着的四类缺陷，钉住「用户走得到」。

本套的由来不是需求，是核账：v48–v53 交付后复核 129 条 ★「部分响应」，0 条可上调，
反驳者挖出的全是同一类——**后端做了，前端够不着**。v54 的 ReachabilityTest 保证
「控制器有前端引用」，但**控制器有前端引用 ≠ 用户走得到**。本套钉的正是那一层。

三段各对应一条核账坐实的缺陷，断言全是**修复前主控实测过的反向事实**：
  [1] 217  ——修复前 gate=off/warn/block 三档全 4012（关键词闸对 gate 失明）；
  [2] 2553 ——修复前已诊断标本在 scope=all / stained 下全部消失；
  [3] 994  ——修复前全系统无任何页面把 outp_emr.id 交给用户。
另钉一条本版刻意不做的边界：版本留痕**只回看不回滚**。

助手逐字抄自 e2e-v51-cdss.py / e2e-v48-pathology.py（已验证能跑通），不凭印象猜契约——
本仓已因猜契约返工多次（驼峰/蛇形、rows/items/groups、排班是 POST 建、开药前须先 start）。
"""
import datetime
import io
import re
import time
import urllib.parse as _u

from e2elib import call, login, new_patient, ok, today_bj  # noqa: E402

t = login()
today = today_bj()


def api(m, p, b=None, **kw):
    return call(m, p, b, t, **kw)


def q(s):
    return _u.quote(s, safe='')


def uniq(pre):
    return pre + str(int(time.time() * 1000) % 100000000)


def iso(mins):
    return (datetime.datetime.now(datetime.timezone.utc)
            + datetime.timedelta(minutes=mins)).isoformat().replace('+00:00', 'Z')


def set_gate(v):
    ok(api('PUT', f'/config/cdss.gate.allergy?value={v}'), f'置 gate={v}')


def new_drug(name):
    code = uniq('V55')
    r = api('POST', '/masterdata/drugs/import', text=f'{code},{name},0.25g*24粒/盒,盒,胶囊,10.00,100,0')
    assert r['code'] == 0 and r['data']['imported'] == 1, f'建药失败：{r}'
    hit = [x for x in ok(api('GET', f'/masterdata/drugs?keyword={q(name)}&all=true'), '查药') if x['code'] == code]
    assert hit, '建了药却查不到'
    return hit[0]


def visited(pid, dept_id=None):
    depts = ok(api('GET', '/system/depts'), '科室')
    dept = next((x for x in depts if x['type'] != 'NURSING'), depts[0]) if dept_id is None else {'id': dept_id}
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': dept['id'], 'scheduleDate': today.isoformat(), 'fee': '0.00', 'capacity': 50}), '排班')
    reg = ok(api('POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']}), '挂号')
    ok(api('POST', f"/outpatient/doctor/{reg['id']}/start", {}), '接诊')
    return reg['id']


def order_drug(rid, did):
    return api('POST', f'/outpatient/doctor/{rid}/orders',
               {'lines': [{'orderType': 'DRUG', 'itemId': did, 'qty': 1, 'usageRoute': '口服',
                           'frequency': 'tid', 'dosePerTime': '1粒', 'days': 3}]})


# ===========================================================================
# 1) 217：关键词闸纳入 gate 管辖；出厂态（无结构化覆盖）warn/block 仍值守
# ===========================================================================
drug = new_drug('阿莫西林胶囊' + uniq(''))
pid = new_patient(t, '217可达' + uniq(''), sex='M', allergyHistory='青霉素过敏')['id']
try:
    set_gate('off')
    r = order_drug(visited(pid), drug['id'])
    assert r['code'] == 0, (
        f'gate=off 时关键词闸必须让路（修复前三档全 4012，off 对过敏审查是假的）：{r}')
    for gate in ('warn', 'block'):
        set_gate(gate)
        r = order_drug(visited(pid), drug['id'])
        assert r['code'] == 4012, (
            f'出厂态（结构化目录为空、原文未核对）gate={gate} 关键词闸必须仍值守——'
            f'**删了闸就是裸奔**。实际：{r}')
finally:
    set_gate('warn')
print('[1] 217 OK（**gate=off 关键词闸让路** / 出厂态 warn+block 仍 4012，不裸奔）')

# ===========================================================================
# 2) 2553/2558：已诊断标本在阅片列表能搜到、报告抽屉打得开
# ===========================================================================
ppid = new_patient(t, '2553可达', sex='F')['id']
rid = visited(ppid, dept_id=1)
items = ok(api('GET', '/masterdata/charge-items'), '收费项目')
cand = next((i for i in items if '病理' in (i.get('name') or '') or '活检' in (i.get('name') or '')), None)
assert cand, '主数据无病理收费项目（主数据缺项，非功能缺陷）'
ok(api('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'EXAM', 'itemId': cand['id'], 'qty': 1}]}), '开病理医嘱')
ok(api('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}), '结算')
pend = ok(api('GET', '/pathology/pending'), '待取材')
oid = next((p['order_id'] for p in pend if p.get('order_id')), None)
assert oid, '待取材队列里没有刚开的病理医嘱'
reg = ok(api('POST', '/pathology/registry/specimens',
             {'orderId': oid, 'partNo': 1, 'specimenType': 'ROUTINE', 'specimenDesc': '2553可达',
              'samplingSite': '左乳', 'clinicalDiagnosis': '待查', 'fixative': '福尔马林',
              'fixedAt': iso(-30), 'urgent': False}), '登记')
sid = reg.get('id') or reg.get('specimenId')
bc = reg.get('barcode') or reg.get('barCode')
ok(api('PUT', f'/pathology/registry/specimens/{sid}/receive-check', {}), '接收核对')


def in_worklist(scope):
    r = ok(api('GET', f'/pathology/report/worklist?scope={scope}&keyword={q(bc)}&limit=200'), f'阅片列表 {scope}')
    return any(str(x.get('id')) == str(sid) or x.get('barcode') == bc for x in (r.get('items') or []))


ok(api('PUT', f'/pathology/specimens/{bc}/diagnose',
       {'grossFinding': 'g', 'microFinding': 'm', 'diagnosis': '2553可达诊断'}), '书写诊断')
assert not in_worklist('stained'), '默认档 stained 仍应只看未诊断——既有契约不变'
assert in_worklist('diagnosed'), (
    '已诊断标本必须能在 scope=diagnosed 搜到——修复前基础 SQL 写死 diagnosed_at is null，'
    '已诊断标本永久消失、报告追溯无入口')
assert in_worklist('any'), 'scope=any（按病理号/患者追溯）也必须能搜到'
rep = ok(api('GET', f'/pathology/report/{sid}/reports'), '报告抽屉')
assert rep.get('hasPrimary') is True, f'报告抽屉应能打开且 hasPrimary=true：{rep}'
print('[2] 2553/2558 OK（默认档不变 / **diagnosed 与 any 能搜到已诊断标本** / 报告抽屉打得开）')

# ===========================================================================
# 3) 994：版本留痕的入口前提——后端把 outp_emr.id 交给了医生站
# ===========================================================================
epid = new_patient(t, '994可达', sex='M')['id']
erid = visited(epid)
saved = ok(api('PUT', f'/outpatient/doctor/{erid}/emr',
               {'emr': {'chiefComplaint': '994可达主诉', 'presentIllness': '现病史v1'}, 'diagnoses': []}), '写病历')
ws = ok(api('GET', f'/outpatient/doctor/{erid}/workspace'), '医生站工作区')
emr = ws.get('emr') or {}
assert emr.get('id'), (
    f'医生站工作区必须把 emr.id 交出来——修复前全系统没有任何页面显示 outp_emr.id，'
    f'版本留痕页只有一个空输入框，医生不知道该填什么：{ws}')
vers = ok(api('GET', f"/emr/versions/OUTP/{emr['id']}"), '版本列表')
assert (vers.get('total') or 0) >= 1, f'保存一次应至少有一版：{vers}'
print('[3] 994 OK（**workspace 交出 emr.id** / 版本列表按该 id 直达）')

# ===========================================================================
# 4) 边界：只回看不回滚——版本留痕控制器不得有任何写映射
# ===========================================================================
src = io.open('modules/outpatient/src/main/java/cn/hip/outpatient/web/EmrVersionController.java',
              encoding='utf-8').read()
# **只认行首的注解用法，不认裸 token**：该类的 javadoc 里明写着「全类一个 @PostMapping / @PutMapping
# 都没有」，裸 `@PostMapping` 匹配会撞上这句说明文字本身——首版本套就是这么误报的。
# 与 v53 那个把触发器声明 `before insert or update` 判成回填的扫描器同一类错：匹配语法形态，不匹配字面。
# 真正的注解永远在行首（缩进后紧跟 @），javadoc 里的提法前面是 ` * ` 与 `{@code`。
assert not re.search(r'^\s*@(Post|Put|Delete|Patch)Mapping\b', src, re.M), (
    '版本留痕控制器出现了写端点——「恢复到某一版」会让当前版本的责任人变得不清楚，法定病历不做回滚')
# 回滚语义同理只查代码，不查注释：把 // 与 /* */ 剥掉再搜
code_only = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
code_only = re.sub(r'//[^\n]*', '', code_only)
assert not re.search(r'restore|rollback|revert', code_only, re.I), '版本留痕控制器代码里不得出现回滚语义'
print('[4] 边界 OK（**只回看不回滚**：EmrVersionController 零写映射、零回滚语义）')

print('\ne2e-v55-reach 全部通过 ✅')
