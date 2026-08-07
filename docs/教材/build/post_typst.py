# -*- coding: utf-8 -*-
"""body.typ 后处理：裸图片 box -> 居中 figure（统一宽度）。"""
import io, os, re
wd = os.path.dirname(os.path.abspath(__file__))
t = io.open(os.path.join(wd, "body.typ"), encoding="utf-8").read()
t, n = re.subn(r'#box\(image\("(mmd/fig\d+\.png)"\)\)',
               r'#figure(image("\1", width: 74%))', t)
HR = '#let horizontalrule = align(center, v(4pt) + line(length: 34%, stroke: 0.5pt + luma(170)) + v(4pt))\n'
if not t.startswith("#let horizontalrule"):
    t = HR + t
io.open(os.path.join(wd, "body.typ"), "w", encoding="utf-8").write(t)
print("figures:", n)
