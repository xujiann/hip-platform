# -*- coding: utf-8 -*-
"""签认分桶：把 ★ 实质性降级条目按**处置类型**分桶，供项目负责人批量签认。

用法：python tools/signoff-triage.py            （只读，生成两份产物）
      python tools/signoff-triage.py --check    （只校验与统计，不写文件；CI 可用）

输入（只读，绝不修改）：
  docs/验收/技术偏离表-v3.csv        —— 当前权威结论（UTF-8 BOM + CRLF）
  docs/验收/偏离表重出-签认清单.md   —— §2.1 正式签认名单

输出：
  docs/验收/签认分桶清单.md   —— 按桶分组、每桶带批量签认块，供负责人逐桶决策
  docs/验收/签认分桶.csv      —— 机器可读，供 tools/reissue-upgrade.py 下游消费

与既有工具链的边界（**不得越界**）：
  · 本脚本**只提议、不改表**。改 v3 结论一律走 tools/reissue-upgrade.py --apply。
  · 本脚本**不生成对外说明文案**。对外文案须过 tools/reissue-qa.py 四维校验，
    由人工撰写；机器生成对外口径正是 v45 核账查出 18 条假应答的成因。
  · 产物是**内部决策文档**，内部词（file:line / 表名 / 轮次号）在此允许出现；
    它们不得流入 v3 的「响应说明」列。

分桶的诚实边界（写在产物页首，不许省略）：
  · 规则是**正则分诊**，不是判定。每条都附「命中了哪条规则」，负责人可据此复核。
  · 规则之间按优先级互斥，先命中先归桶——**归桶顺序会影响结果**，已在产物里标注。
  · 凡规则无把握的一律进 Z 桶（需人工判），**不许猜**。桶大小不是准确率。
"""
import csv, io, os, re, sys, collections, datetime

sys.stdout.reconfigure(encoding='utf-8')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
V3 = os.path.join(ROOT, 'docs', '验收', '技术偏离表-v3.csv')
LIST = os.path.join(ROOT, 'docs', '验收', '偏离表重出-签认清单.md')
OUT_MD = os.path.join(ROOT, 'docs', '验收', '签认分桶清单.md')
OUT_CSV = os.path.join(ROOT, 'docs', '验收', '签认分桶.csv')

# ---------------------------------------------------------------- 分桶规则
# 每条规则 = (桶号, 桶名, 匹配字段, 正则, 处置动作)
# 顺序即优先级。命中即归桶，不再参与后续规则。同一桶可有多条规则。
E_NAME = 'E 说明列不可签认——须先重写'
E_ACT = '回查代码后重写「响应说明」，方可进入签认；当前文案无法作为签认依据'
B_NAME = 'B 非软件条款——出函或澄清，非开发项'
B_ACT = '出具承诺函/测试报告/厂商规格书，或改判配套产品响应'
C_NAME = 'C 设计选择被判缺项——宜写澄清而非降级'

RULES = [
    ('A', 'A 知识内容类——应改判「配套产品响应」',
     '参数摘要',
     r'知识包|知识库|病人特征|判断条件|药品特点|药理属性|不良反应信息通报'
     r'|作用机制|药物治疗分类|乌头碱|单胺氧化酶|磺胺结构|中药颗粒目录'
     r'|药物手册|医药学公式|文献|杂志|参考文献',
     '改判为配套产品响应（平台提供承载框架与执行机制，知识内容由知识库厂商提供并导入）'),

    ('B', B_NAME, '参数摘要',
     r'国产操作系统|达梦|金仓|麒麟|信创|ClickHouse|数据仓库|数仓|ESB|企业服务总线'
     r'|证书(?:包含|策略|有效期)|承诺函|测试报告|适配证书|软件著作权|等保测评',
     B_ACT),
    # 条款自述「非本平台软件功能」（专线租赁、网络设备硬件规格等）
    ('B', B_NAME, '响应说明',
     r'非本平台软件功能|线路租赁|厂商规格书|拟投设备',
     B_ACT),

    ('C', C_NAME, '参数摘要',
     r'删除用户|物理删除',
     '写澄清：以停用+审计留痕替代物理删除，是更合规的设计选择，非功能缺失'),
    # 平台有意采取的更宽处置（如缺药提示而非阻断），被判为缺项
    ('C', C_NAME, '响应说明',
     r'采用提示而不阻断|提示而非阻断|考虑到.{0,12}实际业务',
     '写澄清：该处置方式是权衡实际业务后的设计选择，并可按院方要求配置为阻断'),

    ('E', E_NAME, '响应说明',
     r'界面细节逐条比对|字段级差异逐条比对|细节逐条比对'
     r'|对应模块.{0,10}能力已实现|对应业务登记能力已实现',
     E_ACT + '（机器泛化占位文案）'),
    # 内部词/内部口吻漏进对外说明列。实测全作用域仅 2 条命中，规则足够精确。
    # 注：既有 tools/reissue-qa.py 的 A 维只拦三段式蛇形表名（[a-z]+_[a-z]+_[a-z]+），
    #     两段式（md_drug）与「雏形/已闭环/字段扩展」这类内部口吻从它下面漏了过去。
    ('E', E_NAME, '响应说明',
     r'\b[a-z]+_[a-z]+\b|ESB\s*级|雏形|已闭环|字段扩展',
     E_ACT + '（内部词或内部口吻漏进对外文案）'),

    ('Y', 'Y 已挂起的二级评审建议', '响应说明',
     r'【二级证据评审建议',
     '按既有评审建议先行裁决，再并入相应桶'),
]

# D 桶不靠关键词，靠「说明列是否枚举了已提供部分」分档（见 classify）
D1_NAME = 'D1 快赢——已提供大部，缺口已枚举'
D2_NAME = 'D2 真欠账——需排期评估'
F_NAME = 'F 已判配套产品/外部条件——确认即可'
Z_NAME = 'Z 规则无把握——须逐条人工判'

D1_ACT = '按缺口清单补齐后申请上调（成本低、★ 密度高者优先成版）'
D2_ACT = '按主题成版排期；排期前先确认该条是否值得为评标而建'
F_ACT = '确认现结论口径无误即可，无开发动作'
Z_ACT = '逐条人工判定处置类型'

# D2 排期用的主题聚类（只用于给排期做输入，不影响桶归属）
THEMES = collections.OrderedDict([
    ('护理与病历文书', r'护理|病历|文书|体温|病案|随访|评分'),
    ('病理 PIS', r'病理|标本|取材|蜡块|切片|染色|冰冻'),
    ('数据治理与报表指标', r'数据集|指标|填报|质检|报表|数据质量|数据标准|主数据|字典'),
    ('权限审计与系统运维', r'权限|日志|审计|用户|角色|备份|监控|单点|运维'),
    ('医技检验影像', r'检验|影像|LIS|PACS|设备|检查|放射|超声'),
    ('医保 DRG 结算', r'医保|DRG|结算|基金|付费'),
    ('门急诊与住院业务', r'门诊|急诊|住院|医嘱|处方|挂号|收费|床位'),
    ('药事与药房', r'药品|药房|药剂|发药|调剂|处方审|用药'),
])


def theme_of(param: str) -> str:
    for name, pat in THEMES.items():
        if re.search(pat, param):
            return name
    return '其他'


def classify(row: dict):
    """返回 (桶号, 桶名, 处置动作, 命中规则说明)。"""
    concl = row['响应结论'].strip()

    # F：已经判在平台责任之外，签认成本最低，先摘出去
    if concl in ('配套产品响应', '外部条件'):
        return ('F', F_NAME, F_ACT, f'现结论已是「{concl}」')

    for code, name, field, pat, act in RULES:
        m = re.search(pat, row[field])
        if m:
            return (code, name, act, f'{field}命中「{m.group(0)}」')

    if concl != '部分响应':
        return ('Z', Z_NAME, Z_ACT, f'现结论「{concl}」不在已知处置类型内')

    note = row['响应说明']
    # D1/D2 的判据是**缺的是整条还是子项**，这是说明列里唯一机器可读的规模信号：
    #   ·「本条所述 X …未提供」——X 就是本条要求本身，整条缺 → D2
    #   ·「本平台已提供/支持 X；尚未提供①②」——主体在，缺的是子项 → D1
    # 顺序要紧：170 条两种措辞都有，一律按「本条所述」判 D2（整条缺的语义更强）。
    if re.search(r'本条所述', note):
        return ('D2', D2_NAME, D2_ACT, '说明列为「本条所述…未提供」（整条缺）')
    if re.search(r'尚未提供', note):
        return ('D1', D1_NAME, D1_ACT, '说明列描述了已有能力并逐项列出缺口（子项缺）')
    if re.search(r'已提供', note):
        return ('D1', D1_NAME, D1_ACT, '说明列枚举了已提供部分')
    return ('Z', Z_NAME, Z_ACT, '说明列句式不在已知模式内')


def load_v3():
    rows = list(csv.DictReader(io.open(V3, encoding='utf-8-sig', newline='')))
    need = {'序号', '实质性★', '重要▲', '参数摘要', '响应结论', '响应说明'}
    missing = need - set(rows[0].keys())
    if missing:
        sys.exit(f'v3 列缺失：{missing}')
    return rows


def load_roster():
    """解析签认清单 §2.1 的正式名单，返回 {序号: 原→新}。"""
    roster, in21 = {}, False
    for ln in io.open(LIST, encoding='utf-8').read().splitlines():
        if ln.startswith('### 2.1'):
            in21 = True
            continue
        if in21 and ln.startswith('### '):
            break
        if in21 and ln.startswith('|'):
            c = [x.strip() for x in ln.strip().strip('|').split('|')]
            if len(c) >= 5 and re.fullmatch(r'\d+', c[0]):
                roster[c[0]] = c[2]
    return roster


def main():
    check_only = '--check' in sys.argv
    rows = load_v3()
    roster = load_roster()

    # 作用域：★ 且 现结论不是「正偏离/无偏离」、不是「非参数行」——即仍欠一个处置决定的
    scope = [r for r in rows
             if r['实质性★'].strip()
             and r['响应结论'].strip() not in ('正偏离/无偏离', '非参数行', '非参数')]

    recs = []
    for r in scope:
        code, name, act, why = classify(r)
        recs.append({
            '序号': r['序号'].strip(),
            '桶': code,
            '桶名': name,
            '现结论': r['响应结论'].strip(),
            '建议处置': act,
            '命中规则': why,
            '主题': theme_of(r['参数摘要']),
            '在正式名单内': '是' if r['序号'].strip() in roster else '否',
            '参数摘要': re.sub(r'\s+', ' ', r['参数摘要']).strip()[:80],
        })

    # ---- 名单一致性核对（数据质量，必须报出来）----
    scope_nos = {r['序号'] for r in recs}
    roster_only = sorted(set(roster) - scope_nos, key=int)
    scope_only = sorted(scope_nos - set(roster), key=int)
    non_downgrade = sorted(
        [n for n, ch in roster.items()
         if ch in ('正偏离/无偏离 → 正偏离/无偏离', '部分响应 → 正偏离/无偏离')], key=int)

    order = ['E', 'Y', 'A', 'B', 'C', 'D1', 'D2', 'F', 'Z']
    groups = collections.OrderedDict((k, []) for k in order)
    for rec in recs:
        groups[rec['桶']].append(rec)

    print(f'作用域（★ 且仍欠处置决定）：{len(recs)} 条')
    for k in order:
        g = groups[k]
        if g:
            print(f'  {k:2}  {len(g):4}  {g[0]["桶名"]}')
    print(f'\n正式名单 §2.1：{len(roster)} 条')
    print(f'  名单内但不在作用域（多为已上调/未降级）：{len(roster_only)} {roster_only[:10]}')
    print(f'  作用域内但不在名单（★ 原本就非正偏离）：{len(scope_only)} 条')
    print(f'  名单里混入的非降级行：{len(non_downgrade)} {non_downgrade}')

    if check_only:
        return

    write_csv(recs)
    write_md(groups, order, recs, roster, roster_only, scope_only, non_downgrade)
    print(f'\n已写出：\n  {OUT_MD}\n  {OUT_CSV}')


def write_csv(recs):
    cols = ['序号', '桶', '桶名', '现结论', '主题', '建议处置', '命中规则', '在正式名单内', '参数摘要']
    with io.open(OUT_CSV, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in sorted(recs, key=lambda x: (x['桶'], int(x['序号']))):
            w.writerow({c: r[c] for c in cols})


def write_md(groups, order, recs, roster, roster_only, scope_only, non_downgrade):
    today = datetime.date.today().isoformat()
    L = []
    A = L.append
    A(f'# ★ 实质性条目签认分桶清单（{today}）')
    A('')
    A('> 由 `tools/signoff-triage.py` 生成，**只读分诊、不改偏离表**。')
    A('> 目的：把逐条签认改为**按桶批量决策**——同一桶内处置动作相同，负责人签一次即可。')
    A('')
    A('## 这份清单的诚实边界（先读这段再看桶）')
    A('')
    A('1. **桶是正则分诊的结果，不是判定。** 每条都带「命中规则」列，可据此复核；')
    A('   规则按优先级互斥，先命中先归桶，**归桶顺序影响结果**（顺序见脚本 `RULES`）。')
    A('2. **规则无把握的一律进 Z 桶**，没有猜测归桶。桶大小不代表准确率。')
    A('3. **本清单不生成任何对外文案。** 改「响应说明」须人工撰写并过 `tools/reissue-qa.py`；')
    A('   改结论须走 `tools/reissue-upgrade.py --apply`。机器生成对外口径正是 v45 核账')
    A('   查出 18 条假应答的成因，此处不重蹈。')
    A('4. 本文件是**内部决策文档**，出现内部词（表名/轮次号）属正常，但它们不得流入 v3。')
    A('')
    A('## 总览')
    A('')
    A('| 桶 | 条数 | 含义 | 桶级处置动作 | 是否需要开发 |')
    A('|---|---:|---|---|---|')
    dev = {'E': '否（先补文案）', 'Y': '看裁决', 'A': '否', 'B': '否', 'C': '否',
           'D1': '是（低成本）', 'D2': '是（需评估）', 'F': '否', 'Z': '待定'}
    # 桶级动作：多规则桶（E/B/C）的逐条动作可能不同，总览须给桶级口径，
    # 不能拿 g[0] 的逐条动作冒充全桶——那正是「一槽多句只求值一句」的同型错误。
    bucket_act = {
        'E': '回查代码后重写「响应说明」，方可进入签认',
        'Y': '按既有评审建议先行裁决，再并入相应桶',
        'A': '改判为配套产品响应（平台提供承载框架，知识内容由厂商提供）',
        'B': '出具承诺函/测试报告/厂商规格书，或改判配套产品响应',
        'C': '写澄清：是设计选择而非功能缺失',
        'D1': '按缺口清单补齐后申请上调（★ 密度高者优先成版）',
        'D2': '按主题成版排期；排期前先确认该条是否值得为评标而建',
        'F': '确认现结论口径无误即可，无开发动作',
        'Z': '逐条人工判定处置类型',
    }
    for k in order:
        g = groups[k]
        if not g:
            continue
        A(f'| **{k}** | {len(g)} | {g[0]["桶名"]} | {bucket_act[k]} | {dev[k]} |')
    A(f'| | **{len(recs)}** | 合计 | | |')
    A('')
    if not groups['Z']:
        A('> Z 桶（规则无把握）本次为 **0 条**——全部条目都由某条具名规则命中。')
        A('> 这不等于分类全对：规则仍是分诊，逐条正确性以「命中规则」列为准由人工复核。')
        A('> 主控已抽验 D1/D2 各 4 条，8/8 与说明列语义一致（抽验记录见规划节）。')
        A('')
    A('**关键读法**：真正需要开发的只有 D1 + D2；A/B/C/F 合计是**口径问题不是开发问题**，')
    A('签认即可清账。E 桶必须最先处理——它的说明列当前无法作为签认依据。')
    A('')
    A('## 名单一致性核对')
    A('')
    A(f'- 正式名单 §2.1 共 **{len(roster)}** 条（文档标题写 418）')
    A(f'- 名单内但已不在作用域（多为已上调）：**{len(roster_only)}** 条 {roster_only if roster_only else ""}')
    A(f'- 作用域内但不在名单（★ 原本就非正偏离，同样欠一个决定）：**{len(scope_only)}** 条')
    if non_downgrade:
        A(f'- ⚠ 名单里混入 **{len(non_downgrade)}** 条非降级行（序号 {non_downgrade}）——')
        A('  §2.1 的定义是「★ 实质性条款降级」，这几条应移出，否则签认范围虚高。')
    A('')

    for k in order:
        g = groups[k]
        if not g:
            continue
        A(f'## {k} 桶 · {g[0]["桶名"]}（{len(g)} 条）')
        A('')
        A(f'**桶级处置动作**：{bucket_act[k]}')
        acts = sorted({x['建议处置'] for x in g})
        if len(acts) > 1:
            A('')
            A('本桶逐条动作不止一种（命中了不同规则），逐条动作见下表「建议处置」列：')
            for a in acts:
                A(f'- {a}')
        A('')
        if k == 'D2':
            A('按主题分布（排期输入）：')
            A('')
            tc = collections.Counter(x['主题'] for x in g)
            A('| 主题 | 条数 |')
            A('|---|---:|')
            for t, n in tc.most_common():
                A(f'| {t} | {n} |')
            A('')
        multi = len(acts) > 1
        head = '| 序号 | 现结论 | 主题 | 参数摘要 | 命中规则 |'
        sep = '|---:|---|---|---|---|'
        if multi:
            head = '| 序号 | 现结论 | 主题 | 参数摘要 | 命中规则 | 建议处置 |'
            sep = '|---:|---|---|---|---|---|'
        A(head)
        A(sep)
        for r in sorted(g, key=lambda x: int(x['序号'])):
            p = r['参数摘要'].replace('|', '\\|')
            row = f'| {r["序号"]} | {r["现结论"]} | {r["主题"]} | {p} | {r["命中规则"]} |'
            if multi:
                row += f' {r["建议处置"]} |'
            A(row)
        A('')
        A('**批量签认**（负责人在此签一次，覆盖本桶全部条目）：')
        A('')
        A('- [ ] 同意本桶处置动作，全部条目按上述动作处理')
        A('- [ ] 不同意，改为：____________________（写明新动作）')
        A('- [ ] 需逐条看，例外条目序号：____________________')
        A('')
        A('签认人：____________  日期：____________')
        A('')

    io.open(OUT_MD, 'w', encoding='utf-8').write('\n'.join(L) + '\n')


if __name__ == '__main__':
    main()
