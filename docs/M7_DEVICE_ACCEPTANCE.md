# M7 设备验收

本文件记录 [M7 开发清单](M7_IMPLEMENTATION_PLAN.md)中 M7-1、M7-2 和 M7-4 至 M7-6 的真机结果，以及关卡 G1 和 G2 的证据，记录方式沿用 [M4 设备验收](M4_DEVICE_ACCEPTANCE.md)。设计见 [M7 架构设计](M7_USB_ARCHITECTURE.md)。

单元测试、捕获测试、编译结果、DAC 屏幕显示的采样率、听感和描述符声明的能力，都不能作为本文件的通过依据。`BIT-PERFECT VERIFIED` 只来自外部逐样本比对的签名记录，方法见 [M4 设备验收](M4_DEVICE_ACCEPTANCE.md)"记录准备与签名"。

M4 矩阵中缺少设备的严格 USB 组合（#53）和暂缓的 8 小时严格播放，由本文件的 M7-6 结果关闭。

## 状态

| 状态 | 含义 |
| --- | --- |
| `PASS` | 该行列出的组合和条件已实测通过。其他手机、DAC、ROM 或格式的结果不能由此推定。 |
| `FAILED` | 已实测，结果不符合要求。 |
| `NOT_TESTED` | 具备条件，尚未测试。 |
| `MISSING_DEVICE` | 缺少所需的手机、DAC 或采集设备。 |
| `DEFERRED` | 所有者明确决定暂缓。 |

失败记录不删除，也不改写为通过。重新测试时追加一条新记录。

## 每条记录的内容

- 应用版本名和 versionCode、提交、已安装 base APK 的 SHA-256。调试构建须注明，不作为发布候选的结果。
- 手机厂商和型号、Android 版本和 API、ROM fingerprint 的 SHA-256。
- DAC 的名称、VID/PID、USB 描述符版本、UAC 版本、接入方式（例如转接线和供电）。
- 原始描述符的 SHA-256。序列号和 usbfs 路径不写入记录，描述符样本进入仓库前须确认不含序列号。
- 源格式、解码输出格式、实际使用的 alternate setting、采样率、subslot 和位分辨率。
- 测试文件或测试向量的 SHA-256。
- 时间、操作步骤、通过、失败和跳过的数量。
- 欠载次数、传输错误次数和 `mediaFramesLost`。
- 用户数据是否保留，以及仍未解决的问题。

原始日志和描述符转储保存在私有 QA 目录，本文件只记录摘要和哈希。任何一项身份信息变化后，都须重新判定，不继承旧结果。

## 操作要求

- **音量：** 自有输出的第一个样本之前，把 DAC 和后级设备的音量调到最低。D4 的硬件音量在 M7-5 才完成，此前的测试向量可能以满幅输出。
- **adb：** 手机的 USB 口接 DAC 时，改用无线 adb。
- **数据：** 覆盖安装，不卸载，不清除应用数据，不修改手机上的音乐文件。
- **恢复：** 每次测试结束后确认接口已释放、系统可以重新使用该设备；如果需要重新插拔，须写入记录。

## 当前矩阵（2026-11-03）

M7 的产品代码尚未开始。下列只有 M7-1 的 JBL Flip 7 一行有实测结果。

### M7-1 描述符

| 组合 | 状态 | 关闭条件 |
| --- | --- | --- |
| iQOO 9 / Android 15 × JBL Flip 7 | `PASS` | UAC1，只有 48 kHz，16 位和 24 位，自适应同步，没有反馈端点和麦克风，Feature Unit 有主音量和静音。先实现 UAC1（D3）。见执行记录。 |
| 每款参考 DAC | `MISSING_DEVICE` | 同上。D1 待定。 |

### M7-2 技术验证（G1）

组合为 iQOO 9 / Android 15 × JBL Flip 7，经真实的 `ExoPlayer`、调试用的 `AudioOutputProvider`、`StrictPcmAudioSink` 和 `DirectUsbAudioOutput` 输出。

| 项目 | 状态 | 关闭条件 |
| --- | --- | --- |
| 测试向量写入 | `NOT_TESTED` | 声明接口、配置时钟、选择 alternate setting、播放、释放接口，全程没有错误。 |
| 连续播放 60 秒 | `NOT_TESTED` | 没有欠载和传输错误。 |
| 连续播放 30 分钟（G1） | `NOT_TESTED` | 没有欠载。 |
| 息屏播放 30 分钟 | `NOT_TESTED` | 没有欠载，进程没有被冻结。 |
| 起播、暂停、恢复、seek、切歌、流结束（G1） | `NOT_TESTED` | 进度正确，每项操作后没有旧数据混入。 |
| 暂时失去焦点（G1） | `NOT_TESTED` | 释放 DAC，恢复后重新接管；路由变化不会提前抢回。 |
| duck | `NOT_TESTED` | 样本不变，没有引发暂停。 |
| 接管时的 `BECOMING_NOISY` | `NOT_TESTED` | 关闭相应处理后，接管不会导致暂停。 |
| 播放中拔出 | `NOT_TESTED` | 失败关闭并撤销状态，没有崩溃和残留。 |
| 播放中结束进程 | `NOT_TESTED` | 记录恢复方法；无法自动恢复时，说明中写明重新插拔。 |
| 释放后系统恢复使用该设备（G1） | `NOT_TESTED` | 系统路由恢复，其他应用可以经该设备播放。 |
| USB 授权（D8） | `NOT_TESTED` | Activity 禁用时插入不出现询问；启用后授权框提供"始终使用"，选择后下次插入自动授权。JBL Flip 7 没有麦克风，可以验证这一项。 |
| 真实 ALAC 文件的 encoder delay | `NOT_TESTED` | 记录实际取值。不为 0 时这些文件被拒绝并说明原因，由所有者决定是否实现裁剪。 |
| 资源消耗 | `NOT_TESTED` | 记录欠载次数、CPU 占用、唤醒次数、耗电和 native 库增加的体积（D5）。 |
| 同项结果 × 每款参考 DAC | `MISSING_DEVICE` | 参考 DAC 到位后逐款重复。JBL Flip 7 只有 UAC1、48 kHz 和自适应同步，不能证明 UAC2、异步反馈和多采样率切换，这些留给 G2。 |

### M7-4 官方路径修复

| 组合 | 状态 | 关闭条件 |
| --- | --- | --- |
| 开放官方通道的手机 × DAC | `MISSING_DEVICE` | 现有测试机都没有开放官方通道。须以 `getSupportedMixerAttributes()` 核对，16 位和 24 位源都能以整数格式达到 `ACTIVE`；duck 系数是否到达官方路径单独记录。 |

### M7-5 音量和授权

| 项目 | 状态 | 关闭条件 |
| --- | --- | --- |
| 音量键（vivo ROM） | `NOT_TESTED` | 音量键经远程音量接口到达 Vesqen，并改变硬件音量。 |
| 起始音量（D4） | `NOT_TESTED` | 第一个样本之前设置并读回；重新插入后不高于约 -30 dB 和记忆值中较低的一个。JBL Flip 7 的描述符声明了主音量，是否可写要到 M7-2 发送控制请求才能确认。 |

### M7-6 验收（G2）

| 组合或项目 | 状态 | 关闭条件 |
| --- | --- | --- |
| iQOO 9 / Android 15 × 参考 DAC（G2） | `MISSING_DEVICE` | 44.1 kHz / 16 位、48 kHz / 24 位、96 kHz / 24 位，以及 DAC 支持时的 192 kHz / 24 位，实际显示 `BIT-PERFECT ACTIVE`。这是 2.0 开始收费的条件。 |
| Android 16 手机 × 参考 DAC | `MISSING_DEVICE` | 同上格式。手机已有，缺参考 DAC（D1 待定）。 |
| Honor STF-AL00 / Android 9 × 参考 DAC | `MISSING_DEVICE` | 同上格式。24 位音源被拒绝并说明原因（D10）。 |
| 16 位音源送入只提供 24 位格式的 DAC（#65） | `MISSING_DEVICE` | 补零后高位与源样本一致，低 8 位为 0。JBL Flip 7 提供 16 位格式，不能用于这项测试。 |
| 插拔 50 次 | `NOT_TESTED` | 没有崩溃和错误的 `ACTIVE`，每次都能恢复。 |
| 后台与锁屏、来电、进程被杀后的恢复 | `NOT_TESTED` | 状态与实际一致，失败时停止播放，不切换回系统输出。 |
| 耗电与温度 | `NOT_TESTED` | 记录原始数据。 |
| 8 小时连续播放 | `DEFERRED` | 按所有者安排执行。 |
| 失败原因逐项触发 | `NOT_TESTED` | 显示的环节与实际一致。无法用真机触发的项目用测试覆盖，并注明。 |
| 外部逐样本比对（#49） | `MISSING_DEVICE` | 至少一条组合有签名记录，候选 APK 实际显示 `BIT-PERFECT VERIFIED`。D1 待定。 |

## 关闭条件

- **G1：** 上文标有 G1 的各行通过。结论同时记入 M7 开发清单；结论不支持继续时，由所有者重新评估范围。
- **G2：** iQOO 接参考 DAC 实测达到 `BIT-PERFECT ACTIVE`。
- **M7：** M7-6 各行关闭，或由所有者明确暂缓并写入发布说明。合并和发布是独立的状态。

## 执行记录

按时间追加，不回写或删除早期结果。

### 2026-11-03 · M7-1 JBL Flip 7 描述符

- **构建：** Vesqen Test 1.1.1 / versionCode 16（调试构建，不是发布候选），来自 `feature/usb-descriptor-reader`，基于 `38ea94f`。手机上 base APK 的 SHA-256 与本地一致：`bfbf7b2156c945d9e73a3502964b9dd51e0422473f2302974db7a20ce683e5c4`。
- **手机：** vivo V2171A（iQOO 9），Android 15 / API 35，ROM fingerprint 的 SHA-256 为 `686b0eea4b6d4eb3edc2f73e383c04b3178894e375ae97f8160e0c82fbe5d19e`。USB 口接 JBL，adb 走 Wi-Fi。
- **设备：** JBL Flip 7，VID `0ecb`，PID `2107`，USB 描述符版本 2.00，UAC1。由所有者用线缆接入，线缆型号未记录。
- **原始描述符：** 203 字节，SHA-256 为 `4949c8833528413b01221e601b8e0f110b320f1d797b5816547dfeddf682345d`。其中只有字符串的索引，没有序列号内容。样本位于 `usb-engine/src/test/resources/descriptors/jbl-flip-7-0ecb-2107.hex`，原始输出保存在私有 QA 目录。
- **步骤：** `UsbDescriptorDumpActivity` 请求 USB 权限，所有者接入设备后由 adb 点按确定，打开设备并读取 `getRawDescriptors()`，随后关闭。没有声明接口，也没有传输音频。解析结果已对照十六进制逐项核对。

| 项目 | 结果 |
| --- | --- |
| 接口 | 接口 0 为 HID，接口 1 为 AudioControl，接口 2 为 AudioStreaming |
| Alt 1 | 线性 PCM，双声道，16 位占 2 字节，48 kHz，端点 `0x03`，192 字节 |
| Alt 2 | 线性 PCM，双声道，24 位占 3 字节，48 kHz，端点 `0x03`，288 字节 |
| 同步 | 自适应，`bInterval` 为 1，没有反馈端点，没有多事务 |
| 采样率请求 | 端点声明支持 |
| 音量 | Feature Unit 9 声明主音量和静音，没有分声道音量。UAC1 不声明是否可写 |
| 麦克风 | 没有采集流。Android 仍把它列为 `usb_headset` |
| 总线速度 | 未读取。包长正好等于每毫秒的数据量，提示为全速，要到 M7-2 用 `USBDEVFS_GET_SPEED` 确认 |

- **结果：** 通过，没有错误。读取后系统音频设备列表仍有 `usb_headset`，用户数据保留。系统没有提供“始终使用”选项，因为当前 Activity 没有声明 `USB_DEVICE_ATTACHED` 过滤器（D8）。
- **对后续的影响：** 先实现 UAC1。M7-2 的完整链路只能使用 48 kHz 音源。JBL Flip 7 不能用于 UAC2、异步反馈、多采样率切换和 #65 的测试。
- **仍未确认：** 主音量是否可写、音量范围、采样率读回和总线速度，都要等 M7-2 声明接口后才能取得。
