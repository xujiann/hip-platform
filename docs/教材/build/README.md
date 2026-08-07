# 教材构建流水线

从 docs/教材/*.md 生成 Word 排版稿与 Typst 送审 PDF。

依赖：pandoc 3.x ｜ Node（本地安装 @mermaid-js/mermaid-cli）｜ Python（pip install typst pymupdf）｜ Word（可选，仅用于更新 docx 目录域）。

```bash
# 1. 合并章节 + 抽取 mermaid（输出 book/book.md 与 book/mmd/*.mmd）
python build_book.py
# 2. 渲染全部 mermaid 图（用本地 node_modules/.bin/mmdc，参数 -b white -w 900 -s 2）
# 3. Word 版：pandoc book.md -o 排版稿.docx --reference-doc=ref-cn.docx --toc -M toc-title=目录 ...
#    （ref-cn.docx = pandoc 默认 reference.docx 将 theme 的 ea 字体改为 微软雅黑/宋体）
# 4. PDF 版：
python prep_typst.py                                   # 图片改相对路径、去 openxml 分页
pandoc book_typ.md -f markdown-citations -t typst -o body.typ   # 必须关 citations（@注解误判）
python post_typst.py                                   # 裸图 -> figure(width:74%)，前置 horizontalrule 定义
python compile_typst.py                                # typst 编译出 book-typst.pdf
```

排版样式集中在 main.typ（扉页/目录/页眉页脚/案例框/代码/表格），改样式只动它。
路径按实际环境调整（脚本内以自身所在目录为工作目录）。
