# B · 纸与声（Paper & Sound）

状态：2026-09-25 选定，作为官网和 App 重设计（#35）的主方向。本文记录画板上的全部取值。画板包括官网首屏（英文和中文），App 的正在播放、曲库、链路、设置四页（各有浅色和深色），以及中文标题字体对比。标"提案"的部分还没有画板。对比度都按 WCAG 公式计算。

## 1. 概念

**每首歌都附带"链路注记"（liner notes）。**

- 唱片内页记录录音、母带和制作信息。Vesqen 把"文件 → 解码器 → AudioTrack → 路由"这条证据链当作同一类信息，安静地附在歌曲旁边，需要时再展开。
- 暖纸色底、衬线标题、细线分隔，像印刷品而不是仪表盘。
- 封面决定环境色：正在播放页随当前专辑轻微染色，除背景外一切保持不变。
- 官网主标题：*Every track comes with liner notes.* 中文版：每首歌，都有一份链路注记。

适用范围：官网全部页面（中英文）；App 的正在播放、曲库、专辑详情、链路和设置页。

## 2. 色彩

### 2.1 浅色令牌（画板）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `paper` | `#F3EFE6` | 页面底色，无封面时的正在播放背景 |
| `paper-raised` | `#FBF9F4` | 卡片：链路注记卡、专辑详情卡、输出声明卡、证据展开、置顶指标、迷你播放条、选中的专辑按钮 |
| `paper-nav` | `#F6F3EC` | App 底部导航 |
| `ink` | `#1A1A16` | 主文字、图标、链路墨线 |
| `ink-body` | `#3F3C35` | 官网正文段落 |
| `ink-muted` | `#5E5A50` | 次要文字、元信息、次要图标 |
| `hairline` | `#D8D1C1` | 官网分隔线、选中按钮的边框 |
| `hairline-soft` | `#E2DBCB`、`#E6DFD0`、`#DDD6C6` | 卡片内行分隔、曲目行、导航顶线 |
| `hairline-app` | `ink` 的 10%–16% 透明度 | App 内分隔线，叠在染色背景上也成立 |
| `chip-neutral` | 官网 `#ECE7DB`；App 用 `ink` 的 7% 透明度 | SYSTEM MIXED 等中性标签的底 |
| `moss-deep` | `#536B1E` | 主按钮、播放键、进度、ACTIVE 标签 |
| `moss-deep-hover` | `#43581A` | 主按钮悬停 |
| `on-moss` | `#FFFFFF` | moss-deep 上的文字和图标 |
| `amber-deep` | `#7A4F00` | 严格输出停止等可恢复的提醒，沿用品牌的 Warning Amber Deep |
| 标识 | 外层 `#1B1C18`，内层 `#9FBF4B` | Twin Paths 主版，不变 |

### 2.2 对比度

| 组合 | 对比度 |
| --- | --- |
| `ink` / `paper` | 15.2:1 |
| `ink` / `paper-raised` | 16.6:1 |
| `ink-body` / `paper` | 9.6:1 |
| `ink-muted` / `paper` | 6.0:1 |
| `ink-muted` / `paper-raised` | 6.5:1 |
| `ink-muted` / `paper-nav` | 6.2:1 |
| `moss-deep` 作文字 / `paper` | 5.2:1（在 `paper-raised` 上 5.7:1） |
| 白 / `moss-deep` | 6.0:1（悬停色 7.9:1） |
| `amber-deep` / `paper` | 6.2:1（在 `paper-raised` 上 6.8:1） |
| `ink` / `chip-neutral` | 14.1:1 |
| `ink` / 按 §2.3 规则生成的任意染色 | ≥ 13.4:1 |
| `ink-muted` / 按 §2.3 规则生成的任意染色 | ≥ 5.27:1 |
| `ink` / 染色背景上的 7% 中性标签 | ≥ 11.4:1 |

画板里手选的 Copper 染色 `#EEDDCC` 亮度略低于规则（L 0.907），在它上面 `ink` 是 13.2:1，`ink-muted` 是 5.2:1，仍然达标。

标识内层 `#9FBF4B` 在纸色上只有 1.8:1。这是品牌主版的既定用法，标识不承载信息。

### 2.3 封面取色：正在播放页的背景

正在播放页的背景跟随当前专辑变化，这是 B 最有辨识度的地方。

1. **只染背景。** 文字、播放键和进度、证据状态标签、分隔线、底部导航和迷你播放条都不随封面变化。状态的含义永远不依赖专辑颜色。
2. **取色算法（提案）：**
   - 封面解码后缩小到最长边不超过 64 px，转换到 OKLCH。
   - 丢弃近黑、近白和灰色像素：L < 0.2、L > 0.95 或 C < 0.02。剩下的不足 8% 时，按无彩色封面处理，背景用 `paper`（深色用 `night`）。
   - 按色相分 24 档（每档 15°），以彩度加权统计，取最高的一档，得到主色相 h 和这一档的彩度中位数 C。
   - 浅色背景 = `oklch(0.915, clamp(0.25 × C, 0.008, 0.030), h)`。亮度固定、彩度封顶，所以全部 360° 色相下，`ink-muted` 的对比度最低也有 5.27:1，`ink` 最低 13.4:1。
   - 深色背景见 §2.4。
3. **没有封面或解码失败：** 用 `paper` / `night`。
4. **切歌过渡：** 背景颜色用 800 ms 过渡，缓动 `cubic-bezier(0.22, 1, 0.36, 1)`。系统关闭动画时直接切换。
5. **缓存：** 按专辑在内存里缓存计算结果（LRU）。取色在封面加载完成后于后台线程完成，不需要修改曲库数据库。
6. **同一规则还用于：** App 专辑详情（在曲库选中专辑时整页染色），以及官网首屏的专辑区。链路页和其他列表页不染色。

画板里的浅色染色是手工选的，接近上述规则（Copper 的亮度略低）：

| 专辑（虚构） | 背景 | OKLCH |
| --- | --- | --- |
| Low Tide Archive（深青封面） | `#DCE5E0` | L 0.914 · C 0.012 · h 162 |
| Copper Hours（铜色封面） | `#EEDDCC` | L 0.907 · C 0.030 · h 67 |
| 盐与雾（雾灰封面） | `#E1E4DC` | L 0.914 · C 0.011 · h 124 |
| Night Transit（夜色封面） | `#E2E2DF` | L 0.912 · C 0.004 |
| 空港（天蓝封面） | `#DCE3E7` | L 0.912 · C 0.009 · h 232 |

官网首屏另有一组"专辑墨色"，只用于主标题里的斜体词：Low Tide `#1F5A57`（6.9:1）、Copper `#9A4A22`（5.4:1）、盐与雾 `#46524E`（7.1:1）。

### 2.4 深色（画板：正在播放、曲库、链路、设置）

跟随系统深浅色，取代现行正在播放页固定使用的 Nocturne Graphite。

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `night` | `#151411` | 暖调近黑。`night-text` 在其上 15.1:1 |
| `night-raised` | `#1E1C18` | 卡片。`night-muted` 在其上 6.7:1 |
| `night-nav` | `#191814` | 底部导航。`night-muted` 在其上 7.0:1 |
| `night-text` | `#EDE8DC` | 主文字、链路墨线、焦点环 |
| `night-muted` | `#A8A294` | 次要文字，在 `night` 上 7.3:1 |
| `night-hairline` | `night-text` 的 10%–16% 透明度 | 分隔线 |
| `night-chip` | `night-text` 的 8% 透明度 | SYSTEM MIXED 标签底，其上文字 12.6:1 |
| `moss-bright` | `#BFD66B` | 深色下的播放键、进度、ACTIVE。作文字 11.4:1；实心底配 `night` 文字 11.4:1 |
| `amber-bright` | `#F2C36B` | 严格输出停止等可恢复提醒，在 `night` 上 11.2:1 |
| 封面阴影 | `0 26px 50px rgba(0, 0, 0, 0.45)` | 深色下的封面和书架 |

深色染色 = `oklch(0.21, clamp(0.20 × C, 0.006, 0.024), h)`。全部色相下 `night-muted` 都 ≥ 6.9:1。画板里的深色染色按这条规则计算：

| 专辑（虚构） | 背景 | 说明 |
| --- | --- | --- |
| Low Tide Archive | `#141A19` | 封面主色 C 0.044、h 191 |
| Copper Hours | `#21150F` | 封面主色 C 0.117、h 46 |
| 盐与雾、Night Transit | `#151411` | 封面接近无彩色，按规则回到 `night` |
| 空港 | `#16191B` | 封面主色 C 0.031、h 240 |

在这些背景上，`night-text` ≥ 14.4:1，`night-muted` ≥ 6.9:1，`moss-bright` ≥ 10.9:1。深色的染色很克制，夜里只看得出冷暖，不会像浅色那样明显。

### 2.5 证据状态

链路页（浅色和深色）画出了全部六种状态，可以在画板的 Tweaks 里用 `output` 切换预览。

| 状态 | 浅色 | 深色 |
| --- | --- | --- |
| SYSTEM MIXED | `ink` 7% 底加合流图标，文字用 `ink`；不用 Moss | `night-chip` 底，文字用 `night-text` |
| BIT-PERFECT AVAILABLE | `moss-deep` 1.5 px 描边，空心点，文字 `moss-deep` | `moss-bright` 描边，空心点 |
| BIT-PERFECT REQUESTED | 同 AVAILABLE，空心点呼吸 | 同左 |
| BIT-PERFECT ACTIVE | `moss-deep` 实心，白点白字 | `moss-bright` 实心，`night` 点和字 |
| BIT-PERFECT VERIFIED | `moss-deep` 描边加盾牌图标；必须同时给出验证记录编号和方法 | `moss-bright` 描边加盾牌 |
| 严格输出已停止 | `amber-deep` 描边加警示图标，并提供"改用系统输出" | `amber-bright` 描边加警示图标 |

颜色不能单独提升声明等级。描边或实心、图标、文字共同区分状态，与现行规范一致。

## 3. 字体

| 用途 | 拉丁 | 中文 | 画板规格 |
| --- | --- | --- | --- |
| 官网主标题 | Instrument Serif 400 | 思源宋体 400（网页字体） | 104 px，行高 0.94，字距 −0.02em；强调词用斜体 |
| 官网字标 | Instrument Serif | — | 30 px |
| 官网小标题 | Instrument Serif | 思源宋体 400 | 26 px，行高 1.1 |
| 链路注记卡标题 | Instrument Serif Italic | — | 26 px |
| 正文 | Instrument Sans 400 | 黑体 | 官网 19 px / 1.6；卡片 15 px / 1.55；元信息 13 px |
| 眉题、标签 | Instrument Sans 500，大写 | — | 12–13 px，字距 0.12–0.16em |
| App 页面标题 | — | 宋体 400 | 32 px（"曲库""链路"） |
| App 歌名 | Instrument Serif | 宋体 400 | 34 px / 1.12 |
| App 专辑名 | Instrument Serif | 宋体 400 | 详情 28 px / 1.1；书架 17 px / 1.15；列表 21 px / 1.15 |
| App 小标题 | — | 宋体 400 | 17–22 px（"链路注记""当前播放路径""置顶指标"、输出声明标题） |
| App 小标签 | Instrument Sans 600 | 黑体 | 13–14 px（顶栏"正在播放"、"最近添加"） |
| App 正文与控件 | Instrument Sans | 黑体 | 15 px；元信息 12–13 px；导航 12 px，选中 600 |
| App 数字 | Instrument Serif | — | 置顶指标 34 px |
| 时间与编号 | Instrument Sans | — | 等宽数字（tabular-nums） |

规则：宋体只用于 16 px 以上的标题，只用 400 一个字重；正文、标签和数据一律黑体。13 px 的宋体横笔太细，在手机上发虚，见 `B-Fonts.dc.html` 的小字对比。

**2026-09-25 决定：App 不打包中文字体。中文标题优先用手机自带的宋体，没有就自动回落到系统黑体。**

- 原生 Android（AOSP）的 `fonts.xml` 把 `NotoSerifCJK-Regular.ttc`（思源宋体）登记为简体中文的 serif 回落字体，只有 400 一个字重，所以 B 不做粗宋。
- 国产 ROM 常常替换系统字体，是否保留宋体要逐台确认，先从用户的 iQOO 真机开始。
- 实现：Android 10（API 29）起，用 `Typeface.CustomFallbackBuilder` 以 Instrument Serif 为主字体，`setSystemFallback("serif")` 作系统回落；汉字会自动落到系统宋体，系统没有宋体时落到默认中文字体。Android 8–9 的自定义字体不能指定回落族，中文会显示为系统黑体；如果需要，可以把中文片段单独设成 `FontFamily.Serif`。
- 需要按"有没有宋体"微调样式时，Android 10 起可以用 `SystemFonts.getAvailableFonts()` 检查。
- 设计必须在两种结果下都成立。两种效果并排见 `B-Fonts.dc.html`。
- 官网照旧用 `next/font` 自托管思源宋体，按 unicode-range 切片、按需加载，成本很小。

App 只打包 Instrument Serif（Regular、Italic）和 Instrument Sans，都使用 SIL OFL 1.1 授权，文件很小。

### 中文官网的排版

中文版官网（`B-Web-ZH.dc.html`）与英文版版式相同，只调整排版参数：

- 中文不用斜体。英文主标题里斜体加专辑墨色的强调词，中文只保留专辑墨色（"链路注记"四个字）。
- 主标题：思源宋体 400，92 px，行高 1.16，字距 +0.02em，分三行："每首歌，" / "都有一份" / "链路注记。"。中文比拉丁字母更需要行距，负字距会挤。
- 正文：18 px，行高 1.9；卡片说明 13 px，行高 1.7；原则说明 15 px，行高 1.8。
- 小标签（眉题、"正在播放""来自曲库"）不做大写变换，改用 0.2em 字距。
- 原则编号用宋体的"一、二、三"，替代英文版的斜体罗马数字。
- 专辑名、艺术家名和证据状态标签（SYSTEM MIXED、BIT-PERFECT ACTIVE 等）保持原文。

## 4. 版式

### 4.1 官网（画板宽 1440）

- 左右边距 80 px。页眉内边距 26 px 80 px：左侧标识和字标，中间导航（Liner notes、Privacy、Support、中文，间距 36 px），最右边是状态标签或主按钮：封闭测试期间显示不可点的"In closed testing / 封闭测试中"，正式上架后换成"Get it on Google Play / 在 Google Play 获取"。
- 首屏两栏：`minmax(0, 1fr)` 加 640 px，栏间距 56 px。左栏上边距 40 px，元素间距 26 px，依次是眉题、主标题、段落（最宽 520 px）、按钮行、脚注。
- 右栏是"唱片套"：640 × 700，圆角 28 px，底色是当前专辑的染色。
  - 封面叠层 340 × 340，位于 (64, 64)，圆角 4 px。
  - 专辑切换列表宽 200 px，位于左 52、上 452。
  - 链路注记卡宽 336 px，位于右 40、上 300，与前层封面的右下角重叠约 140 × 104 px。这是刻意的编辑式重叠。
- 底部三栏原则：栏间距 56 px，顶部一条细线，罗马数字斜体编号 i.、ii.、iii.。
- 中文版版式完全相同，只替换文字和排版参数（§3"中文官网的排版"）。导航最后一项是 English，和英文版的"中文"互相切换。

### 4.2 App（画板 390 × 844）

- 左右边距 24 px。顶部 44 px 留给系统状态栏，不画假状态栏。
- 正在播放，自上而下：顶栏 48 px → 封面 280 px（展开链路注记时缩小，浅色画板 164 px，深色画板 156 px）→ 歌名和"艺术家 — 专辑" → 进度 → 控制 → 链路注记 → 底部导航 80 px。
- 曲库，自上而下：标题栏 56 px → 文字页签 → "最近添加"书架（封面 124 px，间距 14 px）→ 专辑详情卡 → 迷你播放条 → 底部导航。
- 链路（整页可滚动），自上而下：标题"链路"和实时观测提示 → 当前播放 → 输出声明卡 → 当前播放路径（五站）→ 边界说明 → 置顶指标（2 × 2）→ 打开高级仪表盘 → 底部导航。
- 设置（整页可滚动），自上而下：标题"设置"和一句引导 → 完整版卡片 → 播放输出（单选）→ 严格输出状态框（选中严格 USB 时出现）→ 音频证据（播放链路、输出验证记录）→ 应用信息（关于、隐私政策、开源许可）→ 隐私说明一句 → 底部导航。分组标题用宋体 19 px，分组内是 `paper-raised` 圆角 16 px 的整块卡片，行间细线。

### 4.3 圆角、阴影、分隔

- 圆角：封面 3–4 px，像唱片套的硬边；链路注记卡 6 px；证据展开 10 px；App 卡片 14–18 px；按钮全圆；唱片套面板 28 px。
- 浅色阴影一律用暖色 `rgba(40, 30, 16, α)`：
  - 官网封面叠层：前层 `0 34px 70px` α 0.28，中层 `0 18px 40px` α 0.16，后层 `0 12px 30px` α 0.12。
  - 链路注记卡：`0 24px 60px rgba(48, 38, 22, 0.16)`，外加 `0 1px 0 rgba(48, 38, 22, 0.06)`。输出声明卡：`0 12px 30px rgba(48, 38, 22, 0.08)` 加同样的 1 px 底线。
  - App 封面 `0 26px 50px` α 0.24；书架选中 `0 16px 30px` α 0.24，未选中 `0 2px 6px` α 0.10；迷你播放条 `0 10px 30px` α 0.12。
- 深色不靠阴影分层，用 `night-raised` 底和 6% 的细边。
- 分隔用 1 px 细线，不用一层层的色块卡片。

## 5. 组件

- **主按钮**：`moss-deep` 底、白字、全圆角（深色用 `moss-bright` 底、`night` 字）。页眉里高 44 px，正文里高 52 px。悬停 `#43581A`。每页只有一个主操作，可以在页面里重复出现。
- **次要按钮**：1 px `ink` 描边、透明底、全圆角，高 44 px。例如严格输出停止时的"改用系统输出"。
- **文字链接**：`ink`，下划线偏移 5 px、粗 1 px。
- **图标按钮**：44 px 圆形、透明底，悬停叠加 `ink` 6%。图标 24 px，线宽 1.6–1.8。
- **状态标签**：高 26–30 px、全圆角，11–12 px、字重 600、字距 0.06em。样式见 §2.5。
- **链路注记（App 正在播放）**：可折叠。折叠时显示一行摘要，例如"FLAC 24/96 · 有线耳机 · SYSTEM MIXED"，右侧箭头展开时旋转 180°。展开后依次是 01 · 源文件、02 · AudioTrack、03 · 路由与稳定性，每行给出数值和置信度（如"实测 · 未验证"），最后是状态标签和一句说明。
- **链路注记卡（官网）**：斜体标题 Liner notes 加 NOW PLAYING 眉题；歌名和"艺术家 — 专辑"；File、Decoder、Route 三行定义列表（标签列宽 84 px）；状态标签；一句说明。
- **输出声明卡（链路页）**：`paper-raised`，圆角 16 px，内边距 18 px。依次是状态标签、宋体标题 22 px、说明 14 px / 1.6。标题和说明直接用现有字符串。
- **当前播放路径（链路页）**：五站：源文件、解码器、应用处理、AudioTrack、路由。左侧一条 1 px 墨线（`ink` 22%）串起 9 px 空心节点；一个 5 px 墨点沿线向下流动，表示正在观测。播放停止或正在切换输出时不流动。每站显示步骤编号 11 px、数值 15 px、置信度 12 px。点开后展开证据：来源、方法（只有推导和估算才有）、更新时间，节点变实心。
- **置顶指标（链路页）**：2 × 2 卡片。Instrument Serif 34 px 数字加单位、指标名 13 px、置信度 11 px，每秒刷新。没有播放时显示"—"和"当前没有播放"。画板用的是现有指标：估算的缓冲时长、音频欠载次数、进程 CPU 负载、整机功耗估算。
- **实时观测提示**：6 px 墨点呼吸，加"正在实时观测"。播放停止时改为"播放已停止"。
- **高级仪表盘入口**：上下两条细线的整行按钮。
- **完整版卡片（设置页，Play 构建）**：和输出声明卡同一种卡片。依次是中性状态标签、宋体标题 22 px、说明 14 px；试用中和试用结束时附主按钮"一次性解锁 · [价格]"和文字按钮"恢复购买"。三种状态见 §6。不含 Billing 的 GitHub 构建没有这张卡片。
- **单选组（播放输出）**：整块卡片里两个选项，每项是 20 px 圆形单选标记、选项名 16 px 和说明 13 px。选中时圆圈和圆点是 `moss-deep`，未选中圆圈是 `#7C776B`（深色 `#8A8578`）。选项不可用时用禁用状态，并加锁形图标和"解锁后可用"。
- **严格输出状态框**：选中严格 USB 后出现在单选组下方。细边框，内有状态标签（REQUESTED 或 ACTIVE）和一句说明。
- **设置列表行**：高至少 56 px（带说明时 64 px），标题 16 px、说明 13 px、右侧箭头；需要显示值时（如版本号）放在箭头左侧，用等宽数字。
- **封面叠层（官网）**：三个槽位，按 (x, y, 旋转, 缩放)：前层 (0, 0, 0°, 1)，中层 (76, −34, 5°, 0.9)，后层 (−44, −52, −6°, 0.82)。选中的专辑移到前层，其余依次后移。
- **专辑切换（官网）**：按钮列表。40 px 缩略图，衬线专辑名 19 px，艺术家 13 px。选中时 `paper-raised` 底加细线边框。
- **书架（App）**：横向滚动。选中的封面上移 6 px、阴影加深，整页随之染色，下方展示这张专辑的详情卡。
- **专辑详情卡**：`paper-raised`，圆角 18 px。衬线专辑名 28 px，下方"艺术家 · 格式"，右侧 48 px 播放键。曲目行高 48 px，编号用等宽数字，行间细线。
- **页签（App）**：纯文字，间距 20 px。选中为 `ink` 加 2 px `ink` 下划线，未选中为 `ink-muted`。不用胶囊或色块。
- **底部导航**：`paper-nav` 底加顶部细线，图标线宽 1.6。选中项为 `ink`、字重 600，文字下方加一条 16 × 2 px 的 `moss-deep` 短线（深色用 `moss-bright`）。
- **迷你播放条**：`paper-raised` 卡片，细线边框，柔和阴影。44 px 封面，衬线歌名 18 px，下方路由和状态（浅色画板"有线耳机 · SYSTEM MIXED"；深色画板"USB DAC · BIT-PERFECT ACTIVE"，ACTIVE 用 `moss-bright`），右侧 44 px 播放键。
- **进度条**：2 px 细线（`ink` 16%），已播放部分为 `moss-deep`，加 10 px 圆点。

## 6. 交互

- **官网首屏**：指针在唱片套内移动时，封面叠层按层做视差浮动。每层的位移系数分别是 10、18、26 px，纵向再乘 0.7；指针离开后回位。点专辑时叠层重排，唱片套底色和主标题斜体词一起换色，链路注记卡换成这张专辑的内容。触屏设备和系统开启"减少动态效果"时关闭视差。
- **正在播放**：切歌时背景颜色过渡，新封面淡入。点"链路注记"展开或收起，封面同步缩放。深色画板演示严格 USB 场景：切歌后先显示约 1.4 s 的 REQUESTED，再变为 ACTIVE。收藏、随机、循环等沿用现有功能。
- **曲库**：页签切换视图。在专辑视图里点书架封面，整页染色，详情卡切换。其他视图是衬线标题加元信息的细线列表。
- **链路**：点路径中的一站展开证据，一次只展开一站。置顶指标每秒刷新，只在页面可见时更新，与现有的采样规则一致。
- **设置**：选严格 USB 后，状态框先显示约 1.4 s 的 REQUESTED，再变为 ACTIVE；选回系统输出，状态框消失。画板的 Tweaks 里 `license` 可以切换完整版卡片的三种状态：试用中（`trial`）、试用已结束（`ended`）、已解锁（`unlocked`）。试用结束时严格 USB 选项锁定，这一点跟随 MONETIZATION 里暂定的锁定清单。"播放链路"一行跳转到链路页。
- **官网**：英文版和中文版的语言入口互相跳转。
- 同一方向的页面之间，底部导航可以互相跳转；浅色和深色各自成套。

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
| 路径流动点 | 2.8 s 一趟 | `cubic-bezier(.45, 0, .55, 1)` | 沿墨线从上到下，首尾淡入淡出 |
| 证据展开 | 400 ms | `cubic-bezier(.22, 1, .36, 1)` | 淡入并下移 4 px |
| 实时观测提示 | 1.6 s 一周期 | ease-in-out | 墨点透明度 1 → 0.3 → 1 |
| REQUESTED 呼吸 | 1.2 s 一周期 | ease-in-out | 空心点透明度变化 |

系统开启"减少动态效果"时，关闭全部动画、视差和流动点，颜色直接切换。

## 8. 文案

语气：短句、陈述事实，不夸张。证据类措辞沿用现有字符串，例如"实测 · 未验证"。官网文案另按官网仓库 `docs/SITE_PLAN.md` §5 的规则写：对举句（如"不是……而是……"）一页最多一次，少用排比、连串否定和破折号，中文不逐句对着英文翻。

官网英文（画板，草稿）：

- 眉题：An offline music player for Android
- 主标题：Every track comes with *liner notes.*
- 段落：Vesqen plays the music stored on your phone. It works offline and needs no account. For each song, it records the path to your headphones step by step: the file, the decoder, the output route, and how each value was measured.
- 封闭测试期间不放按钮。首屏说明下面是一行状态："Vesqen is in closed testing. Downloads open here when it launches on Google Play."，旁边是 Read the privacy policy 链接；页眉右侧是"In closed testing"标签
- 脚注：Android 8.0 or later · Version 1.0 beta
- 链路注记卡的说明：
  - ACTIVE：Android-side evidence: AudioTrack, mixer and route agree. External verification is a separate, signed record.
  - 有线：On this path Android may mix, resample or add system effects, so Vesqen marks it as system mixed.
  - 蓝牙：Bluetooth re-encodes audio before it reaches your headphones, so Vesqen marks this path as system mixed too.
- 三条原则：Offline, always（Playback never uses the network, and there is no account to create.）/ Evidence on request（Each value on the Chain page shows where it came from and how it was measured.）/ Fails closed（If strict USB output can’t be kept, Vesqen stops playback and tells you why.）

App 中文：

- 正在播放、曲库：正在播放、第 1 / 3 首、链路注记、01 · 源文件、02 · AudioTrack、03 · 路由与稳定性、实测 · 未验证、"Android 可能混音、重采样或应用系统处理。"、最近添加，以及现有的页签和控件名称。
- 链路页的输出声明直接用现有字符串：由 Android 管理的播放、发现兼容的严格输出、正在准备严格 USB 输出、严格 USB 输出已生效、输出已通过外部验证、严格输出已停止，以及对应的说明。VERIFIED 的记录编号和方法在画板里是占位"[记录编号]""[验证方法]"。
- 链路页的其余文字：正在实时观测、当前播放、当前播放路径、刚刚更新、"源文件与 AudioTrack 数据不等于设备最终输出。轻触参数可查看证据。"、置顶指标、打开高级仪表盘、来源、方法、更新、当前没有播放，以及现有的指标名和数据来源名。
- 设置页沿用现有的分组和引导句（"先决定音乐如何播放，需要时再查看对应证据。""播放输出""音频证据""应用信息"等）。选项说明是按 #35 写的白话提案，替换现有字符串里的开发者用语：
  - 系统输出：现有"使用 Android 普通路由，保留系统混音、重采样和手机音量。" → 提案"按 Android 的普通方式播放。系统可能混音、重采样，手机音量照常可调。"
  - 严格 USB：现有"要求 Android 14+、兼容 USB DAC、精确 mixer profile 和未经改写的 PCM；任一条件失效都会停止播放。" → 提案"只把未经改动的音频交给兼容的 USB DAC。需要 Android 14 以上；条件不满足就停止播放，不会悄悄改回系统输出。"
  - 播放链路：现有"查看当前 Android 路由声明、已检测的输出类型与证据边界。" → 提案"查看当前播放路径和每一项的证据。"
  - 输出验证记录（未导入）：提案"未导入。只接受维护者签名的记录，而且只对完全相同的应用、手机、DAC 和格式生效。"
- 完整版卡片（提案，价格待定）：
  - 试用中："试用还剩 9 天" / "试用结束后，基础播放继续免费；严格 USB 输出和完整的音频证据需要一次性解锁。不订阅，也不需要账号。"
  - 试用已结束："解锁 Vesqen 完整版" / "基础播放继续免费。解锁后可以继续使用严格 USB 输出和完整的音频证据。一次购买，不订阅。"
  - 已解锁："Vesqen 完整版" / "感谢支持。购买记录保存在你的 Google Play 账号里，重新安装后会自动恢复。"

官网中文（画板，草稿）：

- 导航：链路注记、隐私政策、支持、English；页眉右侧是"封闭测试中"标签；首屏状态："Vesqen 目前在封闭测试中，正式版上架后会在这里提供下载。"；链接：阅读隐私政策
- 眉题：Android 离线音乐播放器
- 主标题：每首歌，都有一份链路注记。
- 段落：Vesqen 播放你手机里的音乐，离线就能用，也不用注册账号。播放时，它会记下声音从文件到耳机经过的每一步：文件、解码器、输出路由，以及每个数值是怎么测到的。
- 脚注：Android 8.0 及以上 · 1.0 测试版
- 链路注记卡的说明：
  - ACTIVE：Android 端的证据显示，AudioTrack、mixer 和路由一致。外部验证另有一份签名记录。
  - 有线：在这条路径上，Android 可能混音、重采样或加系统音效，所以 Vesqen 标为系统混音。
  - 蓝牙：蓝牙会在手机上重新编码后再发给耳机，所以这条路径也标为系统混音。
- 三条原则：离线使用（播放从不联网，也不用注册账号。）/ 证据可查（链路页上的每个数值，都写明了来源和测量方式。）/ 做不到就停下（严格 USB 输出保持不了时，Vesqen 会停止播放，并告诉你原因。）

## 9. 无障碍

- 对比度见 §2.2 和 §2.4。染色规则从算法上保证了下限。
- 触控目标不小于 44 px：播放键 68 px，图标按钮 44 px，列表行和路径行高至少 48 px。
- 焦点环：2 px `ink`（深色用 `night-text`），偏移 2–3 px。
- 所有控件都是真实的按钮或链接，图标按钮带无障碍标签；路径行带展开状态。
- 颜色不单独承载状态（§2.5）。
- 遵守系统的"减少动态效果"（§7）。

## 10. 实现要点

**App（Compose）**

- 浅色角色：`background` = `paper`，`surface` = `paper-raised`，`onSurface` = `ink`，`onSurfaceVariant` = `ink-muted`，`primary` = `moss-deep`，`onPrimary` = 白，`outlineVariant` = `hairline`。深色对应 `night`、`night-raised`、`night-text`、`night-muted`、`moss-bright`、`night`（作 onPrimary）。
- 染色：`animateColorAsState(target, tween(800, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))`。
- 封面缩放：`animateDpAsState(if (notesOpen) 164.dp else 280.dp, tween(550, …))`。
- 字体见 §3：`res/font` 只放 Instrument Serif 和 Instrument Sans，中文走系统回落。
- 动画需要遵守系统的动画缩放和"移除动画"设置。

**官网**

- CSS 变量：`--paper`、`--paper-raised`、`--ink`、`--ink-body`、`--ink-muted`、`--hairline`、`--moss-deep`、`--wash`，Tailwind 4 的 `@theme` 引用这些变量；深色用 `prefers-color-scheme` 切换到 §2.4 的令牌。
- 视差用 `pointermove` 加 `requestAnimationFrame` 节流；在 `(pointer: coarse)` 和 `prefers-reduced-motion: reduce` 下关闭。
- 专辑区只用原创的抽象封面，不能用真实唱片封面。
- 字体用 `next/font` 自托管。

## 11. 风险与待决问题

- 深色版目前只有画板，还没在真机上看过夜间效果。
- 国产 ROM 是否自带中文宋体要逐台确认；没有宋体的手机上，中文标题会是黑体（§3）。
- 低分辨率或文字很多的封面，取色可能不稳定，要用真实曲库在真机上验证。
- 现行规范里"正在播放页固定深色"、约 3.96% 的封面反光上限、Roboto 字体和"不用全大写"都要改（见 [README](README.md)）。
- 链路注记里 AudioTrack、mixer 这类术语的去留，随 #35 的文案审核决定。
- 官网的隐私政策页还没画。
- 完整版卡片的文案、价格和锁定清单都跟随 #46 和 MONETIZATION 里的暂定方案，价格在画板里是"[价格]"占位。
- 设置页的白话选项说明是提案，要和 #35 的文案审核一起定。

## 12. 画板

[mockups/](mockups/) 里的 B 相关文件：

- `B-Web.dc.html`、`B-Web-ZH.dc.html`：官网首屏，英文和中文。可以移动指针看视差，点专辑切换；导航里的语言入口互相跳转。
- `B-Sitemap.dc.html`：官网页面结构，详细规划在官网仓库的 `docs/SITE_PLAN.md`。
- `B-Settings.dc.html`、`B-Settings-Dark.dc.html`：设置。切换播放输出；在 Tweaks 里用 `license` 切换完整版卡片的三种状态。
- `B-Now.dc.html`、`B-Now-Dark.dc.html`：正在播放。点上一首或下一首看背景染色过渡，点"链路注记"展开。深色版演示严格 USB 的 REQUESTED → ACTIVE。
- `B-Library.dc.html`、`B-Library-Dark.dc.html`：曲库。点书架封面换专辑并染色，页签可切换。
- `B-Chain.dc.html`、`B-Chain-Dark.dc.html`：链路。点路径展开证据；在 Tweaks 里用 `output` 切换六种输出状态。
- `B-Fonts.dc.html`：中文标题字体对比，有宋体和回落到黑体两种结果并排。
