# Media3 的起播淡入与严格输出声明：一次捕获测试

[English](MEDIA3_FADE_IN_STUDY.md) · 中文

2026-11-02。被测版本为 Media3 1.11.0（Vesqen 1.1.1 使用的版本）。测试代码为 [`Media3PcmCaptureDeviceTest`](../app/src/androidTest/java/io/github/sumirenokai/vesqen/playback/Media3PcmCaptureDeviceTest.kt)，由 [PR #241](https://github.com/Sumire-no-kai/Vesqen/pull/241) 合入。

## 摘要

Vesqen 的严格 USB 输出以“送到 DAC 的样本与原文件一致”为目标。本文用捕获测试检查 Media3 交给音频输出的 PCM 是否与源文件一致。结果显示，`DefaultAudioSink` 在每次起播、seek、重新 prepare 和格式切换后，对前 20 ms 做线性淡入，其余样本逐字节一致。这项处理没有关闭入口，在输出端也无法还原。本文比较了两种规避方案，两者都能使样本保持一致，Vesqen 最终选择绕过 `DefaultAudioSink` 的严格 PCM Sink。本文的结论只到 Media3 的输出接口为止，不能证明 DAC 收到的数据。

## 1. 背景

Vesqen 区分几个强度不同的声明：文件是无损格式、系统支持直通、已请求 bit-perfect、外部验证通过。严格 USB 输出的前提之一，是应用自身的处理链没有改动样本。链路页的“Vesqen 这一段”也会给出“未改动音频”这一结论。

此前的判断依据是配置：音量为 100%、速度为 1.0、没有 EQ，格式转换不丢精度。这些条件都不直接观察样本。2026-11-02 的一次架构审查读到 Media3 的源码后指出，`DefaultAudioSink` 会无条件改动输出开头的样本。这一点此前没有被发现，于是需要实测。

## 2. 研究问题

1. Media3 实际交给音频输出的 PCM，与源文件有什么差别？
2. 哪种方案能让严格模式下的样本保持不变？
3. 以后升级 Media3 时，能否发现这个行为的变化？

## 3. 方法

**测试装置。** 用一个假的音频输出接替真实输出，把 `AudioOutput.write()` 收到的内容保存到内存，再与源文件的 PCM 逐字节比较。播放由真实的 `ExoPlayer` 和真实的解码器完成，全程不需要 DAC，也不发出声音。输出端的时钟是模拟的，按 4 倍速消耗数据。

**音源。** 音源在运行时生成，避免依赖外部文件。内容是用固定种子生成的满量程噪声，以便按内容唯一地对齐。格式为 16 位和 24 位的 WAV 与 FLAC，采样率为 44.1、48 和 96 kHz，时长一般为 2 秒，另有 10 ms 的短曲。FLAC 由测试代码直接写出，使用 verbatim 子帧，不依赖编码器。

**比较方式。** 先在淡入范围之外取一段数据，按内容在源文件中定位，再比较捕获到的每一帧。float 样本按源位深还原成整数后比较；还原结果不是整数时，单独计为“不在整数格点上”。

**三种模式。**

- 现状：应用目前使用的 `DefaultAudioSink`。
- 方案 A：包装 `DefaultAudioSink`，在每段输出之前送入 20 ms 数字静音，让淡入只作用在静音上。
- 方案 B：严格 PCM Sink，PCM 不经处理直接写入输出。

**用例。** 完整播放、seek、重新 prepare、同格式切歌、混合位深切歌、连续格式切换、短于 20 ms 的曲目、部分写入、输出卡住、播放进度（起播和 seek 后），以及带 encoder delay 的音源。

**环境。**

| 设备 | 系统 | FLAC 解码器 |
| --- | --- | --- |
| 模拟器 | Android 16（API 36） | `c2.android.flac.decoder` |
| Honor STF-AL00 | Android 9（API 28） | `OMX.google.flac.decoder` |

## 4. 结果

### 4.1 现状：前 20 ms 被改动

下表是模拟器上完整播放的结果。seek、重新 prepare 和格式切换之后的结果相同，被改动的都是新一段输出的前 20 ms。

| 音源 | 到达输出端的格式 | 被改动的帧 | 不在整数格点上的样本 |
| --- | --- | --- | --- |
| WAV，44.1 kHz，16 位 | int16 | 882 | 0 / 1764 |
| FLAC，44.1 kHz，16 位 | float | 882 | 1739 / 1764 |
| WAV 或 FLAC，48 kHz，24 位 | float | 960 | 1561 / 1920 |
| WAV 或 FLAC，96 kHz，24 位 | float | 1920 | 3104 / 3840 |

被改动的帧数恰好等于 20 ms，第一帧为零，20 ms 之后完全一致。这与[源码](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1915-L1931)一致：`maybeRampUpVolume()` 只判断是否为 PCM 和已写入帧数，每次新建输出后计数归零。`DefaultAudioSink` 的 Builder 没有关闭它的选项。Media3 1.11.1 和当前的 main 分支仍然如此，因此升级不能解决问题。

同格式切歌时输出被复用，曲目边界不淡入，也不丢帧。源位深不同，但解码后都是同采样率 float 的曲目，同样如此。

### 4.2 整数校验不足以发现这个问题

16 位 WAV 的淡入样本仍是合法的整数，1764 个样本全部通过整数校验。因此“还原后必须是整数”只是必要条件。Vesqen 原先的架构设计依靠这项校验，并假定只替换 `AudioOutput` 就够了，这两点都不成立。

### 4.3 方案比较

两个方案都通过了全部字节比较：严格模式下，所有用例从第一帧起都与源文件一致，流结束时不丢帧，播放进度也与源文件对应。差别在下列方面：

| 项 | 方案 A：静音前导 | 方案 B：严格 PCM Sink |
| --- | --- | --- |
| 送到输出的内容 | 每次起播、seek 和格式切换多出 20 ms 静音 | 只有源数据 |
| 输出卡住 | 输出被重建，真实样本被淡入（48 kHz 下 960 帧） | 不经过这条路径 |
| encoder delay | 裁剪落在静音上，源文件的延迟帧留在输出里 | 不裁剪 |
| 时钟和流结束 | 由 Media3 实现，外层须换算进度 | 自己实现 |
| 依赖 | Media3 的内部行为，变化时编译器不会报错 | 公开的 `AudioSink` 接口 |
| 原型代码量 | 65 行 | 184 行，另需按模式选择 Sink 的外层 |

encoder delay 这一项，方案 A 无法保持正确的裁剪，所以两个方案都改为拒绝这类音源。WAV 和 FLAC 不受影响，ALAC 尚未用真实文件确认。

### 4.4 Android 9

Android 9 上，24 位 FLAC 到达输出端时只有 16 位。Vesqen 为避免 [#107](https://github.com/Sumire-no-kai/Vesqen/issues/107) 的崩溃，不向 OMX 解码器请求 float 输出，它们输出 16 位 PCM。低位在到达输出端之前已经丢失，所以严格输出会拒绝这种情况。这些用例在 Honor 上记录后跳过字节比较，24 位 FLAC 的比较结果来自模拟器。

### 4.5 运行记录

| 版本 | 设备 | 用例 | 轮数 | 结果 |
| --- | --- | --- | --- | --- |
| 比较版本（现状、A、B） | 模拟器 | 36 | 5 | 全部通过，1 项按设计跳过 |
| 比较版本（现状、A、B） | Honor | 36 | 2 | 全部通过，1 项按设计跳过 |
| 合入版本（现状、B） | 模拟器 | 26 | 2 | 全部通过，2 项按设计跳过 |
| 合入版本（现状、B） | Honor | 26 | 1 | 全部通过，2 项按设计跳过 |
| 收紧断言后（现状、B） | 模拟器 | 36 | 3 | 全部通过，6 项按设计跳过 |

跳过的项只适用于严格模式，或者不需要播放，只运行一次。收紧断言后没有在 Honor 上重跑。

## 5. 讨论

**对已发布版本的影响。** Vesqen 1.1.1 的链路页在结论里写“未改动音频”。这个判断有一段等待期，结论显示时淡入多半已经过去，但这句话没有说明这一点。严格 USB 官方路径的 `BIT-PERFECT ACTIVE` 同样受影响，只是现有测试手机都没有开放官方通道，这部分没有真机证据。处理方式是：1.2.0 修改文案（[#242](https://github.com/Sumire-no-kai/Vesqen/issues/242)），2.0 修复严格模式。普通输出继续使用 `DefaultAudioSink`，淡入保留，说法也须保留这一点。

**为什么选择方案 B。** 方案 A 确实更短，但它的正确性依赖 Media3 内部如何 flush、如何裁剪、淡入有多长，这些变化时编译器不会报错。方案 B 的代码更多，时钟和流结束也要自己负责，换来的是严格模式下 Media3 的处理链碰不到样本，也不向输出插入任何额外内容。

**对 Media3 的评价。** 本文不认为这是 Media3 的缺陷。它是通用播放库，并不承诺样本恒等。本文也没有评估这段淡入对普通播放的听感影响。

**测试本身的问题。** 这组测试也有过漏洞：比较按内容对齐，所以开头丢帧的捕获原本能够通过。自动代码审查发现了这一点，现在要求完整音源的捕获必须从第一帧开始，并加了对应的回归测试。后来的独立审查又发现两处断言过松：流结束只检查位置的上界，现状基线也只限制淡入不超过 20 ms，15 ms 的淡入同样能够通过。这两处都已收紧，并加了回归测试。另外，进度用例在 4 倍速的模拟时钟下对时序敏感，偶尔失败，现在在采样点冻结时钟。

## 6. 局限

- 测试只验证到 `AudioOutput.write()`，不代表 DAC 收到的内容。真实的逐样本证明要靠外部数字采集（[#49](https://github.com/Sumire-no-kai/Vesqen/issues/49)），目前还没有任何组合达到 `BIT-PERFECT VERIFIED`。
- 输出端的时钟是模拟的。真实 USB 上的时序、欠载和反馈要到 M7-2 才能验证。
- 只测了两台设备，模拟器代表 AOSP 的解码器。没有测 iQOO 或其他厂商的解码器，也没有测 ALAC。
- 方案 B 目前是测试代码中的原型，应用还没有使用它。
- 这组测试需要设备，CI 只编译，不运行。

## 7. 结论

1. Media3 1.11.0 的 `DefaultAudioSink` 会改动起播、seek、重新 prepare 和格式切换后的前 20 ms，其余样本不变。
2. 这项处理无法关闭，也无法在输出端还原，严格模式必须在 Sink 这一层处理。严格 PCM Sink 能让样本从第一帧起保持一致，Vesqen 选择了这个方案。
3. 只检查配置和整数格点，不足以支持“未改动音频”这样的声明，需要直接比较样本。

这个测试保留在仓库中。今后升级 Media3 时手动在设备上运行，如果淡入行为发生变化，现状模式的用例会失败。

## 8. 复现

需要一台已连接的设备或模拟器，JDK 25。

```bash
./gradlew :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest
```

```bash
adb install -r -t app/build/outputs/apk/github/deviceTest/app-github-deviceTest.apk
```

```bash
adb install -r -t app/build/outputs/apk/androidTest/github/deviceTest/app-github-deviceTest-androidTest.apk
```

```bash
adb shell am instrument -w -r -e class io.github.sumirenokai.vesqen.playback.Media3PcmCaptureDeviceTest io.github.sumirenokai.vesqen.devicetest.test/androidx.test.runner.AndroidJUnitRunner
```

每个用例的详细数字写在 logcat 的 `Media3PcmCapture` 标签下。在会冻结无界面进程的 ROM 上，改用 `tools/run_device_tests.py --foreground-host --host-launch each-case`。

## 参考

- [`DefaultAudioSink.maybeRampUpVolume()`，Media3 1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1915-L1931)
- [M7 架构设计](M7_USB_ARCHITECTURE.md)、[架构审查](M7_USB_ARCHITECTURE_REVIEW.md)、[二次审查](M7_USB_ARCHITECTURE_REVIEW_2.md)
- [#71 自有 USB 输出](https://github.com/Sumire-no-kai/Vesqen/issues/71)、[#49 外部验证](https://github.com/Sumire-no-kai/Vesqen/issues/49)、[#242 链路页文案](https://github.com/Sumire-no-kai/Vesqen/issues/242)
