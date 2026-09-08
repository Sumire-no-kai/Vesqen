# M4 公开 Beta 交付检查表

本文件准备 M4-06 的发布材料，不构成发布决定。只有满足 [PRD](PRD.md)、[M4 设备验收](M4_DEVICE_ACCEPTANCE.md)及保留的 M2/M3 门禁后，才可把候选称为完整 M4；设备缺失时最多准备“受限 Beta 候选”。

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
- [ ] 从上一公开版本执行保留数据升级，核对曲库稳定 ID、收藏、历史、歌单、手动排序、SAF grant、队列 checkpoint、输出模式和 registry；不以卸载重装替代升级。
- [ ] 新装、升级、系统回收/重启、存储不可用与回滚限制分别记录；数据库或持久格式变化另做兼容审查。
- [ ] EN/ZH、窄屏/横屏/大字、深浅主题、减少动效、触控目标、对比度与 TalkBack 的适用矩阵通过或公开列限。
- [ ] Privacy：无网络权限/后台上传新增；诊断导出继续显式操作；路径、URI、标题、设备标识和 evidence reference 按策略脱敏。
- [ ] 支持范围、问题反馈所需的脱敏诊断步骤、已知限制和回退方式写入发布页。
- [ ] M4 验收记录至少含一条真实 VERIFIED 组合；若没有，只能由用户另行决定是否发布受限 Beta，且标题/文案不能暗示 M4 已完成。
- [ ] 合并、tag、远端 release、商店/分发上传和用户采用分别确认，不从本地构建或 CI 自动推断。

## Blocker 分级

| 等级 | 示例 | 处理 |
| --- | --- | --- |
| Blocker | 数据丢失、错误 VERIFIED、严格失败后继续播放、签名绕过、崩溃/ANR、发布签名或升级失败 | 不发布；修复并用同一候选重新执行相关矩阵。 |
| Major | 核心播放/扫描/队列异常、主要操作不可达、严重可访问性或设备兼容回归 | 原则上阻断，除非用户明确缩小 Beta 支持范围并公开限制。 |
| Minor | 不影响核心流程且有明确规避方式的视觉或文案问题 | 记录、排期，并确认不会误导证据声明。 |

## 交付记录

发布决定时追加：候选 commit、分支/PR、版本、产物哈希、签名证书指纹、CI URL、设备矩阵、release URL、发布时间与回滚点。没有执行的字段保留“未执行”，不留空让人误读为通过。
