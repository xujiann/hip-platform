# -*- coding: utf-8 -*-
"""v51 CDSS 规则引擎扩充 + 盘点批次层收敛 E2E。

本套钉三类东西，优先级从高到低：

  ① **边界**（§0，不需要后端）。本版有一条写错比不写更危险的规则——过敏。
     三条边界一次都不许被悄悄突破：
       · 自由文本过敏史**不得脚本解析**（「青霉素过敏」vs「青霉素皮试阴性」文本相近而语义相反，
         解析错造成反向拦截：该拦的不拦、不该拦的拦住）；
       · **不得内置来源不明的药学知识**（编出来的配伍禁忌看起来很专业，医生会信，然后出事）；
       · **不得按性别年龄猜妊娠**（「育龄女性即可能妊娠」两头都错）。
     边界放最前是有意的：它们不需要后端且价值最高，放末尾时只要前面任何一步挂了就一次都跑不到，
     而「悄悄补成假的」恰恰最可能发生在功能没做完的那一版。

  ② **闭环**。规则表建好、维护端点建好、`POST /cdss/allergy/check` 能命中，
     都不等于医生开药时会被拦——**接不进开单链路的 CDSS 等于没有 CDSS**。
     故 §3 同时验两件事：check 端点自身命中（[3a]），以及**真的走一遍开单**也命中（[3b]）。
     [3b] 是这一套里唯一直接对应临床安全的断言。

  ③ **账**。§7 还 v50 署名登记的欠账：盘点确认时批次层的差异必须被报出来，不许被静默抹平。

【与 assert 的分工，承 v50】未交付/不可达记 gap 末尾汇总（中途 assert 掉，主控只看得见第一个缺口）；
**边界被突破、既有契约被改坏、账对不上一律当场 assert** ——那是不能带着跑的。

【怎么跑】`python tools/e2e-v51-cdss.py`（后端须运行于 localhost:8080，可用 HIP_E2E_BASE 指向其它实例）。
§0 五节静态边界检查不需要后端，任何时候都能跑；后端须已应用 V152+ 迁移，否则 [2] 会以
「1401 配置项不存在」失败——那说明连的是一个早于本版的实例，不是 gate 真的丢了。
"""
import datetime as _dt
import json as _json
import os as _os
import re as _re
import sys as _sys

_sys.stdout.reconfigure(encoding='utf-8')

ROOT = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))

GAPS = []


def gap(cond, title, detail):
    """未交付/不可达一类的缺口：记录并继续，末尾统一汇总退非零。"""
    if not cond:
        GAPS.append((title, detail))
        print(f'  ✗ [V51-未交付] {title}')
    return bool(cond)


def _source_files(*rel_dirs, suffixes=('.java',)):
    """遍历真代码目录。跳过 target/ node_modules/ 与 .claude/worktrees/
    ——构建产物与他人工作区不是本仓代码，扫进来会得出假结论。"""
    for rel in rel_dirs:
        base = _os.path.join(ROOT, *rel.split('/'))
        if not _os.path.isdir(base):
            continue
        for dp, dn, fn in _os.walk(base):
            dn[:] = [x for x in dn if x not in ('target', 'node_modules', 'worktrees', '.git', 'dist', '.claude')]
            for f in fn:
                if f.endswith(suffixes):
                    p = _os.path.join(dp, f)
                    yield p, open(p, encoding='utf-8', errors='ignore').read()


def _code_lines(text):
    """只认**真代码**：注释里写「不做 X——按药名猜是危险的假实现」是**声明边界**，恰恰要鼓励；
    把它算成突破会逼后人删掉说明，反而让边界从对外可见变成不可见（v48 立、v50 沿用的口径）。"""
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('*') or s.startswith('//') or s.startswith('/*') or s.startswith('--'):
            continue
        yield s


def _rel(p):
    return _os.path.relpath(p, ROOT).replace(_os.sep, '/')


JAVA_DIRS = ('modules', 'platform', 'server/src/main/java', 'datacenter', 'bureau', 'ai-service', 'impl')
MIGRATION_DIR = 'server/src/main/resources/db/migration'


def _new_migrations():
    """本版新增迁移（V152 起）。药学知识与零回填两条纪律只约束新增段，不追溯既有迁移。"""
    for p, t in _source_files(MIGRATION_DIR, suffixes=('.sql',)):
        m = _re.match(r'V(\d+)__', _os.path.basename(p))
        if m and int(m.group(1)) >= 152:
            yield p, t


# ===========================================================================
# 0) 边界钉死（**不需要后端，故放最前**）
# ===========================================================================

# ---- 0.1 自由文本过敏史不得被脚本解析 ----
# 判据是**文件级**：文件读了过敏史，且文件里有对 allergy 变量做字符串匹配/切分的真代码行。
# 既有的一处（v51 之前就在）登记在下面钉死：允许它存在（铁律「既有链路不许改坏」），
# 但**不许再多一处，也不许它自己长大**——往关键词表里加词是最危险的补法。
_LEGACY_FREETEXT = {
    'modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java':
        'checkRationalDrugUse + ALLERGY_CROSS（4 组关键词）：allergy.contains(关键词) 命中即抛 4012。'
        '既漏（原文写「阿莫西林过敏」时不命中）又误（原文写「青霉素皮试阴性」时命中）。',
}
_LEGACY_KEYWORD_CAP = {
    'modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java': 4,
}

# **大小写敏感**且接收者须以小写字母开头：`allergy.contains(...)` 是拿自由文本做匹配（要抓），
# `ALLERGEN_TYPES.contains(...)` 是校验枚举取值域（正常代码，不能抓）。
# 加了 re.I 时后者会被 `\ballerg\w*` 一起吃掉——本套第一次跑就是这么误报的。
_PARSE_RX = _re.compile(
    r'\b(?:allerg[A-Za-z]*|getAllergyHistory\(\))\s*\.\s*'
    r'(contains|matches|split|indexOf|startsWith|endsWith|replaceAll)\s*\(')
_hits = []
for _p, _txt in _source_files(*JAVA_DIRS):
    if 'getAllergyHistory' not in _txt and 'allergy_history' not in _txt:
        continue
    if _rel(_p) in _LEGACY_FREETEXT:
        continue
    for _s in _code_lines(_txt):
        low = _s.lower()
        if (_PARSE_RX.search(_s)
                or ('allergy_history' in _s and (' like ' in low or 'ilike' in low or 'regexp' in low))
                or ('pattern.compile' in low and 'allerg' in low)):
            _hits.append(f'{_rel(_p)}: {_s[:120]}')
assert not _hits, (
    '出现了**新的**自由文本过敏史解析路径：\n' + '\n'.join(_hits[:10])
    + '\n「青霉素过敏」与「青霉素皮试阴性」字符高度相似而语义相反，关键词/正则解析必然同时制造'
      '假阴性（"PCN 过敏" 漏掉）与假阳性（把皮试阴性判成过敏）。而假阳性泛滥的代价不止是麻烦：'
      '医生一旦发现过敏提示经常是错的，就会养成无脑点「继续」的肌肉记忆，真警告也一起被点掉。'
      '\n合规出路只有**人工确认的迁移工作台**：列出原文 → 人逐条判断 → 落结构化过敏原，原文永不被覆盖。')

# 既有那一处的规模在此封顶：只许减不许增
for _f, _cap in _LEGACY_KEYWORD_CAP.items():
    _abs = _os.path.join(ROOT, *_f.split('/'))
    assert _os.path.exists(_abs), f'登记表与真代码脱节：{_f} 已不存在，请同步更新登记表（否则本条静默失效）'
    _n = sum(1 for _s in _code_lines(open(_abs, encoding='utf-8').read())
             if 'List.of(' in _s and '", List.of(' in _s)
    assert _n <= _cap, (
        f'{_f} 的关键词映射条目从 {_cap} 涨到了 {_n}。词越多越容易撞上「皮试阴性」「否认药物过敏史」'
        f'「家族史」这些语义相反的写法。补假阴性只有一条合规路径：结构化过敏原 + 显式药品映射。')
print(f'[0.1] 边界 OK（**自由文本过敏史无新增脚本解析路径**；既有 1 处已登记并封顶 '
      f'{list(_LEGACY_KEYWORD_CAP.values())[0]} 条关键词）')

# ---- 0.2 迁移不得从 allergy_history 派生任何写入 ----
# 最诱人的一步：insert into cdss_patient_allergy select ... where allergy_history like '%青霉素%'。
# 一条 SQL 就能把全院存量刷成结构化，看起来完成度极高，实际是把两类错误一次性、批量、
# 无签名地灌进临床拦截依据里。
_hits = []
for _p, _txt in _new_migrations():
    _flat = ' '.join(_code_lines(_txt)).lower()
    for _verb in ('insert into', 'update '):
        _i = 0
        while True:
            _i = _flat.find(_verb, _i)
            if _i < 0:
                break
            _end = _flat.find(';', _i)
            _stmt = _flat[_i:_end if _end > 0 else len(_flat)]
            if 'allergy_history' in _stmt:
                _hits.append(f'{_rel(_p)}: {_stmt[:160]}')
            _i += len(_verb)
assert not _hits, (
    'V152+ 迁移里出现了引用 allergy_history 的写语句：\n' + '\n'.join(_hits[:10])
    + '\n自由文本过敏史**只能被读来给人看**。原文归原文（empi_patient.allergy_history 一个字节不改），'
      '结构化归结构化，人工核对是唯一桥梁。')
print('[0.2] 边界 OK（**迁移零条从 allergy_history 派生的写入**：V152+ 全部 insert/update 零命中）')

# ---- 0.3 不得内置来源不明的药学知识 ----
# ①迁移种子：规则/字典表的每条 insert 都必须自陈是示例（这句话必须在**数据里**，
#   不能只在注释里——注释不会随 select * from 出现在药剂科的屏幕上）。
_bad = []
for _p, _txt in _new_migrations():
    _flat = ' '.join(_code_lines(_txt))
    _low = _flat.lower()
    _i = 0
    while True:
        _i = _low.find('insert into', _i)
        if _i < 0:
            break
        _end = _flat.find(';', _i)
        _stmt = _flat[_i:_end if _end > 0 else len(_flat)]
        _table = _re.sub(r'(?i)insert\s+into\s+', '', _stmt).split('(')[0].split()[0].lower()
        _i += len('insert into')
        if _table.startswith(('sys_', 'sec_', 'auth_')):     # gate 与阈值不是药学知识
            continue
        if not any(k in _stmt for k in ('示例', '占位')) and not any(
                k in _stmt.upper() for k in ('SAMPLE', 'EXAMPLE')):
            _bad.append(f'{_rel(_p)} → {_table}: {_stmt[:160]}')
assert not _bad, (
    'V152+ 迁移往规则/字典表里灌了**没有自陈是示例**的行：\n' + '\n'.join(_bad[:10])
    + '\n本版只做规则引擎，规则内容由药剂科按院内用药目录维护。'
      '种子最多给「空表 + 一条标注示例的行」。编出来的配伍禁忌会直接误导处方。')

# ②代码：不得硬编码药名配伍/交叉表。判据是「药名字面量 + 匹配或名单构造」，不是「出现过药名」。
_LEGACY_HARDCODED = {
    'modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java':
        'ALLERGY_CROSS：青霉素→西林/青霉素、头孢、磺胺、阿司匹林→水杨酸（v51 之前既有）',
    'modules/outpatient/src/main/java/cn/hip/outpatient/web/OutpNurseStationController.java':
        'SKIN_TEST_CATEGORIES：青霉素类→青霉素/西林、头孢类→头孢（v51 之前既有）',
}
_DRUGS = ('青霉素', '头孢', '磺胺', '阿司匹林', '华法林', '阿莫西林', '布洛芬', '左氧氟沙星', '甲硝唑',
          '地高辛', '二甲双胍', '胰岛素', '他汀', '克拉霉素', '红霉素', '呋塞米', '螺内酯', '卡马西平',
          '苯妥英', '利福平', '氨茶碱', '双硫仑', '藿香正气', '喹诺酮', '西林', '碘伏')
_MATCH_CTX = ('contains(', 'equals(', 'matches(', 'indexof(', 'startswith(', 'endswith(', ' like ',
              "like '", 'ilike', 'regexp', 'pattern.compile', 'list.of(', 'set.of(', 'map.of(',
              'arrays.aslist', ' in (', 'switch (')
_hits = []
for _p, _txt in _source_files(*JAVA_DIRS):
    if _rel(_p) in _LEGACY_HARDCODED:
        continue
    for _s in _code_lines(_txt):
        if not any(_d in _s for _d in _DRUGS):
            continue
        low = _s.lower()
        if any(c in low for c in _MATCH_CTX):
            _hits.append(f'{_rel(_p)}: {_s[:120]}')
assert not _hits, (
    '真代码里出现了「药名字面量 + 匹配/名单构造」的组合，说明有人把药学知识编进了代码：\n'
    + '\n'.join(_hits[:10])
    + '\n规则内容必须落在**可维护的数据结构**里由药剂科维护。硬编码的配伍表还有一个附带伤害：'
      '它不随院内用药目录更新，药剂科改不动、也看不见。')
print(f'[0.3] 边界 OK（**无来源不明的药学知识**：V152+ 种子全部自陈示例；'
      f'{len(_DRUGS)} 个药名字面量在新代码里零命中，既有 {len(_LEGACY_HARDCODED)} 处已登记封存）')

# ---- 0.4 不得按性别年龄猜妊娠 ----
# 「育龄女性即可能妊娠」两头都错：把全部 15–49 岁女性当孕妇（假阳性泛滥 → 提示疲劳 →
# 真警告一起被点掉），又漏掉区间外的真孕妇。妊娠状态**只能来自人工申报**。
_PREG_RX = _re.compile(r'妊娠|怀孕|孕妇|育龄|哺乳|pregnan|gestation|lactat', _re.I)
_DEMO_RX = _re.compile(r'getSex|\bsex\b|性别|"F"|\'F\'|女|getBirthDate|birth_date|birthDate'
                       r'|getYears|childbear|\bage\b|年龄', _re.I)
_hits = []
for _p, _txt in _source_files(*JAVA_DIRS):
    for _s in _code_lines(_txt):
        if _PREG_RX.search(_s) and _DEMO_RX.search(_s):
            _hits.append(f'{_rel(_p)}: {_s[:120]}')
assert not _hits, (
    '真代码里出现了「妊娠/哺乳 + 性别或年龄」的组合，疑似按人口学特征推定妊娠状态：\n'
    + '\n'.join(_hits[:10])
    + '\n妊娠状态只能来自人工申报（cdss_population_status，带来源分级与失效日）。'
      '查不到有效申报 = 未采集，与「已问过、不是」语义不同，不得互相顶替。')
print('[0.4] 边界 OK（**无按性别年龄推定妊娠**：妊娠/哺乳词与性别/年龄词的同行组合零命中）')

# ---- 0.5 新 gate 的**出厂值**必须是 warn ----
# 出厂值就是迁移里那条 insert 的字面量，静态判最准：跑起来之后读到的可能是谁改过的。
# 过敏这类高危拦截也默认 warn——此前从无此校验，直接 block 会让存量处方大面积失败，
# 医生第一反应是找人把 gate 整个关掉，于是连 warn 也一起没了。
GATE_KEYS = []
_bad = []
for _p, _txt in _new_migrations():
    for _m in _re.finditer(r"\(\s*'([a-z0-9_.]*\.gate\.[a-z0-9_.]+)'\s*,\s*'([^']*)'", _txt):
        GATE_KEYS.append(_m.group(1))
        if _m.group(2) != 'warn':
            _bad.append(f'{_rel(_p)}: {_m.group(1)} 出厂值 = {_m.group(2)!r}')
assert not _bad, ('新 gate 的出厂值不是 warn：\n' + '\n'.join(_bad)
                  + '\n新增拦截一律三态、默认 warn、坏配置回落 warn。')
GATE_KEYS = sorted(set(GATE_KEYS))
assert any('allergy' in k for k in GATE_KEYS), (
    f'V152+ 迁移里没有过敏 gate——过敏是本版最严重的缺口，它必须有自己的档位。现有：{GATE_KEYS}')
print(f'[0.5] 边界 OK（**新 gate 出厂全为 warn**：{GATE_KEYS}）')

# ---------------------------------------------------------------------------
# 以下需要后端运行于 localhost:8080
# ---------------------------------------------------------------------------
from e2elib import call, login, new_patient, ok, q, today_bj  # noqa: E402

t = login()

# 时间一律从业务时间线派生，**绝不写墙钟字面量**——本仓被这个炸过四次
today = today_bj()
STAMP = _dt.datetime.now(_dt.timezone(_dt.timedelta(hours=8))).strftime('%H%M%S')
_n = [0]


def uniq(p):
    _n[0] += 1
    return f'{p}{STAMP}{_n[0]}'


def d(days):
    return (today + _dt.timedelta(days=days)).isoformat()


def api(method, path, body=None, **kw):
    return call(method, path, body, t, **kw)


def blob(x):
    return _json.dumps(x, ensure_ascii=False)


def rows_of(payload):
    """各车道读端点的容器键不统一（list / items / rows / warnings），统一剥一层。"""
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for k in ('items', 'rows', 'list', 'warnings', 'hits', 'data'):
            if isinstance(payload.get(k), list):
                return payload[k]
    return []


def new_drug(name, stock=0):
    """建一味 E2E 专用药。CSV 导入是平台**唯一**的建药入口。
    列：code,name,spec,unit,dose_form,price,stock,antibiotic"""
    code = uniq('V51')
    r = api('POST', '/masterdata/drugs/import',
            text=f'{code},{name},0.25g*24粒/盒,盒,胶囊,10.00,{stock},0')
    assert r['code'] == 0 and r['data']['imported'] == 1, f'建药失败：{r}'
    hit = [x for x in ok(api('GET', f'/masterdata/drugs?keyword={q(name)}&all=true'), '查药')
           if x['code'] == code]
    assert hit, f'建了药却查不到：{code}'
    return hit[0]


def visited(patient_id):
    """排班 → 挂号 → 接诊，拿到一张能开单的挂号。"""
    depts = ok(api('GET', '/system/depts'), '科室')
    dept = next((x for x in depts if x['type'] != 'NURSING'), depts[0])
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': dept['id'], 'scheduleDate': today.isoformat(),
                  'fee': '0.00', 'capacity': 50}), '排班')
    reg = ok(api('POST', '/outpatient/registrations',
                 {'patientId': patient_id, 'scheduleId': sch['id']}), '挂号')
    ok(api('POST', f"/outpatient/doctor/{reg['id']}/start", {}), '接诊')
    return reg['id']


def order_drug(reg_id, drug_id, days=3):
    """开一行药。返回**整个响应体**——本套要看的不只是 code，还有返回体形状与 warnings。"""
    return api('POST', f'/outpatient/doctor/{reg_id}/orders',
               {'lines': [{'orderType': 'DRUG', 'itemId': drug_id, 'qty': 1, 'usageRoute': '口服',
                           'frequency': 'tid', 'dosePerTime': '1粒', 'days': days}]})


def order_drugs(reg_id, pairs):
    """一次开多行药（[(drug_id, days), ...]）。DDI 要同一张处方里有两味药才谈得上相互作用。"""
    return api('POST', f'/outpatient/doctor/{reg_id}/orders',
               {'lines': [{'orderType': 'DRUG', 'itemId': did, 'qty': 1, 'usageRoute': '口服',
                           'frequency': 'tid', 'dosePerTime': '1粒', 'days': d}
                          for did, d in pairs]})


def set_gate(key, value):
    """改 gate 档位。既有维护端点是 `PUT /api/config/{key}?value=`（v50 同款用法），
    不是 POST /system/configs——契约先读源码（SysConfigController）再写，不凭印象猜。"""
    return api('PUT', f'/config/{key}?value={q(value)}')


def mentions_warning(body):
    s = blob(body)
    return ('warning' in s.lower()) or ('警告' in s) or ('提示' in s)


# ===========================================================================
# 1) 既有 CDSS 契约防改坏：三类规则 / 4 个端点 / 码 4015-4017-4650 逐字不动
# ===========================================================================
rules = ok(api('GET', '/cdss/rules'), '规则库总览')
for k in ('ddi', 'dose', 'age', 'suggestions'):
    assert k in rules, f'GET /cdss/rules 缺既有分组 {k}（既有契约逐字不动）：{blob(rules)[:300]}'
ok(api('GET', '/cdss/alerts'), '提醒留痕')
ok(api('GET', '/cdss/suggestions?icd=J15'), '诊断建议')
bad = api('POST', '/cdss/ddi-rules',
          {'drugA': '甲', 'drugB': '乙', 'severity': 'MAYBE', 'message': '非法级别'})
assert bad['code'] == 4650, f'既有码 4650（级别只能为 FORBID/CAUTION）不许改：{bad}'

# 既有 DDI FORBID 仍拦（用本套自建规则跑，不依赖种子内容——种子是 v51 明令不再新增的那类药学知识）
_a = new_drug(uniq('DDI甲药'), stock=100)
_b = new_drug(uniq('DDI乙药'), stock=100)
ok(api('POST', '/cdss/ddi-rules',
       {'drugA': _a['name'], 'drugB': _b['name'], 'severity': 'FORBID',
        'message': 'E2E 自建的相互作用规则，不构成任何用药建议'}), '建 DDI 规则')
_p = new_patient(t, uniq('DDI回归'))
_rid = visited(_p['id'])
ok(order_drug(_rid, _a['id']), '开甲药')
_r = order_drug(_rid, _b['id'])
assert _r['code'] == 4015, f'既有码 4015（CDSS 相互作用拦截）不许改：{_r}'
print('[1] 既有 CDSS 契约 OK（4 端点 + 三类规则 + 码 4015/4650 逐字不动）')

# ===========================================================================
# 2) gate：档位真的可改（出厂 warn 已在 [0.5] 静态判过）
#
# 这里只验「档位是活的」：改得动、改完立即生效（ConfigReader 有 30 秒缓存，
# SysConfigController.update 会 evict——不 evict 的话本套后面的 off 档用例会随机失败，
# 且只在特定执行顺序下复现）。
# ===========================================================================
gate_keys = [k for k in GATE_KEYS]
for k in gate_keys:
    r = set_gate(k, 'warn')
    assert r.get('code') == 0, (
        f'gate {k} 改不动（{r}）。1401=配置项不存在说明迁移的 insert 没落库；'
        f'1402=校验拒绝说明 SysConfigController.validate 把这个键管住了。'
        f'改不动的 gate 等于没有 gate——实施期只能直连改库。')
print(f'[2] gate OK（{len(gate_keys)} 个 gate 可维护：{gate_keys}）')

# ===========================================================================
# 3) 过敏：结构化命中 + 交叉命中 + **接进开单链路**
#
# 表建好、端点建好、/check 能命中，都**不等于**医生开药时会被拦。
# 接不进开单链路的 CDSS 等于没有 CDSS——[3b] 是这一套里唯一直接对应临床安全的断言。
# ===========================================================================
allergy_ready = True
drug_x = new_drug(uniq('过敏命中药'), stock=100)
pat = new_patient(t, uniq('过敏结构化'))
rid = visited(pat['id'])

r = api('POST', '/cdss/allergy/allergens',
        {'code': uniq('AG'), 'name': uniq('E2E过敏原'), 'allergenType': 'DRUG',
         'drugLevel': 'INGREDIENT', 'remark': 'E2E 自建，非药学知识'})
if not gap(r.get('code') == 0, '过敏原字典无维护端点（POST /cdss/allergy/allergens）',
           '没有维护入口 → 映射表恒空 → 过敏审查恒不命中 → 所有过敏断言永远不会红。'
           '一条永远不会红的断言等于没有断言。'):
    allergy_ready = False
else:
    ag = r['data']
    ag_id = ag['id'] if isinstance(ag, dict) else ag
    r = api('POST', f'/cdss/allergy/allergens/{ag_id}/drugs',
            {'drugId': drug_x['id'], 'mappedLevel': 'INGREDIENT', 'note': 'E2E'})
    allergy_ready &= gap(r.get('code') == 0, '过敏原→药品映射无维护端点', f'{r}')
    r = api('POST', f"/cdss/allergy/patients/{pat['id']}/allergies",
            {'allergenId': ag_id, 'severity': 'SEVERE', 'manifestation': '皮疹',
             'source': 'TEST', 'note': 'E2E'})
    allergy_ready &= gap(r.get('code') == 0, '患者结构化过敏记录无登记端点', f'{r}')

if allergy_ready:
    # [3a] 审查端点自身必须命中
    chk = api('POST', '/cdss/allergy/check',
              {'patientId': pat['id'], 'registrationId': rid, 'drugIds': [drug_x['id']]})
    hit_txt = blob(chk)
    assert chk.get('code') == 0, f'warn 档 /check 应放行并回带提示：{chk}'
    assert mentions_warning(chk) or 'hit' in hit_txt.lower(), (
        f'【假阴性】患者已登记结构化过敏原、该过敏原已显式映射到送审药品，'
        f'POST /cdss/allergy/check 却一个字的提示都没有：{hit_txt[:400]}')
    print('[3a] 过敏审查端点 OK（结构化命中并回带提示）')

    # [3b] **真的走一遍开单**——CDSS 接不进开单链路就等于没有
    body = order_drug(rid, drug_x['id'])
    gap(body.get('code') != 0 or mentions_warning(body),
        '过敏审查**没有接进开单链路**',
        f"患者已登记结构化过敏原且映射到本次所开药品，走 POST /outpatient/doctor/{{rid}}/orders "
        f"开单却 code=0 且返回体无任何提示：{blob(body)[:400]}\n"
        f"接入点是 DoctorStationService 里 `cdssService.checkPrescription(...)` 那一行之后，"
        f"**只增不改**：既有 4012/4015/4017 三条路径逐字不动。\n"
        f"表建好、端点建好、/check 能命中，都不等于医生开药时会被拦——"
        f"**接不进开单链路的 CDSS 等于没有 CDSS**，这是本套唯一直接对应临床安全的断言。")

    # [3c] 交叉过敏族：必须命中，且**恒为警告、永不拦截**
    drug_y = new_drug(uniq('交叉过敏药'), stock=100)
    pat2 = new_patient(t, uniq('过敏交叉'))
    rid2 = visited(pat2['id'])
    ra = api('POST', '/cdss/allergy/allergens',
             {'code': uniq('AGA'), 'name': uniq('甲族过敏原'), 'allergenType': 'DRUG',
              'drugLevel': 'CLASS'})
    rb = api('POST', '/cdss/allergy/allergens',
             {'code': uniq('AGB'), 'name': uniq('乙族过敏原'), 'allergenType': 'DRUG',
              'drugLevel': 'INGREDIENT'})
    if ra.get('code') == 0 and rb.get('code') == 0:
        aid = ra['data']['id'] if isinstance(ra['data'], dict) else ra['data']
        bid = rb['data']['id'] if isinstance(rb['data'], dict) else rb['data']
        api('POST', f'/cdss/allergy/allergens/{bid}/drugs',
            {'drugId': drug_y['id'], 'mappedLevel': 'CLASS'})
        api('POST', f"/cdss/allergy/patients/{pat2['id']}/allergies",
            {'allergenId': aid, 'severity': 'MODERATE', 'source': 'CLINICAL'})
        ga = api('POST', '/cdss/allergy/groups', {'code': uniq('GA'), 'name': uniq('甲族')})
        gb = api('POST', '/cdss/allergy/groups', {'code': uniq('GB'), 'name': uniq('乙族')})
        if ga.get('code') == 0 and gb.get('code') == 0:
            gaid = ga['data']['id'] if isinstance(ga['data'], dict) else ga['data']
            gbid = gb['data']['id'] if isinstance(gb['data'], dict) else gb['data']
            api('POST', f'/cdss/allergy/groups/{gaid}/members', {'allergenId': aid})
            api('POST', f'/cdss/allergy/groups/{gbid}/members', {'allergenId': bid})
            rc = api('POST', '/cdss/allergy/cross',
                     {'groupIdA': gaid, 'groupIdB': gbid, 'riskLevel': 'HIGH', 'note': 'E2E'})
            if gap(rc.get('code') == 0, '交叉过敏族无维护端点', f'{rc}'):
                cross = api('POST', '/cdss/allergy/check',
                            {'patientId': pat2['id'], 'registrationId': rid2,
                             'drugIds': [drug_y['id']]})
                assert cross.get('code') == 0, (
                    f'交叉命中被当成拦截了（code={cross.get("code")}）。交叉族配宽一格就会拦掉大量'
                    f'临床合理处方，医生第一反应是关 gate，于是连直接命中的 warn 也一起没了。'
                    f'交叉必须**恒为警告、永不拦截**：{blob(cross)[:300]}')
                gap(mentions_warning(cross), '交叉过敏命中但不出声',
                    f'患者对甲族过敏原过敏、所开药映射到乙族过敏原、甲乙已登记交叉，/check 却无任何提示：'
                    f'{blob(cross)[:400]}\n这正是既有 DDI 那套 drugName.contains(drug_a) 子串匹配必然'
                    f'漏掉的形态——「青霉素」与「阿莫西林」一个字都不重合。'
                    f'交叉不拦截是对的，但**不出声就是漏拦**。')
                print('[3c] 交叉过敏 OK（命中且只警告不拦截）')
else:
    gap(False, '过敏结构化链路不可用，[3a]/[3b]/[3c] 全部跳过', '见上方缺口。')

# ===========================================================================
# 4) 人工核对工作台：既然不许自动解析，就必须给人工入口（只堵不疏 = 存量患者永久失守）
# ===========================================================================
wl = api('GET', '/cdss/allergy/migration/worklist?limit=5')
if gap(wl.get('code') == 0, '缺自由文本过敏史**人工核对工作台**待办端点',
       '禁止自动解析而不给人工入口，等于把存量患者的过敏史永久排除在审查之外。'):
    # 工作台必须只列原文让人看，**不得**替人给出结构化结论
    txt = blob(wl)
    assert 'allergyHistory' in txt or 'allergy_history' in txt or 'sourceText' in txt or \
           'source_text' in txt or wl['data'] in ([], {}, None) or not rows_of(wl['data']), (
        f'工作台待办里看不到原文——工作台的全部意义就是把原文摆给人看：{txt[:400]}')
    # 处置必须由人给出结构化过敏原：不传 allergens 而声称 STRUCTURED，必须被拒
    pat3 = new_patient(t, uniq('工作台'), allergyHistory='青霉素皮试阴性（我院）')
    bad = api('POST', f"/cdss/allergy/migration/patients/{pat3['id']}/review",
              {'sourceText': '青霉素皮试阴性（我院）', 'resolution': 'STRUCTURED',
               'allergens': [], 'note': 'E2E：声称已结构化却一条过敏原都不给'})
    assert bad.get('code') != 0, (
        '工作台接受了「resolution=STRUCTURED 但 allergens 为空」的处置——'
        '那等于系统自己替人认定「已核对完毕」，而实际上一条结构化过敏原都没产生。'
        f'这个患者会从待办里消失、且永远不会被任何过敏规则命中：{bad}')
    print('[4] 人工核对工作台 OK（原文可见；空结论的 STRUCTURED 被拒）')

# ===========================================================================
# 5) warn 必须真的出声；off 必须与 v50 逐字同形
# ===========================================================================
# 用**既有**的疗程上限（CAUTION）跑：CdssService 命中 CAUTION 时只往 cdss_alert 写一行，
# 返回体里一个字都没有——医生开完单什么也看不见。那不叫 warn，那叫记了个账。
dose_drug = new_drug(uniq('疗程上限药'), stock=100)
pat4 = new_patient(t, uniq('疗程提醒'))
rid4 = visited(pat4['id'])
alerts_before = len(ok(api('GET', '/cdss/alerts'), '留痕(前)'))

# 先造一条 **CAUTION 档 DDI 规则**再开单——否则没有任何规则命中，
# 「返回体里没有提示」就成了必然，那不是在验 warnings 通道，是在验空集。
# 疗程规则（cdss_dose_rule）没有维护端点，只有 DDI 有（POST /cdss/ddi-rules），
# 两者共用 CdssService.checkPrescription 的同一条 warnings 通道，判据等价。
_ddi_partner = new_drug(uniq('DDI搭档药'), stock=100)
ok(api('POST', '/cdss/ddi-rules', {
    'drugA': dose_drug['name'], 'drugB': _ddi_partner['name'],
    'severity': 'CAUTION', 'message': 'E2E 自建的 CAUTION 规则（不依赖种子内容）'}), '建 CAUTION DDI 规则')
# 同一张处方里同时开这两味药，必然命中 CAUTION
body = order_drugs(rid4, [(dose_drug['id'], 10), (_ddi_partner['id'], 3)])
assert body.get('code') == 0, f'CAUTION 档必须放行：{body}'
gap(mentions_warning(body), 'warn/CAUTION 档命中后返回体里没有任何提示（静默）',
    f'主控口径原文：「warn 档必须**真的把提示给到医生**（返回体带 warnings），不能静默」。'
    f'躺在一张要另外去查的表里的提示等于没有提示。实际返回体：{blob(body)[:400]}\n'
    f'本条同时是本版三个新 gate（allergy/duplicate/population）warn 档的验收口径。')

# off 档：返回体必须与 v50 逐字同形（data 仍是**订单数组**，不许包一层 {orders,warnings}）
# 只动 cdss.gate.*：pharm.gate.* 是药房侧的档位，与开单返回体形状无关，顺手关掉会牵连别的用例。
cdss_gates = [k for k in gate_keys if k.startswith('cdss.gate.')]
for k in cdss_gates:
    set_gate(k, 'off')
try:
    off_drug = new_drug(uniq('off档形状药'), stock=100)
    pat5 = new_patient(t, uniq('off档'))
    rid5 = visited(pat5['id'])
    off = order_drug(rid5, off_drug['id'])
    assert off.get('code') == 0, f'off 档必须旁路全部新校验：{off}'
    assert isinstance(off.get('data'), list), (
        f'off 档下 data 不再是**订单数组**（v50 逐字口径：R.ok(List<OutpOrder>)），而是 '
        f'{type(off.get("data")).__name__}。接进开单校验的纪律是「只增不改」：新增 warnings 只能作为'
        f'**新增字段**随行下发，不能把既有 data 包一层。off 档是那个「本该毫无变化」的档位，'
        f'它一变形，既有前端与 15 套 e2e 会在最不该出问题的地方一起挂：{blob(off)[:300]}')
    for k in ('id', 'registrationId', 'groupNo', 'orderType', 'itemId', 'itemName',
              'qty', 'unitPrice', 'amount', 'status'):
        assert k in off['data'][0], f'off 档订单元素缺既有字段 {k}：{blob(off["data"][0])[:300]}'
    print('[5] gate 形状 OK（off 档 data 仍是订单数组、既有字段齐全）')
finally:
    for k in cdss_gates:
        set_gate(k, 'warn')      # 复位，别把档位留给下一套 e2e

# ===========================================================================
# 6) 台账：三档都记、命中与否都记（只记拦下来的那部分，命中率的分母就是假的）
# ===========================================================================
gl = api('GET', '/cdss/allergy/gate-log?limit=20')
gap(gl.get('code') == 0, '缺过敏审查台账端点 GET /cdss/allergy/gate-log',
    'warn 档放行不等于没发生过。不落台账，院方永远拿不到「一个月里多少次过敏命中被放行」，'
    '也就永远没有依据把 gate 收紧到 block（v48 双签、v50 发药核对同一课）。')

# ===========================================================================
# 7) 盘点批次层：差异必须被**报出来**，不许被静默抹平（v50 署名欠账）
# ===========================================================================
tk_drug = new_drug(uniq('盘点批次药'), stock=0)
did = tk_drug['id']
ok(api('POST', '/pharm/stock/stock-in',
       {'drugId': did, 'qty': 100, 'batchNo': uniq('B'), 'expireOn': d(400),
        'supplier': 'E2E供应商'}), '批次入库 100')


def agg_stock(drug_id):
    for x in ok(api('GET', '/masterdata/drugs?keyword=&all=true'), '查药'):
        if x['id'] == drug_id:
            return x['stock']
    return None


def batch_stock(drug_id):
    bb = ok(api('GET', f'/pharm/stock/balance/by-batch?drugId={drug_id}'), '按批次余额')
    return sum(int(r.get('qty') or r.get('onHand') or 0) for r in rows_of(bb))


assert agg_stock(did) == 100 and batch_stock(did) == 100, '入库后两本账都应为 100'

take = ok(api('POST', '/inventory/stock-take',
              {'drugIds': [did], 'remark': 'v51 批次层收敛验证'}), '建盘点单')
ok(api('POST', f"/inventory/stock-take/{take['id']}/counts",
       {'entries': [{'drugId': did, 'actualQty': 95}]}), '录实盘数 95')
confirm = api('POST', f"/inventory/stock-take/{take['id']}/confirm")
detail = api('GET', f"/inventory/stock-take/{take['id']}")

agg, bat = agg_stock(did), batch_stock(did)
said = blob(confirm) + blob(detail)
reported = any(w in said for w in ('batch', 'Batch', '批次', 'drift', '漂移'))
converged = (agg == bat)
refused = confirm.get('code') != 0

gap(converged or refused or reported,
    '盘点**静默抹平**了批次层差异',
    f'入库 100 → 实盘 95 → 确认盘点后：总账 md_drug.stock={agg}（已被拉到实存）、'
    f'批次子账合计={bat}（原地不动），盘点确认返回体与盘点单详情里都没提 batch/批次/drift/漂移。\n'
    f'于是盘点单写着「已确认、净盈亏 -5」，而批次账现在错了 {bat - agg} 盒，**没有任何地方说这件事**。\n'
    f'这正是 v50 车道 E 署名登记的欠账：「看起来一直对得上、实际每次都靠盘点擦屁股」——'
    f'下一次召回时会照着一本错了 {bat - agg} 盒的批次账去下架真药。\n'
    f'三条合格出路任选其一：① 盘到批次层（调余额 + 写带盘点单号的 ADJ 流水）；'
    f'② 有漂移时拒绝确认（同 8008 纪律）；③ 确认时把差异随返回体报出来。'
    f'唯一不合格的是三样都没有。')
if converged or refused or reported:
    print(f'[7] 盘点批次层 OK（总账 {agg} / 批次子账 {bat}；'
          f'{"已收敛" if converged else ("拒绝确认" if refused else "差异已报出")}）')

# 对账端点必须仍然自洽（v50 既有硬不变量：pharm_stock.qty == Σ flow.qty_delta）
rec = ok(api('GET', f'/pharm/stock/reconcile?drugId={did}'), '批次对账')
assert rec.get('mismatched') == 0 and rec.get('balanced') is True, (
    f'批次子账内部不自洽了——「对任意 (batch_id, location_code)：pharm_stock.qty == '
    f'sum(pharm_stock_flow.qty_delta)」是 V147 写死的硬不变量。'
    f'若盘点收敛到了批次层却漏写流水，第一个红的就是这里：{rec}')

# ===========================================================================
# 汇总
# ===========================================================================
print()
if GAPS:
    print(f'===== V51 未交付 / 不可达：{len(GAPS)} 项 =====')
    for i, (title, detail) in enumerate(GAPS, 1):
        print(f'{i}. {title}')
        for line in detail.splitlines():
            print(f'     {line}')
    _sys.exit(1)
print('===== v51 CDSS E2E 全绿 =====')
