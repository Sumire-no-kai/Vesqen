# Vesqen Product Requirements Document

> Local lossless audio, without compromise.

- 文档状态：Draft v0.9
- 更新日期：2026-09-01
- 产品形态：Android 本地音乐播放器
- 开源许可证：Apache License 2.0
- 最低安装版本：Android 8.0（API 26，产品支持基线）
- 核心直出目标：Android 14+（API 34）官方 USB bit-perfect 能力
- 项目创建基线：`compileSdk 36`、`targetSdk 36`；后续随稳定工具链和 Google Play 要求升级

## 1. 产品摘要

Vesqen 是一款轻量、离线优先、面向本地音乐库的 Android 播放器。产品重点不是堆叠流媒体、社交或云服务，而是高效完成音频解码、清楚展示完整播放链路，并在设备支持时通过 USB Audio Class DAC 使用 Android 官方 bit-perfect 输出能力。

Vesqen 必须把“文件是无损格式”“系统报告支持 Direct Playback”“已请求 bit-perfect”以及“完整链路经过实测验证”区分开来。产品不得仅凭 Hi-Res 标签、USB 路由、DAC 屏幕采样率或 API 调用成功就宣称已经实现严格 bit-perfect。

## 2. 产品愿景

让用户能够回答三个问题：

1. 我正在播放的源文件究竟是什么？
2. 手机当前如何解码和输出它？
3. 这条链路是否经过重采样、混音、音量缩放或 DSP？

Vesqen 的长期差异化来自“可验证的播放链路”，而不只是格式数量或主观音质宣传。

## 3. 目标用户

### 3.1 核心用户

- 在手机上管理和播放本地 FLAC、ALAC、WAV、AIFF 等音乐的用户。
- 使用 USB-C 外接 USB DAC、便携解码耳放或台式 DAC 的用户。
- 希望知道实际采样率、位深、解码器和输出路径的音频爱好者。
- 重视离线、隐私、低功耗和小体积应用的用户。

### 3.2 次级用户

- 需要观察 Android 设备音频能力的开发者与测试人员。
- 希望比较不同手机、ROM 和 DAC 输出行为的发烧友。
- 将本地端侧音频分析用于整理曲库的高级用户。

## 4. 产品目标

### G1：可靠的本地播放

稳定扫描、索引并播放常见有损和无损音乐文件，支持后台播放、队列、专辑封面、标签和无缝衔接。

### G2：高效使用现有 SoC

优先使用经过测量后最节能、稳定的解码路径，包括平台优化解码器、CPU SIMD、音频 DSP 或硬件 offload。目标不是“跑满 SoC”，而是在不破坏信号的前提下降低 CPU、内存、温升与耗电。

### G3：透明展示音频链路

以用户可理解、开发者可审计的方式展示源文件、解码输出、系统路由、USB DAC、混音器行为、重采样、DSP、音量控制和缓冲状态。

### G4：受控的 USB 无损直出

在 Android 14+ 且 ROM/HAL/DAC 实际暴露相应能力时，使用官方 preferred mixer attributes 和 `MIXER_BEHAVIOR_BIT_PERFECT`。无法满足条件时必须明确失败或要求用户主动切换兼容模式，不能静默重采样后继续显示 bit-perfect。

### G5：轻量与离线优先

核心播放功能无需账号、云端或网络权限。高级能力以独立模块实现，避免 AI、网络曲库或实验性 USB 引擎拖累核心播放器。

### G6：可复现的工程证据

发布性能、格式或 bit-perfect 声明时，提供对应版本、手机、ROM、DAC、音频格式和验证方法，形成公开设备矩阵。

## 5. 非目标

首个稳定版本不以以下能力为目标：

- 在线流媒体聚合、DRM 音乐服务或音乐商店。
- 社交动态、评论、关注和广告推荐系统。
- 云账号、跨用户同步或强制遥测。
- AI 升频、AI 音质增强或任何进入 bit-perfect 输出链的生成式处理。
- 对所有 Android 手机、ROM、USB-C 配件作统一 bit-perfect 承诺。
- 通过 Root、系统补丁或私有厂商接口修改手机音频栈。
- 首版自研完整 UAC2 等时传输引擎。

## 6. 产品原则

1. **真实优先**：不把格式无损等同于输出无损。
2. **失败可见**：直出失败必须清楚说明原因，不能静默降级。
3. **默认简单**：普通用户打开即可播放，高级信息按需展开。
4. **模式隔离**：系统播放、DSP 播放和 USB bit-perfect 模式具有明确边界。
5. **测量驱动**：只有测量证明有收益时才引入自研解码或硬件加速路径。
6. **模块化轻量**：实验性编解码器、旧系统 USB 引擎和 AI 模型不得成为核心安装包的强依赖。
7. **本地隐私**：核心功能不上传音乐、标签、封面或收听历史。
8. **信息自由**：默认界面保持克制；在 Audio Proof、实验室和诊断页面尽可能开放可验证信息、布局与安全参数，让用户自行决定信息密度。
9. **播放优先、证据随行**：曲库和播放页先帮助用户选择与控制音乐；格式、链路、来源和可信度按需展开，不用高级信息阻挡普通播放。
10. **一个明确的下一步**：首启、空曲库、无当前曲目、输出变化和错误状态都必须提供一个最明显的主操作，并保持 `Library / Now / Chain` 导航位置稳定。

## 7. 关键术语与声明等级

### 7.1 输出状态

| 状态 | 含义 | 是否允许显示“bit-perfect” |
| --- | --- | --- |
| `SYSTEM MIXED` | 通过 Android 常规混音路径输出，可能发生混音、音量缩放、DSP 或重采样 | 否 |
| `DIRECT SUPPORTED` | 系统 API 报告给定格式存在 direct/offload/bitstream 支持 | 否 |
| `BIT-PERFECT AVAILABLE` | USB 设备暴露 API 34 bit-perfect mixer attribute | 可显示“可用”，不得显示“已验证” |
| `BIT-PERFECT ACTIVE` | 应用成功设置 bit-perfect mixer attribute，播放配置与所选 attribute 匹配 | 可显示“已启用”，必须附设备边界 |
| `BIT-PERFECT VERIFIED` | 指定手机、ROM、DAC 和测试格式已通过外部或等效数字验证 | 是，仅限设备矩阵中的精确组合 |

### 7.2 重要限制

- USB-C 只是物理接口，不等同于数字 USB Audio Class DAC。
- 被动模拟 USB-C 耳机、3.5 mm 转接器和 USB Audio Class DAC 必须分别识别。
- `getDirectPlaybackSupport()` 的结果不能替代 API 34 bit-perfect mixer 能力检测。
- DAC 显示与源文件相同的采样率不足以证明位级一致。
- `BIT-PERFECT VERIFIED` 不得跨 ROM 更新、手机型号或 DAC 型号推断。

## 8. 总体功能范围

### F1：本地曲库

- 通过 MediaStore 发现用户音乐。
- 通过 Storage Access Framework 授权一个或多个文件夹。
- 增量扫描、新增/删除/移动检测和手动重新扫描。
- 按歌曲、专辑、艺术家、流派和文件夹浏览。
- 搜索、排序、收藏、最近播放和播放次数。
- 播放列表和当前播放队列。
- 大型曲库扫描可暂停、恢复，不阻塞播放和主界面。
- 默认曲目行保持 72 dp 左右的紧凑节奏，主要展示 48 dp 封面、标题、艺术家、播放状态和更多操作；允许加入一枚紧凑的源音频质量标签，但时长、专辑、文件位置和完整遥测不得堆叠在默认列表中。
- 曲目详情通过明确的详情页或底部浮层展开；列表中的整行主操作始终是播放该曲目。

### F2：格式与元数据

核心目标格式：

- 无损：FLAC、ALAC、WAV、AIFF。
- 常用有损：MP3、AAC/M4A、Ogg Vorbis、Opus（以平台和所选解码器支持为准）。
- PCM：16-bit、24-bit、32-bit integer；浮点 PCM 作为后续兼容项。
- 采样率：按解码器、系统输出设备和 DAC 实际能力协商，不宣传固定上限。

元数据目标：

- 标题、艺术家、专辑、专辑艺术家、曲号、碟号、年份、流派。
- 内嵌封面和同目录常见封面文件。
- 文件大小、时长、编码、声道、位深、采样率和码率。
- 标签缺失、异常编码和损坏封面不应阻止播放。
- M1 封面读取优先使用 Android 10+ MediaStore provider 的 `content://` 缩略图，不依赖已弃用的 `ALBUM_ART` 文件路径。系统缩略图不可用时使用中性品牌占位，且绝不影响播放；不得调用会先分配完整、无上限 APIC byte array 的内嵌图片 API。若要恢复低版本或异常媒体的内嵌封面，必须先引入经过大小上限与格式测试的流式解析器。

高级格式候选：APE、WavPack、CUE、DSD/DoP。它们属于独立里程碑，不进入首个稳定版承诺。

#### F2.1 源音频质量标签

在 M4 之前完成源音频主标签；基础版本纳入 M1，实时增强纳入 M2：

- 曲库列表和 Now 播放页可以显示紧凑的 `LOSSLESS SOURCE` 或 `HI-RES SOURCE` 文字标签。
- `LOSSLESS SOURCE` 只根据已识别的无损编码与有效源参数判定，例如 FLAC、ALAC 或无损 PCM；不得据此宣称最终输出无损。
- `HI-RES SOURCE` 使用 Vesqen 公开、可测试的源文件规则。初始候选规则为无损源、位深至少 24-bit 且采样率至少 88.2 kHz；正式实现前须确认阈值、文案和商标边界。
- 标签旁优先展示两个主要源参数，例如 `24-bit · 96 kHz`；码率可作为另一枚次级值或进入详情。
- 曲库列表使用扫描得到的静态/平均码率；Now 页面在 M2 telemetry 可用时可以显示窗口实时码率，并明确区分 `LIVE` 与 `AVG`。
- 标签只使用 Vesqen 自有文字和视觉，不使用未经授权的 Hi-Res Audio 标志。
- 元数据缺失、冲突或无法可靠判定时不显示质量结论，只显示已确认的参数或 `UNKNOWN`。

### F3：播放体验

- 播放、暂停、上一首、下一首、拖动进度。
- 后台播放、媒体通知、锁屏控制和耳机按键控制。
- 音频焦点、来电/导航提示和外设断开处理。
- 队列编辑、单曲循环、列表循环和随机播放。
- 支持无缝播放，并对专辑连续曲目提供可验证测试样本。
- 恢复上次队列和播放位置。
- 普通模式支持系统音量。
- 睡眠定时器作为稳定版后续的小型功能。
- 曲库底部提供持久 mini-player，包含封面、标题/艺术家、上一首、播放/暂停和下一首；非按钮区域打开完整播放页。它作为独立的 72 dp tonal card 悬浮在紧凑导航上方，以 4 dp 间隙与之分离；导航和曲库画布连续，禁止为 mini-player 在底部另铺一整块近白/近黑背景。
- 完整播放页采用“封面舞台 + 单一底部运输台”：上部是大封面，下部不透明运输台承载固定单行曲名、空间允许时的艺术家/专辑、事实性输出状态、进度和控制；曲目详情与播放链证据分别通过明确入口打开，不在主控制区堆叠参数。
- Now 页在不挤压核心控制的前提下展示源音频主标签及两个主要参数；标签属于源文件事实，必须与当前输出状态在视觉和语义上分开。
- Now 的可见材质采用 Nocturne Graphite：Canvas `#101415`、Dock `#191F20`、Raised `#202728`、Artwork Frame `#252C2D` 是同一条近中性石墨阶梯。真实封面只能在 36 dp 模糊、22% 输入与 82% Canvas 遮罩后提供约 3.96% 的低频反光；无封面或读取失败时保持完全中性。禁止回到紫色上域/橄榄底座的硬切、绿色大面积背景、装饰渐变、玻璃卡、亮分割线或描边加大阴影。
- 上一首／播放暂停／下一首必须组成居中的主控制组；一个“播放顺序”入口、明确可点的“播放会话”入口与圆形 `i` 为底栏次级控制，不能与三枚运输控制同排抢占层级。`SYSTEM MIXED` 只在 Now/Chain 的事实入口出现，不在 mini-player 中挤占标题空间。
- 完整播放页在紧凑或宽屏窗口中都是沉浸式焦点面：隐藏承载顶层目的地的底部导航或 navigation rail，让一个受保护的深色 Now Surface 覆盖全窗口；工具栏和 Android Back 都返回其来源（通常为 Library），状态 chip 仍可进入 Chain，不允许形成回退到桌面的死路。
- 完整播放页不是纵向信息流：曲名与页头始终为较小的单行，超长曲名以横向跑马灯呈现而不换行；页面按可用高度和字体缩放收缩封面与间距，绝不通过竖向滚动或隐藏基础播放控制来容纳内容。极端字体缩放时，次要 artist/album 会收纳；route chip 则由固定 footer 中唯一的 48 dp Chain 图标入口替代，仍保持可达。
- 完整播放页提供明确可点的播放会话入口，仅在固定的上部封面舞台内原位切换当前可证明的播放/暂停、进度、剩余时间和队列位置；页头、背景、运输台、进度和核心控制不随会话切换移动或重排。极端字体缩放时，固定运输台仍保留进度，会话卡收敛为状态与队列，避免裁切或纵向滚动。会话舞台使用受控淡变/微缩放而不是全页横向分页或照片式拖动；不得伪造实时编码、采样率、PCM 或 bit-perfect 指标。Android Back 先收起会话再返回来源。
- mini-player 展开为完整播放页时使用约 240 ms 的空间连续转换；系统开启减少动效时改为约 80 ms 的交叉淡入淡出。
- 随机、列表循环和单曲循环不得拆成多个底栏按钮。一个熟悉的 48 dp“播放顺序”入口按“顺序播放 → 随机播放 → 列表循环 → 单曲循环 → 顺序播放”轮换；该普通循环必须在每次切换时清除其他 Media3 播放顺序开关，不能由 Vesqen 留下隐藏的“随机 + 循环”组合。若外部 Media3 控制器传来复合状态，仍由这一颗按钮以组合图标和准确 TalkBack 状态如实展示；下一次点击统一回到顺序播放。顺序播放使用中性弱化色，其余三态使用品牌主色；随机、列表循环和单曲循环分别使用标准 shuffle、repeat 与 repeat-with-`1` 图标。每次用户点按后，只有实际 controller 状态发生变化才在 Now 顶部安全区给出约 1.5 秒的非持久、居中悬浮小气泡，明确新模式并作为 polite live region；它不得覆盖运输台或根据预期 next state 预先提示。
- Now 返回来源使用约 180 ms 的反向空间转换；用户点按上一首/下一首时，仅封面与曲目信息以约 220 ms 的对应横向方向过渡，运输控制台保持空间稳定。减少动效时统一使用上述 80 ms 交叉淡入淡出回退。

#### F3.1 连续倍速播放

- 普通/DSP 播放模式提供连续倍速控制，初始目标范围为 0.50×–2.00×，默认 1.00×。
- UI 表现为连续滑杆，实际速度至少支持 0.01× 的有效调节精度；同时提供 0.50×、0.75×、1.00×、1.25×、1.50× 和 2.00× 快捷值。
- 滑杆接近 1.00× 时应吸附到标准速度，并通过轻微触觉或视觉反馈确认；不得要求用户精确拖动才能回到原速。
- 控件旁始终提供独立、明确且支持 TalkBack 的 `重置为 1.00×` 操作，即使滑杆位置异常或数值不可见也能恢复。
- 默认启用保持音调的 time-stretch；是否开放独立 pitch 调节作为 M5 评估项，不进入基础倍速承诺。
- 倍速状态至少在当前播放会话中保持；是否跨会话记忆必须由用户主动启用，默认新会话为 1.00×。
- 倍速不为 1.00× 时，Now 和 Dashboard 必须持续显示当前速度，避免用户忘记已经修改。
- 任何非 1.00× 倍速都会改变 PCM/播放时钟，因此不得在 bit-perfect 模式中启用。

### F4：普通系统播放模式

- 默认允许手机扬声器、蓝牙、3.5 mm 耳机和系统 USB 路由。
- 清楚显示当前系统输出设备与路由变化。
- 显示系统可能混音或重采样，不能以源文件参数冒充最终输出参数。
- EQ、响度均衡、ReplayGain、crossfade 等处理如后续实现，只能在普通/DSP 模式启用。

### F5：USB bit-perfect 模式

- 只允许数字 USB Audio Class 输出设备进入该模式。
- 应用启动时检测 Android API level，并由统一策略解析器选择当前系统可用的 USB 输出候选路径。
- 策略解析器必须在 USB DAC 插拔、USB 权限变化、源格式变化、路由变化和用户切换模式时重新判定。
- 检查系统版本、USB 输出设备、支持的 mixer attributes、bit-perfect behavior、源格式和所选实现是否匹配。
- 根据源音频选择匹配的采样率、声道和编码配置。
- 设置 preferred mixer attributes，并确认当前设置状态。
- 禁用软件音量、EQ、响度均衡、ReplayGain、crossfade、淡入淡出和其他 DSP。
- 音量交给 DAC 硬件控制，并在界面中解释手机音量键行为。
- USB DAC 拔出、权限丢失、路由切换或格式不支持时立即停止直出。
- 失败时显示具体原因，并提供用户主动选择的兼容模式。
- 不允许自动降级后继续保留 `BIT-PERFECT ACTIVE` 标识。

#### F5.1 USB 输出策略选择

| 运行环境 | 启动时选择的候选策略 | 实际启用条件 |
| --- | --- | --- |
| API 34+ | `OfficialMixerBitPerfectAdapter` | 已连接数字 USB DAC，ROM/HAL 暴露 bit-perfect mixer attribute，且源格式与 attribute 匹配 |
| API 26–33，M7 未提供或未启用 | `SystemUsbRouteAdapter` | 只提供 Android 常规 USB 路由，状态保持 `SYSTEM MIXED` 或其他有证据支持的非 bit-perfect 状态 |
| API 26–33，M7 已提供 | `DirectUsbHostAdapter` 候选 | 用户明确启用实验模式、授予 USB 权限，且 UAC 版本、endpoint、格式和设备组合经过支持矩阵确认 |

系统版本只用于筛选候选 Implementation，不能单独证明直出可用。应用启动时可以显示“官方路径候选”“需要高级 USB 引擎”或“仅普通系统路由”，但只有完成设备与格式能力检查并成功配置输出后，才能显示 `BIT-PERFECT ACTIVE`。

策略选择集中在 `UsbOutputStrategyResolver` Module。它通过小型 Interface 接收系统版本、USB 设备、源格式、用户模式和可选模块状态，返回包含所选 Adapter、声明等级和失败原因的 `UsbOutputDecision`。UI、播放队列和具体 Adapter 不得各自复制版本判断。

### F6：Audio Proof 音频链路面板

Audio Proof 在顶层导航中显示为 `Chain`，同时允许从 Now 进入与当前播放会话绑定的信息 Dashboard。默认层先给出用户可理解的当前声明、输出类型和是否存在需要处理的问题；源、解码、处理、系统路由、USB 与实时观测按层展开。默认页面不得直接呈现完整工程仪表盘。

显示以下信息：

- 源文件：容器、编码、采样率、位深、声道、码率和文件大小。
- 解码器：名称、实现来源、软件/平台/offload 路径和解码输出格式。
- 处理链：播放倍速、pitch 保持、ReplayGain、EQ、crossfade、响度、格式转换和其他 DSP 状态。
- 系统路由：扬声器、蓝牙 codec、耳机或 USB 设备。
- USB 设备：制造商、产品名、VID、PID、设备类型和可读取的能力。
- 输出：请求格式、系统 mixer attribute、Direct Playback 支持位和实际可观察状态。
- 稳定性：缓冲大小、underrun 次数、路由切换和最近一次错误。
- 声明：`SYSTEM MIXED`、`DIRECT SUPPORTED`、`BIT-PERFECT AVAILABLE`、`ACTIVE` 或 `VERIFIED`。

高级用户可以导出一份不含音乐内容和个人路径的诊断报告。

#### F6.1 Now 到播放信息 Dashboard

- M2 应提供从 Now 进入播放信息 Dashboard 的连续动画入口，可以研究左滑、右滑、上滑或下滑，但最终方向在 M2 交互设计阶段确定。
- 手势方向必须与系统返回手势、歌词入口、队列、TalkBack 和单手操作共同评估；在 M2 前不把某一方向写成不可更改的产品约束。
- Dashboard 出现应采用局部揭示、浮现、层级展开或其他保持空间连续性的动画，不得实现为生硬的整页 pager、翻书或与内容无关的“翻页”效果。
- 手势不能成为唯一入口；Now 必须保留明确可点击且可被 TalkBack 发现的播放信息/Chain 入口。
- Dashboard 具有清晰的主次层级：顶部先显示源标签、主要参数、当前输出、输出声明和最重要异常；详细 decoder、route、telemetry 与诊断数据按区块展开。
- 本节只定义能力与体验约束，不提前固定最终布局、手势方向、动画曲线或歌词页面关系；这些内容在 M2 原型和真机测试后确定。

#### F6.2 按输出路由动态加载信息

Dashboard 根据实际活跃输出动态显示对应详情，并保留一组跨路由共享的源、decoder、缓冲和 Vesqen CPU 信息：

- USB DAC：设备名、VID/PID、可观察的 UAC/格式能力、请求与观察到的输出格式、mixer attribute、直出策略、权限、DSP/音量状态和 bit-perfect 声明证据。
- 3.5 mm/有线模拟输出：有线设备类型、当前路由、请求与可观察输出格式、系统混音/DSP/音量状态；无法观察手机内部 DAC 后半段时必须明确说明，不推测芯片或模拟链参数。
- Bluetooth Classic/LE Audio：输出类型、设备、A2DP/LE Audio/SCO profile、连接与播放状态；在公开系统接口、系统版本和用户权限允许时，进一步展示当前 codec 配置、采样率、bits per sample、channel mode、可确认的 bitrate/quality mode 和本地/可选能力。
- 内置扬声器：扬声器路由类型、请求与可观察输出格式、声道、系统混音、音量、DSP/音效状态和其他可确认的处理。
- 路由切换时对应区块更新，但源文件、播放控制和 Dashboard 的主层级保持稳定，避免整个页面像翻页一样突然替换。

蓝牙监控以 Android 公开能力为上限。需要时向用户解释并请求 `BLUETOOTH_CONNECT`；权限被拒绝、ROM 未暴露、查询要求 privileged 权限或实际协商参数不可取得时，显示 `UNAVAILABLE` 和原因。不得使用隐藏接口、Root 或开发者选项文本推测当前 codec，更不能把设备支持列表当作正在使用的配置。

#### F6.3 实时观测

在播放页的可选信息区、Audio Proof 和设备实验室中，按设备与系统实际可取得的数据展示：

- 音频流：窗口实时码率、平均码率、压缩帧大小、解码输入/输出速率、PCM 数据吞吐、播放位置和时钟偏差。
- 解码：当前 decoder、软件/平台/offload 路径、输入/输出 PCM 格式、单帧或窗口解码耗时和格式切换事件。
- 播放：缓冲占用、目标缓冲、预缓冲、underrun、丢帧/错误、seek 耗时、gapless 转换和 AudioTrack timestamp。
- 进程与 SoC：Vesqen 进程 CPU 占用、播放线程 CPU time、内存、GC、可公开读取的 CPU 频率/核心状态、thermal status 和 decoder/offload 状态。
- 功耗：系统公开信息允许时展示电量、温度、电流或功率估算，并注明设备支持与估算方法。
- 输出：当前路由、请求/观察到的格式、mixer attribute、USB DAC、音量/DSP 状态和最近一次策略决策。

“实时码率”默认表示最近时间窗口内读取的压缩音频字节数推导出的码率，不得把文件平均码率冒充实时值。“SoC 占用”不能作为笼统且无法验证的单一数字；优先显示 Vesqen 进程 CPU、可读取的系统指标和已确认的 decoder/offload 路径。厂商未公开的数据必须显示为不可用，不能通过设备营销参数推测。

#### F6.4 数据可信度

每项高级指标必须携带数据来源、更新时间和以下可信度之一：

| 等级 | 含义 |
| --- | --- |
| `MEASURED` | 直接来自当前播放内核、计数器或 Android 公开系统数据 |
| `DERIVED` | 由可见原始数据和公开公式计算，例如窗口实时码率 |
| `ESTIMATED` | 只能近似估计，例如部分设备的瞬时功耗 |
| `UNAVAILABLE` | 当前系统、ROM、权限或设备不提供可靠数据 |

历史图表、导出报告和 UI 标签必须保留该可信度，不能在展示层丢失来源信息。

#### F6.5 自由布局与安全调节

- 自由布局、图表和刷新率属于用户主动进入的高级视图；普通 Chain 摘要保持稳定，不继承高级信息密度。
- 用户可以选择、隐藏、固定、排序和分组指标，并为播放页、Audio Proof 和实验室保存独立布局。
- 支持紧凑、详细和图表视图；单位、时间窗口、图表历史长度与刷新间隔可以调节。
- 刷新间隔候选为 250 ms、500 ms、1 s、2 s 和 5 s，默认 1 s，并提供低功耗模式。
- 支持开始/停止本地诊断录制，用户明确操作后才持续采样；导出前进行隐私清理。
- 纯展示设置尽可能自由；影响播放的工程参数必须位于高级实验模式，具有安全范围、默认值、逐项重置和一键全部恢复。
- 可调工程参数候选包括缓冲目标、预缓冲、decoder 偏好、低功耗/低延迟策略和曲库扫描并发度。
- 任何会改变 PCM 数据、采样率、音量或 DSP 的调节，在 bit-perfect 模式下必须锁定或触发退出，不能以“高级自由”为由破坏声明。

#### F6.6 初步实现思路

建立独立 `PlaybackTelemetry` Module，通过小型 Interface 按需返回 `TelemetrySnapshot`。其 Implementation 在内部汇总播放内核、进程/系统、thermal/power 和 USB/audio Adapter；UI 只消费统一快照，不直接轮询系统或播放器内部对象。

各输出类型可以提供独立的 route telemetry Adapter，并投影为同一份带可信度的 Dashboard 数据模型；具体 Adapter 命名和 UI 组合方式在 M2 设计与技术验证时决定。

Telemetry Module 默认不常驻采样。只有相关页面可见或用户主动开始诊断录制时启用定时采样；页面关闭、播放停止或录制结束后立即取消。播放内核通过事件提供高频原始计数，Telemetry Implementation 按用户选择的窗口聚合，避免 UI 刷新频率反向干扰音频线程。

### F7：设备与 DAC 实验室

- 保存本机系统版本、Build fingerprint 和音频能力快照。
- 保存用户连接过的 DAC 及其可观察能力。
- 对常用采样率、位深和声道组合执行非破坏性能力探测。
- 记录测试时间、应用版本、ROM、DAC 和结果。
- 支持导出和导入匿名设备能力报告。
- 官方设备矩阵只收录可复现、带证据等级的结果。

### F8：DSP 与播放调节模式（独立于 bit-perfect）

候选能力包括：

- 连续倍速播放和默认保持音调的 time-stretch。
- ReplayGain。
- 参数均衡器。
- Crossfeed。
- Crossfade。
- 响度归一化。

#### F8.1 均衡器

- 均衡器位于普通/DSP 播放模式，初始关闭且默认曲线为平直。
- 至少提供参数均衡、若干清晰命名的预设、总开关、preamp/headroom 和削波风险提示；具体频段数量在 M5 原型与性能测试后确定。
- 始终提供独立的 `恢复平直` 和 `关闭均衡器` 操作；重置不得依赖逐个拖回所有频段。
- 用户预设可以保存、重命名和删除；内置预设始终可以恢复，用户修改内置预设时另存为用户预设。
- 切换输出设备时保留用户选择，但若设备或当前模式不支持，必须暂停应用并解释原因，不能静默应用不同曲线。
- Dashboard 显示 EQ 开关、当前预设、preamp/headroom 和可确认的削波状态。

任何非 1.00× 倍速或 DSP 启用后，界面必须退出 bit-perfect 状态。用户在 bit-perfect 模式中打开倍速或 EQ 时，应用必须先说明会退出直出并取得明确操作；用户进入 bit-perfect 时也必须先恢复 1.00× 并旁路全部 DSP。不得在后台自动切换后保留错误的直出声明。DSP 与倍速不是核心首发功能，也不得成为播放的默认前提。

### F9：渐进式自研播放内核（条件性核心演进）

Vesqen 不在首版从零重写 FLAC、ALAC 等编解码算法，而是在 M1–M4 先使用 Media3、平台解码器和 Android 音频 API 建立可靠基线。基线稳定后，再根据可测量的控制力、性能、功耗或兼容性收益逐步替换播放内核中的实现。

自研范围优先包括：

- 统一的播放命令、状态机、队列、时钟、缓冲和错误恢复。
- 数据源读取、容器解封装、解码调度、PCM 缓冲与输出之间的可替换接口。
- 普通系统输出与 USB bit-perfect 输出的模式切换、格式切换和 fail-closed 策略。
- 可审计的 PCM 格式、解码器、处理链、缓冲和输出状态事件，直接供 Audio Proof 使用。
- 经性能分析证明必要后，再用 NDK/C++、SIMD 或专用解码器替换局部实现。

不得为了“自研”标签复制成熟编解码器或一次性重写整个播放栈。每次替换必须能够按格式或设备回退到已验证的 Media3 实现，并通过一致的契约测试和设备测试。

### F10：高级 USB 引擎（实验性可选模块）

长期候选目标是在 Android 8–13 上绕过 Android 默认音频混音链，由 Vesqen 在用户态直接管理 UAC 设备并把解码后的 PCM 送到 USB DAC。Android USB Host 公开层负责设备枚举、用户授权、interface/endpoint 描述、control transfer 和连接生命周期；音频数据面另设可替换的 isochronous transport。

该模式的核心理念是：不把 OEM Audio HAL 或 Android mixer 作为严格直出的可信前提，由 App 掌握从本地音频文件到提交 USB 音频 packet 之间的完整用户态数据链：

`Local file → App extractor → App-controlled decoder → PCM ring buffer → clock/packetizer → native isochronous transport → USB DAC`

在该链路中：

- 目标格式初期使用经过审计、随 App 交付或由 App 明确选择的 FLAC/WAV software decoder，不把 OEM platform decoder/offload 作为严格路径的必要条件；这不要求 Vesqen 从零重写 FLAC 算法。
- 音频数据不得经过 `AudioTrack`、AAudio、OpenSL ES、AudioFlinger、Android mixer、系统音量处理或 OEM Audio HAL。
- App 负责源格式验证、解封装、解码输出格式、PCM buffer、软件音量/DSP 旁路、时钟同步、UAC packetization 和错误状态。
- Android 仍负责文件/USB 权限、进程调度、电源管理、Linux USB 栈、USB host controller 与物理传输；因此该模式是 App-owned user-space audio chain，不是“完全绕过 Android 操作系统”。
- Android 14+ 官方 bit-perfect 路径与 App-owned direct USB 路径是两个独立 Implementation，必须分别标识、测试和验证，不能共享未经证明的 `VERIFIED` 结论。

Android 公开 `UsbEndpoint`/`UsbRequest` 接口并不提供完整 isochronous streaming：公开异步请求只覆盖 bulk/interrupt endpoint。因此不得把普通 `UsbRequest.queue()` 描述成 UAC 等时音频实现。M7 必须先验证一条不需要 Root 的 native transport 路径，例如在系统和内核允许时基于 `UsbDeviceConnection` 授权后的 file descriptor 使用经过审计的 usbfs/libusb-compatible Implementation。该路径不是 Android 官方音频直出能力，不能假设所有 ROM、内核或商店分发环境均可用。

核心工程范围明确包括：

- UAC 协议：描述符解析、AudioControl/AudioStreaming interface、alternate setting、class-specific control、格式与 endpoint 协商。
- Isochronous streaming：帧/微帧 packetization、URB 队列深度、提交/回收、完成状态、错误包和热插拔取消。
- Clock synchronization：同步/自适应/异步 endpoint 行为、feedback endpoint、时钟漂移估计与安全修正。
- Buffer management：PCM 环形缓冲、水位、预缓冲、underrun/overrun、实时线程、背压和恢复策略。
- 可观测性：实际 packet/URB 统计、buffer 水位、feedback、漂移、underrun、设备错误、CPU、温升和功耗。

该模块具有显著兼容性、安全、许可证和维护成本，只有在 Android 14+ 路径完成、设备矩阵成熟且用户价值被验证后才启动。初始实验应先限定 UAC1 PCM 和少量参考 DAC，之后再评估 UAC2、异步 feedback、多声道与 DSD/DoP。整个路径不得依赖 Root、系统补丁或私有厂商音频接口。

### F11：轻量端侧音频智能（实验性可选模块）

- 对曲库抽取短时音频片段，离线生成乐器、人声、场景或相似度标签。
- 支持“相似曲目”和本地语义筛选。
- 使用量化小模型，并比较 CPU、GPU、NNAPI/NPU 路径。
- 默认不在播放期间运行；优先在空闲或充电时索引。
- 模型、运行时和索引可以单独下载或构建，不进入核心播放链。
- 必须记录模型大小、准确率、p50/p95 延迟、内存、功耗和回退路径。
- 不修改 bit-perfect 模式送往 DAC 的音频数据。

### F12：视觉识别、可访问性、语言与外观

- 正式视觉北极星为 `The Quiet Signal`：安静、精确、亲密，以克制表面和稀缺信号色服务音乐与事实状态。
- 正式产品标识为对称的 `Twin Paths` 双路径 V：外路径代表播放，内路径代表随行证据；禁止改成对勾、箭头、播放三角、音符、耳机、唱片或波形。
- 核心主题色为 Signal Moss `#9FBF4B`；深色活跃色为 `#BFD66B`，浅色实心活跃色为 `#536B1E`。普通页面 Moss 面积原则上不超过约 10%。
- `SYSTEM MIXED` 与 `DIRECT SUPPORTED` 使用中性色；`AVAILABLE` 使用 Moss 描边和空心图标；`ACTIVE` 使用 Moss 实心和活动点；`VERIFIED` 必须同时显示盾牌/证书提示及精确设备矩阵上下文。颜色不得单独提升声明等级。
- 可恢复限制使用 Warning Amber Bright `#F2C36B` / Deep `#7A4F00`，失败与破坏性操作使用 Error `#BA1A1A`；所有状态同时使用图标、文字和可执行说明。
- A 是深色曲库主表达，B 是同一系统的完整浅色主题，C 是 A/B 体系内的完整播放页，不得发展成三个互不相干的 UI 皮肤。
- 有活动播放时，Now 可作为 C 的受保护 Nocturne Graphite 焦点面覆盖外层深浅主题：其根 Surface 必须显式设置高对比前景色；状态栏背景延续 Canvas `#101415`，导航栏背景延续完全不透明的 Dock `#191F20`，并关闭 OEM 自动对比衬底。Canvas 与 Dock 仅以一层 20 dp Player Lift 分离，不以亮线、描边或第二层阴影分隔。浅色系统图标/手势标记保留在深色背景上以满足可读性。宽窗口中 rail 也必须让位，避免系统栏同时跨越深浅背景；退出 Now 后完整恢复外层系统栏状态。该例外不改变 Library / Now / Chain 的信息架构。
- 默认关闭 Android 动态色，确保 Logo、选择、证据、警告和错误语义稳定；未来若提供动态色，只允许作为用户主动选项，且不得改写品牌标识和证据状态颜色。
- 使用留白、色阶、低强度光影和必要的模糊建立层级，普通曲目行不使用硬分割线、描边卡片网格或装饰阴影。透明与模糊必须有高对比不透明回退。
- TalkBack 语义、逻辑焦点顺序、至少 48 dp 触控目标、字体缩放、颜色之外的状态表达和减少动效回退均为发布要求。
- 首发至少支持英文和简体中文界面；高级参数必须提供简明解释，不得只有缩写。
- `DESIGN.md`、`.impeccable/design.json` 与 `docs/brand/VISUAL_IDENTITY.md` 是正式实现基线；核心色、标识几何或交互架构变更必须同步这些文件并记录迁移理由。

### F13：开源与可维护性

- Apache-2.0 许可证。
- 公开构建说明、贡献指南、安全政策和版本路线图。
- 核心逻辑具备单元测试；关键播放状态具备集成测试。
- CI 至少执行构建、静态检查和单元测试。
- 第三方编解码器、模型和数据集必须记录许可证与来源。

### F14：信息架构与导航

- 顶层导航语义 ID 固定为 `Library / Now / Chain`，顺序在深浅主题和不同窗口宽度中保持一致；英文显示这些名称，简体中文本地化为 `曲库 / 正在播放 / 链路`。
- 紧凑窗口使用带文字标签的 64 dp 底部导航（系统 navigation inset 额外保留）；其颜色与曲库画布连续，标签单行且可省略，不能用强制固定高度裁切大字号。中等和展开窗口使用同顺序的 navigation rail。
- 首次启动和普通冷启动进入 Library。Now 没有当前曲目时，只说明“从曲库选择一首”并提供返回 Library 的主操作。
- Chain 没有播放会话时，只说明“开始播放后展示链路”并提供返回 Library 的主操作；不得显示空白仪表盘或无来源的默认参数。
- Settings、曲目详情、队列、搜索和实验室均为二级目的地，不增加永久顶层 tab。
- mini-player 以悬浮核心卡片位于顶层导航之上，不把整个底部区域变成第二个 Surface；点击其非控制区域进入 Now。Chain 状态 chip 可从 mini-player 或 Now 提供直达，但不能代替永久导航。
- Now 的焦点面是上述导航规则的空间例外：底部导航或宽屏 rail 在其中不常驻，但显式 Back、系统 Back 和 Chain 入口始终可达；返回后恢复稳定的 `Library / Now / Chain` 导航。

## 9. 用户主流程

### 9.1 首次使用

1. 用户启动应用。
2. 应用检测 API level、USB Host feature 和可选 USB 引擎状态，形成初始 USB 输出策略；此时不宣称 bit-perfect 已启用。
3. 应用说明本地优先和文件访问范围。
4. 用户选择 MediaStore 音乐或授权文件夹。
5. 应用在后台扫描并逐步展示曲库。
6. 用户在默认 Library 入口选择歌曲，mini-player 出现并开始普通系统播放；`Library / Now / Chain` 导航保持可见。

### 9.2 普通播放

1. 用户选择歌曲。
2. 播放器创建系统播放会话。
3. 曲库显示紧凑 mini-player；用户可继续浏览，也可点击其主体进入 Now 完整播放页。
4. Now 显示标题、艺术家、封面、进度、核心控制和事实性输出状态；源文件参数进入曲目详情，完整输出证据进入 Chain。
5. Chain 将模式标记为 `SYSTEM MIXED` 或其他有证据支持的状态，并先显示可理解摘要。

### 9.3 USB bit-perfect 播放

1. 用户连接 USB DAC 并授权。
2. 应用识别其为数字 USB Audio Class 输出。
3. 用户主动打开 USB bit-perfect 模式。
4. `UsbOutputStrategyResolver` 根据 API level、可选模块、DAC、权限和源格式重新生成决策。
5. API 34+ 优先检查并配置官方 mixer attribute；API 26–33 只有在 M7 引擎可用且设备在支持范围内时才进入自研直出路径。
6. 检查成功后配置输出并开始播放，显示 `BIT-PERFECT ACTIVE`。
7. 条件不满足时停止并解释原因；用户可以主动切换兼容模式，但应用不得静默改变策略。

### 9.4 查看链路证据

1. 用户点击永久 `Chain` 导航，或从 mini-player / Now 的明确入口进入播放信息 Dashboard；M2 验证后还可以使用不与系统返回或歌词冲突的手势进入。
2. 用户先看到当前声明、输出摘要和需要处理的问题，再按需展开源、解码、处理、系统路由和 DAC 五层信息。
3. 用户主动进入高级视图后，才打开实时指标、调整刷新率并固定关心的指标或图表。
4. 用户可以保存高级布局，或主动录制并导出经过隐私清理且保留可信度标签的诊断报告。

## 10. 非功能要求

### 10.1 轻量化

- 核心 release APK/AAB 下载体积的初始预算为 30 MB 以内，不包含可选 AI 模型和实验性编解码模块。
- 未启用的高级模块不得初始化运行时、常驻服务或后台任务。
- 核心播放器不依赖账号、广告 SDK 或通用云客户端。
- 实时观测页面关闭且没有诊断录制时，不得保留周期性 telemetry 轮询或独立常驻服务。

### 10.2 稳定性

- 参考设备连续播放 8 小时无崩溃、无失去控制的后台服务。
- 参考设备播放 24-bit/96 kHz FLAC 连续 2 小时，无可归因于 Vesqen 的 underrun、爆音或异常升温。
- 连续执行 100 次播放/暂停/切歌/拖动组合，不崩溃且状态一致。
- USB DAC 反复插拔 50 次，应用不崩溃、不保留错误直出标识。
- 大型曲库扫描与播放并行时，播放线程不因扫描发生可感知中断。

### 10.3 性能与功耗

- 建立至少一台中端设备和一台旗舰设备的基准。
- 记录 FLAC、ALAC 和 WAV 的 CPU、内存、耗电、温升和 underrun。
- 优化以基线数据为依据，不为了技术展示引入更耗电的自研路径。
- 每个 release 候选版本不得出现未解释的显著性能回退。
- 记录关闭、默认 1 s 和高频刷新三种 telemetry 状态的额外 CPU、内存、功耗与播放稳定性开销。
- 默认实时观测不得导致音频线程阻塞、underrun 增加或 bit-perfect 链路发生变化；不满足时必须降低采样频率或停用对应指标。

### 10.4 隐私与安全

- 核心版本不需要互联网权限。
- 不上传音频文件、文件路径、标签、封面或收听历史。
- 诊断导出默认移除个人目录和可识别文件名。
- SAF URI 和数据库仅存于应用私有空间。
- USB 权限、设备断开和文件访问异常必须安全释放资源。

### 10.5 兼容性

- 最低 Android 8.0（API 26）。
- API 26 是 Vesqen 的产品与维护基线，不是 Android USB Host 或官方 bit-perfect API 的共同最低版本。
- Android USB Host API 从 API 12 提供；Vesqen 仍不因此下调最低安装版本。
- Media3 1.9.0 及之后版本最低为 API 23，因此与 API 26 基线兼容。
- 项目创建时使用 `compileSdk 36` 和 `targetSdk 36`；API 34 音频调用必须通过版本隔离实现，不能在低版本路径直接加载。
- 核心测试覆盖至少 Android 8/9、Android 13、Android 14 和当前稳定 Android 版本。
- USB bit-perfect 官方路径只在 API 34+ 暴露。
- API 26–33 默认提供普通系统播放；严格 USB 直出只有在未来高级 USB 引擎完成后才可能增加。
- 启动时的 API level 检测只选择候选策略；最终决策还必须绑定 USB DAC、ROM/HAL 能力、权限、源格式和可选模块状态。
- USB 策略必须在进程启动、设备插拔、权限变化、源格式变化和路由变化时重新计算。
- Manifest 声明 `android.hardware.usb.host`，但核心播放器不依赖 USB，因此将该 feature 设为非必需并在运行时检测。
- 兼容性按手机型号、ROM Build、DAC、格式和应用版本记录。

## 11. 分阶段路线图

### M0：项目基础与产品边界

目标：建立可持续开发和公开协作基础。

范围：

- PRD、README、路线图、贡献指南和安全政策。
- Android 项目骨架、包名和模块边界。
- 构建、静态检查、单元测试和 CI。
- 定义输出状态模型和禁止夸大声明的规则。

完成条件：

- 新贡献者能够从干净环境完成 debug 构建和测试。
- CI 在默认分支稳定通过。
- PRD 中所有首版范围和非目标得到确认。

### M1：本地播放器 MVP

目标：成为真正可日常播放本地音乐的轻量应用。

范围：

- MediaStore/SAF 导入和增量曲库。
- 歌曲、专辑、艺术家和文件夹浏览。
- FLAC、ALAC、WAV、AIFF 和常用有损格式播放。
- 标签、封面、队列、后台播放和媒体通知。
- 曲库列表与 Now 的基础源音频标签、位深/采样率参数和静态/平均码率。
- 基础无缝播放和音频焦点处理。
- 普通系统输出：扬声器、蓝牙、3.5 mm 和系统 USB 路由。
- `The Quiet Signal` 深浅主题、Twin Paths 标识、紧凑曲目列表、mini-player、完整 Now 页和 `Library / Now / Chain` 导航基线。

完成条件：

- 核心格式测试样本全部通过。
- 8 小时连续播放和基础交互压力测试通过。
- 断开耳机、蓝牙和 USB 时没有崩溃或错误状态。
- `LOSSLESS SOURCE`、`HI-RES SOURCE`、位深、采样率和平均码率使用已确认的源文件数据，不能暗示最终输出质量。
- 新用户在首启授权后能从 Library 完成“选择歌曲 → mini-player 控制 → Now 播放 → Chain 查看声明”的路径，无死路或含义不明的主要按钮。
- 深浅主题、文字缩放、TalkBack、48 dp 触控目标和减少动效回退通过对应的 UI/可访问性检查。

### M2：Audio Proof 与能力观测

目标：形成区别于普通播放器的可审计链路界面。

范围：

- 源文件、解码器、解码输出和处理链展示。
- 从 Now 进入的播放信息 Dashboard、非整页翻页式连续动画和始终存在的可点击入口；最终手势方向在 M2 确认。
- 根据 USB、3.5 mm、Bluetooth Classic/LE Audio 和内置扬声器动态加载对应 route 详情。
- `PlaybackTelemetry` Module、统一 `TelemetrySnapshot` 和 `MEASURED`/`DERIVED`/`ESTIMATED`/`UNAVAILABLE` 可信度模型。
- 实时码率、进程 CPU、内存、缓冲、underrun、thermal 和可取得的功耗/SoC 指标。
- 指标选择、排序、固定、图表、刷新率、布局保存和按需诊断录制。
- 系统输出设备、Direct Playback 查询和可观察输出状态。
- USB DAC 基础信息和能力快照。
- 缓冲、underrun、路由变化和错误记录。
- 隐私清理后的诊断导出。

完成条件：

- 不再把源文件参数直接标记为最终输出参数。
- 实时码率和所有推导/估算指标可以追溯原始数据、时间窗口和计算方式。
- Dashboard 切换输出路由时信息正确更新，主次层级和播放控制保持稳定，不表现为整页翻页。
- 蓝牙 codec/采样率/位深/声道/bitrate 只在当前公开接口和权限确实提供时展示；支持能力与当前配置明确分开。
- 不支持的 SoC、decoder 或功耗数据明确显示 `UNAVAILABLE`，不伪造统一设备指标。
- 页面关闭后采样停止；默认与高频刷新模式的性能开销完成基准测试且不会导致可归因的 underrun。
- 用户布局、刷新率与重置操作在进程重启后行为一致。
- 每种输出状态都有证据来源和用户解释。
- 状态变化与插拔、路由切换和播放会话一致。

### M3：Android 14+ USB bit-perfect

目标：使用公开 Android API 实现受控 USB 直出。

范围：

- `UsbOutputStrategyResolver` Module 和可测试的 `UsbOutputDecision` 状态模型。
- 启动时 API level/USB Host feature 检测，以及设备、格式和路由变化时的重新判定。
- API 34+ `OfficialMixerBitPerfectAdapter` Implementation。
- USB 数字音频设备识别。
- `getSupportedMixerAttributes()` 能力查询。
- bit-perfect attribute 匹配和设置。
- 直出模式下禁用软件音量与 DSP。
- 不支持格式、设备拔出和路由丢失时 fail closed。
- 兼容模式必须由用户明确选择。

完成条件：

- 至少两台 Android 14+ 手机和两款 USB DAC 完成能力测试。
- API 26–33 不会加载或调用 API 34 专属 Implementation，并明确落入普通系统路由。
- 策略解析器的版本、设备、格式、权限和插拔组合测试通过。
- 所有失败路径都不会错误显示 `BIT-PERFECT ACTIVE`。
- 尚未经过外部验证的组合不得显示 `VERIFIED`。

### M4：验证矩阵与公开 Beta

目标：把功能存在转化为可复现的产品证据。

范围：

- 已知测试向量、回环或数字分析验证流程。
- 手机/ROM/DAC/格式/版本设备矩阵。
- 长时间播放、热插拔、后台、锁屏和来电恢复测试。
- 性能、耗电、温升和安装体积基线。
- 无障碍、英文和简体中文界面检查。

完成条件：

- 至少一个精确组合达到 `BIT-PERFECT VERIFIED`。
- Beta 阻断缺陷清零，已知限制公开。
- 发布页面中的每项音频声明都能链接到对应证据。

### M5：高级本地播放能力

目标：在不破坏轻量核心的情况下补充发烧友功能。

候选范围：

- CUE、APE、WavPack。
- DSD/DoP 可行性与兼容矩阵。
- 连续倍速：0.50×–2.00×、保持音调、快捷值、1.00× 吸附、独立重置和可选的跨会话记忆。
- DSP 模式：参数 EQ、预设、preamp/headroom、恢复平直、ReplayGain、crossfeed、crossfade。
- NAS/SMB/WebDAV 本地网络曲库，可作为独立扩展。
- DAC 实验室和社区能力报告。

完成条件：

- 每项能力独立评估体积、功耗和许可证成本。
- DSP 与 bit-perfect 状态严格互斥。
- 倍速重置在触控、TalkBack 和异常状态下都能恢复 1.00×；EQ 一次操作即可恢复平直并完全旁路。
- 0.50×、1.00×、2.00× 和若干中间速度完成音调、seek、切歌、gapless、后台和输出切换测试。
- EQ 与倍速关闭后不保留处理链，Dashboard 能准确显示当前是否旁路。
- 未启用功能不增加后台负担。

### M6：渐进式自研播放内核（条件性里程碑）

目标：在不牺牲稳定性和轻量化的前提下，逐步获得对播放调度、PCM 链路和输出策略的自主控制。

启动前置条件：

- M1–M4 已形成可运行、可测量、可回退的 Media3 基线。
- 已定位 Media3/平台路径无法解决的明确问题，或测量证明自研实现能带来有意义的体积、功耗、延迟、稳定性或链路可审计性收益。
- 已准备覆盖核心格式、队列行为、无缝播放和输出模式的回归样本。

初步实现思路：

1. 建立独立的 `playback-core` Module，只暴露小型 `PlaybackEngine` Interface；接受播放命令并返回结果，以只读状态流发布 `PlaybackSnapshot`。
2. 首个 Implementation 为 `Media3PlaybackAdapter`，把 M1–M4 已验证的 Media3 播放器接到统一接口；测试使用 `FakePlaybackEngine`，使接口本身成为测试 Seam。
3. 把数据源、Extractor、Decoder、PCM Buffer、Clock 与 Audio Output 分成内部可替换 Adapter，但不向 UI 暴露 Media3、`AudioTrack` 或 native 细节。
4. 新增 `NativePlaybackEngine` 实验实现，先复用成熟 extractor 和平台 `MediaCodec`/已审计 decoder，只自研调度、环形缓冲、时钟、格式切换与错误恢复；不得一开始重写 FLAC/ALAC 算法。
5. 输出端至少提供 `SystemAudioOutputAdapter` 与 `UsbBitPerfectOutputAdapter` 两个真实 Implementation；二者共享状态契约，但 bit-perfect 适配器必须 fail closed。
6. 仅在 profiling 证明 Kotlin/JVM 路径不足时，将明确的热路径迁移至 NDK/C++ 或 SIMD；native 代码作为局部实现而不是第二套产品状态机。
7. 通过 feature flag 按格式和设备逐项切换，在新实现通过相同测试后替换旧实现，不长期叠加两套重复集成测试。

候选范围：

- 播放命令与状态机、队列和无缝衔接。
- 本地数据源与容器解封装调度。
- 解码器选择、PCM 环形缓冲和播放时钟。
- 普通系统输出与 API 34+ bit-perfect 输出 Adapter。
- 格式变化、seek、音频焦点、路由变化和故障恢复。

完成条件：

- M1 的核心播放行为在统一接口下保持功能等价。
- 核心 PCM 测试向量、seek、gapless 和格式切换测试通过。
- 8 小时播放、100 次交互压力测试和设备矩阵不出现显著回退。
- 每条自研路径都有明确收益数据、Media3 回退开关和可解释的 Audio Proof 状态。

该阶段是条件性工程演进，不是首个稳定版的阻断项；如果收益不足，应保留深接口并继续使用 Media3 Implementation。

### M7：旧系统高级 USB 引擎（条件性里程碑）

目标：评估 Android 8–13 严格 USB 直出的真实价值与成本。

启动前置条件：

- M3、M4 已完成。
- M6 已至少完成 M7 所需的 App-owned PCM 路径：本地数据源、目标格式 extractor/software decoder、PCM 输出 Seam 和可测试的 `DirectUsbHostAdapter` 接入点；不要求 M6 的所有候选格式或优化全部完成。
- 有明确用户需求和目标 DAC 集合。
- 可以持续维护真机和 DAC 测试矩阵。
- 已在参考 Android 8–13 设备上完成 native isochronous transport 技术验证，确认无需 Root 且能安全使用应用获得授权的 USB device connection。
- native 依赖、内核交互、许可证、安全和目标分发渠道完成专项审查；不得仅凭其他商业播放器存在就推断 Vesqen 的实现可直接分发。

候选范围：

- 实现与 M3 相同 USB 输出 Interface 的 `DirectUsbHostAdapter`，由同一个 `UsbOutputStrategyResolver` 选择。
- 建立首批严格数据链：FLAC/WAV 本地文件读取、App-controlled software decode、确定性 PCM 格式、环形缓冲、clock/packetizer 和 native USB transport；随后再评估 ALAC 与其他格式。
- 严格路径运行时验证音频数据未创建或写入 `AudioTrack`、AAudio/OpenSL ES，且未进入 AudioFlinger/OEM Audio HAL 输出链。
- USB Host 权限和设备生命周期。
- Java/Kotlin 控制面：raw descriptor、interface/alternate setting、endpoint、UAC1 class-specific control 和 PCM 格式协商。
- `NativeIsochronousTransportAdapter` 数据面：等时 OUT packetization、URB 队列、完成回收、取消和设备断开。
- PCM 环形缓冲、预缓冲、水位、背压、underrun/overrun 和错误恢复。
- 同步/自适应/异步 clock 模式、feedback endpoint、漂移测量和长期稳定性。
- Audio Proof 中展示 transport、packet、buffer、feedback、clock drift、错误和性能状态。
- 后续再评估 UAC2、更复杂的异步 feedback、多声道与 DSD/DoP。

完成条件：

- 至少一台 API 26–33 手机与一款目标 UAC1 DAC 完成 16/24-bit、44.1/48/96 kHz 参考矩阵；每项结果绑定手机、ROM、内核、DAC 和应用版本。
- 参考组合连续输出 2 小时，无未恢复 underrun/overrun、爆音、失控漂移或 USB 资源泄漏。
- 完成 50 次授权、启动、停止、插拔和异常恢复，不崩溃且不会错误保留直出状态。
- transport 关闭或失败时 fail closed；兼容系统输出只能由用户明确选择，不能在同一声明下静默回退。
- CPU、内存、温升、功耗和 packet/URB 错误率完成基准，并证明高规格 PCM 在参考设备上具有足够余量。
- 使用可审计的运行时事件和集成测试证明目标格式遵循 `file → extractor → software decoder → PCM buffer → packetizer → native USB transport`，没有回落到系统音频输出。
- 在发布任何 bit-perfect 声明前，仍需对精确参考组合完成数字链路验证；用户态传输成功本身不是位级一致证明。

该阶段是实验性能力，不承诺一定进入稳定版。

### M8：端侧音频智能（可选）

目标：为 Edge AI 求职和高级曲库体验提供一个真实、轻量、可测量的模块。

范围：

- 离线音频标签或嵌入生成。
- 相似曲目和本地语义筛选。
- 量化模型及 CPU/GPU/NNAPI 路径比较。
- 充电/空闲索引、功耗限制和模型卸载。

完成条件：

- AI 模块关闭时，核心播放的体积、后台和音频链不受影响。
- 有公开测试集或人工标注集上的质量报告。
- 有模型大小、延迟、内存、耗电和设备回退数据。
- AI 输出不进入 bit-perfect 播放链。

## 12. 版本优先级

### 必须完成（稳定版核心）

- M0–M4。
- 本地曲库、核心格式、可靠播放、Audio Proof。
- Android 14+ 官方 USB bit-perfect 能力。
- 设备矩阵与至少一个外部验证组合。

### 应该完成（稳定版后续）

- M5 中经过评估的格式和 DAC 实验室。
- 更成熟的播放列表、搜索、睡眠定时器和可访问性。

### 可以完成（独立实验）

- M6 渐进式自研播放内核。
- M7 旧系统高级 USB 引擎。
- M8 端侧音频智能。
- NAS、远程控制和 ABX 测试工具。

## 13. 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 厂商未实现 API 34 bit-perfect HAL flag | 功能在部分手机不可用 | 运行时能力检测、设备矩阵、禁止统一承诺 |
| Direct Playback 被误解为 bit-perfect | 产生错误宣传 | 独立状态模型和证据说明 |
| USB DAC 组合高度碎片化 | 插拔、格式和时钟问题 | 限定参考设备、自动诊断、分级兼容列表 |
| Android 公开 USB 接口不支持 isochronous 数据面 | M7 无法只靠 Java USB Host API 完成 UAC 播放 | 独立 native transport 技术验证、参考内核矩阵和明确停止条件 |
| native usbfs/libusb 路径受 ROM、内核或分发环境限制 | 旧系统直出兼容性和发布范围不稳定 | 不依赖 Root、专项安全/许可证/渠道审查、按设备 fail closed |
| 自研播放内核形成两套长期实现 | 测试翻倍、状态漂移和维护成本失控 | 统一 Interface、按能力渐进替换、保留短期回退并删除被替换路径 |
| 自研编解码或 USB 引擎使应用变重 | 偏离轻量目标 | 可选模块、体积预算、按测量决定实现 |
| 实时信息采样反而增加耗电或 underrun | 观测功能干扰播放结果 | 按需采样、聚合快照、开销基准和自动降频 |
| SoC、码率或功耗指标被过度解读 | 用户得到错误的性能或音质结论 | 展示来源、窗口与可信度；不可取得时显示 `UNAVAILABLE` |
| 蓝牙设备支持能力被误报为当前 codec 配置 | 用户误解实际无线输出链 | 区分 connected/active/supported/negotiated，受限数据标记 `UNAVAILABLE` |
| `HI-RES SOURCE` 被误解为 bit-perfect 输出 | 源文件标签造成音质宣传越界 | 标签明确写 SOURCE，并与输出声明分区显示 |
| 用户忘记倍速或无法拖回 1.00× | 播放长期处于非预期速度 | 标准速度吸附、持续状态提示和独立重置按钮 |
| EQ 增益造成削波或重置不彻底 | 失真或处理链状态不可信 | preamp/headroom、削波提示、恢复平直和完全旁路测试 |
| DSP 与直出同时开启 | 破坏位级一致性 | 模式互斥和统一播放状态机 |
| 高级格式许可证不兼容 | 阻碍 Apache-2.0 分发 | 引入前完成许可证审计 |
| AI 功能喧宾夺主 | 增加体积、耗电和维护成本 | 最后阶段、可卸载、播放链隔离 |
| “Hi-Res”营销超过证据 | 损害可信度 | 发布声明必须绑定测试证据 |

## 14. 成功指标

首个稳定版的成功不以下载量作为唯一标准，而以以下工程结果衡量：

- 日常本地播放在参考设备上稳定完成长时间测试。
- 用户能准确理解当前输出是系统混音、Direct、bit-perfect 可用、已启用或已验证。
- Android 14+ USB 直出失败时没有静默降级或错误状态。
- 至少一个手机/ROM/DAC/格式组合完成可复现 bit-perfect 验证。
- 核心安装体积、CPU、内存和功耗保持在公开预算内。
- 开源仓库具备可复现构建、CI、测试和清晰的贡献入口。

## 15. 待确认决策

- Android 应用 ID 和 Java/Kotlin namespace 已确定为 `io.github.sumirenokai.vesqen`。
- UI 技术栈和具体 Compose/Media3 版本；当前 Media3 新版本最低 API 23，不改变 API 26 产品基线。
- 核心解码策略：平台/Media3 优先，哪些格式需要独立 native decoder。
- M6 的首个自研替换点，以及触发替换所需的性能或兼容性证据阈值。
- bit-perfect 验证使用的参考手机、ROM、USB DAC 和采集设备。
- 首批有损格式是否全部进入 M1。
- `HI-RES SOURCE` 最终阈值、文字风格和商标审查；初始候选为无损、至少 24-bit/88.2 kHz。
- Now 到 Dashboard 的最终手势方向、歌词入口关系和动画方案，在 M2 原型测试后确定。
- 核心安装体积 30 MB 预算是否需要进一步收紧。
- M5 之后优先选择高级格式、DAC 实验室、NAS 还是端侧 AI。

## 16. 官方技术依据

- Android 14 preferred mixer attributes 与可选 bit-perfect HAL 支持：<https://source.android.com/docs/core/audio/preferred-mixer-attr>
- `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT`（API 34）：<https://developer.android.com/reference/android/media/AudioMixerAttributes>
- `AudioManager.getDirectPlaybackSupport()`（API 33）和 mixer attribute API：<https://developer.android.com/reference/android/media/AudioManager>
- Android USB Host API：<https://developer.android.com/develop/connectivity/usb/host>
- `UsbEndpoint`（公开文档注明 isochronous endpoint 当前不受支持）：<https://developer.android.com/reference/android/hardware/usb/UsbEndpoint>
- `UsbRequest`（公开异步请求覆盖 bulk/interrupt）：<https://developer.android.com/reference/android/hardware/usb/UsbRequest>
- `UsbDeviceConnection`（control/bulk、raw descriptors 和授权连接 file descriptor）：<https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection>
- Android API level 与 `minSdkVersion` 定义：<https://developer.android.com/guide/topics/manifest/uses-sdk-element>
- Google Play target API 要求：<https://developer.android.com/google/play/requirements/target-sdk>
- Media3 release notes（1.9.0 起 `minSdk 23`）：<https://developer.android.com/jetpack/androidx/releases/media3>
- Android Bluetooth A2DP 状态与权限：<https://developer.android.com/reference/android/bluetooth/BluetoothA2dp>
- Android Bluetooth codec 配置数据：<https://developer.android.com/reference/android/bluetooth/BluetoothCodecConfig>
- Android 音频输出设备类型：<https://developer.android.com/reference/android/media/AudioDeviceInfo>
