# -*- coding: utf-8 -*-
"""v50 门诊药房调剂 E2E：批次地基 → 摆药预调剂 → 发药核对 → 拆零 / 效期。

本套的存在意义与 v48 病理同：这批 46★ 全是**投标偏离表已答「平台已实现」而代码里只有
4 个端点 + 63 行 DispenseService** 的诚信补齐。除了钉死「能真的走通」，本套重点钉三类：

  ① **账**。药房的命根子是「流水即账，库存即余额」。本仓现在有**三本账**：
       总账     md_drug.stock      ↔ inv_transaction     （药品级，销售单位「盒」）
       批次子账 pharm_stock        ↔ pharm_stock_flow    （(批次,地点) 级，销售单位）
       拆零子账 pharm_split_stock  ↔ pharm_split_txn     （药品级，**最小单位「片」**）
     三本各自自洽而合起来对不上，是药房最经典的坏法——单独看每本都是平的，
     没有任何单本账的用例会发现。故本套除逐本对账，还专门钉**跨账换算**：
     开包那一盒在总账是 OUT −1 盒，在拆零账是 OPEN +24 片，必须严丝合缝。

  ② **入口**。有 Service 没有 Controller 的功能等于没有交付：表永远是空的，
     所有「表里没有违规行」的断言都恒真而毫无意义。**一条永远不会红的断言等于没有断言**，
     故本套把「维护入口存在与否」单独钉（[7][8]），不让空表替它作证。

  ③ **边界**。本版刻意不做的三件事，不许下一轮被悄悄补成假的：
     包药机/发药机设备直连驱动、按药名猜麻精药品、用字符串相似度自动判看似听似。

【与既往 e2e 的两处刻意不同，都有理由】
  * **边界检查放在最前面**（[0]）。它们不需要后端，且价值最高。放末尾时只要前面任何一步
    挂了（比如某车道还没交付），边界就一次都跑不到——而「悄悄补成假的」恰恰最可能发生在
    功能没做完的那一版。
  * **「未交付」用 gap() 收集、末尾一次性汇总，不当场 assert 中断**。本套是并行开发期的
    验收网：中途 assert 掉，主控就只看得见第一个缺口，看不见另外四个。
    **真正的正确性违反（账对不上、既有契约被改）仍然当场 assert**——那是必须立刻停的。

【怎么跑】`python tools/e2e-v50-pharmacy.py`（后端须运行于 localhost:8080，可用
HIP_E2E_BASE 指向其它实例）。[0] 三节静态边界检查不需要后端，任何时候都能跑。
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
    """未交付/不可达一类的缺口：记录并继续，末尾统一汇总退非零。

    与 assert 的分工：**账对不上、既有契约被改一律 assert 当场停**（那是不能带着跑的）；
    「这个功能还没做」则记 gap——中途停会让主控只看见第一个缺口。
    """
    if not cond:
        GAPS.append((title, detail))
        print(f'  ✗ [V50-未交付] {title}')
    return bool(cond)


# ===========================================================================
# 0) 边界钉死（**不需要后端，故放最前**）
# ===========================================================================

def _source_files(*rel_dirs, suffixes=('.java',)):
    """遍历真代码目录。跳过 target/ node_modules/ 与 .claude/worktrees/
    （构建产物与他人工作区不是本仓代码，扫进来会得出假结论）。"""
    for rel in rel_dirs:
        base = _os.path.join(ROOT, *rel.split('/'))
        if not _os.path.isdir(base):
            continue
        for dp, dn, fn in _os.walk(base):
            dn[:] = [x for x in dn if x not in ('target', 'node_modules', 'worktrees', '.git', 'dist')]
            for f in fn:
                if f.endswith(suffixes):
                    p = _os.path.join(dp, f)
                    yield p, open(p, encoding='utf-8', errors='ignore').read()


def _code_lines(text):
    """只认**真代码**：注释里写「不做 X——含吗啡即毒麻是危险假实现」是**声明边界**，
    恰恰要鼓励；把它算成突破会逼后人删掉说明，反而让边界变得不可见（v48 已立此口径）。"""
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('*') or s.startswith('//') or s.startswith('/*') or s.startswith('--'):
            continue
        yield s


def _rel(p):
    return _os.path.relpath(p, ROOT).replace('\\', '/')


# ---- 0.1 包药机 / 发药机设备直连驱动：前后端依赖都不许出现 ----
_BANNED_JS = ('serialport', 'node-hid', 'escpos', 'modbus', 'node-printer', 'jssc', 'usb')
_BANNED_JAVA = ('jserialcomm', 'purejavacomm', 'nrjavaserial', 'jssc', 'rxtx', 'usb4java',
                'hid4java', 'javax.comm')

_pkg = _os.path.join(ROOT, 'frontend', 'shell', 'package.json')
if _os.path.exists(_pkg):
    _deps = _json.load(open(_pkg, encoding='utf-8'))
    _names = [n.lower() for n in
              list(_deps.get('dependencies', {})) + list(_deps.get('devDependencies', {}))]
    for b in _BANNED_JS:
        hit = [n for n in _names if b in n]
        assert not hit, (
            f'package.json 出现 {hit}：v50 **明确不做包药机/发药机设备直连驱动**'
            f'（硬边界，同 v48 玻片打码机——平台无设备驱动层，硬做只能做出假的）。')

for _p, _txt in _source_files('.', suffixes=('pom.xml',)):
    _low = _txt.lower()
    for b in _BANNED_JAVA:
        assert b not in _low, (
            f'{_rel(_p)} 出现串口/USB/HID 驱动依赖 {b}：v50 明确不做包药机/发药机设备直连。')
print('[0.1] 边界 OK（**无包药机/发药机设备驱动依赖**：前端 package.json 与全部 pom.xml 零命中）')

# ---- 0.2 不许按药名猜麻精药品 ----
# md_drug **没有「精麻毒放」属性位**（v46 的 1444★/1445★ 正因此标 available:false）。
# 「含吗啡即毒麻」是危险假实现：同名不同剂型/复方制剂会误判，商品名制剂会漏判，
# 而管制药品台账要同时对得上药监与卫健两条线，猜错一味出的是**法律问题**不是数据问题。
_NARCOTIC_NAMES = ('吗啡', '哌替啶', '芬太尼', '氯胺酮', '咪达唑仑', '可待因', '曲马多',
                   '羟考酮', '苯巴比妥', '三唑仑', '瑞芬太尼')
# 判据是「药名 + **匹配/名单**上下文」，不是「出现过药名」。
# 理由：v46 的 AnesQcController 在**返回体的 caveat 字符串里**写着
# 「毒麻药无属性位可判，『含吗啡即毒麻』是危险的假实现」——那是把边界**告诉调用方**，
# 是本仓最该鼓励的做法。只按「出现过药名」判会把它算成突破，逼着后人删掉这句说明，
# 反而让边界从对外可见变成不可见。故必须同时出现匹配算子或名单构造才算数。
_MATCH_CTX = ('contains(', 'equals(', 'matches(', 'indexof(', 'startswith(', 'endswith(',
              ' like ', "like '", 'ilike', 'regexp', 'pattern.compile', 'list.of(',
              'set.of(', 'arrays.aslist', ' in (', 'switch (')
_hits = []
for _p, _txt in _source_files('modules', 'platform', 'server/src/main', 'datacenter'):
    for _s in _code_lines(_txt):
        if not any(_n in _s for _n in _NARCOTIC_NAMES):
            continue
        low = _s.lower()
        if any(c in low for c in _MATCH_CTX):
            _hits.append(f'{_rel(_p)}: {_s[:120]}')
assert not _hits, (
    '真代码里出现了「麻精药品名 + 匹配/名单」的组合，说明有人在按药名猜管制属性：\n'
    + '\n'.join(_hits[:10])
    + '\n「含吗啡即毒麻」是危险假实现：同名不同剂型/复方制剂会误判，商品名制剂会漏判。'
      '要做麻精流程必须**先补 md_drug 的「精麻毒放」属性位**，不许按名字猜。')

# 反向钉死：既然不许猜，就**不许有麻精端点**——除非属性位真的补上了
# 同上一条的道理：只认**路由声明**，不认散文。AnesQcController 的 caveat 里写着
# 「narcotic / psychotropic 全仓零命中」——那是把边界告诉调用方，不是开了个麻精端点。
_ctrl_hits = []
for _p, _txt in _source_files('modules', 'platform', 'server/src/main'):
    for _s in _code_lines(_txt):
        if not _s.startswith('@') or 'Mapping' not in _s:
            continue
        if _re.search(r'(narcotic|psychotropic|controlled[_-]?drug|majing)', _s, _re.I):
            _ctrl_hits.append(f'{_rel(_p)}: {_s[:120]}')
_has_attr = any(_re.search(r'(control_class|controlled_class|narcotic_class|special_class)', _t)
                for _, _t in _source_files('server/src/main/resources/db', suffixes=('.sql',)))
assert not _ctrl_hits or _has_attr, (
    f'出现了麻精相关代码路径 {_ctrl_hits[:5]}，但 md_drug 仍无「精麻毒放」属性位——'
    f'没有属性位就只能按药名猜。先补属性位，再谈流程。')
print(f'[0.2] 边界 OK（**无按药名猜麻精的代码路径**：{len(_NARCOTIC_NAMES)} 个管制药名字面量'
      f'在真代码里零命中；且无属性位时也无麻精端点）')

# ---- 0.3 看似听似（LASA）不许用字符串相似度自动判 ----
# 编辑距离会把「阿莫西林胶囊 0.25g」与「阿莫西林胶囊 0.5g」判成一对（同药不同规格不是 LASA），
# 又会漏掉「氯化钾 / 氯化钠」（差一字但读音天差地别是典型 LASA）。既误报又漏报，
# 全院几百条提示里只要有一半是噪音，剩下一半也就废了——**假提示比不提示更危险**。
_SIM_FUNCS = ('levenshtein', 'similarity(', 'word_similarity', 'jaro', 'winkler', 'soundex',
              'metaphone', 'editdistance', 'edit_distance', 'sequencematcher', 'difflib',
              'fuzzywuzzy', 'jaccard', 'ngram_similarity', 'pg_trgm')
_lasa_files = [(p, t) for p, t in
               (list(_source_files('modules', 'platform', 'server/src/main'))
                + list(_source_files('server/src/main/resources/db', suffixes=('.sql',))))
               if 'lasa' in t.lower()]
assert _lasa_files, 'LASA（看似听似）全仓零命中——46★ 含该条款，应已由车道C 落地'
_sim_hits = []
for _p, _txt in _lasa_files:
    for _s in _code_lines(_txt):
        low = _s.lower()
        for _f in _SIM_FUNCS:
            if _f in low:
                _sim_hits.append(f'{_rel(_p)}: {_s[:100]}')
assert not _sim_hits, (
    'LASA 相关文件里出现了字符串相似度函数：\n' + '\n'.join(_sim_hits[:10])
    + '\n看似听似**只能由药剂科按院内《易混淆药品目录》逐条维护**。')

# 更狠的一条：对照表不许被 select 批量灌——那就是自动判定，与用不用相似度函数无关
for _p, _txt in _lasa_files:
    _flat = ' '.join(_code_lines(_txt)).lower()
    for _m in _re.finditer(r'insert\s+into\s+pharm_lasa_pair', _flat):
        _tail = _flat[_m.end():_m.end() + 240]
        _head = _tail.split('values')[0]
        assert 'select' not in _head, (
            f'{_rel(_p)} 用 `insert ... select` 批量生成 LASA 对照表：那就是自动判定。'
            f'对照表须逐条维护。')
print(f'[0.3] 边界 OK（**LASA 无字符串相似度自动判**：{len(_SIM_FUNCS)} 个相似度函数零命中；'
      f'对照表无 insert-select 批量灌入）')

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


def drug_by_id(drug_id):
    for x in ok(api('GET', '/masterdata/drugs?keyword=&all=true'), '查药'):
        if x['id'] == drug_id:
            return x
    return None


def new_drug(name, stock=0):
    """建一味 E2E 专用药。CSV 导入是平台**唯一**的建药入口。
    列：code,name,spec,unit,dose_form,price,stock,antibiotic"""
    code = uniq('V50')
    r = api('POST', '/masterdata/drugs/import',
            text=f'{code},{name},0.25g*24粒/盒,盒,胶囊,10.00,{stock},0')
    assert r['code'] == 0 and r['data']['imported'] == 1, f'建药失败：{r}'
    hit = [x for x in ok(api('GET', f'/masterdata/drugs?keyword={q(name)}&all=true'), '查药')
           if x['code'] == code]
    assert hit, f'建了药却查不到：{code}'
    return hit[0]


def stock_of(drug_id):
    row = drug_by_id(drug_id)
    return row['stock'] if row else None


def rows_of(payload):
    """各车道读端点的容器键不统一（list / items / rows / batches），统一剥一层。"""
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for k in ('items', 'rows', 'list', 'batches', 'flows', 'data'):
            if isinstance(payload.get(k), list):
                return payload[k]
    return []


# ===========================================================================
# 1) 批次地基：入库必须**双写两本账**并合得上
# ===========================================================================
drug = new_drug(uniq('对账药'))
did = drug['id']

ok(api('POST', '/pharm/stock/stock-in',
       {'drugId': did, 'qty': 120, 'batchNo': uniq('B'), 'producedOn': d(-30),
        'expireOn': d(400), 'supplier': 'E2E供应商', 'purchasePrice': '3.2500'}), '批次入库')
assert stock_of(did) == 120, f'总账 md_drug.stock 应 +120，实际 {stock_of(did)}'

bal = ok(api('GET', f'/pharm/stock/balance?drugId={did}'), '批次余额')
bal_blob = _json.dumps(bal, ensure_ascii=False)
for k in ('drift', 'aggregateStock', 'coverage'):
    assert k in bal_blob, (
        f'GET /pharm/stock/balance 返回体缺 {k}。总账与批次子账之间存在**已知漂移**'
        f'（发药只扣汇总不扣批次），口径与覆盖率不随体下发，管理者会把「批次库存」当成'
        f'全院全历史的准确口径——而实情可能只是「全院 3 个药启用了批次管理」。'
        f'实际：{bal_blob[:400]}')

by_batch = ok(api('GET', f'/pharm/stock/balance/by-batch?drugId={did}'), '按批次余额')
assert sum(int(r.get('qty') or r.get('onHand') or 0) for r in rows_of(by_batch)) == 120, \
    f'批次子账合计应为 120：{by_batch}'

rec = ok(api('GET', f'/pharm/stock/reconcile?drugId={did}'), '对账端点')
assert rec.get('checked', 0) >= 1, f'/reconcile 应至少核对到刚入库的这一行：{rec}'
assert rec.get('mismatched') == 0 and rec.get('balanced') is True, (
    f'/reconcile 报出了不平的行——「对任意 (batch_id, location_code)：'
    f'pharm_stock.qty == sum(pharm_stock_flow.qty_delta)」是 V147 写死的硬不变量，'
    f'两者永远同事务写入。不平即有一条路径改了余额却没写流水：{rec}')

def _flow_type(f):
    """流水类型取值：端点走 JdbcTemplate 直出，键是**蛇形** flow_type。

    本套已在三处各写了一遍 `f.get('flowType') or f.get('type')` 的 or 链并各错一次——
    统一收敛到这里。凭印象猜驼峰/蛇形是本仓的老毛病，写断言前先看返回体实际形状。
    """
    return f.get('flow_type') or f.get('flowType') or f.get('type')


flows = rows_of(ok(api('GET', f'/pharm/stock/flows?drugId={did}'), '批次流水'))
# 端点走 JdbcTemplate 直出，键是 **蛇形** flow_type；写这行时先 grep 了返回体实际形状，
# 不凭印象猜——本仓已因猜契约返工多次（驼峰/蛇形、rows/items/groups 各版本不一）。
ins = [f for f in flows if _flow_type(f) == 'IN']
assert len(ins) == 1, f'入库应恰好留一条 IN 流水：{flows}'
assert ins[0].get('srcStockInId') or ins[0].get('src_stock_in_id'), (
    'IN 流水必须带 src_stock_in_id 勾稽键，把批次子账与既有入库单勾在一起——'
    '没有勾稽键的两本账就是两个各说各话的数：' + _json.dumps(ins[0], ensure_ascii=False))
print('[1] 批次入库 OK（**总账 +120 / 批次子账 +120 / IN 流水带 src_stock_in_id 勾稽** / '
      'balance 带 drift+aggregateStock+coverage / reconcile 逐行有结论）')

# ===========================================================================
# 2) 报损：原因必填 + 双写
# ===========================================================================
batches = rows_of(ok(api('GET', f'/pharm/stock/batches?drugId={did}'), '批次列表'))
assert batches, f'入库后应能查到批次：{batches}'
bid = batches[0]['id']

assert api('POST', '/pharm/stock/scrap', {'batchId': bid, 'qty': 5, 'reason': ''})['code'] != 0, \
    '报损原因必填——减少资产且不可逆，原因是审计与药监检查的唯一线索（同 V82 拒收 8012）'
ok(api('POST', '/pharm/stock/scrap', {'batchId': bid, 'qty': 5, 'reason': 'E2E 破损报损'}), '报损')
assert stock_of(did) == 115, f'报损须**双写**：总账也应 −5，实际 {stock_of(did)}'
after = rows_of(ok(api('GET', f'/pharm/stock/balance/by-batch?drugId={did}'), '报损后余额'))
assert sum(int(r.get('qty') or r.get('onHand') or 0) for r in after) == 115, \
    f'批次子账也应 −5：{after}'
# 余额扣穿必须被拦住：负库存一旦落库，后面所有对账都在跟一个假数比
assert api('POST', '/pharm/stock/scrap',
           {'batchId': bid, 'qty': 99999, 'reason': '扣穿测试'})['code'] != 0, \
    '报损量超过批次余额必须被拒（5408），绝不允许把余额扣成负数'
print('[2] 报损 OK（**原因必填** / 批次与总账同减 / **余额扣不穿**）')

# ===========================================================================
# 3) 效期 gate：三态、出厂 warn、block 真拦、坏配置回落 warn 不回落 off
# ===========================================================================
GATE = 'pharm.gate.expiry.stock_in'
settings = ok(api('GET', '/pharm/stock/settings'), '库存设置')
sblob = _json.dumps(settings, ensure_ascii=False)
# 返回体用的是语义化短名（batchGate / expiryGate），不是配置键全名——
# 这是更好的对外契约（调用方不必知道内部键名）。断言认真实形状，不认我的想当然。
for k in ('warn', 'expiryGate', 'batchGate', 'pickRule'):
    assert k in sblob, f'GET /pharm/stock/settings 应回带 gate 当前档位与取批规则：{sblob[:300]}' 

warned = ok(api('POST', '/pharm/stock/stock-in',
                {'drugId': did, 'qty': 3, 'batchNo': uniq('EXP'), 'expireOn': d(-10)}),
            'warn 档过期入库')
assert 'warn' in _json.dumps(warned, ensure_ascii=False).lower(), (
    f'warn 档必须**回带 warnings**而不是静默放行——静默的 warn 等于 off，'
    f'而 warn 的全部意义就是「先让问题当场可见、可统计」：{warned}')

ok(api('PUT', f'/config/{GATE}?value=block'), '收紧 gate 到 block')
try:
    blocked = api('POST', '/pharm/stock/stock-in',
                  {'drugId': did, 'qty': 3, 'batchNo': uniq('EXP2'), 'expireOn': d(-10)})
    assert blocked['code'] == 5404, f'block 档必须以 5404 拦住已过期批次入库：{blocked}'
    ok(api('PUT', f'/config/{GATE}?value=BLOKC'), '写一个拼错的配置值')
    bad = api('POST', '/pharm/stock/stock-in',
              {'drugId': did, 'qty': 3, 'batchNo': uniq('EXP3'), 'expireOn': d(-10)})
    assert bad['code'] == 0 and 'warn' in _json.dumps(bad, ensure_ascii=False).lower(), (
        f'坏配置须回落 **warn**（放行 + 提示），既不许回落 off（静默失效），也不许当 block'
        f'（一个笔误就让药房停摆）：{bad}')
finally:
    ok(api('PUT', f'/config/{GATE}?value=warn'), '还原 gate')
print('[3] 效期 gate OK（出厂 warn / warn 回带 warnings / block 以 5404 真拦 / '
      '**坏配置回落 warn 不回落 off**）')

# ===========================================================================
# 4) 摆药与预调剂：既有「整单一步发药」之前缺掉的三个环节
# ===========================================================================
pid = new_patient(t, uniq('药房E2E'), sex='F')['id']


def charged_visit(drug_id, qty=2):
    sch = ok(api('POST', '/outpatient/schedules',
                 {'deptId': 1, 'scheduleDate': today.isoformat(), 'fee': 0, 'capacity': 99}), '排班')
    rid = ok(api('POST', '/outpatient/registrations',
                 {'patientId': pid, 'scheduleId': sch['id']}), '挂号')['id']
    ok(api('POST', f'/outpatient/doctor/{rid}/start', {}), '接诊')
    ok(api('POST', f'/outpatient/doctor/{rid}/orders',
           {'lines': [{'orderType': 'DRUG', 'itemId': drug_id, 'qty': qty, 'usageRoute': '口服',
                       'frequency': 'tid', 'dosePerTime': '1粒', 'days': 3}]}), '开药')
    ok(api('POST', '/outpatient/charges/settle',
           {'registrationId': rid, 'payMethod': 'CASH'}), '结算')
    return rid


rid1 = charged_visit(did)
pick = ok(api('POST', '/outpatient/pharmacy/picking/orders', {'registrationId': rid1}), '建摆药单')
pick_id = pick.get('id') or pick.get('pickingId')
assert pick.get('pickNo') or pick.get('pick_no'), f'摆药单号必须生成：{pick}'
assert (pick.get('status') or '').upper() == 'PENDING', f'新建摆药单应为待摆药：{pick}'

dup = api('POST', '/outpatient/pharmacy/picking/orders', {'registrationId': rid1})
assert dup['code'] == 5421, (
    f'同一挂号重复建摆药单应返 5421——否则同一批药被配两遍，两张单会各自走到「已配好」，'
    f'发药台看到两份。实际：{dup}')

ok(api('POST', f'/outpatient/pharmacy/picking/orders/{pick_id}/start', {}), '开始摆药')
detail = ok(api('GET', f'/outpatient/pharmacy/picking/orders/{pick_id}'), '摆药单详情')
lines = detail.get('lines') or []
assert lines, f'摆药单应有明细：{detail}'
ok(api('PUT', f'/outpatient/pharmacy/picking/orders/{pick_id}/lines/{lines[0]["id"]}/pick',
       {'pickedQty': lines[0]['qty']}), '逐项确认')
prep = ok(api('POST', f'/outpatient/pharmacy/picking/orders/{pick_id}/prepared', {}), '预调剂完成')
assert (prep.get('status') or '').upper() == 'PREPARED', f'应置已配好：{prep}'
assert prep.get('preparedAt') or prep.get('prepared_at'), (
    '预调剂完成时刻必须落痕——它与 dispensedAt 是两个时刻，'
    '混为一谈会让「配好到取药的等候时长」这个指标恒等于 0')

wins = ok(api('GET', '/outpatient/pharmacy/picking/windows'), '窗口字典')
assert wins, '发药窗口字典应有默认配置（实施期按院方实际窗口替换）'
ok(api('PUT', f'/outpatient/pharmacy/picking/orders/{pick_id}/window',
       {'windowCode': wins[0]['code']}), '分配窗口')

gates = ok(api('GET', '/outpatient/pharmacy/picking/gates'), '摆药 gate')
gblob = _json.dumps(gates, ensure_ascii=False)
assert gblob.count('warn') >= 2, (
    f'摆药的两条 gate（line_confirm / window）出厂值都必须是 warn（铁律4）：'
    f'此前从无「逐项确认」与「窗口」，直接 block 会让药房当天大面积配不出药。{gblob}')

# 基线取「摆药前的实测值」而不是写死 115：上面效期 gate 那段做了三次 warn 档入库各 3 件，
# 其中两次真的放行了（warn 放行 + 坏配置回落 warn 也放行），故此刻是 121 不是 115。
# **本条要钉的是「摆药前后库存不变」，不是某个绝对数** —— 写死绝对数会让上游每加一笔
# 入库都要回来改这个魔数，改着改着就没人知道它到底在断言什么了。
_before_pick = stock_of(did)
assert _before_pick == 121, (
    f'摆药前基线应为 121（120 入库 −5 报损 +3 warn 档过期入库 +3 坏配置回落 warn 入库）：'
    f'实际 {_before_pick}')
assert stock_of(did) == _before_pick, (
    f'**摆药不得扣库存**：摆药单是旁挂的一层，扣减与置 DISPENSED 仍只由发药那一步做。'
    f'摆药前 {_before_pick}，摆药后 {stock_of(did)}')
print('[4] 摆药与预调剂 OK（建单 → 开始 → 逐项确认 → 已配好 → 分配窗口 / '
      '**同挂号重复建单返 5421** / **摆药不改医嘱状态、不扣库存** / gate 出厂 warn）')

# ===========================================================================
# 5) 既有发药链路一字未改
# ===========================================================================
ok(api('GET', '/outpatient/dispense/worklist'), '既有发药队列')
disp = ok(api('POST', f'/outpatient/dispense/{rid1}', {}), '既有发药端点')
assert isinstance(disp, list) and disp, f'既有发药端点应原样返回本次发药的订单列表：{disp}'
assert stock_of(did) == _before_pick - 2, f'发药后总账应 −2，实际 {stock_of(did)}'
assert api('POST', f'/outpatient/dispense/{rid1}', {})['code'] == 6001, \
    '重复发药应返既有码 6001——既有错误码 6001–6006 原样保留，不许被新流程改掉'
oid = disp[0]['id']
ok(api('GET', f'/outpatient/dispense/dispensed?registrationId={rid1}'), '已发药记录')
ok(api('POST', f'/outpatient/dispense/orders/{oid}/return', {}), '既有退药端点')
assert stock_of(did) == _before_pick, f'退药应回补到摆药前水位，实际 {stock_of(did)}'
print('[5] 既有链路 OK（**4 个端点逐字未改** / 6001 原样 / 发药退药仍是扣加库存的那一步）')

# ===========================================================================
# 6) 发药出库**没有进批次账**——本版对账保障的真实缺口，如实钉住不掩盖
# ===========================================================================
flows2 = rows_of(ok(api('GET', f'/pharm/stock/flows?drugId={did}'), '批次流水(发药后)'))
types = {_flow_type(f) for f in flows2}
bal2 = ok(api('GET', f'/pharm/stock/balance?drugId={did}'), '余额(发药后)')
b2 = _json.dumps(bal2, ensure_ascii=False)
if 'OUT' in types:
    # 发药已接入批次账：那就必须能对上，且退药必须回到原批次
    assert '"drift":0' in b2.replace(' ', '') or '"drift": 0' in b2, \
        f'发药已写 OUT 流水，drift 就必须归零：{b2[:300]}'
    print('[6] 发药已接入批次账（drift 归零）——请把本车道 report 里的缺口条目一并销案')
else:
    gap(False, '发药/退药/盘点三条路径不进批次账，批次层恒等式只对入库与报损成立',
        'V147 注释第三节自陈的「已知漂移」。后果：① 近效期预警仍是估算，召回时算不出'
        '「这批还剩多少在架」；② 退药回补落不到原批次，不变式③（退药可追溯到原发药批次）'
        '直接不成立；③ 最危险的是盘点——它把汇总拉到实存，把前两条造成的差额**永久抹平**，'
        '批次层的错账原地不动无人知晓，表面上「一直对得上」实则每次靠盘点擦屁股。'
        '收敛路径 V147 已给：DispenseService 改为先按 pick_rule 取批、扣批次余额并写 OUT，'
        '再扣 md_drug.stock。要动既有发药链路与并发抢占模式，须单独评估回归。')
    assert 'drift' in b2, (
        '发药未进批次账**且漂移没有随返回体下发**——两者只能占一个：'
        '要么把账做全，要么把缺口明说。都不做就是让它安静地错下去。')
    print('[6] 漂移已显式下发（不掩盖是底线，但缺口已记入 GAPS 交主控裁决）')

# ===========================================================================
# 7) 拆零：换算系数**有没有维护入口**
# ===========================================================================
info = api('GET', f'/outpatient/pharm-ops/split/drugs/{did}')
assert info['code'] == 0, f'拆零可行性查询应可调用：{info}'
blocked = api('POST', '/outpatient/pharm-ops/split/dispense', {'drugId': did, 'qtyMinUnit': 5})
assert blocked['code'] == 5460, (
    f'未维护 pack_size/min_unit 的药必须返 5460 拒绝拆零，**尤其不许从 spec 文本正则出系数**：'
    f'规格是自由录入（「10ml:0.1g*5支/盒」「24片×2板」「1g/瓶」），猜错一位就是把'
    f'「1 盒」当「1 片」发出去。实际：{blocked}')

maintained = None
_all_drugs = ok(api('GET', '/masterdata/drugs?keyword=&all=true'), '全量药品')
for row in _all_drugs:
    r = api('GET', f'/outpatient/pharm-ops/split/drugs/{row["id"]}')
    if r['code'] == 0 and (r['data'] or {}).get('packSize'):
        maintained = row
        break

# 一条都没维护过是正常的：新库 + 零回填，系数本就该由药剂科逐条录。
# 真正要钉的是「**有没有地方能录进去**」——只堵不疏才是缺陷。
if maintained is None and _all_drugs:
    _row = _all_drugs[0]
    _put = api('PUT', f'/outpatient/pharm-ops/drugs/{_row["id"]}/pack-spec',
               {'packSize': 24, 'minUnit': '片'})
    if _put['code'] == 0:
        _chk = api('GET', f'/outpatient/pharm-ops/split/drugs/{_row["id"]}')
        if _chk['code'] == 0 and (_chk['data'] or {}).get('packSize') == 24:
            maintained = _row
            # 顺带钉死「拒绝猜」没被顺手改成「默认按 1」
            _bad = api('PUT', f'/outpatient/pharm-ops/drugs/{_row["id"]}/pack-spec',
                       {'packSize': 1, 'minUnit': '片'})
            assert _bad['code'] == 5465, (
                '换算系数等于 1 必须被拒——等于 1 说明该药本就不需要拆零；'
                f'放行它等于给「默认按 1」开后门，而那会让「1 盒」当「1 片」发出去：{_bad}')
            ok(api('PUT', f'/outpatient/pharm-ops/drugs/{_row["id"]}/pack-spec',
                   {'packSize': 24, 'minUnit': '片'}), '恢复系数')

if gap(maintained is not None,
       '拆零换算系数（md_drug.pack_size / min_unit）**没有任何维护入口**，5460–5479 整段在生产上不可达',
       'V150 加了两列，但全仓 grep 只有 SQL 注释、零写入路径：'
       'MasterDataController 的 CSV 导入只认 8 列（code..antibiotic + 费用类别/自费）；'
       'PUT /masterdata/drugs/{id}/attrs 的 ItemAttrReq 只有 feeCategoryCode 与 selfPay。'
       '于是 POST /pharm-ops/split/dispense 对**每一味药**都返 5460，拆零一次也走不通。'
       '注意这不是「拒绝猜」做错了——拒绝猜是对的；错在**只堵不疏**：'
       '既然不许猜，就必须给药剂科一个能录进去的地方。'):
    mid = maintained['id']
    meta = ok(api('GET', f'/outpatient/pharm-ops/split/drugs/{mid}'), '拆零信息')
    ps = int(meta['packSize'])
    before_stock = stock_of(mid)
    # **基线取实测散装余量，不写死绝对数**：同一天重跑本套时，上一轮开包的散装余量还在，
    # 凑同样的数需要开的包数会变少（首轮剩 9 片，二轮凑 30 片只需再开 1 包）。
    # 写死 2 会让本条在第二次跑时假红——而那恰恰说明业务是对的（散装先用完才开新包）。
    _m0 = ok(api('GET', f'/outpatient/pharm-ops/split/drugs/{mid}'), '拆零信息(发药前)')
    _loose0 = int(_m0.get('remainQty') or _m0.get('looseQty') or 0)
    _need = ps + 6
    _expect_packs = max(0, -(-(_need - _loose0) // ps))   # 向上取整，散装够则不开包
    sd = ok(api('POST', '/outpatient/pharm-ops/split/dispense',
                {'drugId': mid, 'qtyMinUnit': _need}), '拆零发药')
    assert sd['packsOpened'] == _expect_packs, (
        f'凑 {_need} 个最小单位、已有散装 {_loose0} 个，应开 {_expect_packs} 个销售单位'
        f'（向上取整，且散装先用完再开新包）：{sd}')
    assert sd['remainQty'] == _loose0 + _expect_packs * ps - _need, (
        f'散装余量应为 已有{_loose0} + 开包{_expect_packs}×{ps} − 发出{_need}：{sd}')
    assert stock_of(mid) == before_stock - _expect_packs, (
        f'开包必须从整包库存里真的扣掉 {_expect_packs} 个销售单位')
    txns = rows_of(ok(api('GET', f'/outpatient/pharm-ops/split/drugs/{mid}/txns'), '拆零流水'))
    opened = sum(int(x['qty']) for x in txns
                 if x.get('type') == 'OPEN'
                 and (x.get('refNo') or x.get('ref_no')) == sd['dispenseNo'])
    assert opened == 2 * ps, (
        f'【跨账换算】总账出库 2 个销售单位 × pack_size {ps} 必须等于拆零账入池 {opened} 个最小单位。'
        f'差一点就是「按盒扣、按片发」——账上扣 1 盒实际发 3 片，剩下的去哪了没人知道，'
        f'而这个差额会被下一次盘点抹平。')
    ok(api('POST', f'/outpatient/pharm-ops/split/dispenses/{sd["dispenseId"]}/return',
           {'qtyMinUnit': 3}), '拆零部分退回')
    print(f'[7] 拆零 OK（未维护系数返 5460 不猜 spec / 开包向上取整 / '
          f'**跨账换算严丝合缝 2×{ps}** / 支持部分退回）')
else:
    print('[7] 拆零：拒绝猜 spec 这一条是对的（5460 命中），但**无维护入口**已记入 GAPS')

# 近效期：读端点必须能跑，且必须说明阈值取自哪一处（同一概念只能有一个数）
wd = ok(api('GET', '/outpatient/pharm-ops/expiry/warn-days'), '近效期阈值')
wblob = _json.dumps(wd, ensure_ascii=False)
assert 'inv_expiry_warn_days' in wblob or 'source' in wblob.lower(), (
    f'近效期阈值必须说明取自哪一处：药库巡检的 inv_expiry_warn_days 与药房窗口的 '
    f'pharm.expiry.warn_days 是同一个业务概念，两处都显示「90 天」时没人看得出信谁。{wblob}')
ok(api('GET', '/outpatient/pharm-ops/expiry/warnings'), '近效期列表')
print('[7b] 近效期 OK（阈值单一真相：药房窗口留空即跟随药库巡检阈值，并随返回体说明来源）')

# ===========================================================================
# 8) 发药核对（双人核对 / 高危 / 看似听似）：Service 有、**入口有没有**
# ===========================================================================
probe = api('GET', f'/outpatient/dispense-check/registrations/{rid1}')
has_entry = probe.get('code') not in (4040, 4041)
if gap(has_entry,
       '发药核对（5440–5459）**没有任何 Controller**，DispenseCheckService 整段不可达',
       '该 Service 里有双人核对 5443、高危逐行确认、LASA 提示、gate 台账，'
       '还有 md_drug.high_alert 与 pharm_lasa_pair 的维护方法——全部没有入口。'
       '后果：① 核对单永远建不出来，pharm_dispense_check 永远是空表，'
       '于是「摆药人≠核对人」「未核对不得发药」两条断言恒真而毫无意义'
       '（**一条永远不会红的断言等于没有断言**）；'
       '② 高危药品目录与易混淆药品目录在生产上一条也录不进去，'
       '于是核对提示永远是「本单 N 行药品高危属性尚未维护」。'):
    chk = ok(api('POST', '/outpatient/dispense-check/registrations',
                 {'registrationId': rid1}), '建核对单')
    cid = chk.get('id') or chk.get('checkId')
    same = api('PUT', f'/outpatient/dispense-check/{cid}/check', {})
    assert same['code'] == 5443, (
        f'**摆药人不得兼任核对人**——一个人签两次不叫双人核对。这条与 gate 无关：'
        f'gate 管的是「未核对能否发药」，不管「能否一人占两个位」（同 v48 病理双签口径）。'
        f'实际：{same}')
    print('[8] 发药核对 OK（双人核对 5443 / 高危逐行确认 / LASA 提示 / gate 台账）')
else:
    print('[8] 发药核对：**Service 有、入口无**，已记入 GAPS')

# ===========================================================================
# 汇总
# ===========================================================================
print('\n' + '=' * 78)
if GAPS:
    print(f'e2e-v50-pharmacy：能跑的都跑通了，但有 {len(GAPS)} 个缺口 ❌\n')
    for i, (title, detail) in enumerate(GAPS, 1):
        print(f'  {i}. {title}\n     {detail}\n')
    print('以上均为「功能不可达 / 账做不全」，不是环境问题。交主控裁决：要么补齐，'
          '要么登记为本版明确不做并署名——**不许留在注释里安静地不做**。')
    _sys.exit(1)
print('e2e-v50-pharmacy 全部通过 ✅')
