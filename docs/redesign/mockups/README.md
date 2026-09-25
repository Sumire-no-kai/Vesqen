# 画板源文件

这些文件是设计画布"Vesqen 视觉方向"（<https://claude.ai/artifact/Jm4AVHe4iEduAQoMXtVFzd>）中 B 和 C 两个方向的画板源码，于 2026-09-25 从画布原样保存。

| 文件 | 方向 | 画面 | 尺寸 |
| --- | --- | --- | --- |
| `B-Web.dc.html` | B · 纸与声 | 官网首屏（英文） | 1440 × 1024 |
| `B-Now.dc.html` | B · 纸与声 | 正在播放（中文） | 390 × 844 |
| `B-Library.dc.html` | B · 纸与声 | 曲库（中文） | 390 × 844 |
| `C-Web.dc.html` | C · 动态版式 | 官网首屏（英文） | 1440 × 1024 |
| `C-Now.dc.html` | C · 动态版式 | 正在播放（中文） | 390 × 844 |
| `C-Library.dc.html` | C · 动态版式 | 曲库（中文） | 390 × 844 |

注意：

- 格式是画布的 Design Component（`.dc.html`）：`{{…}}` 占位由文件末尾的脚本计算，`<sc-for>`、`<sc-if>` 是画布运行时的循环和条件。它依赖画布提供的 `support.js`，直接用浏览器打开无法正常显示。要看可交互版本，请打开画布；这里的文件用来保存精确取值，并在需要时重新导入画布。
- 字体从 Google Fonts 加载，全部使用 SIL OFL 1.1 授权。
- 歌曲、艺术家、专辑和封面全部是虚构的，封面是内联的抽象矢量图。
- 设计说明见上一级的 [README](../README.md)、[B](../B_PAPER_AND_SOUND.md) 和 [C](../C_KINETIC.md)。
