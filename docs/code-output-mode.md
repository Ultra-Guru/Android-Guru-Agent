# Coding 工位输出展示双档（流式 / 完整）

> v1.5 · 对应 PR：feat/code-output-mode
> 需求原话：「模仿 Rikka Hub 的输出方式，再做一种，与我原来的可以切换，
> 在设置里切换。」

## 1. 目标

Coding 模式（胶囊时间轴）支持全局切换两种输出展示档：

| 档位 | 生成中 | 完成后 |
| --- | --- | --- |
| **流式 STREAMING**（默认） | 结论气泡逐字追加 + 光标；思考链流式展开；写/编辑实时代码窗；终端尾窗随输出贴尾；自动跟随滚动 | 完整内容（既有行为，零变化） |
| **完整 COMPLETE**（新增） | 本轮过程条目全部静默，仅一张加载进度卡（呼吸圆点 + 已用时 + 工具计数 + 骨架条） | 切片解除，完整时间轴一次性渲染 + 滚动一次到底 |

两档共享同一份 `CodeStreamSnapshot` 渲染通道与消息模型（**不维护两套
消息列表**）；完整档的「隐藏」是渲染侧的纯派生切片，session 侧数据
不变——**切换不丢任何会话数据**（落盘 / 长任务追踪 / 深水区观察照常）。

## 2. 数据与状态模型

```kotlin
enum class CodeOutputMode { STREAMING, COMPLETE }   // ui/screen/code/CodeOutputMode.kt

data class CodeUiState(
    …
    val renderMode: CodeOutputMode = CodeOutputMode.STREAMING,  // run 粒度快照
)
```

- 持久化：`AgentSettings.codeOutputMode`（`"streaming"` | `"complete"`，
  缺省 `"streaming"`，随 AgentSettings JSON 落 SharedPreferences——沿用
  仓库既有设置持久化惯例，与 `codingToolSurface` 同款字符串枚举模式）；
- `CodeUiState.renderMode`：**每轮 run 开始时捕获**（runEngine →
  `captureRunOutputMode()`），不是直读设置流——这是「切换只影响下一条
  消息或完成后的消息」的落点：运行中在设置页改档，当前轮继续按旧档
  渲染到收束，下一轮自动按新档；两档的终态渲染完全一致，收束后无需
  显式回同步；
- 启动恢复：`restoreOutputMode()`（init 单行调用）。

## 3. 渲染规则（完整档生成中）

| 区域 | 流式档 | 完整档生成中 |
| --- | --- | --- |
| 结论气泡 | 实时追加文本 + 光标 | 静默（加载卡占位） |
| 工具胶囊 | 实时展开「正在调用…」+ 实时代码窗 | 静默（加载卡显示「已调用 N 个工具」） |
| 思考过程 | 流式展开跟随全文 | 静默（收束后默认折叠，与流式档终态一致） |
| 终端尾窗 | 运行中展开跟随输出 | 静默（收束后折叠头行回归） |
| 滚动 | 自动跟随底部 | 生成中不跟随；收束后锚定动画滚动一次 |
| 错误态 | ErrorBar（两档统一，不区分模式） | 同左 |

切片函数 `completeModeVisibleEntries(entries)`：保留到最后一条
`UserEntry`（含）——每次 runEngine 都经 `beginRun` 追加用户条目，故
「最后一条 UserEntry」即本轮起点；其后的思考/胶囊/状态/结论全部静默。
防御性兜底：无 UserEntry 时原样返回全部条目（fail-open，宁可漏静默
不吞历史）。

## 4. 设置入口

设置中心 → 工作台 → Coding 工位 →「输出方式」下拉
（`CodingSettingsSection`，`DropdownRow` 组件，与「工具面」同款交互）。
字符串在 `strings_settings_v3.xml` 与 `values-zh` 双语镜像。

不设顶栏 SegmentedButton 快捷开关（需求明确「在设置里切换」）；如后续
要加屏内快捷切换，复用 `AgentSettings.codeOutputMode` 通道即可。

## 5. 加载进度卡（CompleteModeGeneratingCard）

- 三枚呼吸圆点（相位错开 180ms）+ 标题 + 已用时（每秒跳，复用
  `formatCapsuleDuration` 格式）；
- 进度行：本轮工具计数（`snapshot.toolCallCount`）+ 失败计数
  （`snapshot.failedToolCallCount`，错误色）——静默不等于黑箱；
- 两道骨架占位条（呼吸透明度），传达「内容将在完成后一次性出现」；
- 卡片高度恒定（计数/用时只改文本不改结构），不触发时间轴锚定跟随。

## 6. 涉及文件

| 文件 | 职责 |
| --- | --- |
| `ui/screen/code/CodeOutputMode.kt` | 档位枚举 + 解析 + 完整档可见切片（纯函数） |
| `ui/screen/code/CodeOutputModeOps.kt` | VM 扩展：恢复 / run 开始捕获（God-file 预算纪律） |
| `ui/screen/code/CodeUiState.kt` | `renderMode` 字段 |
| `ui/screen/code/CodeViewModel.kt` | 两处单行调用点（init 恢复 / runEngine 捕获） |
| `ui/screen/code/CodeScreen.kt` | renderMode 传入时间轴 + 完整档终端尾窗门控 |
| `ui/screen/code/stream/CodeStreamTimeline.kt` | 切片渲染 + 加载卡条目 + 锚定 key 改派生切片 |
| `ui/screen/code/stream/CompleteModeGeneratingCard.kt` | 加载进度卡本体 |
| `ui/screen/settings/SettingsRepository.kt` | `AgentSettings.codeOutputMode` 字段 |
| `ui/screen/settings/SettingsV3Sections.kt` | 设置行（DropdownRow） |
| `res/values{,-zh}/strings_settings_v3.xml`、`strings_code.xml` | 双语字符串 |

## 7. 验收对照（规格书）

- 开关切换后 UI 即时更新：设置行即时回显；时间轴两档终态渲染一致，
  差异仅存在于生成中（按设计）；
- 流式模式逐字显示且不卡顿：既有 25ms ≤40Hz 攒批通道零改动；
- 完整模式只在完成后展示全文：生成中切片只留历史 + 用户条目 + 加载卡；
- 生成中切换有明确限制语义：档位 run 粒度捕获，当前轮不受打扰、下一轮
  生效（设置页无运行态可读，采用捕获语义而非硬禁用）；
- 消息内容 / 工具调用 / 错误态两档一致：同一 session 数据源 + ErrorBar
  统一错误通道；
- 切换不丢会话数据：切片是 `remember` 派生视图，session / 落盘 /
  检查点恢复全链路无感知。
