# Google Play 上架材料

状态：2026-09-30 起草，用于 `1.0.0-beta.1` 的封闭测试（#44、#45、#56）。对外说法遵守 [M4 Beta 发布清单](M4_BETA_RELEASE.md) 的“对外声明基线”：不宣传未经验证的 bit-perfect，没验证过的组合写“未验证”。beta.1 免费，不含任何收费功能；收费版本上线前按 #46、#48 更新本文件。

字数上限按 Play Console：应用名称 30、简短说明 80、完整说明 4000、版本说明 500，下面每段都标了实际字数。

## 1. 基本信息

| 项目 | 填写 |
| --- | --- |
| 应用名称 | 英文 `Vesqen: Offline Music Player`；中文 `Vesqen：离线音乐播放器` |
| 默认语言 | 英语（美国）en-US；另加中文（简体）zh-CN |
| 应用或游戏 | 应用 |
| 免费或付费 | 免费。免费上架后不能改为付费下载，这是已确认的单向决定（[商业模式](MONETIZATION.md)） |
| 类别 | 音乐与音频（Music & Audio） |
| 标签 | 在 Play Console 的预设列表里选与音乐播放器、音频播放器相关的标签，以控制台实际提供的为准 |
| 联系邮箱 | vesqen@sumirenokai.com |
| 网站 | https://vesqen.sumirenokai.com |
| 电话 | 不填 |
| 隐私政策网址 | https://vesqen.sumirenokai.com/privacy/ |

用户的语言没有对应的商品详情时，Play 显示默认语言（英语）。繁体中文（zh-TW、zh-HK）的商品详情以后需要时再加。

## 2. 商品详情文案

### English (en-US)

应用名称（28/30）

```text
Vesqen: Offline Music Player
```

简短说明（75/80）

```text
Plays the music on your phone offline and shows how each track reaches you.
```

完整说明（2063/4000）

```text
Vesqen plays the music stored on your phone. It works offline and needs no account. While a track plays, Vesqen records the path from the file to your headphones, and the Chain page shows each step with where the value came from and how it was measured.

Your library
• Finds music through Android's media library and in folders you add.
• Lossless FLAC, ALAC, WAV and AIFF, plus MP3, AAC, Ogg Vorbis and Opus, as far as your phone's decoders support them.
• Browse by album, artist and folder, keep playlists and favorites, and edit a queue that is still there next time.
• Plays in the background, with controls in the notification and on the lock screen.

The Chain page
• Shows the source format, the decoder, what Android's audio system reports and the output route.
• Each value says whether it was measured, derived or estimated, and when it was observed.
• Status labels keep claims apart. SYSTEM MIXED means Android may mix, resample or add effects. BIT-PERFECT ACTIVE means Android reports that the request, the mixer and the route all match the file. BIT-PERFECT VERIFIED appears only when a signed verification record matches your exact app build, phone, DAC and format.

Strict USB output (Android 14 and later)
• With a compatible USB DAC, Vesqen can ask Android for its official bit-perfect USB mode.
• If the conditions can't be kept, Vesqen stops playback and tells you why. It doesn't switch to system output on its own.
• Whether it works depends on the phone, its Android build and the DAC. Combinations we haven't verified are marked as unverified.

Privacy
• Vesqen doesn't request Android's Internet permission, so the app itself can't send or receive data.
• Your library, playlists and play counts stay in the app's private storage on your phone.

Requirements and status
• Android 8.0 or later. Strict USB output needs Android 14 or later and a compatible USB DAC.
• This is a beta. Some features are still being checked on more phones and DACs; the release notes list what is known.
• Vesqen is open source under the Apache 2.0 license.
```

### 中文（简体）zh-CN

应用名称（14/30）

```text
Vesqen：离线音乐播放器
```

简短说明（29/80）

```text
离线播放手机里的音乐，并写明每首歌从文件到耳机经过的路径。
```

完整说明（876/4000）

```text
Vesqen 播放你手机里的音乐，离线就能用，也不用注册账号。播放时，它会记下声音从文件到耳机经过的每一步；链路页上的每个数值，都写明了来源和测量方式。

曲库
• 通过 Android 媒体库查找音乐，也可以添加指定的文件夹。
• 支持无损的 FLAC、ALAC、WAV、AIFF，以及 MP3、AAC、Ogg Vorbis、Opus，以手机的解码器支持为准。
• 按专辑、艺术家和文件夹浏览，支持歌单和收藏；播放队列可以编辑，下次打开还在。
• 后台播放，通知栏和锁屏都能直接控制。

链路页
• 显示源文件格式、解码器、Android 音频系统报告的信息，以及输出路由。
• 每个数值都标明是实测、推导还是估算，以及观测时间。
• 状态标签把不同程度的结论分开：SYSTEM MIXED 表示 Android 可能混音、重采样或加音效；BIT-PERFECT ACTIVE 表示 Android 报告的请求、mixer 和路由都与文件一致；只有签名的验证记录与你的应用版本、手机、DAC 和格式完全一致时，才会显示 BIT-PERFECT VERIFIED。

严格 USB 输出（Android 14 及以上）
• 连接兼容的 USB DAC 后，Vesqen 可以向 Android 请求官方的 bit-perfect USB 模式。
• 条件保持不了时，Vesqen 会停止播放并说明原因，不会自己改回系统输出。
• 能不能用取决于手机、系统版本和 DAC。没有验证过的组合会标为未验证。

隐私
• Vesqen 没有申请 Android 的网络访问权限，应用本身无法发送或接收数据。
• 曲库、歌单和播放次数都保存在应用的私有存储里，只在你的手机上。

系统要求与版本状态
• Android 8.0 及以上。严格 USB 输出需要 Android 14 及以上和兼容的 USB DAC。
• 当前是测试版，部分功能还在更多手机和 DAC 上验证，已知情况见版本说明。
• Vesqen 是开源软件，采用 Apache 2.0 许可证。
```

## 3. 版本说明（`1.0.0-beta.1`）

上传前按 #42 的真机结果再核对一遍。

English（442/500）

```text
First test version of Vesqen 1.0 beta.
• Local library with folders, playlists, favorites and a saved queue.
• Chain page: the playback path, with how each value was measured.
• Strict USB output on Android 14+ with a compatible DAC. It stops playback if it can't be kept.
• The privacy policy is in Settings > About.
Known limit: no phone and DAC combination has been verified bit-perfect yet. Please send problems to vesqen@sumirenokai.com.
```

中文（223/500）

```text
Vesqen 1.0 测试版的第一个测试版本。
• 本地曲库：文件夹、歌单、收藏，播放队列会自动保存。
• 链路页：显示播放路径，以及每个数值的测量方式。
• Android 14 及以上可配合兼容 DAC 使用严格 USB 输出，条件保持不了时会停止播放。
• 隐私政策在“设置 > 关于”里。
已知限制：目前还没有任何手机和 DAC 的组合通过 bit-perfect 验证。遇到问题请发邮件到 vesqen@sumirenokai.com。
```

## 4. 图片素材

| 素材 | 文件 | 规格 |
| --- | --- | --- |
| 应用图标 | [store/icon-512.png](store/icon-512.png) | 512 × 512，32 位 PNG，全幅不透明；圆角和阴影由 Play 添加 |
| 置顶大图（英文） | [store/feature-graphic-en.png](store/feature-graphic-en.png) | 1024 × 500，24 位 PNG |
| 置顶大图（中文） | [store/feature-graphic-zh.png](store/feature-graphic-zh.png) | 1024 × 500，24 位 PNG |
| 手机截图 | 待拍，见第 6 节 | 至少 2 张、最多 8 张，竖屏；中英文各一套 |

源文件在 [store/src/](store/src/)，导出方法见 [store/README.md](store/README.md)。置顶大图用 B 方案的版式和官网同款的虚构专辑。注记卡特意显示 SYSTEM MIXED（有线耳机），不在宣传图里展示尚未验证的 BIT-PERFECT 状态。

## 5. 应用内容声明（Play Console → 应用内容）

| 项目 | 填写 |
| --- | --- |
| 隐私政策 | https://vesqen.sumirenokai.com/privacy/ |
| 应用访问权限 | 所有功能都不需要账号或特殊访问权限。严格 USB 输出需要硬件，不属于访问限制 |
| 广告 | 不含广告 |
| 内容分级 | 问卷类别选最接近的“其他类型的应用”（以控制台实际选项为准）。暴力、性、粗话、受管制物品、赌博都选“否”；用户之间不能互动或分享内容；不分享位置；beta.1 没有数字商品购买。预计评级为适合所有人 |
| 目标受众 | 13–15 岁、16–17 岁、18 岁及以上。不面向 13 岁以下，也不以儿童为吸引对象，和隐私政策“儿童”一节一致 |
| 新闻应用 | 否 |
| 数据安全 | 不收集任何数据；不与第三方分享任何数据。依据：beta.1 没有 INTERNET 权限，数据只在手机上；媒体会话属于用户发起、可预期的传递（核查记录见[开发日志](DEVELOPMENT_LOG.md) 2026-09-26）。收费版本按 #48 的结论更新 |
| 广告 ID | 不使用。Release 合并 Manifest 里没有 `AD_ID` 权限 |
| 政府应用、金融功能、健康功能 | 都不适用 |
| 前台服务（#56） | 类型 `mediaPlayback`，用例选“媒体播放”；说明和中断影响见下；演示视频见第 6 节 |

前台服务申报的说明（控制台用英文填写）：

```text
Vesqen plays music from the user's local library. The mediaPlayback foreground service keeps playback going when the user leaves the app or turns off the screen, and shows playback controls in the notification and on the lock screen.
```

中断的影响：

```text
If the service were deferred or stopped, the music would stop as soon as the user left the app or turned off the screen.
```

## 6. 截图与演示视频（需要真机）

- **演示曲库**：截图里不能出现商业专辑的封面和曲名（#44）。用自制音频加官网同款的虚构专辑（Low Tide Archive、Copper Hours、盐与雾），标签和封面写完整，避免出现 `<unknown>`（#32）。生成带标签和封面的 FLAC 需要 ffmpeg 一类工具，本机目前没有，安装前先征得所有者同意。
- **截图清单**（手机分别切到英文和中文，各拍一套）：
  1. 曲库（专辑视图）
  2. 正在播放
  3. 链路页（播放中，只拍真实状态，不摆拍 ACTIVE 或 VERIFIED）
  4. 设置（播放输出选项）
- **拍摄要求**：状态栏用系统演示模式（固定时间、满电、无通知），画面里不出现个人信息；原生分辨率竖屏，用 `adb exec-out screencap -p` 截取。
- **演示视频（#56）**：用 `adb shell screenrecord` 录制，30 秒以内。在曲库开始播放，回到桌面，再锁屏，用通知栏的控件暂停和继续。上传到在线视频平台并设为不公开列出，把链接填进前台服务申报。

## 7. 封闭测试设置（#45）

- 轨道：封闭测试。免费上架，测试者直接安装。
- 测试者：用电子邮件列表添加，至少 12 人，建议多招几人，避免中途退出后连续 14 天的人数不够。测试者需要 Google 账号，并位于开放的国家或地区；中国大陆无法使用 Google Play。
- 反馈方式：vesqen@sumirenokai.com。
- 仓库只记录测试人数、起止日期和结论，不记录测试者身份、邮箱或订单号。测试者名单在测试结束后 2 个月内删除，与隐私政策一致。
- 签名：Play 应用签名导入现有的 application signing v1，注册 upload v1 证书并核对指纹（[发布签名记录](RELEASE_SIGNING.md)，#41 完成后才能生成签名包）。
