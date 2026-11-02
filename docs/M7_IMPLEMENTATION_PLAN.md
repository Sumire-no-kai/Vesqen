# M7 开发清单：Vesqen 自有 USB 输出

2026-10-30，2026-11-02 更新。架构见 [M7 架构设计](M7_USB_ARCHITECTURE.md)。范围以 [PRD 的 M7 章节](PRD.md) 和 [#71](https://github.com/Sumire-no-kai/Vesqen/issues/71) 为准；相关事项为 [#49](https://github.com/Sumire-no-kai/Vesqen/issues/49)（外部验证）、[#53](https://github.com/Sumire-no-kai/Vesqen/issues/53)（2026-10-16 决定双机双 DAC 矩阵随 M7 完成）和 [#65](https://github.com/Sumire-no-kai/Vesqen/issues/65)（16 位补零）。按 [商业模式](MONETIZATION.md)，2.0 开始收费的条件是 M7 在没有官方通道的参考手机（iQOO）上接参考 DAC，实测达到 `BIT-PERFECT ACTIVE`。

## 当前状态

**M7 尚未开始编写代码。** 分支 `feature/usb-host-output` 及其 worktree `.claude/worktrees/usb-host-output` 于 2026-10-26 从 `master` 建立，目前没有自己的提交。M7-1 和 M7-2 在这个分支上开发，何时合入 `master` 见 D6。

可直接复用的部分：

| 现有部分 | M7 中的用途 |
| --- | --- |
| `UsbOutputStrategyResolver` / `UsbOutputDecision` | 纯策略判断，已有单元测试。现在 API 34 以下一律拒绝；M7 在此增加自有输出这一候选结果，官方通道可用时仍优先选择官方通道。 |
| `UsbOutputCoordinator` | 是 Media3 1.11 的 `ForwardingAudioOutputProvider`，在 `getAudioOutput()` 中决定输出目标。M7 在此返回一个把 PCM 交给自有 USB 引擎的 `AudioOutput`，队列、解码和 `PlaybackSnapshot` 不需要另建。 |
| 严格模式的失败处理 | 失败关闭、generation 防过期回调、`StrictUsbFailureDialog` 和非用户操作不弹窗的规则（#34）沿用。 |
| 链路页与验证记录 | `OutputVerification` 的记录匹配、`tools/generate_m4_test_vectors.py` 测试向量和 `tools/compare_m4_pcm.py` 逐样本比对可用于 #49。 |
| 试用与收费 | 试用从严格 USB 第一次实际开始播放时计时（#119），M7 的播放同样适用。 |

硬件：参考手机 iQOO 9（Android 15）可用，Honor（Android 9）可用于旧系统路径。DAC 目前只有 JBL Flip 7，它只报告 24 位 / 48 kHz；第二款 DAC 和数字采集设备均没有。所有者 2026-10-26 说明暂时无法购买 USB DAC，因此 M7-1 和 M7-2 先以 JBL Flip 7 作为唯一的 USB 音频设备进行，参考 DAC、#49 的外部验证和完整矩阵等硬件到位后再做。

## 已核实的平台事实

以下内容于 2026-10-30 和 2026-11-02 按 AOSP `main`、Linux 内核、Media3 1.11.0 源码和 Android 文档核对，ROM 行为仍须真机确认。

- **Java USB Host API 不支持等时传输。** `UsbEndpoint` / `UsbConstants` 文档注明等时端点不受支持，而 USB 音频流使用等时端点，因此音频数据须由 native 层经 `UsbDeviceConnection.getFileDescriptor()` 提交（Linux usbdevfs 的 `USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURB`）。设备打开、接口声明、`setInterface()` 和控制请求可以继续使用 Java API。项目目前没有 NDK/CMake 配置。
- **接管与释放。** `claimInterface(interface, true)` 会断开内核驱动，Android 的 USB 音频设备随之消失；`releaseInterface()` 成功后会请求内核重新连接驱动（`android_hardware_UsbDeviceConnection.cpp`）。内核在文件描述符关闭时只释放接口，不重新绑定驱动（`usbdev_release`），因此进程崩溃或被杀后 DAC 可能需要重新插拔，恢复方法见架构设计，尚未验证。
- **接管期间系统路由消失。** Vesqen 持有 DAC 时，系统提示音、通话和其他应用的声音会改由手机扬声器或其他设备输出。接入 M7 时必须把"Vesqen 主动接管"与"设备拔出"区分开。AOSP 把 USB 设备列入 `BECOMING_NOISY` 的设备集合，`PlaybackService` 又开启了相应处理，因此接管会使 Media3 自行暂停，接管期间须关闭这项处理。
- **USB 授权。** 用户须在系统弹窗中授权。对带麦克风输入的 DAC，AOSP 不要求应用持有录音权限，授权弹窗可能附加录音提示；用户在系统中关闭麦克风访问（Android 12 起的隐私开关）时，系统拒绝授予该设备的 USB 权限（`UsbUserPermissionManager.hasPermission()`）。
- **控制请求。** 向接口发送控制请求前须已声明该接口（内核 `check_ctrlrecip`），因此 AudioControl 接口也须声明。
- **传输上限。** 单个等时 URB 最多 128 个包（内核 `USBDEVFS_SUBMITURB`）。
- **音量键。** Player 报告 `DeviceInfo.PLAYBACK_TYPE_REMOTE` 时，Media3 把媒体会话设为远程音量（`MediaSessionLegacyStub.createVolumeProviderCompat()`）。
- **现有数据。** 使用统计已经发送 `bitPerfectMixer` 及手机型号和 ROM；设备报告的音频能力部分已经包含 VID/PID 和接口协议，可以看出 UAC 版本。
- **版本范围。** 1.0 起最低版本为 API 28（#149），因此 PRD 中"Android 8–13"在实际范围上为 Android 9–13，另加没有 bit-perfect mixer attribute 的 Android 14+ 设备。
- **UAC1 带宽。** UAC1 设备通常运行在全速 USB，带宽约可支持 96 kHz / 24 位双声道；176.4 kHz 及以上的双声道 PCM 需要 UAC2 高速设备。

## 决定事项

| 编号 | 事项 | 结论 |
| --- | --- | --- |
| D1 | 参考 DAC 和数字采集设备 | **待定**，须在 2027-02 底之前到位（#210）。建议：至少一款常见的 UAC2 异步便携 DAC，且带硬件音量；另确认 JBL Flip 7 的 UAC 版本，作为 UAC1 或第二款设备。#49 的采集链路建议采用带 S/PDIF 输出的 USB 数字转盘，接入带 S/PDIF 输入的录音接口，在 Mac 上录制后逐样本比对；数字转盘本身是 UAC 设备，可以同时验证 M7 路径。S/PDIF 最高约 192 kHz / 24 位。 |
| D2 | 等时传输层 | 2026-11-02 决定：自写 usbdevfs 层。设备打开、接口声明和控制请求使用 Java API，native 只负责提交和回收等时 URB，不引入 libusb，也就没有 LGPL-2.1 义务。G1 遇到无法解决的问题时再评估 libusb（它可经 `libusb_wrap_sys_device` 在未 root 的手机上使用）。 |
| D3 | 第一版范围 | 2026-11-02 决定：UAC1 和 UAC2 双声道 PCM，包含异步反馈，取代 PRD 原先"先限定 UAC1"的写法。DSD/DoP 和多声道留到之后。先实现 JBL Flip 7 实际使用的版本，另一版本待参考 DAC 到位后验证，未经实测不对外声明支持。 |
| D4 | 音量 | 2026-11-02 决定：有硬件音量（Feature Unit）的 DAC 使用硬件音量，音量键经媒体会话的远程音量接收（vivo ROM 待实测）。首次接管时在第一个样本之前把音量降到约 -30 dB（设备范围不足时取下限），此后按 VID/PID 在本机记住设置，释放时恢复接管前的音量。没有硬件音量的 DAC，每款设备第一次使用时说明输出为满幅、音量只能由 DAC 或后级设备调节，用户确认后以固定音量播放。不使用软件音量。 |
| D5 | 打包 | 2026-11-02 决定：native 库打入 APK，沿用现有 APK 已有的四种 ABI，不设 `abiFilters`，GitHub 版继续只发一个 APK，按 16 KB 页对齐（NDK r28 起默认）。只有开始自有输出时才加载，加载失败按失败关闭并说明原因，普通播放不依赖它。体积预算在 M7-2 实测后确定。 |
| D6 | M7 的开发分支与 Play 版 1.x 的维护分支 | 2026-11-01 所有者决定：M7-1 和 M7-2 在 `feature/usb-host-output` 上开发，这两个阶段的 PR 合入该分支，不进 `master`；每发布一个 1.x 版本，就把 `master` 合并进该分支一次。M7-2 通过（G1）且 1.2.0 发布之后，从最后一个 1.x 标签建立 `release/1.x` 维护分支，再把 M7 合入 `master`，M7-3 起的 PR 直接合入 `master`。G1 不通过时，代码留在该分支，由所有者重新评估范围。Play 版停留在 1.x，之后的小修从 `release/1.x` 发布；两边都需要的修复分别合入 `master` 和 `release/1.x`。 |
| D7 | 是否增加"这台手机是否开放官方通道"的数据 | 2026-11-02 关闭：不增加。使用统计已经发送 `bitPerfectMixer`，隐私政策已写明；设备报告的音频能力部分已经包含 VID/PID 和接口协议。原记录中"设备报告只记录失败原因"有误，在此更正。 |
| D8 | USB 授权的持久方式 | **待定**，最晚在 M7-5 之前。授权默认只在当次连接有效；系统授权框提供"始终允许"的前提是应用声明了匹配的 `USB_DEVICE_ATTACHED` 过滤器（AOSP `canBeDefault()`）。建议：过滤器放在默认禁用的 Activity 上，用户开启严格模式后才启用，代价是启用后插入 DAC 时系统会询问是否打开 Vesqen（待实测）。另一方案是不声明过滤器，每次插入后由系统授权框询问一次。 |

## 分阶段实施

每个阶段对应一个或数个 PR，按 D6 合入：M7-1 和 M7-2 合入 `feature/usb-host-output`，M7-3 起合入 `master`。阶段之间按依赖顺序推进，前一阶段的结论可能改变后续范围。

### M7-0 准备（不含代码）

- [x] 所有者确定 D2–D7（D6 于 2026-11-01，其余于 2026-11-02）。
- [ ] 所有者确定 D8；D1 的设备在具备购买条件后确定。两者都不阻挡 M7-1 和 M7-2。
- [ ] M7-1 开始前，按 D6 把 `feature/usb-host-output` 更新到最新的 `master`。
- [ ] 开始真机测试前建立 `docs/M7_DEVICE_ACCEPTANCE.md`，记录格式沿用 [M4 设备验收](M4_DEVICE_ACCEPTANCE.md)。
- [ ] 在修改 `UsbOutputCoordinator` 之前补齐其单元测试（[发布待办](LAUNCH_BACKLOG.md)"1.1.0 之后、2.0 之前"中的遗留项）。

### M7-1 描述符读取（只读）

- [ ] 用 Kotlin 解析 `UsbDeviceConnection.getRawDescriptors()`：UAC 版本、音频流接口与 alternate setting、格式（位深、subslot、声道、采样率）、端点同步类型和反馈端点、Feature Unit 的音量和静音范围、UAC2 时钟源。
- [ ] 以参考 DAC 的真实描述符作为测试样本，覆盖解析与不支持的结构。
- [ ] 仅在调试构建中提供读取入口，不声明接口，不传输音频。

完成条件：JBL Flip 7 和此后加入的每款参考 DAC 都有描述符记录和解析结果；据此确定先实现 UAC1 还是 UAC2（D3）。

### M7-2 技术验证

- [ ] 固定 `ndkVersion`，CI 安装同一版本的 NDK。
- [ ] 在调试构建中实现最小的 native 等时写入：声明接口、选择 alternate setting、设置并读回采样率、播放测试向量、释放接口。
- [ ] 在 iQOO 上先以 JBL Flip 7 测试，参考 DAC 到位后逐款重复：连续播放 60 秒和 30 分钟、播放中拔出、播放中结束进程，确认释放后系统重新接管该设备。
- [ ] 逐项验证[架构设计](M7_USB_ARCHITECTURE.md)中"待实测的设计假设"，包括接管时的 `BECOMING_NOISY`、进程被结束后的恢复和息屏 30 分钟。
- [ ] 记录欠载次数、CPU 占用、唤醒次数和耗电，以及 native 库增加的体积（D5）。具备采集设备时完成第一次逐样本比对。

完成条件：JBL Flip 7 在 iQOO 上无欠载播放测试向量，且释放后系统可正常使用该设备。参考 DAC 的同项结果在其到位后补充，作为 M7-3 之后继续推进的依据。结论记入本文件；如结论不支持继续，先由所有者重新评估 M7 范围。

### M7-3 USB 引擎

- [ ] 开始前，按 D6 从最后一个 1.x 标签建立 `release/1.x`，把 `feature/usb-host-output` 合入 `master`。
- [ ] 建立独立的 `:usb-engine` Gradle 模块（Kotlin 与 C++），对应用只暴露小型接口。
- [ ] 实现 URB 队列、环形缓冲、按反馈调整的包长、采样率与 alternate setting 切换、错误映射，以及供 Media3 使用的播放位置。按 D3 覆盖 UAC1 和 UAC2，以及全速和高速的包间隔与反馈格式。
- [ ] 实现 `ExactPcmConverter`：float 按源位深还原为整数并校验，校验失败时失败关闭；按 #65 的规则补零。32 位源只接受整数输入。不做重采样、抖动或截断。
- [ ] native core 在主机上单元测试，其中必须验证：各种反馈序列下，所有包的载荷拼接后与输入逐字节一致。

### M7-4 接入播放服务

- [ ] `UsbOutputStrategyResolver` 增加自有输出候选：官方通道不可用、用户已开启严格模式、已授予 USB 权限、描述符确认支持当前源格式。路径按 USB 连接选择，不按曲目切换。由 `vesqen.directUsb` 构建开关控制，到 2.0.0-beta.1 时开启。
- [ ] `UsbOutputCoordinator.getAudioOutput()` 为自有输出返回 `DirectUsbAudioOutput`，覆盖播放、暂停、flush、seek、切歌、格式变化和服务重建。
- [ ] 区分主动接管与设备拔出：接管期间关闭 `BECOMING_NOISY` 处理，拔出由 `ACTION_USB_DEVICE_DETACHED` 和 URB 错误判断。拔出、端点出错、权限撤销和描述符不匹配时失败关闭并撤销 `ACTIVE`。
- [ ] 来电等暂时失去音频焦点时释放接口，让系统可以使用 DAC；恢复时重新接管。
- [ ] 增加与已声明接口对应的清理记录，确保任何失败路径都会释放接口。
- [ ] 同格式曲目之间连续播放；格式变化需要切换 alternate setting 时的停顿如实列为限制。
- [ ] 修复官方路径（#71 2026-10-23）：在开放通道的手机上，多数文件因 float 与 HAL 的整数格式不符而停止。严格模式下 16 位源保持 16 位整数，24 位源经 `ExactPcmConverter` 精确转为 24 或 32 位整数。现有测试机都没有开放官方通道，须在具备条件的手机和 DAC 上以 `getSupportedMixerAttributes()` 核对。

### M7-5 音量、界面与链路页

- [ ] 按 D4 实现硬件音量和音量键；开始播放前降低硬件音量。
- [ ] 设置、Now 和链路页显示"Vesqen 直接输出到 DAC"及实际格式；链路页说明系统音频路由在此期间不经过该 DAC。
- [ ] 第一次使用时说明：接管期间其他应用和系统提示音会改由手机扬声器输出；需要 USB 授权；关闭麦克风访问时系统可能拒绝授权。
- [ ] 失败时说明卡在哪个环节（#71 2026-11-02，M7 的完成条件）：复用官方路径的 `usb_failure_*` 机制，不另建展示方式，不使用笼统的"播放失败"。至少覆盖 USB Host 权限；设备枚举与生命周期（含拔出和热插拔）；UAC 版本与接口、endpoint 选择；格式协商；传输与缓冲错误；时钟与反馈；设备组合不在支持范围内。原因只陈述实际观察到的情况，无法确定环节时明确说明。失败时停止播放，不自动切换回系统输出，也不提升状态。
- [ ] 失败原因同时出现在播放界面、链路页和设备报告中，四种语言使用正式书面用语。
- [ ] 诊断导出和设备报告遵循现有隐私过滤规则。新的取值进入设备报告前，先更新 `contracts/device-report/vocabulary.json` 并部署服务端。
- [ ] 隐私政策写明本机保存的内容：按 VID/PID 记录的音量和固定音量确认，以及接管标记。

### M7-6 验收

- [ ] iQOO（Android 15）、一台 Android 16 手机和 Honor（Android 9）分别连接各参考 DAC，测试 44.1 kHz / 16 位、48 kHz / 24 位、96 kHz / 24 位，以及 DAC 支持时的 192 kHz / 24 位。
- [ ] 16 位音源送入只提供 24 位格式的 DAC（#65）。
- [ ] 插拔 50 次（PRD 10.2）、后台与锁屏、来电、进程被杀后的恢复，以及耗电与温度；8 小时连续播放按所有者安排执行。
- [ ] 用真实 DAC 和参考手机逐项触发 M7-5 的失败原因，确认显示的环节与实际一致；无法用真机触发的项目用测试覆盖，并注明。
- [ ] 至少一条组合完成外部逐样本比对并签发验证记录，候选 APK 实际显示 `BIT-PERFECT VERIFIED`（#49）。
- [ ] iQOO 接参考 DAC 达到 `BIT-PERFECT ACTIVE`，作为 2.0 开始收费的条件。

## 声明边界

自有输出显示 `BIT-PERFECT ACTIVE` 须同时满足：已声明接口，alternate setting 与源格式一致，采样率已设置且读回一致（设备不支持读回时显示为不可用，不得推定），Vesqen 的处理链没有改动样本，且输出正在进行。欠载会插入静音而不改写样本，但必须计数并在链路页显示。DAC 屏幕显示的采样率、听感和描述符声明的能力都不能作为 `VERIFIED` 的依据；`VERIFIED` 仍然只来自外部逐样本比对的签名记录。

## 风险

| 风险 | 应对 |
| --- | --- |
| ROM 限制或改动 USB Host 行为 | M7-2 先在 iQOO 上验证；有问题的 ROM 列入兼容表并失败关闭。 |
| 等时传输在后台或省电策略下欠载 | 高优先级 native 线程、合并多包的 URB、真机测量欠载和耗电。 |
| 进程异常退出后 DAC 无法被系统使用 | M7-2 实测；如无法自动恢复，在说明中写明重新插拔即可恢复。 |
| 硬件音量缺失或开始时音量过大 | 按 D4：第一个样本之前降低硬件音量；没有硬件音量的设备须用户确认。 |
| DAC 碎片化 | 以参考 DAC 为准，描述符不在支持范围内的设备失败关闭并说明原因。 |

## 不在 M7 范围内

DSD/DoP、多声道、重采样、EQ 等 DSP 处理，M6 的自研播放内核（M7 继续使用 Media3 解码），以及 Play 版的应用内购买。
