# 发布路线与待办

`1.0.0-beta.1` 于 2026-10-01 在 [GitHub Releases](https://github.com/Sumire-no-kai/Vesqen/releases/tag/v1.0.0-beta.1) 发布（预发布），官网首页提供下载。本文件按阶段索引剩下的事项，以 GitHub 里程碑为准：[beta.2](https://github.com/Sumire-no-kai/Vesqen/milestone/2)、[1.0 正式版](https://github.com/Sumire-no-kai/Vesqen/milestone/1)、[2.0](https://github.com/Sumire-no-kai/Vesqen/milestone/3)、[Google Play](https://github.com/Sumire-no-kai/Vesqen/milestone/4)。对外声明基线（[M4 Beta 发布清单](M4_BETA_RELEASE.md)）继续适用。

2026-10-01 所有者决定（详见 [PRD](PRD.md) 末尾“2026-10-01 beta.1 发布后的路线决定”）：

- 正式版 1.0 不要求 `BIT-PERFECT VERIFIED`，写成公开限制；最迟在 2.0 之前完成，M7 是 2.0 的必做项。
- Google Play 封闭测试最早使用 beta.2，因为 beta.2 才有设备报告。
- beta.2 起申请联网权限，用于默认开启、可以关闭的匿名统计、设备报告上传和检查更新；服务端用现有的 Cloudflare 账号。
- 收费方案在正式版准备上架前再讨论。2026-10-13 已确定：2.0 开始收费，1.0 和测试版一样免费，写明"2.0 之前免费"；付费功能表和条件见[商业模式](MONETIZATION.md)。

## beta.1（已完成）

签名备份（#41）、真机回归（#42）、开发者验证（#57）、隐私政策定稿和官网页面（#38、#43）、应用内隐私政策（#36）、冻结例外（#60）、GitHub 签名发布流程（#63）都已关闭。#32、#33 的修复已合进 `master`，随 beta.2 发布。

## beta.2（GitHub）

| Issue | 内容 | 类型 |
| --- | --- | --- |
| [#35](https://github.com/Sumire-no-kai/Vesqen/issues/35) | 界面与文案全面重做（B · 纸与声），含 #42 发现的失败文案和无障碍选中状态 | 界面 |
| [#34](https://github.com/Sumire-no-kai/Vesqen/issues/34) | 严格 USB 模式下，冷启动即弹出失败对话框 | bug |
| [#37](https://github.com/Sumire-no-kai/Vesqen/issues/37) | 应用内第三方开源许可声明 | 合规 |
| [#39](https://github.com/Sumire-no-kai/Vesqen/issues/39) | 备份与换机迁移规则 | 数据 |
| [#62](https://github.com/Sumire-no-kai/Vesqen/issues/62) | 2026-09-30 代码审查的遗留项 | 质量 |
| [#66](https://github.com/Sumire-no-kai/Vesqen/issues/66) | 设备测试会改掉测试机上真实的输出模式和队列 | 测试 |
| [#68](https://github.com/Sumire-no-kai/Vesqen/issues/68) | GitHub 版应用内检查、下载并安装更新 | 新功能 |
| [#69](https://github.com/Sumire-no-kai/Vesqen/issues/69) | 导出设备报告（用户选择内容，可上传或分享） | 新功能 |
| [#70](https://github.com/Sumire-no-kai/Vesqen/issues/70) | 匿名使用统计与 Cloudflare 服务端 | 新功能、隐私 |
| [#72](https://github.com/Sumire-no-kai/Vesqen/issues/72) | 链路页：蓝牙播放分两段显示，标出 Vesqen 这一段没有改动音频 | 新功能 |

发布 beta.2 时，隐私政策（应用内和官网）、README、官网和发布说明里“应用不申请联网权限”的说法要一起改写。发布说明还要预告收费方向（正式版计划提供试用和一次性买断），不写价格。#35 的视觉方向见[官网与界面重设计方向](redesign/README.md)。

2026-10-02 起 Vesqen 面向全球用户，从 beta.2 开始在少数目标社区传播，大范围宣传留到 1.0。传播口径、地区和渠道的暂定安排见 [PRD](PRD.md) 末尾“2026-10-02 全球用户与对外传播”。

## beta.3（2026-10-16 已在 GitHub 发布）

2026-10-13 起开发。测试版和 1.x 免费，可以使用全部功能；2.0 开始收费。

已经合并到 `master`：

- [#119](https://github.com/Sumire-no-kai/Vesqen/issues/119)（PR #121）：试用计时、权益状态、第一次打开严格 USB 时的说明，以及设置里的"高级功能"。收费开关关闭，不计时，也不锁功能；文案写"2.0 之前免费"。
- [#118](https://github.com/Sumire-no-kai/Vesqen/issues/118)（PR #122）：beta.2 审查的遗留问题。
- [#120](https://github.com/Sumire-no-kai/Vesqen/issues/120) 的 App 和服务端部分（PR #123、#124、#127）：激活码格式、签发工具、App 验证和输入（只在调试版出现）、服务端发码和作废、激活链接放在 `vesqen-api.sumirenokai.com`。Worker 已在 2026-10-13 部署，销售保持关闭。
- [#113](https://github.com/Sumire-no-kai/Vesqen/issues/113)（PR #125，2026-10-13）：崩溃后下次打开时询问是否发送错误报告。
- [#52](https://github.com/Sumire-no-kai/Vesqen/issues/52)（PR #126）：进程被结束后，按耳机或蓝牙的播放键恢复队列并播放。两台测试手机上按键都没有到达 Vesqen，发布说明不列为新功能，跟进在 [#129](https://github.com/Sumire-no-kai/Vesqen/issues/129)。
- 首次使用的三处界面修正。所有者在 Play 版上发现，直接提交（`0df5828` 到 `9a4e6f2`）：
  - 开屏的使用统计页：答复固定在第一屏，只有说明文字在放不下时滚动；
  - 没有授权的空曲库：主按钮改为"允许访问设备音乐"，"添加音乐文件夹"改为次要按钮；
  - 列表原本在最顶上时，扫描后仍停在最顶上。

发版前（2026-10-16 发布时都已完成，见 [beta.3 发布说明](releases/1.0.0-beta.3.md)开头的清单）：

- 真机验收上面已经合并的内容：2026-10-14 完成，界面测试在 iQOO 上全部通过，签名草稿在 Honor 上从 beta.2 升级并保留数据（`docs/M4_DEVICE_ACCEPTANCE.md`）。
- beta.3 是用户第一次通过应用内更新器升级，要完整测一遍从 beta.2 升到 beta.3：2026-10-16 发布后在 Honor 上通过。
- 发布说明写上这三处修正和"高级功能"的说明，写明"2.0 之前免费"；耳机键恢复只写进已知限制（#129）。
- 官网同步加一句收费说明（官网 `docs/SITE_PLAN.md`）。
- Play Console 商品详情里"测试版"那一段换成 [`PLAY_LISTING.md`](PLAY_LISTING.md) 的新文字（"从 2.0 起……"）。旧文字写的是 1.0 收费，已经上传过（所有者 2026-10-13 要求 beta.3 上线时提醒）。

## 1.0 正式版之前

2026-10-16 所有者确定（[PRD](PRD.md) 末尾"2026-10-16 1.0 的范围"）：`1.0.0` 是 Google Play 正式版的第一版，GitHub 同时发布同一个版本；严格 USB 的设备矩阵写成公开限制，随 2.0 的 M7 完成（#53）；增加日语和繁体中文（#75，2026-10-18 所有者决定日语未经母语者审读也随 1.0 发布）；系统报告的蓝牙 codec 先做实验（#73）。

2026-10-20 所有者改为先在 GitHub 和官网发布 1.0，Play 正式版审核通过后再上架同一个版本（PRD 末尾"2026-10-20 1.0 先在 GitHub 和官网发布"）。GitHub 发版不用等 Google。

Play 正式版的时间取决于 Google：2026-10-16 查看时封闭测试已满 12 人加入，连续 14 天后申请正式版，审核一般 7 天以内。申请表要写测试者反馈的总结。

| Issue | 还要做 |
| --- | --- |
| [#50](https://github.com/Sumire-no-kai/Vesqen/issues/50) | 曲库首页快速滑动：两台手机修复前后各三轮对比，加上大曲库压力测试（M3-R1）。2026-10-17 所有者反馈曲库甩动和设置里的页面切换仍明显卡。2026-10-18 合并了 PR #140（Baseline Profile）、#141（去掉逐首比较曲目的开销）和 #142（设置页在子页面下保持组合）。iQOO（60 Hz，2112 首）上：编译后曲库 UI 线程时间低约 10%、冷启动快约 5%，曲库甩动在帧预算内；设置 ↔ 播放链路的切换开始前有一帧停顿，回设置的这一帧编译后从 39 降到 21 ms，超过 33 ms 的切换从 68/90 降到 50/90。Honor（Android 9，60 Hz，2106 首）2026-10-18 做了三轮前后对比，结果记在 #50：编译后回设置的那一帧从 62 降到 33 ms，冷启动快约 14%；进链路那一帧没变（约 80 ms）；曲库甩动前后一样，约 13–14% 的帧超过 16.7 ms，RenderThread 也接近预算。2026-10-19 合并 PR #145（自动字号先试最大字号，置顶数值上限 27 sp，所有者决定保持 27 sp）：进链路的那一帧 iQOO 37.2 → 31.8 ms，Honor 101.5 → 94.8 ms。2026-10-19 合并 PR #146（列表共用缺省封面的图和图标，行的“更多”按钮第一次按下时才建水波纹）：Honor 编译后曲库甩动超过 16.7 ms 的帧 13.7% → 2.8%，RenderThread 中位 13.9 → 10.4 ms。还剩：进链路这一帧仍偏长（整页排版和底栏重新测量）；Honor 上每出现一行约 2–3 ms 的文字测量；两个都合并后在真机上实际体验；发 1.0 前重新生成 Baseline Profile |
| [#51](https://github.com/Sumire-no-kai/Vesqen/issues/51) | 8 小时有线播放已通过。2026-10-19 iQOO 关屏 24/96 长播有效 4 小时 12 分钟（其中 3 小时 13 分钟用电池），之后的暂停是 vivo 在静音约 3 分钟后发的媒体按键；2026-10-20 所有者决定不再重跑 8 小时，关屏长播和 2 小时 24/96 按通过记。来电中断 2026-10-20 在模拟器上通过（未接、接听、先暂停三种情况）。还缺拔耳机（所有者在 Honor 上做过，待确认）和蓝牙断连。严格播放 8 小时要接 DAC。按 #51 的验收标准，没做的项目写进商品详情和发布页的限制 |
| [#136](https://github.com/Sumire-no-kai/Vesqen/issues/136) | 1.0 代码审查（2026-10-17）：R1–R11、R24（加时区判断）、R32（设置页返回保持位置）已修；R9 在 Android 8.0 模拟器上真的清除媒体存储验证过，真机上没做；R12–R31 其余可以放到之后 |
| [#143](https://github.com/Sumire-no-kai/Vesqen/issues/143) | 2026-10-18 代码审查（10 月 17–18 日的改动）：1.0 前要修的 Q1–Q6 都已修好（Q1、Q2、Q6 是 PR #144，Q3 `d31bbd6`，Q4 `f372289`，Q5 重新生成 profile `f1c8655`）；Q20（迷你播放条按钮的点击区域只有 40 dp）和 Q12–Q14 也已修好（`e08b3d2`）；Q7–Q11、Q15–Q19 可以放到之后 |
| [#149](https://github.com/Sumire-no-kai/Vesqen/issues/149) | 从 1.0 起最低支持 Android 9.0（API 28），2026-10-19 所有者决定：`minSdk` 26 → 28，清掉过时的版本判断，更新 PRD、README、Play 商品详情和官网的系统要求；8.x 设备停在 beta.3，GitHub 更新器按 `minimumAndroidApi` 自动不推送 |
| [#151](https://github.com/Sumire-no-kai/Vesqen/issues/151) | 赞助入口。2026-10-19 所有者决定：GitHub 版"关于"页加"支持开发"（Buy Me a Coffee），Play 版不加（Play 付款政策不允许应用内引导到其他付款方式）。2026-10-20：BMC 用户名改不了，沿用 `buymeacoffee.com/edward_lee`（页面内容已改成 Sumire Studio / Vesqen）；另加偶尔的提示，写明支持用在哪里。隐私政策加了对应说明（10 月 20 日版），发版时同步到官网。见 [`MONETIZATION.md`](MONETIZATION.md) 的"GitHub 优先" |
| [#35](https://github.com/Sumire-no-kai/Vesqen/issues/35) | 按 beta.3 复核界面和文案（字号、深浅色、减少动效），加完整的 TalkBack |

发布 1.0 时：

- 版本号改成 `1.0.0`（`versionCode` 13），写发布说明，重新签名，从 beta.3 做升级验收。
- 在最新的 master 上按 `baselineprofile/README.md` 重新生成 Baseline Profile，提交后再打版本（#50、#143 Q5）。2026-10-18 已在 `69e9226` 上重新生成（`f1c8655`）。之后的 `e08b3d2` 给迷你播放条加了 `MiniPlayerControl`，profile 里还没有它的规则；界面或热路径再有改动也一样，发版前在最终的 master 上再生成一次。2026-10-24 合并 PR #166（#165 全项目审计的修复）后，在 `48bfa7c` 上重新生成（提交排在版本号之前），包含 `MiniPlayerControl`、支持提示，以及 #157 和 #166 改过的热路径。
- 服务端（`server/usage`）已在 2026-10-17 部署（版本 `8bae26bb`，master `ce31aed`），认识设备报告词表里新加的蓝牙编码字段（#73）；没有新的数据库迁移。之后如果服务端再有改动，发版前要再部署一次。官网隐私政策同步到最新版（10 月 18 日，加了时区信号，#143 Q3；`npm run sync-privacy`）。
- 正式通道第一次启用：官网 `updates/stable.json` 第一次填上版本；GitHub 发布不标预发布。
- 发布说明、README、官网和 Play 商品详情写明严格 USB 的限制（#53）。发布说明的已知限制还要写上：耳机键恢复（#129，2026-10-19 所有者决定只写进已知限制，issue 已关闭）；严格模式下切歌时无缝播放会断开，修复前列为限制（#136 R17）。
- Play（审核通过后，和 GitHub 不同时）：商品详情去掉"测试版"那一段，发布到正式版，建议分阶段放量。
- Play 数据安全表单：对照 [`PLAY_LISTING.md`](PLAY_LISTING.md) 第 5 节核对一遍。表单按 beta.2 填写，之后 #73 给设备报告加了系统报告的蓝牙编码字段。
- 官网下载区先以 GitHub 为主，加安装说明；Play 上架后再加 Play 入口。README 状态同步。

## 1.0 之后的排期（暂定）

2026-10-24 所有者决定：官网不写日期，"已知问题与计划"页按"下一个更新版本 / 2.0 之前 / 2.0"三档排。这里的时间是内部估计，可以随反馈调整，不对外承诺（PRD 2026-10-13：对外不承诺具体功能或上线时间）。

### 1.0.1：1.0 发布后 1 周内，2026-11-01 前（[里程碑 1.0.1](https://github.com/Sumire-no-kai/Vesqen/milestone/5)）

2026-10-25 所有者决定提前：不等 2–3 周，1 周内发。2026-10-26：下表的修复都已合并（PR #171–#174），服务端的 #170 P1 已部署；发版步骤见 [1.0.1 发布说明](releases/1.0.1.md)开头的清单。2026-10-27 发布 [v1.0.1](https://github.com/Sumire-no-kai/Vesqen/releases/tag/v1.0.1)（`12fec01`，APK `db8149fe…6d1a`），iQOO 和 Honor 从 1.0.0 覆盖升级的验收记在 [M4 设备验收](M4_DEVICE_ACCEPTANCE.md)。

| Issue | 内容 |
| --- | --- |
| [#158](https://github.com/Sumire-no-kai/Vesqen/issues/158) | 随机播放时"下一首播放"和"添加到队列"插到随机位置：在服务里用自定义命令调用 `setShuffleOrder`，队列按随机顺序显示 |
| [#164](https://github.com/Sumire-no-kai/Vesqen/issues/164) | 文件夹来源里改名或移动文件会丢失收藏、播放次数和歌单位置：按 #143 Q2 的做法先隐藏、再按文件名和大小接回 |
| [#162](https://github.com/Sumire-no-kai/Vesqen/issues/162) | 约 35 个文字按钮的可点区域补到 48 dp |
| [#160](https://github.com/Sumire-no-kai/Vesqen/issues/160) | Android 13–14 上编号 5、6 的蓝牙编码命名 |
| [#167](https://github.com/Sumire-no-kai/Vesqen/issues/167) | 专辑、艺术家等列表的中文排序和歌曲列表一致：先按 A–Z 分组，再按拼音（#165 A13 之后中文全部排在英文之前） |
| [#170](https://github.com/Sumire-no-kai/Vesqen/issues/170) P2、P3 | 1.0.0 发布后审查：播放恢复不看循环模式（单曲循环在曲末会换歌，短于 1 秒的曲目被跳过）；没有专辑艺术家时，不同艺术家的同名专辑合成一张 |
| | 1.0 发布后反馈里需要尽快修的问题 |

#170 P1（设备报告接口的重复属性名）是服务端问题，单独修复和部署，不随 App 版本，部署前由所有者确认。

发 1.0.1 时：`versionName` 1.0.1、`versionCode` 14；重新生成 Baseline Profile；发布说明；官网更新日志加一条，"已知问题与计划"页和 #163 去掉已修好的条目。

### 1.1.0：下一个版本（[里程碑 1.1.0](https://github.com/Sumire-no-kai/Vesqen/milestone/6)）

2026-10-27 所有者决定：1.0.1 之后的修复和两项新功能合成一个 1.1.0，不单独发 1.0.2。1.1.0 之前遇到必须马上修的问题，再单独发 1.0.x。不定日期。按[版本规则](VERSIONING.md)，带新功能的版本升次版本号。

官网"已知问题与计划"和 #163 只列修复；#168、#169 仍不写进 README、官网和 #163（2026-10-25 的决定，2026-10-27 确认不变）。

2026-10-29 进度：#165 合并数据库 helper 已完成（PR #177）；审查遗留的 8 条已全部合并（PR #178–#181），R14 一并修复，R13、R18、#143 Q15、#156 V24 已核实被覆盖，R22 推迟到 1.1.0 之后。所有者决定先做 #159、#139 和 #170 F8–F10，两项新功能稍后开始。

2026-10-30 进度：下表各项已全部合并到 master，`versionName` 仍为 1.0.1。#161 改名已直接提交（`83d39a7`）。新建队列沿用播放模式、"清空队列"后可撤销、关闭动画时"正在播放"标题不滚动由 PR #187 完成（`b036531`）。所有者定下的收尾顺序：
1. 检查四种语言的全部应用内文案；
2. 对 1.1.0 的全部改动与 1.0.1 对照做至少两轮深度代码审查，每轮修完再审；
3. 安全检查，网络部分优先；
4. 更新文档；
5. 所有者真机测试。

协调会话可以在审查完成、CI 通过后自行合并修复 PR；改版本号、重新生成 Baseline Profile 和发布仍要所有者确认。

2026-10-31 进度：收尾顺序的第 1–4 步已完成，所有者说当天没有问题即可准备上线 1.1.0。
- 文案：四种语言共 233 处改为正式书面语（PR #190）。
- 深度审查共三轮，记在 #191：
  - 第一轮 D1–D20，PR #192、#193；
  - 第二轮 A1–A5、B1–B2、C1–C6，PR #195、#196、#197；
  - 第三轮 R3-A1–A2、B3–B5、R3-C1–C2，PR #199、#200、#201、#204。
  - 组合审查（#205）的 F1、F2，PR #206；A5 由 PR #203 修复（均由 Codex 完成，协调会话复核）。
  - 所有者同意 #196 中"未能对应的记录"的新文案。
- 安全检查记在 #194：S1、S3、S4 由 PR #198 修复，第三轮发现的 S1 绕过由 PR #199 修复。
- Baseline Profile 已重新生成，并加入打开歌词的一步（PR #202）。
- 留到 1.1.0 之后（所有者 2026-10-31 同意），见下面"1.1.0 之后、2.0 之前"：D20。
- 留到开放销售之前：S2 和 B5，以及官网的两项核对。
- 发布提交（`release: prepare 1.1.0`）把版本号改为 1.1.0（`versionCode` 15），附发布说明 `docs/releases/1.1.0.md`。推送到 master 即开始发版运行，须所有者确认。
- 2026-10-31 已发布：所有者批准签名和发布。iQOO 和 Honor 从 1.0.1 升级验收通过（`docs/M4_DEVICE_ACCEPTANCE.md`，`docs/releases/1.1.0.acceptance.json`）；`v1.1.0` 于 13:18 发布为 Latest；README、官网（`4f2044e`）和 #163 已同步。未做：Google Play 是否提交 1.1.0 由所有者决定；清除媒体存储后的真机检查（#159、#139）和真机 TalkBack 未做。

新功能：

| Issue | 内容 |
| --- | --- |
| [#169](https://github.com/Sumire-no-kai/Vesqen/issues/169) | Now 页显示本地歌词：手动导入、同名 `.lrc`、内嵌标签，完全离线（PRD"2026-10-24 本地歌词"）。2026-10-30 所有者决定放进 1.1.0，并定下 14 项做法（#169 评论）。PR #188（解析，`cf38470`）和 PR #189（存储、查找、Now 界面，`644ca72`）已合并，三轮审查的 Y1–Y25 均已修复。真机检查和同名 `.lrc` 设备测试随 1.1.0 测试进行 |
| [#168](https://github.com/Sumire-no-kai/Vesqen/issues/168) | "艺术家"页把几位艺术家合写的标签按分隔符拆开分组，只在显示层计算，不改表结构（PRD"2026-10-24 多位艺术家拆分"）。PR #186 已合并（`3edd445`） |

修复和优化：

| Issue | 内容 |
| --- | --- |
| [#165](https://github.com/Sumire-no-kai/Vesqen/issues/165) 审计 | 最多四个 SQLiteOpenHelper 打开同一个数据库，合成一个。下一次改表结构之前必须完成，所以排在 #139 前面 |
| [#159](https://github.com/Sumire-no-kai/Vesqen/issues/159)、[#139](https://github.com/Sumire-no-kai/Vesqen/issues/139) | 媒体库重建时的匹配，以及对不上的旧记录让用户选择归属。都要改扫描流程，一起做。2026-10-29 所有者定下 #139 的五件事：一直保留到用户处理；"待选择"状态沿用现有隐藏行，不改表结构；合并时收藏任一有即算有、播放次数相加、最后播放时间取较晚者、歌单位置去重；只在有这类记录时于来源卡片显示一行提示，不弹窗；只处理匹配不唯一的记录。PR #182（#159）和 PR #185（#139）已合并 |
| [#170](https://github.com/Sumire-no-kai/Vesqen/issues/170) F8–F10 | 随机播放改用自定义 `ShuffleOrder`：队列编辑没有 masking、每次插入重建两次平台队列、masking 时队列短暂按列表顺序显示（PR #183）。进程被结束后保留随机顺序（1.0.1 的已知限制）：`playback-state` 增加 `play_order` 和 `play_order_signature` 两个键，两条恢复路径都按保存的顺序恢复（PR #184，基于 #183）。两个 PR 均已合并 |
| 审查遗留（[#136](https://github.com/Sumire-no-kai/Vesqen/issues/136)、[#143](https://github.com/Sumire-no-kai/Vesqen/issues/143)、[#156](https://github.com/Sumire-no-kai/Vesqen/issues/156)、#165） | 先做会丢数据或让操作没有反应的：暂停后进程被结束，恢复时同一首多算一次播放；恢复队列时暂时读不到的曲目被永久去掉；每 5 秒的进度检查点重写整个队列；数据库损坏时默认直接删除文件；扫描期间整个曲库被锁住（#136 R12）；扫描期间的媒体变化让很长的扫描一直完不成（R15）；详情页"播放"在播放次数更新后没有反应（R28）；链路页经过别的页面再回来时丢了返回目标（#156 V25）。R13、R14、R18、R22、#143 Q15、#156 V24 先核实是否已被后来的修复覆盖 |

所有者已定（2026-10-29 至 10-30）：

- A36（#165）：两个渠道共用包名和版本号序列。1.1.0 如果改表结构（#139），Play 版还停在 1.0.x，以后 Play 的 1.0.x 用更高的版本号覆盖安装 GitHub 1.1.0 时打不开数据库。改表结构之前要定版本号区间和降级处理；收费开关按渠道分开可以留到 2.0 之前。2026-10-29 所有者决定：1.1.0 不改表结构；继续共用一条版本号序列，编号更高的版本一定用更新的代码，Play 只发 GitHub 也发过的同一版本，1.1.0 之后的修复版只出 1.1.x；版本号区间和收费开关留到 2.0 之前再定。
- [#161](https://github.com/Sumire-no-kai/Vesqen/issues/161)：中文"高级功能"和"高级链路"撞名。2026-10-29 所有者同意改名，随 1.1.0 改，已提交。
- 新建队列沿用当前的随机和循环设置（#165 播放）。"清空队列"保持一次点击，之后提供撤销（#136 R7）。迷你播放条标题保持单行截断，不滚动（#143 Q13，对比华为音乐和网易云音乐后决定）。以上均在 PR #187。

所有者测试前：文案、两轮以上的深度审查和安全检查按上面的收尾顺序完成（2026-10-31 已完成）。

所有者测试时，iQOO 和 Honor 都要检查：
- 从 1.0.1 覆盖升级；
- 真实结束进程后恢复，包括随机顺序和播放计数；
- 清除媒体存储后对不上的记录（#159、#139）；
- #185 的提示在 320 dp、大字号和深色下的显示；
- #187 的撤销和播放模式；
- #169 歌词：四种语言、2.0 倍字号、深色、横屏和读屏，以及同名 `.lrc` 的设备测试（需要所有者在测试版中添加测试文件夹）。

Honor 测试前要重启共用的 adb 服务，会短暂影响其他会话，先问所有者。

发 1.1.0 时：
- `versionName` 1.1.0、`versionCode` 15；
- 重新生成 Baseline Profile，加一步"打开歌词"；
- 发布说明，是否提及歌词由所有者定；
- 官网更新日志；"已知问题与计划"页和 #163 去掉已修好的条目。

四种语言的全部应用内文案统一为书面正式用语，不用口语化表述（2026-10-29 所有者要求，例如按钮用"确定"而非"知道了"）。2026-10-30 所有者把这一检查交给协调会话，发现问题直接修改。

### 1.1.0 之后、2.0 之前

2026-10-31：从 1.1.0 到 2.0 的版本排期（暂定）见 [#210](https://github.com/Sumire-no-kai/Vesqen/issues/210)，包括 1.1.1、可能的 1.2.0、M7 各阶段、2.0 开卖准备和 2.0 的关卡。1.1.0 的遗留（D20、S2、B5、官网两项核对）在 [1.1.1 里程碑](https://github.com/Sumire-no-kai/Vesqen/milestone/7)。

| Issue | 内容 |
| --- | --- |
| [#147](https://github.com/Sumire-no-kai/Vesqen/issues/147) | 按音质筛选。入口、读不到采样率的曲目、有损格式的条件都没定，暂不排期 |
| PRD | 睡眠定时器（PRD"稳定版后续的小型功能"），还没有 issue |
| [#191](https://github.com/Sumire-no-kai/Vesqen/issues/191) D20 | 曲库重建后的文件夹授权：目前要用户重新添加文件夹，原有系统授权保留。建议按保留的授权自动加回文件夹并改提示文案，动手前与所有者确认 |
| [#194](https://github.com/Sumire-no-kai/Vesqen/issues/194) S2、B5 | 开放销售前：Worker 发放激活码前核对商品、币种和金额（需要所有者定商品并部署）；Pro 页提供替换已生效激活码的入口；官网核对下载按钮读取的版本文件和购买成功页地址中的订单编号 |
| #136、#143、#156、#165 | 1.1.0 没挑中的审查遗留：链路和遥测的判断与文案、界面和无障碍的小问题、CI 跑设备测试和编译 `:baselineprofile`、`UsbOutputCoordinator` 单元测试、依赖校验、架构整理 |

### Google Play 正式版

封闭测试满 14 天后约 2026-10-30 申请正式版，审核一般 7 天以内，上架当时最新的 1.0.x（1.0.1 按期发布就直接用 1.0.1）。Play 版此后留在 1.x（[商业模式](MONETIZATION.md)"GitHub 优先"）。

### 2.0：不定日期

前提是 M7（#71）在没有官方 bit-perfect 通路的参考手机（iQOO）上实测达到 `BIT-PERFECT ACTIVE`。在那之前只记录进度，不估日期；M7 有实测结果后再排。随 2.0 的内容见下面两张表。M7 的分阶段清单和待定事项见 [M7 开发清单](M7_IMPLEMENTATION_PLAN.md)。

## 2.0 之前

| Issue | 内容 |
| --- | --- |
| [#49](https://github.com/Sumire-no-kai/Vesqen/issues/49) | 真实 DAC 矩阵与外部数字逐样本验证（BIT-PERFECT VERIFIED） |
| [#71](https://github.com/Sumire-no-kai/Vesqen/issues/71) | M7 高级 USB 引擎：没有官方 bit-perfect 通道的手机也能严格直出 |
| [#65](https://github.com/Sumire-no-kai/Vesqen/issues/65) | 严格 USB：16 位音源补零后送入 24 位 bit-perfect 通道 |

2.0 开始收费（2026-10-13 所有者决定），买断先在 GitHub 版上线（2026-10-19 [商业模式](MONETIZATION.md)"GitHub 优先"），以下随 2.0 完成：

| Issue | 内容 |
| --- | --- |
| [#120](https://github.com/Sumire-no-kai/Vesqen/issues/120) | 官网购买页、付款成功页和激活页（官网 PR #7），隐私政策购买一节，条款和退款政策，正式签名密钥和激活码，Stripe 正式开通 |
| | 定价（付费功能表已定，见[商业模式](MONETIZATION.md)），打开收费开关 `vesqen.premiumEnforced` |

## 以后再评估

2026-10-13 移出 1.0：GitHub 版要在官网卖激活码，F-Droid 会重新签名（更新器用不了），默认开启的统计会被标成 Tracking，它对付费解锁的态度也还没查。

| Issue | 内容 |
| --- | --- |
| [#74](https://github.com/Sumire-no-kai/Vesqen/issues/74) | 评估上架 F-Droid 和 IzzyOnDroid（可能需要无统计、无更新器的构建） |

## Google Play

封闭测试 2026-10-13 起用 beta.2，2026-10-16 更新为 beta.3。#44（商品详情）、#47（构建拆分）、#56（前台服务申报）已关闭。

| Issue | 内容 |
| --- | --- |
| [#45](https://github.com/Sumire-no-kai/Vesqen/issues/45) | 封闭测试 12 人连续 14 天，然后申请正式版 |
| [#46](https://github.com/Sumire-no-kai/Vesqen/issues/46)、[#48](https://github.com/Sumire-no-kai/Vesqen/issues/48) | 应用内解锁和 Billing 组件的披露。不随 2.0：按[商业模式](MONETIZATION.md)"GitHub 优先"（2026-10-19），2.0 的买断先在 GitHub 版上线（#120），Play 版大概率停在 1.x、只做小修；Play 版是否收费以后再定 |

## 依赖关系

- #45 依赖 beta.2（#69 设备报告）、#44、#56，以及 #47 的构建拆分。Play 构建不能包含 #68 的自行更新和“安装未知应用”权限。
- #44 的截图要等 #35 重做完成后再拍。
- #70 上线前，隐私政策要先改好并完成核查；#69 的上传接口也依赖 #70 的服务端。
- #46 依赖 #48；只有决定在 Play 版收费时才做，见上面"Google Play"一节。
- #49 需要一台开放了官方 bit-perfect 通道的手机，或者走 #71 的路径。
- #74 以后再评估时，和 #47 的构建拆分一起考虑。
- #72 的文案和版式随 #35 的链路页一起做。#73 要靠 #69 的设备报告收集各 ROM 能否收到 codec 广播。
