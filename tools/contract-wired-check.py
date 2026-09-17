#!/usr/bin/env python3
"""契约对账：后端往返回体里放的键，前端有没有人消费。

**为什么有这个脚本**（v63 的实付学费）：
v63 三个车道各自都绿、CI 全绿、46 套 E2E 全绿，而合并后 `doneRemark` / `techDoneGateRule` /
`textFieldForms` / `fieldsCoverText` 四个新契约键在 `frontend/shell/src` **零引用**——
后端 javadoc 还白纸黑字写着「前端原样印这一个字符串，屏上与库内不可能再是两套口径」，
而没有任何前端印它。根因是那一轮按「后端车道／前端车道」横切，新契约是本轮才产生的，
持有前端文件的车道写任务书时它还不存在。**这道缝只有合并的人看得见，而合并的人（我）也漏了。**

于是把「定版检查单：本轮新增/改动的每个契约键都有真实消费方」从一句话变成一条能跑的命令。

用法：
    python tools/contract-wired-check.py                # 全量扫描，列出无人消费的键
    python tools/contract-wired-check.py --since ff45ae8  # 只看某个基线之后新增的键（合并后对账用这个）

**它不替你做判断**：无人消费不等于错。有些键本来就只给 E2E／CSV 导出／别的后端用。
所以默认只报告、退出码 0；加 --strict 才把「新增且无人消费」当失败（退出码 1），
供定版前当闸门用。允许在 tools/contract-wired-allow.txt 里按 `键名 # 理由` 登记豁免。
"""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRONTEND = ROOT / "frontend" / "shell" / "src"
ALLOW = ROOT / "tools" / "contract-wired-allow.txt"

# 只扫这些后端目录里的控制器：返回体键就是在这里 put 进去的
BACKEND_GLOBS = ["modules/*/src/main/java/**/web/*Controller.java"]

# body.put("xxx", ...) / m.put("xxx", ...) / one.put("xxx", ...) —— 本仓一律是这个形态
PUT_RE = re.compile(r'\b\w+\.put\(\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*,')

# 前端消费的形态：d.xxx / row.xxx / v.xxx / ['xxx'] / "xxx" / `xxx`
def consumed_in_frontend(key: str, blob: str) -> bool:
    return (
        re.search(r"[.\[]\s*['\"]?" + re.escape(key) + r"['\"]?\s*\]?", blob) is not None
        or re.search(r"\b" + re.escape(key) + r"\b", blob) is not None
    )


def read_allow():
    if not ALLOW.is_file():
        return {}
    out = {}
    for line in ALLOW.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, reason = line.partition("#")
        out[key.strip()] = reason.strip() or "(未写理由)"
    return out


def backend_files():
    files = []
    for g in BACKEND_GLOBS:
        files.extend(sorted(ROOT.glob(g)))
    return files


def keys_added_since(base: str) -> set:
    """只取 diff 里 + 行新增的 put 键——合并后对账时，我们关心的是本轮新产生的契约。"""
    try:
        diff = subprocess.run(
            ["git", "-C", str(ROOT), "diff", f"{base}..HEAD", "--", "modules"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
        ).stdout
    except subprocess.CalledProcessError as e:
        sys.exit(f"git diff 失败：{e}")
    added = set()
    for line in diff.splitlines():
        if line.startswith("+") and not line.startswith("+++"):
            added.update(PUT_RE.findall(line))
    return added


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", help="只看该基线之后新增的契约键（合并后对账用）")
    ap.add_argument("--strict", action="store_true", help="发现未豁免的无人消费键时退出码 1")
    args = ap.parse_args()

    if not FRONTEND.is_dir():
        sys.exit(f"找不到前端源码目录：{FRONTEND}")

    # 前端全文拼一块儿：只判「有没有人消费」，不需要精确到哪一行（那是 contract_wired 字段要写的）
    blob = []
    for p in FRONTEND.rglob("*"):
        if p.suffix in (".vue", ".ts", ".js") and p.is_file():
            blob.append(p.read_text(encoding="utf-8", errors="replace"))
    blob = "\n".join(blob)

    allow = read_allow()
    found = {}   # key -> [文件:行]
    for f in backend_files():
        rel = f.relative_to(ROOT).as_posix()
        for i, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for k in PUT_RE.findall(line):
                found.setdefault(k, []).append(f"{rel}:{i}")

    scope = keys_added_since(args.since) if args.since else set(found)
    targets = {k: v for k, v in found.items() if k in scope}

    unwired, allowed = [], []
    for k in sorted(targets):
        if consumed_in_frontend(k, blob):
            continue
        (allowed if k in allow else unwired).append(k)

    what = f"{args.since}..HEAD 新增的" if args.since else "全部"
    print(f"扫描 {len(backend_files())} 个控制器，{what}契约键 {len(targets)} 个。\n")

    if allowed:
        print(f"── 已登记豁免（{len(allowed)}）")
        for k in allowed:
            print(f"   {k}  # {allow[k]}")
        print()

    if not unwired:
        print("✓ 没有无人消费的契约键。")
        return 0

    print(f"── 前端零消费（{len(unwired)}）——要么本轮接上，要么删掉，要么写进 {ALLOW.name} 说明理由")
    for k in unwired:
        where = targets[k]
        print(f"   {k}")
        for w in where[:3]:
            print(f"       后端：{w}")
        if len(where) > 3:
            print(f"       …另有 {len(where) - 3} 处")
    print()
    if args.strict:
        print("--strict：判为失败。")
        return 1
    print("（默认只报告。无人消费不等于错——有些键只给 E2E / CSV 导出 / 别的后端用。加 --strict 当闸门。）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
