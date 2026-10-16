# M4 验证矩阵与设备验收

本文件落实 [PRD M4](PRD.md) 的设备、证据、稳定性、性能和界面门禁。代码编译、单元测试、Android instrumentation 源码编译、`BIT-PERFECT ACTIVE`、DAC 屏幕采样率、听感或模拟录音都不能单独证明 `BIT-PERFECT VERIFIED`。

## 证据状态与不可变身份

每条组合记录只使用以下结果之一：`VERIFIED`、`FAILED`、`NOT_TESTED`、`MISSING_DEVICE`、`DEFERRED`。失败记录不删除或改写为通过；重新测试生成新的稳定 record id。

每次记录必须包含：应用版本名/code、已安装 base APK SHA-256、手机 manufacturer/model、Android API、ROM fingerprint SHA-256、DAC VID/PID/名称/USB descriptor version、源和 sink 的采样率/声道/PCM encoding、测试向量 SHA-256、时间、方法、采样信号点和证据位置。APK、ROM、DAC descriptor、源格式或 sink 格式任一变化，都必须重新判定，不能继承旧结果。

应用只接受由内置公钥明确标识的独立、版本化 verification issuer 签署的离线 registry；issuer 不得复用 Android APK 更新签名。envelope schema v2 必须携带稳定 `keyId`，未知 key、旧 keyless envelope 和错误签名均拒绝。导入不是 VERIFIED 开关：仅在严格 USB 状态为 `ACTIVE`、唯一物理 USB Audio Class 设备可识别且所有字段精确匹配时，播放器、Chain 与遥测才显示 `VERIFIED`。字段重复、未知字段、超出 256 KiB、组合不匹配和非 ACTIVE 状态同样 fail closed。诊断可导出稳定 record id、方法和测试向量哈希；证据位置按文本隐私策略脱敏。

## 记录准备与签名

1. 从 [payload 模板](m4/verification-payload.template.json) 复制到私有 QA 目录；不要把真实 ROM、签名材料或未审阅证据提交到仓库。
2. 生成合法的确定性 PCM 测试向量：

   ```powershell
   <python> tools/generate_m4_test_vectors.py --output <new-private-directory>
   ```

   数字采集完成后，在记录了两个显式对齐 frame offset 的前提下逐字节比较：

   ```powershell
   <python> tools/compare_m4_pcm.py --expected <vector.wav> --captured <digital-capture.wav> --expected-start-frame <n> --captured-start-frame <n> --frames <n> --output <new-result.json>
   ```

3. 填写组合记录，再校验并生成不可变 canonical payload：

   ```powershell
   <python> tools/m4_verification.py prepare --source <records.json> --output <new-payload.json>
   ```

4. 使用仓库外保存的专用 verification issuer 私钥签名；不得使用 APK 更新私钥。`keyId` 必须与应用内固定的公钥条目一致。密码只从 `VESQEN_KEYSTORE_PASSWORD` 与可选的 `VESQEN_KEY_PASSWORD` 环境变量读取，不能写进命令、仓库或日志：

   ```powershell
   java tools/M4VerificationSigner.java sign <payload.json> <issuer-keystore> <JKS-or-PKCS12> <alias> <key-id> <new-registry.json> --no-overwrite true
   ```

5. `tools/m4_verification.py inspect` 只检查 v2 envelope、`keyId`、Base64 和 payload 结构，并明确报告 `signatureCryptographicallyVerified=false`；密码学验签由 signer 自检和应用导入执行。v1 keyless registry 必须使用专用 issuer 重新签发，不能原样迁移。

首个 issuer 的稳定身份是 `vesqen.output_verification.2026_01`；固定 SPKI SHA-256 为 `35619e5cc562b23282aa5bce0aa4e6ba6221e40b97522d96d91ea5c07daf0db4`，当前自签证书 SHA-256 为 `649b405d38d2620a1f69733c2091bbae0917a3c615db72e4ca696d9c25b1149b`。迁移开发机时，只通过受控加密介质复制 PKCS12，并在目标机单独导入密码；迁移后重新核对两项指纹。私钥、PKCS12、密码和解密后的临时文件都不能进入 Git 或普通云同步。密钥轮换应新增 `keyId` 与公钥，并在仍需读取旧 registry 时保留旧公钥。

测试向量是整数算法合成的双声道 PCM，不含第三方录音，manifest 标为 CC0-1.0 并记录每个 WAV 的哈希、采样率、位深、声道、时长和字节数。数字采集必须位于能证明 Android 到 DAC 之间 PCM 的信号点，记录采集设备/固件/时钟/线缆与逐样本比较命令；模拟输出录音只能作为模拟链路观察，不能升级为数字 bit-perfect 证据。

## 当前矩阵（2026-09-08）

| 组合/门禁 | 状态 | 当前证据与关闭条件 |
| --- | --- | --- |
| Honor STF-AL00 / Android 9 / API 28 / 普通系统输出 | `PARTIAL_PASS` | 候选 APK 身份、签名 registry 存储/拒绝、55 项 UI、普通扬声器短流程、100 次 Chain 生命周期和数据保留已通过；Debug 启动/体积基线已采集。不具备 API 34 mixer，不能进入严格 USB 或 VERIFIED。详见下方执行记录。 |
| iQOO V2171A / Android 15 / API 35 / 无 DAC | `PARTIAL_PASS` | 最终候选的 registry/cache 3 项、无 DAC 严格模式 8 轮 fail-closed、55 个不同 UI 用例、100 次 Chain 生命周期、Profile 启动基线和覆盖安装数据保留已有证据；因没有 DAC，不能证明严格 USB `ACTIVE` 或 `VERIFIED`。详见下方执行记录。 |
| Android 14+ 手机 A × DAC A/B | `MISSING_DEVICE` | 需要真实 UAC 设备、支持/不支持格式、设置/读回/route、拔插与失败清理。 |
| Android 14+ 手机 B × DAC A/B | `MISSING_DEVICE` | 第二台现代手机不得由 Honor API 28 或模拟器替代。 |
| 至少一条外部数字逐样本组合 | `MISSING_DEVICE` | 需要有效签名记录并在精确组合上实际显示 VERIFIED。 |
| 8 h 普通播放 | `PASS`（Honor，有线耳机，亮屏） | 2026-10-09 跑满 8 小时，见下方执行记录。扬声器音量为 0 时，Honor 会冻结后台播放，见同一记录。 |
| 3×15 min 采样对比、30 min 诊断录制 | `PASS`（Honor） | 2026-10-10，见下方执行记录。 |
| 8 h 严格播放、2 h 24/96 | `DEFERRED` | 严格播放需要真实 DAC；2 h 24/96 还没跑。 |
| Bluetooth / 3.5 mm / 通话与 OEM 中断矩阵 | `DEFERRED` / `NOT_TESTED` | Bluetooth 按既有决定暂缓；通话需可控条件，不能自动拨打。 |
| Library 根因修复与 Honor/iQOO A/B | 开放的 M3-R1 | M4 性能采集不关闭 M3 的业务根因和修复门禁。 |

## 短时稳定性与性能步骤

- 保留数据覆盖安装 app/test APK；测试前备份应用数据库和偏好，不卸载、不清数据、不修改手机音乐文件。
- 先执行签名 registry 的有效签名、错误签名不替换、重载持久化与 exact match instrumentation；旧 Android 必须覆盖实际运行，不以 compileSdk 推断兼容。
- 执行现有 `SpeakerPlaybackDeviceTest.playbackSwitchSeekPauseAndObserverRelease` 与 `SpeakerChainLifecycleDeviceTest.chainNavigationAndRecordingLifecycle`。它们覆盖切歌/seek/暂停、短诊断、100 次观察者或 Chain 生命周期、前后台和旋转；不替代长时或 USB 外设测试。
- 使用 `tools/collect_m4_baseline.py` 收集 3–20 次冷启动、APK 哈希/体积、meminfo、gfxinfo、CPU、battery 和 thermal 原始快照。Debug 必须显式 `--allow-debuggable` 且只能标为比较结果。
- Library/Chain 滑动分别使用 `tools/measure_library_scroll.py`，固定 APK、数据、刷新率、播放状态、温度与实际 XML viewport，每项至少三轮；报告所有轮次而非最好一轮。
- 手动覆盖 EN/ZH、320/360 dp、短横屏、1.0/1.3/1.5/2.0 字体、深浅主题、减少动效和完整 TalkBack 语音遍历。语义树通过不能单独宣布 TalkBack 体验通过。

## 最终关闭条件

- 本文所有 `MISSING_DEVICE`、关键 `NOT_TESTED` 与非用户明确暂缓项关闭；至少一条精确组合拥有有效外部数字证据和签名 registry，并由候选 APK 实际解析为 VERIFIED。
- 严格模式所有失败路径无错误 ACTIVE/VERIFIED，设备或 route 变化立即撤销；普通输出恢复必须由用户选择。
- M3-R1、M3 USB/旧系统/资源门禁和相关 M2 开放项逐项关闭或明确列为公开 Beta 限制，不通过更换里程碑名称消失。
- 最终候选的 unit/lint/Debug/Profile/Release/instrumentation compile、适用设备 runner、升级/数据保留、CI、签名与 APK 身份分别记录；合并和发布是独立状态。

## 执行记录

在最终候选产生后追加，不回写或删除早期失败：commit、版本、app/test APK 哈希、设备/ROM、runner 原文目录、通过/失败/跳过、人工步骤、用户数据恢复和仍开放风险。

### 2026-09-08 · Honor 旧系统候选

- 候选版本为 `0.4.0-beta.1` / versionCode `9`，核心提交为 `a555597`。最终本地与设备已安装 app APK 的 SHA-256 均为 `f5229e5c1fdf1123bd92d59e97f9192fabe1ca78d806b893de751f6aa8805715`；test APK 均为 `bd21f6541a15a2e152f5ca5cd46890685545fd93bcf4485cfc26277ff319581c`。设备是 HUAWEI/Honor `STF-AL00`、Android 9 / API 28，ROM fingerprint 仅以 SHA-256 `e93b23fb23b15a31bdeb39cd117aae98071e51358301f1af7d5c81862bbec436` 记录。
- registry 首轮 2 项在 API 28 因 `InputStream.readNBytes` 不存在而失败；原始失败保留在私有 `verification-repository.txt`。改为有界兼容读取后，独立 2/2 通过；随后包含有效签名、错误签名不替换、Settings 导入 fail-closed 和 150% 英文 Playback progress 的最终定向批次 4/4 通过。
- 最终 UI runner 55/55 通过；其中 About 首轮因新增设置行后入口位于视口外、VERIFIED 展示首轮因 Chain 摘要位于视口外而失败。测试改为执行真实滚动后分别定向复测通过，完整 55 项再跑通过，没有删减产品断言。最终 app 上的 registry/运行身份缓存 3/3、普通扬声器切歌/seek/暂停/观察者释放 1/1 通过；较早同功能候选上的 Chain 100 次进出、诊断、前后台、旋转、隐私和释放 1/1 通过。
- 一次把整个扬声器测试类误加入组合 runner，UI 55 项完成后进入首个 sampling soak 时被立即中断；该批次没有 `result.json`，不计整套通过，也没有继续执行用户暂缓的长时测试。随后独立 UI 55/55 和明确方法级扬声器短测 1/1 通过，原始中断目录保留。
- Debug 基线明确不是 Release 验收：采集时 app APK 哈希为 `7d5c3a8a...`，并非上面的最终 `f5229e5c...`；两者体积均为 24,721,095 bytes，小于 PRD 的 30 MB 预算。5 次 `ThisTime` 为 4299/4304/4291/4310/4380 ms，中位数 4304 ms。`TotalTime` 首次受系统恢复影响为 51489 ms，其余为 4304/4291/4310/4380 ms，中位数 4310 ms；不能丢弃首轮原始值后宣称全为约 4.3 秒。CPU、内存、gfx、battery 与 thermal 原始输出均保留，thermal 在该 ROM 上为空。Honor 上最终 APK 的 Release/Profile 性能基线仍须另采。
- 覆盖安装前后逻辑用户数据一致：40 首曲目、1 个来源、0 个歌单/条目；排除扫描瞬态 `seen_epoch` 后曲目字段摘要一致，来源身份/授权摘要一致。测试清空的播放队列偏好已从测试前备份精确恢复，最终 XML SHA-256 为 `92b21d976f0c1944fbd83307b2204d5eba84cd53877e3d818f7b6a7212f9b302`。设备端仅清理本轮明确创建的 `speaker-acceptance` 和两个 WAV；可从主机归档 `speaker-acceptance-device.tar` 恢复，用户音乐未改动。
- 以上 runner、备份和原始指标位于未提交的 `build/qa/m4-honor-20260908`。它们证明当前候选的 Honor/API 28 普通输出与软件路径，不证明 API 34 严格 USB、真实 DAC、外部数字逐样本、Release 性能、长时稳定性或完整 M4 验收。

### 2026-09-08 · 最终本地软件门禁

- JDK 21 下 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleProfile :app:assembleRelease :app:assembleDebugAndroidTest` 通过：199 项 JVM 测试，0 failure/error/skip；Lint 0 error / 21 warning；182 个 Gradle 任务成功。
- 最终产物：Debug `f5229e5c1fdf1123bd92d59e97f9192fabe1ca78d806b893de751f6aa8805715`（24,721,095 bytes），Profile `140ecec5ade2b0b92036d052222f41c196fdc09ccacc423d438410ea207ebe0f`（15,963,112 bytes），unsigned Release `82e185ab1bf59ad92a59cc68fced8015b9691f8ad71b2b539f54a56aaffc237b`（15,954,892 bytes），test APK `bd21f6541a15a2e152f5ca5cd46890685545fd93bcf4485cfc26277ff319581c`（1,329,991 bytes）。unsigned Release 只证明构建，不是发布签名候选。
- Python 工具回归 13/13 通过；使用临时 RSA/PKCS12 key 完成 payload prepare、Java signer 自检与 registry inspect 冒烟。`inspect` 按设计只报告结构与 `signatureCryptographicallyVerified=false`，不冒充应用端验签。

### 2026-09-08 · iQOO 现代系统候选

- 设备是 vivo/iQOO `V2171A`、Android 15 / API 35，ROM fingerprint 仅以 SHA-256 `686b0eea4b6d4eb3edc2f73e383c04b3178894e375ae97f8160e0c82fbe5d19e` 记录。最终 Debug app/test APK 的 SHA-256 分别为 `f5229e5c1fdf1123bd92d59e97f9192fabe1ca78d806b893de751f6aa8805715` 与 `bd21f6541a15a2e152f5ca5cd46890685545fd93bcf4485cfc26277ff319581c`，均与本地候选一致。
- registry 与运行身份缓存 3/3 通过。无 DAC 严格模式定向用例 1/1 通过：连续 8 轮均未错误进入 `AVAILABLE`/`ACTIVE`，并且只有用户选择普通系统输出后才恢复播放；这证明 API 35 无外设时 fail closed，不证明真实 USB 路由。
- Chain 生命周期用例 1/1 通过，耗时 79.018 秒，覆盖 100 次进出、前后台、旋转、诊断、隐私和观察者释放。UI 首轮完成 54/55；首例因同 Activity 前台宿主竞争而未取得 Compose hierarchy。该缺失用例随后以独立 `MainActivity` 宿主在相同最终 APK 上 1/1 通过。再次把宿主应用到整套时第二例在断言前触发 vivo `startActivitySync` 后台启动超时，因此保留原失败，不把两批拼成一份全绿 runner；当前证据覆盖 55 个不同 UI 用例，但不存在单次 55/55 报告。
- 最终 Debug 的 5 次冷启动 `TotalTime` 为 1503/1605/1758/1748/1834 ms，中位数 1748 ms，只作可调试包比较。不可调试 Profile APK 哈希为 `140ecec5ade2b0b92036d052222f41c196fdc09ccacc423d438410ea207ebe0f`，5 次冷启动为 370/414/427/457/462 ms，中位数 427 ms、范围 370–462 ms；启动、APK 体积和资源快照不替代 Library/Chain 滑动、长时功耗或 Release 签名验收。
- 覆盖安装与测试前有 112 首曲目、1 个来源、0 个歌单/条目。测试前后排除扫描瞬态的曲目用户字段、来源身份/授权、歌单和条目摘要分别保持一致；最终又从有效 Debug 备份精确恢复。重新导出的 31 个私有文件逐文件 SHA-256 与测试前 31/31 一致，播放状态 XML 恢复为 `f9bf8cacb5ad2c76af909249a1ca8d3ee364026945df161fe3c099409ee8d13e`。两次因旧 Profile 不可调试而只包含 `run-as` 错误文本的 61-byte 文件明确判为无效备份，未用于恢复。
- 原始 runner、基线、有效备份与逐文件核对位于未提交的 `build/qa/m4-iqoo-20260908`。设备最终保留 `0.4.0-beta.1` / `9` Debug 包、应用进程停止且用户数据精确恢复；未修改用户音乐。没有连接 DAC，故真实 mixer attributes、AudioTrack USB route、拔插、外部数字逐样本和 `VERIFIED` 继续为硬件门禁。

### 2026-09-09 · 大字体动态证据布局复测

- M4 UI follow-up 在 Honor API 28 与 iQOO API 35 的字体比例 1.3 上复现了 Chain 核心证据列不等高：右列 `实测 · 未验证 · N 秒前` 会换行，且秒数变化可能动态改变卡片高度。根因是 1.3 仍走双列布局，并且 OEM 非线性字体缩放可能略低于名义值。提交 `24c5279` 将大字体边界留出 OEM 容差，统一核心事实、选择器、展开控件和指标值的堆叠规则，并让堆叠后的证据行占满可用宽度；没有截断置信度、验证状态或观测时间。
- 新增的 360 dp / 字体 1.3 / 120 秒证据年龄回归先以 `overflowWidth=true` 失败，修复后在 Honor 通过；随后 8 个定向 Chain 布局用例全部通过，覆盖 320/480 dp、字体 1.3/1.5/2.0、600 dp 指标列和 840 dp 高级布局。iQOO 上的 runner 再次受 vivo 前台 Activity 启动限制停滞，本次不计为 instrumentation 通过；改用真实应用、UI hierarchy 与截图人工复测。证据年龄从两位数进入三位数后，两个 AudioTrack 证据行仍分别保持 `[120,1475][960,1525]` 与 `[120,1706][960,1756]`，单行内容完整且卡片未跳高。
- 本地 199 项 JVM 测试通过，Lint 为 0 error / 21 个既有 warning，Debug 与 instrumentation APK 编译通过；PR CI 的 Debug/test、Profile、Release 均通过。系统栏颜色、默认字体动态年龄和已完成的 M4 矩阵不重复执行。复测结束后两台设备均恢复字体比例 1.0、浅色模式并停止应用；iQOO 31/31、Honor 4/4 个备份文件逐文件 SHA-256 匹配。长时播放、真实 DAC、外部数字逐样本和 `VERIFIED` 仍按上表保持开放或暂缓。

### 2026-09-10 · Library/Chain 修复的双机功能回归与受限 Beta 决定

- PR #27 已将 `ac64438` 合并为 `738da97`；修复 Library 跨页面/内部层级的滚动位置保存、集合消失时的状态冲突、Chain 数值位数变化导致换行。最终本地 unit/lint/Debug/androidTest 构建通过，PR CI 的 Debug/tests、Profile、Release 通过。
- 同一 Debug app APK SHA-256 为 `c14f91fe8dce10fd81a64a6d6f456e561c8ee558db7027efecd8263277a11ee5`，test APK 为 `5a1c93ba005c05e55ee7aeca826d42fef01647c0a81d1a3c42419779fb8486bf`。本轮补验前核对主机产物和两机安装 app 的 SHA-256，一致；Honor 安装 test 的 SHA-256 也与主机一致。
- iQOO Android 15 的最终独立 runner 为 6/6，通过记录沿用 `build/qa/ui-fix-20260910/final-targeted-after-review/`，本轮未重复执行。更早因 vivo 前台宿主确认而中断的批次不计通过。
- Honor STF-AL00 / Android 9 本轮接入时未安装 Vesqen，使用上述产物新装 app/test，没有卸载或清理任何用户应用；同样 6 项 runner 单批 6/6，0 失败/跳过，instrumentation 时间 17.052 秒。覆盖全部歌曲与喜欢独立位置、Settings 往返、集合消失与缩短后的索引夹紧，以及 360 dp RAW、600 dp RAW/AUTO 的核心和高级数值完整性/固定高度。原始记录在 `build/qa/limited-beta-20260910/honor-ui/`，测试后停止 Vesqen 进程。
- 上述是同一修复 APK 的双机功能证据；没有重跑快滑性能 A/B、长时、真实 USB、Release 签名或完整无障碍矩阵，不关闭这些门禁。
- 用户确认受限 Beta 发布到 GitHub Releases 和 Google Play 测试渠道，第二款 DAC 缺失公开列限，不再阻挡本次受限 Beta；真实 iQOO + JBL Flip 7 USB 短测仍待执行。长期发布密钥留到最后创建，尚未执行签名、tag、Release 发布或 Play 上传。

### 2026-09-16 · 发版候选详细复审与最终软件回归

- 本轮详细复审修复严格输出 PCM 闸门、Controller 断连后的证明撤销、MediaSession 命令能力、MediaStore 多卷身份迁移与歧义隔离、完整扫描清理边界、SAF provider 错误、播放历史整库刷新、播放列表 1+N 查询以及无关 Now 转场。完整发现和保留的架构债务见 [架构审查](ARCHITECTURE_REVIEW.md)。
- 本地完整软件门禁为 230 项 JVM tests 全部通过、Lint 0 errors / 26 warnings，Debug、Profile、未签名 Release 与 instrumentation APK 构建完成；Python 工具测试 13/13。本机构建使用 Oracle JDK 25.0.4.1；推送 `c6dd340` 后，GitHub Actions [Android CI #35057717601](https://github.com/Sumire-no-kai/Vesqen/actions/runs/35057717601) 在 Temurin JDK 21 下通过证据工具、unit/lint/Debug/test APK、Profile 和 Release，JDK 21 门禁独立关闭。
- iQOO V2171A / Android 15 上，曲库存储与迁移 11/11 通过，三个输出／断连 UI 用例通过，严格失败提示在 vivo 销毁 Activity 的合批失败后独立 1/1 通过。测试 APK 签名不一致时只替换 test 包，主应用未卸载或清数据；最终移除 test 包并恢复不可调试 Profile，设备与本地 Profile SHA-256 均为 `ac6a3ad2678279ac838e88e734b62ad09d9b20ab24bcab8eeb8de9279eb12177`。
- 这形成已推送且远端 CI 通过的软件冻结候选，但没有连接真实 DAC，不关闭严格路由／拔插、外部逐样本 VERIFIED、发布签名与同签名升级、完整 TalkBack／适配、长时矩阵或 M3-R1 双机性能门禁；没有创建 tag、Release 或商店产物。

### 2026-10-01 · beta.1 CI 签名 APK 的安装与同签名更新

对应 #42 的最后两项。

- 候选：[GitHub APK release #36811359590](https://github.com/Sumire-no-kai/Vesqen/actions/runs/36811359590)，工作流提交 `d9e20b6`，源码为 `release/1.0.0-beta.1` 的 `57daf836ed003e0e0c7c8794d4a4d0c9f083f164`。所有者批准签名后生成草稿 `v1.0.0-beta.1`。草稿里的 `Vesqen-1.0.0-beta.1.apk` 的 SHA-256 为 `5670977b4f68c648dbf46396cf06d3f188ef3993dc438e4da64b25ba79f9b396`，与当天在本机封板签名的 APK 逐字节相同（未签名包 SHA-256 `fd963c0cdf5d18874c97aa9400ff35525fc23414b0cef57d693bdac0ea3a8489`），所以 #42 里记录的 Honor 走查也是在这个文件上做的。
- 本机复核草稿附件：`SHA256SUMS` 一致；apksigner 只有一个签名者，证书 SHA-256 为 `743e96fcb71dc58188496819a001a27cd88d909162c5ae929c87bfa29ab86293`，v2、v3 验证通过；aapt 显示 1.0.0-beta.1 / 10、minSdk 26、9 项权限，没有 INTERNET，不可调试；16 KB 页对齐检查通过。
- iQOO Neo9 V2338A / Android 16（OriginOS 16，PD2338B_A_16.2.13.2）：13:21 先用 adb 全新安装同一字节的本机封板 APK，走查了授权、曲库扫描（108 首，与 MediaStore 中 `is_music=1` 的数量一致）、24 位 / 192 kHz FLAC 播放、正在播放页和曲目详情、链路页（源文件、AudioTrack pcm-float、路由、欠载 0 次），以及严格 USB 在没有 USB 输出设备时停止播放并提示、没有降级，再切回系统输出继续播放。通知栏的暂停、播放、下一首，后台播放 20 秒和媒体键都正常，没有崩溃或 ANR。当时记下的数据：收藏 1 首，该曲播放 2 次，队列 108 首停在第 2 首，输出模式为系统输出。
- 同一台 Neo9 上用 `adb install -r` 安装 CI 草稿 APK。vivo 安全守护只提示“外部来源应用”，确认后继续。更新成功：firstInstallTime 仍是 13:21:04，lastUpdateTime 为 13:51:11，设备上 base.apk 的哈希为 `5670977b…b396`。两项运行时权限保留；收藏、播放次数、队列位置和输出模式全部保留，曲库不需要重新扫描，没有崩溃。
- iQOO V2171A / Android 15：所有者同意后，卸载此前用 debug 密钥签名的 Profile 构建（会清掉它的测试数据），再全新安装 CI 草稿 APK，安全守护同样只提示外部来源。授权、扫描、播放、后台 15 秒、媒体键暂停、继续和下一首、前台通知、链路页（源文件 48 kHz / 16 位，AudioTrack pcm-float）都正常，没有崩溃或 ANR。
- 结论：安装、同签名覆盖更新和数据保留通过，Android 9（Honor）、15、16 都覆盖到了。这是第一个公开版本，所以这次覆盖更新是同一 versionCode（10 → 10）、同一签名的更新，验证的是证书一致时系统允许覆盖、应用数据保留；versionCode 增大的真实升级路径要到 beta.2 才能执行。真实 bit-perfect DAC、外部逐样本 VERIFIED、完整 TalkBack 和长时测试仍未覆盖，作为公开限制。

### 2026-10-09 · Honor 8 小时普通播放（#51）

- 构建：`deviceTest`，本地合并 master `929bd9b`、#106（`fef0ddb`，后台不再每帧重算正在播放页）和 #108（`61eb06d`，只向 Codec2 解码器请求浮点 PCM），合并提交 `271a42d`，APK SHA-256 `693b1f7185ae4f82d22b429062a7657fb259864b37937ac3984df29587789519`。#106 和 #108 都是这次长测前发现的问题，见下文。
- 设备：Honor STF-AL00 / Android 9，插电，屏幕常亮，前台是 Termux 或 SkyLink（另一个测试），Vesqen 在后台。
- 播放内容：曲库 106 首（94 首 FLAC，包括 16/24 位、44.1/48/96 kHz；11 首 MP3；1 首 WAV），列表循环。
- 记录：手机上的脚本每分钟记一次进程、CPU、内存、媒体会话、冻结状态、AudioFlinger 音轨、前台服务、通知、电池和屏幕；每 10 分钟记一次 AudioFlinger 的欠载计数。原始数据在未提交的 `build/qa/long-run/`，里面有曲名，不进仓库。

**结果（有线耳机，媒体音量 3/15）**：15:18 → 23:17，7.98 小时，469 条记录，相邻两条最长相隔 71 秒。

- 全程同一个进程，没有崩溃、重启或 ANR；没有一条记录处于冻结、未播放或音轨不活动状态。
- 换曲 142 次，曲库循环了一圈多，FLAC、MP3、WAV 之间的切换都覆盖到了。
- CPU 平均占一个核心的 12.4%，每小时在 11.4%–13.3% 之间；内存（PSS）每小时的中位数在 196–206 MB 之间，没有增长趋势。
- AudioFlinger 欠载计数 47 次快照全部为 `partial=0 empty=0`；电池温度 33–37 °C。
- 这个进程 12:01 启动后一直没有重启（14:51–15:17 被冻结，见下），到 10-10 08:16 仍在播放。

**扬声器音量为 0 时（同一构建，第 3 轮，12:01 起）**：连续播放 2.8 小时、换曲 47 次后，Honor 的系统冻结器把 Vesqen 放进了冻结组（`freezer.state=FROZEN`）：14:33 冻结 1 分钟后自动恢复，14:51 再次冻结后不再恢复，播放停止。当时屏幕亮着，音频焦点仍在 Vesqen。前一天关屏时，同样在扬声器音量为 0 的情况下约 50 秒就被冻结。插上耳机（媒体音量不为 0）后，第 4 轮跑满 8 小时没有一次冻结，推测是 EMUI 对“后台无声播放”的限制，没有系统日志可以直接证明。正常听歌时音量不为 0，所以不作为发布阻碍；关屏、音量不为 0 时的长时播放还没测。

**长测发现并修复的问题**

- #106：后台播放时主线程一直占一个核心的 30%–50%，原因是正在播放页的切换动画在界面停止后无法结束、每帧重算。修复前后，回归测试在 Honor 上分别为 5/5 失败和 5/5 通过；后台主线程 CPU 从 36% 降到 0.5%–2%。
- #107 / #108：在 Honor 上，只要开始解码 MP3 就会在系统的 ACodec 里崩溃（第一轮长测在 FLAC 24/48 → MP3 处崩溃），原因是向不能输出浮点的 OMX 解码器请求了浮点 PCM。beta.1 带着同样的设置。修复后，原来会崩的五种切换组合各 3/3 通过；iQOO（Android 15）上 24 位 FLAC 仍为浮点输出，MP3 正常。

**仍未覆盖**：严格播放 8 小时（需要真实 DAC）；2 小时 24/96 连续播放；3×15 分钟采样对比和 30 分钟录制；关屏或锁屏、音量不为 0 时的长时播放；蓝牙、有线耳机插拔、来电等中断。

### 2026-10-10 · Honor 采样对比和 30 分钟诊断录制（#51）

- 构建：master `917ebd6` 的 `deviceTest`，app APK SHA-256 `c285ed198268252864851d8e67780add3874b66292d9993a7bf5d1743cc6e730`，test APK `071e5a7126bccf4462e60db4736194ca664b428310b9c4b4a91b648c68a097d9`。应用代码与 8 小时长测的构建相同。
- 设备：Honor STF-AL00 / Android 9，经 USB 供电，有线耳机，屏幕常亮。测试期间用 `--foreground-host` 让测试应用留在前台，避免 Android 9 的系统冻结器冻结没有界面的测试进程。
- 用例：`SpeakerPlaybackDeviceTest#samplingComparisonSoak`（09:31–10:17）和 `#diagnosticRecordingSoak`（10:17–10:48），都是 1/1 通过。两者都循环播放生成的 24 位 / 48 kHz WAV，每 5 秒检查一次仍在播放、没有播放错误。下表的 CPU 是整个测试进程的 CPU 时间，其中包括每 5 秒一次的内存采集。

| 阶段 | 时长 | CPU（一个核心的百分比） | 内存 PSS（开始 / 结束 / 最大） | GC | 遥测样本 | 欠载和错误事件 |
| --- | --- | --- | --- | --- | --- | --- |
| 不采样 | 15.0 min | 12.5% | 197 / 203 / 205 MB | 1 次 | 不采样 | 不采样 |
| 每秒采样 | 15.0 min | 19.2% | 207 / 204 / 211 MB | 9 次，0.8 s | 836 | 0 |
| 每 0.25 秒采样 | 15.0 min | 21.0% | 206 / 208 / 211 MB | 32 次，3.2 s | 3321 | 0 |
| 诊断录制（每秒） | 30.1 min | 23.5% | 182 / 231 / 232 MB | 85 次，16.7 s | 1672 条快照 | 60 个事件，无丢弃 |

- 结束时三个采样阶段都确认没有观察者残留（`activeObservationCount` 回到 0），采样频率也随观察者结束而停止。
- 诊断录制在上限之内：最多 1800 条快照、4096 个事件，实际 1672 条快照、60 个事件，丢弃 0、事件序号缺口 0。导出的报告通过了私有内容检查（不含 `VESQEN_QA_PRIVATE`、`file://` 和缓存路径）。
- 观察：录制期间内存随保存的快照一起增长（5 分钟 208 MB，30 分钟 231 MB），在快照上限内有界。30 分钟录制导出的 JSON 约 50 MB，作为邮件附件偏大，以后可以考虑压缩格式。

### 2026-10-12 · beta.2 CI 签名 APK 从 beta.1 升级

- 候选：[GitHub APK release #38213613584](https://github.com/Sumire-no-kai/Vesqen/actions/runs/38213613584)，源码和工作流都是 `master` 的 `76781ec82e3605891e0af20d08500005756d9a07`，所有者批准签名后生成草稿 `v1.0.0-beta.2`。`Vesqen-1.0.0-beta.2.apk` 的 SHA-256 为 `36954f80d0bfa94fe1bddd00fe43700d875affa36a1e8ba54c25ecc6908e70a0`（未签名包 `02c8e784…ef12`）。同一次运行产出的未签名 Play 版 AAB（`unsigned-play-bundle`）SHA-256 为 `b89f24e63f8891cc2bf697c68fd4f8fb03c1d6e3b7ec3672ac9526e865043521`。
- 本机复核草稿附件：`SHA256SUMS` 和 `release-manifest.json` 一致；apksigner 只有一个签名者，证书 SHA-256 为 `743e96fc…6293`，v2、v3 验证通过；aapt 显示 1.0.0-beta.2 / 11，带 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES`（GitHub 版）。Play 版 AAB 的 Manifest 里没有 `REQUEST_INSTALL_PACKAGES` 和更新安装界面。两个构建都配置了线上统计地址。
- Honor STF-AL00 / Android 9：设备上原有 10-01 安装的正式 beta.1（base.apk 哈希 `5670977b…b396`）。升级前收藏 1 首（05. 前前前世），播放到 3:09 后暂停，输出模式为系统输出。用 `adb install -r` 安装草稿 APK：versionCode 10 → 11，firstInstallTime 仍是 2026-10-01 12:18:56，设备上 base.apk 哈希为 `36954f80…e70a0`。首次打开显示使用统计说明（征求同意的地区），选了"不发送"，避免验收数据进入线上统计。新界面下曲库完整，收藏 1 首保留，队列（第 93 / 106 首）和进度 3:09 保留，`<unknown>` 显示为"未知艺术家"（#32）。从 3:09 继续播放到 3:18，前台服务正常，暂停正常；链路页、设置里的"更新"组和"使用统计：关"都正常。
- iQOO V2171A / Android 15：设备上原来没有正式包，先安装 GitHub 上公开的 beta.1（哈希 `5670977b…b396`），授予音乐和通知权限，扫描出 112 首（与 MediaStore 中 `is_music=1` 的数量一致）。播放"ありがとう、神様"并收藏，暂停在 0:18。安装草稿 APK 时，vivo 安全守护只提示"外部来源应用"。升级成功：versionCode 10 → 11，firstInstallTime 不变（18:42:41），base.apk 哈希为 `36954f80…e70a0`，两项运行时权限保留。同样选"不发送"。收藏 1 首、队列（第 97 / 112 首）和进度 0:18 保留，不需要重新扫描；继续播放、前台服务、媒体键暂停正常，链路页显示源文件 FLAC 192 kHz。
- 两台手机在升级之后都没有崩溃记录；唯一一次进程退出是安装更新本身（`PACKAGE UPDATED`）。
- 没有覆盖：官网的 `updates/beta.json` 要在发布后才上线，所以这次没有验证应用内检查更新能否拿到线上版本文件。应用内的下载、核对、安装流程已在 #68 中用本地文件验证（2026-10-10），发布后再补查一次"检查更新"。#72 蓝牙和扬声器各播一首仍未做。
- 结论：安装、versionCode 增大的同签名升级和数据保留通过，覆盖 Android 9 和 15。

### 2026-10-14 · beta.3 CI 签名 APK 从 beta.2 升级

> 2026-10-15 作废：本节的候选 `0c77c95`（APK `83d0b86a…546b`）在代码审查后撤下，草稿已删除，发布的是下面 2026-10-16 一节的重新签名版本。本节的设备观察（包括耳机键恢复的结论）仍然有效。

- 候选：[GitHub APK release #38484502335](https://github.com/Sumire-no-kai/Vesqen/actions/runs/38484502335)，源码和工作流都是 `master` 的 `0c77c9533d42db07e2dd854bafdb831867f31c0c`，所有者批准签名后生成草稿 `v1.0.0-beta.3`。`Vesqen-1.0.0-beta.3.apk` 的 SHA-256 为 `83d0b86a3f15c63a7256aa7d3b581bd5487125a03b88313fee227cb55bcd546b`（未签名包 `f1c22eb0…365b`）。同一次运行产出的未签名 Play 版 AAB（`unsigned-play-bundle`）SHA-256 为 `f0beb7aed8556ec420b6f33fd01137850af6d407ca4a656ddba5fc07c36695b1`。R8 映射（`release-mapping`）里 GitHub 版 `mapping.txt` 为 `68eaf405…2080`，Play 版为 `577ea576…8b3c`。
- 本机复核草稿附件：`SHA256SUMS` 和 `release-manifest.json` 一致；apksigner 只有一个签名者，证书 SHA-256 为 `743e96fc…6293`，v2、v3 验证通过，zipalign 通过；aapt 显示 1.0.0-beta.3 / 12，带 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES`（GitHub 版），激活链接只接受 `vesqen-api.sumirenokai.com`。Play 版 AAB 的 Manifest 里没有 `REQUEST_INSTALL_PACKAGES`、更新安装界面和激活链接。
- Honor STF-AL00 / Android 9（无线 adb）：设备上原有 GitHub 版 beta.2（base.apk 哈希 `36954f80…e70a0`，升级前备份到 `build/qa/device-backups/`）。升级前第 93 / 106 首“05. 前前前世”暂停在 3:17，已收藏，输出为系统输出。用 `adb install -r` 安装草稿 APK：versionCode 11 → 12，firstInstallTime 仍是 2026-10-01 12:18:56，设备上 base.apk 哈希为 `83d0b86a…546b`。打开后没有再显示使用统计说明（beta.2 时已回答），也没有崩溃提示；曲库完整，队列、进度 3:17 和收藏保留。从 3:17 继续播放到 3:23，前台服务正常，暂停正常。设置“播放输出”卡片里的“高级功能”一行显示“2.0 前免费”，点进去是 2.0 之前免费的说明，没有激活码入口（发布版在收费开关打开前不显示）。
- 崩溃提示（#113）：`adb shell am crash` 之后重新打开，出现“Vesqen 上次意外退出了”。“查看并发送”打开设备报告，默认勾选“最近的错误”和“崩溃记录”。预览里的崩溃记录只有 `android.app.RemoteServiceException` 和各帧的类名、方法、行号，没有异常消息和文件路径；Android 9 没有系统退出记录（`UNSUPPORTED_ANDROID_VERSION`）。没有上传。
- 耳机键恢复（#52）在这台 Honor 上不成立。Vesqen 播放时是系统的媒体按键会话，但系统没有记住它（`Last MediaButtonReceiver: null`），保存下来的按键接收者一直是网易云音乐。用 `am kill` 结束 Vesqen 后，`input keyevent 87`（下一首）被系统发给网易云并把它拉起，网易云没有播放，随后结束了它的进程。从最近任务划掉 Vesqen 后，记住的仍是网易云，所以没有再发播放键。Media3 1.11 在 Android 12 以下运行时把按键交给播放服务，只有会话正常释放时才换成 #52 的广播接收器；这台手机上两种情况都没有让系统记住 Vesqen。
- 界面测试：iQOO V2171A / Android 15 上的“Vesqen Test”（`.devicetest`，由 `0c77c95` 构建，app/test APK SHA-256 为 `a3bae9a4…b06f` / `eeee0d77…401a`）。`VesqenAppTest` 新增和改动的 15 项全部通过：崩溃提示 4 项、严格 USB 首次说明和试用结束 2 项、激活码 1 项、使用统计说明 3 项、空曲库 2 项、扫描后停在顶部 1 项、大字号设备报告按钮 2 项。`PlaybackResumptionDeviceTest` 1 项通过。vivo 的外部来源确认要在手机上点“继续安装”；点过之后包已装好，但 adb 和 Gradle 的安装命令一直不返回（`ShellCommandUnresponsiveException`），所以改为核对设备上的 APK 哈希后直接运行 `am instrument`。fast_freezer 冻结过一次测试进程，按 `build/qa/run-vivo.sh` 的办法把 `ComponentActivity` 拉到前台。
- 耳机键恢复（#52）在 iQOO 上也不成立（2026-10-15，“Vesqen Test”，所有者同意先停掉当时占着媒体按键会话的 `com.phoenix.read`）。从曲库点歌播放后，系统记住的按键接收者是 Media3 的 `MediaButtonReceiver`（`Last MediaButtonReceiver` 显示这个组件）。暂停、回到桌面，用 `run-as … kill -9` 结束进程后：
  - `input keyevent 87`（下一首）没有发给 Vesqen，而是被 vivo 的 `com.vivo.media.FeatureService.handleMediaButtonEventTransfer` 转给网易云音乐。网易云被拉起并开始播放，大约 13 秒后用 `am force-stop` 停掉；从这以后，系统记住的接收者变成了网易云。
  - 重新从界面播放、暂停、结束进程，记住的接收者又回到 Vesqen 后，`input keyevent 126` 和 `cmd media_session dispatch play` 都没有唤起 Vesqen，也没有唤起别的应用。
  - 广播记录里只有发给网易云的两条 `MEDIA_BUTTON`，系统一次也没有把按键广播发给 Vesqen 的接收器。所以在这台 vivo 上，按键怎么分发由 vivo 自己决定，不看 Android 记住的接收者。
  - 杀掉进程后，系统安排 1 秒后重启 `PlaybackService`（`am_schedule_service_restart`），但进程没有起来。
- 原因（2026-10-15，[#129](https://github.com/Sumire-no-kai/Vesqen/issues/129)）：两台手机都只给系统内置名单里的应用分发媒体按键。
  - iQOO：vivo 另存一份 `vivo_media_button_receiver`，只记 `vivo_audio_policy_media_session_applist` 里的应用（网易云、i音乐、Spotify 等）。i音乐一播放两份记录都换成它；Vesqen 播放时只有 Android 的记录变。
  - Honor：EMUI `third_app_filter.xml` 的 `mediasession` 名单（华为音乐、网易云、Spotify 等）。华为音乐播放本地歌曲时记录立刻换成它；Vesqen 播放时不变。
  - 实验包给 Vesqen 的会话补上和这些应用相同的广播 PendingIntent 接收器，两台手机的记录仍然不变，所以与接收器类型无关。实验应用已卸载，iQOO 的 Vesqen Test 已换回上面的 beta.3 测试包。两台手机记住的应用现在分别是 i音乐和华为音乐（原来都是网易云）。
- 没有覆盖：其他厂商和原生 Android 上的耳机键恢复；连接蓝牙和重启时保持安静；没有 DAC 时的严格 USB；应用内更新器，要等发布和官网 `updates/beta.json` 上线后再测。
- 结论：安装、versionCode 增大的同签名升级和数据保留通过（Android 9）。耳机键恢复在两台测试机上都不成立，beta.3 的发布说明不把它列为新功能，只写进已知限制。

### 2026-10-16 · beta.3 重新签名的 APK 从 beta.2 升级

- 候选：代码审查修复（PR #130）合并后，在 `master` 的 `f48fece4d0d0c7f743299c880f00b66e0806fc0d` 上手动运行 [GitHub APK release #38726792266](https://github.com/Sumire-no-kai/Vesqen/actions/runs/38726792266)，所有者批准签名后生成新草稿 `v1.0.0-beta.3`（之前 `0c77c95` 的草稿已删除，没有公开过，也没有留下标签）。`Vesqen-1.0.0-beta.3.apk` 的 SHA-256 为 `ffb512f6bb01e2c2819752a9d60f93adc507fb29aaa7d815ac331263ab01ee86`（未签名包 `6da46ab2…ae0a`）。同一次运行产出的未签名 Play 版 AAB 为 `2c4715a6ffe0a4bb90f54006dc503c10165846dc70aea0c5f7f2850bba7c2c9d`；R8 映射里 GitHub 版 `mapping.txt` 为 `f6a41d0b…5408`，Play 版为 `9bad8480…7763`。
- 本机复核草稿附件：`SHA256SUMS` 和 `release-manifest.json` 一致；apksigner 只有一个签名者，证书 SHA-256 为 `743e96fc…6293`，v2、v3 验证通过，zipalign 通过；aapt 显示 1.0.0-beta.3 / 12，带 `REQUEST_INSTALL_PACKAGES`（GitHub 版）。Play 版 AAB 的 Manifest 里没有 `REQUEST_INSTALL_PACKAGES`、更新安装界面和激活链接。草稿正文来自 `f48fece` 的发布说明：耳机键恢复只在已知限制里，两处 SHA-256 已填好。
- Honor STF-AL00 / Android 9（无线 adb）：卸载旧草稿，装回公开的 beta.2（base.apk `36954f80…e70a0`），使用统计选“不发送”，授权后扫描出 106 首。播放第 93 首“05. 前前前世”，收藏后暂停在 0:44。用 `adb install -r` 安装新草稿：versionCode 11 → 12，firstInstallTime 不变（2026-10-16 11:46:26），设备上 base.apk 哈希为 `ffb512f6…ee86`。打开后没有统计说明和崩溃提示；曲库完整，队列（第 93 / 106 首）、进度 0:44 和收藏保留，输出为系统输出。从 0:44 继续播放到 0:50，前台服务和 Media3 的媒体通知正常，暂停正常。“高级功能”一行显示“2.0 前免费”，页面是 2.0 之前免费的说明。
- 崩溃提示：`adb shell am crash` 后重新打开，出现“Vesqen 上次意外退出了”。“查看并发送”打开设备报告，生成后点“分享”，在系统分享面板返回取消；重新生成的报告里那条崩溃记录（`android.app.RemoteServiceException`）仍在，符合 PR #130 的改动：只有上传成功才删除。没有上传。
- 结束后 Honor 回到原来的前台应用（SkyLink），Termux 一直在运行。
- 没有覆盖：和 2026-10-14 一节相同（耳机键恢复在测试机上不可用、蓝牙和重启、无 DAC 时的严格 USB）；应用内更新器要等发布和官网 `updates/beta.json` 上线后再测。PR #130 的设备测试结果见该 PR。
- 结论：安装、versionCode 增大的同签名升级和数据保留通过（Android 9）。
- 发布后的应用内更新器（2026-10-16，Honor，USB）：官网 `updates/beta.json` 上线 beta.3 后，卸载再装回公开的 beta.2（`36954f80…`），统计选“不发送”，扫描 106 首，第 93 首收藏后暂停在 0:20。“设置 → 更新 → 检查更新”找到 1.0.0-beta.3 并显示完整版本说明；点“下载”后提示第一次安装要允许 Vesqen 安装应用，“安装”打开系统的“安装外部来源应用”页，所有者打开开关。再点“安装”，系统安装器查验后转到华为应用市场的安装检查页（提示“应用备案状态未知”，下面夹着其他应用推荐），点“继续安装”。安装完成：versionCode 11 → 12，安装来源记为 `io.github.sumirenokai.vesqen`，base.apk 哈希为 `ffb512f6…ee86`，与发布附件一致；队列（第 93 / 106 首）、进度 0:20 和收藏保留，“更新”一节显示“已是最新版本”。结束后 Honor 回到 SkyLink。

