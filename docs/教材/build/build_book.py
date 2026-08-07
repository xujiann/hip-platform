# -*- coding: utf-8 -*-
"""合并教材 md -> 单一 pandoc 输入；抽取 mermaid 为 .mmd 待渲染；清理仓库内部链接。"""
import re, os, io

SRC = r"C:\Users\drxuj\医院信息化\docs\教材"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "book")
os.makedirs(os.path.join(OUT, "mmd"), exist_ok=True)

ORDER = [
    "第0章-前言与导读.md",
    "第1章-医院信息化概论与政策环境.md",
    "第2章-医院业务流程全景.md",
    "第3章-标准与规范.md",
    "第4章-总体架构与技术选型.md",
    "第5章-平台底座.md",
    "第6章-集成平台.md",
    "第7章-门急诊系统.md",
    "第8章-住院与护理.md",
    "第9章-医技系统.md",
    "第10章-医保接口与DRG.md",
    "第11章-临床数据中心与患者360.md",
    "第12章-数据治理质控与统计上报.md",
    "第13章-安全合规与运维.md",
    "第14章-产品化与实施方法论.md",
    "附录A-AI在医院信息化中的应用.md",
    "附录B-实训环境搭建指南.md",
    "附录C-术语表.md",
    "后记.md",
]

PAGEBREAK = '\n\n```{=openxml}\n<w:p><w:r><w:br w:type="page"/></w:r></w:p>\n```\n\n'

mermaid_count = 0
parts = []
for idx, name in enumerate(ORDER):
    with io.open(os.path.join(SRC, name), "r", encoding="utf-8") as f:
        text = f.read()

    # 抽取 mermaid 块 -> mmd 文件 + 图片占位
    def repl_mermaid(m):
        global mermaid_count
        mermaid_count += 1
        mid = "fig%03d" % mermaid_count
        with io.open(os.path.join(OUT, "mmd", mid + ".mmd"), "w", encoding="utf-8") as g:
            g.write(m.group(1).strip() + "\n")
        return "![](%s)\n" % (OUT.replace("\\", "/") + "/mmd/" + mid + ".png")

    text = re.sub(r"```mermaid\s*\n(.*?)```", repl_mermaid, text, flags=re.S)

    # 去掉指向仓库文件/章节文件的本地链接，仅保留链接文字（不动 http 链接与图片）
    text = re.sub(r"(?<!\!)\[([^\]]+)\]\((?!https?://)[^)]+\)", r"\1", text)

    parts.append(text.strip() + "\n")

book = PAGEBREAK.join(parts)
with io.open(os.path.join(OUT, "book.md"), "w", encoding="utf-8") as f:
    f.write(book)

print("files=%d mermaid=%d chars=%d" % (len(ORDER), mermaid_count, len(book)))
print("out=" + os.path.join(OUT, "book.md"))
