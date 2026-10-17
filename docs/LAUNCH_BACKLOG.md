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
- [#52](https://github.com/Sumire-no-kai/Vesqen/issues/52)（PR #126）：进程被结束后，按耳机或蓝牙的播放键恢复队列并播放。两台测试手机上按键都没有到达 Vesqen，发布说明不列为新功能，跟进在 [#129](https://github.com/Sumire-no-kai/Vesqen/issues/129)。
- 首次使用的三处界面修正。所有者在 Play 版上发现，直接提交（`0df5828` 到 `9a4e6f2`）：
  - 开屏的使用统计页：答复固定在第一屏，只有说明文字在放不下时滚动；
  - 没有授权的空曲库：主按钮改为"允许访问设备音乐"，"添加音乐文件夹"改为次要按钮；
  - 列表原本在最顶上时，扫描后仍停在最顶上。

还要做：

| Issue | 内容 |
| --- | --- |
| [#113](https://github.com/Sumire-no-kai/Vesqen/issues/113) | 崩溃后下次打开时询问是否发送错误报告（PR #125，等所有者审隐私政策） |

发版前：

- 真机验收上面已经合并的内容：2026-10-14 完成，界面测试在 iQOO 上全部通过，签名草稿在 Honor 上从 beta.2 升级并保留数据（`docs/M4_DEVICE_ACCEPTANCE.md`）。
- beta.3 是用户第一次通过应用内更新器升级，要完整测一遍从 beta.2 升到 beta.3。
- 发布说明写上这三处修正和"高级功能"的说明，写明"2.0 之前免费"；耳机键恢复只写进已知限制（#129）。
- 官网同步加一句收费说明（官网 `docs/SITE_PLAN.md`）。
- Play Console 商品详情里"测试版"那一段换成 [`PLAY_LISTING.md`](PLAY_LISTING.md) 的新文字（"从 2.0 起……"）。旧文字写的是 1.0 收费，已经上传过（所有者 2026-10-13 要求 beta.3 上线时提醒）。

## 1.0 正式版之前

2026-10-16 所有者确定（[PRD](PRD.md) 末尾"2026-10-16 1.0 的范围"）：`1.0.0` 是 Google Play 正式版的第一版，GitHub 同时发布同一个版本；严格 USB 的设备矩阵写成公开限制，随 2.0 的 M7 完成（#53）；增加日语和繁体中文（#75）；系统报告的蓝牙 codec 先做实验（#73）。

上线时间取决于 Play：2026-10-16 查看时封闭测试已满 12 人加入，连续 14 天后申请正式版，审核一般 7 天以内。申请表要写测试者反馈的总结。

| Issue | 还要做 |
| --- | --- |
| [#75](https://github.com/Sumire-no-kai/Vesqen/issues/75) | 繁体中文和日语已合并（PR #131、#132）；日语还没有母语者审读，发布前决定是否带上 |
| [#73](https://github.com/Sumire-no-kai/Vesqen/issues/73) | 链路页和链路注记显示系统报告的蓝牙编码，有线显示输出格式；实验通过，2026-10-16 决定进 1.0 |
| [#50](https://github.com/Sumire-no-kai/Vesqen/issues/50) | 曲库首页快速滑动：两台手机修复前后各三轮对比，加上大曲库压力测试（M3-R1） |
| [#51](https://github.com/Sumire-no-kai/Vesqen/issues/51) | 8 小时有线播放已通过；还缺新系统上的关屏长播、2 小时 24/96、拔耳机和来电 |
| [#35](https://github.com/Sumire-no-kai/Vesqen/issues/35) | 按 beta.3 复核界面和文案（字号、深浅色、减少动效），加完整的 TalkBack |
| [#54](https://github.com/Sumire-no-kai/Vesqen/issues/54) | 仓库与文档整理；删除 `codex/*` 远程分支前先问所有者 |
| [#129](https://github.com/Sumire-no-kai/Vesqen/issues/129) | 在原生 Android（例如 Pixel）上验证耳机键恢复（#52） |

发布 1.0 时：

- 版本号改成 `1.0.0`（`versionCode` 13），写发布说明，重新签名，从 beta.3 做升级验收。
- 发布前先部署服务端（`server/usage`），让它认识设备报告词表里新加的蓝牙编码字段（#73），否则带这些字段的报告会被拒收。官网隐私政策同步到 10 月 16 日版（`npm run sync-privacy`）。
- 正式通道第一次启用：官网 `updates/stable.json` 第一次填上版本；GitHub 发布不标预发布。
- 发布说明、README、官网和 Play 商品详情写明严格 USB 的限制（#53）。
- Play：商品详情去掉"测试版"那一段，发布到正式版，建议分阶段放量。
- 官网下载区改成 Google Play 和 GitHub 两个入口，README 状态同步。

## 2.0 之前

| Issue | 内容 |
| --- | --- |
| [#49](https://github.com/Sumire-no-kai/Vesqen/issues/49) | 真实 DAC 矩阵与外部数字逐样本验证（BIT-PERFECT VERIFIED） |
| [#71](https://github.com/Sumire-no-kai/Vesqen/issues/71) | M7 高级 USB 引擎：没有官方 bit-perfect 通道的手机也能严格直出 |
| [#65](https://github.com/Sumire-no-kai/Vesqen/issues/65) | 严格 USB：16 位音源补零后送入 24 位 bit-perfect 通道 |

2.0 开始收费（2026-10-13 所有者决定），以下随 2.0 完成：

| Issue | 内容 |
| --- | --- |
| [#46](https://github.com/Sumire-no-kai/Vesqen/issues/46)、[#48](https://github.com/Sumire-no-kai/Vesqen/issues/48) | Play 版应用内购买和 Billing 组件的披露 |
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
| [#46](https://github.com/Sumire-no-kai/Vesqen/issues/46)、[#48](https://github.com/Sumire-no-kai/Vesqen/issues/48) | 应用内解锁和 Billing 组件的披露，随 2.0 |

## 依赖关系

- #45 依赖 beta.2（#69 设备报告）、#44、#56，以及 #47 的构建拆分。Play 构建不能包含 #68 的自行更新和“安装未知应用”权限。
- #44 的截图要等 #35 重做完成后再拍。
- #70 上线前，隐私政策要先改好并完成核查；#69 的上传接口也依赖 #70 的服务端。
- #46 依赖 #48，随 2.0 上线；收费方案 2026-10-13 已确定，见[商业模式](MONETIZATION.md)。
- #49 需要一台开放了官方 bit-perfect 通道的手机，或者走 #71 的路径。
- #74 以后再评估时，和 #47 的构建拆分一起考虑。
- #72 的文案和版式随 #35 的链路页一起做。#73 要靠 #69 的设备报告收集各 ROM 能否收到 codec 广播。
