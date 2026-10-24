# 信使思考逻辑线（Courier / 驿传调度范式）

> Coding 模式第四思考逻辑线 —— 收发匣投递 · 趟次步进机 · 勘合通行证 ·
> 档房卷宗压缩 · 示廊公告 · 下站子驿委派。
> 独立库实现：[`com.apex.courierkit:courier-dispatch`](https://github.com/Ultra-Guru/apex-courier-kit)
> （仓库 Ultra-Guru/apex-courier-kit，纯 Kotlin JVM、零 Android 依赖），
> 宿主仅保留薄适配层 `CourierKitAdapter`。

## 一、范式一页

信使线把一次任务循环建模为一座**驿站**。用户输入是驿件，模型回复是
一趟递送，工具调用是过所勘合，子代理是下站接力：

```
用户输入 ──► 收发匣（CourierInbox）──► 趟次步进机（CourierTripRunner）
              steer 插队 / queue 排队        │ 一趟 = 一条助手消息
              控制件自成边界                  │ 逻辑趟 vs 物理尝试
                                             ▼
                              ┌─ 勘合门（PermitGate）：通配则例三态法则
                              ├─ 工具纤程（并行）：本地三件 + 宿主注册表面
                              ├─ 档房（Archivist）：估算 ≥ 触发线 → 卷宗折叠
                              ├─ 示廊（Bulletin）：常设指令价值增量公告
                              └─ 下站（Outpost）：子驿会话委派（courier_task）
```

### 与另三线的分工

| 维度 | 深潜 | 标准 | 织造 | **信使** |
|---|---|---|---|---|
| 循环宿主 | ApexAgentEngine | standard-loop 库 | loom-weave 库 | **courier-dispatch 库** |
| 输入调度 | 直接进循环 | 直接进循环 | 转向队列 | **收发匣（steer/queue/控制件 + 投递幂等）** |
| 步语义 | 迭代 | 回合 | 梭回合 | **趟（逻辑步 vs 物理尝试 + needsContinuation 收束）** |
| 工具执行 | 串行 | 串行批次 | 串行批次 | **同趟并行纤程（终趟关调用留定义）** |
| 权限门 | 主执行器组合门 | 库内权限门 | 织造权限门 | **勘合门（`*`/`?` 通配 + 后例覆盖 + always 记忆 + 复合命令逐段）** |
| 压缩 | 滑窗+摘要 | 库内压缩 | digest 织入 | **档房卷宗（近期逐字 + 七标题模板 + 70/50/35 收缩阶梯）** |
| 常设指令 | JIT 上下文 | 规则段 | 提示词织入 | **示廊价值增量（epoch 基线 + 冻结更正行）** |
| 子代理 | SubAgentRunner | 库内派发器 | 子线程群 | **子驿会话（深度闸 + 前台等待/后台唤醒 + `<courier>` 信封）** |
| 会话记忆 | code_memory | code_memory_standard | code_memory_loom | **code_memory_courier** |

## 二、核心机制

### 1. 收发匣（inbox）—— 投递调度

- **steer**（缺省）：插队件，下一安全边界**按入匣顺序整批先行**投递；
- **queue**：排队件，等空闲边界逐条投递，steer 永远优先；
- **控制件**（手动压缩）：自成边界且**置顶**——先压缩再投早先的 steer，
  保其原文在 checkpoint 之后逐字保留；
- **幂等**：按 id 去重（重复落匣静默忽略）；
- **批语义**：一次投递 ≥1 可见件 → 趟次计数器归零（整批只归零一次）。

### 2. 趟次步进机 —— 逻辑步 vs 物理尝试

一趟（trip）= 一条助手消息；趟内可发生多次**物理尝试**（压缩重试 /
流失败重递 / 溢出恢复——未产出耐久内容前重试**不耗趟次额度**）。
出口分类：

| 出口 | 语义 |
|---|---|
| Done(false) | 纯文本收束（无本地调用）→ 排水完成 |
| Done(true) | 有工具调用 → 回执落账后下一趟 |
| ContinueInterrupted | 半截流：保留部分产物 + 合成续令（"从断点继续，不重复已完成内容"） |
| ContinueLimit | 输出截断：续令（"拆小块继续"）；连续 3 次截断即失败 |
| Retry | 流失败重递（同趟：抖动指数阶梯 2s 翻倍封顶 10s ≤10 次，retryAfter 采信封顶 15 分钟） |
| Failed / Interrupted | 请求级失败 / 中止 / 拒付隧道 |

**终趟**（趟次上限）：注入终趟令 + `toolChoice=None`——工具**定义保留**
（缓存前缀不失效）但调用关闭，模型只能收口汇报。

**并行纤程**：同趟多工具调用并行执行（`maxParallelTools` 可设上限）；
输出/进度帧逐调用转发，回执独立落账。

### 3. 勘合门（permit）—— 通行证三态法则

- **则例**：`(action, resource, effect)`；resource 支持 `*`（任意串）与
  `?`（单字符）通配，尾部 `" *"` 后缀可选（`git push *` ≡ `git push` +
  任意后继参数）；
- **堆叠**：画像默认（底）→ 会话则例（中）→ always 勘合记忆（顶），
  **后例覆盖**（findLast 优先）；
- **聚合**：deny > ask > allow；未命中 → **ASK**（保守缺省）；
- **复合命令逐段**：终端类调用经命令探针提取命令串 → 拆解器切段
  （`cd /a && git push; ls` → 三段）→ 逐段核验；"always" 按段模式
  （`git push *`）记忆；
- **ask 悬置**：出票 + `UserInputRequired` 事件；答复 `yes` / `always` /
  `no`（可带 `no: 原因`）：
  - always → 记忆 save 模式 + **自动复评其余悬置票**（新则例下可通行者
    即时放行）；
  - 拒付带反馈 → **纠正性回执**（模型可见，可改姿势）；
  - 拒付无反馈 → **缺陷隧道**（趟次中断，模型不再重试同一路径）；
  - 拒一张 = 拒本轮全部悬置票（批语义）；
- **整枝剪除**：`* deny` 压轴的动作从工具面整体摘除（模型根本看不到）；
- **工具 id 伪动作通道**：宿主工具 id 型规则（无空格模式）映射到
  `tool` 伪动作（非严格请求——未命中不参与聚合）。

### 4. 档房（archivist）—— 结构化卷宗压缩

- **触发**：估算 ≥ 窗口 − max(10%, 16k)（未知窗口永不自动触发）；
- **切分**：从尾部累计 keepTokens → 回退到**用户消息边界**（调用与
  回执永不拆开；至少保留最后一个交换）；近期段**逐字保留**；
- **卷宗**：远期段摘要为七标题模板（`# Mission / # Constraints /
  # Decisions / # Progress / # Next Step / # Key Files / # Notes`）；
  缺标题 → **纠偏一次**，再缺 → 压缩失败（不落账）；
- **投递阶梯**：原文 → 扁平转写（`[User]:` / `[Assistant dispatch]:` /
  `[Tool result]:` ≤1250 字符/条）→ 70% → 50% → 35% 收缩（最旧交换
  逐组丢弃）；
- **落账**：卷宗信封（`<station-dossier>`）单条 User 消息 + 近期段，
  checkpoint 之后的模型视图从信封起读；epoch 随之推进。

### 5. 示廊（bulletin）—— 常设指令价值增量

常设指令源（环境 / 日期 / 工作区 / 全局规则 / 项目规则 / 技能索引）
逐源读取 → SHA-256 指纹 → 变更才动作：**首次**全文进基线（渲染进
系统提示词，每趟都在）；**后续变更**产出一条**冻结更正行**（以 System
消息渲染一次——模型在时间轴上看到一次显式更正，而不是提示词悄悄变）。

### 6. 下站前哨（outpost）—— 子驿会话委派

`courier_task` 委派子代理 = **子驿站会话**（独立组件集：纯内存会话 /
自动拒付勘合 ask / 无计划门）：

- **深度闸**：递归深度受限（缺省 1——子驿不再委派）；
- **前台**：等待完成，结果进 `<courier station="…" state="…">` 信封；
- **后台**：登记句柄立即回执 + "不轮询"训令；完成时向母站收发匣投递
  **合成输入**（唤醒下一安全边界）；
- 问答表单 `courier_question` 与行程清单 `courier_todos` 同为本地工具。

### 7. 计划门（PLAN 模式宿主适配）

规划趟解析 `GOAL/STEPS/RISKS` 结构 → `PlanGenerated` +
`PlanAwaitingConfirmation` → 确认后注入批准续令转执行（驳回注入反馈
续令继续修订）。信使线 PLAN 的画像法则 = 只读（edit/write 整枝拒付）。

## 三、接线分工（库 ↔ 宿主）

| 层 | 库（apex-courier-kit） | 宿主（本仓库） |
|---|---|---|
| 循环内核 | CourierTripRunner / Inbox / PermitGate / Archivist / Bulletin / Outpost | — |
| 协议类型 | Dispatch*（消息/事件/工具面/画像/则例） | AgentEvent / LlmMessage ↔ 适配器 1:1 映射 |
| 端口契约 | CourierPorts（15 端口） | ModelRuntime / ToolRegistry / CodeConversationMemory / AppLogger 接线 |
| 事件面 | DispatchEvent（21 型） | `CourierKitAdapter.toHost()` 映射 AgentEvent |
| 执行器 | 勘合门独占权限确认 | `@Named("courierEngineTools")` 仅环境门（#230 纪律） |
| 会话记忆 | — | `code_memory_courier` 独立通道（切回现场不丢） |
| 模式切换 | — | `StandardLogicMode.COURIER` / `DualLogicCodeEngine` 第四路 / `/logic:courier`（别名 express） |

composite build 钉版：`gradle/courier-kit.lock`（sync-courier-kit 工作流
只跟库仓 main **全绿**提交自动 bump）。

## 四、验证

- 库仓 CI：build-test（编译 + 纯 JVM 单测 + mavenLocal 冒烟）全绿，
  含 45+ 用例（收发匣投递语义 / 步进机出口分类 / 勘合法则与 always
  记忆 / 卷宗纠偏与收缩阶梯 / 计划门 / 子驿信封 / 引擎集成金色路径）；
- 宿主测试：`DualLogicCodeEngineTest`（四线路由 + 信使线装配可选性）/
  `StandardLogicModeTest`（别名 round-trip + 标志位互斥）；
- 全部验证经 GitHub Actions（ci / quality-gate / guard-rails /
  changelog / apk）。
