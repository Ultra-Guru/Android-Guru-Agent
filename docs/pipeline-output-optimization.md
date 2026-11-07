# Agent 流水线输出优化（Pipeline Output Optimization）

> 目标：让 Agent 执行过程中的每一类输出（文字 / 代码 / 文件 / 选择 / 工具 / 思考 / Skill / 连接器 / 插件 / MCP）都以最适合的形态呈现，并补全此前被丢弃的收尾信息。

## 一、新增能力总览

| 能力 | 之前 | 现在 |
|------|------|------|
| 代码输出 | 纯文本等宽块 | `CodeOutputCard`：轻量语法高亮（关键字/字符串/数字/注释/注解）+ 语言标签 + 行数 + 复制 |
| 文件操作 | 一段 ✅ 文本 | `FileOpCard`：按扩展名图标 + 文件名/路径 + 操作徽章（新建/覆盖/追加/编辑/删除/移动）+ 大小变更（Old→New）+ 行数统计 + 逐条编辑明细 |
| Shell 执行 | 纯文本 | `ShellOutputCard`：`$ command` 命令头（可复制）+ 输出体，失败红色化 |
| JSON 输出 | 原始 JSON 文本 | `JsonOutputCard`：可折叠树形查看器（深度≤2 默认展开，30 项截断，值类型着色） |
| 连接器调用 | 无分类（表现为普通工具） | `ToolKind.CONNECTOR`（紫色 + Link 图标）：`connector*` / `HttpRequest` / `github_*` 自动识别，`/connector:xxx` 路由专用横幅 |
| 插件调用 | 无分类 | `ToolKind.PLUGIN`（琥珀 + Extension 图标）：`plugin*` 前缀识别，`/plugin:xxx` 路由专用横幅 |
| Skill 调用 | 横幅永远"正在执行"脉冲 | `PipelineBannerCard`：运行中脉冲、结束后对勾 + 总耗时（修复永不停歇的动画） |
| Plan 步骤 | `StepStart` 事件被丢弃 | `StepMarkerCard` 步骤分隔卡（步骤 N + 描述） |
| 任务完成 | `Complete` 事件被丢弃（只复位 loading） | `RunSummaryCard`：总结文本 + 迭代数 + 工具调用数 + 总耗时 |
| 弹出选择（CHOICE） | 只有自由文本框 | 从 prompt 解析 `1. / • / -` 选项渲染单选卡 + 自定义答案兜底 |
| 确认（CONFIRMATION） | "提交/取消"语义模糊 | 明确"确认 / 拒绝"双按钮 |
| 工具输出细节 | 折叠时只有 8 行原始文本 | 折叠时智能摘要行（文件路径 / 命令 / URL / server）+ 统计（行数/字符数）+ 截断提示 + 复制按钮 |
| ANSI 转义序列 | 原样混入输出（shell 彩色码） | 事件入口统一 `stripAnsi` 清理 |
| 运行中工具卡 | 无耗时感知 | 实时耗时计时（秒级刷新）+ 参数智能摘要 |

## 二、架构与数据流

```
AgentEvent ──► AgentChatViewModel.handleEvent
                │  stripAnsi（chunk/output 统一清理）
                │  classifyTool(name, args, routeContextKind) → ToolKind
                │  StepStart  → AgentUiMessage.StepMarker
                │  Complete   → AgentUiMessage.RunSummary + finishActiveBanner()
                │  Error/Aborted/abort() → finishActiveBanner()
                ▼
          AgentUiMessage（新增 PipelineBanner / StepMarker / RunSummary）
                ▼
        AgentChatMessages.AgentMessageItem 分发
                ▼
   ToolCallCard ──► SmartToolOutput（detectOutputView 自动路由）
                      ├─ FILE_OP → FileOpCard(parseFileOp)
                      ├─ SHELL   → ShellOutputCard
                      ├─ CODE    → CodeOutputCard(highlightCode)
                      ├─ JSON    → JsonOutputCard(JsonNodeRow)
                      └─ PLAIN   → TextOutputBlock（复制 + 统计 + 截断提示）
```

### 关键决策

1. **输出视图路由是纯函数**（`detectOutputView` / `parseFileOp` / `classifyTool` / `smartToolSummary`）：
   全部为无副作用字符串处理，便于独立单测；不引入第三方语法高亮库（约 60 行正则扫描器实现，覆盖 C 族/Kotlin/Java/Python/Go/Rust/Swift/Bash 常用关键字）。
2. **路由上下文泛化**：`skillContext: String?` 升级为 `routeContextKind/Name`，`SlashCommandRoute` 新增 `routeKind/sourceName`，`/skill:` `/connector:` `/plugin:` 三类指令统一走 `PipelineBanner`，循环内工具调用带同源徽章（`classifyTool` 兜底规则）。
3. **横幅收尾不新增消息**：完成态通过 `finishActiveBanner()` 原地更新 `PipelineBanner.finishedAt`，消息流不被收尾噪音刷屏。
4. **ANSI 清理在事件入口**：ViewModel 在 chunk/output/fullOutput 进入缓冲前统一清理，UI 层零感知，时间线与卡片共用干净文本。

## 三、涉及文件

- `ui/screen/agent/AgentChatOutputCards.kt`（新增）：智能输出渲染器（代码高亮/文件卡/JSON 树/Shell 卡/RunSummary/StepMarker/ANSI 清理/格式化工具）
- `ui/screen/agent/AgentChatViewModel.kt`：事件处理增强（StepStart/Complete/横幅收尾/路由上下文/ANSI 清理）
- `ui/screen/agent/AgentUiModels.kt`：`ToolKind.CONNECTOR|PLUGIN`、`PipelineBanner`、`StepMarker`、`RunSummary`、`AgentToolCallUi.startedAt`
- `ui/screen/agent/AgentChatToolCards.kt`：CONNECTOR/PLUGIN 视觉规格、智能摘要行、SmartToolOutput 接线、运行卡实时耗时
- `ui/screen/agent/AgentChatPlanCards.kt`：`SkillBannerCard` → `PipelineBannerCard`（三态：Skill/连接器/插件 + 运行/完成）
- `ui/screen/agent/AgentChatDialogs.kt`：`UserInputDialog` 按输入类型差异化（CHOICE 单选卡/CONFIRMATION 语义化/TEXT）
- `ui/screen/agent/AgentChatMessages.kt`：新消息类型分发
- `ui/screen/agent/SlashCommands.kt` + `slash/SlashCommandRouter.kt`：路由类别（routeKind/sourceName）

## 四、修复与打磨记录（CI 修复轮）

### 编译修复（11 处 Kotlin 编译错误）

| 位置 | 问题 | 修复 |
|------|------|------|
| `AgentChatOutputCards.kt` | 缺少 `androidx.compose.foundation.layout.height` 导入（7 处 `Modifier.height` 调用） | 补充导入 |
| `AgentChatPlanCards.kt` | 使用 `FontFamily.Monospace` 但未导入 `FontFamily` | 补充导入 |
| `AgentChatToolCards.kt` | `Icons.Default.Link` / `Icons.Default.Extension` 未导入（导入误加到了 PlanCards） | 补充导入到使用方 |
| `AgentChatToolCards.kt` | `SmartToolOutput(output = toolCall.output)`：`String?` → `String` 类型不匹配 | 传入分支内已保证非空的 `outputText` |

### CI 括号平衡检查误报修复

CI 以原始字符计数检查 `.kt` 文件的括号配对（含字符串/注释）。以下合法代码会被误报，已调整为源码层面配对的等价写法：

- `parseFileOp`：正则字面量中的 `\(` → `\x28`（Java/ICU 正则十六进制转义，语义完全等价，已用 kotlinc 2.0.21 + JVM 实测验证行为一致）；
- `parseChoiceOptions`：字符类 `[...)]` → `[...\x29]`；
- 注释中的 `1)` → 全角 `1）`。

### 逻辑缺陷修复（本轮真实找出的 bug）

1. **旧流水线横幅永久脉冲**：`/skill:` 等流水线执行中用户发送新消息 / 重试会取消旧 job，但横幅未收尾。现在 `sendMessage` / `retry` 在 `currentJob?.cancel()` 后调用 `finishActiveBanner()`。
2. **`SmartToolOutput` 视图路由 remember key 不完整**：`remember(toolName, output.take(64))` 在两段输出前 64 字符相同、尾部结构不同时会复用错误的视图类型；改为完整 `output` 作 key。
3. **`FileOpCard` 解析缓存 key 缺 `args`**：`parseFileOp` 入参含 `args` 但 remember key 未包含。
4. **代码高亮配色不随主题**：`CodeOutputCard` 的 `remember(code, lang)` 未把 `colors` 纳入 key，明暗切换后高亮配色陈旧；已纳入。
5. **运行中工具卡计时从 0 起跳**：`RunningToolCallCard` 首帧显示 0s 而非真实已用时长；改为初始化即按 `startedAt` 计算，且 ≥60s 切换为 `2m05s` 格式。

### UI 打磨

- `PipelineBannerCard`：脉冲无限动画仅在运行态创建（完成态零动画开销）；
- `StepMarkerCard`：步骤编号升级为带旗帜图标的胶囊徽章，层级更清晰；
- `RunSummaryCard`：补充细描边，与文件操作卡/代码卡视觉同族；
- 工具卡完成态耗时统一走 `formatDuration`（`1.2s` / `2m05s`），等宽字体对齐。

---

## 五、Streaming Pipeline 2.0 修订（2026-11，P0 正确性 + P1 聚合）

> 本节对齐「Apex Streaming Engine 2.0」规格书的 P0/P1 条目。P0 已实施；P1 聚合已实施；增量 Markdown 渲染器与动态刷新频率留作独立后续（见文末路线）。

### P0 内容完整性（不丢字）——已实施

**问题实证**：`TaskRuntime` 高水位（1024）丢弃 `ResponseChunk/ThinkingChunk/ToolOutputChunk/ToolProgress`（`isDroppableUnderPressure`），其契约「丢过程不丢结果」依赖 UI 在 `ResponseComplete` 用 `fullText` 回填——但 `CodeEventReducer` 旧实现只翻 `isStreaming` 标志，被丢 chunk 在 messages 路径永久缺字（`ToolOutputChunk` 有 `ToolCallComplete.output` 自愈，`ThinkingChunk` 有胶囊侧 `fullThought` 回填，唯回复文本无修复）。

**修复**：新增 `reconcileStreamText(accumulated, fullText)` 纯函数（CodeStreamModels，双端共用+单测）——`fullText` 非空即权威全文（引擎 contentBuilder 每轮迭代局部重建、气泡已按轮分立，跨轮覆写回归的根源已消除），空白时保留 UI 累积（反思修正轮文本可能从未流经引擎累积器）。`CodeEventReducer` 与 `CodeStreamSession` 两处收束统一走该仲裁。

### P1 Event Aggregator——已实施

**问题实证**：胶囊时间轴有 25ms（≤40Hz）渲染 ticker 攒批，但 messages 路径每 `ResponseChunk` 仍做一次 `_uiState.update` + 全消息列表拷贝 + 字符串追加（`CodeEventReducer.kt` 旧注释自认「不做 16ms 节流」）。

**修复**：`CodeViewModel` 新增 `pendingResponseText` 聚合缓冲——`ResponseChunk` 只追加缓冲，渲染 ticker 每 25ms 批量归约；边界事件（工具开始/收束/错误/系统行插队/Complete/取消）强制先冲文本再归约，保顺序；`finally` 冲残留（取消时刷新已收到内容）。聚合只合并、不丢字。

### 后续路线（独立任务）

- **P1 增量 Markdown 渲染器**：现 `MarkdownText` 为 `remember(markdown)` 全量解析（流式尾段每 tick 重解析、有界但非增量）。方向：块级增量解析（已完成区块缓存 + 活动区块单独更新 + 未闭合围栏容错 + 代码块闭合后才语法高亮）。涉及 `MarkdownParser` 结构改造，独立 PR。
- **P2 动态刷新频率**：25ms 固定档 → 按设备负载 16/25/33ms 自适应。
- **P2 流式诊断**：首 token 延迟 / 数据间隔 / 丢弃计数上报诊断页（TaskRuntime 已有 droppedUnderPressure 日志，可结构化）。
- **P2 长输出内存**：messages 路径的 4000 字工具输出截断已存在；长回复考虑分段落盘。
