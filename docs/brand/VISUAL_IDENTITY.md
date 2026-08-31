# Vesqen 视觉识别系统

- 状态：正式基线 v1.0
- 生效日期：2026-08-31
- 品牌主张：The Quiet Signal / 安静而可信的信号
- 产品标识：Twin Paths / 双路径

本文件定义 Vesqen 的正式视觉识别基线。界面实现以根目录的 `DESIGN.md` 和 `.impeccable/design.json` 为机器可读来源；本文件负责解释品牌含义、资产用法和不可变规则。

## 1. 品牌核心

Vesqen 是一款离线优先、重视播放链路证据的本地音乐播放器。视觉语言必须同时表达两种体验：普通用户能直接找到并播放音乐，高级用户能继续查看链路和证据，但技术信息不会压过音乐本身。

品牌性格是：安静、精确、亲密。界面应像一件可信的聆听工具，而不是内容流、广告面板、霓虹音频仪表盘或参数竞赛。

## 2. 正式标识：Twin Paths

Twin Paths 由两条沿同一垂直轴汇聚的 V 形路径构成：

- 外路径代表用户真正听到的播放路径。
- 内路径代表 Vesqen 对源、解码、处理和输出证据的追踪。
- 两条路径同向但不重叠，表达“播放优先、证据随行”。
- 对称几何刻意避免单边上扬、尾部延伸和播放三角，防止被读成对勾、下载箭头或常见播放器图标。

### 2.1 构造参数

- 标准画板：`108 × 108`。
- 垂直对称轴：`x = 54`。
- 外路径：`M29,32 L54,78 L79,32`，7 单位圆角描边。
- 内路径：`M39,32 L54,60 L69,32`，6 单位圆角描边。
- 实际外接范围：`x = 25.5–82.5`、`y = 28.5–81.5`。
- Android 自适应图标安全区：`x/y = 21–87`，两条路径完整位于安全区内。

不得拉伸、旋转、倾斜、改变两条路径的相对高度、添加第三条路径，或给路径增加音符、波形、耳机、唱片、播放键和勾形尾部。

### 2.2 留白与最小尺寸

- 独立标志四周最小留白为外路径描边宽度的 2 倍，即标准画板中的 14 单位。
- 数字界面最小尺寸为 24 dp/px；低于 32 dp/px 时优先使用单色版本。
- 横向组合标最小宽度为 112 px；小于该宽度时只使用独立标志。
- 启动图标、通知图标和主题图标必须使用各自专用资产，不得从截图或 App 图标位图裁切。
- 24 dp 通知标志使用光学校正几何：外路径 `M22,24 L54,84 L86,24` / 9 单位，内路径 `M35,24 L54,61 L73,24` / 8 单位。它只扩大墨迹面积，不改变 Twin Paths 的对称关系；不得反向覆盖标准主标。

## 3. 标识版本

| 版本 | 使用场景 | 外路径 | 内路径 / 处理 |
| --- | --- | --- | --- |
| Primary | 浅色背景、文档、官网 | Ink Dark `#1B1C18` | Signal Moss `#9FBF4B` |
| Inverse | 深色背景、深色界面 | Ink Light `#E7E8E1` | Signal Moss Bright `#BFD66B` |
| Monochrome | Android 主题图标、通知、印刷限制 | 单一前景色 | 与外路径同色 |
| App Icon | Android launcher | Carbon Black `#0F0F0F` 背景 | Inverse 标识 |

Signal Moss 是品牌锚点，但单色媒介中几何识别优先于颜色。任何壁纸动态色都不得改变 Logo 本身。

## 4. 品牌色彩

### 4.1 核心色

| Token | HEX | 用途 |
| --- | --- | --- |
| Signal Moss | `#9FBF4B` | 固定品牌标识、少量身份时刻 |
| Signal Moss Bright | `#BFD66B` | 深色主题活跃控件、进度、选中状态 |
| Signal Moss Deep | `#536B1E` | 浅色主题实心主按钮和选中状态 |
| Carbon Black | `#0F0F0F` | 深色画布、App 图标背景 |
| Carbon Surface | `#171914` | 深色结构层 |
| Carbon Elevated | `#23261E` | mini-player、菜单、底部浮层 |
| Pure White | `#FFFFFF` | 浅色主画布 |
| Frost Surface | `#F6F7F2` | 浅色次级表面 |
| Ink Dark | `#1B1C18` | 浅色主题主文字 |
| Ink Light | `#E7E8E1` | 深色主题主文字 |

辅助色 Midnight Violet `#1E1B2B` 只用于完整播放页的氛围，不是第二主色；Warning Amber Bright `#F2C36B` / Deep `#7A4F00` 用于深浅主题的可恢复提醒；Error `#BA1A1A` 只用于错误和破坏性操作。

### 4.2 Signal Budget

普通页面中 Moss 色面积原则上不超过约 10%。它只用于品牌、焦点、选择、播放进度和中性播放状态之上的正向证据；大面积背景、整行饱和高亮和装饰渐变均不使用 Moss。

### 4.3 已验证对比度

| 组合 | 对比度 |
| --- | ---: |
| Ink Light / Carbon Black | 15.54:1 |
| Muted Dark / Carbon Black | 11.46:1 |
| Muted Dark / Carbon Elevated | 9.18:1 |
| Ink Dark / Pure White | 17.13:1 |
| Muted Light / Pure White | 6.40:1 |
| Muted Light / Frost Surface | 5.94:1 |
| Signal Moss Bright / Carbon Surface | 11.00:1 |
| Signal Moss Deep / Frost Surface | 5.59:1 |
| Pure White / Signal Moss Deep | 6.02:1 |
| Ink Dark / Signal Moss Bright | 10.64:1 |
| Pure White / Warning Amber Deep | 7.13:1 |
| Ink Dark / Warning Amber Bright | 10.44:1 |

上述组合满足 WCAG 2.2 AA 普通文本目标；实际界面仍需按最终字号、透明度和背景重新验证。

### 4.4 证据状态映射

| 状态 | 颜色 | 颜色之外的必要提示 |
| --- | --- | --- |
| `SYSTEM MIXED` | 深色用 Carbon Elevated + Muted Dark；浅色用 Frost + Muted Light | 路由图标和完整状态文字 |
| `DIRECT SUPPORTED` | 与中性状态相同，不做成功色 | `DIRECT` 文字；不得显示勾选或验证徽章 |
| `BIT-PERFECT AVAILABLE` | Moss 描边，不使用实心 Moss 背景 | 空心圆可用性图标和 `AVAILABLE` 文字 |
| `BIT-PERFECT ACTIVE` | 深色用 Moss Bright 实心；浅色用 Moss Deep 实心 | 实心活动点和 `ACTIVE` 文字 |
| `BIT-PERFECT VERIFIED` | Moss 描边或低强度色阶，并与精确矩阵上下文同行 | 盾牌/证书图标、`VERIFIED` 文字和设备矩阵入口 |
| 可恢复限制 | 对应主题的 Warning Amber | 警告图标、原因和下一步 |
| 失败 / 破坏性操作 | Error | 错误图标、精确原因和恢复操作 |

`SYSTEM MIXED` 和 `DIRECT SUPPORTED` 不得使用 Moss。AVAILABLE、ACTIVE、VERIFIED 即使共享品牌色，也必须以描边/实心、图标、文字和证据上下文区分；颜色本身不能提升声明等级。

## 5. 字体与文案

- UI 与展示：Roboto，中文回退 Noto Sans SC，再回退系统 sans-serif。
- 技术数据：Roboto Mono / 系统 monospace，仅用于采样率、位深、缓冲、时间戳和可信度数据。
- 标题主要使用 600 字重；正文使用 400；按钮和标签使用 600。
- 文案短、事实化、可行动。默认只告诉用户下一步，高级证据通过明确入口展开。
- 不用全大写营造品牌声量；`SYSTEM MIXED` 等固定证据状态例外。

## 6. UI 体系与信息架构

正式体系只有一套，不是三套互不相干的播放器皮肤：

- **A / Library Dark**：品牌的深色主表达，也是默认展示基准。
- **B / Library Light**：A 的完整浅色主题，对应层级、组件、导航和交互完全一致。
- **C / Now Playing**：A/B 系统中的完整播放页，可随系统主题显示深色或浅色；专辑色只形成受控氛围层。

顶层导航的稳定语义 ID 固定为 `Library / Now / Chain`；界面文案需要本地化，英文显示上述名称，简体中文显示 `曲库 / 正在播放 / 链路`：

1. Library 是首启和冷启动默认入口，负责选择音乐。
2. Now 是专注播放界面；没有当前曲目时只提示返回曲库选择一首。
3. Chain 是输出与证据入口；默认先显示可理解摘要，用户再展开源、解码、处理、路由和实时数据。没有播放会话时显示“开始播放后展示链路”，唯一主操作返回 Library。

曲目行只保留封面、标题、艺术家、播放状态和更多操作。时长、格式、采样率、路径和遥测进入详情、Now 或 Chain，不在默认列表堆叠。

## 7. 形状、材质与光影

- 专辑封面圆角 10 dp；控件 12 dp；独立表面 16 dp；只有短状态 chip 使用胶囊形。
- 默认通过色阶、留白和对齐分层，不以硬分割线和卡片网格分层。
- mini-player、菜单和底部浮层可使用小范围柔和阴影；普通曲目行无边框、无阴影。
- 完整播放页可使用专辑封面低频模糊光，但必须叠加稳定遮罩保护文字和控制器。
- 不支持、性能不足或开启减少透明度时，使用 Carbon Elevated 或 Frost Surface 的不透明回退，布局和可操作性保持不变。

## 8. 动效

| 语义 | 时长 | 处理 |
| --- | ---: | --- |
| 按压反馈 | 90–150 ms | 小幅色阶或比例反馈，不弹跳 |
| 随机 / 循环模式 | 160 ms | 只变化语义色、图标和微小比例；循环在同一入口 `Off → All → One → Off` |
| 普通状态切换 | 180 ms | 标准 easing，保持空间关系 |
| mini-player → Now | 240 ms | shared-axis / 容器转换 |
| Now → 来源 | 180 ms | 与进入方向相反的短 shared-axis 转换 |
| 上一首 / 下一首 | 220 ms | 仅封面与曲目身份按方向横移；运输控制台不跳位 |
| 列表进入或重排 | 220–280 ms | 只对变化项目运动，不整页漂移 |
| 减少动效 | 80 ms | 交叉淡入淡出，无位移、模糊缩放或视差 |

动效必须说明状态变化、空间来源或播放上下文；不得作为循环装饰。

## 9. 图标与插图

- 功能图标遵循 Android/Material 的熟悉语义，24 dp 视觉尺寸置于至少 48 dp 触控区域。
- 品牌标志不得替代“播放”“下一首”“返回”等功能图标。
- 空状态插图若使用 Twin Paths，只能作为低对比度身份暗示，不得变成大型装饰水印。
- 专辑封面是主要图像来源；无封面时使用安静的中性色占位，不生成虚假封面或音乐波形。

## 10. 资产清单与治理

正式源文件位于 `docs/brand/assets/`：

- `vesqen-mark-primary.svg`
- `vesqen-mark-inverse.svg`
- `vesqen-mark-monochrome.svg`
- `vesqen-app-icon.svg`
- `vesqen-lockup-primary.svg`
- `vesqen-lockup-inverse.svg`
- `vesqen-mark-construction.svg`
- `vesqen-notification-symbol.svg`
- `vesqen-visual-system-board.svg`
- `vesqen-visual-system-board.png`（由同名 SVG 渲染的审阅预览）

Android 运行时版本位于 `app/src/main/res/`。以后修改几何或核心色时，必须同时更新 SVG、Android VectorDrawable、`DESIGN.md`、`.impeccable/design.json` 与对比度测试记录，并在开发日志中写明迁移原因。
