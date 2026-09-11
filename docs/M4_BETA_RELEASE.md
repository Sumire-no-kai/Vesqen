# M4 公开 Beta 交付检查表

用户已于 2026-09-10 决定发布受限 Beta，目标渠道为 GitHub Releases（签名 APK）和 Google Play 测试渠道（AAB）。第二款 DAC 暂时缺失，不阻挡此次受限发布；相关矩阵仍未验证。只有满足 [PRD](PRD.md)、[M4 设备验收](M4_DEVICE_ACCEPTANCE.md)及保留的 M2/M3 门禁后，才可把候选称为完整 M4。发布决定不等于安装包已经发布。

## 本次发布顺序

1. 收录已合并修复和适用证据，补齐可用设备的短时回归；已有且仍适用的测试不重复执行。
2. 在 iQOO + JBL Flip 7 上确认 USB 识别、能力、实际路由、格式变化及拔插/失败清理；记录无法取得的证据。第二款 DAC 和外部数字逐样本组合留作公开限制，不用扬声器听感代验。
3. 完成隐私/权限说明、EN/ZH 发布说明、安装升级与支持范围准备；公开 Release 不提供诊断开关、录制或导出入口，问题反馈不要求用户自行开启日志。
4. 用户要求长期 keystore 留到最后创建。最终发布时确定签名身份、记录证书指纹、生成并核验签名 APK/AAB，在安装候选上核验数据保留和无诊断入口，再交付两个渠道。Play 测试轨道及账号准入条件在上传前确认。

第二款 DAC 不再是本次受限 Beta 的阻断项；崩溃/ANR、数据丢失、错误输出声明、签名/安装升级失败及 Release 诊断开关仍阻断发布。

## 对外声明基线

- 产品继续使用 Media3 与 Android 公共 API；没有自定义 USB 驱动、native 播放内核或跨设备通用 bit-perfect 保证。
- `AVAILABLE` 只表示当前能力候选，`ACTIVE` 只表示应用请求、AudioTrack、mixer readback 和 route 的 Android 侧观察一致；只有签名记录精确匹配当前构建/手机/ROM/DAC/格式时才显示 `VERIFIED`。
- 不以 DAC 屏幕、听感、采样率相同、AudioTrack request 或模拟录音代替外部数字证据。未验证组合公开写“未验证”，不写“理论支持”。
- Chain 和诊断中的 unavailable、confidence、source、时间和隐私过滤仍是产品契约。

## 候选发布说明模板

### 新增

- 可导入由应用签名证书验证的离线输出验证记录；精确匹配时，播放器、Chain 和 Audio Proof 显示 `BIT-PERFECT VERIFIED`。
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
- Library 首页卡顿的 M3-R1 根因、实际业务修复与双机 A/B 仍开放。
- 长时播放、完整外设/中断、折叠屏、高刷新率和完整 TalkBack 语音矩阵按设备与用户安排继续开放。

## 发布门禁

- [ ] 所有 Beta blocker 为 0；失败 runner 有结论和修复后独立复测，未通过项没有被删除或弱化。
- [ ] `version.properties` 使用经审阅的 SemVer 与递增 versionCode；release notes 与 About 一致。
- [ ] JDK 21 下 unit、Python 工具测试、lint、Debug/Profile/Release、instrumentation compile 通过；CI 通过。
- [ ] 候选 APK/Bundle 使用预期发布签名，证书指纹和 base APK SHA-256 记录；keystore、alias 和密码不进入仓库或日志。
- [ ] 若已有公开版本，从该版本执行保留数据升级；若为首次公开发布，记录无上一公开版本，并用同一长期签名的前后候选验证升级。核对曲库稳定 ID、收藏、历史、歌单、手动排序、SAF grant、队列 checkpoint、输出模式和 registry；不以卸载重装替代升级。现有 Debug/Profile 使用开发签名，不能视为新生产签名的直接升级来源，也不能卸载用户数据来规避签名不匹配。
- [ ] 新装、升级、系统回收/重启、存储不可用与回滚限制分别记录；数据库或持久格式变化另做兼容审查。
- [ ] EN/ZH、窄屏/横屏/大字、深浅主题、减少动效、触控目标、对比度与 TalkBack 的适用矩阵通过或公开列限。
- [ ] Privacy：最终合并 Manifest 不含 INTERNET；如依赖声明 ACCESS_NETWORK_STATE，准确说明它只用于查询网络连接状态。无后台上传新增。Release 不提供诊断启用、录制或导出入口；Debug/Internal 导出仍须显式操作并按策略脱敏。
- [ ] 支持范围、无需开启日志的问题复现步骤、已知限制和回退方式写入发布页；不得要求 Release 用户寻找隐藏诊断开关。
- [x] 无外部数字 VERIFIED 组合时发布受限 Beta 的决定已获用户确认（2026-09-10）；第二款 DAC 缺失公开列限。标题/文案不得暗示 M4 完成，原始 M4 验收门禁继续保留。
- [ ] 合并、tag、远端 release、商店/分发上传和用户采用分别确认，不从本地构建或 CI 自动推断。

## Blocker 分级

| 等级 | 示例 | 处理 |
| --- | --- | --- |
| Blocker | 数据丢失、错误 VERIFIED、严格失败后继续播放、签名绕过、崩溃/ANR、发布签名或升级失败 | 不发布；修复并用同一候选重新执行相关矩阵。 |
| Major | 核心播放/扫描/队列异常、主要操作不可达、严重可访问性或设备兼容回归 | 原则上阻断，除非用户明确缩小 Beta 支持范围并公开限制。 |
| Minor | 不影响核心流程且有明确规避方式的视觉或文案问题 | 记录、排期，并确认不会误导证据声明。 |

## 交付记录

发布决定时追加：候选 commit、分支/PR、版本、产物哈希、签名证书指纹、CI URL、设备矩阵、release URL、发布时间与回滚点。没有执行的字段保留“未执行”，不留空让人误读为通过。
