# -*- coding: utf-8 -*-
import os, typst
wd = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(wd, "book-typst.pdf")
typst.compile(os.path.join(wd, "main.typ"), output=out, root=wd)
print("ok", os.path.getsize(out))
