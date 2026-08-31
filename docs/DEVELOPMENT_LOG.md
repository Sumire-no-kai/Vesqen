# 开发日志

本文件记录 Vesqen 的实现、验证和设计决策。目标是让后续开发、复盘和对外材料都能追溯到可复核的依据，而不是把计划或局部检查当作交付结论。

## 记录约定

- **已验证**：附带执行环境或命令；仅在该范围内成立。
- **待验证**：实现已经存在，但还没有获得对应的证据。
- **阻塞/无效结果**：保留原因，不将它写成通过或产品缺陷。
- 每次功能分支合并前，更新本文件的范围、决策、验证和遗留项。

## 2026-08-31 · 开发基线与 Git 工作流

- 已以远端 `master` 的 `6c4142b` 作为 M1-A 开发基线，工作在功能分支 `codex/m1a-local-playback`。
- 本次只会提交 M1-A 代码、测试和本开发日志；构建产物、IDE 状态和本机配置继续由 `.gitignore` 排除。
- 合并顺序：本地质量门禁 → 功能分支提交与 PR → 远端 CI → 合并。设备仪器测试的有效结果单独记录，不能被编译或 CI 的绿色状态替代。

## 2026-08-31 · M1-A 本地播放最小闭环

### 范围

M1-A 只实现本地媒体库到系统混音播放的最小闭环：

- 通过 `MediaStore` 读取本机音乐元数据并显示简单曲库。
- 使用 Media3/ExoPlayer 播放、暂停、上一首、下一首和进度跳转。
- 通过 `MediaSessionService` 支持后台播放、媒体通知与锁屏控制。
- 在界面和播放快照中统一声明 `SYSTEM MIXED`。
- 覆盖纯逻辑单元测试、基础 Compose 仪器测试和真机冒烟验证。

本阶段**不**声称 USB 独占、位完美输出、外接 DAC 格式协商或 DSP；这些属于后续需要设备矩阵证据的阶段。

### 实现记录

| 项目 | 结果 |
| --- | --- |
| 媒体访问 | `MediaStore` 查询只保留稳定的 `content://` URI、显示元数据和时长，不读取或展示真实文件系统路径。 |
| 权限 | Android 13+ 请求 `READ_MEDIA_AUDIO`；旧版本使用 `READ_EXTERNAL_STORAGE`。媒体访问确认后才请求通知权限，避免首启时权限目的混淆。 |
| 播放 | `PlaybackService` 创建 ExoPlayer 和 MediaSession；控制层通过 MediaController 发送播放队列和传输控制。 |
| 界面 | Compose 曲库、空/错误/加载态、已连接输出类型说明、正在播放栏、上一首/播放暂停/下一首/进度跳转。中文和英文资源均已补齐。 |
| 输出声明 | M1-A 固定显示 `SYSTEM MIXED`，只展示“已连接的输出类型”，不把它误写为当前真实路由或无损/位完美证明。 |
| 测试替换 | 删除初始化模板测试，新增曲目显示、进度换算、时长格式化的 4 个 JVM 单元测试，以及首启音乐授权提示的 1 个 Compose 仪器测试。 |

### 设计决策与问题处理

| 决策/问题 | 处理与理由 |
| --- | --- |
| 生命周期依赖建议升级到 2.11.x | 未升级。该版本要求 `compileSdk 37`，而 PRD 当前锁定 `compileSdk/targetSdk 36`；保留兼容的 2.9.2，待 SDK 基线整体升级时再处理。 |
| Manifest 要求导出的播放服务 | 服务为系统媒体浏览/会话发现而显式导出；已限定为 Media3 所需 intent-filter，并在 lint 中作局部、带理由的忽略。 |
| 首次启动即创建播放器控制器 | 改为用户首次播放时惰性创建。这样在尚未授权、也未播放时不绑定媒体服务，降低首启副作用并让权限仪器测试只测其目标界面。 |
| 通知权限与音乐权限同时请求 | 改为串行：先音乐，成功后再通知。库页和播放能力的前置条件更清楚，用户也能理解每个授权的用途。 |
| 设备 UI 树可能包含个人歌曲元数据 | 真机检查后只保留安全、聚合的断言（控件状态、输出声明、崩溃缓冲区）；日志、提交和报告均不记录歌曲标题、艺术家或文件路径。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| JVM 单元测试、lint、Debug 组装 | `./gradlew.bat testDebugUnitTest lintDebug assembleDebug` | **已通过（惰性控制器调整后）**：4 个 JVM 测试通过，lint 为 0 个错误，Debug APK 可组装。保留 6 条升级提醒：SDK 36 基线、Lifecycle 2.9.2 和 Compose BOM 均有可用更新；它们不是当前构建错误，SDK/Lifecycle 的取舍见上文。 |
| 真机手动冒烟 | vivo V2171A，Android 15 | **已通过（M1-A 手动路径）**：首启显示音乐访问；音乐与通知权限按顺序出现；曲库、`SYSTEM MIXED` 声明、播放/暂停、前后切换、后台服务均可工作；崩溃缓冲区为空。最新复核中，`MainActivity` 冷启动为 1.722 秒且无授权首启页显示“允许访问音乐”。未在日志中保存设备媒体内容。 |
| Compose 仪器测试 | `./gradlew.bat connectedDebugAndroidTest` 与直接调用 `AndroidJUnitRunner` | **未获得有效结果 / 待换环境复验**：测试 APK 和 runner 均成功安装，runner 反复进入唯一用例 `firstLaunchRequestsLocalMusicAccess`，却未回传通过、断言失败或应用崩溃的完成事件。一次在 runner 运行期间用外部 UI 树工具观察的诊断，触发设备 `UiAutomation` 的 `RuntimeException: Bad file descriptor`；该次结果已污染，不能归因为 Vesqen。随后在不并发访问 UI 自动化的干净重跑中，仍只得到用例开始事件，未得到完成报告。手动启动和 UI 复核通过，但这不能代替自动化通过结论。 |

### 下一步

1. 在干净的 Android 模拟器或另一台设备上重跑 `connectedDebugAndroidTest`，取得独立的通过/失败结论；不要在 runner 执行期间并发调用 `uiautomator`。
2. 补充通过验证后，提交功能分支、创建 PR、等待 CI，并仅在 CI 通过后合并。

## 2026-08-31 · M1-A 合并闭环

- 功能分支 `codex/m1a-local-playback` 已通过 PR #1 合并到 `master`，合并提交为 `37c822a0bf71ddaf1252ea06d6879595a91d0e25`。
- 合并前证据包括 JVM 单元测试、lint、Debug 组装、GitHub CI 和 vivo V2171A（Android 15）手动冒烟；Compose runner 未回传有效完成事件的限制继续保留，未被 CI 或手动检查替代。
- 因此，M1-A 的本地曲库、普通系统混音播放、后台会话和基础控制闭环可以作为后续开发基线；这不等于完整 M1、完整 UI 架构或任何 bit-perfect 阶段已经完成。

## 2026-08-31 · 正式视觉识别系统 v1.0

### 范围

本轮工作位于 `codex/visual-identity-system`，负责把已确认的 UI 方向和 Logo 从样例收敛为可版本化的正式基线：

- A 固定为深色 Library 主表达，B 是同一系统的浅色主题，C 是该系统中的完整 Now 播放页，而不是第三套皮肤。
- 确立 `The Quiet Signal` 视觉北极星和 `Library / Now / Chain` 顶层信息架构。
- 以对称 `Twin Paths` 双路径 V 作为正式标识；交付主标、反白、单色、App 图标、横向组合、构造图、24 dp 通知光学校正版和视觉系统板 SVG，以及视觉板 PNG 预览。
- 建立 `PRODUCT.md`、`DESIGN.md`、`.impeccable/design.json`、视觉识别指南和 Compose 品牌 token。
- 为 status chip、曲目行、mini-player 和底部导航分别提供深浅机器可读组件，避免 B 主题依赖后续开发者自行推断颜色。
- 替换 Android 模板紫色主题、机器人 launcher 图标和网格背景；增加专用 monochrome、应用内标志、API 31+ 启动画面和媒体通知小图标。
- 将品牌、导航、渐进式信息密度、动效、光影、不透明回退和可访问性要求同步到 PRD v0.5。

本轮不声称完整 Compose Library/Now/Chain 页面已经实现；正式资产和主题是下一轮 UI 重构的输入与约束。

### 设计决策

| 决策 | 处理与理由 |
| --- | --- |
| Logo 是否保留样例中的双 V | 保留“双路径”含义，但所有路径围绕 `x=54` 严格对称，移除单边上扬、尾部和播放三角，降低被读成对勾或箭头的风险。 |
| 主色如何区别于常见播放器绿 | 采用偏橄榄黄绿的 Signal Moss `#9FBF4B`，深色活跃值 `#BFD66B`、浅色实心活跃值 `#536B1E`；普通页面限制在约 10% 面积。 |
| `SYSTEM MIXED` 是否使用 Moss | 否。它与 `DIRECT SUPPORTED` 都是中性事实状态；AVAILABLE 用 Moss 描边，ACTIVE 用 Moss 实心，VERIFIED 必须附盾牌/证书提示和精确设备矩阵上下文。颜色不能单独提升证据等级。 |
| 深浅模式是否形成两套品牌 | 否。A/B 共享组件、布局、导航和语义，只替换对应 surface/on-surface 层级；Logo 和证据语义不随壁纸改变。 |
| Android 动态色默认行为 | 从模板的默认开启改为默认关闭。未来可作为主动选项，但固定品牌和证据状态不得漂移。 |
| 高版本透明与模糊 | 只用于完整播放页和确有分离关系的浮层，并要求 Carbon Elevated / Frost Surface 的不透明高对比回退。 |
| 旧密度 launcher WebP | 项目最低版本为 API 26，自适应 `mipmap-anydpi` 已覆盖支持范围；删除 10 个仍包含 Android 模板机器人的旧 WebP，避免仓库中存在冲突品牌源。删除可由 Git 恢复。 |
| 通知标志是否直接缩放 108 单位主标 | 否。状态栏 24 dp 下标准主标墨迹偏小，保留同一对称轴与双路径关系，将外/内路径光学校正为 9/8 单位和更大的外接范围；专用 SVG 与 VectorDrawable 同步。 |

### 实现问题与修复

| 问题 | 处理 |
| --- | --- |
| `android:windowLightNavigationBar` 在基础主题触发 API 27 lint 错误，而产品最低为 API 26 | 从基础主题移除该属性；运行时 edge-to-edge 继续负责受支持版本的系统栏外观，不提高最低版本，也不以 lint baseline 掩盖。 |
| Media3 1.11 的通知图标 API 与旧 Builder 示例不同 | 直接检查项目实际依赖字节码，确认 `setSmallIcon()` 位于构建后的 `DefaultMediaNotificationProvider`；改为 provider 实例配置。 |
| `DefaultMediaNotificationProvider` 属于 Media3 `UnstableApi` | 将 `@UnstableApi` 精确限定在 `PlaybackService.onCreate()`，避免把整个 Service 类型传播为 opt-in API。 |
| 沙箱环境首次无法下载 Gradle 9.5.0 | 在取得授权的网络/缓存环境完成下载和复验；该错误属于构建环境限制，不记为应用测试失败。 |

### 验证记录

| 检查 | 结果 |
| --- | --- |
| SVG / Android XML 解析 | **已通过**：正式资产和 Android 资源均可作为 XML 解析。 |
| `.impeccable/design.json` 解析 | **已通过**：schema v2 sidecar 可解析。 |
| 视觉板渲染 | **已通过**：使用本机 Chrome headless 以 1600×1200 渲染 SVG，并人工检查主标、色板和 A/B/C 关系。 |
| 品牌文字对比度 | **已通过（含实际组件组合）**：Ink Light/Carbon 15.54:1、Muted Dark/Carbon Elevated 9.18:1、Ink Dark/White 17.13:1、Muted Light/Frost 5.94:1、Moss Bright/Carbon Surface 11.00:1、Moss Deep/Frost 5.59:1、White/Moss Deep 6.02:1、Ink Dark/Moss Bright 10.64:1、White/Warning Deep 7.13:1、Ink Dark/Warning Bright 10.44:1。 |
| JVM 单元测试、lint、Debug 组装 | `./gradlew.bat testDebugUnitTest lintDebug assembleDebug` **已通过**；4 个 JVM 测试通过、lint 无错误、Debug APK 可组装。 |
| Android 15 安装与图标冒烟 | vivo V2171A **已通过（限定范围）**：`installDebug` 成功；`MainActivity` 冷启动 1.102 秒；系统应用信息页实际蒙版图标清晰显示 Twin Paths；检查期间 crash buffer 为空。未保存设备媒体内容，截图仅位于被忽略的 `app/build` QA 目录。 |

### 待验证 / 下一步

1. 在下一轮实现正式 Library、Now、Chain 页面、持久 mini-player、底部导航 / navigation rail 和详情层级；不得把视觉板当作已完成 UI。
2. 在 Android 13+ 验证主题 monochrome launcher，在 API 31+ 验证深浅启动画面，并检查媒体通知小图标的实际状态栏清晰度。
3. 完整 UI 落地后补充深浅模式、字体缩放、TalkBack、减少动效和窄/宽窗口的截图与自动化证据。

## 2026-08-31 · 正式 UI 壳层落地（进行中）

### 范围

本轮工作位于 `codex/implement-ui-shell`，将正式视觉识别系统落实到可运行的 Compose 界面，同时保持 M1-A 的实际能力边界：

- 实现固定顺序的 `曲库 / 正在播放 / 链路` 顶层目的地；窄窗口使用底部导航，宽窗口切换为 navigation rail。
- 实现曲库授权、加载、空、错误、搜索、紧凑曲目行与曲目详情层级；曲目行只显示标题和艺术家，详情只呈现目前真实可得的专辑和时长元数据。
- 实现跨目的地持续的 mini-player、完整 Now 播放页，以及只陈述事实的 Chain 页面。
- 以 Twin Paths 的中性占位视觉表示当前缺失的封面数据；它不是伪造专辑封面，也不为曲目补写不存在的采样率、编码或 DAC 信息。
- 补齐中英文文案、可测试的纯 `VesqenAppContent` 边界、减少动效策略与可访问的 48 dp 交互目标。

### 设计与实现决策

| 决策/问题 | 处理与理由 |
| --- | --- |
| 播放快照原先可能不触发 Compose 重组 | `VesqenViewModel` 改为拥有 `PlaybackSnapshot` 状态，并由 `PlaybackController` 发布更新。这样首次开始播放后 mini-player、Now 与 Chain 能收到真实状态变化。 |
| 输出链路是否展示“当前路由”或“位完美” | 不展示。M1-A 只固定声明 `SYSTEM MIXED`；已连接输出类型明确标注为枚举能力，不冒充活动路由，direct / bit-perfect 明确为不可用。 |
| 没有封面字段时如何避免页面空洞 | 使用品牌的 Twin Paths 中性占位组件，并在代码和文案中避免把它称为真实封面；未来加入媒体封面元数据后替换该数据源，而不是把视觉占位写入模型。 |
| Now、Library、Chain 是否做成三套皮肤 | 不做。三者共享同一 token、层级、导航顺序和状态语义；仅在信息密度与任务焦点上不同。 |
| 测试是否需要启动真实 Activity | 新增 Compose 测试直接驱动无 Android 依赖的 `VesqenAppContent`，不请求权限、不查询 MediaStore、不连接 Media3，从而能覆盖导航、空态、详情、mini-player 与 Chain 文案而不污染设备媒体数据。 |
| 减少动效支持 | 当系统关闭 Animator 时使用短 fade 回退；测试可注入减少动效策略。API 26 无公共“移除动画”偏好读取 API，因此不把该偏好伪装成已检测状态。 |
| 用户在系统设置中改完权限后界面可能陈旧 | 为 `VesqenApp` 注册生命周期 `ON_RESUME` 同步。这样从应用设置返回时会重新读取音乐和通知权限、刷新曲库或移除通知警告，而不是要求用户杀掉应用或再次点击重试。 |
| mini-player 是否重复展示输出标签 | 不展示。它只保留封面占位、标题/艺术家和三个播放控制，避免在 320–360 dp 窄屏压缩标题；可点的 `SYSTEM MIXED` 解释入口保留在 Now 页，跳转 Chain。 |
| 可点状态标签的无障碍语义 | Now 页的 `SYSTEM MIXED` 标签除了状态文本，还提供“查看播放链路”的动作标签；Chain 摘要中的非点击标签仍只朗读事实状态，避免把同一标签误读为可操作。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| JVM 单元测试、lint、Debug 组装、仪器测试源码编译 | `./gradlew.bat testDebugUnitTest lintDebug assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace` | **已通过**：6 个 JVM 测试通过、lint 无错误、Debug APK 可组装，新增 Compose 仪器测试源码可编译。 |
| Android 15 手动视觉冒烟 | vivo V2171A，浅色中文系统 | **已通过（限定为空态与导航）**：安装和启动成功；授权引导、曲库、Now 空态、Chain 空态、三目的地导航、Twin Paths 占位和 Moss 主操作层级均在实际屏幕检查；崩溃缓冲区为空。设备当时无可供本应用播放的本地曲目，因此不把此项写作真实播放状态的视觉或音频验证。 |
| Compose 仪器测试执行 | `./gradlew.bat :app:connectedDebugAndroidTest --stacktrace` | **未获得有效结果 / 待换环境复验**：设备生成 `0` 个测试的空报告。报告显示 UTP 在测试启动前重新安装 `app-debug.apk` 时收到 `ShellCommandUnresponsiveException`（`SplitApkInstaller.installCommit`），因此当前 Compose 用例尚未执行；约两分钟后仅停止已确认挂起的本机 Gradle 测试进程，设备 crash buffer 为空。不能将此记作测试通过或应用失败。 |
| 绕过 UTP 的安装诊断 | `adb install -r -t app-debug.apk` | **需要用户确认 / 未绕过安全提示**：vivo Package Installer 展示“未知来源”风险确认，继续安装按钮在确认风险前不可用。为避免代理自动确认设备安全提示，已返回并停止挂起的 ADB 安装进程；现有 Debug 安装仍保留。该现象佐证安装通道限制，不改动 runner 或测试源码。 |

### 后续验证

1. 在干净 Android 模拟器或另一台设备重新获得 Compose runner 的非空完成报告，并覆盖有真实本地媒体时的 mini-player、Now、详情与 Chain 状态；若继续使用本 vivo，需由设备所有者明确确认“未知来源”安装提示后再诊断，期间不并发使用 UI 自动化工具。
2. 在深色系统、字体缩放、TalkBack、减少动效以及宽窗口上补充设备级截图/自动化证据。
3. 后续 M 阶段接入可审计的输出链路数据前，继续只显示 `SYSTEM MIXED`，不扩展为 direct、独占或 bit-perfect 声称。

## 2026-08-31 · 播放器焦点页与真实封面修复

### 触发与范围

本轮工作位于 `codex/refine-player-experience`，由真实 Android 15 设备上的播放状态复现触发。复现确认了以下体验问题：本机曲目明明存在系统可显示的封面，但 Vesqen 始终绘制品牌占位；旧安装包的 mini-player 将 `SYSTEM MIXED` 挤成两行；Now 使用可纵向滚动的列表并保留底部导航，进度轨道过重；随机、循环、信息入口与会话信息层缺失；系统 Back 未被单 Activity 拦截而直接回到桌面。

本轮只修复这些 M1 体验/元数据路径，不扩大为输出证明、音频格式遥测、直接输出或 bit-perfect 工作。

### 实现记录

| 项目 | 结果 |
| --- | --- |
| 真实封面 | `AudioTrack` 与 MediaStore 查询补充 `ALBUM_ID`、provider-owned album artwork URI、修改时间与扫描 revision。`AlbumArtworkLoader` 在 IO 线程使用 Android 10+ `ContentResolver.loadThumbnail()`，先查专辑 collection、再安全地尝试具体媒体 item；只用 `content://` URI、16 MiB 进程内缓存，不复制用户封面到磁盘。Media3 控制器重连但曲库尚未回填时，快照也会把 source/artwork URI 回传给 mini-player 和 Now。无法读取时才显示 Twin Paths 中性占位，播放不会因此失败。 |
| 封面兼容性与缓存决策 | 不使用 Android 10+ 已弃用的 `ALBUM_ART` 文件路径；同一扫描内 album provider 缩略图按 artwork URI/尺寸/revision 共享，媒体 item 缩略图则按曲目 URI/修改时间/尺寸隔离。相同 key 的并发请求会合并，失败结果短暂负缓存。重扫或撤销音乐权限会提升 cache epoch、清空缓存和进行中的 key；旧任务允许自然结束但不能写回新一代缓存。撤权同时清除 Library 里的旧媒体行，`AlbumArtwork` 在新请求前同步置空 state；异步曲库扫描也有 refresh epoch，过期或撤权后的结果不能重新写回曲库。M1 不调用 `MediaMetadataRetriever.embeddedPicture`：该 API 会在应用检查大小前分配完整 APIC byte array；低版本/异常媒体的有界内嵌封面解析留给具备专门格式测试的后续工作。 |
| mini-player | 固定为 72 dp 单行：真实封面、标题/艺术家、上一首、播放/暂停、下一首。`SYSTEM MIXED` 不再属于 mini-player，且新增 320 dp 宽回归用例避免状态文案重新挤占标题空间。 |
| Now 焦点页 | 删除 `LazyColumn`，紧凑窗口隐藏底部导航，改为无纵向滚动的约束式全高播放页。曲名固定为较小的单行；超长名称使用横向跑马灯而不换行。短屏/大字体按层级缩小封面和间距；极端字体缩放会把次要 artist/route chip 留在横向会话页，优先保证进度、五键控制和 `i` 入口始终可见。它采用正式设计板 C 的受保护 Midnight Violet 焦点面、真实封面低强度氛围、4 dp 可视进度轨道/12 dp thumb/48 dp 触控区与五键控制布局。 |
| 播放能力 | `PlaybackSnapshot` 与 `PlaybackController` 现在传递 Media3 的随机、循环（关闭/列表/单曲）和队列位置状态；随机、循环按钮调用真实 Media3 setter，不是仅改变图标。 |
| 信息层 | 底部圆形 `i` 打开曲目详情；Now 可左右滑至播放会话页，显示真实的播放状态、已播放/剩余时间、队列位置及 `SYSTEM MIXED` → Chain 入口。不会补写当前实现没有的 PCM、码率、采样率、直出或 bit-perfect 数据。 |
| Android Back | 新增纯 `VesqenNavigationState`：mini-player → Now 的 Back 返回 Library；Now 内进入 Chain 的 Back 先回 Now，再回 Library；Library 才交给系统退出。Now 详情打开时 Back 先关闭详情。工具栏 Back 与系统 Back 共用同一状态机。 |

### 设计与缺陷决策

| 决策/问题 | 处理与理由 |
| --- | --- |
| 正式 C 版与浅色曲库如何一致 | Library 仍可有深浅两套完整主题；有活动曲目的 Now 作为受保护的深色听音焦点面，直接对齐正式视觉板 C。该空间例外已同步到 PRD 和 `DESIGN.md`，不新增顶层目的地，返回后恢复稳定导航。 |
| 是否用纵向滚动容纳短屏内容 | 不使用。标题永远单行，长标题横向跑马灯；封面、间距和极端字体缩放下的次要信息会按可用高度收缩或移入已有横向会话页，避免“播放器页面上下还有空白可滑”的行为。 |
| 第一次封面组件编译 | Compose 的委托状态不能被 smart-cast；已将状态读取为局部不可变 bitmap 再渲染，随后重新编译通过。 |
| 提交前封面审查 | 将“重新扫描后仍命中旧图”、同一专辑列表行重复解码、权限撤销后 Compose 保留旧 bitmap、清理前任务回写、旧扫描结果在撤权后写回，以及 Media3 重连时退回占位列为 P1，并在提交前直接修复。审查还确认 `embeddedPicture` 的后置 byte 限制不能阻止前置分配，故 M1 直接禁用该不安全回退而非留下错误的“已防护”结论。 |
| 真机更新安装 | 设备 Package Installer 再次要求所有者勾选“未知来源”风险确认才可继续。没有勾选、没有绕过，也已关闭安装器；因此新 APK 的真机视觉/手势结果仍待所有者明确确认安装后复验。 |
| 实机复现复核 | 再次启动当前已安装包并执行实际向上滑动，确认它仍是旧版：mini-player 仍有两行 `SYSTEM MIXED`，Now 仍为大号双行标题和纵向可滑白色页面。此证据不能代表新源码已部署，恰好说明必须先由设备所有者完成系统安装确认后再验收。 |
| 诊断截图 | 已查看旧版 Library、mini-player 和 Now 的真实截图，用于确认问题；首次三张 `C:\tmp` PNG、设备临时 UI XML，以及本次四张可视化目录临时 PNG 均已删除，未进入仓库。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| JVM 回归测试 | `./gradlew.bat :app:testDebugUnitTest --rerun-tasks --stacktrace` | **已通过**：6 个 suite、13 个测试，0 failures、0 errors。覆盖新增封面缓存 key、队列位置/循环状态和 Back 状态机。 |
| 完整本地质量门禁 | `./gradlew.bat testDebugUnitTest lintDebug assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace` | **已通过**：Debug APK 可组装、lint 无错误、JVM 测试任务通过；Compose UI 回归源码可编译，覆盖 mini 无 `SYSTEM MIXED`、320 dp 行高、横滑信息、详情、扩展控制，以及 480 dp 高/2×字体下长标题单行和基础控制可见性。 |
| 新 APK 真机 UI/手势 | Android 15 物理设备 | **待验证 / 未绕过系统安全确认**：APK 已构建，但安装被设备所有者确认步骤拦截。旧安装包只用于问题复现，不能替代新版验证。 |
| Compose 仪器测试执行 | `connectedDebugAndroidTest` | **待验证**：此前 UTP 安装阶段没有取得有效完成报告；本轮因同一设备安装确认门槛未重复运行，不把测试源码可编译误报为已执行。 |

### 后续验证

1. 由设备所有者确认测试 APK 安装后，复验真实 MediaStore 封面在 Library / mini-player / Now 的三处一致性，随机/循环状态、横滑会话页、工具栏 Back 与系统手势 Back。
2. 在模拟器或另一台设备取得 Compose runner 的非空完成报告，覆盖新增 320 dp 断言与 horizontal pager 手势。
3. 在深色/浅色 Library、字体缩放、TalkBack、减少动效和宽窗口中补齐设备级视觉验证；Now 的深色焦点面与可访问的可读性回退必须同时检查。

## 2026-08-31 · 焦点播放器运输台收口与真机复验

### 触发与范围

在新 APK 已实际安装后复看 Now，确认上一首/下一首并非缺少业务回调，而是深色焦点页继承了外层浅色 `LocalContentColor`：`Ink Dark #1B1C18` 落在 Midnight Violet `#1E1B2B` 上的对比度约为 1.02:1，按钮在真机上近乎不可见。同时，首版无纵向滚动的页面虽然修正了滚动问题，但封面和控制区之间的留白没有承接正式 C 版的构图。

本轮不扩大 M1 的音频/输出能力；只收口焦点页的视觉层级、上下曲可发现性、小屏约束和对应测试证据。

### 设计与实现决策

| 决策/问题 | 处理与理由 |
| --- | --- |
| 深色焦点页的前景色与系统栏错误 | 在嵌套深色主题内使用 `Surface(color = MidnightViolet, contentColor = onSurface)`，而非仅绘制自定义背景。这样返回、标题、上下曲和 `i` 显式获得高对比前景；播放器将状态栏背景设为 Midnight Violet、导航栏回退色设为 transport dock 的 `surfaceContainer`，关闭 API 29+ 的自动 contrast scrim，并保存/恢复原有颜色、contrast 与图标外观。系统图标和手势横条仍保持浅色，因为公开 API 不能自定义其品牌色且深色背景上必须可读。 |
| 底部导航与运输台断色 | 原先把 `navigationBarsPadding()` 放在 dock 外层，透明导航栏下露出了上半页 Midnight Violet。改为让 dock 的 `Surface` 延伸到窗口底部，仅让其内容避开导航手势区；这样 Android 15+ 的 edge-to-edge 透明导航栏也会直接显示运输台色。 |
| 宽屏焦点页的系统栏分裂 | 有活动播放时，Now 让顶层导航（包括宽屏 rail）让位并覆盖整个窗口。否则透明状态栏会同时跨越浅 rail 与深播放器，单一图标策略必然有一侧不可读；回到顶层目的地后 rail 与原系统栏策略恢复。 |
| 画面过空 | 改为“封面舞台 + 一个不透明底部运输台”。封面保留低强度氛围层和简洁边框；标题、状态、进度与控制被一个有意的底部表面锚定，避免用装饰卡片或硬线条填空。 |
| 上下曲层级 | `上一首 / 播放暂停 / 下一首` 改为居中的三枚主运输控制，正常窗口为 56 / 72 / 56 dp；随机、循环、分页点和圆形 `i` 转入 48 dp 次级底栏，保留所有功能但不与主控竞争。 |
| 单行与小屏 | 曲名和 Now 页头均固定单行；曲名仅允许水平 marquee，页面无 `verticalScroll`。320×480 dp、360×533 dp、360×640 dp、640×320 dp 横屏、浅色宿主及 2× 字号均通过响应式分支收缩封面/间距和次要元数据；窄宽 footer 自动隐藏中间的会话文字，避免挤压循环与详情。 |
| 事实状态 | `SYSTEM MIXED` 继续仅作为 Now/Chain 中可解释的事实状态；mini-player 维持单行，不显示该 chip。没有新增 codec、采样率、直出或 bit-perfect 声称。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| Kotlin 与 Compose 测试源码编译 | `./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --stacktrace` | **已通过**：新增浅色宿主、320×480/2× 字号、360×533/2× 字号、360×640、640×320 横屏、无纵向 scroll 语义、Now 三键回调与真实宽屏 rail 让位用例均可编译。 |
| 本地质量门禁 | `./gradlew.bat testDebugUnitTest lintDebug assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace` | **已通过**：6 个 JVM suite、13 个测试，0 failures、0 errors、0 skipped；lint 无错误；Debug APK 与 Android 测试源码均可组装/编译。 |
| 播放页修复 APK 部署一致性 | Android 15 物理设备 | **已通过**：播放器布局修复的本地 Debug APK SHA-256 `D6227A37AD46F9DA8CE2764C62C0A958F4496426431580D901E3EDA110CFDCD5` 与设备已安装 `base.apk` 一致；应用更新后曲库可读。 |
| 播放页真机手动回归 | Android 15 浅色系统、真实本地媒体 | **已通过（UI/交互范围）**：该布局修复 APK 启动后进入 Now；真实封面、深色系统栏、封面舞台、运输台和高对比上下曲已进行屏幕核查；纵向上滑后主控边界不变；下一首实际切换、上一首恢复原曲，播放/暂停可往返切换，Android Back 返回曲库；未见应用 `FATAL EXCEPTION`。临时截图和设备 UI XML 均已删除。 |
| 系统栏衔接修复部署 | Android 15 物理设备、最新本地 Debug APK SHA-256 `6F0D46266FB7E75B9A1F3158C95476A68BE761D6ADD16EB97554AEBFA378AA08` | **已通过（手势导航）**：设备已安装的 `base.apk` 与本地 APK 哈希一致；进入 Now 后人工核查状态栏延续 Midnight Violet 场景、底部手势导航区延续 transport dock 的 `surfaceContainer`，未见自动 contrast scrim 或紫色断层。临时截图和设备 UI XML 已删除。三键导航未验证，未更改设备系统设置。 |
| Compose 仪器测试执行 | `:app:connectedDebugAndroidTest` 与直接安装测试 APK | **未执行 / 不计为通过**：Gradle 仅生成测试 APK，未产生运行结果；设备对第三方测试 APK 强制要求所有者指纹验证。未绕过或关闭该保护。该尝试清除了目标应用但没有安装测试包，已立即重装上述精确 Debug APK、复核哈希并恢复原有运行时权限。 |

### 后续验证

1. 在允许自动安装测试 APK 的模拟器或设备取得非空 Compose runner 报告，执行新增的小屏、字体缩放、浅色宿主和三键回调用例。
2. 补充 TalkBack、减少动效、横屏/宽窗口和深色系统下的设备级检查；这些是可访问性与适配性门槛，不由当前单机冒烟替代。

## 2026-08-31 · 播放模式单一入口与有意义动效

### 触发与范围

本轮位于 `codex/refine-player-modes-motion`。用户指出 Now 底栏的循环不应拆成彼此独立的“循环”和“单曲循环”按钮，并要求播放器打开/返回及上一首/下一首具备有意义、克制的动效。

本轮只处理循环模式交互、模式反馈和空间连续性；不改变音频播放语义、不新增输出或格式声明，也不提前修改 Now 页的背景、材质或主题色。后者保留给行为变更合并后的独立视觉分支，避免把交互回归与色彩判断混在同一个 PR。

### 设计与实现决策

| 决策/问题 | 处理与理由 |
| --- | --- |
| 单曲循环是否另设一颗按钮 | 不设。底栏保持单一熟悉的循环图标，按 `关闭 → 列表循环 → 单曲循环 → 关闭` 轮换；单曲状态使用 Material 的 `RepeatOne`（带 `1`）图标。这样不挤占圆形 `i`、分页点或随机入口，也与常见播放器的学习成本一致。 |
| 如何让用户知道一次点击真的切换了模式 | 关闭态采用弱化的中性色；列表循环与单曲循环使用 Signal Moss。随机和循环均以 160 ms 的颜色与微小比例过渡反馈状态，循环图标在普通动效下以淡入/缩放交替切换。48 dp 触控区域不随图标变化，TalkBack `stateDescription` 始终陈述“循环关闭 / 列表循环 / 单曲循环”。 |
| 上下曲如何既有过渡又不让控制器跳位 | 上一首/下一首调用会记录对应方向；只有封面舞台和曲目身份在 220 ms 内作小幅定向横移、淡入和收敛缩放。进度、三枚主运输控制、模式 footer 与 `i` 保持原位，避免像整页卡片切换一样失去操作连续性。 |
| 进入与返回如何避免生硬 | mini-player → Now 使用 240 ms 由下向上的 shared-axis 进入；Now → 来源使用 180 ms 反向离场。系统关闭 Animator 时，所有上述运动统一降级为 80 ms 短交叉淡入淡出，不保留位移或缩放。 |
| 首次编译错误 | 首轮 Kotlin 编译准确发现 `animateColorAsState` 与 `graphicsLayer` 的 Compose 包路径错误；仅修正导入后重新通过完整门禁。该问题未进入运行时 APK。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| 本地质量门禁 | `./gradlew.bat testDebugUnitTest lintDebug assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace`；随后 `:app:testDebugUnitTest --rerun-tasks` 与 `:app:compileDebugAndroidTestKotlin` | **已通过**：Debug APK 组装、lint 与 Android Compose 测试源码编译均成功；新鲜 JVM 回归为 6 个 suite、13 个测试、0 failures、0 errors。新增用例覆盖一个循环控件的三态轮换/无障碍状态、切歌后封面舞台与运输控制保持可达，以及 320×480 dp/2× 字号、360×533 dp/2× 字号、640×320 dp 下未裁切的原始 bounds 与 48 dp 触控范围。 |
| 真机安装一致性 | Android 15 物理设备 | **已通过**：设备所有者完成系统更新确认后，本地 Debug APK 与设备已安装包的 SHA-256 一致；应用可读取真实本地媒体。未绕过 Package Installer 的安全确认。 |
| 真机交互与动效核查 | Android 15 物理设备、真实本地媒体 | **已通过（UI/交互范围）**：单一循环入口依次呈现关闭→列表循环→单曲循环→关闭，随机反馈可开关并复原；上一首/下一首实际改变当前曲目身份而运输控制保持位置；受控页内纵向手势不移动播放器几何；系统 Back 与工具栏 Back 都回到曲库；crash buffer 为空。截图和设备 UI XML 均在查看后删除。动效时长由代码和测试策略约束，未将人工观察写作精确时序测量。 |
| Compose 仪器测试执行 | `./gradlew.bat connectedDebugAndroidTest --console=plain --stacktrace` | **未获得有效完成报告 / 不计为通过**：测试部署后只记录到首个用例启动，设备前台随后切换为另一项正在运行的工作，未生成通过或失败结果；没有 Vesqen crash 记录。只终止了本轮已确认挂起的两个本机 Gradle 测试进程，未干预设备上的其他任务。后续应在空闲设备或模拟器重跑。 |

### 后续步骤

1. 设备所有者完成当前系统更新确认后，先比对新 APK 与设备安装包哈希，再做上述真机手动回归。
2. 获得通过的本地与真机证据后，创建行为变更 PR、等待 CI，并按既定 Git workflow 合并。
3. 从更新后的 `master` 新开独立分支，仅重做 Now 页的背景、控制台材质与受控光影；不在这一条行为分支掺入主题色改动。

## 2026-08-31 · Now 夜间石墨材质与受控封面反光

### 触发与范围

在播放模式与动效分支合并后，用户要求不再保留“墨绿色与蓝黑色/紫色割裂”的焦点页，而是在不复制其他播放器品牌、也不扩大 M1 音频能力的前提下，重做 Now 的背景、运输台材质与光影。本轮位于 `codex/rework-now-materials`，只影响有活动播放时的全屏 Now 与其信息页；曲库、mini-player、输出事实语义和播放控制行为保持不变。

### 设计与实现决策

| 决策/问题 | 处理与理由 |
| --- | --- |
| 焦点页是否继续使用可感知的紫色/绿色分区 | 不继续。Now 采用专属的 **Nocturne Graphite / 夜间石墨** 中性材质阶梯：Canvas `#101415`、Dock `#191F20`、Raised `#202728`、Artwork Frame `#252C2D`。Signal Moss 继续只表示可操作的激活/正向状态，而不是铺满背景或运输台。 |
| 封面氛围是否做成大面积渐变或“假封面” | 不做。只有真实 bitmap 加载后才显示 36 dp 模糊的封面反光；其 alpha 为 22%，再经 82% Canvas scrim，最终可见量严格为 3.96%。无封面、不可读 URI、加载失败或 API 26–30 时均保持纯不透明 Canvas，不把 Twin Paths 占位或未模糊图片伪装成光源。 |
| 旧 Android 上的 `blur` | Compose 的平台 blur 在 Android 12（API 31）以下不保证真实效果。反光明确以 API 31 为门槛，较低版本直接使用不改变布局的 Canvas 回退；新增 JVM 边界用例锁定 API 30/31 行为。 |
| 如何界定运输台而不回到硬线条 | 使用一个 20 dp 的 ambient + spot **Player Lift** 阴影作为唯一边界。Dock 不加 divider、border、glow、渐变或第二层阴影；导航手势 inset 放在全宽 Dock 内，避免高版本 edge-to-edge 下出现底部断色。 |
| 封面舞台和背景异步加载不一致 | 背景与舞台复用同一安全 `AlbumArtworkLoader` 路径（专辑 URI 失败后可回退媒体 item thumbnail）。反光测试标记只附在成功加载的 `Image`，而不是其中性的加载容器，因此不可读 URI 不会声称存在反光。 |
| 可读性是否由视觉印象决定 | 不依赖主观判断。固定前景在 Canvas/Dock/Raised 上分别计算对比度：Ink Light/Canvas 15.03:1、Ink Light/Dock 13.53:1、Muted/Raised 9.08:1、Moss/Dock 10.37:1；这些均高于 WCAG AA 门槛。 |
| 规范是否会与实现再次漂移 | 同步更新 `Color.kt`、`DESIGN.md`、`docs/brand/VISUAL_IDENTITY.md`、PRD 及机器可读的 `.impeccable/design.json`；本地 Impeccable 检查对 `NowScreen.kt` 没有额外发现。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| JVM 单元测试、lint、Android 测试源码编译 | `./gradlew.bat testDebugUnitTest lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --stacktrace` | **已通过**：7 个 JVM suite、16 个测试、0 failures、0 errors；Android Compose 测试源码可编译。Lint 保留 13 条既有项目提醒，但本轮修改的 `NowScreen`、`AlbumArtwork` 和 `OutputStatusChip` 为 0 条结果。 |
| 常规 APK 输出目录 | `./gradlew.bat assembleDebug` | **外部文件占用 / 非代码失败**：Windows 持续占用旧的 `app/build/outputs/apk/debug/app-debug.apk`，Gradle 无法删除它。未猜测或终止 ADB、IDE、系统扫描或用户进程。 |
| 隔离 APK 组装 | `./gradlew.bat -I C:/tmp/vesqen-isolated-build.init.gradle.kts assembleDebug` | **已通过**：仅将本轮输出重定向到临时目录；未修改项目的正式 Gradle 配置，成功生成新的 Debug APK。 |
| 真机截图/手动视觉回归 | Android 15 物理设备 | **待验证 / 不计为通过**：检查时设备前台正在运行用户的其他工作，因此未覆盖安装、未拉起 Vesqen、未截屏，也未运行 Compose runner。真机将重点核查真实封面、无封面回退、Canvas/Dock 系统栏衔接和轻主题宿主进入/退出 Now。 |

### 后续步骤

1. 在设备空闲且由所有者确认后，安装隔离构建的 APK，核查上述 Now 视觉场景并在查看后删除临时截图/UI XML。
2. 设备级截图确认前不合并本分支；截图通过后创建 PR、等待 CI，再按 Git workflow 合并。
3. 继续将 `SYSTEM MIXED` 限定为 M1 事实声明，不由新的光影或封面呈现推导 direct、独占、无损或 bit-perfect 结论。

## 2026-08-31 · Now 固定骨架与会话原位过渡

### 触发与范围

真机反馈指出原来的 Now 会在左右滑动时像相册一样拖动整张页面：封面、信息页和底部运输台一起离开/进入，既破坏了播放器的空间连续性，也让原本应该始终可用的主控制显得不稳定。本轮仍在 `codex/rework-now-materials`，重做 Now 内部会话信息的交互层级，并同时修复真机复现的上一首/下一首可见却不可执行问题；不改变 M1 的事实声明边界，也不把会话升级为新的顶层目的地。

### 设计与实现决策

| 决策/问题 | 处理与理由 |
| --- | --- |
| 为什么不能仅调 Pager 的动画参数 | 根因是 `HorizontalPager` 承载了两张各自拥有 dock 的完整页面；即使减慢或淡化位移，用户仍会感到整页像照片一样被替换。直接删除 Pager 与 page state。 |
| 上一首/下一首为什么会显示可点却不切歌 | 真机复现表明 timeline 的 `hasPrevious/hasNext` 不等于当前 MediaSession 已授权专用上一/下一首命令。执行路线改为：优先使用 shuffle-aware 的相邻 index + `seekToDefaultPosition`；专用命令可用时再回退；两者均不可执行时以 `canSkipPrevious/canSkipNext` 禁用 mini-player 与 Now 控件，避免假可用状态。该选择以纯函数单元测试和真机来回切歌回归保护。 |
| 哪些元素必须固定 | Nocturne Canvas/封面反光、系统栏、Now 页头与 Back、一个运输台、曲目身份、route chip、进度、上一首/播放暂停/下一首、随机、循环和 `i` 始终留在同一空间位置。 |
| 会话信息如何可发现 | 原分页点替换为 dock 中央明确可点的“播放会话 / 专辑封面”入口；`i` 继续只打开曲目详情，职责不混淆。TalkBack 同时得到动作描述与当前状态。 |
| 如何避免生硬切屏 | 仅上部固定尺寸的 focus stage 在封面与会话事实卡间使用 `AnimatedContent` 的受控淡入淡出与 0.985 微缩放；不使用 horizontal slide、整页拖动或布局重排。减少动效时退化为现有的短交叉淡入淡出。 |
| 切歌时为什么仍需要局部方向动效 | 会话开关只以 focus content 为 target，不能因此丢失上一首/下一首的封面过渡。Artwork 分支内部继续以 track presentation 为 target，保留 220 ms、受固定 stage 裁切的定向淡入/微缩放；身份和运输台不横移。 |
| 返回行为 | Android Back 的优先级为：曲目详情 → 已展开的播放会话 → 既有的 Now 返回来源逻辑。因此查看会话不会让一次返回直接离开播放器。 |
| 小屏/大字体 | 会话卡与封面共享同一个上部 stage；紧凑状态省略标题和剩余时间，极端字号再把会话卡收敛为状态与队列，进度仍由固定运输台提供，避免在 112 dp stage 中裁切或滚动。此时 route chip 改为 footer 内唯一的 48 dp AccountTree → Chain 入口；窄宽 footer 的随机/会话/循环/详情则改为四颗均分的 48 dp 图标，消除中间文字按钮与右侧控件重叠。 |
| 规范一致性 | `DESIGN.md` 和 PRD 已将“横向分页/横向切换”改为“明确会话入口 + 原位内容过渡”，避免未来实现重新引入照片式分页。 |

### 验证记录

| 检查 | 环境/命令 | 结果 |
| --- | --- | --- |
| Kotlin 与 Android Compose 测试源码编译 | `./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --stacktrace` | **已通过**。 |
| JVM 回归 | `./gradlew.bat testDebugUnitTest --rerun-tasks` | **已通过**：8 个 suite、20 个测试、0 failures、0 errors；包括相邻曲目执行路线的纯函数覆盖。 |
| Lint 与 Android UI 测试编译 | `./gradlew.bat testDebugUnitTest lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --stacktrace` | **已通过**：最终工作树的 lint 为 0 errors、13 条既有 warnings；Android Compose 测试源码可编译。 |
| Compose UI 回归覆盖 | `VesqenAppTest` 源码 | **已编译**：会话显式入口、左滑不切换会话、切换前后固定壳层 bounds 相同、无 horizontal scroll 语义、Android Back 先收起会话，以及 320 dp/2×字号下会话卡、footer 触控边界和极端字号 Chain 入口均有源码回归。有效 runner 报告仍待空闲设备或模拟器。 |
| Impeccable 静态复核 | `detect.mjs --json NowScreen.kt` | **无命中**；该结果只辅助代码审查，不能替代实机动效与视觉验收。 |
| 真机安装与视觉回归 | Android 15 物理设备、真实本地媒体 | **已通过（普通字体/实际交互范围）**：最终隔离 Debug APK 与设备安装包一致。横向滑动不会打开会话；明确入口才会原位打开会话，header、进度与固定运输台控件 bounds 在前后相同，footer 无重叠，UI tree 无横向 scroll 节点。Android Back 先收起会话，再从 Now 回曲库；上一首/下一首实际改变并复原曲目；正常 route chip 仍能进入 Chain；本轮 crash buffer 无应用记录。临时截图与 UI XML 将在记录后删除。 |
| Compose 仪器测试执行 | `connectedDebugAndroidTest` | **仍待有效 runner 报告 / 不计为通过**：本轮未在用户设备上重新部署测试 APK，以免打断设备当前的手动验收；源码编译、JVM 回归和手动真机检查不能替代该门禁。 |

### 后续步骤

1. 由设备所有者在最终包上确认 Now 的视觉与交互感受；确认前不创建或合并 PR。
2. 获得独立的空闲设备/模拟器窗口后，重跑 `connectedDebugAndroidTest` 并取得完整 runner 报告；不要把当前的手动验收替代为仪器测试通过。
3. 继续将 `SYSTEM MIXED` 限定为 M1 事实声明，不由新的光影或封面呈现推导 direct、独占、无损或 bit-perfect 结论。
