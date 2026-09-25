# C · 动态版式（Kinetic）

状态：2026-09-25 作为备用方案保存，未采用。本文记录画板上的全部取值，方便日后原样恢复；标"提案"的部分没有画板。对比度都按 WCAG 公式计算。

## 1. 概念

**让字自己说话。** 超大字号、强对比，再加上动效。

- 标题就是界面：关键词可以点，格式名在跑马灯里流动，曲库用首字母格筛选。
- 瑞士式网格：字重对比极大的无衬线字，粗分隔线，直角或小圆角。
- 官网主标题：*Plays lossless offline. Shows the path.* 其中 lossless、offline、path 三个词可以点。

适用范围与 B 相同：官网和 App 共用。画板里正在播放页用深色、曲库用浅色，用来展示两种底色下的表现。

## 2. 色彩

### 2.1 令牌

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `off-white` | `#EDEDE6` | 浅色底 |
| `carbon` | `#0F0F0E` | 深色底；浅色底上的文字 |
| `carbon-raised` | `#1B1B19` | 深色底上的分段控件底、封面占位 |
| `carbon-line` | `#2A2A27` | 深色底上的分隔线、进度轨道 |
| `muted-light` | `#55554E` | 浅色底上的次要文字 |
| `muted-dark` | `#A3A39A`、`#BDBDB4` | 深色底上的次要文字 |
| `acid` | `#D3EE6A` | 强调填充：关键词底、播放键、ACTIVE、选中页签；在深色底上也可作强调文字 |
| `acid-hover` | `#E4F59A` | 深色底上的链接悬停 |
| `chip-outline` | `#6A6A63` | 深色底上 SYSTEM MIXED 的描边 |
| `key-border` | `#B9B9B0` | 首字母格可用态的边框 |
| `key-disabled` | `#9C9C94` | 首字母格不可用态的文字 |
| 标识 | 浅色底用主版（`#1B1C18` 加 `#9FBF4B`） | 构造与用法不变 |

`acid` 不是现有品牌色。它是 Moss Bright `#BFD66B` 的更亮版本，色相相同：OKLCH L 0.903 · C 0.160 · h 119（Moss Bright 是 L 0.836 · C 0.136 · h 120）。采用 C 之前，要先把它加入品牌色板，并重新界定 Signal Budget。

### 2.2 对比度

| 组合 | 对比度 |
| --- | --- |
| `carbon` / `off-white` | 16.3:1 |
| `muted-light` / `off-white` | 6.4:1 |
| `off-white` / `carbon` | 16.3:1 |
| `#A3A39A` / `carbon` | 7.6:1 |
| `#BDBDB4` / `carbon` | 10.1:1 |
| `acid` / `carbon`，以及 `carbon` / `acid` | 14.8:1 |
| `off-white` / `carbon-raised` | 14.7:1 |
| `chip-outline` / `carbon`（图形） | 3.5:1 |
| `key-border` / `off-white`（图形） | 1.7:1，低于非文字元素的 3:1 |
| `key-disabled` / `off-white` | 2.4:1。禁用控件不受对比度要求约束 |
| `acid` / `off-white` | 1.1:1，**禁止**这样用作文字 |

首字母格的可用态目前主要靠字母本身（16.3:1）来识别。采用 C 时应把 `key-border` 加深到 3:1 以上。

## 3. 字体

| 用途 | 拉丁 | 中文 | 画板规格 |
| --- | --- | --- | --- |
| 官网主标题 | Bricolage Grotesque 800 | — | 156 px，行高 0.9，字距 −0.05em |
| App 歌名 | Bricolage Grotesque 800 | 思源黑体 900 | 52 px，行高 0.94，字距 −0.04em |
| App 页面标题 | — | 思源黑体 900 | 64 px（"曲库"），字距 −0.02em |
| 格式数字 | Bricolage Grotesque 800 | — | 44 px，行高 0.9 |
| 曲序（描边） | Bricolage Grotesque 800 | — | 104 px，行高 0.8，1.5 px 描边，透明填充 |
| 首字母大字 | Bricolage Grotesque 800 | — | 128 px，行高 0.8 |
| 列表名称 | Bricolage Grotesque 800 | 思源黑体 | 22 px，行高 1.1 |
| 官网字标 | Bricolage Grotesque 800 | — | 24 px，字距 −0.03em |
| 正文与控件 | Hanken Grotesk 400–700 | 思源黑体 | 15–16 px |
| 标签、眉题 | Hanken Grotesk 600–700，大写 | — | 11–14 px，字距 0.12–0.24em |

授权：Bricolage Grotesque、Hanken Grotesk、Noto Sans SC 都使用 SIL OFL 1.1。App 端，很多手机的系统中文字体没有 900 这样的特粗字重，要打包思源黑体的一个粗字重，或者接受系统粗体。

## 4. 版式

### 4.1 官网（画板宽 1440）

- 左右边距 64 px。页眉内边距 26 px 64 px：标识和字标、导航（How it works、Privacy、Support、中文，间距 32 px，字重 600）、主按钮。
- 首屏区高 580 px。眉题一行两端对齐：左"Offline music player — Android 8.0+"，右"Tap a highlighted word"。主标题三行。
- 说明卡：`carbon` 底，宽 420 px，位于右 64、上 360，圆角 22 px，内边距 26 px 28 px，正好落在第三行右侧的空白里。
- 跑马灯带：高 104 px，`carbon` 底，60 px 字重 800 的格式名，间隔是 16 px 的 `acid` 菱形，间距 40 px。
- 底部三栏原则：栏间距 48 px，编号 56 px。

### 4.2 App 正在播放（深色，390 × 844）

- 左右边距 24 px。顶部 44 px 留给系统状态栏。
- 顶栏 48 px，居中是"正在播放"，12 px、字重 700、字距 0.24em。
- 封面 168 × 168，贴右边缘、无圆角。左侧是描边曲序和"/ 03"。
- 歌名 52 px，最多两行。下面是"艺术家 — 专辑"，大写、字距 0.14em。
- 格式数字行：左侧编码标签，加"24 BIT""96 KHZ"这样的大数字；右侧"输出 / USB DAC"。
- 6 px 粗进度条，已播放部分用 `acid`。
- 控制：随机和循环 44 px；上一首、下一首 56 px；播放键是 72 px 的 `acid` 圆形。
- 底部一组贴底：状态标签、一句说明、"系统输出 / 严格 USB"分段控件。
- 底部导航 80 px。

### 4.3 App 曲库（浅色，390 × 844）

- 标题"曲库"64 px，右侧是搜索和排序图标按钮。
- 页签 17 px：选中项 `acid` 底、字重 800，其余字重 500。
- 首字母格：7 列 × 4 行，每格高 44 px，间距 5 px，内含 A–Z 和 #。
- 结果区：左侧 128 px 大字母（宽 96 px），右侧列表。列表行高 64 px，下边是 2 px 的 `carbon` 线。
- 迷你播放条：`carbon` 卡片，右侧 48 px `acid` 播放键。
- 底部导航顶部是一条 2 px 的 `carbon` 线。

### 4.4 圆角

封面 0；状态标签和页签 4 px；首字母格 6 px；卡片 14–22 px；按钮全圆。

## 5. 组件

- **关键词标记**：关键词是真实按钮。字体继承标题，背景是 `acid` 纯色条，位置 `0 88%`。未选中时尺寸 `100% 16%`，像荧光笔下划线；选中或悬停时 `100% 100%`，整词涂满。
- **说明卡**：眉题用 `acid`（12 px、字距 0.16em），主句用 Bricolage 26 px、字重 700，补充一句 15 px、`#BDBDB4`。卡片带 `aria-live="polite"`。
- **跑马灯**：内容复制两份，整体平移 −50% 实现无缝循环。悬停暂停，对读屏隐藏；同样的格式信息在 lossless 说明卡里有文字版。
- **主按钮（官网）**：`carbon` 胶囊，高 48 px，右端嵌一个 36 px 的 `acid` 圆形箭头；悬停时上移 2 px。
- **描边曲序**：只作装饰，对读屏隐藏。
- **格式数字**：编码标签 11 px，数字 44 px，单位 12 px。单位统一大写。
- **输出分段控件**：两格，高 44 px，外框圆角 14 px，`carbon-raised` 底。选中格 `off-white` 底、`carbon` 字。
- **状态标签**（高 30 px，圆角 4 px，12 px、字重 800、字距 0.08em）：
  - SYSTEM MIXED：`chip-outline` 1.5 px 描边加合流图标，不用 `acid`。
  - REQUESTED：`acid` 描边，`acid` 文字，加呼吸的空心圆点。
  - ACTIVE：`acid` 实心，`carbon` 文字，加 `carbon` 实心圆点。
  - AVAILABLE 和 VERIFIED（提案）：`acid` 描边加空心点；`acid` 描边加盾牌图标，并附验证记录的上下文。
- **首字母格**：选中格为 `carbon` 底、`off-white` 字；可用格为 `carbon` 字加 `key-border` 边框；不可用格用禁用状态，没有边框。
- **列表行**：名称 22 px、字重 800，元信息 13 px，下边 2 px 的 `carbon` 线。
- **底部导航**：深色版选中项是 `acid` 胶囊加 `carbon` 图标；浅色版选中项是 `carbon` 胶囊加 `off-white` 图标。选中文字字重 800。

## 6. 交互

- **官网**：点 lossless、offline 或 path，这个词被涂满，说明卡换成对应内容，默认显示 path。跑马灯悬停时暂停。
- **正在播放**：切歌时歌名重新入场，封面从右侧滑入。严格 USB 模式下切歌，先显示约 1.4 s 的 REQUESTED 再变成 ACTIVE（画板里的模拟）。"系统输出"和"严格 USB"可以互相切换，状态随之变为 SYSTEM MIXED 或经过 REQUESTED 变为 ACTIVE。
- **曲库**：页签切换数据集。首字母格按当前视图标出可用字母，点字母就筛选出对应条目。切换页签时，如果当前字母在新视图里可用就保留，否则用该视图的默认字母（歌曲 G、专辑 L、艺术家 M、文件夹 M、流派 J、播放列表 S）。中文条目按拼音首字母归类。

## 7. 动效

| 动效 | 时长 | 缓动 | 说明 |
| --- | --- | --- | --- |
| 主标题逐行入场 | 900 ms | `cubic-bezier(.16, 1, .3, 1)` | 上移 40 px 并淡入，三行延迟 0.05、0.15、0.25 s |
| 关键词标记 | 450 ms | `cubic-bezier(.22, 1, .36, 1)` | 过渡背景尺寸 |
| 说明卡切换 | 500 ms | `cubic-bezier(.16, 1, .3, 1)` | 上移 16 px，从 0.98 缩放到 1 |
| 跑马灯 | 32 s 一圈 | 线性 | 悬停暂停 |
| 标识描线 | 1400 ms，延迟 0.2 s | `cubic-bezier(.22, 1, .36, 1)` | 只做描线出现 |
| 封面切换 | 550 ms | `cubic-bezier(.16, 1, .3, 1)` | 从右侧 24 px 滑入 |
| 歌名切换 | 600 ms | 同上 | 上移 18 px |
| REQUESTED 呼吸 | 1.2 s 一周期 | ease-in-out | 透明度 1 → 0.25 → 1 |
| 首字母切换 | 大字母 500 ms，列表 450 ms | 同上 | 大字母上移 24 px，列表从右侧 12 px 滑入 |
| 播放键悬停 | 200 ms | ease | 放大到 1.04 |

系统开启"减少动态效果"时关闭全部动画，跑马灯停止。

## 8. 文案

官网英文（画板，草稿）：

- 眉题：Offline music player — Android 8.0+；提示：Tap a highlighted word
- 主标题：Plays lossless / offline. Shows / the path.
- 说明卡：
  - LOSSLESS：FLAC, ALAC, WAV and AIFF — plus MP3, AAC, Ogg Vorbis and Opus. / Vesqen reads each file's format and shows you exactly what it found.
  - OFFLINE：No account. No streaming. Playback never touches the network. / Your library, playlists and listening history stay on the phone.
  - PATH：Source, decoder, AudioTrack, route — each with how it is known. / Measured, derived or estimated. When a value is missing, Vesqen says why.
- 跑马灯：FLAC 24/96、ALAC 16/44.1、WAV 24/192、AIFF 24/48、Opus、MP3 320、Ogg Vorbis、AAC 256
- 三条原则：01 Offline / 02 Evidence / 03 Fails closed

App 中文：正在播放、输出、USB DAC、系统输出、严格 USB，以及三种状态说明：

- "Android 可能混音、重采样或应用系统处理。"
- "正在重建 AudioTrack，核对 mixer 与路由。"
- "AudioTrack、mixer 与路由一致；非外部验证。"

格式清单与 PRD 一致：无损 FLAC、ALAC、WAV、AIFF；有损 MP3、AAC/M4A、Ogg Vorbis、Opus。

## 9. 无障碍

- 对比度见 §2.2。`acid` 只作填充，或者在 `carbon` 上作文字。
- 触控目标：首字母格 44 px，分段控件 44 px，播放键 72 px。
- 焦点环：浅色底 3 px `carbon`，深色底 3 px `acid`。
- 关键词按钮带 `aria-pressed`；跑马灯和描边曲序对读屏隐藏；不可用的字母用禁用状态。
- 遵守"减少动态效果"（§7）。

## 10. 实现要点

- **App（Compose）**：描边数字可用 `TextStyle` 的 `drawStyle = Stroke(...)`，跑马灯可用 `Modifier.basicMarquee()`，关键词涂满可用 `drawBehind` 画背景条。实现时先核对当前 Compose 版本里这些 API 是否稳定。
- **官网**：按画板用 CSS 实现。跑马灯内容复制两份，平移 −50%；关键词标记用 `background-size` 过渡。

## 11. 风险

- 中文长标题在 52 px 下会频繁换行，需要最大行数和降级字号的规则。
- 荧光绿配巨型无衬线字，最近在 AI 公司官网上很常见，时间久了容易显得跟风。
- `acid` 不在品牌色板里，Signal Budget（Moss 面积约 10%）需要重新界定。
- 超大字号在小屏幕和系统大字体设置下，适配成本高。
- 标签和艺术家名大量使用全大写，与视觉识别 §5"不用全大写营造品牌声量"冲突，采用 C 时要修改这条规则。

## 12. 画板

[mockups/](mockups/) 里的 C 相关文件：

- `C-Web.dc.html`：官网首屏。点三个关键词，悬停跑马灯。
- `C-Now.dc.html`：正在播放。切换输出模式，切歌。
- `C-Library.dc.html`：曲库。点首字母格和页签。
