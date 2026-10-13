# Google Play 上架材料

状态：2026-09-30 为 beta.1 起草；2026-10-13 按 `1.0.0-beta.2` 的 Play 版重写，用于封闭测试（#44、#45、#47、#56）。对外说法遵守 [M4 Beta 发布清单](M4_BETA_RELEASE.md) 的“对外声明基线”：不宣传未经验证的 bit-perfect，没验证过的组合写“未验证”。测试版免费，不含任何收费功能；收费版本上线前按 #46、#48 更新本文件。App 的行为、价格或验证状态变了，也要同步改这里和 Play Console。

Play 版和 GitHub 版的区别见 [版本管理](VERSIONING.md#build-flavors-and-channels)：Play 版没有自动更新，也没有“安装应用”权限；使用统计和设备报告上传与 GitHub 版相同。

字数上限按 Play Console：应用名称 30、简短说明 80、完整说明 4000、版本说明 500，下面每段都标了实际字数。

## 1. 基本信息

| 项目 | 填写 |
| --- | --- |
| 应用名称 | `Vesqen`，中英文相同（2026-10-12 创建应用时填写） |
| 包名 | `io.github.sumirenokai.vesqen`，与 GitHub 版相同，两个版本可以互相覆盖安装 |
| 默认语言 | 英语（美国）en-US；另加中文（简体）zh-CN |
| 应用或游戏 | 应用 |
| 免费或付费 | 免费。免费上架后不能改为付费下载，这是已确认的单向决定（[商业模式](MONETIZATION.md)） |
| 类别 | 音乐与音频（Music & Audio） |
| 标签 | 在 Play Console 的预设列表里选与音乐播放器、音频播放器相关的标签 |
| 联系邮箱 | vesqen@sumirenokai.com |
| 网站 | https://vesqen.sumirenokai.com |
| 电话 | 不填 |
| 外部营销 | 开启 |
| 隐私政策网址 | https://vesqen.sumirenokai.com/privacy/ |

用户的语言没有对应的商品详情时，Play 显示默认语言（英语）。繁体中文（zh-TW、zh-HK）的商品详情以后需要时再加。

## 2. 商品详情文案

中文是写给中文读者的，不是逐句翻译。不要用 Play Console 的“使用 AI 导入翻译”。

### English (en-US)

简短说明（76/80）

```text
Offline music player that shows the path each song takes to your headphones.
```

完整说明（2470/4000）

```text
Vesqen is an offline music player for the music already on your Android phone. It needs no account and shows no ads. While a song plays, Vesqen records each step from the file to your headphones, and every value says where it came from.

Your library
• Finds music through Android's media library and in folders you add.
• Browse by song, album, artist, folder, genre or playlist. Keep favorites, and a queue that is still there next time.
• Plays FLAC, ALAC, WAV and AIFF, plus MP3, AAC, Ogg Vorbis and Opus, as far as your phone's decoders support them.
• Background playback with controls in the notification and on the lock screen.

The Chain page
• Shows the source file, the decoder, what AudioTrack and Android's mixer report, and the output route.
• Each value is marked as measured, derived or estimated, with the time it was observed.
• For Bluetooth, it shows Vesqen's part separately from the phone's Bluetooth encoding, which is lossy or unknown.

Strict USB output
• With a USB DAC on Android 14 or later, Vesqen can ask Android for its official bit-perfect USB mode.
• It works only when the phone offers that path and the DAC has a matching profile. Many phones don't offer it, even on Android 14 and later.
• If the conditions can't be kept, Vesqen stops playback and tells you why. It doesn't switch to system output on its own.
• No phone and DAC combination has been verified bit-perfect with an external measurement yet. Treat every combination as unverified.

Output states
SYSTEM MIXED: Android's normal playback path, which may mix, resample or add effects.
BIT-PERFECT ACTIVE: Android reports that the AudioTrack, the mixer and the route match the file. This is evidence from the Android side, not an external measurement.
BIT-PERFECT VERIFIED: a signed record from an external measurement matches your exact app, phone, DAC and format.

Privacy
Playback and your library never use the network. Vesqen sends a usage statistics message at most once a day, with no ID of any kind, and you can turn it off. In the EU, the EEA, the UK, mainland China and South Korea it asks first. Device reports are sent only when you choose to upload one. The privacy policy lists what each one sends and how long it is kept: https://vesqen.sumirenokai.com/privacy/

Beta
This is a beta. It is free and includes every feature. Version 1.0 is planned to offer a free trial and a one-time purchase, with no subscription and no ads.

Feedback: vesqen@sumirenokai.com
```

### 中文（简体）zh-CN

简短说明（34/80）

```text
播放手机里的本地音乐，离线可用，并记下每首歌从文件到耳机经过的路径。
```

完整说明（1037/4000）

```text
Vesqen 是一款 Android 离线音乐播放器，播放你手机里已有的音乐，不用注册账号，也没有广告。播放时，它会记下声音从文件到耳机经过的每一步，每个数值都写明来源。

曲库
• 通过 Android 媒体库查找音乐，也可以添加指定的文件夹。
• 按歌曲、专辑、艺术家、文件夹、流派和歌单浏览；可以收藏歌曲，播放队列下次打开还在。
• 支持 FLAC、ALAC、WAV、AIFF，以及 MP3、AAC、Ogg Vorbis、Opus，以手机的解码器支持为准。
• 后台播放，通知栏和锁屏都能控制。

链路页
• 显示源文件、解码器、AudioTrack 和 Android 混音器报告的信息，以及输出路由。
• 每个数值都标明是实测、推导还是估算，以及观测时间。
• 用蓝牙时分两段显示：Vesqen 这一段，以及系统的蓝牙编码，后者是有损或未知的。

严格 USB 输出
• 在 Android 14 及以上的手机上接 USB DAC 时，Vesqen 可以向 Android 申请官方的 bit-perfect USB 模式。
• 前提是手机开放了这条通路，DAC 也有匹配的配置。很多手机即使是 Android 14 以上也没有开放。
• 条件保持不住时，Vesqen 会停止播放并说明原因，不会自己改回系统输出。
• 目前还没有任何手机和 DAC 的组合经过外部测量验证，请把所有组合都当作未验证。

输出状态
SYSTEM MIXED：Android 的普通播放路径，系统可能混音、重采样或加音效。
BIT-PERFECT ACTIVE：Android 报告的 AudioTrack、混音器和路由都与文件一致。这是 Android 端的证据，不是外部测量。
BIT-PERFECT VERIFIED：一份来自外部测量的签名记录，与你的应用版本、手机、DAC 和格式完全一致。

隐私
播放和曲库从不使用网络。Vesqen 每天最多发送一次使用统计，不带任何 ID，可以关闭；在欧盟、欧洲经济区、英国、中国大陆和韩国会先征求你的同意。设备报告只在你选择上传时才会发送。每一项发送什么、保留多久，隐私政策里都写清楚了：https://vesqen.sumirenokai.com/zh/privacy/

测试版
目前是测试版，免费，可以使用全部功能。正式版计划提供免费试用和一次性买断，不做订阅，也没有广告。

反馈：vesqen@sumirenokai.com
```

## 3. 版本说明（`1.0.0-beta.2`，Play）

Play 上的测试者是第一次安装，不写和 beta.1 相比的变化。在 Play Console 里按语言标签分开填写。

English（472/500）

```text
First test version on Google Play.
• Offline playback with folders, playlists, favorites and a saved queue.
• Chain page: the path from the file to your headphones, with how each value was measured.
• Strict USB output on Android 14+ with a compatible DAC. Many phones don't offer it yet.
• Usage statistics at most once a day, with no ID. You can turn them off.
Known limit: no phone and DAC combination has been verified bit-perfect yet. Feedback: vesqen@sumirenokai.com
```

中文（237/500）

```text
Google Play 上的第一个测试版本。
• 离线播放手机里的音乐，支持文件夹、歌单、收藏，播放队列会自动保存。
• 链路页：显示从文件到耳机的路径，以及每个数值的测量方式。
• Android 14 及以上可配合兼容的 DAC 使用严格 USB 输出，很多手机目前还没有开放。
• 使用统计每天最多发送一次，不带任何 ID，可以关闭。
已知限制：目前还没有任何手机和 DAC 的组合通过 bit-perfect 验证。反馈：vesqen@sumirenokai.com
```

## 4. 图片素材

| 素材 | 文件 | 规格 |
| --- | --- | --- |
| 应用图标 | [store/icon-512.png](store/icon-512.png) | 512 × 512，32 位 PNG，全幅不透明；圆角和阴影由 Play 添加 |
| 置顶大图（英文） | [store/feature-graphic-en.png](store/feature-graphic-en.png) | 1024 × 500，24 位 PNG |
| 置顶大图（中文） | [store/feature-graphic-zh.png](store/feature-graphic-zh.png) | 1024 × 500，24 位 PNG |
| 手机截图（英文） | [store/screenshots/en/](store/screenshots/en/) | 6 张，1080 × 2400，24 位 PNG（Play 不接受带透明通道的截图） |
| 手机截图（中文） | [store/screenshots/zh/](store/screenshots/zh/) | 同上，App 语言为简体中文 |

图标和置顶大图的源文件在 [store/src/](store/src/)，导出方法见 [store/README.md](store/README.md)。置顶大图用 B 方案的版式，叠放三张虚构专辑；注记卡只写文件、解码器和路由，不显示输出状态，以免暗示尚未验证的 bit-perfect。

截图按文件名顺序上传，Play 商品页先显示前几张：

1. `01-now`：正在播放
2. `02-chain`：链路页，播放中的真实状态 SYSTEM MIXED，不摆拍 ACTIVE 或 VERIFIED
3. `03-library`：曲库的歌曲列表
4. `04-albums`：专辑
5. `05-album`：专辑详情
6. `06-settings`：设置里的播放输出选项

英文截图配英文商品详情，中文截图配 zh-CN 商品详情。

## 5. 应用内容声明（Play Console → 应用内容）

2026-10-12 按下表填完，10 项都已提交。

| 项目 | 填写 |
| --- | --- |
| 隐私政策 | https://vesqen.sumirenokai.com/privacy/ |
| 应用访问权限 | 所有功能都不需要账号或特殊访问权限。严格 USB 输出需要硬件，不属于访问限制 |
| 广告 | 不含广告 |
| 内容分级 | IARC 问卷，类别选“其他类型的应用”（All Other App Types）。暴力、性、粗话、受管制物品、赌博都选“否”；用户之间不能互动或分享内容；不分享位置；没有数字商品购买。评级为适合所有年龄 |
| 目标受众 | 只选 18 岁及以上，没有勾选附加的限制未成年人选项。不面向儿童，和隐私政策“儿童”一节一致 |
| 新闻应用 | 否 |
| 数据安全 | 见下表 |
| 广告 ID | 不使用。合并后的 Manifest 里没有 `AD_ID` 权限 |
| 政府应用、金融功能、健康功能 | 都不适用 |
| 前台服务（#56） | 上传 AAB 之后才出现。类型 `mediaPlayback`，用例选“媒体播放”；说明和中断影响见下；演示视频见第 6 节 |

数据安全（依据 Play 版 beta.2：每天一次的使用统计，以及用户自己选择上传的设备报告；Play 版没有更新检查）：

| 问题 | 回答 |
| --- | --- |
| 是否收集或分享所需的用户数据类型 | 是 |
| 传输中是否加密 | 是，全部走 HTTPS |
| 账号 | 应用不允许创建账号 |
| 能否申请删除数据 | 否，但数据会在 90 天内自动删除。统计到达服务器时就合并成不带 ID 的每日总数，报告 7 天后删除。选“是”需要另做一个写明删除步骤的网页 |
| 收集的类型 | 应用活动 → 应用互动；应用信息和性能 → 崩溃日志、诊断信息；文件和文档（只在用户勾选“包含文件名”时上传文件名） |
| 每一类的回答 | 收集，不分享（Cloudflare 作为服务提供方代为处理，Google 不算作分享）；不是临时处理；可选；用途只选“分析” |
| 不勾选 | 位置、个人信息、设备或其他 ID（不发送任何 ID）、音频文件（从不上传音频）以及其他类型 |

1.0 加入 Play 结算（#46、#48）后，按实际收集的数据重新填写。

前台服务申报的说明（控制台用英文填写）：

```text
Vesqen plays music from the user's local library. The mediaPlayback foreground service keeps playback going when the user leaves the app or turns off the screen, and shows playback controls in the notification and on the lock screen.
```

中断的影响：

```text
If the service were deferred or stopped, the music would stop as soon as the user left the app or turned off the screen.
```

## 6. 截图与演示视频

2026-10-13 完成。

- **演示曲库**：截图里不能出现商业专辑的封面和曲名。用 [`tools/demo_library`](../tools/demo_library/README.md) 生成三张虚构专辑（官网同款的 Low Tide Archive、Copper Hours、盐与雾），共十首 FLAC，标签和封面都写全，不会出现 `<unknown>`。
- **拍摄设备**：临时建的 Android 模拟器（AOSP Android 16 arm64 系统镜像，1080 × 2400，420 dpi），装 beta.2 的 Play 版构建，拍完连同系统镜像一起删除。没用两台测试机，因为它们和其他测试共用，曲库里也有商业专辑。
- **拍摄要求**：状态栏用 SystemUI 演示模式（9:30、满电、Wi-Fi 满格、不显示通知），手势导航，画面里没有个人信息。中英文用 `cmd locale set-app-locales` 切换 App 语言，各拍一套。用 `adb exec-out screencap -p` 截取原生分辨率。
- **演示视频（#56）**：用 `adb shell screenrecord` 录了 26 秒：在曲库点一首歌开始播放，回到桌面，下拉通知栏，用媒体控件暂停再继续。锁屏后屏幕熄灭，录到的只有黑屏，所以没有锁屏这一步。视频不放进仓库；上传到 YouTube 并设为“不公开”，把链接填进前台服务申报。

## 7. 封闭测试与上传

- **轨道**：封闭测试。免费上架，测试者直接安装。国家和地区选所有可选的；中国大陆没有 Google Play。
- **测试者**：至少 12 人连续参加 14 天，建议招 15～20 人，避免中途退出后人数不够。在 Play Console 里用电子邮件列表添加，和隐私政策“封闭测试”一节的写法一致；如果改用 Google 群组，先改隐私政策。官网、两个 README 和 beta.2 发布说明都写了报名方式：用 Google Play 账号的邮箱发信到 vesqen@sumirenokai.com。
- **反馈**：vesqen@sumirenokai.com，也可以在 Play 上直接反馈。
- **记录**：仓库只记录测试人数、起止日期和结论，不记录测试者身份、邮箱或订单号。测试者名单在测试结束后 2 个月内删除，与隐私政策一致。
- **签名和 AAB**：按 [发布签名记录](RELEASE_SIGNING.md#google-play-enrollment-and-upload) 导入 application signing v1、注册 upload v1 证书并核对指纹，再用上传密钥在本机签 AAB。beta.2 的 AAB 来自 GitHub Actions 运行 38213613584 的 `unsigned-play-bundle`，保留 30 天（到 2026-11-11），SHA-256 `b89f24e63f8891cc2bf697c68fd4f8fb03c1d6e3b7ec3672ac9526e865043521`。
- **自动保护**：Protected with Play → Automatic protection 里只有 Installer check（默认开启）。它在 Play 下发的 APK 里加本地检查，从其他来源装了 Play 版时提示去 Play 安装，不联网、不发数据。“Share app telemetry with Google” 开关属于防篡改保护，只对部分 Play 合作伙伴开放，本账号没有这一项（2026-10-13 核对）。以后如果出现，开启前先和所有者确认，并同步改隐私政策和数据安全表单。改了自动保护设置后，要重新上传 AAB 才生效。
- **开发者验证**：Android developer verification 页面里，`io.github.sumirenokai.vesqen` 为 Registered，应用签名密钥 `74:3E:96:FC:…:62:93` 为 Verified（2026-10-13 核对）。GitHub 版用同一把密钥，所以也已覆盖。列表里另外 3 把是 Play 生成的密钥，包括已弃用的 `F7:8F:…:C6:E3`，不影响。
- **顺序**：商品详情 → 封闭测试轨道和测试者 → Play 应用签名和上传 AAB → 前台服务申报 → 送审。审核通过、测试者加入后，14 天从那时开始计算。
- **进度**：2026-10-13 第一次送审（封闭测试 `11 (1.0.0-beta.2)`、商品详情、应用内容、商店设置）当天通过，封闭测试轨道 Active，178 个国家/地区。前台服务申报的演示视频是 YouTube 不公开视频。测试者在名单里之后，用 Android 上的 Play 链接或网页链接 `https://play.google.com/apps/testing/io.github.sumirenokai.vesqen` 加入。当天加入人数为 0，名单 2 人。
