# Vesqen 问题与修复案例集

本文件面向项目复习、工程学习与面试准备，持续收录有证据的问题。时间线与原始验证记录见 [开发日志](DEVELOPMENT_LOG.md)，职责和长期取舍见 [架构审查](ARCHITECTURE_REVIEW.md)。

## 阅读约定

- 根因分为“代码可确认”“设备复现确认”“待验证假设”，不把猜测写成结论。
- “已实现修复”不等于通过真机验收，更不等于发布。每次运行的证据追加到开发日志。
- 历史案例依据现有日志整理，不声称恢复了未被记录的全部开发过程。
- 面试讨论点用于解释技术决策，不虚构性能改善比例、生产事故、用户规模或商业收益。

## R01：曲库更新后跳回顶部

**背景 / 2026-09-07 review / P2**：用户在全部歌曲中向下浏览，此时播放开始、自动切歌或收藏变更，会刷新 `AudioTrack` 列表。

**根因（代码可确认）**：标题索引以整个 `List<AudioTrack>` 为 key；`playCount`、`lastPlayedAtMs`、`isFavorite` 等与标题排序无关的字段也会让索引失效。索引重建期间，条件分支用 `LibraryLoading` 替代 `LibraryTrackList`。后者内部的 `rememberLazyListState()` 随组件销毁，不是简单重组，重新挂载时滚动位置回到起点。

**解决方案**：

1. 提取只含稳定 ID 和标题的排序 key，历史与收藏变化不重新运行 ICU collation。
2. 索引后台更新时继续保留列表和上一份顺序；不能为了一个派生索引卸载用户正在操作的内容。
3. 根据 ID 把旧顺序投影到最新对象，及时更新收藏/历史、排除已删除曲目、纳入新曲目，避免“保留旧顺序”变成“保留旧元数据”。
4. 新索引尚未对应当前标题集合时暂停字母定位，防止旧 section offset 跳到错误曲目；列表仍可正常浏览。

**代码**：`LibraryScreen.kt`、`library/LibraryTitleIndex.kt`。

**回归**：`LibraryTitleProjectionTest` 覆盖依赖 key、最新元数据、删除/新增/重命名；`LibraryRefreshUiTest` 用 150 首夹具滚动到第 80 项后刷新收藏与历史，检查曲目仍可见。执行状态见本日开发日志。

**面试讨论点**：重组与卸载有什么不同？派生状态真正依赖哪些字段？为什么不能仅记住旧对象列表？本修复不等于证明 iQOO 所有快速滑动掉帧均已解决。

## R02：重复点播同一首歌漏记收听历史

**背景 / 2026-09-07 review / P2**：曲库扫描会替换正在播放项目的元数据；用户再次点播同一首歌也会替换播放队列。两者都可能产生 `PLAYLIST_CHANGED`。

**根因（代码可确认）**：旧 gate 仅比较相同 track ID 和事件 reason，混淆“同一首歌曲”与“同一次播放”。为了防元数据刷新重复计数，错误地抑制了用户真实重新点播。

**解决方案**：新建队列项时生成 occurrence token，元数据同步保留该 token；服务侧只有在相同歌曲、相同 occurrence、`PLAYLIST_CHANGED` 三者都成立时才保留已计数状态。显式新队列产生新 token，repeat/seek 仍是新的进入事件。仍需 `isPlaying` 后才计数，暂停/恢复不会被误算。

**取舍**：歌曲 ID 是持久身份，occurrence 是队列项的运行期身份，不能拿 URI 或歌名替代。token 放在 MediaItem metadata extras 中跨 MediaController/MediaSession 传递，不改变曲库主键或数据库格式。缺少 token 时不猜测“只是元数据更新”。

**代码 / 回归**：`PlaybackController.kt`、`PlaybackStateKeeper.kt`、`PlaybackControllerPolicyTest`。覆盖元数据刷新、同曲目新队列、缓冲后真正开始、重复回调、未知身份以及 repeat。

**面试讨论点**：内容身份、队列出现位置、播放事件身份是三个不同概念；去重必须由业务身份定义，而不是仅靠值相等。

## R03：重复 URI 的预加载污染当前曲目码率

**背景 / 2026-09-07 review / P2**：唯一 URI 正在播放时，用户通过“下一首播放”或“加入队列”再加一次同曲目。队列变了，当前项目不一定切换。

**根因（代码可确认）**：URI 唯一性只在媒体切换时缓存。后续相同 URI 的预读取就可能被算进当前 occurrence 的字节总数，导出的 read bitrate 看似精确，实际来源错误。

**解决方案**：在 Media3 timeline 更新时重新判断当前 URI 的唯一性。一旦不唯一，解绑已活动 transfer，使本次 occurrence 的字节/码率证据不可用。即使后来删除重复项，也不把不可判定的旧读取追认为当前曲目；下一次具备明确归属的新 occurrence / transfer 才重新累计。

**取舍**：宁可明确 `TEMPORARILY_UNAVAILABLE`，也不能展示不具备来源证据的数字。全进程读取吞吐仍是另一个指标，不能代替当前曲目读取码率，更不能代替蓝牙无线传输码率。

**代码 / 回归**：`AndroidPlaybackTelemetry.kt` 的 listener 与 `CurrentMediaTransferAttribution`；测试覆盖无切歌追加重复项、未变动队列、删除重复项、旧 transfer 继续回调、新 occurrence 恢复。

**面试讨论点**：缓存是带有效条件的结论。失效条件来自哪类事件？已经混杂的数据为什么不能靠恢复一个布尔值重新变成可信证据？

## R04：解码器停用后采样缓存持续增长

**背景 / 2026-09-07 review / P2**：播放失败等情况会停用解码器，但仍保留选中歌曲；链路页或诊断录制还在采样。

**根因（代码可确认）**：捕获逻辑重复返回 `lastDecoderCounters`，其观察时间不再前进。每次 capture 都把同一观察时间的样本入队，而淘汰也使用这个冻结时间，因此窗口不会正常前移。

**解决方案**：

1. 相同单调时间的观察合并为一个样本，乱序样本不加入速率窗口。
2. 使用当前 capture 的单调时间淘汰过期窗口；速率分母仍保留数据自身的观察时间，不能伪造新采样。
3. 每条缓冲增加 4096 条硬上限，作为异常时钟或过密调用的第二层防护。
4. 过期后不继续展示旧实时速率，恢复新观察需重新积累至少两个有效点。

**代码 / 回归**：`TelemetryRateTracker` 与 `TelemetryRateTrackerTest`。覆盖一万次冻结观察、窗口过期、新数据恢复、冻结 capture 下的容量上限；原有单调时钟、分母窗口、counter rollback 与 session/generation 边界测试保留。

**面试讨论点**：为什么需要区分 capture 时间、源观察时间与墙上时间？时间窗口为什么仍需容量上限？缺失读数为什么不应伪装成零？

## U01：功能已存在，但用户找不到收藏入口

**背景**：曲库可以展示我的喜欢，曲目详情支持收藏，但播放器详情没有接入收藏回调；曲库爱心与横向分类栏挤在一起，缺少文字说明。

**解决方案**：播放器顶栏提供 48 dp 爱心切换按钮，横竖屏均可用；播放器详情页也连接同一回调。状态来自最新曲库元数据，不独立维护另一份“假收藏”。首页在页头提供独立的“爱心 + 我的喜欢”文字导航，歌曲/专辑/艺术家仍是独立分类栏；收藏页使用明确的标题、返回操作和歌曲数量，而非继续呈现一排筛选器。

**取舍**：不为加一个按钮扩大主播放控制区的最小宽度，保留上一首/播放/下一首在小屏的原布局。无有效曲目时按钮禁用；TalkBack 可读添加/移除含义与选中状态。

**讨论点**：可发现性也是功能完整性的一部分，后端能力和一个隐蔽入口不等于用户完成得了任务。

### U01 的失败迭代：不能用两个大按钮解决导航层级

第一次把“全部曲库 / 我的喜欢”做成等宽筛选块，用户查看真机页面后否定。实际截图中，两个块与搜索框堆叠，下面又出现“歌曲”分类；“全部曲库”与分类语义重复，新增高度却没有建立“收藏集合”的层级。

第二次修订不是换颜色或圆角：删除整排选择块，将收藏提到页头导航；降低标题视觉重量；把较低频的目录与重扫操作收进菜单；分类只有当前项强调，其他项使用次级文字。大字体下页头动作换行，保留 48 dp 触控目标。收藏为空时解释如何在播放页添加，而非显示“搜索无结果”。

学习点：一个入口从“看不到”变成“很大”不一定变好。应同时解释入口属于什么对象、与旁边功能是什么关系，以及用户进入后如何回来。用户对上一版的否定和截图应保留，不能在复盘中只展示最终方案。

## U02：链路信息从卡片堆叠变成过于简陋的列表

**背景**：第一版高级仪表盘层级过多，修订为平铺证据行后又失去视觉层级；常用参数全部埋在高级页面。

**解决方案**：增加共用的核心参数面板，在概览与高级页都显示。上层并列区分源采样率/位深与 AudioTrack 采样率/编码，下层呈现系统选中设备、当前曲目读取码率、缓冲和欠载。小宽度/大字体切换为单列。完整参数保留在高级页，使用有分组、留白和层级的表面，而不是将所有信息平铺成日志。

**证据约束**：复用同一快照与 formatter，保留 confidence 和观察时间；未知原因直接可见，点读数查看来源、窗口和方法。源文件、AudioTrack、最终设备输出仍严格区分；外层显式订阅需要的指标，离开 Chain 后仍遵守原观察生命周期。

**讨论点**：渐进披露是“先回答主要问题，再提供证据”，不是隐藏所有参数；共享组件能避免概览与详情各自定义精度或不可用语义。

## 历史案例索引

以下为已有日志/架构审查记载的案例，详细日期、命令与证据范围应沿链接追溯，不能把历史设备结果当作当前 APK 已验收。

| 案例 | 背景与原因 | 解决方案与学习点 | 原始记录 |
| --- | --- | --- | --- |
| 真实封面始终显示占位 | MediaStore 曲目模型未完整传递 provider 封面身份；重连后 UI 也可能缺失 URI | 补 album ID/artwork URI，provider 缩略图回退，IO 线程和有界缓存；不读取已弃用裸文件路径 | 开发日志 2026-08-31 播放器焦点页与真实封面修复 |
| 撤权/重扫后旧图或旧列表回写 | 已启动的异步任务晚于失效事件完成 | cache/scan epoch，旧结果不能写入新代；撤权同步清除可见数据 | 同上 |
| Now 整页像相册一样左右滑走 | Pager 中每页都包含完整 dock，动画调速无法修复空间模型 | 删除全页 Pager，稳定播放控制壳，仅切换上部内容 | 开发日志 2026-08-31 Now 交互修订 |
| 随机与循环未合并成一个操作 | 把用户要求错误收窄为合并 repeat 模式 | 一个统一播放顺序状态映射；以 Media3 回传确认反馈，不预测成功 | 开发日志 2026-08-31 播放顺序修订 |
| 扫描故障被当成空曲库 | provider 异常、暂停或空游标不代表完整枚举 | 只有成功完成的扫描才 prune，保留旧数据和恢复状态 | 开发日志 2026-09-01 来源与扫描 |
| 重复歌曲恢复到错误队列位置 | 仅持久化 track ID，重复项被解析为第一次出现 | 保存队列索引，过滤缺失项后重映射，兼容旧恢复格式 | 架构审查 2026-09-05 |
| 缓冲时无法暂停 | `isPlaying=false` 被误当成没有待播放意图 | 区分 `playWhenReady` 与实际播放状态；统一 Pause/Play/Prepare/Replay 策略 | 同上 |
| MediaStore 重建后缓存错误命中 | 只比较 generation，没有数据库版本或存储卷身份 | 将 volume、database version、generation 和应用 metadata revision 共同纳入缓存校验 | 同上 |
| 过期缓存读取覆盖新 UI | 旧 IO 完成晚于撤权/扫描/新读取 | 可失效的读取 epoch；受控挂起测试复现返回顺序 | 同上 |
| 后台仍刷新 Now 进度 | Composition 存在不等于 Activity 可见 | `repeatOnLifecycle(STARTED)` 约束访问，前台立即刷新 | 同上 |
| 进度圆点纵向错位 | Material3 最小布局高度与 12 dp 可见 thumb 不匹配 | 48 dp thumb 容器中居中 12 dp 圆点，使用共同几何契约而非机型像素补丁 | 开发日志 2026-09-07 iQOO evidence |
| 原列表拖动无效 | local function reference 相等导致 `rememberUpdatedState` 保留捕获的 `editing=false` | 用 lambda 更新闭包，固定列表坐标处理手势；测试从浏览进入编辑而不是直接渲染编辑态 | 开发日志 2026-09-07 follow-up |
| 测试出现构造器 ABI 错误 | 新 test APK 安装失败后仍启动旧 runner，应用和测试 APK 不匹配 | 安装失败立即停止，匹配 APK 后再运行；无效结果不计作产品回归 | 同上 |
| 蓝牙协商码率无法取得 | 普通应用公开 API 对路由、能力、协商参数、无线实际吞吐的暴露不同 | 可枚举设备与不可公开指标分开；不反射隐藏 API，不用文件码率冒充传输码率，导出设备名脱敏 | 开发日志 2026-09-07 蓝牙补充 |

## 仍开放：iQOO 快速滑动掉帧

用户提供同机网易云更顺滑的对照，因此不能以 SM8450 性能不足解释。历史 warm 60 Hz 与新候选的滑动统计波动明显，关闭字母索引后仍能出现掉帧。已有短 Perfetto trace，但尚无完成的热点归因。

下一步应控制曲库、扫描状态、刷新率、温度与 warmup，用一致手势重复采样，结合主线程 frame、Compose measure/layout、位图上传和 GC 事件定位。R01 解决的是明确的滚动位置丢失及无关 ICU 重建，不足以替代这项性能验收。

## 后续新增案例模板

按“触发场景 -> 影响 -> 最小复现 -> 根因及证据级别 -> 修改的业务约束 -> 替代方案与取舍 -> 回归/设备证据 -> 残余风险 -> 面试讨论点”记录。没有复现、没有测量或尚未通过的门禁直接写明，不补写结果。

## P01：首页快速滑动——如何从慢帧统计走到调用栈

### 先确认测的是什么

用户反馈的是 **Library 曲库首页**。Chain 的 250 ms 采样滚动是另一个场景；离开 Chain 且未录制时遥测应无观察者，因此不能拿“优化高级链路刷新”当作首页修复。

先固定 APK、曲库、排序、索引开关、播放/暂停、是否扫描、窗口尺寸、字体、实际刷新率、温度和缓存状态。冷启动与预热分别记。两台手机都为 360 dp 宽，但 Honor Android 9 是 640 dp 高，iQOO Android 15 是 800 dp 高，ADB 注入速度也不同；跨设备数字用于复现范围，不能作为同一修复前后对照。

本轮脚本为 `tools/measure_library_scroll.py`。从新鲜 UI XML 取应用内最高的纵向可滚动区域，不拿分类横栏当曲目列表，也不从截图猜坐标。上下各留视口的六分之一，每 8 次换方向；每轮 48 次、每次 100 ms，先预热 4 次，每种条件重复 3 轮。应保证当前没有弹窗、扫描和录制，并记录起始位置；大量撞到列表边界只能算边界效果测试。

```powershell
adb -s DEVICE shell uiautomator dump /sdcard/vesqen-scroll.xml
adb -s DEVICE pull /sdcard/vesqen-scroll.xml build/qa/scroll.xml
python tools/measure_library_scroll.py --serial DEVICE --xml build/qa/scroll.xml --output build/qa/scroll-run
```

### “有多少卡顿”怎么算

每轮手势前执行 `adb shell dumpsys gfxinfo PACKAGE reset`，结束读 `dumpsys gfxinfo PACKAGE framestats`。保留原始文件和摘要，避免把启动、跳转或前一轮累计进去。

- `Janky frames / Total frames rendered × 100%` 是本次 Android HWUI 报告的慢帧比例。例如 27/570 = 4.74%。这是帧，不是“发生 27 次肉眼可见卡顿”，更不是音频断音次数。
- p95 表示约 95% 的样本不超过该时长；p99 看尾部。60 Hz 一帧约 16.67 ms，120 Hz 约 8.33 ms，但不能把任意 SDK 的 `gfxinfo` 百分位直接套一个固定阈值重新命名为系统 jank：新系统还使用预测 deadline、流水线及不同的统计字段。
- 同时看帧数、时长、重复间波动和实际呈现时间。一个低 jank 数字不保证跟手延迟低，也不能代表冷启动、大曲库或长时间运行。

### 时间线解决“慢在哪里”，CPU 栈解决“在执行什么”

iQOO 用 Perfetto 的 `linux.ftrace`（gfx/view/dalvik、调度、CPU 频率）和 `android.surfaceflinger.frametimeline`。在 Perfetto UI 选中应用的 Expected/Actual Frame，沿同一时间段看主线程、RenderThread、GPU/SurfaceFlinger：

1. 主线程长时间 Running：展开 `Choreographer#doFrame → animation/traversal → Compose`，找重组、测量/布局、文字排版或同步工作。
2. 主线程 Runnable 但未运行：看 CPU 竞争、频率和热状态；Blocked/Sleeping：看 Binder、锁、IO 或渲染队列等待。不能把所有墙钟时间都算成代码计算。
3. 主线程较快而 RenderThread/GPU 慢：查绘制、位图上传、阴影/过绘、buffer dequeue 和显示合成。
4. GC 与超时帧重合才构成 GC 方向的证据；只有总 GC 次数不足以归因。

FrameTimeline 需 Android 12+，Honor Android 9 不能伪造同等能力。该机以 gfxinfo 和 atrace 的 UI 切片补证据。本轮一次 32768 KiB atrace 缓冲申请报 OOM，产生的短文件无有效应用切片，作废；不把“有 trace 文件”算采集成功。iQOO 的 55 s 环形 trace 发生覆盖，且处理器报告丢弃 2 条负时间戳事件，因此统计只描述保留下来的窗口，不推算整场总量。

CPU 调用栈另用 Simpleperf（iQOO）：

```text
adb shell simpleperf record --app PACKAGE -e cpu-clock -f 500 -g --duration 45 -o /data/local/tmp/vesqen-home.data
adb shell simpleperf report -i /data/local/tmp/vesqen-home.data --children
```

`-g` 采集调用链；`Self` 是函数自身样本，`Children` 包含其子调用。例如 `TrackRow → Text → measure` 的多个 inclusive 百分比不能相加。找到热点后沿父调用回到业务入口，再对齐慢帧，而不是看到 `Looper`、解释器或 Compose 在栈顶就删除它。采样百分比不是一帧内耗时；带 profiler 的数据也不能直接与未带 profiler 的数据当作优化 A/B。没有改 root、内核符号权限或系统安全设置。

### 本轮实际发现与未解决部分

测量候选 Debug APK SHA-256 为 `10473730116FE268A3E103FE68014B3B94B39F06B0AF8CAB3EED7D60A1615218`；不是后续通知断言版。以下均为暂停的曲库首页，普通系统扬声器设备，没有 Chain 录制。

| 条件 | 三轮 HWUI jank | 三轮 p95 |
| --- | --- | --- |
| iQOO，重扫结束、恢复默认编译状态后 | 4.74% / 0.16% / 0.99% | 32 / 25 / 25 ms |
| iQOO，强制 `speed` 编译、重新启动 | 12.69% / 1.65% / 0.33% | 44 / 27 / 25 ms |
| Honor，安装后的原始编译状态 | 32.02% / 29.25% / 26.96% | 31 / 23 / 23 ms |
| Honor，强制 `speed` 编译、重新启动 | 38.40% / 48.20% / 45.34% | 77 / 46 / 36 ms |

编译实验没有证明稳定改善，缓存、运行顺序和机温仍可能影响结果，不能把它写成修复。iQOO 更早一轮 1.99%/1.20%/0.86% 正在扫描且 XML 视口改变，明确排除；脚本现已拒绝带扫描提示的 XML。

有效的 iQOO Perfetto 保留窗口里，7 个应用 deadline miss 对应的测量/布局切片约 **7.83–10.85 ms**；整个窗口单次 `AndroidOwner:measureAndLayout` 最大 16.81 ms，紧急列表预取最大 9.87 ms。文本测量、重组、预取都在主线程路径上。这把调查范围缩小到 **新行组成及测量/布局的帧预算**，还不能断言某一函数是唯一原因。

同一窗口共保留 1347 个应用 FrameTimeline 切片：1319 个仅 `Buffer Stuffing`、6 个仅 `App Deadline Missed`、1 个两者兼有、21 个 `None`。**不能报告成“98% 掉帧”**：Buffer Stuffing 表示帧排队，可能保持平滑但增加输入延迟，需要单独查显示节奏与排队。参考 [Perfetto FrameTimeline 官方解释](https://perfetto.dev/docs/data-sources/frametimeline)。

另一轮 45.135 s / 500 Hz CPU 采样共 14237 个样本、0 丢失。主线程解释器路径 inclusive 约 69.68%，`TextStringSimpleNode.measure` 6.93%，`TrackRow` 5.87%；这些有嵌套，不能相加。当前采样没有支持“主线程同步解码封面是主因”，也不能证明所有文件的图片路径都无问题。

**状态：OPEN，已有热点和呈现延迟证据，尚无经过单变量复测的修复。** 下一阶段先取得可代表发布行为的 Release/profileable 基线，再分别检验列表预取、新行文本测量与渲染队列；每次只改一个变量。最终需在两机相同业务流程下重复证明收益，同时保留正确封面、曲目点击、索引、拖动和无障碍。不要为得到漂亮数字关闭用户功能。

### Honor 的有效时间线补采（2026-09-08）

早先大 buffer 的 atrace 因设备内存不足而失败，不能分析。最后改成 `atrace -z -b 2048 -a <包名> -t 25 gfx view dalvik`，使用 2 MiB buffer，采集与 16 次手势由同一 Python 进程协调。取得 1,250,647 字节有效 trace，解析器没有报告 error/data_loss；通过设备 `pidof` 对齐应用主线程，而非按缺失的进程名猜测。

这轮使用最终生产 APK `B280D6095CC38D454BF56583DFBFC71B9F690D1898FF58B916D574D020927FA0`，不是上表旧候选。带 trace 的单轮 HWUI 为 257 帧、118 jank（45.91%）、p95 121 ms。主线程 `measureAndLayout` 单次最大 161.88 ms、文字测量 34.63 ms、urgent prefetch 80.23 ms，`doFrame` 最大 234.67 ms。它再次把范围指向列表布局/预取路径，但只有一次且带采集开销，不能拿它与前面三轮直接比较为回归或优化收益。

这里是切片的墙钟时间，包含线程被抢占或等待；本轮为降低旧机负担没有采集 sched，所以不能把 161.88 ms 全算成 CPU 执行。下一步需要发布构建基线及短时调度证据区分布局计算与等待，再决定改哪段代码。

### 面试或复盘时怎样讲

“先确认用户反馈场景，用固定手势取得慢帧分布；再将超时帧对齐线程时间线，用 CPU 栈找到业务热点。发现一个被扫描污染的实验并剔除，也发现低 HWUI jank 仍可能有 buffer 排队延迟。最终明确定位范围和证据限制，未把热点当作完成的根因修复。”

## R05：Honor 把 24-bit FLAC 显示为 16-bit

同一份经过文件头与 SHA-256 核验的 FLAC，iQOO 显示 24-bit，Honor 的真实音源验收却断言 `expected 24 but was 16`。原实现把 `MediaExtractor` 的 `pcm-encoding` 当源位深，旧 ROM 给出的其实可能是解码器 PCM 格式。

新增有界 FLAC STREAMINFO / RIFF WAVE 头读取，固定小块分配、最多遍历 1 MiB；WAVE 支持普通 PCM/float 和 extensible 的有效位数，异常/截断/未知返回不可用。压缩源不再用 PCM encoding 补源位深；缓存 metadata revision 从 2 到 3，正常重扫更新旧事实，保留曲目 ID 和用户元数据。源格式与 AudioTrack 请求仍独立。

6 项 JVM 回归覆盖 16/24/32-bit、奇数 chunk padding、extensible 有效位数、截断/恶意大小、短读取及不能 skip 的流。Honor 原真机断言复测通过三份真实音源。它证明这些文件的源事实已修正，不证明所有编码格式或最终 DAC 位深。

## U03：系统媒体通知有文字却没有封面

设备通知中 `android.title` 和 `android.text` 已有曲名/艺术家，`android.largeIcon` 与点击入口为空。界面使用专辑 provider 缩略图及有界内嵌封面回退，Media3 默认 bitmap loader 却把专辑元数据 URI 当图片流，两条读取路径不一致。

服务的 `SessionArtworkLoader` 复用既有 `AlbumArtworkLoader`，在后台读取 256 px 缩略图；同时设置不可变的 session Activity PendingIntent，系统通知可返回应用。源 URI 仅留在媒体会话的展示 metadata，诊断仍通过原白名单导出。切歌异步封面由 Media3 当前 metadata 回调管理，服务销毁关闭 loader executor。

Android 13+ 系统媒体 UI 主要读取 MediaSession，OEM 决定折叠模板和右侧展开箭头，不能用一个应用开关保证移除；详见 [Media3 后台播放官方文档](https://developer.android.com/media/media3/session/background-playback)。应用能保证提供曲名、封面及控制动作，不能承诺替系统取消展开交互。

## T02：跨 Android 版本的验收断言也需要校准

Honor 的两个失败没有通过改产品数据或跳过测试解决。第一，MediaRouter 的“已选择系统路由类型”和 API 33+ AudioManager 的“预期路由”是不同指标：前者在该旧 ROM 返回未知类型，正确原因是 `SOURCE_DID_NOT_REPORT`，后者才是 `UNSUPPORTED_ANDROID_VERSION`。测试分别验证公开事实和不可用原因，不能把连接了扬声器推断成已选路由。

第二，实际横屏/回竖屏已成功，超时发生在返回后检查导航栏。该 Honor 普通页面进入前的 `navigationVisible` 就是 false，来自设备导航模式。回归改为先保存真实初始状态，再验证返回恢复相同状态、状态栏可见且应用 FULLSCREEN/HIDE_NAVIGATION 标志已清除；不强行要求系统显示不存在的导航栏。Activity 重建时使用当前 RESUMED 生命周期实例，避免读取已销毁的 ActivityScenario 实例。随后完整 100 次 Chain 进出、录制前后台和横竖屏回归通过。

iQOO 无前台宿主的 runner 启动/服务连接曾超时；用例开始时显式启动同一应用宿主后通过。界面首例也曾因启动辅助争用 ActivityScenario 失败，预先建立宿主后独立执行通过。保留原失败记录，分别报告各轮结果，不能将启动失败批次重写成全通过。
