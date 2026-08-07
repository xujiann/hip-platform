// 《医院信息化：原理、架构与工程实践》送审稿排版模板（Typst 0.15）
#let songti = ((name: "Times New Roman", covers: "latin-in-cjk"), "SimSun")
#let heiti = ((name: "Arial", covers: "latin-in-cjk"), "Microsoft YaHei")
#let codefont = ("Consolas", "Microsoft YaHei")

#set document(title: "医院信息化：原理、架构与工程实践（送审稿 v0.1）")

// pandoc 片段所需的分隔线
#let horizontalrule = align(center, v(4pt) + line(length: 34%, stroke: 0.5pt + luma(170)) + v(4pt))

// ===== 页面 =====
#set page(
  paper: "a4",
  margin: (top: 2.6cm, bottom: 2.4cm, left: 2.5cm, right: 2.5cm),
  header: context {
    let pg = here().page()
    let opens = query(heading.where(level: 1)).filter(h => h.location().page() == pg)
    if opens.len() > 0 { return none }
    let hs = query(selector(heading.where(level: 1)).before(here()))
    if hs.len() > 0 {
      set text(font: heiti, size: 8pt, fill: luma(100))
      align(center)[#hs.last().body]
      v(-6pt)
      line(length: 100%, stroke: 0.4pt + luma(200))
    }
  },
  footer: context {
    align(center, text(font: songti, size: 9pt, counter(page).display("1")))
  },
)

// ===== 正文 =====
#set text(font: songti, size: 10.5pt, lang: "zh", region: "cn")
#set par(justify: true, leading: 0.85em, spacing: 1.1em,
         first-line-indent: (amount: 2em, all: true))

// 列表、表格、代码、图内取消首行缩进
#show list: set par(first-line-indent: 0em)
#show enum: set par(first-line-indent: 0em)
#show table: set par(first-line-indent: 0em)
#show figure: set par(first-line-indent: 0em)
#show raw.where(block: true): set par(first-line-indent: 0em)

// ===== 标题 =====
#show heading: set text(font: heiti, weight: "bold")
#show heading.where(level: 1): it => {
  pagebreak(weak: true)
  v(5em)
  set text(size: 21pt)
  it
  v(4pt)
  line(length: 100%, stroke: 1.2pt + rgb("#2F5496"))
  v(2.5em)
}
#show heading.where(level: 2): it => { v(1.1em, weak: true); text(size: 14pt, fill: rgb("#2F5496"), it.body); v(0.8em, weak: true) }
#show heading.where(level: 3): it => { v(0.9em, weak: true); text(size: 11.5pt, it.body); v(0.6em, weak: true) }

// ===== 案例框（引用块）=====
#show quote.where(block: true): it => {
  set text(size: 10pt)
  block(width: 100%, fill: rgb("#F4F7FB"),
        stroke: (left: 2.5pt + rgb("#2F5496")),
        inset: (left: 14pt, right: 12pt, top: 10pt, bottom: 10pt),
        radius: (top-right: 3pt, bottom-right: 3pt),
        it.body)
}

// ===== 代码 =====
#show raw: set text(font: codefont)
#show raw.where(block: true): it => {
  set text(size: 8.5pt)
  block(width: 100%, fill: luma(248), stroke: 0.4pt + luma(215),
        radius: 3pt, inset: 9pt, it)
}
#show raw.where(block: false): it => box(fill: luma(245), inset: (x: 3pt, y: 0pt), outset: (y: 3pt), radius: 2pt, it)

// ===== 表格 =====
#set table(stroke: 0.4pt + luma(190), inset: (x: 7pt, y: 5pt))
#show table: set text(size: 9pt)
#show table.cell.where(y: 0): set text(font: heiti, weight: "bold")

// ===== 图片 =====
#show figure: it => { v(0.6em, weak: true); it; v(0.6em, weak: true) }

// ===== 扉页 =====
#page(header: none, footer: none)[
  #v(4.5cm)
  #align(center)[
    #text(font: heiti, size: 26pt, weight: "bold")[医院信息化]
    #v(0.2em)
    #text(font: heiti, size: 17pt)[原理、架构与工程实践]
    #v(2.2cm)
    #line(length: 42%, stroke: 0.8pt + rgb("#2F5496"))
    #v(0.8cm)
    #text(font: songti, size: 12pt)[以 HIP 平台为贯穿案例]
    #v(0.4cm)
    #text(font: songti, size: 11pt, fill: luma(90))[送审稿 · v0.1]
    #v(0.3cm)
    #text(font: songti, size: 11pt, fill: luma(90))[2026 年 8 月]
  ]
  #v(1fr)
  #align(center)[
    #text(font: songti, size: 9pt, fill: luma(120))[本稿为未定稿送审版本，稿内【成稿核对】标注处待正式出版前逐条核定，请勿引用。]
  ]
]

// ===== 目录 =====
#{
  set page(header: none)
  set par(first-line-indent: 0em)
  show outline.entry.where(level: 1): it => {
    v(11pt, weak: true)
    text(font: heiti, weight: "bold", size: 10.5pt, it)
  }
  outline(title: text(font: heiti, size: 18pt)[目　录], depth: 2, indent: auto)
  pagebreak(weak: true)
}

// 正文从第 1 页重新计数
#counter(page).update(1)

#include "body.typ"
