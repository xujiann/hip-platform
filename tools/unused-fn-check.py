#!/usr/bin/env python3
"""定义了从未调用：扫 frontend/shell/src/**/*.vue，找「定义了、同文件里却再没人提它」的函数。

**为什么有这个脚本**（v74 的实付学费，提交 161904f）：
门诊医生站里 `loadEmrTemplates()` 自 v45 起**定义了却从未被调用**——结构化模板那一栏
（989★/1075★ 的交付物）在门诊站从来没有加载过数据，栏是空的，而 vue-tsc 与 vite build 都不报
（`noUnusedLocals` 没开，开了也只管局部变量的一部分形态）。它存在了近三十轮，直到三方复核手点才发现。
这是「接线类缺陷」的又一种形态：契约对账（contract-wired-check.py）盯**后端键没人用**，
本脚本盯**前端函数没人调**。

**判据（一句话）**：`<script setup>` 里用 `function name(` / `async function name(` /
`const name = (` / `const name = async (` 定义的函数，在**同一个 .vue 文件**（含 `<template>`、`<script>`、
`<style>` 全文，注释先剥掉）里再没有第二处 `name` 作为独立单词出现 → 判红，输出 file:line。
  · 独立单词 = 前后都不是 `[\\w$]`；`obj.name` 这种属性访问不算（那是别的对象的成员，不是调用本函数）。
  · `defineExpose({ name })` / `defineProps` / 模板 `@click="name"` 里出现的名字天然算「有第二处」。
  · `export` 的名字跳过（可能被别的模块 import，同文件看不出来）。
  · 注释先剥掉再数：否则一句「loadEmrTemplates 从未被调用」的注释就能骗过闸门——
    这正是契约对账闸门吃过的亏（见 contract-wired-check.py 里 strip_comments 的说明），照抄。

**已知局限（不是误报，是没做）**：
  · 只看「有没有第二处提到」，不看那第二处是不是它自己函数体里的递归自调——纯自调的死函数会漏。
  · 只扫 .vue；.ts 工具模块里的未用导出不在本脚本范围（vue-tsc 不报，另议）。
  · 只认上述四种定义形态；`const f = function(` / `const f = useXxx(` 等不扫。

用法：
    python tools/unused-fn-check.py --selftest        # 对照组：历史文件必红、HEAD 必绿；失败退出非零
    python tools/unused-fn-check.py                   # 全量扫描，有未豁免的判红退出 1
    python tools/unused-fn-check.py --allow tools/unused-fn-allow.txt   # 放行清单：`相对路径:函数名 # 理由`

放行清单只用于「确认是真死、但本轮不删」的存量（删属产品改动，由主控定）；
每条必须写明理由，清完一条删一行。
"""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRONTEND = ROOT / "frontend" / "shell" / "src"

# 对照组：修复前的历史文件（161904f 把它接上）。其中 loadEmrTemplates 定义了从未调用，必须判红。
CONTROL_COMMIT = "161904f"
CONTROL_PATH = "frontend/shell/src/views/outpatient/DoctorStationView.vue"
CONTROL_NAME = "loadEmrTemplates"

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
LINE_COMMENT = re.compile(r"(?<![:\w])//[^\n]*")

SCRIPT_BLOCK = re.compile(r"<script\b([^>]*)>(.*?)</script>", re.S | re.I)

# 定义形态（行首任意缩进；嵌套函数同样适用）。
# 箭头函数必须真有 `=>`：`const all = (await x).data` 这种括号表达式不是函数，不能当定义（首版就踩了这个坑）。
FN_DECL_RE = re.compile(r"^(?P<ind>[ \t]*)(?P<exp>export\s+)?(?:async\s+)?function\s*\*?\s+(?P<n>[A-Za-z_$][\w$]*)\s*\(", re.M)
ARROW_HEAD_RE = re.compile(r"^(?P<ind>[ \t]*)(?P<exp>export\s+)?const\s+(?P<n>[A-Za-z_$][\w$]*)\s*(?::[^=\n]+)?=\s*(?:async\s+)?(?P<p>\(|[A-Za-z_$][\w$]*\s*=>)", re.M)


def _is_arrow(text: str, pos: int) -> bool:
    """pos 指向 `(`：配平括号后（可带 `: 返回类型`）是否紧跟 `=>`。"""
    depth = 0
    i = pos
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    else:
        return False
    return re.match(r"\s*(?::[^=;{}]*?)?\s*=>", text[i + 1:i + 400]) is not None


def definitions(body: str):
    """[(body 内 0 起行号, 函数名)]——不含 export。"""
    out = []
    for m in FN_DECL_RE.finditer(body):
        if not m.group("exp"):
            out.append((body.count("\n", 0, m.start("n")), m.group("n")))
    for m in ARROW_HEAD_RE.finditer(body):
        if m.group("exp"):
            continue
        if m.group("p") == "(" and not _is_arrow(body, m.start("p")):
            continue
        out.append((body.count("\n", 0, m.start("n")), m.group("n")))
    return sorted(out)


def _blank(m):
    """把匹配到的注释换成等长空白，保留换行——这样剥完注释行号仍与原文一致。"""
    return re.sub(r"[^\n]", " ", m.group(0))


def strip_comments(text: str) -> str:
    text = HTML_COMMENT.sub(_blank, text)
    text = BLOCK_COMMENT.sub(_blank, text)
    return LINE_COMMENT.sub(_blank, text)


def word_uses(name: str, text: str) -> int:
    """独立单词出现次数；`.name` 属性访问（非 `...name` 展开）不计。"""
    n = 0
    for m in re.finditer(r"(?<![\w$])" + re.escape(name) + r"(?![\w$])", text):
        s = m.start()
        if s >= 1 and text[s - 1] == "." and not (s >= 3 and text[s - 3:s] == "..."):
            continue
        n += 1
    return n


def analyze(text: str):
    """返回 [(行号, 函数名)]：<script setup> 里定义了、全文再无第二处出现的函数。"""
    clean = strip_comments(text)
    found = []
    for sm in SCRIPT_BLOCK.finditer(clean):
        attrs = sm.group(1)
        if "setup" not in attrs:
            continue
        body_start = sm.start(2)
        base_line = clean.count("\n", 0, body_start)  # 0 起的 body 首行号
        for off, name in definitions(sm.group(2)):
            if word_uses(name, clean) <= 1:
                found.append((base_line + off + 1, name))
    return found


def read_allow(path):
    out = {}
    if not path:
        return out
    p = pathlib.Path(path)
    if not p.is_absolute():
        p = ROOT / p
    if not p.is_file():
        sys.exit(f"找不到放行清单：{p}")
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, _, reason = line.partition("#")
        out[key.strip()] = reason.strip() or "(未写理由)"
    return out


def selftest() -> int:
    ok = True

    # 1) 合成对照：四种形态各一，另配「该算有用」的三种形态
    synthetic_dead = """<template><div @click="used"/></template>
<script setup lang="ts">
function deadA() {}
async function deadB() {}
const deadC = () => {}
const deadD = async () => {}
// deadE 只在注释里被提到
const deadE = () => {}
function used() {}
</script>"""
    got = {n for _, n in analyze(synthetic_dead)}
    want = {"deadA", "deadB", "deadC", "deadD", "deadE"}
    if got != want:
        print(f"FAIL 合成对照（应红）：期望 {sorted(want)}，实得 {sorted(got)}")
        ok = False
    synthetic_live = """<template><button @click="inTpl" :x="fmt(1)"/></template>
<script setup lang="ts">
function inTpl() {}
const fmt = (v) => v
const viaExpose = () => {}
const spread = () => {}
function caller() { return [...spread] }
defineExpose({ viaExpose, caller })
</script>"""
    got = analyze(synthetic_live)
    if got:
        print(f"FAIL 合成对照（应绿）：误判 {got}")
        ok = False
    if analyze("<script setup>\nconst o = {}\nfunction go() {}\no.go()\n</script>") != [(3, "go")]:
        print("FAIL 合成对照：`o.go()` 属性访问不应算作对本地 go 的调用")
        ok = False

    # 2) 历史对照组：修复前的真实文件，loadEmrTemplates 必须红
    try:
        old = subprocess.run(
            ["git", "-C", str(ROOT), "show", f"{CONTROL_COMMIT}^:{CONTROL_PATH}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"FAIL 取不到历史对照文件 {CONTROL_COMMIT}^:{CONTROL_PATH}（浅克隆？CI 需 fetch-depth: 0）：{e}")
        return 1
    reds = {n for _, n in analyze(old)}
    if CONTROL_NAME not in reds:
        print(f"FAIL 对照组：{CONTROL_COMMIT}^ 的 DoctorStationView.vue 里 {CONTROL_NAME} 必须判红，实得 {sorted(reds)}")
        ok = False
    else:
        print(f"OK 对照组（应红）：{CONTROL_COMMIT}^ 的 {CONTROL_NAME} 判红")

    # 3) HEAD 同文件必须绿（至少 loadEmrTemplates 不得红）
    head = (ROOT / CONTROL_PATH).read_text(encoding="utf-8", errors="replace")
    reds = {n for _, n in analyze(head)}
    if CONTROL_NAME in reds:
        print(f"FAIL 对照组：HEAD 的 DoctorStationView.vue 里 {CONTROL_NAME} 不该判红（已被接上）")
        ok = False
    else:
        print(f"OK 对照组（应绿）：HEAD 的 {CONTROL_NAME} 判绿")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="对照组自检（历史文件必红、HEAD 必绿），失败退出非零")
    ap.add_argument("--allow", help="放行清单，行格式 `相对路径:函数名 # 理由`")
    args = ap.parse_args()

    if args.selftest:
        rc = selftest()
        print("selftest " + ("通过" if rc == 0 else "失败"))
        return rc

    if not FRONTEND.is_dir():
        sys.exit(f"找不到前端源码目录：{FRONTEND}")
    allow = read_allow(args.allow)

    reds, allowed, used_allow = [], [], set()
    files = sorted(FRONTEND.rglob("*.vue"))
    for f in files:
        rel = f.relative_to(ROOT).as_posix()
        for line, name in analyze(f.read_text(encoding="utf-8", errors="replace")):
            key = f"{rel}:{name}"
            if key in allow:
                allowed.append((rel, line, name, allow[key]))
                used_allow.add(key)
            else:
                reds.append((rel, line, name))
    print(f"扫描 {len(files)} 个 .vue，判红 {len(reds)} 处，已放行 {len(allowed)} 处。\n")
    if allowed:
        print("── 已放行（存量死函数，待主控清理）")
        for rel, line, name, why in allowed:
            print(f"   {rel}:{line}  {name}  # {why}")
        print()
    stale = sorted(set(allow) - used_allow)
    if stale:
        print("── 放行清单里有已不再判红的条目（函数已删或已接上），请删掉这些行")
        for k in stale:
            print(f"   {k}")
        print()
    if reds:
        print("── 定义了从未调用（同文件再无第二处提及）——接上它，或删掉它，或登记放行并写理由")
        for rel, line, name in reds:
            print(f"   {rel}:{line}  {name}")
        return 1
    if stale:
        return 1
    print("OK - 没有定义了从未调用的函数。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
