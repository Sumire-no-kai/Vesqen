# beta.2 第一批后台修复交接

## #66 设备测试隔离

仪器测试统一使用 `deviceTest` 构建，应用包名为 `io.github.sumirenokai.vesqen.devicetest`，测试包为其 `.test` 后缀。Android UID 和私有存储与真实应用隔离，进程崩溃也不会覆盖真实偏好、队列、曲库或诊断记录。Debug、Profile、Release 包名和版本号不变。

检查了 StrictUsbSpeakerDeviceTest、SpeakerPlaybackDeviceTest、SpeakerChainLifecycleDeviceTest、RealLosslessDeviceTest；它们都使用同一个宿主校验入口。真实音源测试的扫描与历史也写入隔离曲库。系统音量、音频路由和外部夹具仍属设备共享资源，不能并行占用手机。构建与执行命令见 M1_DEVICE_ACCEPTANCE。

## #62 第 1–3 项

`UsbOutputStatus.failure` 和 `decisionCode` 保留停止输出的原始原因；`mixerCleanup` 单独提供待清理数量与异常类型集合。没有异常消息、堆栈、设备地址或序列号。字段通过 MediaSession Bundle 传递，旧 Bundle 缺少字段时使用空清理状态。界面/设备报告可直接消费此类型，本批未增加 Chain 指标或文案。

Release APK 的 assemble/package 与 bundle 都依赖 `checkPrivacyPolicyFinal`。第 4 项界面开关未处理。

## #34 非界面部分

触发路径：`PlaybackService.onCreate → UsbOutputCoordinator.attachPlayer` 会判定持久化的严格模式；`PlaybackController.synchronizeLibrary → startQueue(playWhenReady=false)` 恢复队列时，之前无条件调用 prepare，媒体项变化又触发严格判定，并可能创建 AudioTrack。

现在暂停恢复不 prepare；严格模式无播放意图时仅判定能力，不重建 AudioTrack。不可用/失败状态仍然保留，PCM 关闭规则不变。

`UsbOutputStatus.failureOrigin` 提供 SERVICE_START、QUEUE_RESTORE、USER_PLAYBACK、USER_MODE_CHANGE、TRACK_TRANSITION、ROUTE_CHANGE、PROCESSING_CHANGE、SERVICE_STOP。旧 Bundle 兼容为 null。界面需根据来源决定模态提示，本批没有修改对话框，所以整项 #34 不能关闭。

**adb 路由现象：本次没有复现。** iQOO 通过有线 adb 连电脑时，系统 MediaRouter 和隔离应用均报告手机扬声器，AudioManager 的 USB 输出数为 0。当前代码从 AudioManager 的音频 sink 类型选择 USB，不读取 adb/USB 连接标记，也没有把 USB 连接直接升级为音频路由。SDK 中对应的 MediaRoute2Info 与 AudioDeviceInfo 类型值一致。没有证据支持添加设备特例过滤；历史报告仍需在现象再次出现时保存同一时刻的 MediaRouter、AudioManager 和 USB inventory 来归因。

## 2026-10-03 验证记录

- 本地 JDK 25：254 项 JVM 测试，0 失败/错误/跳过；Debug lint、Debug、Release、deviceTest 与仪器 APK 构建通过。
- Release 负向门禁：仅用临时 Gradle init 脚本替换任务输入为带 Draft 的文件，`assembleRelease` 在 `checkPrivacyPolicyFinal` 失败；仓库隐私政策未修改。
- iQOO V2171A / Android 15 / PD2171_A_15.3.19.0.W10：新启动来源测试 1 项、会话字段测试 2 项、严格 USB 扬声器测试 1 项（8 轮模式切换）、普通播放短测 1 项通过。
- Honor STF-AL00 / Android 9 / STF-AL00 9.1.0.225(C00E125R1P9)：新启动来源测试 1 项、会话字段测试 2 项、普通播放短测 1 项通过。首轮后台测试被 PowerGenie 杀死，保留失败日志；使用已有前台宿主机制后通过。
- 两机恢复来源均为 QUEUE_RESTORE，主动播放失败来源均为 USER_PLAYBACK；iQOO 失败为 NO_USB_AUDIO_DEVICE，Honor 为 UNSUPPORTED_ANDROID_VERSION。没有静默降级播放。
- 测试版本仍为 1.0.0-beta.1 / 10（仅本地代码候选，非新发布）；宿主 APK SHA-256：`e617b5ff49ccfd8b5fcaedf106ff75f4a2838d281872114e3f94fdb5166cc4aa`；仪器 APK：`ea2905c04fa43eb88943917d6c6b371d80b1c25c138f7922dff16bea8241240e`。
- 未执行 75 分钟 soak、未重新布置 RealLossless 外部音源、未验证真实 DAC。不能据此关闭 M1/M2/M3 整体设备门禁。原始路由、安装与测试日志留在忽略的 `build/qa/beta2/`，不提交含设备/曲目信息的原始转储。
- 对话框行为留给界面接入；本地测试结果不等于远程 CI 或发布验收。
