# M1 真机验收门禁

本文件只定义 M1 的最终真机证据。代码存在、本地 JVM 测试、APK 构建、Android 测试源码可编译或有限手动冒烟均不能替代这些结果。

## 验收前提

- 使用准备发布的同一 commit 与 APK，并记录版本名、versionCode、APK SHA-256。
- 至少覆盖一台 Android 8/9 设备或模拟器、一台 Android 13、一台 Android 14，以及一台当前稳定 Android 版本设备；物理音频与 ROM 行为必须由真机覆盖。
- 设备测试前保留用户原有音乐与播放列表；测试夹具放入独立的 `Vesqen M1 QA` 文件夹。
- 核心格式夹具至少包含：FLAC 16/44.1、FLAC 24/96、ALAC、WAV PCM 16/24/32、AIFF PCM 16/24/32、MP3、AAC/M4A、Ogg Vorbis 和 Opus。连续专辑另准备两首已知无缝边界样本。

## 自动化 runner

1. 记录 `adb devices -l` 的目标序列号和系统版本。
2. 构建 `assembleDebug` 与 `assembleDebugAndroidTest`，再对明确序列号使用 `adb install -r -t` 覆盖安装两个 APK；禁止使用会卸载主应用或清空现有曲库数据的便捷任务。
3. 使用明确序列号直接执行 `adb shell am instrument -w -r io.github.sumirenokai.vesqen.test/androidx.test.runner.AndroidJUnitRunner`。测试用数据库与偏好必须采用隔离名称，不得覆盖真实用户数据。
4. 只在 instrumentation 输出非空、退出码为 0、无 failure／crash 且报告与目标序列号对应时记为通过。
5. 保存测试报告摘要；不得用 `compileDebugAndroidTestKotlin`、JVM 测试或手动检查替代。

## 功能矩阵

- MediaStore 与多个 SAF 文件夹可同时导入；新增、删除、移动与修改标签后增量结果正确。
- 扫描可暂停、继续；杀死并重启应用后保留已提交曲库，能继续中断来源。
- 歌曲、专辑、艺术家、文件夹、流派与播放列表浏览结果正确；搜索、全部排序、收藏、最近播放和播放次数可持续保存。
- 播放列表可创建、重命名、删除、添加、移除和调整顺序。
- 当前队列可播放指定项、上移、下移、删除和清空；进程重启后恢复队列、顺序模式和播放位置，但不会意外自动播放。
- 所有格式夹具可开始播放、拖动、切换下一首并正确显示可读取的来源元数据；异常标签或损坏封面不会阻止播放。
- 已知连续专辑样本的边界没有由 Vesqen 引入的暂停；结论记录设备、ROM、格式和样本哈希。

## 稳定性与路由

- 连续播放 8 小时，无崩溃、无失控后台服务，队列与通知状态一致。
- FLAC 24/96 连续播放 2 小时，记录 CPU、内存、耗电、温升及可感知异常。
- 完成 100 次播放/暂停/切歌/拖动组合，状态一致且无崩溃。
- 分别验证来电、导航提示、耳机按键与音频焦点恢复。
- 分别断开 3.5 mm、蓝牙和系统 USB 输出，应用不崩溃、不保留错误状态；重复 USB 插拔 50 次。
- 输出设备变化能自动刷新“已连接输出类型”；该证据仍不得被表述为活动路由、直出或 bit-perfect 证明。
- 大型曲库扫描与播放并行时没有可感知播放中断，并记录曲目规模与扫描耗时。

## UI 与可访问性

- 在窄屏、传统 16:9、现代长屏、横屏播放页和宽窗口检查无裁切、重叠或不可达操作。
- 深色/浅色、系统字体 1.0×/1.3×/1.5×/2.0×均检查曲库、播放列表、队列、Now、Chain、设置与关于。
- TalkBack 能说明所有主要按钮、选中状态、播放错误和队列操作；触控目标不小于 48 dp。
- 系统减少动效开启时，播放器展开、返回、曲目切换和状态变化使用既定淡变回退。

## 2026-09-07 新增需求验收（未执行）

- 字母索引：全部歌曲标题升序且无搜索时默认显示；关闭后跨进程重启保持关闭。收藏、歌单及其他集合内部、搜索或非标题排序不显示。验证 A/Z/#、无曲目的字母、大小写、中英混排、多音字、数字、空标题和相同标题；跳转第一首必须与列表实际排序一致。
- 索引交互：点按与拖动可达且不覆盖曲目操作或 mini-player；窄屏／大字号／TalkBack 的字母选择器有正确状态及至少 48 dp 操作，不依赖密集小字母完成唯一导航。开启索引不能新增明显滑动掉帧。
- 自定义顺序：收藏与两个独立自建歌单分别拖动、上移、下移首尾与中间项，重启、重扫及切换全库排序后保持各自顺序。搜索／自动排序视图不能错误写入底层顺序；返回自定义视图恢复原顺序。
- 数据兼容：隔离夹具验证旧收藏首次确定性初始化、旧歌单顺序保留、新成员追加、移除后重加追加，以及失败／暂停扫描和权限撤销不丢失旧元数据。迁移不清空真实收藏、历史、歌单和队列。
- 队列边界：从收藏／歌单开始顺序播放符合当时显示顺序；随后编辑集合不修改正在播放的队列、当前项或进度。单独编辑队列仍按原契约工作。
- 封面状态：在目标设备录屏比较播放与暂停，约 5% 缩放清楚可见且克制，无循环／弹跳、裁切和运输台位移；快速连续切换、横屏、大字号、无封面及减少动效均正常。录屏记录应用版本、设备与动画设置，不能只凭截图判断动效。
- 进度圆点：先保存用户报告场景截图，再对修复版本记录 0/25/50/75/100%、按压、拖动、切歌及横竖屏截图和几何断言。圆心与轨道中线在渲染舍入容差内对齐，已播段端点、触控位置与 seek 一致；0 时长不可伪拖动。不得硬编码特定手机偏移。
- 滚动性能：在报告问题的手机上使用同一曲库、相同刷新率、温度区间、构建类型及固定快速往返脚本，分开比较封面冷／暖缓存、播放／暂停、扫描中／结束及索引开／关。每种前后对照至少重复三次，保留帧时间 p50/p95/p99、超帧预算比例、主线程／RenderThread／GC／位图工作和样本时长。
- 性能关闭条件：识别 trace 中的具体瓶颈，单变量修复的改善超过基线波动，实际快速滑动明显卡顿不再复现；音频不中断、封面仍正确且内存有界。优先以可代表发布行为的构建确认；不能把 Debug 的跨设备或不同缓存状态数字混为前后对照。未取得报告设备结果时保持 OPEN。

## 结论记录

每项结果必须包含设备型号、Android/ROM build、应用版本与 commit、测试时间、夹具哈希、执行步骤、通过/失败和异常日志。全部门禁通过后，M1 才能标记为完成；USB 直出、Audio Proof 与 bit-perfect 不属于本文件的 M1 结论。

## 2026-09-07 connected iQOO partial evidence

- V2171A, Android 15, SM8450, observed active display mode 60 Hz. Now pause/play and midpoint-seek screenshots confirm a centered thumb and approximately 5% cover transition. Artifacts: ignored `build/qa/iqoo-m1m2-20260907/fixed-now-*.png`.
- New ordering contract: edit in the original Favorites/playlist list, drag handles with continuous edge scrolling, save/cancel, no separate sheet and no visual up/down button column. Large-library drag, cancellation and persistence still require the matching instrumentation runner/device checks.
- Scroll-jank acceptance remains open; collected gfxinfo and Perfetto artifacts are measurements, not proof of a root cause or repair.
- Updated instrumentation installation was rejected by OEM security. The old installed test runner produced ABI errors and is not a valid test result for this candidate. Do not run connectedDebugAndroidTest or clear user data to work around this gate.
- Honor, route disconnect, accessibility and endurance matrix coverage remain pending. Local unit/build/lint results are recorded separately in DEVELOPMENT_LOG.md.

Manual follow-up: original-list Favorites drag, Save, process restart and persisted order were confirmed on iQOO using two temporary favorites; initial empty membership was restored afterward. This closes that narrow flow only, not the large-list/custom-playlist/accessibility matrix. See `verified-inline-reordered.png` and `verified-persisted.xml` in the ignored local QA directory.

## 2026-09-07 review 修复增量（非完整关闭）

- 当前修复包含列表保留与历史 occurrence 区分；180 项本地单元回归通过。
- iQOO 手动：滚动区域点播及同歌重新点播后，可见曲目节点位置保持；播放器收藏同步到我的喜欢，重启后保留，横屏收藏入口可见。临时收藏已撤回。
- 首页第一版等宽筛选块被用户否定；第二版页头收藏导航、收藏页返回/空状态、曲库菜单已实机查看，截图见私有 build/qa/review-fixes-20260907/home-redesign-final.png。
- 匹配 test APK 已成功安装，但 LibraryRefreshUiTest / LibraryOrderUiTest 执行未取得完成结果，不能计通过。详见开发日志。
- 快滑帧率归因、Honor、大字体/平板及完整功能矩阵继续开放。

## 2026-09-07 · 扬声器验收中的交互回归

- 在保留用户数据的安装上，`tools/run_device_tests.py` 已取得有效 AndroidJUnitRunner 结果，替代此前挂起/ABI 不匹配的无效记录；历史失败不追溯改成通过。
- 最终模拟器 UI 46/46、0 跳过，包含 100 曲目原列表向下/反向拖动与不触发点播、曲库刷新保留视口。拖动暴露并修复了 LazyColumn 跟随首可见 key 而自行滚动的问题。
- 实际播放器横竖屏/返回测试发现系统导航栏残留隐藏状态并已修复，模拟器 100 次 Chain 导航及录制生命周期回归通过。最终大字体工具栏修复仅模拟器验证，真机更新需要 vivo 指纹验证，不能把模拟器结果写成真机最终产物结果。
- 这不关闭既定 M1 快滑三次前后对照、真实格式与路由中断、完整无障碍/跨设备矩阵。详细产物和真机长时记录参见 [M2 验收记录](M2_DEVICE_ACCEPTANCE.md)。

## 2026-09-08 · 双机功能与快滑定位

- 最终生产 APK 已成功保留数据安装到 Honor STF-AL00 / Android 9 和 iQOO V2171A / Android 15；精确 SHA 和测试 APK 批次见 [M2 最新记录](M2_DEVICE_ACCEPTANCE.md)。此前安装阻挡和旧系统未执行的记录仅描述当时状态。
- 两机分别取得 47 个不同 UI 用例的通过结果，包含原列表拖动、刷新保留视口、设置/播放器进入 Chain 并显示当前曲目。Honor 一批 47/47；iQOO 首例宿主冲突独立复测通过，不能写成原批次全通过。
- 两机实际播放控制与 100 次 Chain 进出、后台录制、播放器横竖屏返回及资源释放均取得通过结果。Honor 导航栏验收尊重原硬件导航模式，并检查应用已清除隐藏标志，不强行改系统导航设置。
- 三份实际 24-bit 无损音源在两机验证；新增容器头回归修复 Honor 源位深误报。系统媒体通知得到封面和返回入口；iQOO 紧凑模板的封面展示及箭头是 OEM 限制。
- Library 快滑在两机完成帧统计；有效 iQOO Perfetto/CPU 栈和 Honor atrace 指向布局、文本及预取热点。尚无可靠优化收益，M1 性能体验仍未关闭。可复现步骤、失效实验和调用栈解释见 [中文工程案例 P01](ENGINEERING_CASEBOOK.md)。
- 两机原曲目 ID、来源身份、收藏和歌单保留。Honor 从 37 首增加到 40 首，iQOO 保持 112 首；保留用户导入，恢复测试前播放/浏览偏好和系统字体/方向/超时设置，清理本轮私有夹具。没有用旧数据库覆盖新音源或历史。
