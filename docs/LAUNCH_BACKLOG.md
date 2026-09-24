# 正式上架前待办

2026-09-24 决定：`1.0.0-beta.1` 封版，Play 封闭测试使用的版本不做任何修改。下列事项必须在 Google Play 正式版上架前关闭，或者在对应 issue 中写明处置结论（例如作为公开限制写进商品详情和发布页）。

以 GitHub 里程碑[正式上架前](https://github.com/Sumire-no-kai/Vesqen/milestone/1)为准，本文件只是按时间和依赖整理的索引。对外声明基线（[M4 Beta 发布清单](M4_BETA_RELEASE.md)）继续适用。

## 上传封闭测试之前

| Issue | 内容 | 由谁完成 |
| --- | --- | --- |
| [#40](https://github.com/Sumire-no-kai/Vesqen/issues/40) | 确定开发者名称和公开邮箱；账号主体可以等到收费前 | 所有者决定 |
| [#41](https://github.com/Sumire-no-kai/Vesqen/issues/41) | 第二份签名备份和离机密码保管 | 所有者 |
| [#42](https://github.com/Sumire-no-kai/Vesqen/issues/42) | 冻结候选的真机回归与证据重建，含 iQOO + JBL Flip 7 USB 短测 | 所有者连接设备，开发执行 |
| [#38](https://github.com/Sumire-no-kai/Vesqen/issues/38) | 隐私政策定稿：填写占位信息并多方核查 | 开发 |
| [#43](https://github.com/Sumire-no-kai/Vesqen/issues/43) | 官网发布隐私政策页面（Play Console 必填） | 开发 |
| [#44](https://github.com/Sumire-no-kai/Vesqen/issues/44) | 商品详情与上架材料（基础部分） | 开发 |
| [#45](https://github.com/Sumire-no-kai/Vesqen/issues/45) | 在 Play Console 创建应用并完成 12 人、14 天封闭测试 | 所有者 |
| [#36](https://github.com/Sumire-no-kai/Vesqen/issues/36) | 应用内隐私政策（中英文全文 + 网页链接），作为冻结例外加入 beta.1。已由 #55 完成 | 开发 |
| [#56](https://github.com/Sumire-no-kai/Vesqen/issues/56) | Play Console 前台服务申报（`mediaPlayback`，需要演示视频） | 开发准备视频，所有者提交 |
| [#57](https://github.com/Sumire-no-kai/Vesqen/issues/57) | Android 开发者验证：为 GitHub 分发登记包名和签名（2026-09-30 起在部分地区生效，2027 年全球） | 所有者 |

应用内隐私政策的文本在构建时从 `docs/PRIVACY_POLICY*.md` 打包。发布守卫 `checkPrivacyPolicyFinal` 会在文本仍有草稿标记或占位、或者网页地址为空时，拒绝生成上传 Play 的包。因此 #38 和 #43 必须在上传前完成。

## 封闭测试期间（beta.2）

| Issue | 内容 | 类型 |
| --- | --- | --- |
| [#32](https://github.com/Sumire-no-kai/Vesqen/issues/32) | `<unknown>` 占位值被当作艺术家原样显示 | bug |
| [#33](https://github.com/Sumire-no-kai/Vesqen/issues/33) | 链路页观测时间只按秒显示，旧数据被当作"当前播放链路" | bug |
| [#34](https://github.com/Sumire-no-kai/Vesqen/issues/34) | 严格 USB 模式下，冷启动即弹出失败对话框 | bug |
| [#35](https://github.com/Sumire-no-kai/Vesqen/issues/35) | 界面与文案全面重新审核（可读性、美观性、一致性），并修复已发现的界面问题 | 界面 |
| [#37](https://github.com/Sumire-no-kai/Vesqen/issues/37) | 应用内第三方开源许可声明 | 合规 |
| [#39](https://github.com/Sumire-no-kai/Vesqen/issues/39) | 备份与换机迁移规则仍是模板 | 数据 |
| [#46](https://github.com/Sumire-no-kai/Vesqen/issues/46) | 14 天试用与一次性应用内解锁（Play 构建） | 收费 |
| [#47](https://github.com/Sumire-no-kai/Vesqen/issues/47) | 不含 Billing 的 GitHub 构建变体 | 收费 |
| [#48](https://github.com/Sumire-no-kai/Vesqen/issues/48) | 真机验证含 Billing 的构建能否移除 INTERNET | 收费、隐私 |

## 正式上架之前

| Issue | 内容 |
| --- | --- |
| [#49](https://github.com/Sumire-no-kai/Vesqen/issues/49) | 真实 DAC 矩阵与外部数字逐样本验证（BIT-PERFECT VERIFIED） |
| [#50](https://github.com/Sumire-no-kai/Vesqen/issues/50) | 曲库首页快速滑动性能的双机前后对比（M3-R1） |
| [#51](https://github.com/Sumire-no-kai/Vesqen/issues/51) | 长时播放与中断测试 |
| [#52](https://github.com/Sumire-no-kai/Vesqen/issues/52) | 进程被杀后通过耳机键或系统媒体控制恢复播放队列 |
| [#53](https://github.com/Sumire-no-kai/Vesqen/issues/53) | 决定正式上架的版本号与定位（PRD 稳定版门槛） |
| [#54](https://github.com/Sumire-no-kai/Vesqen/issues/54) | 仓库与文档整理：发版复审记录、`codex/*` 远程分支、签名文档中的本机路径 |

## 依赖关系

- #36 的发布守卫要求 #38（定稿文本）和 #43（网页地址）在上传前完成。
- #45 依赖 #40、#41、#42、#43、#44、#56。
- 首次在 GitHub 发布 APK 之前完成 #57。
- #46 依赖 #47（Billing 只进 Play 构建）和 #48（联网权限的结论）。
- #44 的截图要等 #32 和 #33 修好之后再拍，否则会露出 `<unknown>` 等问题。
- #53 取决于 #49 能否在上架前完成。
