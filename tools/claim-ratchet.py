#!/usr/bin/env python3
"""屏上断言棘轮：本轮动过的上屏文案必须登记；循环内不随循环变量变化的断言自动升 FACT 档。

**为什么有这个东西**（v64 的实付学费）
v61 起连续四轮，同一批投标参数被复核打回，形态一轮比一轮隐蔽：
  v61 每条修复只做一半 → v62 只修被点名的那个入口 → v63 后端改完前端没接
  → v64 **修复自身带进了新的假话**。
前三种都有工具盯着（DEV_FACING / RAW_ENUM / contract-wired-check.py 等），
第四种一条都没抓住——因为那些工具盯的是「接没接上」（键、入口、消费方），
而 v64 那四条是**接上了，但那句话在它依附的状态下是假的**：
  · reviseCoverNote 写死「已预填在下面的字段栏里」，而 UNPARSED 档一项都不预填；
  · summaryTitle 断言「下面那张表才按日拆分」，却被无条件绑给每一条指标，
    而按科室/技术类型/标本类别/染色类型分组的那几条，表里连日期列都没有；
  · 蜡块 caveat 宣称合计「必然小于」按日各行之和，而演示库态两数逐字相等；
  · 退化告警说「字段级查询与统计取不到」，而那几行仍在库里、按版本调阅得到。

**两条机制，各治一半**

(1) `--since <ref> --strict`：**diff 驱动的棘轮**。不问句子说了什么，只问**这句是不是本轮动过的**。
    选它而不选词法分诊，是因为实测：v64 四条假话 `git log -S` 全部命中本轮车道提交，
    而本轮未动的对照句零命中；反过来，三种「按句子长相分诊」的办法都被证明打穿——
    reviseCoverNote 那类「不含数、不在循环、不引字典的装饰性从句」没有任何静态形态可抓。
    本轮动过的带句读文案必须在登记簿里有一行，否则红。

(2) `--detect`：**循环变量规则**。在 v-for 子树里，一条文本绑定若**不引用任何循环变量**，
    而它解析到的定义又是个带句读的句子，那它就是「一句话被无条件说给 N 个不同对象听」——
    summaryTitle 正是这个形状。命中的自动升 FACT 档（FACT 档才需要配对拍用例）。

**两级登记是有意的**：实测一轮约 70 条新/改文案，给每条都配运行时守卫是数千行/轮的脚手架，
必然塌方成橡皮图章——**一个所有人都在敷衍的登记簿比没有更坏**。
默认档只要复核时签一下字（成本≈0）；只有被 `--detect` 点名、落在投标参数热区、
或被复核打回过的，才升 FACT 档、才上对拍台。

**这套办法抓不到什么**（必须读，不许当它是全覆盖）
  a. **段落式 caveat 一个槽位载多句断言**时，登记簿只有一行，被求值的只有登记的那句，
     兄弟句仍是「登记齐全、闸门全绿、从没被求值过」。PathQcController 的 caveat 正是这个形态。
  b. **文案一字未动、而它底下的代码被改判**——v63 那句「若无已染色挂接切片就拦」就是这么变成假话的
     （v62 改了判定、文案没动）。diff 驱动看不见这种。
  c. **把串挪出扫描域**：从 controller 挪进 service、从 `:title` 挪进普通文本节点，
     都能静默逃逸。挪一行代码的事，而且看起来还像好实践。
  d. 模板子树按缩进切，不是真解析 HTML。本仓格式统一所以可用；格式一乱就会漏。

用法：
    python tools/claim-ratchet.py --selftest              # 活对照组，先跑这个
    python tools/claim-ratchet.py --detect                # 循环变量规则，列出必须升 FACT 的
    python tools/claim-ratchet.py --since v1.6.4 --strict # 棘轮：本轮动过的文案必须已登记
"""
import argparse
import collections
import hashlib
import io
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / 'docs' / '屏上断言登记簿.tsv'

VUE_GLOB = 'frontend/shell/src/**/*.vue'
JAVA_GLOBS = ['modules/*/src/main/java/**/web/*Controller.java']

# 句读：有它才算「一句话」，没有的是标签/列名，不会说假话
SENTENCE = re.compile('[。；！？]')

HTMLC = re.compile('<!--.*?-->', re.S)
BLOCKC = re.compile(r'/\*.*?\*/', re.S)
LINEC = re.compile(r'(?<![:\w])//[^\n]*')


def strip_comments(t):
    """注释先剥。contract-wired-check.py 曾因为不剥注释，被一句提到键名的注释骗过绿灯。"""
    return LINEC.sub(' ', BLOCKC.sub(' ', HTMLC.sub(' ', t)))


def fingerprint(s):
    """归一化后取指纹：插值占位、空白、全角空格都抹平，措辞改一个字才算变。"""
    n = re.sub(r'\$\{[^}]*\}', '〇', s)          # ${...} → 〇
    for ch in ('"', "'", '`', '+'):
        n = n.replace(ch, '')
    n = re.sub(r'\s+', '', n).replace('　', '')
    return hashlib.sha1(n.encode('utf-8')).hexdigest()[:12]


# ---------------------------------------------------------------- 循环变量规则

VFOR = re.compile(r'v-for="\s*\(?\s*([A-Za-z_$][\w$]*)\s*(?:,\s*([A-Za-z_$][\w$]*)\s*)?\)?\s+(?:in|of)\s')
TEXT_BIND = re.compile(r':(?:title|label|content|description)="([^"]*)"')
MUSTACHE = re.compile(r'\{\{([^}]*)\}\}')
IDENT = re.compile(r'[A-Za-z_$][\w$]*')


def template_of(text):
    i = text.find('<template>')
    if i < 0:
        return ''
    j = text.rfind('</template>')
    return text[i:j if j > i else len(text)]


def indent_of(line):
    return len(line) - len(line.lstrip())


def loop_subtrees(tpl):
    """按缩进切出每个 v-for 元素的子树。见文件头 limits (d)：不是真解析 HTML。"""
    lines = tpl.split('\n')
    out = []
    for i, line in enumerate(lines):
        m = VFOR.search(line)
        if not m:
            continue
        base = indent_of(line)
        body = [line]
        for nxt in lines[i + 1:]:
            if nxt.strip() and indent_of(nxt) <= base:
                break
            body.append(nxt)
        block = '\n'.join(body)
        # **子树里所有层级的循环变量都要算进来，含嵌套 v-for**。
        # 第一版只收本层，于是 AllergyReviewView 里内层 v-for 的 a.code / a.name 被判成
        # 「不随循环变化」——一口气 4 条误报。而误报最危险：本项目的真实失败模式是
        # 「一条咬错的断言迟早被人改宽到形同虚设」，宁可漏也不能乱咬。
        loop_vars = {g for mm in VFOR.finditer(block) for g in mm.groups() if g}
        out.append((i + 1, loop_vars, block))
    return out


def sentence_defs(script):
    """文件内 const/computed 定义 → 它的定义文本（到下一个顶层 const 为止），供裸标识符回溯。"""
    defs = {}
    for m in re.finditer(r'\bconst\s+([A-Za-z_$][\w$]*)\s*=', script):
        name = m.group(1)
        nxt = re.search(r'\n(?:const|function)\s', script[m.end():])
        defs[name] = script[m.end(): m.end() + (nxt.start() if nxt else 1200)]
    return defs


def detect_loop_invariant_claims(paths):
    """v-for 子树里不引用任何循环变量、且解析到带句读定义的文本绑定。"""
    hits = []
    for p in paths:
        raw = p.read_text(encoding='utf-8', errors='replace')
        text = strip_comments(raw)
        tpl = template_of(text)
        if not tpl:
            continue
        defs = sentence_defs(text)
        for lineno, loop_vars, body in loop_subtrees(tpl):
            exprs = [(e, 'bind') for e in TEXT_BIND.findall(body)]
            exprs += [(e, 'mustache') for e in MUSTACHE.findall(body)]
            for expr, kind in exprs:
                names = set(IDENT.findall(expr))
                if names & loop_vars:
                    continue                      # 引用了循环变量 → 随对象而变，不是无条件断言
                for n in names:
                    body_text = defs.get(n)
                    # **要的是「定义里有一句带句读的长串」，不是「定义文本里出现过句读」**。
                    # 第一版直接对定义源码搜句读，于是 join('；') 的**分隔符**也算数——
                    # PrintView 的 diagText / briefHistory 一口气 3 条误报（它们是同一次就诊的
                    # 患者信息，每张单据都印同一份本来就对）。现在要求：定义里真有一条
                    # 长度 > 12 且含句读的字符串字面量，才算「它说了一句话」。
                    if body_text and any(
                            SENTENCE.search(lit) and len(lit) > 12
                            for mm in LIT.finditer(body_text)
                            for lit in [next(x for x in mm.groups() if x is not None)]):
                        # 取定义里那条最长的带句读字面量当这条断言的正文，
                        # 指纹与棘轮同一套——同一行登记簿两边都认得。
                        lits = [next(x for x in mm.groups() if x is not None)
                                for mm in LIT.finditer(body_text)]
                        sent = max((l for l in lits if SENTENCE.search(l) and len(l) > 12),
                                   key=len, default='')
                        hits.append({
                            # 夹具文件在 ROOT 之外（自检用的合成标本），relative_to 会抛——原样给绝对路径
                            'file': (p.relative_to(ROOT).as_posix()
                                     if ROOT in p.resolve().parents else p.as_posix()),
                            'vfor_line': lineno,
                            'expr': expr.strip(),
                            'ident': n,
                            'loop_vars': sorted(loop_vars),
                            'fp': fingerprint(sent),
                            'text': sent,
                        })
                        break
    return hits


# ---------------------------------------------------------------- 棘轮

# 三种引号都要收：.vue 大量用单引号，第一版只写了双引号与反引号，
# 自检对照组当场红了三条（「下面那张表才按日拆分」正是单引号串）——这就是活对照组的用处。
_Q = '"' + "'" + '`'
LIT = re.compile('|'.join('%s((?:[^%s\\\\]|\\\\.)*)%s' % (q, q, q) for q in _Q))


def touched_claims(base, head='HEAD'):
    """<base>..HEAD 新增行里的带句读文案。diff 只看 + 行——改过的那一句才进棘轮。"""
    paths = ['frontend/shell/src', 'modules']
    try:
        diff = subprocess.run(['git', '-C', str(ROOT), 'diff', f'{base}..{head}', '--', *paths],
                              capture_output=True, text=True, encoding='utf-8',
                              errors='replace', check=True).stdout
    except subprocess.CalledProcessError as e:
        sys.exit(f'git diff 失败（基线 {base} 取不到？）：{e}')
    # **按「连续的 + 行」成块，不按物理行**。
    # 第一版按行抽，自检当场红：Java / JS 的一句话是跨多行拼接的，
    # 「…必然小于按日各行…」那一行片段以全角逗号收尾，句号在下一行——
    # 按行看它不含句读，直接被滤掉，而那正是 v64 四条假话之一。
    # 单位要对着「一句话」，不是对着「一行代码」。
    cur = None
    out = {}
    run = []

    def flush(fname, lines):
        if not fname or not lines:
            return
        joined = ''.join(
            next(x for x in m.groups() if x is not None)
            for ln in lines for m in LIT.finditer(strip_comments(ln)))
        if SENTENCE.search(joined) and len(joined) > 12:
            out.setdefault(fingerprint(joined), {'text': joined.strip(), 'file': fname})

    for line in diff.split('\n'):
        if line.startswith('+++ b/'):
            flush(cur, run)
            run = []
            cur = line[6:]
        elif line.startswith('+') and not line.startswith('+++') and cur:
            run.append(line[1:])
        else:
            flush(cur, run)
            run = []
    flush(cur, run)
    return out


def read_registry():
    if not REGISTRY.is_file():
        return {}
    reg = {}
    for line in REGISTRY.read_text(encoding='utf-8').split('\n'):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) >= 2:
            reg[parts[0].strip()] = {'tier': parts[1].strip(),
                                     'note': parts[3].strip() if len(parts) > 3 else ''}
    return reg


# ---------------------------------------------------------------- 活对照组

def selftest():
    """这把尺子必须先证明它抓得到已知的假话，再证明它不咬正常文案。"""
    ok = True

    def check(name, got, want):
        nonlocal ok
        mark = 'OK  ' if got == want else '**FAIL**'
        if got != want:
            ok = False
        print(f'  {mark} {name}（期望 {want}，实得 {got}）')

    # ============================================================================
    # 对照组一律用**不随修复变化的标本**：合成夹具，或钉死的历史区间。
    #
    # 第一版不是这么写的，v65 车道 C 当场把它打红并说清了为什么——**它红得是对的**：
    #   · 原对照组一要求「在真实的 PathQcView.vue 里找得到 summaryTitle」，
    #     而本轮任务书恰恰要求车道 C 把 summaryTitle 修掉。两者不可能同时为真，
    #     缺陷一修好，这个活标本就消失、尺子自己变红。
    #   · 原对照组三跑 touched_claims('v1.6.3')，而 git diff 是**两点树比较**、不是各次提交补丁的并集：
    #     本轮把那两句从 HEAD 的树里删掉之后，它们不再作为 + 行出现，探针必假。
    # 也就是说：那版自检会**随本轮修复逐条塌掉**——尺子越有用，它自己越早失效。
    #
    # 本仓早有这条纪律，是我建工具时没照做：V62TechRemarkTest 明写
    # 「对照组是历史形态的字面量，不随修复变化，两边各自成立」。
    # 反面教材也在同一处：绝不能把 want 从 True 改成 False、或把这两条删掉——
    # 那等于让尺子不再证明自己抓得住已知的假话，而一把不证明自己有效的尺子就是摆设。
    # ============================================================================

    print('【对照组一：循环变量规则必须抓到 v64 summaryTitle 那个形状（合成夹具，不扫活文件）】')
    FIXTURE_BAD = """<template>
      <div v-for="ind in list" :key="ind.code">
        <el-descriptions v-if="ind.summary" :title="summaryTitle">
          <el-descriptions-item :label="ind.name">x</el-descriptions-item>
        </el-descriptions>
      </div>
    </template>
    <script setup lang="ts">
    const summaryTitle = computed(() =>
      '本期合计　——整个统计区间一个数，不是某一天；下面那张表才按日拆分')
    </script>"""
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        f = pathlib.Path(td) / 'Fixture.vue'
        f.write_text(FIXTURE_BAD, encoding='utf-8')
        fx = detect_loop_invariant_claims([f])
    check('v64 那个形状被判红', any(h['ident'] == 'summaryTitle' for h in fx), True)
    check('同子树里引用循环变量的兄弟绑定不被咬',
          any('ind.' in h['expr'] for h in fx), False)

    print('【对照组二：活树上的判红条数（只报告，不当断言——它会随修复变化）】')
    hits = detect_loop_invariant_claims(sorted(ROOT.glob(VUE_GLOB)))
    print(f'  当前主树判红 {len(hits)} 条：'
          + '、'.join(sorted({h["file"].split("/")[-1] + ":" + h["ident"] for h in hits})))

    print('【对照组三：棘轮必须认出 v64 那四条假话——基线钉死在 v1.6.3..v1.6.4 这个不可移动的历史区间】')
    hist = touched_claims('v1.6.3', head='v1.6.4')
    texts = ' '.join(c['text'] for c in hist.values())
    for probe in ('已预填在下面的字段栏里', '下面那张表才按日拆分', '必然小于', '取不到'):
        check(f'{probe!r} 在 v1.6.3..v1.6.4 的改动里', probe in texts, True)

    print('【对照组四：指纹必须对措辞敏感、对插值与空白不敏感】')
    check('插值不同但措辞相同 → 同指纹',
          fingerprint('共 ${a} 天') == fingerprint('共 ${b} 天'), True)
    check('措辞改一个字 → 不同指纹',
          fingerprint('必然小于各行之和') == fingerprint('可能小于各行之和'), False)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--since', help='基线 ref（上一个发布 tag）')
    ap.add_argument('--strict', action='store_true', help='有未登记的本轮文案就退出码 1')
    ap.add_argument('--detect', action='store_true', help='只跑循环变量规则')
    ap.add_argument('--selftest', action='store_true', help='跑活对照组')
    a = ap.parse_args()

    if a.selftest:
        return 0 if selftest() else 1

    if a.detect:
        hits = detect_loop_invariant_claims(sorted(ROOT.glob(VUE_GLOB)))
        print(f'循环变量规则：判红 {len(hits)} 条（这些必须升 FACT 档）\n')
        for h in hits:
            print(f'  {h["fp"]}  {h["file"]}:{h["vfor_line"]}')
            print(f'      绑定 {h["expr"]!r} 解析到 {h["ident"]}，而循环变量是 {h["loop_vars"]}')
            print(f'      正文：{h["text"][:88]}')
            print('      —— 这句话被无条件说给每个对象听：请证明它对每个都成立，证不出就是假话')
        return 0

    if not a.since:
        ap.error('要么 --detect / --selftest，要么给 --since')

    claims = touched_claims(a.since)
    reg = read_registry()
    missing = {k: v for k, v in claims.items() if k not in reg}
    print(f'{a.since}..HEAD 动过的带句读上屏文案 {len(claims)} 条，登记簿已有 {len(claims) - len(missing)} 条。\n')
    if not missing:
        print('OK - 本轮动过的文案都已登记。')
        return 0
    print(f'── 未登记（{len(missing)}）——每条都要在 {REGISTRY.name} 里有一行'
          f'（默认档只需签字；被 --detect 点名的须升 FACT 并配对拍用例）')
    by_file = collections.defaultdict(list)
    for k, v in missing.items():
        by_file[v['file']].append((k, v['text']))
    for f, items in sorted(by_file.items()):
        print(f'\n  {f}')
        for k, t in items:
            print(f'    {k}  {t[:72]}')
    print()
    if a.strict:
        print('--strict：判为失败。')
        return 1
    print('（默认只报告。加 --strict 当闸门。）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
