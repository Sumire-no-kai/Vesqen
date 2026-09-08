# M2 Audio Proof 验收门禁

本文件只定义 M2 的最终设备与性能证据。功能代码、JVM 测试、APK 构建、Android 测试源码编译或一次手动冒烟均不能单独完成 M2。M3 的 mixer attribute 设置与 bit-perfect 输出不属于本门禁。

**最新结论（2026-09-08）：Android 9 Honor / Android 15 iQOO 的本轮扬声器、真实无损音源与功能回归已完成；M2 整体仍为部分验收。** 完整外设、系统/格式/无障碍矩阵、Library 快滑和高级页 250 ms 性能门禁仍开放；可开始 M3 软件开发，不能宣布 USB 输出已验收。下文历史失败与旧 APK 结果保留，最新证据在文末。

同日软件收尾已补齐 iQOO 的转场/标识符回归和高级页 Profile 短时对照，详见文末。长时间稳定性测试按用户要求暂缓；本轮短时结果不代替长时门禁。

## 验收前提

- 使用准备合并的同一 commit 与 APK，记录版本名、versionCode、APK SHA-256、手机型号、Android／ROM build 和测试时间。
- 至少覆盖 Android 8/9、Android 10–12、Android 13、Android 14 与当前稳定 Android 版本；系统输出和 USB 行为必须由具备相应硬件的真机覆盖。
- 准备 CBR／VBR、有损／无损、16/24/32-bit、44.1/48/96 kHz、双声道，以及损坏或缺失元数据的本地夹具，并记录哈希。
- 测试安装必须保留用户曲库、播放列表、历史和设置；诊断文件保存到独立 QA 目录。

## 数据真实性与可追溯性

- Source、decoder input、AudioTrack request、system route 与 observable output 使用不同字段和来源，任何源参数都不会显示成最终硬件输出参数。
- 每个指标显示稳定 ID 对应的本地化名称、`MEASURED`／`DERIVED`／`ESTIMATED`／`UNAVAILABLE`、来源与更新时间。
- 每个推导值保留输入指标、单调时间窗口、原始操作数与计算方法；每个估算值保留方法和输入指标。
- 窗口读取码率与文件平均码率明确区分；切歌、seek、预取和缓存读取时不把数据源吞吐误称为当前曲目编码码率。
- 厂商未公开的 decoder 输出、播放线程 CPU、逐核心状态、系统 DSP、最终 mixer 后格式等保持 `UNAVAILABLE`，不根据机型营销信息推测。
- 播放会话切换后不保留上一会话的 decoder、AudioTrack、underrun 或错误事实；gapless 预加载 decoder 的初始化与释放不会污染当前会话。

## 输出与 USB

- 扬声器、3.5 mm、蓝牙和系统 USB 路由分别验证 selected route、anticipated route 与 connected endpoints 的含义一致，路由变化事件带正确会话归属。
- Android 10–12 的 `isDirectPlaybackSupported` 与 Android 13+ 的 `getDirectPlaybackSupport` 只显示当前 AudioTrack 请求格式的能力；不得升级成 active 或 verified 声明。
- Android 14+ 只读查询 supported／preferred mixer attributes；M2 不设置或清除 attribute，状态始终不高于 `SYSTEM MIXED`／有依据的 `DIRECT SUPPORTED`。
- USB Host 设备与 Android audio endpoint 分开呈现；VID/PID、权限、audio interface 以及公开采样率／声道／编码数组含义正确。空能力数组显示“任意值”，不显示成“不支持”。
- 非音频 USB 插拔不生成音频事件；音频设备插拔、路由切换和页面关闭后不存在迟到回调、重复注册或失控采样。

## 2026-09-07 蓝牙补充验收（未执行）

- 对可用的 A2DP 耳机／音箱及系统支持的 LE Audio、SCO 场景记录精确设备／ROM／应用组合；缺少硬件的场景标记未验证，不能以另一 profile 代验。
- 区分连接、已选择路由与可证实的流级端点；覆盖“蓝牙已连接但仍从扬声器播放”和多个已连接端点，不把连接列表第一项误认为播放设备。
- 若公开 API 提供当前 codec／协商参数，记录 API、观察时间和路由归属；若不提供，显示准确的 UNAVAILABLE 原因。codec 能力列表、标称配置值、源文件码率、PCM 吞吐和实际蓝牙窗口码率不能混用，也不得以 aptX／LDAC 名称升级 bit-perfect 声明。
- Android 12+ 覆盖 BLUETOOTH_CONNECT 同意、拒绝与撤销；旧系统按公开接口验证最小权限行为。不授权仍可正常播放及显示允许读取的路由信息，不要求扫描、定位、root、ADB 或隐藏 API。
- 断连、关闭蓝牙、切换扬声器／USB、切歌及权限撤销后旧设备和旧 codec／码率证据及时失效。无观察者和无录制时没有新增蓝牙采样／回调泄漏；复用既有 M2 高频、长时和进出 Chain 压力检查。
- 用测试设备标识验证导出的 JSON 不含蓝牙名称、别名、MAC 地址、可识别设备 ID 或错误文本中的同类信息；允许保留匿名端点、设备类型和非识别性证据。分别验证成功和失败／撤权路径。
- 正确显示系统不提供的 codec／实时码率为不可用可以通过真实性验收；这不等于已实现该指标读取，更不能宣传所有手机都能显示实时蓝牙码率。

## Chain 与自由布局

- 默认 Chain 只显示声明、输出、问题与精简链路；完整工程指标只在用户主动打开高级视图后出现。
- 指标选择、隐藏、固定、同组排序、分组排序、紧凑／详细／图表、AUTO／SI／RAW 单位、窗口、历史长度、刷新率与低功耗模式均可操作。
- 退出并重启进程后布局与刷新设置一致；“恢复默认”逐项恢复规范默认值。
- 250 ms、500 ms、1 s、2 s、5 s 刷新选项实际改变采样需求；低功耗模式把有效间隔限制为至少 2 s，窗口不会短于有效采样间隔。
- 图表在换会话、倒退时间戳和采样缺口处正确清理或断线；TalkBack 能读出当前值、最小值、最大值、时间跨度、可信度与缺口数。
- 在 320×480 dp、传统 16:9、现代长屏、横屏、平板／宽窗口和折叠窗口检查摘要、网格、菜单与自定义面板无裁切、重叠、横向溢出或不可达操作。
- 深浅主题、系统字体 1.0×/1.3×/1.5×/2.0×与 TalkBack 均完成检查；所有主要操作触控目标不小于 48 dp。

## 诊断录制与隐私

- 只有用户明确点按后才开始录制；离开 Chain 后录制可继续，用户停止、播放会话持续消失、来源结束、来源失败和 owner 取消均会封存并释放 observer。
- 快照与事件达到上限时只丢弃最旧项并记录丢弃计数；连续导出、取消、目标打开失败、写入失败和清除竞态不会破坏保留的录制。
- JSON 输出稳定、可解析并保留版本、可信度、来源、单调时间、窗口、操作数、方法、事件序号和终止原因。
- 导出报告不含媒体标题、艺术家、专辑、文件名、路径、URI、路由名称、USB 名称、VID/PID、序列号、异常文本或 source detail；USB 设备只保留匿名序号、权限与公开能力。

## 采样生命周期与性能

- Chain 不可见且没有主动诊断录制时，active observation 为 0，sampler、route callback 与 USB receiver 均停止。
- 分别在采样关闭、默认 1 s 和高级 250 ms 下进行不少于 15 分钟的同一夹具对照，记录进程 CPU、Java/native/PSS、GC、温度、电流或功率估算、帧时间与 underrun 增量。
- 250 ms 模式不会造成可归因的 underrun、音频中断或明显 UI 掉帧；如果设备不提供某项公开数据，结果记录 `UNAVAILABLE` 而不是以其他设备代验。
- 连续诊断录制 30 分钟、前后台切换、横竖屏切换和至少 100 次进入／退出 Chain 后，无采样泄漏、内存无界增长、崩溃或 ANR。

## 自动化与结论记录

1. 构建主 APK 与测试 APK，并对明确设备使用 `adb install -r -t` 覆盖安装，禁止卸载或清空用户数据。
2. 使用 `adb shell am instrument -w -r` 直接执行 telemetry、Chain、诊断和持久化测试类；保存目标序列号对应的非空 runner 输出。
3. 保存布局截图、UI hierarchy、性能原始数据、logcat 摘要和诊断 JSON 的隐私扫描结果。
4. 每项结果写明步骤、通过／失败、设备与夹具；只有全部门禁通过后才能把 M2 标记为完成。

`BIT-PERFECT AVAILABLE`、`ACTIVE` 或 `VERIFIED` 必须由 M3/M4 的精确设备、ROM、DAC、格式与外部验证证据支持；M2 完成也不能自动获得这些声明。

## 2026-09-07 Bluetooth implementation and speaker-only spot check

The public AudioManager output probe now supplies connected Bluetooth endpoint names/types separately from selected-system and anticipated routes. Codec capabilities, negotiated parameters, configured bitrate and actual transport bitrate are distinct fields with unavailable reasons, not inferred from source/PCM rates or device marketing. A2DP, SCO, LE and hearing-aid route classification is covered by local regressions; names/MAC-like test labels are excluded from diagnostic JSON. No Bluetooth scanning/connection permission was introduced for these existing public audio-route queries.

On iQOO, the speaker-only advanced Chain UI showed no connected Bluetooth endpoints and transport bitrate not applicable. Actual Bluetooth connection/disconnection/route switching, permission behavior across ROMs, and Honor checks are NOT accepted by that observation. Advanced UI has been revised to compact metric rows; source, confidence, timestamps, reasons and chart evidence remain required. New instrumentation was compiled but its installation was rejected; the old runner's ABI errors are not valid candidate results.

Advanced Chain final visual follow-up: viewed the redesigned compact toolbar and flat metric rows on the connected iQOO (`final-dashboard.png` in the ignored QA directory). Generic method details expand per row; unavailable reasons remain visible. This is a portrait visual spot check, not a full adaptive-layout or long-running telemetry acceptance pass.

## 2026-09-07 review 修复增量（非完整关闭）

- Timeline 更新会使重复 URI 归属失效；冻结 decoder 观察去重、按 capture 单调时间过期并有容量上限。定向纯逻辑回归通过，未声称完成长时硬件验证。
- iQOO 实际查看 Chain 概览/高级页共用核心参数与详细证据页面，未把 source、AudioTrack 或选中系统路由当作最终设备输出；当前未报告的 AudioTrack 字段明确显示不可用及原因。
- 截图见私有 build/qa/review-fixes-20260907/chain-summary-new.png、chain-advanced-detail.png。真实 Bluetooth/USB 切换、耐久性与完整设备矩阵继续开放。

## 2026-09-07 · 已授权的扬声器验收

本轮按用户要求执行可用扬声器与本地自动化，Bluetooth、USB 和 3.5 mm 外设暂缓。M2 仍为 **部分验收，未关闭**；下列证据不替代缺失的旧 Android、其他厂商真机、完整音频格式及外设矩阵。

### 环境与产物

- 真机：iQOO V2171A，Android 15 / API 35，ROM `AP3A.240905.015.A2/compiler260112143406`，360×800 dp。模拟器：Google x86_64 Android 16 / API 36.1，`BE4B.251210.005`，360×640 dp；不代表第二台物理音频设备。
- 版本 `0.4.0-alpha.1` / code 8；`feature/m2-audio-proof` 的 `21629a9` 加保留的未提交工作区。不是已合并或发布版本。
- 最终 UI 候选 APK SHA-256：`93BA16CF30D7E41AB633BA19B89175B7CCCFB89DCFB20CD0C030AE1128CFBF70`；test APK：`514367B9E0CE87765376B0E961856250EF720C71EAD6081CB3DAB318C80E2BC9`。
- 最终 UI 候选在模拟器完成 46/46 UI 测试，无跳过。真机更新被 vivo 指纹身份验证暂停，取消等待后继续验证已安装的录制候选：app `778BAF549A45DBCC123525839BE5180012F2E8508F0B89F1AC332DAD2F1AA175`、test `7410252917ED3512048BAE387191B10A1EEB588E49EDDF08CD751D546DF4A0C3`；已通过 `pm path` 和设备 SHA-256 复核。该版本包含拖动视口与系统栏修复，不含最后的大字体工具栏分行修复。身份验证没有被绕过。
- 私有原始证据：忽略目录 `build/qa/speaker-acceptance-20260907/`。包含匹配 APK 哈希、runner 原文和结果 JSON、设备配置、UI XML/截图、诊断 JSON、CPU/内存/温度 JSONL。未上传；手机用户数据库与设置在测试前已备份，未卸载或清空主应用。
- 真机播放夹具由测试生成：60 秒低音量双声道 PCM WAV，44.1 kHz/16-bit、48 kHz/24-bit；仅位于应用私有 cache，使用负测试 ID，不把测试媒体加入用户曲库。没有录制麦克风或环境声音。

### 已取得的结果

- 设备存储/空闲 adapter：真机与模拟器分别 9/9（LibraryCatalog、PlaybackStateStore、AndroidPlaybackTelemetry）。
- 扬声器短流程：真机通过切歌、上一首、seek、暂停/恢复、实际 speaker 路由、五档刷新、250 ms 请求在低功耗下限制到 2 s、100 次订阅/取消、短诊断导出与停止后的 0 observer。模拟器同类流程及流式导出校验通过。
- 模拟器不同进程的设置写入/读取与恢复默认：3 项通过。测试以正常 Activity stop 等待 SharedPreferences.apply 的持久化边界，不把立即强杀当作正常退出。
- 模拟器实际 100 次 Chain 进出：每次退出 observer=0；诊断录制期间前后台切换和播放器横竖屏切换通过；停止后 observer=0。动态接收器数量录制前/中/后为 4/5/4，遥测 USB receiver 为 0/1/0（只是生命周期检查，没有 USB 硬件插拔）。
- 系统文件选择器：模拟器真实点按开始/停止，17 快照、3 事件；取消导出后保留，再次导出成功，475,892 字节 JSON 可解析且无测试标题、路径或 URI。目标打开失败/写入失败/清除竞态目前以已有诊断单元测试覆盖，未冒充真实文件提供程序故障注入。
- 适配：自动化覆盖 320×480、大字体、600 dp 网格、短横屏、图表语义及主要操作目标；另实际查看 360×640 竖屏、640×360 播放器横屏、字体 1×/2×、深浅主题。2× 工具栏碎字问题已修复并通过定向设备回归。TalkBack 服务启用/焦点截图只算冒烟，不能替代完整语音遍历验收；1.3×/1.5×和折叠窗口的完整手动矩阵仍开放。

### 15 分钟采样对照

三阶段使用同一旧候选 APK `B111ABC85273CCC2B1E2F208C9FE978B6FE409D2ECF0586845D85E8F769F7A53`，连续播放同一 48 kHz/24-bit 夹具；预热 30 秒，界面停在 Library。CPU 是进程耗时/墙钟时间，即单核等效百分比；不是整机百分比。

| 模式 | 实际时长 | CPU | PSS 范围 | GC 增量 | 遥测快照 | 观察到的 underrun/error 事件 |
| --- | --- | --- | --- | --- | --- | --- |
| 关闭 | 904.274 s | 10.78% | 151.96–168.81 MiB | 0 | 0 | 未采样，不记成 0 |
| 1 s | 903.197 s | 12.05% | 160.14–201.49 MiB | 2 | 876 | 0 |
| 250 ms | 903.570 s | 15.49% | 174.35–211.94 MiB | 6 | 3418 | 0 |

这是一次 Debug、充电中、顺序执行的有限对照：说明该条件下 250 ms 增加 CPU/分配成本，不能据此推算续航或证明所有设备无音频中断。电流读数为 0，未得到有效放电功耗；温度 36.9→35.5°C。Library 静止期没有足够 UI 帧，启动累计 jank 不用于判断高级页的滚动流畅度。最终 UI 候选与此产物不同，不能称为同一最终 APK 的完整性能关闭。

最初的组合 runner 在三项 15 分钟阶段完成后，于录制阶段被主动停止以修正测试校验器的整份 JSON 内存复制；该 runner 的失败记录保留，不计整套通过。产品导出器本身已使用流式输出。30 分钟录制使用独立 runner 和流式 JSON 校验另行执行，结果必须单列。

### 独立 30 分钟诊断记录：通过

上述真机录制候选的 `diagnosticRecordingSoak` 报告 `OK (1 test)`，runner 总计 1,833.157 s。实际测量阶段 1,802.915 s；1733 快照、60 事件，事件为 30 次格式变化和 30 次循环曲目变化，无保留的 underrun/error 事件。停止原因为 `user_stopped`，未超过 1800 快照/4096 事件上限，无丢弃或事件序号缺口。导出 48,157,200 字节 JSON，通过真机流式解析、测试标题/路径检查及主机再次解析；清除并释放后 observer=0，活动采样间隔为空。

该阶段 CPU 为单核等效 12.52%，PSS 262.61–361.05 MiB（首/末 270.00/329.74），Java heap 首/末 39.39/59.88 MiB，native heap 首/末 18.57/28.49 MiB，GC 增量 6，电池温度 35.1→34.6°C。诊断缓存本来就会保留快照，不能要求内存始终等于空闲值；这次有限记录和容量断言支持“本次完成且未超配置上限”，不能证明无限时长或所有设备均无泄漏。不同候选/冷启动的 PSS 不与前述连续三阶段直接作因果比较。

补充实际窗口冒烟：1.3× 浅色 Chain、1.5× 深色 Chain、600×800 dp 摘要与双列高级页已保存并查看截图。窄列英文 `UNAVAILABLE` 仍会换行，完整的字体/菜单/折叠窗口与 TalkBack 语音体验不标为关闭。

### 真机收尾回归与性能待办

- 已安装录制候选 UI 套件 `OK (46 tests)`，0 失败/跳过，104.447 s；不同进程的设置写入 1 项、读取与重置 2 项分别通过。
- 真机生命周期首次在 100 次进出已完成后，因后台自行拉起未恢复测试界面而失败。失败原文保留。复核使用明确的一次外部返回原任务操作：观察到桌面至少 2.78 s 后执行 `am start -W --activity-reorder-to-front --activity-single-top`，旧匹配测试 APK 随后完成全部录制/旋转/系统栏/导出/停止断言，`OK (1 test)`，69.497 s。这是有前台启动辅助的通过，不是无辅助 runner 通过。
- 生命周期测试源码已改为上述返回已有 Activity 的方式；不得创建替代原 Activity 的新 launcher task。修改后的测试在模拟器通过（54.466 s，test APK `025CA4CF8EDD819D92C68CD2668CA10A6BFA35352AB9EB9DF8DA04BA4A7E2A39`），但新测试 APK 尚未安装到真机。之后仅将采样对照与录制拆成两个独立测试入口，避免重复执行 30 分钟录制；最终源码编译结果单独保存。
- 真机最终 100 次退出均 observer=0。录制前/中/后的 package receiver 为 4/5/4，遥测 USB receiver 为 0/1/0。另独立检查高级页 UI：前台/后台/返回的遥测 receiver 为 1/0/1，无录制时随可见性释放。
- 同一已安装 Debug APK、默认选中指标、标准功耗模式，合成 WAV 循环播放，预热滚动后每档三次约 10 s 往返滚动；以 `gfxinfo reset` 分轮采集，而非使用启动累计数据。1 s：561/568/598 帧，jank 5.17%/5.46%/4.68%，p95 32/34/29 ms；250 ms：603/538/532 帧，jank 3.98%/13.01%/10.71%，p95 30/38/34 ms。**高频 UI 性能门禁保持 OPEN**：此次有实际帧证据，但波动较大，需要代表发布行为的同一最终产物、进一步 trace 归因与验证，不能据此宣布修复或声学无中断。
- 收尾已逐字节恢复原播放/浏览偏好；109 首曲目的用户字段、来源授权、收藏/历史/歌单数据核对一致（仅排除正常扫描的 generation/时间/seen_epoch）。已清理本轮私有 WAV、设备侧 QA 导出和新增测试偏好，屏幕超时还原 600000 ms；恢复正常曲库与原暂停状态，模拟器已关闭。报告和原始文件仅保留在本机私有 QA 目录。

后续受阻项应明确拆分：最终 app/test APK 安装需要手机身份验证；Bluetooth/USB/3.5 mm 按用户要求暂缓；其他物理设备及旧系统镜像当前未具备；性能归因、完整格式和无障碍/适配矩阵尚未关闭。不能将这些项合并成一句“只差签字验收”。

## 2026-09-07 · 曲库主页定位与刷新选项修复

用户明确指出滚动目标为 **曲库主页**。上文高级 Chain 的 1 s/250 ms 数据不用于解释主页卡顿。以下原始数据位于本机私有 `build/qa/m3-readiness-20260907/`。

- 本轮先成功安装 app `93BA16CF30D7E41AB633BA19B89175B7CCCFB89DCFB20CD0C030AE1128CFBF70` 及当前测试 APK，扬声器切歌/seek/暂停/观察者释放测试有效通过 1/1（runner 483.078 s，含人工滚动窗口；不是 8 分钟耐久测试）。
- 曲库主页主列表的 XML bounds 为 `[0,594][1080,1926]`；上方横向分类栏不作为纵向滚动目标。109 首实际曲库，私有合成 WAV 循环播放；350 ms 往返滑动预热后，三轮约 10 s 分别为 624/591/619 帧，jank 0.48%/1.02%/0.81%，p95 12/15/15 ms。
- 该往返流程同时采集 45 s Simpleperf `cpu-clock` 500 Hz，12840 samples，1 条错误 callchain；采样有额外开销。应用主线程解释执行栈占 inclusive samples 约 57.57%，测量/布局约 8.38%，TrackRow 约 2.97%。这些是重叠的 CPU 调用栈比例，不能相加，也不是单个卡顿帧的根因证明。封面 load 出现在后台线程；本次证据不足以将卡顿归因于主线程图片解码。
- 随后测试结束、应用冷启动、暂停状态下，改为 100 ms 快速滑动，每 8 次换向；三轮 568/601/612 帧，jank 5.81%/1.00%/0.82%，p95 36/25/24 ms。第一轮较差提示首次加载/布局/JIT 等候选方向，但播放状态、手势与采样条件不同，不能作为前述流程的优化对照。**主页性能问题仍开放；未声称已找到唯一根因或已修复。**
- 刷新选项布局的直接原因是窄屏上两个加权选择器与两个 48 dp 图标争抢同一行。现改为小于 480 dp 的工具栏让两个选择器平分一行，图标自然换到下一行；极窄屏与大字体保留逐项全宽。此改动不改变采样频率，也不冒充主页性能修复。
- 布局候选 app SHA-256 `2A858E47FF036419D5D2D008AD80FD7299B3AF64E197622843A3CEC27E7A232E`，test `C9B4E86939E5D0F804DA43C06C3E068D4646CCE0327F620DB7F911B532EAA944`。unit、lint、Debug 与 instrumentation 构建通过；lint 为 0 errors / 21 warnings。真机安装与实际布局检查结果另行记录，不能由构建通过推断。
- 两个布局候选 APK 随后均安装成功，设备侧 SHA-256 与上述文件一致，之前的指纹安装阻塞已解除。真机中文 360 dp / 1× 字体逐项选择 250 ms、500 ms、1 s、2 s、5 s，保存并查看五张截图，选中值均完整单行显示。扬声器短测试另获 1/1 通过（118.0 s，包含布局检查窗口）。
- 此布局候选的 180/180 单元测试通过；Android 16 模拟器 `VesqenAppTest` 为 44/44、0 失败/跳过，117.765 s，包含既有 320 dp 大字体及 600 dp 布局回归。这里的 44 仅指本轮执行的该测试类，不能与先前多类合计 46 项混写。
- 当前 app/test 配对的 iQOO 生命周期 runner `OK (1 test)`，67.412 s（主机 68.109 s），100 次退出后观察者为 0；录制期间后台返回、旋转、系统栏、隐私导出和停止后释放全部断言通过。runner 使用显式前台宿主启动，后台返回由测试中已修订的返回原任务命令完成，本轮没有另加人工返回操作。
- 收尾恢复本轮开始前的播放/浏览偏好并逐字节核对，清理私有测试音频与新增测试偏好，核对系统设置，关闭本轮模拟器。数据库仍有 109 首、1 个来源，歌单表未变；比较发现扫描时间/epoch 及曲目播放次数/最近播放时间有变化，未将不能确认为测试污染的历史事件回滚，不宣称整个数据库逐字节未变。

M3 软件开发的入口与依赖清单见 [M3_IMPLEMENTATION_PLAN.md](M3_IMPLEMENTATION_PLAN.md)；这不关闭 M2 的开放验收项。

## 2026-09-07 · 用户无损音源与高级链路增量验收

### 音源与传输

用户提供的三份原始文件已复制到 iQOO 的 `Music/Vesqen Test Audio/`，没有转码、重命名或覆盖不同内容的同名文件。主机与手机 SHA-256 全部一致；完整清单及文件头解析结果仅保存于本机私有 `build/qa/real-lossless-20260907/audio-manifest.json`。MediaStore 扫描后使用真实 catalog track 和 content URI 播放。

| 夹具 | 文件头格式 | 采样率 | 位深 | 声道 | 时长 |
| --- | --- | --- | --- | --- | --- |
| A | FLAC | 48 kHz | 24-bit | 2 | 339.160 s |
| B | WAV / PCM | 48 kHz | 24-bit | 2 | 277.007 s |
| C | FLAC | 96 kHz | 24-bit | 2 | 289.515 s |

这些夹具补充了真实 24-bit、48/96 kHz 无损容器覆盖，不代表完整有损、VBR/CBR、32-bit、异常元数据或跨设备矩阵。

### 验收中发现的会话归属缺陷

旧候选确实收到 decoder/input-format/AudioTrack 初始化回调，但部分回调的 session 为空，当前指标一直显示 `SOURCE_DID_NOT_REPORT`。原因是媒体项切换可能早于当前 media period 的确定，adapter 只在切换时记录 active period，随后没有补绑定；原先从事件自身 period 回退还可能误接预加载 period。

修复按 [Media3 EventTime 的 currentMediaPeriodId 契约](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener.EventTime)，在 timeline/renderer 回调首次提供明确当前 period 时绑定，并提升该 period 已缓存的事实。下一首预加载及旧 period 的迟到事件保持隔离。

`TelemetryPeriodDeviceTest` 三项在修复前实际 0/3、修复后 3/3：延迟绑定及匹配事实提升、无需再次媒体项切换的 renderer 回调、禁止拿预加载 period 冒充当前 period。首次真实音源测试只要求源参数和播放操作，虽然 runner 通过，但它没有验证 decoder/AudioTrack 字段，不能用来接受此缺陷；之后增加了真实 decoder 输入采样率和 AudioTrack 请求必须出现的断言。

会话修复候选 app `AAEC35D9122708B8ECEABF9D01E8C04650212282B73EDB1EC956C0AA0F67C08B`、test `2DB711D7632F37C7F0C74DE2AD957EBA2A6435A8D9809371314559A9780BFAEC` 的增强真实音源测试通过 1/1，主机 408.594 s，含手动性能窗口。每首约 33 s 检查取得 126–128 个 250 ms 观察快照、32 个诊断快照，无保留的 underrun/error 事件；seek 到 60 s、暂停/恢复、会话变更、导出解析/隐私和 observer 释放通过。两个 FLAC 的 decoder 为 `c2.android.flac.decoder`，输入分别为 48/96 kHz；三个 AudioTrack 请求分别为 48/48/96 kHz，编码均为 `pcm-16`，声明保持 `SYSTEM MIXED`。WAV 没有独立 codec 名称回调，不编造 decoder 名称。上述请求格式不是最终硬件信号或 bit-perfect 证明。

### 高频性能与进一步修复

同一 96 kHz FLAC、默认 16 指标、详细视图、标准功耗、真机充电中；XML 定位滚动区域，预热后每轮 24 次 350 ms 往返滑动，三轮，逐轮重置 gfxinfo。会话修复候选：250 ms 的 jank 为 6.29%/5.16%/3.96%，p95 34/31/31 ms；1 s 为 1.72%/1.25%/1.73%，p95 15/15/23 ms。不能将不同采样频率或不同产物混作同一性能结果。

另一次 45 s Simpleperf CPU-clock / 500 Hz 捕获 16612 samples、0 lost：主线程 `buildSnapshot` inclusive 1.06%，数字格式化 2.29%，详细视图仍在计算未显示的图表摘要。比例重叠，不能相加或认定为所有卡顿的根因。已将观察者的快照/速率计算移到 Default dispatcher，并仅在图表视图计算图表摘要；没有引入格式化缓存或修改指标精度。

最终候选 app `49D45ED93F7A6B2E5EA96C2626A71EBE64988E6F35FE3457847BA430AA231EAC`、test `565870F597FEB36DAD2FF87202996554F7B16418749ADA152D3EBC5CA81AA4FA` 已构建并安装。180/180 单元测试、lint（0 errors / 21 warnings）、Debug/Release、测试 APK 构建通过；最终候选 period 回归 3/3。最终真实音源/性能执行结果另记，不由构建通过推断。

外部音源测试必须显式传入 `realLosslessAcceptance=true`，在 app 私有 `files/real-lossless-acceptance/audio-manifest.json` 放置文件头独立验证的数组清单（每项至少含 `file`、`bytes`、`sampleRate`、`bits`），并先将匹配音乐导入 MediaStore。正常套件不依赖用户私有音源；`tools/run_device_tests.py` 对显式验收要求非空且无跳过的结果。可选 `manualSeconds` 仅用于人工 UI/性能窗口，不计为耐久测试时长。

### 最终候选执行结果

- 手机侧 app/test SHA-256 与上述最终候选一致。显式外部音源 runner `OK (1 test)`，主机 425.234 s，含手动 UI/性能窗口。A/B/C 分别获得 129/126/128 个高频快照、33/32/32 个诊断快照；源参数、实际 decoder 输入、AudioTrack 请求、seek/暂停恢复、JSON 与隐私检查、最后 observer=0 均通过。未记录到 underrun/error 事件；这约 33 s/首的结果不是 30 分钟耐久验证，也不是外部声学中断检测。
- 图表原始单位/描述与分段 confidence/source/window 两项真机 UI 回归 2/2，7.984 s。实际查看图表模式：陈旧静态 source 事实没有被伪装成持续的新测量点；完整 TalkBack 语音与所有指标的图表视觉矩阵仍未关闭。
- 最终详细页 250 ms：620/622/628 帧，jank 7.58%/4.98%/3.82%，p95 34/32/31 ms；1 s：646/619/647 帧，jank 1.55%/4.36%/1.24%，p95 15/20/23 ms。**此次没有取得稳定的帧时间改善证据，高频性能门禁继续 OPEN。** 已消除确认的计算位置/隐藏摘要开销，不能据此宣称所有卡顿已修复；后续需要更精确的 frame timeline 归因与代表发布行为的性能验证。
- 本轮没有形成曲库主页快速滑动的新根因结论；主页问题属于独立的 M1 体验补充项，不用高级链路的修复或数字代验。
- 最终候选扬声器短测试与 Chain 生命周期合计 2/2，76.397 s（主机 77.078 s）：五档需求、低功耗限制、切歌/seek/暂停、100 次进出、后台返回、旋转、录制导出和释放断言通过。连同最终 period 3 项、真实音源 1 项、图表 UI 2 项，本轮最终 app/test 配对执行 8 项，0 失败/跳过。
- 收尾已归档诊断证据，逐字节恢复测试前的播放/浏览偏好，移除本轮新增的测试偏好与私有夹具；三首用户音源保留，曲库为 112 首。没有恢复整份旧数据库来抹掉新导入或播放历史，也没有执行蓝牙/USB 外设验收。

## 2026-09-08 · Honor / iQOO 双机增量验收

### 环境和版本边界

- Honor STF-AL00，Android 9 / API 28，EMUI `9.1.0.225C00`，arm64，360×640 dp；iQOO V2171A，Android 15 / API 35，ROM `AP3A.240905.015.A2/compiler260112143406`，arm64，360×800 dp。序列号、备份及原始录制只保存在忽略的私有 QA 目录。
- 生产 APK 均为 `0.4.0-alpha.1` / code 8，SHA-256 `B280D6095CC38D454BF56583DFBFC71B9F690D1898FF58B916D574D020927FA0`，覆盖安装成功并核对设备 SHA。对应本次提交的生产源码；未发布新 release。
- 真实音源和 Honor UI/存储初轮 test APK 为 `8DC12B7E52B0EA2C0694591A5E1DAE1761CBDCE34EF1936C1237195ED8F1ECE7`；iQOO UI 初轮为 `730699D0AEB9491BBCB066DEFB256B6BD38CDAF4BA453764C7CFB9AA7DB107A5`；最终只修正跨版本测试同步/断言的 test APK 为 `8E572BFD29DE052DC81805D8DC49128A140271B8E75FBF0BAA2766E8EF09C5DF`。生产 APK 在这些批次中相同，不能声称测试 APK 始终相同。
- JDK 21 本地 186/186 JVM 用例通过，无跳过；Debug lint 0 errors / 21 warnings；Debug、Release 和 instrumentation APK 构建成功。测试断言最后修改后再次编译 instrumentation、执行 lint 成功。

### 真实音源与设备回归结果

三份用户音源（48 kHz / 24-bit FLAC、48 kHz / 24-bit WAV、96 kHz / 24-bit FLAC）已在两机 `/Music/Vesqen Test Audio/` 保留。独立文件头与 SHA-256 清单验证传输一致；文件名、标题与完整哈希清单不提交到仓库。

| 场景 | Honor Android 9 | iQOO Android 15 |
| --- | --- | --- |
| UI、列表排序/刷新保持、Chain 两入口与源标题 | 47/47，81.575 s | 初轮 46/47；首例宿主冲突后独立复测 1/1，合计 47 个用例通过 |
| 曲库 SQLite 迁移/保留、播放 checkpoint、遥测归属/释放、设置持久化 | 14 个用例通过，初批另有 2 个失败见下 | 与扬声器短测试同批 15/15，25.110 s |
| 切歌/seek/暂停、五档采样/低功耗、100 次观察者释放 | 原路由断言修正后通过 | 原无前台宿主连接超时；显式宿主后通过 |
| 100 次 Chain 进出、录制前后台、横竖屏/返回、JSON 隐私和释放 | 最终两项扬声器/生命周期同批 2/2，102.593 s | 生命周期通过；该批扬声器连接超时不能算整批通过，扬声器复测见上一行 |
| 三份真实无损音源、通知 metadata、切歌/seek/暂停、诊断隐私 | 显式音源测试 1/1，110.542 s | 显式音源测试 1/1，105.511 s |

每台共 **64 个不同的用例取得通过结果**，不是一次 64/64 的完整 runner。所有原失败输出保留，无跳过替代通过。私有原文在 `build/qa/two-device-20260907/{honor,iqoo}` 的各独立批次目录。

- Honor 源位深曾错误显示 16-bit：旧平台的 `pcm-encoding` 被当成压缩源位深。改为有界读取容器头，metadata revision 更新后重新扫描。两机三份文件源字段均为 24-bit，decoder input 采样率为 48/48/96 kHz；AudioTrack 请求分别为 48/48/96 kHz、pcm-16。后者不证明最终 DAC 输出格式，也不推翻源文件为 24-bit。
- Honor FLAC decoder 为 `OMX.google.flac.decoder`，iQOO 为 `c2.android.flac.decoder`；WAV 路径没有具名压缩 decoder 时保留 unavailable。所有样本声明为 SYSTEM MIXED，无 ERROR/UNDERRUN 事件；每首约 33 秒，不能代替长期或外部声学检测。
- Honor 三首高频/诊断快照数为 123/31、122/31、123/31；iQOO 为 129/33、126/32、128/32。导出可解析，不含媒体身份或路径，退出后 observer 为 0。
- 两机两份含封面 FLAC 均通过通知标题、非空 largeIcon、非空 contentIntent 断言。iQOO 实际展开通知可见曲名/艺术家/专辑缩略图，折叠通知可见曲名/艺术家和上一首/播放/下一首；点标题可回应用。该 OEM 折叠模板隐藏封面且保留箭头，应用公开 API 无保证移除，不承诺已经取消。
- Honor 实际查看播放器入口的摘要和高级 Chain，顶部正确显示“当前播放音源”；两入口与切换视图还有两机 UI 回归。展示标题不进入诊断数据。
- 测试边界修正及启动失败原因见中文 [工程案例 T02](ENGINEERING_CASEBOOK.md)。未为通过而修改系统导航模式、伪造已选择路由、关闭系统安全保护或删除断言。

### 性能与仍开放项

两机均完成独立 Library 快滑测量。iQOO Perfetto 和 Simpleperf、Honor 最后的小 buffer atrace 有有效证据；扫描污染的初轮及 Honor 内存不足的早期 trace 不计有效。列表测量、文字布局与预取已有热点证据，iQOO 还观察到 buffer 排队；**没有取得稳定修复收益，快滑继续 OPEN**。原始数据、公式、调用栈解释、不同 APK/采集开销及下一步见中文 [工程案例 P01](ENGINEERING_CASEBOOK.md)。

本轮没有重跑每机 30 分钟录制和三个 15 分钟模式；之前 iQOO 的长时证据仍仅对应当时版本。完整 Android 10–14/其余格式/无障碍/适配、真实 Bluetooth/USB/3.5 mm 与高频性能继续开放。Honor 可验证 M3 旧 API 路径，不能充当第二台 Android 14+ USB 设备。M3 主需求和维护优先级已列入 [开发清单](M3_IMPLEMENTATION_PLAN.md)。

## 2026-09-08 · M3 候选的 iQOO 真机回归

本轮只有 iQOO V2171A / Android 15（API 35）连接，未连接 Honor、Bluetooth 或 USB DAC。修复 M3 新增命令导致普通播放权限丢失的问题后，取得以下结果。最终 Debug SHA-256：`7bd6cfe3d22ea39939a8cec0c322f1c773c19879ba194c84ca4f63f3d51efe66`；Profile：`915aa81311d60db35ad9bf9de4f301eda4430a54d4096455a45b4e8523ed9d32`。

| 执行范围 | 有效结果 |
| --- | --- |
| 47 项 UI、Library 顺序/刷新、Chain 入口及适配 | 首批 45 通过；宿主冲突的返回用例独立 1/1；320×480、2 倍字体用例补上滚动到屏外 Chain 入口后独立 1/1，原布局断言保留。 |
| 无 USB 严格模式与扬声器短回归 | 修复前同批 2 失败；修复后 2/2，13.827 s。8 轮模式切换/重连、密集命令、无错误 ACTIVE、主动恢复 SYSTEM；切歌/seek/暂停、采样档位、100 次观察者释放与短诊断隐私通过。 |
| 三份真实无损音源与 14 项存储/设置/遥测契约 | 同批 15/15，116.239 s。48 kHz/24-bit FLAC、48 kHz/24-bit WAV、96 kHz/24-bit FLAC；三个高频窗口分别 129/126/129 个快照，均无记录到 ERROR/UNDERRUN，通知曲名/封面及诊断隐私断言通过。 |
| 100 次 Chain 进出、录制前后台、横竖屏和资源释放 | 独立 1/1，67.097 s。退出观察者归零，显式录制在后台保留唯一观察者，停止后释放。 |

共 **65 个不同用例分别取得通过结果**，不是一次完整 65/65 runner。一次两项 UI 独立批次被后续 instrumentation 提前打断，第二项未完成，该批不计成功；最终两项分别完整执行通过。原失败、中断、安装拒绝与作废性能采集都留在私有 `build/qa/m3-iqoo-20260908/`，没有用跳过或删除断言替代通过。

数据审计：测试前后 112 首曲目、1 个来源，曲目 ID、收藏及收藏顺序一致，SQLite integrity 为 `ok`；原播放队列和浏览偏好已恢复。真实歌曲的测试播放会形成相应历史记录。完整文件名、原始诊断、截图、trace 和用户数据备份不进入 Git。

Library 使用同一修复代码做实际 60 Hz 对照：带迷你播放器的 Debug 三轮 jank 为 1.30/1.16/0.51%、p95 26/25/23 ms；Profile 暂停为 0/0/0%、p95 11/11/10 ms，播放及索引开启场景各三轮均 0%、p95 10 ms。独立 Profile 播放 trace 仍有 1 个应用超时帧，主要等待 RenderThread。根因证据、调用栈方法、不同口径和未关闭项见 [工程案例 R06/P02](ENGINEERING_CASEBOOK.md)。

这些结果不关闭 M2 全部矩阵或 M3 里程碑。本轮没有重跑 30 分钟录制/75 分钟采样对照，没有验证 M3 真实 DAC、旧 Android 或实际 120 Hz；Honor 和两台现代手机×两款 DAC 仍待接入。新源码门禁为 195 项 JVM 通过、Lint 0 错误/21 个既有告警、Debug/Profile/Release 及 instrumentation APK 构建通过，远端 CI 与实机结果分开记录。

## 2026-09-08 · 无外设软件收尾与 Chain 短时性能对照

用户明确暂缓长时间稳定性测试。本轮未运行 8 小时播放、2 小时高采样率、3×15 分钟采样或 30 分钟录制；只做界面、短时帧统计与资源生命周期检查。

- 候选 Debug SHA-256：`74a858ae83d25bd6d286bedd0191a9b9c8037cab8855fb71879643ccbe3ba501`。
- 候选 Profile SHA-256：`87974fcd8362ab7852198a27d178084e8a6156400fa297d5091f3fc4257b87de`，已实际安装并核验非 debuggable。Profile 仍未启用 R8 optimization。
- 最后使用的测试 APK SHA-256：`4a376afcea5e0f5b059ed3c05ee2ee71f169cb169818050a69a60c73b0b4705f`，安装成功后核验哈希再复测。
- 本地 195 项 JVM 测试全部通过；Lint 0 错误、21 个既有告警；Debug、Profile 和 instrumentation APK 构建通过。当前变更只涉及 UI/测试/工具与文档，本轮未重新构建 Release。

**设备结果：50 个不同用例分别取得通过。** 49 项 `VesqenAppTest` 首轮 48 通过；新增动画测试把 48 ms 的过早观察点改到 96 ms 动画中段，保留进入/返回的方向和位移断言后独立 1/1（4.433 s）。另一次测试 APK 安装被拒时误启动了旧测试 APK，该次复测不作为更新版本证据，失败日志保留。最终复测只在安装成功并核对哈希后执行。

新增的两个 320 dp 解码器排版用例均通过：普通字体完整单行、双倍字体最多两行、无 TextLayoutResult 溢出，读数使用超过 80% 卡片宽度。减少动效用例确认交叉淡变期间没有空间位移；既有入口、返回、图表、单位切换和窗口适配回归保留。真实 Profile 截图也确认 360 dp 页面上的 decoder 单行完整显示，以及“250 毫秒”不换行。动效主观体验仍待用户确认。

`SpeakerChainLifecycleDeviceTest` 独立 1/1（78.747 s）：连续 100 次 Chain 进出，每次退出观察者归零，前后台录制/停止、横竖屏、隐私导出与最终资源释放通过。这是短时生命周期覆盖，不是长时间稳定性验收。

**高级页性能：** 同一 iQOO / Android 15 / 实际 60 Hz、同曲目单曲循环、详细视图与默认指标，三轮 250 ms 修复前为 0.90/0.87/0.52% jank，修复后为 0.89/0.35/0.70%；p95 两组均为 13/13/12 ms。1 s 修复前为 0.31/0.65/0.47%，修复后为 0.88/0.36/0.53%，p95 分别为 10/10/11 与 11/10/10 ms。不能据此宣称显著提速。Perfetto 与 Simpleperf 仍指出重组、文字布局的主线程成本；去除重复格式化/分组及非图表历史复制的具体证据和限制见 [中文工程案例 P03/R07](ENGINEERING_CASEBOOK.md)。

测试前后 112 首曲目、1 个来源，稳定 ID/收藏/收藏顺序与歌单内容一致，SQLite integrity 为 `ok`，六项系统设置一致。原播放队列、浏览偏好和原本未保存的默认 Chain 设置已恢复，实际测试播放会形成相应历史。完整备份、截图、trace 和日志位于私有 `build/qa/m2-software-closeout-20260908/`。跨设备、高刷新率、外设、完整格式/无障碍及长时门禁继续开放，M2/M3 均未宣布完整验收。
