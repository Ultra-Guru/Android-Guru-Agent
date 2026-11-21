# Coding 工位输出展示双档（流式 / 完整）

> v1.5.1 · 对应 PR：feat/code-output-mode
> 需求原话：「模仿 Rikka Hub 的输出方式，再做一种，与我原来的可以切换，
> 在设置里切换。」「要包含 skill 以及 mcp，然后还要增加调用工具聚合，
> 以及更多。」

## 1. 目标

Coding 模式（胶囊时间轴）支持全局切换两种输出展示档：

| 档位 | 生成中 | 完成后 |
| --- | --- | --- |
| **流式 STREAMING**（默认） | 结论气泡逐字追加 + 光标；思考链流式展开；写/编辑实时代码窗；终端尾窗随输出贴尾；自动跟随滚动 | 完整内容（既有行为，零变化） |
| **完整 COMPLETE**（新增） | 本轮过程条目全部静默，仅一张加载进度卡（呼吸圆点 + 已用时 + 工具使用聚合计数 + 骨架条） | 切片解除，完整时间轴一次性渲染 + 滚动一次到底；工具胶囊段折叠为聚合卡 |

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
- 进度行：**工具使用聚合**（v1.5.1）——本轮总数（`snapshot.toolUsage.total`，
  run 粒度归零）+ 技能 / MCP 分类计数（非零才显，图标区分）+ 失败计数
  （错误色）——静默不等于黑箱，「用了哪些能力生态」一眼可辨；
- 两道骨架占位条（呼吸透明度），传达「内容将在完成后一次性出现」；
- 卡片高度恒定（计数/用时只改文本不改结构），不触发时间轴锚定跟随。

## 5a. 调用溯源（skill / mcp / builtin 三分类，v1.5.1）

需求：「要包含 skill 以及 mcp」。旧链路事件只携带 `toolName`，胶囊 UI 靠
名字启发式猜测——技能工具（任意命名的 PascalCase id）一律被误标成
「MCP 自定义工具」。v1.5.1 打通权威溯源：

```
SkillToolAdapter / McpAgentTool        ToolMetadata
  category(SKILL) + tag("skill:X")        |
  category(MCP)  + tag("server:Y")        v
EngineToolExecution（registryToolId 已解析处）-> resolveToolOrigin()
  v AgentEvent.ToolCallStart/Complete.origin + originLabel（默认 BUILTIN）
CodeStreamSession -> StreamToolCall.origin / originLabel（Kind 覆盖：
  SKILL -> ToolKind.SKILL，MCP -> ToolKind.MCP_CUSTOM）
  v 两档共用：流式胶囊星芒/拼图图标 + 详情页溯源行；完整档聚合卡分组
```

- 引擎事件新增字段全部带默认值 `BUILTIN`：kit 适配器（标准线/织机/信使）
  与旧发射点零改动，未打标按内置渲染（与既有行为一致）；
- `SkillToolAdapter` 元数据补上显式 `category(SKILL)` + `skill:{id}` 标签
  （旧实现靠 id 猜，仅 id 含 "Skill" 才命中——FetchTodos 这类被折进
  UTILITY）；MCP 工具 v4 起已有 `category(MCP)` + `server:{name}` 打标；
- 胶囊展示：技能 = 星芒图标（teal）+「技能 · id」；MCP = 拼图图标
  （tertiary）+「MCP · 服务器名」；详情页多一行等宽溯源行；
- 持久化：`StorableToolCall.originName/originLabel` + 收尾卡
  `runSkillCalls/runMcpCalls/runSkills/runMcpServers`（缺省值 +
  `ignoreUnknownKeys` 双向兼容旧档）；
- run 粒度会计：`CodeStreamSession.runToolOrigins`（beginRun 归零，
  ArgsDelta 期占位 BUILTIN、Start 到达时被引擎权威打标覆盖——put 语义
  不重复计数），派生 `CodeStreamSnapshot.toolUsage: ToolUsageStats`。

## 5b. 工具调用聚合（完整档收束后的「抽屉」，v1.5.1）

需求：「增加调用工具聚合」。完整档收束后的连续工具胶囊段不再平铺，
折叠为一张 `ToolAggregateCard`（规格书：「完成后折叠『已调用 N 个
工具』」）：

- **折叠态**（默认）：「已调用 N 个工具」+ 三分类 chips（内置 / 技能 /
  MCP，非零才显）+ 失败徽标（>0 错误色）；
- **展开态**：技能 / MCP 服务器 / 内置族三段分组摘要（各带 xN 与失败
  数）+ 调用明细（原序胶囊，点击仍进 DetailSheet 看 diff / 终端全文
  ——聚合不吞可达性）；
- **分段策略**：`groupConsecutive(entries, aggregateTools)`——跨族连续
  胶囊 >= 2 收进一张卡（单颗不包卡）；验证轮次卡（VerifyCycleEntry）
  本就是折叠形态，自然断段独立成卡；Git 操作卡同理；
- **与流式档聚合的分工**：流式档按同族聚合（防刷屏 + 最新命令可见，
  既有行为零变化）；完整档按调用段聚合（过程收进抽屉，结论留在台面）；
- 收尾统计卡（RunStatsCard）同步增加「技能 N」「MCP N」chips（非零
  才显）；
- 聚合是纯派生视图（`computeToolAggregate` 对胶囊列表的只读归约 +
  `remember` 派生段）——切回流式档即还原平铺，不丢任何条目。

## 6. 涉及文件

| 文件 | 职责 |
| --- | --- |
| `ui/screen/code/CodeOutputMode.kt` | 档位枚举 + 解析 + 完整档可见切片（纯函数） |
| `ui/screen/code/CodeOutputModeOps.kt` | VM 扩展：恢复 / run 开始捕获（God-file 预算纪律） |
| `ui/screen/code/CodeUiState.kt` | `renderMode` 字段 |
| `ui/screen/code/CodeViewModel.kt` | 两处单行调用点（init 恢复 / runEngine 捕获） |
| `ui/screen/code/CodeScreen.kt` | renderMode 传入时间轴 + 完整档终端尾窗门控 |
| `ui/screen/code/stream/CodeStreamTimeline.kt` | 切片渲染 + 加载卡条目 + 聚合分段（aggregateTools）+ 锚定 key 改派生切片 |
| `ui/screen/code/stream/CompleteModeGeneratingCard.kt` | 加载进度卡本体（工具使用聚合计数） |
| `ui/screen/code/stream/ToolUsageAggregate.kt` | 聚合卡本体 + 摘要纯函数（v1.5.1） |
| `ui/screen/code/stream/CodeCapsule.kt` | 族样式 + SKILL（星芒 teal）/ MCP 拼图图标 |
| `ui/screen/code/stream/CodeStreamCards.kt` | 收尾统计卡 + 技能 / MCP chips |
| `ui/screen/code/stream/CodeToolDetailSheet.kt` | 详情页溯源行（v1.5.1） |
| `core/engine/AgentEvent.kt` | `ToolCallOrigin` + 双事件溯源字段（默认 BUILTIN） |
| `core/engine/EngineToolOrigin.kt` | 注册表 id -> 溯源信息（元数据打标读取） |
| `core/engine/EngineToolExecution.kt` | Start / Complete 溯源透传 |
| `core/tools/skill/SkillToolAdapter.kt` | 显式 SKILL 打标（category + skill:{id}） |
| `core/code/stream/CodeStreamModels.kt` | `ToolKind.SKILL` / `StreamToolCall.origin` / `ToolUsageStats` / 收尾卡溯源字段 |
| `core/code/stream/CodeStreamSession.kt` | 溯源归约 + run 粒度会计 + 快照聚合统计 |
| `core/code/stream/CodeStreamCheckpoint.kt` | 溯源持久化（双向兼容） |
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
  检查点恢复全链路无感知；
- **技能 / MCP 展示（v1.5.1）**：事件层权威溯源（引擎注册表元数据打标），
  两档共用——技能胶囊星芒图标 + 技能 id、MCP 胶囊拼图图标 + 服务器名、
  详情页溯源行；流式档不再把技能误标成「MCP 自定义工具」；
- **工具调用聚合（v1.5.1）**：完整档收束后连续胶囊段折叠为聚合卡
  （三分类摘要 + 可展开明细，明细胶囊仍可进 DetailSheet）；聚合为纯
  派生视图，切回流式档即还原平铺；
- **旧档兼容（v1.5.1）**：溯源字段全部缺省值 + `ignoreUnknownKeys`，
  旧检查点恢复按内置工具渲染，收尾卡溯源计数缺省 0。
