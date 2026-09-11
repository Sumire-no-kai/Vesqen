# M4 验证矩阵与设备验收

本文件落实 [PRD M4](PRD.md) 的设备、证据、稳定性、性能和界面门禁。代码编译、单元测试、Android instrumentation 源码编译、`BIT-PERFECT ACTIVE`、DAC 屏幕采样率、听感或模拟录音都不能单独证明 `BIT-PERFECT VERIFIED`。

## 证据状态与不可变身份

每条组合记录只使用以下结果之一：`VERIFIED`、`FAILED`、`NOT_TESTED`、`MISSING_DEVICE`、`DEFERRED`。失败记录不删除或改写为通过；重新测试生成新的稳定 record id。

每次记录必须包含：应用版本名/code、已安装 base APK SHA-256、手机 manufacturer/model、Android API、ROM fingerprint SHA-256、DAC VID/PID/名称/USB descriptor version、源和 sink 的采样率/声道/PCM encoding、测试向量 SHA-256、时间、方法、采样信号点和证据位置。APK、ROM、DAC descriptor、源格式或 sink 格式任一变化，都必须重新判定，不能继承旧结果。

应用只接受由当前安装包签名证书对应私钥签署的离线 registry。导入不是 VERIFIED 开关：仅在严格 USB 状态为 `ACTIVE`、唯一物理 USB Audio Class 设备可识别且所有字段精确匹配时，播放器、Chain 与遥测才显示 `VERIFIED`。签名错误、字段重复、未知字段、超出 256 KiB、组合不匹配和非 ACTIVE 状态均 fail closed。诊断可导出稳定 record id、方法和测试向量哈希；证据位置按文本隐私策略脱敏。

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

4. 使用与候选 APK 相同的 Android 签名私钥签名。密码只从 `VESQEN_KEYSTORE_PASSWORD` 与可选的 `VESQEN_KEY_PASSWORD` 环境变量读取，不能写进命令、仓库或日志：

   ```powershell
   java tools/M4VerificationSigner.java sign <payload.json> <keystore> <JKS-or-PKCS12> <alias> <new-registry.json> --no-overwrite true
   ```

5. `tools/m4_verification.py inspect` 只检查 envelope、Base64 和 payload 结构，并明确报告 `signatureCryptographicallyVerified=false`；密码学验签由 signer 自检和应用导入执行。

测试向量是整数算法合成的双声道 PCM，不含第三方录音，manifest 标为 CC0-1.0 并记录每个 WAV 的哈希、采样率、位深、声道、时长和字节数。数字采集必须位于能证明 Android 到 DAC 之间 PCM 的信号点，记录采集设备/固件/时钟/线缆与逐样本比较命令；模拟输出录音只能作为模拟链路观察，不能升级为数字 bit-perfect 证据。

## 当前矩阵（2026-09-08）

| 组合/门禁 | 状态 | 当前证据与关闭条件 |
| --- | --- | --- |
| Honor STF-AL00 / Android 9 / API 28 / 普通系统输出 | `PARTIAL_PASS` | 候选 APK 身份、签名 registry 存储/拒绝、55 项 UI、普通扬声器短流程、100 次 Chain 生命周期和数据保留已通过；Debug 启动/体积基线已采集。不具备 API 34 mixer，不能进入严格 USB 或 VERIFIED。详见下方执行记录。 |
| iQOO V2171A / Android 15 / API 35 / 无 DAC | `PARTIAL_PASS` | 最终候选的 registry/cache 3 项、无 DAC 严格模式 8 轮 fail-closed、55 个不同 UI 用例、100 次 Chain 生命周期、Profile 启动基线和覆盖安装数据保留已有证据；因没有 DAC，不能证明严格 USB `ACTIVE` 或 `VERIFIED`。详见下方执行记录。 |
| Android 14+ 手机 A × DAC A/B | `MISSING_DEVICE` | 需要真实 UAC 设备、支持/不支持格式、设置/读回/route、拔插与失败清理。 |
| Android 14+ 手机 B × DAC A/B | `MISSING_DEVICE` | 第二台现代手机不得由 Honor API 28 或模拟器替代。 |
| 至少一条外部数字逐样本组合 | `MISSING_DEVICE` | 需要有效签名记录并在精确组合上实际显示 VERIFIED。 |
| 8 h 普通/严格播放、2 h 24/96、3×15 min 采样、30 min 录制 | `DEFERRED` | 按用户安排暂缓；短测和脚本不能替代。 |
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
