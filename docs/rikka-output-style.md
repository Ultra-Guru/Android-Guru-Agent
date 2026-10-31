# Rikka 输出风格（CodeOutputStyle · v1.6）

> Coding 屏时间轴的**视觉形态**双风格：经典胶囊时间轴（既有）↔ Rikka
> 风格聊天式（新增）。设置入口：设置 → 工作台 → Coding 工位 → 输出风格。
> 与《code-output-mode.md》（流式 / 整段 = 揭示时机维度）**正交**。

## 设计立场

用户需求：「模仿 Rikka Hub 的输出方式，再做一种，与我原来的可以切换，
在设置里切换；要包含 skill 以及 MCP，然后还要增加调用工具聚合，以及更多。」

- 两风格共享同一份 `CodeStreamSnapshot` 渲染通道与消息模型（**不维护
  两套列表**），只差展示皮肤与分段策略——切换不丢任何会话数据；
- Rikka 风格不是「关掉胶囊」，而是把同一份数据重绘成聊天 App 形态。

## 两个正交维度

| 维度 | 枚举 | 语义 | 持久化字段 |
| --- | --- | --- | --- |
| 输出风格（本篇） | `CodeOutputStyle.TIMELINE / RIKKA` | 视觉形态（皮肤 + 分段） | `codeOutputStyle` |
| 输出方式 | `CodeOutputMode.STREAMING / COMPLETE` | 揭示时机（实时 vs 收束后一次性） | `codeOutputMode` |

四个组合全部有效：Rikka + 整段 = 生成中加载进度卡 + 收束后聊天式
一次性揭示（工具块默认折叠）。

## Rikka 风格渲染清单

| 条目 | 渲染 |
| --- | --- |
| 用户输入 | 右对齐实色 primary 气泡（聊天 App 形态，与结论一眼可辨） |
| 结论 | Markdown 气泡 + 流式光标 ▍（容忍未闭合片段） |
| 思考过程 | 折叠卡：流式自动展开跟随全文，收束自动折叠；手动干预优先 |
| **工具调用（聚合块）** | 连续工具调用（跨族）收进一张卡：流式「正在调用工具…」呼吸指示 + 逐调用行；收束折叠「已调用 N 个工具」+ 内置/技能/MCP 三分类 chips + 失败徽标；展开 = 每次调用明细（工具名含 技能·id / MCP·服务器 溯源、target、参数/进度/完成摘要、exit 码、耗时、状态徽标）；活跃行内嵌实时代码窗（绿增红删）与输出尾窗 |
| 单次工具调用 | 不包头行（「已调用 1 个工具」是废话），直接一行工具卡 |
| 验证轮次 | 折叠卡（轮次 + 通过/未通过），展开复用工具行 |
| 状态/系统行 | 居中 pill（聊天 App 的时间戳/事件语言） |
| 错误 | errorContainer 圆角卡（可恢复/致命 + hint） |
| 停止 | 居中 pill + StopCircle |
| 文件清单 | 触碰文件 chips（>6 折叠计数） |
| Git 操作 | 复用 GitOpVisualCard（与皮肤无关） |
| 运行统计 | 收尾卡：迭代/工具/技能/MCP/文件/失败 chips（非零才显） |
| 终端尾窗 | 复用内联终端胶囊（运行时展开跟随、完成折叠） |

## 切换语义

- **run 粒度捕获**（`captureRunOutputStyle`，runEngine 在 beginRun 前）：
  运行中改设置不打断当前轮皮肤——与输出方式档同款语义；
- **idle 期实时同步**（`observeOutputStyle`，设置流订阅）：空闲时切换
  回屏即生效（两风格渲染的都是终态内容，热切换无撕裂）——消灭
  「切了没反应」；
- 纯 UI 开关：CodeStreamSession / 引擎事件双通道零改动。

## 文件地图

```
app/src/main/kotlin/com/apex/agent/ui/screen/code/
├── CodeOutputStyle.kt          风格枚举 + 持久化名（timeline / rikka）
├── CodeOutputStyleOps.kt       VM 扩展：restore / observe / capture
├── CodeUiState.kt              outputStyle 字段（run 粒度快照）
├── CodeViewModel.kt            init 恢复+订阅；runEngine 捕获
├── CodeScreen.kt               风格分派（RIKKA → RikkaStreamTimeline）
└── stream/
    ├── RikkaStreamTimeline.kt  主列表：分段（rikkaSegments）+ 锚定 + 终端 + 加载卡
    ├── RikkaToolBlock.kt       工具聚合块 + 单工具行（RikkaToolRow，验证卡复用）
    └── RikkaCards.kt           气泡/思考/验证/统计/错误/停止/文件/pill 卡族
```

复用（internal 同包）：`rememberStreamAnchor`（滚动锚定）、
`capsuleStyle / statusColor / statusIcon / formatCapsuleDuration`（胶囊
视觉语言）、`StreamingCodeWindow`（实时代码窗）、`CodeTerminalPanel`
（终端胶囊）、`CompleteModeGeneratingCard`（整段加载卡）、
`GitOpVisualCard`（Git 可视化）、`computeToolAggregate`（三分类聚合
归约）、`MarkdownText`（Markdown 渲染）。

## 无障碍

- 工具行 statusDesc 语义描述（成败不只靠色/形，TalkBack 可感知）；
- 聚合块头行 contentDescription 播报「已调用 N 个工具」；
- 触控目标 ≥ 44dp 同既有卡片纪律。
