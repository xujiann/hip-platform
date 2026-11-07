# OFD 预览嵌入字型

使用 Noto Sans CJK SC Regular 2.004 的未修改静态 OpenType/CFF 文件。

- 官方来源为 notofonts/noto-cjk，标签 Sans2.004。
- 下载地址为 https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf 。
- 文件大小为 16,437,364 字节。
- SHA-256 为 2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b。
- 字型内版权信息为 © 2014-2021 Adobe (http://www.adobe.com/)。
- 许可证采用 SIL Open Font License 1.1，原文见同目录 OFL.txt，来源为 https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/LICENSE 。

OFD 预览会嵌入此字型。遇到该字型无法显示的字符时，生成操作返回错误，防止生成缺字版式文件。

当前采用的 OFDRW 2.3.9 版式引擎按 UTF-16 代码单元布局。实测补充平面字符（如“𠮷”，U+20BB7）虽在字型内存在，仍会生成不可靠的字形及间距。因此本版本在版式生成前明确拒绝补充平面字符；原始病历和元数据保持不变，后续需在兼容的版式引擎与阅读器验证通过后放开。
