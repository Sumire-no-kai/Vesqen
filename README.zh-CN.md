# Vesqen

[![Android CI](https://github.com/Sumire-no-kai/Vesqen/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/Sumire-no-kai/Vesqen/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Sumire-no-kai/Vesqen?include_prereleases&color=7A4F00)](https://github.com/Sumire-no-kai/Vesqen/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![License](https://img.shields.io/badge/license-Apache--2.0-536B1E)](LICENSE)

[English](README.md) · **简体中文**

Vesqen 是一款 Android 离线音乐播放器，播放你手机里的音乐，不用注册账号。播放时，它会记下声音从文件到耳机经过的每一步；链路页上的每个数值，都写明了来源和测量方式。

[官网](https://vesqen.sumirenokai.com/zh/) · [隐私政策](https://vesqen.sumirenokai.com/zh/privacy/) · [支持](https://vesqen.sumirenokai.com/zh/support/)

> **当前状态：** 第一个公开测试版 `1.0.0-beta.1` 已在 [GitHub Releases](https://github.com/Sumire-no-kai/Vesqen/releases/tag/v1.0.0-beta.1) 发布，暂时还没有上架 Google Play。依赖严格 USB 输出之前，请先看[已知限制](#已知限制)。

## 功能

- **曲库**：通过 Android 媒体库查找音乐，也可以添加指定的文件夹。可以按歌曲、专辑、艺术家、文件夹、流派和歌单浏览，支持收藏；播放队列可以编辑，下次打开还在。
- **格式**：无损的 FLAC、ALAC、WAV、AIFF，以及 MP3、AAC、Ogg Vorbis、Opus，以手机的解码器支持为准。
- **后台播放**：通知栏和锁屏都能直接控制。
- **链路页**：显示源文件、解码器、AudioTrack 和 Android 混音器（mixer）报告的信息，以及输出路由。每个数值都标明是实测、推导还是估算，以及观测时间。
- **严格 USB 输出**（Android 14 及以上）：连接兼容的 DAC 时，向 Android 请求官方的 bit-perfect USB 模式。条件保持不了时，Vesqen 会停止播放并说明原因，不会自己改回系统输出。
- **输出验证记录**：可以导入验证记录。只有由 Vesqen 验证签发方签名、并且与应用版本、手机和系统、DAC、源格式、输出格式完全一致的记录，才会让链路页显示 `BIT-PERFECT VERIFIED`。

## 输出状态

| 状态 | 含义 |
| --- | --- |
| `SYSTEM MIXED` | Android 的普通播放路径，系统可能混音、重采样或加系统音效。 |
| `BIT-PERFECT AVAILABLE` | 已连接的 USB DAC 提供匹配的 bit-perfect 混音配置，严格输出还没启用。 |
| `BIT-PERFECT REQUESTED` | Vesqen 正在准备严格 USB 输出，并核对混音器和路由。 |
| `BIT-PERFECT ACTIVE` | Android 报告的 AudioTrack、混音器设置和路由都与文件一致。这是 Android 端的证据，不是外部测量。 |
| `BIT-PERFECT VERIFIED` | 一份来自外部测量的签名记录，与当前这套设备和格式完全一致。 |
| 严格输出已停止 | 严格输出保持不了，播放已停止。 |

状态只按证据显示，不会往高处升级：处于 ACTIVE 的路径不会被称为已验证，无损文件也不会被称为 bit-perfect。

## 下载

- **GitHub Releases**：每个[版本](https://github.com/Sumire-no-kai/Vesqen/releases)都附有签名的 APK 和它的 SHA-256。应用签名证书的 SHA-256 为
  `74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93`，
  可以用 `apksigner verify --print-certs` 核对。GitHub 和 Google Play 的版本使用同一把签名密钥，从任一渠道更新都会保留你的曲库。
- **Google Play**：先做封闭测试，测试结束后再正式上架。
- **系统要求**：Android 8.0 及以上。严格 USB 输出需要 Android 14 及以上和兼容的 USB DAC。

## 隐私

Vesqen 没有申请 Android 的网络访问权限，应用本身无法发送或接收数据。曲库、歌单和播放次数都保存在应用的私有存储里，只在你的手机上。[隐私政策](https://vesqen.sumirenokai.com/zh/privacy/)（[English](https://vesqen.sumirenokai.com/privacy/)）在应用的“设置 → 关于”里也能看到。

## 已知限制

- 目前还没有任何手机和 DAC 的组合通过 bit-perfect 验证，所以还没有 `BIT-PERFECT VERIFIED` 记录。
- 严格 USB 输出还没有在真实的 DAC 上确认过。很多手机即使是 Android 14 及以上，也没有开放 Android 的 bit-perfect 通路，目前测过的 Android 14 及以上手机（vivo 和 iQOO，Android 15 和 16）都没有；在这些手机上，严格模式会停止播放并说明原因。所有组合都请当作未验证。
- Android 13 及以下没有官方的 bit-perfect 混音接口，所以不能使用严格 USB 输出。
- Vesqen 在后台被系统停止后，重新打开应用时会恢复播放队列；暂不支持直接用耳机按键恢复播放。
- 长时间播放、折叠屏、高刷新率和完整的 TalkBack 读屏体验还在测试中。

每个版本的发布说明都会列出当时的限制。已知问题和计划中的工作按版本分在各个[里程碑](https://github.com/Sumire-no-kai/Vesqen/milestones)里。

## 反馈与支持

- **问题和疑问**：发邮件到 vesqen@sumirenokai.com，中文或英文都可以。
- **错误报告和建议**：[新建 issue](https://github.com/Sumire-no-kai/Vesqen/issues/new/choose)，请写上 App 版本、手机型号、Android 版本、DAC 或耳机、文件格式。如果是输出问题，最好附一张链路页的截图。不需要打开任何日志功能。
- **安全问题**：按 [SECURITY.md](SECURITY.md) 的方式私下报告，请不要公开发帖。

## 从源码构建

需要 JDK 25 和 Android SDK Platform 36。Gradle Wrapper 会使用 Gradle 9.5.0 和 Android Gradle Plugin 9.3.2。`minSdk` 为 26，`compileSdk` 和 `targetSdk` 为 36，应用 ID 为 `io.github.sumirenokai.vesqen`。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 上改用 `.\gradlew.bat`。Debug 安装包在 `app/build/outputs/apk/debug/`。

日常预览和滑动性能检查用 Profile 构建：它不可调试，使用和 Release 相同的 R8 优化，并用本机的调试密钥签名。

```bash
./gradlew :app:installProfile
```

每个经过优化的安装包，都要保留对应的 `app/build/outputs/mapping/<variant>/mapping.txt`，才能还原崩溃堆栈。正式发布使用维护者的签名密钥，密钥不会进入仓库（[发布签名](docs/RELEASE_SIGNING.md)）。版本号来自 [`version.properties`](version.properties)（[版本规则](docs/VERSIONING.md)）。

## 文档

- 产品：[PRODUCT.md](PRODUCT.md)、[需求文档](docs/PRD.md)、[路线图](docs/ROADMAP.md)
- 设计：[DESIGN.md](DESIGN.md)、[视觉识别](docs/brand/VISUAL_IDENTITY.md)、[重设计方向](docs/redesign/README.md)
- 工程：[架构审查](docs/ARCHITECTURE_REVIEW.md)、[工程案例](docs/ENGINEERING_CASEBOOK.md)、[开发日志](docs/DEVELOPMENT_LOG.md)
- 发布：[测试版检查表](docs/M4_BETA_RELEASE.md)、[发布路线与待办](docs/LAUNCH_BACKLOG.md)、[设备验收](docs/M4_DEVICE_ACCEPTANCE.md)

## 参与贡献与许可证

提出修改前请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。Vesqen 由 Sumire Studio 开发，采用 [Apache License 2.0](LICENSE) 发布。
