# Vesqen 2.0 / M7 USB 架构二次审核

审核日期：2026-11-02。审核基线为远端 master 的 [a8e38ba7a120e3745d9eca401d46df603bbcf812](https://github.com/Sumire-no-kai/Vesqen/commit/a8e38ba7a120e3745d9eca401d46df603bbcf812)，对照[首轮审核报告](M7_USB_ARCHITECTURE_REVIEW.md)提交 `1f6e1debfc5963ae074b519626008cb5c07bf26e`。

**结论：修订方向正确，9 项意见都有实质回应，但尚不能全部关闭。6 项在设计层解决，2 项部分解决，1 项仍待方案验证。可以继续 M7-0 的验证和 M7-1 的静态解析；在确定 M7-2 的输出适配契约、进入完整引擎实现前，应补齐下面 3 个 P1 和 2 个 P2。**

“设计层解决”只表示原先的矛盾已在文档中修正，不表示代码实现、Android 集成或设备验收通过。当前差异仅涉及 5 个文档，没有新增 M7 产品实现。本次通过 gh 读取固定提交及 #210，并核对 Media3 1.11.0 的 AudioOutput、DefaultAudioSink 和当前播放代码；审核期间没有修改产品代码或运行构建。

## 一、首轮 9 项逐项结论

| 原编号 | 原问题 | 二次结论 | 依据及剩余工作 |
| --- | --- | --- | --- |
| 1 | DefaultAudioSink 的 20 ms 淡入改变样本 | **未关闭：方案待验证** | 已承认不能只替换 AudioOutput，并增加 A/B 候选和捕获测试，方向正确。但优先方案 A 尚有输出重建、前导计数及 EOS 契约缺口，见 R2-01、R2-02。不能把“计划测试”视为已选定的可靠解法。 |
| 2 | 静态描述符拿不到 UAC2 运行时范围；固定时钟不应强制 SET | **设计层解决** | 明确 M7-1 只读静态拓扑、unknown；claim 后动态探测；区分 UAC1/UAC2 和只读时钟。见架构 198–215 行。仍需控制请求及真机验证。 |
| 3 | USB 总帧不能充当媒体进度 | **部分解决** | 已分离媒体帧、静音、USB 总帧及 epoch。但 Sink 注入的前导与 native 生成的静音未明确区别，失败媒体包如何推进时钟及结束 EOS 也未闭合。见 R2-02、R2-04。 |
| 4 | 自动焦点、duck、释放后重新接管相互冲突 | **设计层解决，另有事实错误** | 已监听 suppression、保持单一焦点所有者、阻止焦点丢失期间因路由恢复抢回 DAC。D11 明确选择 duck 时继续播放并忽略系数；这是产品取舍，不因与首轮建议不同而判为未解决。现有官方路径的描述写反，见 R2-05；来电、助手和通知仍须实测。 |
| 5 | 缺少实际速度、bInterval、逐包状态和反馈超时 | **设计层解决** | StreamingPlan、拒绝高速多事务、逐包检查及反馈超时均已补入。实际调度、反馈单位和阈值仍需测试；失败包与媒体时钟的交叉问题见 R2-04。 |
| 6 | flush / destroy / URB 回收与资源所有权 | **部分解决** | epoch、ACK、取消后继续回收、清理记录和恢复标记均有改进。但 DefaultAudioSink 实际走 release，且销毁锁和失败清理的约束相互冲突，见 R2-01、R2-03。 |
| 7 | USB 授权完成不能依赖 AudioDeviceInfo 路由事件 | **设计层解决** | Activity、授权结果、USB 广播、服务启动等统一进入 reconcile，重新读取 UsbManager 事实；没有运行中服务时不启动播放。见架构 171–181、380–386 行。 |
| 8 | Feature Unit 不等于可写音量；VID/PID 不能证明设备身份 | **设计层解决** | 已区分可写、不可写、控制失败；设置前置并读回；重插使用保守起始值；VID/PID 仅作偏好。见架构 361–378 行。硬件音量及 ROM 行为仍待验证。 |
| 9 | 原文件精度与解码输出混淆；整数格点测试不是完整证明 | **设计层解决** | 明确承诺原文件无损 PCM、低精度解码拒绝、首版只支持 16/24 位，以及格点校验仅为必要条件。见架构 124–153 行。真实解码器组合仍须捕获测试。 |

## 二、仍需修正的设计问题

### R2-01 / P1：取消旧输出的协议挂在了 Media3 不会调用的入口上

**关联首轮：1、6。证据性质：上游调用链已核对；M7 实现尚不存在。**

新版规定 `DirectUsbAudioOutput.flush()` 发起 epoch 切换及取消，而 `release()` “只解除与会话的绑定，不释放 USB 接口”。问题在于 Media3 1.11.0 的 `DefaultAudioSink.flush()` 实际调用 `audioOutput.release()`，不调用 `audioOutput.flush()`。seek、reset 或需要重建输出时，因此不能假设会进入文档中的 flush 协议。

如果照表中职责实现，旧 ring / 在途 URB 仍可能继续输出，新的输出却已经绑定同一 USB 会话；只让旧 completion 不计入新 epoch，不能阻止旧样本继续送出，也不能保证更换格式之前数据面已停止。

此外，方案 A 仅靠 `configure()` 预判格式变化不够：DefaultAudioSink 的 stalled-output 恢复会在 `handleBuffer()` 内直接 flush，同格式也会重建和重新淡入。如果保留这条恢复路径，包装层不能漏掉这次前导；若严格模式明确不使用该恢复路径，应写明 `isStalled()` 与失败关闭的行为。

需要补充：

1. 区分“结束一个 AudioOutput 的数据世代”与“释放 USB 接口”。保留长寿命 DirectUsbSession 是合理的，但旧 output 的 `release()` 必须失效旧写入口并启动取消/回收或等价的输出退休协议。
2. 新 output 的 write、格式重配和首个媒体包，都必须受旧 epoch 回收确认约束；不能依赖 DefaultAudioSink 自动等待会话静止。
3. 明确正常 EOS 排空与 seek 丢弃旧缓冲的区别，以及 `release()` 幂等性和 `Listener.onReleased()` 恰好一次的完成语义。上游通过该回调减少 pendingReleaseCount，遗漏会干扰异常重试。
4. 如采用 A，以实际输出世代识别需要前导的时机，覆盖内部 flush / 同格式重建；无法可靠覆盖时选 B。

**关闭条件：** 使用真实 DefaultAudioSink + 假传输，在 ring 和 URB 都有数据时 seek / reset / 重建，证明新世代获准写入前旧世代已停止提交并完成回收；旧数据不混入新世代；同格式重建后首个真实样本仍完整。取消前已经送出的数据不能声称可撤回，须定义切换生效的确认点。

依据：[设计中的方法职责](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L109-L120)、[新旧输出与会话](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L270-L273)、[上游 flush 调用 release](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1566-L1584)、[同格式也可能内部 flush](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1084-L1087)、[release 完成回调](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L2024-L2028)。

### R2-02 / P1：静音前导不能仅靠“播放进度减 20 ms”闭合 EOS 契约

**关联首轮：1、3。证据性质：设计歧义与上游帧计数的静态反例，并非已复现的设备故障。**

方案 A 把前导通过 DefaultAudioSink.handleBuffer 写入。DefaultAudioSink 将其计入 written frames，并在内部以 `writtenFrames > AudioOutput.getPositionUs()` 对应帧数判断 pending data。新版另一节则规定 getPositionUs 只统计媒体、静音不计入。两种规则没有说明前导究竟属于哪一种。

以 48 kHz 为例：100 ms 歌曲为 4,800 帧，20 ms 前导为 960 帧。Sink 总共写入 5,760 帧。如果 DirectUsbAudioOutput 把前导也排除为“静音”，其进度最高只有 4,800 帧，Sink 会一直认为还有数据，EOS 和不同格式切歌无法正常排空。外层 getCurrentPositionUs 减 20 ms 改变不了 Sink 内部这个比较。

反过来，如果前导计入 DirectUsbAudioOutput 的位置，EOS 可以闭合，但文档必须明确它不是“歌曲媒体进度”，并规定外层偏移、seek 基准、前导长度和重复 flush 的处理。

建议为 A 明确三个概念，无须为此建立庞大的新抽象：

- **Sink 接受的帧时间轴**：含显式前导，用于满足 DefaultAudioSink 的 position / pending / EOS 契约。
- **歌曲时间轴**：按本次输出实际前导帧数映射，不能仅固定减一个常量，也不能出现负进度。
- **native 自行补出的 idle / pause / underrun 静音**：不属于 Sink 接受的帧，不推进前两个时间轴。

不要通过样本值是否为零来识别前导，歌曲自身的静音同样是媒体数据。前导元数据、PTS、encoder delay/padding 和同格式 gapless 的处理都应明确。

**关闭条件：** 增加短曲（短于 20 ms）、EOS、不同格式连续切歌、seek 后偏移、前导 partial write 及 encoder delay 用例，同时断言字节内容、最终位置、isEnded 与 hasPendingData；只检查捕获 PCM 的首尾字节不足以证明时间轴正确。

依据：[方案 A 的前导及扣除规则](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L75-L97)、[媒体时钟定义](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L314-L329)、[上游 pending 比较](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1934-L1938)、[上游 EOS 条件](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1333-L1362)。

### R2-03 / P1：销毁时的锁和“每步失败仍继续清理”缺少安全边界

**关联首轮：6。证据性质：文档内部契约冲突，尚未实现。**

架构要求 write 在小锁内执行、destroy 取得同一把锁后销毁，又要求 destroy 等 native 停止并回收在途 URB。即使 destroy 运行在 usb-control，若持锁进行回收等待，播放线程的 write 仍会阻塞在锁上。“销毁不在播放线程”不等于“播放线程不会被销毁阻塞”。readStats / getPositionUs 等其他 JNI 调用也需要相同的句柄生命周期保护。

清理顺序还同时规定“确认全部在途 URB 已回收”和“某一步失败继续执行后续步骤”。如果失败的恰好是停止/回收确认，继续销毁 native 资源或关闭借用 fd，就可能越过尚未满足的所有权条件。保留接管标记只能帮助下一次恢复，不能证明当前资源已安全静止。

建议：

- 锁内只完成 closing 标记、句柄摘除及与短 JNI 调用的互斥；所有后续调用看到关闭状态立即退出。锁外再执行取消、回收和销毁等待。
- 对 write、readStats、setRunning 等所有句柄使用定义一致的生命周期，不只保护 write。
- 区分可继续的清理失败（例如设备音量恢复失败）和不可越过的资源安全屏障（native 静止未确认）。超时后停止新输出、保留必要资源或进入已验证的终止路径，不能将超时等同于已回收。
- 写清每个超时分支中谁继续持有 handle、fd、URB 缓冲和接口，何时才允许下一次接管。

**关闭条件：** 注入延迟回收、取消失败、拔出和 destroy 与 write/readStats 竞争；验证播放线程不等待回收、无销毁后访问、无旧传输存活时重配置；清理标记与实际所有权一致。

依据：[清理继续执行规则](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L270-L274)、[线程、锁与销毁契约](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L346-L359)、[目前要求每步失败继续清理的测试](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L428)。

### R2-04 / P2：允许单包失败后继续，但未定义这些媒体帧怎样结束 EOS

**关联首轮：3、5。证据性质：新增容错规则与时钟定义的交叉缺口。**

新版允许少量 ISO 包失败后继续播放，超过窗口阈值才失败关闭；同时把 mediaFramesDelivered 定义为“所在 URB 已完成的媒体帧”，EOS 等待媒体帧交付。这还没有区分 URB 完成、包成功、包失败但处理已结束。

例如歌曲最后一个 URB 有一个媒体包失败且未达到阈值：若只统计成功交付，written 永远大于 delivered，EOS 可能永远等不到；若把失败包也当成功交付，Audio Proof 的交付数量和含义又不真实。不能用 URB 总体完成抹掉逐包差异。

可选的最小方案：首版任何媒体 ISO 包失败即失败关闭，简化状态。如果保留容错，则单独定义媒体播放游标/已结束处理的帧和成功交付帧；失败帧推进应有的逻辑游标但计入丢失，EOS 等待全部待处理媒体结束，连续性证据保持失败。旧 epoch 的主动取消不得推进新 epoch。

**关闭条件：** 对末包失败、混合成功/失败 URB、短包和部分 actual_length，明确每个计数的增量、最终位置、EOS 和 ACTIVE / streamContinuity 的结果；不允许通过“等到超时”掩盖缺少终结状态。

依据：[计数和 EOS](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L318-L329)、[允许阈值以内单包失败](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L331-L344)。

### R2-05 / P2：官方路径现有 duck 行为被写反，会误导后续修改

**关联首轮：4 的修订说明。证据性质：当前仓库源码证实。**

架构 196 行说 StrictGatedAudioOutput.setVolume 会把 duck 系数传给 AudioTrack，而 hasNeutralProcessing 看不到。当前代码实际在 volume != 1 时同步关闭写门、标记 invalidated、把底层设为 0，并通过 onNonNeutralVolume 调用 failClosed(PROCESSING_NOT_NEUTRAL)。不是把例如 0.2 的系数透传后继续严格播放。

应更正基线描述，避免后续为修复一个不存在的透传行为而改坏现有保护。M7 自有路径按 D11 忽略 duck 系数是另一个明确选择，需要单独验证。系统混音器是否自动 duck、ROM 是否投递通知等问题，也不能用这段 Java/Kotlin 回调直接推定。

**关闭条件：** 文档与 setVolume 和回调调用链一致；将官方路径的非中性音量失败关闭测试，与 direct 路径的 D11 测试分开。

依据：[错误描述](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/docs/M7_USB_ARCHITECTURE.md#L196)、[实际 setVolume](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/app/src/main/java/io/github/sumirenokai/vesqen/playback/UsbOutputCoordinator.kt#L1343-L1359)、[实际 failClosed 回调](https://github.com/Sumire-no-kai/Vesqen/blob/a8e38ba7a120e3745d9eca401d46df603bbcf812/app/src/main/java/io/github/sumirenokai/vesqen/playback/UsbOutputCoordinator.kt#L507-L520)。

## 三、建议保留的架构与收敛顺序

保留 Media3 的提取、解码、队列与媒体会话，保留 Coordinator 单一状态所有者以及独立 USB 会话，不需要因此启动完整播放内核重写。静态探测与运行时探测分阶段、G1 扩大到 Media3 到 USB 的完整链路、G2 补参考 DAC，均是有效改进。#210 已明确 G2；不应误报为没有验收关卡。

优先把 R2-01、R2-03 的生命周期契约写清，再用包含时间轴与 EOS 的捕获测试决定 A/B。A 不应仅因为包装层看起来短而被视为复杂度最低：如果必须持续镜像 DefaultAudioSink 的内部重建、裁剪和时间轴逻辑，受限的严格 PCM Sink（B）可能更容易维护。这是当前证据下的工程建议，不是未经测试便宣布 A 必然不可行；仍遵守 D9 的先验证再选择，以及不修改 Media3 源码的决定。

R2-04 可以用首版单包失败即停止来缩小容错状态空间，或明确引入已处理/成功交付两个不同计数。R2-05 是直接更正文档事实，不需要修改官方路径代码。

## 四、验证记录与边界

- **已完成**：gh 获取固定提交、比较首轮报告之后的 5 个文档变化；逐项核对 9 条回应；读取 #210 的 G1/G2 约束；核对当前 UsbOutputCoordinator / PlaybackService 和 Media3 1.11.0 的相关调用链。
- **上游身份核对**：重新通过 gh 取得 DefaultAudioSink.java，Git blob 为 `7cf699b3648957f32c0459a415d415ab91f75aab`，解码字节计算的 blob 与 API 一致。早先文本缓存仅多一个末尾空行，相关代码和行号一致。
- **静态反例**：R2-02 的帧数关系由上游 pending 判断推导；R2-04 的失败末包是契约反例。这些不是已运行的 Android 集成测试。
- **未执行**：Android 单元测试、构建、lint、Media3 捕获测试、NDK 故障注入、真机焦点/热插拔/锁屏、USB DAC 数字采集、远端 CI。本次只审核文档与源码，没有实现变更，不能宣称这些验证通过。
- **发布范围**：仅新增本二次审核文档，不修改产品代码或架构设计原文；保留已有工作区修改，不切换本地分支。

## 五、处理结果

2026-11-02 由架构作者补充，以上审核内容未改动。5 条新意见已对照 Media3 1.11.0 和现有代码核实，均成立，修订见[架构设计](M7_USB_ARCHITECTURE.md)和[开发清单](M7_IMPLEMENTATION_PLAN.md)。各条的关闭条件中涉及 M7 实现的部分，尚无代码可以验证。

| 项 | 结论 | 处理 | 关闭条件的状态 |
| --- | --- | --- | --- |
| R2-01 | 成立 | 输出退休挂在 `release()` 上，新输出的写入等待确认，切换生效点为确认点；`isStalled()` 始终为 `false`；`flush()` 不承载协议。见"输出退休与销毁"。 | 列入 `DirectUsbAudioOutput` 的测试，M7-3 实现。卡住路径已由捕获测试实测：方案 A 重建后真实样本被淡入，方案 B 不受影响。 |
| R2-02 | 成立 | 捕获测试增加短于 20 ms 的曲目、部分写入、连续格式切换、seek 后的进度和流结束的时间轴，两个方案都已通过。encoder delay 实测发现方案 A 无法保持裁剪，两个方案都改为拒绝。建议采用方案 B，它只有一条时间轴，这一条不再适用；如采用 A，须按本节区分三条时间轴。 | 字节、位置和 `hasPendingData` 的断言已在捕获测试中。`isEnded` 由 `STATE_ENDED` 保证。 |
| R2-03 | 成立 | 小锁只保护标记、句柄摘除和短 JNI 调用，等待都在锁外。区分可继续的清理失败和 native 静止这一安全屏障，并写明确认超时时各资源的归属。见"输出退休与销毁"。 | 列入 native core 和 `DirectUsbSession` 的故障注入测试，M7-3 实现。 |
| R2-04 | 成立 | 保留容错，增加 `mediaFramesProcessed` 和 `mediaFramesLost` 两个计数，进度和流结束按已处理帧计算，丢失的帧记为连续性中断。没有采用首个失败包即停止的方案：一个偶发的坏包就停止音乐过于影响使用；阈值由 M7-2 按实测的错误频率确定，如果错误罕见，再考虑收紧。 | 列入 native core 的测试（末包失败、混合 URB、短包），M7-3 实现。 |
| R2-05 | 成立，属于我的事实错误 | 已更正架构设计，官方路径的代码不改动。 | 文档已更正。两条路径的测试分开，M7-4 实现。 |

审查第三部分的建议已采纳：先写清生命周期契约，再用包含时间轴与流结束的捕获测试比较 A 和 B。比较结果见架构设计的"样本完整性"，建议采用 B，待所有者确认（D9）。

