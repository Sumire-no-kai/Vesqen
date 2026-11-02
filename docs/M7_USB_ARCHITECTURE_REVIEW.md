# Vesqen 2.0 / M7 USB 架构审核

审核日期：2026-11-02（Australia/Sydney）。

**结论：总体分层可以保留，但当前版本不宜直接作为 M7-3 的实现基线。** 至少先修订 PCM 链路、动态能力探测、播放时钟及焦点/资源所有权契约。M7-1 的静态描述符读取可以推进；M7-2 应同时验证 Media3 集成，不能仅以 native 测试向量能播放作为架构成立的证据。

审核固定到远端 master 提交 [8bfe75f](https://github.com/Sumire-no-kai/Vesqen/commit/8bfe75f31514839bbdf4243b46460d66c7f2e27f)。主要对象：[架构设计](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_USB_ARCHITECTURE.md)、[实施清单](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_IMPLEMENTATION_PLAN.md)、PRD 的 M7 范围、#71 讨论，以及同一提交下的播放实现。本报告记录架构审核意见；架构文档和产品代码未在本次审核中修改。

优先级：P1 是实现前应解决的正确性问题；P2 是需要补齐的契约或产品行为。下文区分“源码/规范证实”与“设计缺口”；后者不等于已经在尚不存在的实现中复现了 bug。

## 1. P1：默认 AudioSink 已修改样本，“仅换 AudioOutput”不能直接保证严格输出

**证据：源码证实，并执行了上游函数的主机实验。**

架构第 8、19、89、94–105 行假定 DefaultAudioSink 可以保持中性，再在 AudioOutput 侧精确转换。然而 Media3 1.11.0 的 DefaultAudioSink：

- 固定 AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS = 20；
- setOutputBuffer() 无条件调用 maybeRampUpVolume()；
- PCM 输出开头按帧做线性淡入，再交给 AudioOutput.write()。

这不受 player.volume=1、速度=1 或关闭 skipSilence 的保护。PcmAudioUtil 在第一个采样帧乘以零；后面的适配器已经拿不到被抹去的原值。整数量化后的淡入样本仍然是整数，因此“乘以 2^N 后必须为整数”的检查也不能证明未改动。

引用：[DefaultAudioSink 调用点](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1244-L1249)、[淡入实现](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1915-L1931)、[PCM 运算](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/PcmAudioUtil.java#L40-L60)。

本地实验使用未修改的上游 PcmAudioUtil.java，只为 C 常量/注解/数值 clamp 提供最小桩，JDK 25 编译运行。48 kHz、双声道、20 ms：

- int16 固定输入 16384：首样本变为 0，1922 个样本中前 1920 个改变。
- float 固定输入 0.5：前 1920 个改变，其中 1664 个还会被拟议的 24-bit 整数格点检查拒绝。

**建议：** 先实现一个不接 DAC 的 Media3 → PCM 捕获测试，验证首帧、seek 后首帧、重新 prepare、同格式切歌和 EOS。修订“绝不自定义 AudioSink”的硬约束：优先评估能关闭淡入且保持精度的已验证上游入口；若 1.11.0 没有可用入口，比较一个固定版本的最小补丁与一个范围受限的 strict PCM Sink。仍保留 Media3 的提取、解码、队列和媒体会话，普通输出继续使用原 Sink。不需要因此启动 M6 全内核重写，也不能在输出端反向增益补救丢失的样本。

需要回答：**用什么具体 API 或最小改动关闭这段处理，并用哪个捕获测试证明？**

## 2. P1：纯描述符阶段拿不到全部 UAC2 能力；固定时钟不能强制 SET

**证据：规范与 Linux 参考实现证实。**

实施清单第 63–65 行要求在“不声明接口”的 M7-1 中从 getRawDescriptors() 取得采样率和 Feature Unit 音量范围；架构第 133、139 行先选择格式，再按统一流程设置并读回采样率。

UAC2 的 Clock Source 采样率范围来自控制请求 GET_RANGE，当前值来自 GET_CUR。Feature Unit 的音量范围也通过控制请求取得。静态描述符能告诉你拓扑和控制是否存在/可写，不能提供全部这些运行时数值。文档自己也说明接口控制请求要求已声明接口。对仅有固定频率的 UAC2 Clock Source，规范允许不支持 SET；一律执行并要求 SET 成功会误拒绝合法设备。

引用：[USB Audio 2.0 §5.2.5.1.1、§5.2.5.7.2](https://www.usb.org/sites/default/files/Audio2_with_Errata_and_ECN_through_Sep_14_2026.pdf)、[Linux GET_RANGE 实现](https://github.com/torvalds/linux/blob/v6.12/sound/usb/format.c#L541-L603)。

**建议：**

1. M7-1 只输出静态拓扑、接口、端点和控制能力，运行时范围明确标为 unknown。
2. 接管后经 UacControlClient 获取带边界检查的 CUR/RANGE、时钟选择与有效状态，再最终确定 StreamingPlan。
3. 根据控制的可读/可写性质选择操作：固定时钟读回匹配即可，可写时钟才设置；未知与不支持分开报告。
4. 建立 UAC1 与 UAC2 各自的请求流程，不把两者压成一个无条件 SET/GET 模板。

需要回答：**哪个阶段取得运行时能力？固定频率/只读时钟如何通过验收？**

## 3. P1：USB 总传输帧不能直接用作 Media3 播放进度

**证据：设计中的语义冲突；尚无实现可执行。**

架构第 81–86、173、200 行同时规定暂停/欠载/排空后持续发送静音，又规定 getPositionUs() 由“已完成 URB 帧数 + 单调时钟插值”计算。静音传输也占帧，却没有消费歌曲。若全部计入，暂停或起播预填充后进度就会领先；DefaultAudioSink 将这个值与写入帧数比较来判断是否还有待播放数据，可能提前结束或丢失尾部。仅把位置限制在已写入帧数也不够，它会提前到达这个上界。

此外，USB 主机传输完成不等于 DAC 已完成实际呈现，DAC 内部缓冲延迟没有在此被测量。

引用：[设计方法表](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_USB_ARCHITECTURE.md#L79-L89)、[Media3 pending-data 判断](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1934-L1938)。

**建议：** 独立记录媒体写入帧、媒体消费帧、总 USB 传输帧和补入静音帧。每个 AudioOutput 使用独立 epoch/位置基准；会话可跨输出存活，但计数不能串代。插值要有已提交媒体区间的上界，暂停后不得无限增长，EOS 排空只等待媒体数据。USB 交付时刻与估计的 DAC 呈现时刻在 Audio Proof 中分开。

至少测试：先传 5 秒静音再写媒体、暂停 10 秒后继续、欠载、EOS、seek 时 URB 尚在途、同格式复用和不同格式重建。

需要回答：**补入静音、暂停和新 AudioOutput 分别如何影响媒体时钟？**

## 4. P1：自动音频焦点、严格中性音量和归还 DAC 的行为尚未对齐

**证据：现有实现与 Media3 源码证实触发条件，最终故障属于设计风险。**

PlaybackService 开启 setAudioAttributes(..., true)。Media3 1.11.0 对暂时焦点丢失可以保持 playWhenReady=true，通过 playbackSuppressionReason 暂停；允许 duck 的变化还会经独立音量 multiplier 传给 renderer。现有 Coordinator 主要在 onPlayWhenReadyChanged 处理暂停，并且 player.volume==1 不能排除这个 multiplier。

因此，沿用现有监听不足以兑现“来电释放接口”；只接受中性 setVolume() 也没有回答 duck 时要暂停、失败还是忽略。更进一步，释放后 Android 会重新添加 USB 音频路由，现有 onAudioDevicesAdded() 又会 scheduleReconfigure()，必须防止焦点仍不在 Vesqen 时重新接管。

引用：[当前服务](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/app/src/main/java/io/github/sumirenokai/vesqen/playback/PlaybackService.kt#L258-L265)、[当前设备监听](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/app/src/main/java/io/github/sumirenokai/vesqen/playback/UsbOutputCoordinator.kt#L92-L110)、[AudioFocusManager](https://github.com/androidx/media/blob/1.11.0/libraries/common/src/main/java/androidx/media3/common/audio/AudioFocusManager.java#L355-L396)、[内部焦点与音量处理](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java#L1143-L1156)。

**建议：** 明确一个服务侧音频焦点所有者。严格输出的 duck 策略优先考虑暂停并按需归还 DAC，不缩放 PCM；如使用自管 AudioFocusRequest，应关闭重复的自动焦点管理。状态中保存用户播放意图、focusGranted、suspendedReason、releaseCause，路由增加仅更新设备事实，不能越过焦点条件触发接管。

需要回答：**临时焦点丢失且 playWhenReady 不变时，由谁释放？重新出现的路由为什么不会立即被抢回？**

## 5. P1：全速/高速常量不足以决定包调度；错误必须检查到每个 ISO 包

**证据：协议/内核证实；文档缺少对应规则。**

架构第 191–201 行的高速 125 µs、128 包约 16 ms 只对应特定服务间隔。高速端点还要使用 bInterval；例如 bInterval=4 时服务间隔为 1 ms，128 包约 128 ms，6 个 URB 对应约 768 ms。反馈里的速率单位也需要转换到“每个实际服务间隔的帧数”。不能从 UAC 版本或 bcdUSB 推定当前总线速度。

wMaxPacketSize 的有效载荷也要解析相应位字段，而非直接把整个原始字段当字节上限。对于暂不支持的高速多事务结构，可以明确拒绝，而不是猜测。

ISO 的总 URB 状态不能替代每包 status/actual_length。一次成功 reap 不代表所有音频包都成功。仅对“连续多次错误”计数，会漏掉间歇的丢包；反馈完全消失也可能永远不触发“反馈超范围”。

引用：[Linux interval 计算](https://github.com/torvalds/linux/blob/v6.12/drivers/usb/core/devio.c#L1898-L1906)、[逐包状态返回](https://github.com/torvalds/linux/blob/v6.12/drivers/usb/core/devio.c#L2040-L2070)、[内核错误模型](https://cdn.kernel.org/doc/html/latest/driver-api/usb/error-codes.html)。

**建议：** StreamingPlan 显式持有实际 speed、serviceInterval、每间隔最大载荷、反馈单位/更新周期/有效期。分别定义数据包失败、反馈超时、反馈异常、主动取消的处理。位精确声明须区分“路径按原值传输”和“当前流连续完整”；插入静音或丢包不能在诊断里被写成完整的逐样本一致流。已有签名组合记录不能替代本次流的错误证据。

需要回答：**bInterval>1、反馈中断、单包失败但 URB 总体完成时，各自怎样计数和停止？**

## 6. P2：跨线程 flush/destroy 的所有权和失败清理需写成可测试契约

**证据：设计缺口，不是已证实的内存错误。**

架构将 Media3 写入、usb-control 控制、native 消费分到三条线程，但 JNI 只列出 write/flush/destroy 等方法。SPSC 环形缓冲只解决一个生产者和一个消费者的正常访问，不自动解决第三方清空、销毁或重新配置时的竞争。

应明确：停止接收旧 epoch 数据 → 停止重新提交 → 取消并回收所有在途请求 → native 确认静止 → 重配/释放接口 → 关闭 fd。失败取消和正常 EOF 要区分。Linux v6.12 的 DISCARDURB 内部会等待 usb_kill_urb，但完成记录仍进入 reap 路径；不能将停止线程等同于完成队列、用户 URB 结构和缓冲区都已安全回收。

引用：[JNI 与释放规则](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_USB_ARCHITECTURE.md#L173-L175)、[JNI 接口](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_USB_ARCHITECTURE.md#L203-L205)、[Linux unlink/reap 实现](https://github.com/torvalds/linux/blob/v6.12/drivers/usb/core/devio.c#L2005-L2076)。

**建议：** 给会话和输出各自分配 epoch；控制命令采用有完成确认的协议；只有一个线程操作传输队列。设计 cleanup ledger，任何一步失败仍继续安全清理后续资源并保留根因。只有确认释放成功才清除接管标记，否则下次启动会失去恢复线索。不要让不设上限的控制请求或 join 阻塞 Media3 播放线程。

需要回答：**flush 返回时有哪些保证？destroy 与正在执行的 write 如何互斥？哪一步失败会保留恢复标记？**

## 7. P2：USB 授权完成不能依赖 AudioDeviceInfo 再次出现

**证据：Android 的 USB 授权和音频路由是不同事件来源；设计存在可达的漏通知场景。**

架构第 222、252 行让 UsbAttachActivity 无操作退出，只等待 onAudioDevicesAdded()。如果系统路由在用户授权前已经出现，授权完成不保证再触发一次路由增加；如果设备没有被 Android 音频系统支持，或者服务尚未创建，也不能把音频路由回调作为 USB Host 状态变化的唯一入口。

引用：[设计](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_USB_ARCHITECTURE.md#L217-L223)、[Android USB 发现与授权](https://developer.android.com/develop/connectivity/usb/host)。

**建议：** Activity 仍可不信任 Intent 的设备参数，但退出前向应用已有协调入口发出“重新枚举”信号，实际用 UsbManager.deviceList 和 hasPermission() 重新确认。服务启动、显式权限结果、attach/detach 也进入同一幂等 reconcile 流程；这不等于自动播放或启动新的后台播放。设备路由只作为观测证据。

需要回答：**授权发生在路由回调之后，或没有系统 USB 音频路由时，谁更新状态？**

## 8. P2：Feature Unit 存在不等于音量可用，记忆值也不是安全边界

**证据：规范能力模型证实；安全阈值和产品交互属于待定设计。**

架构按“有 Feature Unit / 没有 Feature Unit”分流，但 Feature Unit 可以只提供 mute 或其他控制，也可能只有逐声道音量，或音量只读。须先验证所选播放拓扑上确有可写 Volume Control，再决定使用硬件音量。必须检查 SET 成功并读回；写失败不可当成“没有音量控制”而直接播放满幅。

-30 dB 是相对增益，不是适用于所有 DAC、耳机和功放的统一安全声压保证。VID/PID 识别型号，不能证明是同一物理设备或仍连接同一副耳机。按型号记忆很高的音量，再自动恢复，不能依靠“首用降低过一次”兜底。

引用：[设计](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/docs/M7_USB_ARCHITECTURE.md#L209-L215)、[USB Audio 2.0 Feature Unit 与控制请求](https://www.usb.org/sites/default/files/Audio2_with_Errata_and_ECN_through_Sep_14_2026.pdf)。

**建议：** 保留 D4 的硬件音量方向，但明确每次接管的起始上限/确认行为、master 与逐声道控制、失败关闭及读回证据。按 VID/PID 保存值只能当偏好。归还前恢复旧音量需确认仍为同一接管会话且所有音频数据已停止。

需要回答：**有 Feature Unit 但没有可写 Volume Control 时属于哪条路径？读取/设置/恢复失败分别怎么办？**

## 9. P2：源精度、解码精度、USB 格式仍需独立判断；32 位路径目前不可达

**证据：现有代码与 Media3 1.11.0 源码证实。**

架构第 96–102 行把“源位深”直接映射为输出转换规则，并认为格点检查能发现元数据错误。实际上，24-bit 源已被解码器截成 16-bit 后仍能补零成合法 24-bit 值，也能通过格点校验。格点校验只能证明这个当前值可精确表示，无法还原或证明上游没有丢掉低位。

当前 Vesqen 明确向非 c2 的 OMX 解码器请求 int16；这是历史兼容性保护，不能为 M7 直接删除。另一方面，DefaultAudioSink 开启 float 时会将高精度整数转换成 float；关闭 float 则进入 int16 转换。因此表中的“32 位源只接受 int32”是合理拒绝规则，但当前默认 Sink 链路不能真正提供那条支持路径。

引用：[Vesqen OMX 规则](https://github.com/Sumire-no-kai/Vesqen/blob/8bfe75f31514839bbdf4243b46460d66c7f2e27f/app/src/main/java/io/github/sumirenokai/vesqen/playback/VesqenAudioRenderersFactory.kt#L119-L130)、[DefaultAudioSink 格式处理](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L748-L760)。

**建议：** 计划分别携带 SourceFormat、DecodedPcmFormat、SinkInputFormat、UsbWireFormat，绑定格式观察时刻和 epoch。先明确首版对 20-bit、32-bit、浮点 WAV、有损解码输出及位深未知的支持/拒绝范围；未经证明的高位深解码不要靠补零提升声明。若首版不需要 32-bit，有清晰原因地拒绝就足够，不必为此重写解码器。转换单元测试应含错误元数据、已截断的合法格点，以及真实 Decoder→Sink 输入组合。

需要回答：**严格输出承诺保持的是原文件的无损 PCM，还是当前解码器产生的 PCM？两者不同的时候界面怎样说明？**

## 可保留的设计与更优实现顺序

建议保留：Media3 的队列/提取/解码/媒体会话；Coordinator 作为唯一产品状态所有者；usb-engine 不依赖 Media3；普通播放按需不加载 native；静态解析放 Kotlin；固定版本 NDK；先参考硬件，再扩大支持矩阵；外部验证独立于 ACTIVE。

自写 usbdevfs 是已经确认的 D2，无需在本次审核中直接推翻。但“native 只需提交回收、工作有限”低估了取消/回收、每包错误、反馈、时钟和兼容性工作。保留 transport 的小接口即可，无需同时做 libusb 后端。若 G1 失败，只有定位到传输实现的问题，比较 libusb 才有价值；它不会自动解决 Media3 淡入、PCM 精度和音量策略。

“同一次 USB 连接不随曲目自动换路径”是合理的稳定性取舍。代价是设备只暴露少量官方格式时，自有输出的更多格式不会被利用。当前可保留并如实说明；以后若有需求，再考虑用户显式切换“本次连接使用自有输出”，无需现在增加复杂自动切换。

推荐顺序：

1. 先做 Media3 → 内存 PCM 捕获，解决第 1、3、9 项，并加入首帧/seek/EOS 回归。这不依赖购买 DAC。
2. M7-1 只验证静态拓扑；M7-2 加入接管后的动态探测、固定时钟、原生传输、暂停/恢复/释放。
3. 用一个参考设备完成最小的 Media3 → USB 垂直链路，将焦点、时钟和故障撤销纳入 G1。JBL 单格式测试不能独自证明 UAC2 异步反馈与多采样率切换。
4. 完成主机上的 packet scheduler、状态机和取消/回收故障注入，再拓展 UAC 版本及参考 DAC。
5. 保留原定的外部数字逐样本验证和发布门槛，不用“听起来正常”、采样率显示或向 USB 提交成功代替它。

## 验证范围

已完成：远端固定提交文档、相关应用源码、Media3 1.11.0 源码、USB-IF UAC 规范和 Linux v6.12 参考实现交叉核对；JDK 25 主机淡入函数实验通过，确认不满足样本恒等。

未执行：完整 Android 构建/单元测试、Media3 Android 集成捕获、NDK 传输测试、真机来电/热插拔/息屏验证、USB DAC 数字采集、远端 CI。M7 当前无实现；不能把设计审核当成设备验收，Linux 参考实现也不能证明目标 ROM 的行为。

主机实验使用第 1 项引用的 Media3 1.11.0 上游函数和最小依赖桩；输入、参数和观测结果已记录于该项。实验材料未随本报告提交，不能据此声称完整 Android/Media3 集成验证通过。

