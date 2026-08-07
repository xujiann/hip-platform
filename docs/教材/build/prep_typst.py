# -*- coding: utf-8 -*-
"""book.md -> book_typ.md：图片改相对路径、去掉 openxml 分页块（typst 模板自行分页）。"""
import io, os, re
wd = os.path.dirname(os.path.abspath(__file__))
t = io.open(os.path.join(wd, "book.md"), encoding="utf-8").read()
t = re.sub(r"!\[\]\([^)]*?/mmd/(fig\d+\.png)\)", r"![](mmd/\1)", t)
t = re.sub(r"```\{=openxml\}.*?```\n?", "", t, flags=re.S)
io.open(os.path.join(wd, "book_typ.md"), "w", encoding="utf-8").write(t)
print("chars=%d imgs=%d" % (len(t), len(re.findall(r"mmd/fig", t))))
