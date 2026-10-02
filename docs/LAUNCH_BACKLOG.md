# 发布路线与待办

`1.0.0-beta.1` 于 2026-10-01 在 [GitHub Releases](https://github.com/Sumire-no-kai/Vesqen/releases/tag/v1.0.0-beta.1) 发布（预发布），官网首页提供下载。本文件按阶段索引剩下的事项，以 GitHub 里程碑为准：[beta.2](https://github.com/Sumire-no-kai/Vesqen/milestone/2)、[1.0 正式版](https://github.com/Sumire-no-kai/Vesqen/milestone/1)、[2.0](https://github.com/Sumire-no-kai/Vesqen/milestone/3)、[Google Play](https://github.com/Sumire-no-kai/Vesqen/milestone/4)。对外声明基线（[M4 Beta 发布清单](M4_BETA_RELEASE.md)）继续适用。

2026-10-01 所有者决定（详见 [PRD](PRD.md) 末尾“2026-10-01 beta.1 发布后的路线决定”）：

- 正式版 1.0 不要求 `BIT-PERFECT VERIFIED`，写成公开限制；最迟在 2.0 之前完成，M7 是 2.0 的必做项。
- Google Play 封闭测试最早使用 beta.2，因为 beta.2 才有设备报告。
- beta.2 起申请联网权限，用于默认开启、可以关闭的匿名统计、设备报告上传和检查更新；服务端用现有的 Cloudflare 账号。
- 收费方案在正式版准备上架前再讨论。

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

发布 beta.2 时，隐私政策（应用内和官网）、README、官网和发布说明里“应用不申请联网权限”的说法要一起改写。#35 的视觉方向见[官网与界面重设计方向](redesign/README.md)。

2026-10-02 起 Vesqen 面向全球用户，从 beta.2 开始在少数目标社区传播，大范围宣传留到 1.0。传播口径、地区和渠道的暂定安排见 [PRD](PRD.md) 末尾“2026-10-02 全球用户与对外传播”。

## 1.0 正式版之前

| Issue | 内容 |
| --- | --- |
| [#50](https://github.com/Sumire-no-kai/Vesqen/issues/50) | 曲库首页快速滑动性能的双机前后对比（M3-R1） |
| [#51](https://github.com/Sumire-no-kai/Vesqen/issues/51) | 长时播放与中断测试 |
| [#52](https://github.com/Sumire-no-kai/Vesqen/issues/52) | 进程被杀后通过耳机键或系统媒体控制恢复播放队列 |
| [#53](https://github.com/Sumire-no-kai/Vesqen/issues/53) | 决定 1.0 的版本号与对外定位 |
| [#54](https://github.com/Sumire-no-kai/Vesqen/issues/54) | 仓库与文档整理：发版复审记录、远程分支、签名文档中的本机路径 |
| [#73](https://github.com/Sumire-no-kai/Vesqen/issues/73) | 链路页显示系统报告的蓝牙 codec（可选证据） |
| [#74](https://github.com/Sumire-no-kai/Vesqen/issues/74) | 评估上架 F-Droid 和 IzzyOnDroid（可能需要无统计、无更新器的构建） |
| [#75](https://github.com/Sumire-no-kai/Vesqen/issues/75) | 评估增加日语等界面语言 |

## 2.0 之前

| Issue | 内容 |
| --- | --- |
| [#49](https://github.com/Sumire-no-kai/Vesqen/issues/49) | 真实 DAC 矩阵与外部数字逐样本验证（BIT-PERFECT VERIFIED） |
| [#71](https://github.com/Sumire-no-kai/Vesqen/issues/71) | M7 高级 USB 引擎：没有官方 bit-perfect 通道的手机也能严格直出 |
| [#65](https://github.com/Sumire-no-kai/Vesqen/issues/65) | 严格 USB：16 位音源补零后送入 24 位 bit-perfect 通道 |

## Google Play（暂缓，最早在 beta.2 之后）

| Issue | 内容 |
| --- | --- |
| [#45](https://github.com/Sumire-no-kai/Vesqen/issues/45) | 在 Play Console 创建应用并完成 12 人、14 天封闭测试（含填写隐私政策网址） |
| [#44](https://github.com/Sumire-no-kai/Vesqen/issues/44) | 商品详情与上架材料，见 [Google Play 上架材料](PLAY_LISTING.md) |
| [#56](https://github.com/Sumire-no-kai/Vesqen/issues/56) | 前台服务申报（`mediaPlayback`，需要演示视频） |
| [#46](https://github.com/Sumire-no-kai/Vesqen/issues/46) | 14 天试用与一次性应用内解锁（Play 构建） |
| [#47](https://github.com/Sumire-no-kai/Vesqen/issues/47) | GitHub / Play 构建拆分（Billing 只进 Play 构建，自行更新只进 GitHub 构建） |
| [#48](https://github.com/Sumire-no-kai/Vesqen/issues/48) | 确认 Billing 带入的 Google 日志组件，写进隐私政策和数据安全表单 |

## 依赖关系

- #45 依赖 beta.2（#69 设备报告）、#44、#56，以及 #47 的构建拆分。Play 构建不能包含 #68 的自行更新和“安装未知应用”权限。
- #44 的截图要等 #35 重做完成后再拍。
- #70 上线前，隐私政策要先改好并完成核查；#69 的上传接口也依赖 #70 的服务端。
- #46 依赖 #47 和 #48；收费方案在正式版准备上架前讨论。
- #49 需要一台开放了官方 bit-perfect 通道的手机，或者走 #71 的路径。
- #74 和 #47 的构建拆分一起考虑。
- #72 的文案和版式随 #35 的链路页一起做。#73 要靠 #69 的设备报告收集各 ROM 能否收到 codec 广播。
