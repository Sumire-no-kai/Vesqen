# B · 纸与声（Paper & Sound）

状态：2026-09-25 选定，作为官网和 App 重设计（#35）的主方向。本文记录画板上的全部取值；标"提案"的部分还没有经过画板验证。对比度都按 WCAG 公式计算。

## 1. 概念

**每首歌都附带"链路注记"（liner notes）。**

- 唱片内页记录录音、母带和制作信息。Vesqen 把"文件 → 解码器 → AudioTrack → 路由"这条证据链当作同一类信息，安静地附在歌曲旁边，需要时再展开。
- 暖纸色底、衬线标题、细线分隔，像印刷品而不是仪表盘。
- 封面决定环境色：正在播放页随当前专辑轻微染色，除背景外一切保持不变。
- 官网主标题：*Every track comes with liner notes.*

适用范围：官网全部页面；App 的正在播放、曲库和专辑详情。链路页和设置页沿用同一套组件，画板待补。

## 2. 色彩

### 2.1 浅色令牌（画板）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `paper` | `#F3EFE6` | 页面底色，无封面时的正在播放背景 |
| `paper-raised` | `#FBF9F4` | 卡片：官网链路注记卡、专辑详情卡、迷你播放条、选中的专辑按钮 |
| `paper-nav` | `#F6F3EC` | App 底部导航 |
| `ink` | `#1A1A16` | 主文字、图标 |
| `ink-body` | `#3F3C35` | 官网正文段落 |
| `ink-muted` | `#5E5A50` | 次要文字、元信息、次要图标 |
| `hairline` | `#D8D1C1` | 官网分隔线、选中按钮的边框 |
| `hairline-soft` | `#E2DBCB`、`#E6DFD0`、`#DDD6C6` | 卡片内行分隔、曲目行、导航顶线 |
| `hairline-app` | `ink` 的 10%–16% 透明度 | App 内分隔线，叠在染色背景上也成立 |
| `chip-neutral` | 官网 `#ECE7DB`；App 用 `ink` 的 7% 透明度 | SYSTEM MIXED 等中性标签的底 |
| `moss-deep` | `#536B1E` | 主按钮、播放键、进度、ACTIVE 标签 |
| `moss-deep-hover` | `#43581A` | 主按钮悬停 |
| `on-moss` | `#FFFFFF` | moss-deep 上的文字和图标 |
| 标识 | 外层 `#1B1C18`，内层 `#9FBF4B` | Twin Paths 主版，不变 |

### 2.2 对比度

| 组合 | 对比度 |
| --- | --- |
| `ink` / `paper` | 15.2:1 |
| `ink-body` / `paper` | 9.6:1 |
| `ink-muted` / `paper` | 6.0:1 |
| `ink-muted` / `paper-raised` | 6.5:1 |
| `ink-muted` / `paper-nav` | 6.2:1 |
| `moss-deep` 作文字 / `paper` | 5.2:1 |
| 白 / `moss-deep` | 6.0:1（悬停色 7.9:1） |
| `ink` / `chip-neutral` | 14.1:1 |
| `ink` / 按 §2.3 规则生成的任意染色 | ≥ 13.4:1 |
| `ink-muted` / 按 §2.3 规则生成的任意染色 | ≥ 5.27:1 |
| `ink` / 染色背景上的 7% 中性标签 | ≥ 11.4:1 |

画板里手选的 Copper 染色 `#EEDDCC` 亮度略低于规则（L 0.907），在它上面 `ink` 是 13.2:1，`ink-muted` 是 5.2:1，仍然达标。

标识内层 `#9FBF4B` 在纸色上只有 1.8:1。这是品牌主版的既定用法，标识不承载信息。

### 2.3 封面取色：正在播放页的背景

正在播放页的背景跟随当前专辑变化，这是 B 最有辨识度的地方。

1. **只染背景。** 文字、moss-deep 的播放键和进度、证据状态标签、分隔线、底部导航和迷你播放条都不随封面变化。状态的含义永远不依赖专辑颜色。
2. **取色算法（提案）：**
   - 封面解码后缩小到最长边不超过 64 px，转换到 OKLCH。
   - 丢弃近黑、近白和灰色像素：L < 0.2、L > 0.95 或 C < 0.02。剩下的不足 8% 时，按无彩色封面处理，背景用 `paper`。
   - 按色相分 24 档（每档 15°），以彩度加权统计，取最高的一档，得到主色相 h 和这一档的彩度中位数 C。
   - 浅色背景 = `oklch(0.915, clamp(0.25 × C, 0.008, 0.030), h)`。亮度固定、彩度封顶，所以全部 360° 色相下，`ink-muted` 的对比度最低也有 5.27:1，`ink` 最低 13.4:1。
3. **没有封面或解码失败：** 用 `paper`。灰度封面会自然落到接近中性的纸色。
4. **切歌过渡：** 背景颜色用 800 ms 过渡，缓动 `cubic-bezier(0.22, 1, 0.36, 1)`。系统关闭动画时直接切换。
5. **缓存：** 按专辑在内存里缓存计算结果（LRU）。取色在封面加载完成后于后台线程完成，不需要修改曲库数据库。
6. **同一规则还用于：** App 专辑详情（在曲库选中专辑时整页染色），以及官网首屏的专辑区。

画板里的染色是手工选的，接近上述规则（Copper 的亮度略低）：

| 专辑（虚构） | 背景 | OKLCH |
| --- | --- | --- |
| Low Tide Archive（深青封面） | `#DCE5E0` | L 0.914 · C 0.012 · h 162 |
| Copper Hours（铜色封面） | `#EEDDCC` | L 0.907 · C 0.030 · h 67 |
| 盐与雾（雾灰封面） | `#E1E4DC` | L 0.914 · C 0.011 · h 124 |
| Night Transit（夜色封面） | `#E2E2DF` | L 0.912 · C 0.004 |
| 空港（天蓝封面） | `#DCE3E7` | L 0.912 · C 0.009 · h 232 |

官网首屏另有一组"专辑墨色"，只用于主标题里的斜体词：Low Tide `#1F5A57`（6.9:1）、Copper `#9A4A22`（5.4:1）、盐与雾 `#46524E`（7.1:1）。

### 2.4 深色（提案，未经画板验证）

跟随系统深浅色，取代现行正在播放页固定使用的 Nocturne Graphite。

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `night` | `#151411` | 暖调近黑。`night-text` 在其上 15.1:1 |
| `night-raised` | `#1E1C18` | 卡片。`night-muted` 在其上 6.7:1 |
| `night-text` | `#EDE8DC` | 主文字 |
| `night-muted` | `#A8A294` | 次要文字，在 `night` 上 7.3:1 |
| `moss-bright` | `#BFD66B` | 深色下的主操作和 ACTIVE。作文字 11.4:1；实心底配 `night` 文字 11.4:1 |
| 深色染色 | `oklch(0.21, clamp(0.20 × C, 0.006, 0.024), h)` | 任何色相下 `night-muted` 都 ≥ 6.9:1 |

### 2.5 证据状态

| 状态 | 浅色 | 深色（提案） |
| --- | --- | --- |
| SYSTEM MIXED | 中性底加合流图标，文字用 `ink`；不用 Moss | `night-raised` 底，文字用 `night-text` |
| BIT-PERFECT AVAILABLE（提案） | `moss-deep` 1.5 px 描边加空心点 | `moss-bright` 描边 |
| BIT-PERFECT REQUESTED（提案） | 描边加呼吸的空心点 | 同左 |
| BIT-PERFECT ACTIVE | `moss-deep` 实心，白点白字 | `moss-bright` 实心，`night` 文字 |
| BIT-PERFECT VERIFIED（提案） | 描边加盾牌图标，必须同时给出验证记录的上下文 | 同左 |

颜色不能单独提升声明等级。描边或实心、图标、文字共同区分状态，与现行规范一致。

## 3. 字体

| 用途 | 拉丁 | 中文 | 画板规格 |
| --- | --- | --- | --- |
| 官网主标题 | Instrument Serif 400 | 思源宋体 | 104 px，行高 0.94，字距 −0.02em；强调词用斜体 |
| 官网字标 | Instrument Serif | — | 30 px |
| 官网小标题 | Instrument Serif | 思源宋体 | 26 px，行高 1.1 |
| 链路注记卡标题 | Instrument Serif Italic | — | 26 px |
| 正文 | Instrument Sans 400 | 思源黑体 | 官网 19 px / 1.6；卡片 15 px / 1.55；元信息 13 px |
| 眉题、标签 | Instrument Sans 500，大写 | — | 12–13 px，字距 0.12–0.16em |
| App 页面标题 | — | 思源宋体 600 | 32 px（"曲库"） |
| App 歌名 | Instrument Serif | 思源宋体 | 34 px / 1.12 |
| App 专辑名 | Instrument Serif | 思源宋体 | 详情 28 px / 1.1；书架 17 px / 1.15；列表 21 px / 1.15 |
| App 正文与控件 | Instrument Sans | 思源黑体 | 15 px；元信息 12–13 px；导航 12 px，选中 600 |
| 时间与编号 | Instrument Sans | — | 等宽数字（tabular-nums） |

四种字体都使用 SIL OFL 1.1 授权，可以随 App 分发，也可以在官网自托管。

中文宋体的打包是 App 端最大的待决问题：

- 官网：用 `next/font` 自托管。Google 的中文字体按 unicode-range 切片、按需加载，成本很小。
- App：完整的中文宋体文件很大，有三种做法。
  - (a) 打包一个字重的思源宋体，歌名和页面标题都用宋体。效果最完整，安装包会明显变大。
  - (b) 只给拉丁文字用衬线，中文回落到系统黑体。成本最低，但中文界面的 B 味道会变淡。
  - (c) 只打包 App 自身界面用到的字的子集，歌名这类用户内容用系统黑体。
- 不能依赖 Google Play 服务的可下载字体，很多国产手机没有 GMS。

## 4. 版式

### 4.1 官网（画板宽 1440）

- 左右边距 80 px。页眉内边距 26 px 80 px：左侧标识和字标，中间导航（Liner notes、Privacy、Support、中文，间距 36 px），右侧主按钮。
- 首屏两栏：`minmax(0, 1fr)` 加 640 px，栏间距 56 px。左栏上边距 40 px，元素间距 26 px，依次是眉题、主标题、段落（最宽 520 px）、按钮行、脚注。
- 右栏是"唱片套"：640 × 700，圆角 28 px，底色是当前专辑的染色。
  - 封面叠层 340 × 340，位于 (64, 64)，圆角 4 px。
  - 专辑切换列表宽 200 px，位于左 52、上 452。
  - 链路注记卡宽 336 px，位于右 40、上 300，与前层封面的右下角重叠约 140 × 104 px。这是刻意的编辑式重叠。
- 底部三栏原则：栏间距 56 px，顶部一条细线，罗马数字斜体编号 i.、ii.、iii.。

### 4.2 App（画板 390 × 844）

- 左右边距 24 px。顶部 44 px 留给系统状态栏，不画假状态栏。
- 正在播放，自上而下：顶栏 48 px → 封面 280 px（展开链路注记时缩到 164 px）→ 歌名和"艺术家 — 专辑" → 进度 → 控制 → 链路注记 → 底部导航 80 px。
- 曲库，自上而下：标题栏 56 px → 文字页签 → "最近添加"书架（封面 124 px，间距 14 px）→ 专辑详情卡 → 迷你播放条 → 底部导航。

### 4.3 圆角、阴影、分隔

- 圆角：封面 3–4 px，像唱片套的硬边；链路注记卡 6 px；App 卡片 14–18 px；按钮全圆；唱片套面板 28 px。
- 阴影一律用暖色 `rgba(40, 30, 16, α)`：
  - 官网封面叠层：前层 `0 34px 70px` α 0.28，中层 `0 18px 40px` α 0.16，后层 `0 12px 30px` α 0.12。
  - 链路注记卡：`0 24px 60px rgba(48, 38, 22, 0.16)`，外加 `0 1px 0 rgba(48, 38, 22, 0.06)`。
  - App 封面 `0 26px 50px` α 0.24；书架选中 `0 16px 30px` α 0.24，未选中 `0 2px 6px` α 0.10；迷你播放条 `0 10px 30px` α 0.12。
- 分隔用 1 px 细线，不用一层层的色块卡片。

## 5. 组件

- **主按钮**：`moss-deep` 底、白字、全圆角。页眉里高 44 px，正文里高 52 px。悬停 `#43581A`。每页只有一个主操作，可以在页面里重复出现。
- **文字链接**：`ink`，下划线偏移 5 px、粗 1 px。
- **图标按钮**：44 px 圆形、透明底，悬停叠加 `ink` 6%。图标 24 px，线宽 1.6–1.8。
- **状态标签**：高 26–30 px、全圆角，11–12 px、字重 600、字距 0.06em。样式见 §2.5。
- **链路注记（App）**：可折叠。折叠时显示一行摘要，例如"FLAC 24/96 · 有线耳机 · SYSTEM MIXED"，右侧箭头展开时旋转 180°。展开后依次是 01 · 源文件、02 · AudioTrack、03 · 路由与稳定性，每行给出数值和置信度（如"实测 · 未验证"），最后是状态标签和一句说明。
- **链路注记卡（官网）**：斜体标题 Liner notes 加 NOW PLAYING 眉题；歌名和"艺术家 — 专辑"；File、Decoder、Route 三行定义列表（标签列宽 84 px）；状态标签；一句说明。
- **封面叠层（官网）**：三个槽位，按 (x, y, 旋转, 缩放)：前层 (0, 0, 0°, 1)，中层 (76, −34, 5°, 0.9)，后层 (−44, −52, −6°, 0.82)。选中的专辑移到前层，其余依次后移。
- **专辑切换（官网）**：按钮列表。40 px 缩略图，衬线专辑名 19 px，艺术家 13 px。选中时 `paper-raised` 底加细线边框。
- **书架（App）**：横向滚动。选中的封面上移 6 px、阴影加深，整页随之染色，下方展示这张专辑的详情卡。
- **专辑详情卡**：`paper-raised`，圆角 18 px。衬线专辑名 28 px，下方"艺术家 · 格式"，右侧 48 px `moss-deep` 播放键。曲目行高 48 px，编号用等宽数字，行间细线。
- **页签（App）**：纯文字，间距 20 px。选中为 `ink` 加 2 px `ink` 下划线，未选中为 `ink-muted`。不用胶囊或色块。
- **底部导航**：`paper-nav` 底加顶部细线，图标线宽 1.6。选中项为 `ink`、字重 600，文字下方加一条 16 × 2 px 的 `moss-deep` 短线。
- **迷你播放条**：`paper-raised` 卡片，细线边框，柔和阴影。44 px 封面，衬线歌名 18 px，下方"有线耳机 · SYSTEM MIXED"，右侧 44 px `moss-deep` 播放键。
- **进度条**：2 px 细线（`ink` 16%），已播放部分为 `moss-deep`，加 10 px 圆点。

## 6. 交互

- **官网首屏**：指针在唱片套内移动时，封面叠层按层做视差浮动。每层的位移系数分别是 10、18、26 px，纵向再乘 0.7；指针离开后回位。点专辑时叠层重排，唱片套底色和主标题斜体词一起换色，链路注记卡换成这张专辑的内容。触屏设备和系统开启"减少动态效果"时关闭视差。
- **正在播放**：上一首和下一首切歌时，背景颜色过渡，新封面淡入。点"链路注记"展开或收起，封面同步缩放。收藏、随机、循环等沿用现有功能。
- **曲库**：页签切换视图。在专辑视图里点书架封面，整页染色，详情卡切换。其他视图是衬线标题加元信息的细线列表。

## 7. 动效

| 动效 | 时长 | 缓动 | 说明 |
| --- | --- | --- | --- |
| 官网入场 | 1000 ms | `cubic-bezier(.22, 1, .36, 1)` | 上移 18 px 并淡入。主标题三行延迟 0.05、0.15、0.25 s，段落 0.3 s，按钮行 0.38 s，脚注 0.44 s |
| 标识描线 | 1600 ms，延迟 0.3 s | 同上 | 只做描线出现，不旋转、不变形 |
| 封面叠层重排与视差 | 700 ms | 同上 | 用 transform 过渡，视差带一点拖尾 |
| 背景染色 | 800–900 ms | ease | 只过渡背景颜色 |
| 封面切换（App） | 700 ms | 同上 | 淡入并上移 8 px |
| 链路注记展开 | 封面缩放 550 ms，内容 500 ms | 同上 | 内容淡入并下移 6 px |
| 书架选中 | 450 ms | 同上 | 上移 6 px，阴影加深 |
| 页签切换 | 450 ms | 同上 | 新内容淡入并上移 8 px |

系统开启"减少动态效果"时，关闭全部动画和视差，颜色直接切换。

## 8. 文案

语气：短句、陈述事实，不夸张。证据类措辞沿用现有字符串，例如"实测 · 未验证"。

官网英文（画板，草稿）：

- 眉题：An offline music player for Android
- 主标题：Every track comes with *liner notes.*
- 段落：Vesqen plays the music you keep on your phone — no account, no streaming, nothing to sign in to. Beside every song it keeps a quiet record of the path to your ears: the file, the decoder, the route, and how each fact is known.
- 按钮：Join the closed beta（占位）；链接：Read the privacy policy
- 脚注：Android 8.0 or later · Version 1.0 beta
- 链路注记卡的说明：
  - ACTIVE：Android-side evidence: AudioTrack, mixer and route agree. External verification is a separate, signed record.
  - 有线：Android may mix, resample or apply system processing on this path — and Vesqen says so.
  - 蓝牙：Bluetooth re-encodes audio before it reaches your headphones, so Vesqen reports this path as system mixed.
- 三条原则：Offline, always / Evidence on request / Fails closed

App 中文：正在播放、第 1 / 3 首、链路注记、01 · 源文件、02 · AudioTrack、03 · 路由与稳定性、实测 · 未验证、"Android 可能混音、重采样或应用系统处理。"、最近添加，以及现有的页签和控件名称。

## 9. 无障碍

- 对比度见 §2.2。染色规则从算法上保证了下限。
- 触控目标不小于 44 px：播放键 68 px，图标按钮 44 px，列表行高至少 48 px。
- 焦点环：2 px `ink`，偏移 2–3 px。
- 所有控件都是真实的按钮或链接，图标按钮带无障碍标签。
- 颜色不单独承载状态（§2.5）。
- 遵守系统的"减少动态效果"（§7）。

## 10. 实现要点

**App（Compose）**

- 颜色角色：`background` = `paper`，`surface` = `paper-raised`，`onSurface` = `ink`，`onSurfaceVariant` = `ink-muted`，`primary` = `moss-deep`，`onPrimary` = 白，`outlineVariant` = `hairline`。
- 染色：`animateColorAsState(target, tween(800, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))`。
- 封面缩放：`animateDpAsState(if (notesOpen) 164.dp else 280.dp, tween(550, …))`。
- 字体放在 `res/font`：Instrument Serif（Regular 和 Italic）、Instrument Sans。中文按 §3 的决定处理。
- 动画需要遵守系统的动画缩放和"移除动画"设置。

**官网**

- CSS 变量：`--paper`、`--paper-raised`、`--ink`、`--ink-body`、`--ink-muted`、`--hairline`、`--moss-deep`、`--wash`，Tailwind 4 的 `@theme` 引用这些变量。
- 视差用 `pointermove` 加 `requestAnimationFrame` 节流；在 `(pointer: coarse)` 和 `prefers-reduced-motion: reduce` 下关闭。
- 专辑区只用原创的抽象封面，不能用真实唱片封面。
- 字体用 `next/font` 自托管。

## 11. 风险与待决问题

- 以浅色为主。夜间听歌需要深色版，目前 §2.4 只有提案。
- 中文宋体的打包方式（§3）。
- 低分辨率或文字很多的封面，取色可能不稳定，要用真实曲库在真机上验证。
- 现行规范里"正在播放页固定深色"和约 3.96% 的封面反光上限都要改（见 [README](README.md)）。
- 链路注记里 AudioTrack、mixer 这类术语的去留，随 #35 的文案审核决定。
- 链路页、设置页、官网中文版和隐私政策页的版式还没画。
- 官网主按钮的去向待定。

## 12. 画板

[mockups/](mockups/) 里的 B 相关文件：

- `B-Web.dc.html`：官网首屏。可以移动指针看视差，点专辑切换。
- `B-Now.dc.html`：正在播放。点上一首或下一首看背景染色过渡，点"链路注记"展开。
- `B-Library.dc.html`：曲库。点书架封面换专辑并染色，页签可切换。
