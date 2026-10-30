# M7 开发清单：Vesqen 自有 USB 输出

2026-10-30。范围以 [PRD 的 M7 章节](PRD.md) 和 [#71](https://github.com/Sumire-no-kai/Vesqen/issues/71) 为准；相关事项为 [#49](https://github.com/Sumire-no-kai/Vesqen/issues/49)（外部验证）、[#53](https://github.com/Sumire-no-kai/Vesqen/issues/53)（2026-10-16 决定双机双 DAC 矩阵随 M7 完成）和 [#65](https://github.com/Sumire-no-kai/Vesqen/issues/65)（16 位补零）。按 [商业模式](MONETIZATION.md)，2.0 开始收费的条件是 M7 在没有官方通道的参考手机（iQOO）上接参考 DAC，实测达到 `BIT-PERFECT ACTIVE`。

## 当前状态

**M7 尚未开始编写代码。** 分支 `feature/usb-host-output` 及其 worktree `.claude/worktrees/usb-host-output` 于 2026-10-26 从 `master` 建立，目前没有提交。

可直接复用的部分：

| 现有部分 | M7 中的用途 |
| --- | --- |
| `UsbOutputStrategyResolver` / `UsbOutputDecision` | 纯策略判断，已有单元测试。现在 API 34 以下一律拒绝；M7 在此增加自有输出这一候选结果，官方通道可用时仍优先选择官方通道。 |
| `UsbOutputCoordinator` | 是 Media3 1.11 的 `ForwardingAudioOutputProvider`，在 `getAudioOutput()` 中决定输出目标。M7 在此返回一个把 PCM 交给自有 USB 引擎的 `AudioOutput`，队列、解码和 `PlaybackSnapshot` 不需要另建。 |
| 严格模式的失败处理 | 失败关闭、generation 防过期回调、`StrictUsbFailureDialog` 和非用户操作不弹窗的规则（#34）沿用。 |
| 链路页与验证记录 | `OutputVerification` 的记录匹配、`tools/generate_m4_test_vectors.py` 测试向量和 `tools/compare_m4_pcm.py` 逐样本比对可用于 #49。 |
| 试用与收费 | 试用从严格 USB 第一次实际开始播放时计时（#119），M7 的播放同样适用。 |

硬件：参考手机 iQOO 9（Android 15）可用，Honor（Android 9）可用于旧系统路径。DAC 目前只有 JBL Flip 7，它只报告 24 位 / 48 kHz；第二款 DAC 和数字采集设备均没有。

## 已核实的平台事实

以下内容于 2026-10-30 按 AOSP `main` 源码和 Android 文档核对，ROM 行为仍须真机确认。

- **Java USB Host API 不支持等时传输。** `UsbEndpoint` / `UsbConstants` 文档注明等时端点不受支持，而 USB 音频流使用等时端点，因此音频数据须由 native 层经 `UsbDeviceConnection.getFileDescriptor()` 提交（Linux usbdevfs 的 `USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURB`）。设备打开、接口声明、`setInterface()` 和控制请求可以继续使用 Java API。项目目前没有 NDK/CMake 配置。
- **接管与释放。** `claimInterface(interface, true)` 会断开内核驱动，Android 的 USB 音频设备随之消失；`releaseInterface()` 成功后会请求内核重新连接驱动（`android_hardware_UsbDeviceConnection.cpp`）。进程崩溃或被杀时系统能否重新接管，尚未验证。
- **接管期间系统路由消失。** Vesqen 持有 DAC 时，系统提示音、通话和其他应用的声音会改由手机扬声器或其他设备输出。现有路由监听会把 USB 路由消失判定为设备丢失，接入 M7 时必须把"Vesqen 主动接管"与"设备拔出"区分开。
- **USB 授权。** 用户须在系统弹窗中授权。对带麦克风输入的 DAC，AOSP 不要求应用持有录音权限，授权弹窗可能附加录音提示；用户在系统中关闭麦克风访问（Android 12 起的隐私开关）时，系统拒绝授予该设备的 USB 权限（`UsbUserPermissionManager.hasPermission()`）。
- **版本范围。** 1.0 起最低版本为 API 28（#149），因此 PRD 中"Android 8–13"在实际范围上为 Android 9–13，另加没有 bit-perfect mixer attribute 的 Android 14+ 设备。
- **UAC1 带宽。** UAC1 设备通常运行在全速 USB，带宽约可支持 96 kHz / 24 位双声道；176.4 kHz 及以上的双声道 PCM 需要 UAC2 高速设备。

## 需要所有者决定的事项

| 编号 | 事项 | 建议 |
| --- | --- | --- |
| D1 | 参考 DAC 和数字采集设备 | 至少一款常见的 UAC2 异步便携 DAC，且带硬件音量；另确认 JBL Flip 7 的 UAC 版本，作为 UAC1 或第二款设备。#49 的采集链路建议采用带 S/PDIF 输出的 USB 数字转盘，接入带 S/PDIF 输入的录音接口，在 Mac 上录制后逐样本比对；数字转盘本身是 UAC 设备，可以同时验证 M7 路径。S/PDIF 最高约 192 kHz / 24 位。 |
| D2 | libusb 或自写 usbdevfs 层 | 建议自写。native 部分只需提交和回收等时 URB，代码量有限，并可避免 libusb 的 LGPL-2.1 义务；如技术验证遇到无法解决的问题，再评估 libusb。 |
| D3 | 第一版范围 | PRD 写明先做 UAC1 PCM。建议在 M7-1 读取参考 DAC 的描述符后再定；如参考 DAC 为 UAC2，第一版应包含 UAC2 双声道 PCM 和异步反馈，否则无法覆盖主要设备。DSD/DoP 和多声道留到之后。 |
| D4 | 音量 | 建议使用 DAC 硬件音量（Feature Unit），并由媒体会话的远程音量接收音量键（候选方案，需验证 Media3 与 vivo ROM 的行为）。开始播放前先把硬件音量设到较低值。没有硬件音量的 DAC 是否允许以固定音量播放，需要决定。 |
| D5 | 打包 | 建议 native 库直接打入 GitHub 版 APK，ABI 为 arm64-v8a 和 armeabi-v7a，另为模拟器测试加入 x86_64；从一开始按 16 KB 页对齐（NDK r28 起默认）。 |
| D6 | Play 版 1.x 的维护分支 | Play 版停留在 1.x。M7 的第一批代码合入 `master` 之前，应从最后一个 1.x 标签建立维护分支，Play 版的小修从该分支发布。 |
| D7 | 设备报告是否记录"这台手机是否开放官方通道" | 设备报告目前只记录严格模式的失败原因。增加字段属于数据收集变更，须先说明上传内容，经所有者确认后再做；不做也不影响 M7。 |

## 分阶段实施

每个阶段对应一个或数个 PR。阶段之间按依赖顺序推进，前一阶段的结论可能改变后续范围。

### M7-0 准备（不含代码）

- [ ] 所有者确定 D1–D7，购买参考 DAC 和采集设备。
- [ ] 按 D6 建立 1.x 维护分支。
- [ ] 开始真机测试前建立 `docs/M7_DEVICE_ACCEPTANCE.md`，记录格式沿用 [M4 设备验收](M4_DEVICE_ACCEPTANCE.md)。
- [ ] 在修改 `UsbOutputCoordinator` 之前补齐其单元测试（[发布待办](LAUNCH_BACKLOG.md)"1.1.0 之后、2.0 之前"中的遗留项）。

### M7-1 描述符读取（只读）

- [ ] 用 Kotlin 解析 `UsbDeviceConnection.getRawDescriptors()`：UAC 版本、音频流接口与 alternate setting、格式（位深、subslot、声道、采样率）、端点同步类型和反馈端点、Feature Unit 的音量和静音范围、UAC2 时钟源。
- [ ] 以参考 DAC 的真实描述符作为测试样本，覆盖解析与不支持的结构。
- [ ] 仅在调试构建中提供读取入口，不声明接口，不传输音频。

完成条件：每款参考 DAC 都有描述符记录和解析结果；据此确定 D3。

### M7-2 技术验证

- [ ] 在调试构建中实现最小的 native 等时写入：声明接口、选择 alternate setting、设置并读回采样率、播放测试向量、释放接口。
- [ ] 在 iQOO 上逐款参考 DAC 测试：连续播放 60 秒和 30 分钟、播放中拔出、播放中结束进程，确认释放后系统重新接管 DAC。
- [ ] 记录欠载次数、CPU 占用、唤醒次数和耗电。具备采集设备时完成第一次逐样本比对。

完成条件：至少一款参考 DAC 在 iQOO 上无欠载播放测试向量，且释放后系统可正常使用该 DAC。结论记入本文件；如结论不支持继续，先由所有者重新评估 M7 范围。

### M7-3 USB 引擎

- [ ] 建立独立的 `:usb-engine` Gradle 模块（Kotlin 与 C++），对应用只暴露小型接口。
- [ ] 实现 URB 队列、环形缓冲、按反馈调整的包长、采样率与 alternate setting 切换、错误映射，以及供 Media3 使用的播放位置。
- [ ] 格式转换只允许无损的整数重排：float 还原为 24 位整数、按 #65 的规则补零。不做重采样、抖动或截断。
- [ ] 包长与反馈换算等纯计算部分在主机上单元测试。

### M7-4 接入播放服务

- [ ] `UsbOutputStrategyResolver` 增加自有输出候选：官方通道不可用、用户已开启严格模式、已授予 USB 权限、描述符确认支持当前源格式。
- [ ] `UsbOutputCoordinator.getAudioOutput()` 为自有输出返回 `DirectUsbAudioOutput`，覆盖播放、暂停、flush、seek、切歌、格式变化和服务重建。
- [ ] 路由监听区分主动接管与设备拔出；拔出、端点出错、权限撤销和描述符不匹配时失败关闭并撤销 `ACTIVE`。
- [ ] 来电等暂时失去音频焦点时释放接口，让系统可以使用 DAC；恢复时重新接管。
- [ ] 增加与已声明接口对应的清理记录，确保任何失败路径都会释放接口。
- [ ] 同格式曲目之间连续播放；格式变化需要切换 alternate setting 时的停顿如实列为限制。

### M7-5 音量、界面与链路页

- [ ] 按 D4 实现硬件音量和音量键；开始播放前降低硬件音量。
- [ ] 设置、Now 和链路页显示"Vesqen 直接输出到 DAC"及实际格式；链路页说明系统音频路由在此期间不经过该 DAC。
- [ ] 第一次使用时说明：接管期间其他应用和系统提示音会改由手机扬声器输出；需要 USB 授权；关闭麦克风访问时系统可能拒绝授权。
- [ ] 失败原因加入 `StrictUsbFailureDialog` 和链路页，四种语言使用正式书面用语。
- [ ] 诊断导出和设备报告遵循现有隐私过滤规则。

### M7-6 验收

- [ ] iQOO（Android 15）、一台 Android 16 手机和 Honor（Android 9）分别连接各参考 DAC，测试 44.1 kHz / 16 位、48 kHz / 24 位、96 kHz / 24 位，以及 DAC 支持时的 192 kHz / 24 位。
- [ ] 16 位音源送入只提供 24 位格式的 DAC（#65）。
- [ ] 插拔 50 次（PRD 10.2）、后台与锁屏、来电、进程被杀后的恢复，以及耗电与温度；8 小时连续播放按所有者安排执行。
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
| 硬件音量缺失或开始时音量过大 | D4 决定；开始播放前降低硬件音量。 |
| DAC 碎片化 | 以参考 DAC 为准，描述符不在支持范围内的设备失败关闭并说明原因。 |

## 不在 M7 范围内

DSD/DoP、多声道、重采样、EQ 等 DSP 处理，M6 的自研播放内核（M7 继续使用 Media3 解码），以及 Play 版的应用内购买。
