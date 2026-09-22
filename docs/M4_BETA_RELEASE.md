# M4 公开 Beta 交付检查表

用户已于 2026-09-10 决定发布受限 Beta，目标渠道为 GitHub Releases（签名 APK）和 Google Play 测试渠道（AAB）。第二款 DAC 暂时缺失，不阻挡此次受限发布；相关矩阵仍未验证。只有满足 [PRD](PRD.md)、[M4 设备验收](M4_DEVICE_ACCEPTANCE.md)及保留的 M2/M3 门禁后，才可把候选称为完整 M4。发布决定不等于安装包已经发布。

## 当前冻结候选

- 版本：`1.0.0-beta.1` / versionCode `10`。这是通向首个稳定版 `1.0.0` 的首个公开 Beta，不代表稳定版已经验收。
- Verification issuer：`vesqen.output_verification.2026_01`；固定 SPKI SHA-256 为 `35619e5cc562b23282aa5bce0aa4e6ba6221e40b97522d96d91ea5c07daf0db4`。私钥位于仓库外，且不复用 APK 更新签名。
- Application signing：`vesqen-app-signing-v1`；证书 SHA-256 为 `74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93`。它将用于 GitHub APK，并在首次 Play App Signing 配置时作为同一应用身份导入。
- Play upload：`vesqen-play-upload-v1`；证书 SHA-256 为 `6A:78:82:9F:9C:74:FA:CE:FB:CE:C4:62:57:CF:9B:E6:01:55:1F:3B:86:49:E6:1E:14:6B:1A:98:20:06:31:5B`。它只用于提交 AAB，不是用户设备上的更新签名。三套身份和本地保管边界见 [发布签名记录](RELEASE_SIGNING.md)。
- 冻结后不接受新功能、普通 UI 优化或非必要重构。只有崩溃/ANR、数据丢失、无法播放、严重卡顿、错误 bit-perfect 声明、关键页面不可操作、签名或安装升级失败可进入当前发布线。
- 任何已发布的 tag/APK/AAB 不可替换。发布后的阻断修复使用新版本，例如 `1.0.0-beta.2` / `11`，并同步更新发布说明与标签。
- `0.4.0-beta.1` / `9` 的设备与构建记录保留为历史证据；因验证记录精确匹配应用版本和 APK 哈希，不能直接充当新候选的产物身份证据。

## 本次发布顺序

1. 收录已合并修复和适用证据，补齐可用设备的短时回归；已有且仍适用的测试不重复执行。
2. 在 iQOO + JBL Flip 7 上确认 USB 识别、能力、实际路由、格式变化及拔插/失败清理；记录无法取得的证据。第二款 DAC 和外部数字逐样本组合留作公开限制，不用扬声器听感代验。
3. 完成隐私/权限说明、EN/ZH 发布说明、安装升级与支持范围准备；公开 Release 不提供诊断开关、录制或导出入口，问题反馈不要求用户自行开启日志。
4. Application signing、Play upload 与离线 verification issuer 已分别创建并完成本机 Keychain 回读、开库和私钥使用验证；私钥、alias 对应密码均留在仓库外。最终发布前仍须完成加密离线备份、Play App Signing 导入与证书对账，生成并核验签名 APK/AAB，在安装候选上核验数据保留和无诊断入口，再交付两个渠道。Play 账号准入与定价路线已于 2026-09-22 确认，见下节。

第二款 DAC 不再是本次受限 Beta 的阻断项；崩溃/ANR、数据丢失、错误输出声明、签名/安装升级失败及 Release 诊断开关仍阻断发布。

## Google Play 定价与封闭测试

2026-09-22 确认：发布账号是约 2026-08 注册的个人 Play Console 账号，晚于 2023-11-13，适用[新个人开发者账号的测试要求](https://support.google.com/googleplay/android-developer/answer/14151465)。上架正式版前必须完成封闭测试：至少 12 名测试者连续加入至少 14 天，之后单独申请正式版权限。发布模式为 Play 付费下载（买断），这次封闭测试同时承载受限 Beta 的 Play 部分。

- **先设价，再上传。** 应用一旦免费提供过就不能改为付费，只能换新包名；定价对所有轨道同时生效（[定价说明](https://support.google.com/googleplay/android-developer/answer/6334373)）。官方没有说明免费发布到测试轨道是否触发这一锁定，因此首次向任何 Play 轨道上传 AAB 之前，必须已建立付款资料并把 `io.github.sumirenokai.vesqen` 设为付费，不得为方便测试临时设为免费。
- **测试者获取应用的方式。** 付费应用在封闭和公开测试中需要测试者购买，只有内部测试可以免费安装（[测试轨道说明](https://support.google.com/googleplay/android-developer/answer/9845334)）。按以下顺序处理：
  1. 优先使用促销码。促销码支持付费应用，非订阅类合计每季度最多 500 个（[促销说明](https://support.google.com/googleplay/android-developer/answer/6321495)）；正式版上线前能否兑换没有官方说明，先用一个测试账号实测并记录结论。
  2. 促销码不可用时，封闭测试期间设较低的付费价格，由测试者购买；提前告知测试者不要在购买后 2 小时内自行退货。
  3. 14 天期满且正式版权限获批后，由开发者在 Order management 统一全额退款。付费应用只支持全额退款，Google 服务费随退款返还（[退款说明](https://support.google.com/googleplay/android-developer/answer/2741495)）。官方没有说明退款是否收回应用以及开发者退款的时限，因此测试期内不退款，获批后也不拖延。
  4. 正式上线前改为正式价格。付费应用可以涨价，改价需要数小时才在 Google Play 生效。
- **测试者范围。** 测试者需要 Google 账号，并位于封闭测试开放的国家或地区；只有内部测试不受地区限制。中国大陆无法使用 Google Play。招募人数高于 12 人，避免中途有人退出后连续满 14 天的人数不足。
- 仓库只记录测试人数、起止日期、促销码实测结论和退款完成状态，不记录测试者身份、邮箱或订单号。

## 对外声明基线

- 产品继续使用 Media3 与 Android 公共 API；没有自定义 USB 驱动、native 播放内核或跨设备通用 bit-perfect 保证。
- `AVAILABLE` 只表示当前能力候选，`ACTIVE` 只表示应用请求、AudioTrack、mixer readback 和 route 的 Android 侧观察一致；只有签名记录精确匹配当前构建/手机/ROM/DAC/格式时才显示 `VERIFIED`。
- 不以 DAC 屏幕、听感、采样率相同、AudioTrack request 或模拟录音代替外部数字证据。未验证组合公开写“未验证”，不写“理论支持”。
- Chain 和诊断中的 unavailable、confidence、source、时间和隐私过滤仍是产品契约。

## 候选发布说明模板

### 新增

- 可导入由独立、版本化 verification issuer 签署的离线输出验证记录；精确匹配时，播放器、Chain 和 Audio Proof 显示 `BIT-PERFECT VERIFIED`。
- M4 确定性 PCM 测试向量、记录校验/签名流程及设备/性能采集工具。
- Settings 显示 registry 状态和失败原因；Chain 可追溯 record id 与验证方法。

### 修复

- 360 dp、150% 英文字体下播放会话的 “Playback progress” 标签改为自适应堆叠，避免省略。
- registry 有界读取覆盖 API 26+，未知/重复/超限/错误签名文档 fail closed 且不会替换已有有效记录。
- PR #27 修复 Library 内部与跨页面的滚动位置恢复、集合消失时的状态冲突，以及 Chain 动态数值换行；这一功能修复不代替快滑性能验收。

### 已知限制（发布前按证据更新）

- 当前没有完成两台 Android 14+ 手机 × 两款 DAC 的严格 USB 矩阵，也没有外部数字逐样本 VERIFIED 组合时，不得宣传完整 bit-perfect 支持。
- Honor Android 9 只能验证普通输出与旧系统兼容，不能验证 Android 14 mixer。
- iQOO Android 15 已验证无 DAC 时严格模式 fail closed、适用 UI/生命周期、Profile 启动基线和数据保留；没有真实 DAC 时仍不能验证严格 USB `ACTIVE` 或任何 `VERIFIED` 组合。
- Library 首页卡顿已完成 R8 构建修复，并移除播放历史触发整库刷新等一条业务路径；最终候选的 Honor/iQOO 配对 A/B、大曲库与实际高刷新率门禁仍开放，因此 M3-R1 未关闭。
- 进程死亡后的队列检查点目前在重新打开应用并取得曲库后恢复；仅靠耳机键或系统 MediaSession resumption 的无 Activity 恢复尚未承诺。
- 长时播放、完整外设/中断、折叠屏、高刷新率和完整 TalkBack 语音矩阵按设备与用户安排继续开放。

## 发布门禁

- [ ] 所有 Beta blocker 为 0；失败 runner 有结论和修复后独立复测，未通过项没有被删除或弱化。
- [ ] `version.properties` 使用经审阅的 SemVer 与递增 versionCode；release notes 与 About 一致。
- [ ] JDK 25 下 unit、Python 工具测试、lint、Debug/Profile/Release、instrumentation compile 通过；CI 通过。
- [x] Application signing、Play upload 和 verification issuer 为三个独立身份；两份 Android PKCS12 的 Keychain 密码回读、别名查找、私钥 CSR 签名和公共指纹核对通过，公共证书已入库，私钥与密码未进入仓库。
- [ ] Application signing 与 Play upload 私钥均完成受控加密离线备份和恢复核验；2026-09-21 已完成第一份可移动介质 AES-256 加密副本及只读恢复验证，第二份独立副本和异机密码保管仍待完成。Play App Signing 导入后，Play 应用签名证书与 GitHub APK 证书一致，上传证书保持独立。
- [ ] 候选 APK/Bundle 使用预期发布签名，证书指纹和 base APK SHA-256 记录；keystore、alias 和密码不进入仓库或日志。
- [ ] 专用 verification issuer 与 APK 更新签名相互独立；应用固定受审阅的 issuer 公钥和稳定 `keyId`，私钥不进入仓库。未配置 issuer 的构建必须明确拒绝 registry，不能回退信任 APK signer。
- [ ] 若已有公开版本，从该版本执行保留数据升级；若为首次公开发布，记录无上一公开版本，并用同一长期签名的前后候选验证升级。核对曲库稳定 ID、收藏、历史、歌单、手动排序、SAF grant、队列 checkpoint、输出模式和 registry；不以卸载重装替代升级。现有 Debug/Profile 使用开发签名，不能视为新生产签名的直接升级来源，也不能卸载用户数据来规避签名不匹配。
- [ ] 新装、升级、系统回收/重启、存储不可用与回滚限制分别记录；数据库或持久格式变化另做兼容审查。
- [ ] EN/ZH、窄屏/横屏/大字、深浅主题、减少动效、触控目标、对比度与 TalkBack 的适用矩阵通过或公开列限。
- [ ] Privacy：最终合并 Manifest 不含 INTERNET；如依赖声明 ACCESS_NETWORK_STATE，准确说明它只用于查询网络连接状态。无后台上传新增。Release 不提供诊断启用、录制或导出入口；Debug/Internal 导出仍须显式操作并按策略脱敏。
- [ ] 支持范围、无需开启日志的问题复现步骤、已知限制和回退方式写入发布页；不得要求 Release 用户寻找隐藏诊断开关。
- [x] 无外部数字 VERIFIED 组合时发布受限 Beta 的决定已获用户确认（2026-09-10）；第二款 DAC 缺失公开列限。标题/文案不得暗示 M4 完成，原始 M4 验收门禁继续保留。
- [ ] 首次向任何 Play 轨道上传前，付款资料已建立且应用已设为付费；应用从未以免费形式在任何轨道提供。
- [ ] 封闭测试至少 12 名测试者连续加入满 14 天并获得正式版权限；促销码实测结论、测试者购买的退款完成情况和正式价格调整分别记录。
- [ ] 合并、tag、远端 release、商店/分发上传和用户采用分别确认，不从本地构建或 CI 自动推断。

## Blocker 分级

| 等级 | 示例 | 处理 |
| --- | --- | --- |
| Blocker | 数据丢失、错误 VERIFIED、严格失败后继续播放、签名绕过、崩溃/ANR、发布签名或升级失败 | 不发布；修复并用同一候选重新执行相关矩阵。 |
| Major | 核心播放/扫描/队列异常、主要操作不可达、严重可访问性或设备兼容回归 | 原则上阻断，除非用户明确缩小 Beta 支持范围并公开限制。 |
| Minor | 不影响核心流程且有明确规避方式的视觉或文案问题 | 记录、排期，并确认不会误导证据声明。 |

## 交付记录

发布决定时追加：候选 commit、分支/PR、版本、产物哈希、签名证书指纹、CI URL、设备矩阵、release URL、发布时间与回滚点。没有执行的字段保留“未执行”，不留空让人误读为通过。

## 发布 Code Review 预备

本节只锁定下一轮审查入口，尚未执行审查，也不代表任何检查项已通过。

1. 以上一次完整发版复审的实现提交 `c6dd340` 为 delta 起点，首先审查 `c6dd340..HEAD`，再对发布高风险不变量做定向回看。
2. 高风险顺序固定为：严格 USB fail-closed 与证据声明、`PlaybackService`/Controller 连接与队列恢复、`LibraryCatalog` 扫描清理与升级保留、签名验证 registry、Release 诊断关闭/权限/混淆、关键页面可操作性。
3. 审查前采集 `git status`、候选 commit、版本、生成产物和哈希；审查后先跑聚焦回归，再按清单执行 JDK 25 全门禁。
4. 审查输出分开记录：代码发现、本地构建/测试、实机 QA、远程 CI、M4 里程碑验收；任何一类不得代替另一类。
