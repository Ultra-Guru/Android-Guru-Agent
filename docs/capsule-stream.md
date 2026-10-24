# 胶囊流式输出系统 — Coding 工作流时间轴

> Coding 模式与 Agent 聊天模式的本质差异：**Coding 工作流不是聊天**。
> 主输出是 **Diff + 终端日志 + 结构化错误** 三件套，Markdown 正文只是
> 结论载体。本系统把 Coding 页的消息流升级为「胶囊时间轴」——每一次
> 工具调用（读/写/改/搜/命令/git/lint/测试）都是一颗带状态的胶囊。

## 一、架构总览（VM 双通道分流）

```
AgentEvent 流（引擎零改动）
   │
   ├─① 副作用通道（既有 reduce，保持不动）
   │    长任务追踪 · 深水区升级观察器 · 会话落盘 · AUTO 错误史归档
   │
   └─② 渲染通道（本系统新增）
        streamSession.onEvent(event)     纯归约 + 置脏（25ms 攒批）
            │
        render ticker（25ms ≤40Hz）
            │
        uiState.stream: CodeStreamSnapshot 不可变快照
            │
        CodeStreamTimeline 渲染（时间轴 + 终端面板 + 详情弹层）
```

渲染与副作用彻底分流：渲染风暴（流式 token、工具输出洪峰）不再冲击
副作用链路；既有功能（长任务/检查点/档位自治）零改动。

## 二、核心组件（core/code-engine/.../code/stream/）

| 文件 | 职责 |
|------|------|
| `CodeStreamModels.kt` | ToolKind 十族 / ToolCallStatus 六态 / StreamToolCall 胶囊视图态 / StreamEntry sealed 时间轴条目 / CodeStreamSnapshot 快照 |
| `CodeStreamSession.kt` | 事件归约状态机：工具全生命周期 / 流式追加 / 验证闭环聚合 / callId 幂等 / 25ms tick |
| `UnifiedDiffParser.kt` | 双格式 diff 归一（git hunk + CodeEditTool mini），行号语义 + 截断/统计 + 容错 |
| `TerminalPulseBuffer.kt` | 四条件脉冲（4KB / 8 行 / 120ms / 显式 flush）+ 16KB 环形尾窗 |
| `CodeStreamCheckpoint.kt` | 扁平持久化：lastEventId + committedFiles + pendingToolCalls + entries（存 diff 原文） |

## 三、UI 组件（app/.../ui/screen/code/stream/）

| 文件 | 职责 |
|------|------|
| `CodeCapsule.kt` | 胶囊本体（族着色图标 + target + 状态徽标 + hunk 进度 + 摘要）+ 「+N 更多」分组 |
| `CodeTerminalPanel.kt` | 终端面板（恒深底 + ANSI 清洗 + 独立锚定 + 可折叠） |
| `CodeDiffView.kt` | hunk 级 Diff（双侧行号 + 绿红底 + 骨架态 + 截断提示） |
| `CodeToolDetailSheet.kt` | 详情弹层分族路由 + 滚动保持（键控 call.id） |
| `CodeStreamScroll.kt` | 双锚定（时间轴与终端各自独立的智能滚动） |
| `CodeStreamCards.kt` | 卡片族：思考链 / 验证轮次（红绿态）/ 结构化错误 / 停止 / 文件 chips / 流式气泡 |
| `CodeStreamTimeline.kt` | 时间轴主列表（同族连续聚合 + 稳定 key + 回底 FAB） |

## 四、关键设计决策

### 4.1 幂等（中断重试安全）

- **callId 即幂等键**：ToolCallStart 重放跳过（已存在即返回）、
  ToolOutputChunk 幂等追加、ToolCallComplete 兜底补建（Start 丢失的
  恢复场景）——整条事件流重放安全；
- **检查点恢复**：未到终态的胶囊恢复为 `PARTIAL`（中断态），由用户
  决定单工具重试（定向指令 + 幂等键语义）或整轮重跑；
- **持久化存 diff 原文**而非 hunk 结构——解析器改进后旧档案自动受益。

### 4.2 脉冲式输出（禁止打字机）

- 终端输出按四条件**整块 flush**（体量/行数/时间窗/显式）——一屏内容
  一次到位，杜绝逐字符重组；
- Diff 按 hunk 级拼装，流式半截 hunk 照常渲染（骨架态兜底）；
- 渲染 25ms ≤40Hz 攒批：tick 无变更返回 null，StateFlow 零更新。

### 4.3 防刷屏 / 防死循环（验证闭环）

- edit→lint/test→再 edit 的闭环会自行延长胶囊链：
  - **验证轮次卡**聚合「一轮改动 + 随后的验证」为可折叠轮次（红绿态
    自动判定），轮内明细默认收起；
  - **同族连续超 3 颗**收进「+N 更多」分组行；
- 错误即 `ErrorEntry` 结构化卡（可恢复标记 + 提示 + 关联胶囊 id），
  禁止 AlertDialog 打断工作流。

### 4.4 双锚定

时间轴与终端面板**各自独立**持有一份 `StreamScrollAnchor`：
- `isAtBottom`（150px 派生）+ 阅读模式（索引递减方向监测）+ 流式
  即时跟随 / 收尾动画；
- 用户在终端里翻历史时，时间轴照常跟随对话推进，互不抢占。

## 五、验收 Checklist

- [x] 工具调用全程胶囊化（读/写/改/搜/命令/git/lint/测试/计划/MCP 十族）
- [x] 胶囊状态机 waiting→running→success/failed/applied/partial
- [x] 编辑类成功显示 hunk 进度（3/7）与 `+N −M` 摘要
- [x] 命令类显示 exit code 与耗时（3.4s）
- [x] BASH 点击 → 终端面板 / 详情全文
- [x] EDIT/WRITE 点击 → hunk 级 Diff（双侧行号）
- [x] GREP 点击 → 命中列表（文件头行高亮）
- [x] 同族连续 >3 收进「+N 更多」，展开态本地保持
- [x] 验证闭环折叠为轮次卡（红绿态）
- [x] 思考链独立卡片（可折叠全文）
- [x] 错误结构化卡片（可恢复标记 + 提示），无 AlertDialog
- [x] 停止卡 + 完成摘要 + 受影响文件 chips
- [x] 终端脉冲整块 flush（四条件），无打字机抖动
- [x] 时间轴 + 终端双锚定，阅读模式互不干扰
- [x] 25ms ≤40Hz 渲染攒批（tick 无变更零更新）
- [x] 中断恢复：检查点落盘 → PARTIAL 态 + lastEventId + committedFiles
- [x] 旧版会话快照兼容（messages → 时间轴降级映射）
- [x] 抽屉导航：Coding 入口在 Agent 正下方（修复缺失项）

## 六、边界用例（测试锁定）

1. **半截 hunk**：流式期间 diff 原文不完整 → 已到行照常渲染，无崩溃；
2. **万行输出**：环形尾窗 16KB 常数内存，面板与详情不撑爆；
3. **事件风暴**：100 个 ToolOutputChunk 不 tick → 终态仍只有一颗胶囊；
4. **重放幂等**：Start/Complete 各重放一次 → 时间轴无重复条目；
5. **轮次嵌套**：edit→test(红)→edit→test(绿) → 两轮次卡，各自红绿态；
6. **坏档隔离**：检查点 JSON 损坏 → .corrupt 隔离 + 空时间轴恢复；
7. **旧档升级**：v1.x 会话快照（无 stream 字段）→ messages 降级映射；
8. **快速切档**：ULTRACODE→STANDARD→APEXCODE 反复切换 → 旋钮基数
   快照保证无指数污染；
9. **未完成恢复**：中断时 running 的 BASH → PARTIAL + pendingToolCalls；
10. **空输入**：空 diff / 空输出 / 空命令 → 骨架与占位文案，无异常。

## 七、终端面板流水式折叠（v2 增补）

用户需求统一口径：「思考时保持展开、思考完折叠；工具调用运行时详细展示、
调用完折叠；终端命令等流水似输出同款处理」。思考卡与工具胶囊此前已按此
语义落地；本节补齐最后一块 —— 终端面板。

- **信号**（CodeStreamSession 维护，随快照下发）：
  - `terminalBusy`：活跃 BASH Start 置 true；Complete / Error / Aborted /
    beginRun / replaceAll 收束为 false；
  - `terminalCommandSummary`：头行展示的首行命令（新命令接管时刷新）；
  - `terminalExitCode`：命令收束时的退出码（exitCodeOf 尾部解析），
    供折叠胶囊头行回看。
- **UI 时机**（CodeScreen）：`LaunchedEffect(terminalBusy)` —— 新命令开跑
  强制展开并重置手动态；命令收束且用户未手动干预则自动折叠（userToggled
  守卫，与 ThinkingCard 同款语义）；手动展开/收起永远尊重。
- **面板**（CodeTerminalPanel）：头行 = 状态点（运行中呼吸脉冲 / exit 0
  绿 / 非零红）+ 命令摘要 + 运行中标签或 exit 码；正文展开/收起改
  AnimatedVisibility（expand/shrinkVertically），滚动状态提升不受折叠影响。
- **边界**（测试锁定，CodeStreamSessionTest）：命令收束 busy=false 且
  退出码保留；第二条命令接管三信号；Abort/Error 中途收束；beginRun 归零。


## 八、参数流式可视化与同族聚合胶囊（v3 增补）

用户需求：「调用写文件/编辑文件工具时，正在写时能实时显示不断写入的
代码（编辑时绿色代表写入、红色代表删除），写完/编辑完成后收回最小化，
过程自动展开、用户也可自己点击」；「同种工具直接放在一起——几十条终端
命令只显示一颗胶囊、始终展示最新命令，展开可查看全部历史、点击进详情」。

### 8.1 参数增量事件（引擎半边）

- `AgentEvent.ToolCallArgsDelta(callId, toolName, delta)`：LLM 流式生成
  tool_calls 时每个 arguments 分片一条（`EngineToolCallDelta.kt` —— 主循环
  1:1 换行 `acc.append(...)` → `appendFragmentAndEmitDelta(...)`，累积与
  上抛同源，`acc.build()` 产出的全量参数与旧管线零差异）；
- 生命周期约定：同一 callId 的 Delta 流结束后必有 ToolCallStart（全量参数、
  状态升级）；消费端按 callId 幂等合并，重放安全。

### 8.2 半截 JSON 的增量键锚定提取（StreamingArgsPreview.kt）

- 每个关注键一个 KeySlot：结构性锚定（`"key"` 前必须是 `{`/`,`，防值内
  同名文本误命中）→ 增量解码（转义感知；尾部不完整 `\u` 存进度等下片，
  不重消费）→ 未转义闭引号封口；
- 尾窗语义：超长值截头留尾（CODE_CAP 24K / EDIT_CAP 8K），写入增量永远
  在尾部，truncated 标记供 UI 提示「上文已省略」；
- 兼容口径：CodeEdit 用 `old_string`/`new_string`，Agent 屏 FileEdit 用
  `edits[].search`/`edits[].replace`；目标键 path/command/pattern/query/
  keyword 多候选首个生效。

### 8.3 等待态胶囊（Session 归约）

- 首个可展示内容到达即建 WAITING 胶囊（工具意图先于执行可见）；
  MCP/未知族无追踪器不建卡，Start 按原路径兜底；
- Start 到达：WAITING → RUNNING 升级（族名/目标/摘要以全量参数补全，
  预览保留到 Complete —— 执行期代码窗持续；计数不重复）；
- Complete / 中止 / beginRun：预览清空 = 代码窗自动收回，追踪器退役
  （内存即回收）。

### 8.4 UI：实时代码窗（CodeStreamingWindow.kt）

- 写文件全文绿色 + 尾部光标块 `▍`；编辑文件红删（`− ` 前缀）在前、
  绿增（`+ ` 前缀）在后 —— 与 hunk Diff 增删同语义（深底取亮色变体）；
- 恒深底 0xFF101418（与终端详情体同款，跨主题一致）；只展示尾窗
  （14 行嵌入 / 400 行详情），流式增量永远在尾部；
- 生命周期：WAITING/RUNNING 展开展示（AnimatedVisibility expand），终态
  自动收回（shrink；lastPreview 缓存保证收束动画最后一帧有内容可渲染）；
  点击胶囊 → DetailSheet 同款实时预览（大视口）。

### 8.5 UI：同族聚合胶囊（CodeCapsuleGroupRow + GroupExpandable）

- 阈值 ≥2（GROUP_THRESHOLD=1）：连续同族胶囊收成一颗聚合胶囊；
- 折叠态 = 最新一颗的完整形态（名称/命令/状态/耗时）+ ×N 计数徽标；
  点击展开；
- 展开态 = 收起头行（族图标 + ×N + 「收起」）+ 全部历史胶囊平铺，每颗
  可点击进详情，头行点击再收起；
- 流水式生命周期：分组内出现活跃胶囊（参数生成/执行中）自动展开（否则
  折叠态会遮住实时代码窗）、全部收束且未手动干预自动收回；手动展开/
  收起永远优先，新流式周期重置手动态（与终端面板/思考卡同款语义）；
- 锚定：预览长度计入 contentLength 档位素材 —— 代码流入时贴尾跟随。

### 8.6 测试锁定

- `StreamingArgsPreviewTest`（12 例）：分片累积 / 转义解码（含跨分片
  `\u` 与代理对）/ 结构性锚定防误命中 / 非字符串值与重复键容错 /
  非 JSON 放弃 / 尾窗截断 / 命令目标 / 回放等价 / 非预览族；
- `CodeStreamArgsDeltaTest`（8 例）：等待态建立与升级（计数不重复）/
  Complete 清空预览 / 迟到分片忽略 / 中止收口 / MCP 族不建卡 /
  名字晚到缓冲回放 / BASH 终端三信号 / 路径后到目标刷新。

## 九、全面可视化增强（v4 增补）

在 v3（参数流式 + 同族聚合）之上补齐五处可视化：运行收尾的结构化
统计卡、聚合胶囊的历史状态条、单颗胶囊的实时进度条、流式光标的呼吸
动画、验证轮次卡的增删行徽标——数字不再只靠文本行承载。

### 9.1 运行统计收尾卡（RunStatsEntry）

- **模型**（CodeStreamModels）：`StreamEntry.RunStatsEntry(id, iterations,
  toolCalls, failedCalls, filesTouched, durationMs)` —— Complete 事件的
  结构化载体，取代旧纯文本「完成 · N 轮 · N 次工具 · Ns」状态行；
- **归约**（CodeStreamSession）：`runStartFailedCount` 在 beginRun 记下
  会话累计失败基线，Complete 时差值即**本轮失败数**（failedToolCallCount
  跨 run 累积、filesTouched 每 run 清零——统计卡三项口径全部按 run 对齐）；
- **出卡门槛**：纯问答轮（零工具零文件）不出重卡，保留旧轻量状态行
  （hadWork = totalToolCalls > 0 || files 非空）——重可视化只给干活轮；
- **检查点**（CodeStreamCheckpoint）：type="runstats" 扁平存取，五数值
  默认 0 + ignoreUnknownKeys 双向兼容旧档；
- **UI**（CodeStreamCards.RunStatsCard）：玻璃卡头行（完成/失败图标 +
  总耗时）+ FlowRow 统计 chips（轮数/工具/文件/失败），失败 >0 全卡
  错误色；`entryContentLength` 补 160 档。

### 9.2 聚合胶囊历史状态条（GroupHistoryStrip）

- 折叠态聚合胶囊主行下方一枚色块 tick 代表一次调用：终态用成对语义色
  （绿成/红败/琥珀中断），WAITING/RUNNING 用族色做呼吸脉冲（无限过渡
  无条件组合，无活跃时不读动画值——槽表稳定）；
- tick 上限 12（GROUP_STRIP_MAX_TICKS），超出「+还有 N 个」文本收尾
  （复用 code_stream_files_more_fmt）；
- 纯装饰无点击语义（组级 TalkBack 播报已含 ×N 计数，不重复）。

### 9.3 实时进度迷你条（CapsuleProgressBar）

- 单颗胶囊 RUNNING 且有进度时，主行下 2dp 细条随进度填充（族色）；
- 编辑类优先 hunksApplied/hunksTotal；其余回退 ToolProgress percent，
  两套量纲（0..1 小数 / 0..100 整数）按 ≤1 与 ≤100 分段折算（liveProgressFraction）。

### 9.4 流式光标呼吸动画（CodeStreamingWindow）

- 尾部光标块从静态 `▍` 升级为透明度 0.15↔1 往返脉冲（750ms，
  RepeatMode.Reverse）；StreamCodeBlock 以 AnnotatedString 单 span 着色
  （cursorAlpha 参数，null = 旧行为），写/编辑两个分支都接线；
- 终态收束窗口整体收回，动画随组件销毁停。

### 9.5 验证轮次卡增删徽标（VerifyCycleCard）

- 轮次头行新增 `+N −M` 行级改动量徽标：轮内编辑胶囊 diffText 现场经
  UnifiedDiffParser 求和（remember(entry) 缓存），绿色与全局增删口径一致；
- 恢复回放的轮次 calls 为空 → 不显徽标（轮次红绿态保留）。

### 9.6 测试锁定（CodeStreamSessionTest）

- `complete appends summary and file chips`：统计卡五字段逐一断言
  （轮数/调用数/失败/文件/耗时）；
- `run stats failed count is per run not cumulative`：第一轮失败 1 → 卡 1；
  第二轮干净 → 卡 0（跨 run 不串台）；
- `run stats entry round trips through checkpoint`：扁平化存取零丢失；
- 旧「完成状态行」断言（2 处）同步迁移到 RunStatsEntry。
