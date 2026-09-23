# Vesqen 商业模式

2026-09-23 用户确认的主策略。它取代 2026-09-22 记录的"Play 付费下载、封闭测试低价购买再退款"路线。平台规则按下列官方文档核对，发布前如有变化应重新核对。

## 主策略

**免费下载 + 应用内试用期 + 到期后一次性应用内买断**（Google Play 一次性商品）。不做订阅、广告、Vesqen 账号或服务器。

选择理由：

- Vesqen 的核心价值依赖手机、ROM 和 DAC 的组合。试用期让用户在付费前确认自己的设备是否支持；付费下载只有购买后 2 小时的自助退款窗口（[退款说明](https://support.google.com/googleplay/android-developer/answer/2741495)），不足以验证 USB DAC。
- 同类本地播放器的惯例是一次性付费：[USB Audio Player PRO](https://play.google.com/store/apps/details?id=com.extreamsd.usbaudioplayerpro) 为付费下载加个别一次性内购，[Symfonium](https://symfonium.app/) 和 [Poweramp](https://play.google.com/store/apps/details?id=com.maxmpz.audioplayer.unlock) 为试用后一次性解锁。
- Vesqen 离线运行、没有服务器，也没有持续的服务成本，不适合订阅。
- 应用本身免费，封闭测试的测试者可以直接安装，不需要先购买再退款。

## 已核实的 Google Play 约束

- **免费是单向决定。** 免费提供过的应用不能再改为付费下载，只能用新包名另建应用（[定价说明](https://support.google.com/googleplay/android-developer/answer/6334373)）。本策略下 `io.github.sumirenokai.vesqen` 在 Play 上永久免费，这一点已确认。
- **试用期只能由应用计时。** 一次性商品只有"购买""租用"两种方式和"预购""折扣"两种优惠，没有免费试用；免费试用只属于订阅（[一次性商品概览](https://support.google.com/googleplay/android-developer/answer/16430488)）。
- **不需要 Vesqen 账号。** 购买绑定用户设备上的 Google 账号。应用在启动或回到前台时调用 `queryPurchasesAsync()`，即可在重装或换机后恢复已购权益（[集成 Billing](https://developer.android.com/google/play/billing/integrate)）。它返回有效订阅和未被消耗的一次性购买；官方文档没有说明离线时能否返回结果，因此重装后首次确认购买不能假定离线可用。
- **同一账号不能重复购买。** 解锁商品作为不消耗的一次性商品；已拥有的用户再次购买会收到 `ITEM_ALREADY_OWNED`。
- **购买必须在 3 天内确认。** 购买状态变为 `PURCHASED` 后 3 天内未确认，Google 会自动退款并收回权益。
- **服务器是建议而非必需。** Google 强烈建议在自己的服务器上校验购买以防作弊，但纯客户端实现也受支持。本策略采用纯客户端实现，接受防作弊能力较弱的代价。
- **应用内购买同样会公开地址。** 通过付费应用或应用内购买获得收入的账号属于 merchant account，Google Play 必须公开显示付款资料中的完整地址；个人账号另公开开发者名称和开发者邮箱（[开发者信息](https://support.google.com/googleplay/android-developer/answer/13634081)）。
- **新个人账号需要封闭测试。** 2023-11-13 之后注册的个人账号，上架正式版前需要至少 12 名测试者连续加入至少 14 天（[测试要求](https://support.google.com/googleplay/android-developer/answer/14151465)）。官方措辞表明每个新应用很可能都要单独满足，因此不采用 Poweramp 式的独立解锁包应用。
- **促销码可以赠送解锁。** 促销码支持一次性商品，非订阅类合计每季度最多 500 个（[促销说明](https://support.google.com/googleplay/android-developer/answer/6321495)）。

## Billing 库对"无网络"承诺的影响

2026-09-23 在本地分支 `test/play-billing-manifest`（提交 `8f04342`，未合并、未推送）接入 `com.android.billingclient:billing:9.1.0`，并生成 Release 合并 Manifest。与当前候选相比新增：

- `android.permission.INTERNET`：不是 Billing 库直接声明的，而是来自它的传递依赖 `com.google.android.datatransport:transport-backend-cct:3.1.8`，即 Google 的日志上报传输组件；
- `com.android.vending.BILLING`，以及查询 Play 结算服务所需的 `<queries>`；
- datatransport 的上报调度组件 `JobInfoSchedulerService`、`AlarmManagerSchedulerBroadcastReceiver`、`TransportBackendDiscovery`；
- 依赖 `play-services-base` 带来的 `GoogleApiActivity`。

结论：含 Billing 的构建具备联网能力，并带有通往 Google 的上报通道，"应用本身无法联网"这一表述对它不再成立。原理上，购买的联网由 Play 商店应用完成，因此移除 `INTERNET` 后购买可能仍然可用，但这必须在上传 Play 内部测试并配置测试商品后，在真机上验证。验证之前，不得对含 Billing 的构建宣称"不能联网"。

## 已确认的决定（2026-09-23）

- **GitHub 渠道**：提供不含 Billing 的完整开源构建（独立产品变体），保持无 `INTERNET`；Play 版带试用和应用内购买。
- **试用时长**：14 天。
- **试用结束后**（暂定）：基础本地播放永久免费，锁定 Vesqen 的独有功能，例如严格 USB 直出、完整 Audio Proof，以及之后的 DSP 和 DAC 实验室。具体锁定清单在收费版本的需求中确定。
- **防滥用**：推荐纯客户端方案，是否采用待用户确认。试用开始时间保存在应用私有存储中，并记录见过的最晚时间，系统时间回拨不会延长试用。卸载重装或清除数据可以重置试用，但同时会清空曲库文件夹授权、收藏、歌单和播放统计，这是已接受的风险。如果上线后数据表明试用滥用明显影响收入，再评估 [Play Integrity 设备召回](https://developer.android.com/google/play/integrity/device-recall)：它在 Google 服务器上为每台设备保存 3 个比特，重装或恢复出厂后仍然保留，不暴露设备或用户标识；目前处于 Beta，需要一个保管 Google Cloud 服务账号的无状态后端，但不需要用户账号。
- **不采用账号后端**：为授权建立 Vesqen 账号会违背"无账号、离线"的承诺，还要满足 Play 对应用内和网页端删除账号的要求（[账号删除要求](https://support.google.com/googleplay/android-developer/answer/13327111)），而且换个邮箱注册就能绕过。

## 版本安排

- **`1.0.0-beta.1`（冻结候选）**：GitHub Releases 和 Play 封闭测试都是免费的完整版本，不含 Billing 库、试用或付费墙。它不需要付款资料，因此也不会触发地址公开。Play 封闭测试承担新账号 12 人 14 天的要求。
- **收费版本**：之后的某个版本加入试用计时、付费墙、购买与 3 天内确认、恢复购买、本地权益缓存，以及退款后收回权益。这些是新功能，按冻结规则不进入 `1.0.0-beta.1`。
- **测试参与者**：收费版本上线时，可以用促销码向封闭测试参与者赠送解锁。

## 收费版本上线前需要决定和完成的事

1. **账号主体**：个人账号（接受公开地址），还是组织账号（需要 D-U-N-S 编号、组织电话和组织网站）。决定之后再建立付款资料。
2. **锁定范围**：把上面暂定的独有功能清单写成收费版本的需求和验收标准。
3. **防滥用方案**：确认是否采用纯客户端方案。
4. **联网权限的真机结论**：确认移除 `INTERNET` 后购买、确认和恢复是否正常；按含 Billing 构建的实际行为更新隐私政策和数据安全表单。
5. **价格和各国定价。**
6. **离线行为**：已解锁用户在离线时必须能正常使用，依赖本地缓存的权益；联网后再向 Play 核对，退款后收回解锁。
7. **两个渠道的说明**：两个渠道共用 application signing 身份，可以互相覆盖安装；在发布页说明 GitHub 版和 Play 版的区别。
