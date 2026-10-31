# 织造引擎（Loom）— Coding 模式第三思考逻辑线

> **已实施**（本 PR）。Coding 屏「思考逻辑」选择器扩展为三档：**深潜**
> （自研七档思考，默认）、**标准**（业界标准 Agent 任务循环）、**织造**
> （经纬交织范式：in-band XML 工具协议 + 梭回合机 + 权限模态门 + 子线程
> 群 + 织造包 + 运行中转向）。三线经 `DualLogicCodeEngine` 门面并联，事件
> 协议 100% 复用 `AgentEvent`——胶囊时间轴、长任务追踪、权限问答、计划
> 确认卡零改动三线通用。切换入口三通道：右上角选择器 / `/logic:loom`
> （别名 `weave`）/ 重启自动恢复（`AgentSettings.codeThinkingLogic`）。

## 一、动机与定位

深潜强在**纵深推理**、标准强在**编排纪律**，织造补上第三种被独立验证过的
思路——**协议自洽的任务织造**：

- **思考链为经线**：每一梭（回合）先 `<deliberate>` 思考块（+ 原生
  reasoning 双通道）再行动；纯思考回合被系统退回重织（nudge 机制）；
- **工具调用为纬线**：**in-band XML 协议**——调用是文本里的 `<use>` 块，
  回执是下一轮用户消息里的 `<outcome_随机短码>` 块。原生函数调用由
  桥接层翻译成同一协议，**无函数调用能力的模型也全兼容**；
- **记忆为布面**：历史超预算时旧轮结晶成 `loom-digest` 摘要轮（SUMMARY
  角色 LLM 摘要，滑窗兜底保摘要轮），恒居历史头；
- **循环为梭**：流式回合机——串行批次执行、防环三警四收敛、瞬时故障
  阶梯重试、上下文溢出压缩重试、输出截断断点续跑、运行中转向队列。

## 二、总体架构

```
CodeViewModel（Coding 屏）
   │ setLogicMode / restoreLogicMode / /logic:loom
   ▼
DualLogicCodeEngine（三路路由门面，volatile 单引用切换）
   ├──► CodeAgentEngine      深潜线（七档思考）      记忆：code_memory/
   ├──► StandardKitAdapter   标准线（任务循环库）    记忆：code_memory_standard/
   └──► LoomKitAdapter       织造线（循环库接线）    记忆：code_memory_loom/
          └─ loom-weave 库内核（Ultra-Guru/apex-kit-loom，com.apex.loomkit:loom-weave）
             ├─ LoomSession            会话织物 + 系统提示词每回合 JIT 重织
             ├─ LoomRoundRunner        梭回合机（流式往返 + 分流守卫）
             ├─ LoomMarkupParser       六态流式增量解析器（跨块 holdback）
             ├─ LoomNativeBridge       原生 tool_calls → use 标记翻译器
             ├─ LoomOutcomeFormatter   回执织入（预算三级裁剪 + Unicode 标量安全）
             ├─ LoomPermissionGovernor 权限三态门（模态 × 效应 × 边界）
             ├─ LoomMemoryWeaver       布面压缩（digest 主路径 + 滑窗兜底）
             ├─ LoomSwarmHub           子线程群（agent-as-tools 委派）
             ├─ LoomPackager           织造包（技能包运行时激活）
             └─ LoomThinkingPolicy     档位策略（回合倍率 + 努力取向 + 家族偏好）
```

## 三、in-band XML 协议（签名特性）

```
模型输出（一轮）：
  <deliberate>先想清楚边界条件……</deliberate>
  我先读一下入口文件。
  <use name="CodeRead"><arg name="path">src/Main.kt</arg></use>

系统织回（下一轮用户消息）：
  <outcome_a3f9 name="CodeRead" status="ok"><payload>文件内容……</payload></outcome_a3f9>
```

| 块 | 文法 | 说明 |
|---|---|---|
| 思考块 | `deliberate` 包裹 | 思考链呈现（UI 思考卡），可选多段 |
| 调用块 | `use name=…` + `arg` 子元素 | 一轮可多个，按序串行执行 |
| 回执块 | `outcome_短码` + `payload` | 每块随机 4 位 base36 短码——防伪造防跨轮抄标签 |
| 摘要块 | `loom-digest rounds=N` | 压缩后的旧轮结晶，恒居历史头 |
| 布样块 | `loom-plan goal=…` + `step`/`note` | PLAN 模式首轮计划（人控门确认） |

- **参数保真**：原生函数调用的 arguments JSON 原文随调用保存（嵌套结构
  零失真）；手写 use 块由结构重建；
- **回执预算**：常规预算（默认 12000 字符）裁剪 + 截断标注行 + 绝对上限
  （64000）兜底；截断按 Unicode 标量对齐（不劈代理对）；
- **实体转义**：arg 值与 payload 全量 XML 实体转义（五命名 + 数字实体）；
  Markdown 正文不经此通道。

## 四、梭回合机（LoomRoundRunner）

```
用户轮落账（含转向队列）
  └─ 循环（回合预算 = 配置 × 档位倍率，钳位 ≥4）：
       转向织入 → 系统轮 JIT → 流式调用（重试阶梯）
         → 标记解析分流：
             布样（PLAN 未确认）→ 人控门 → 确认续织 / 驳回重织
             无调用            → 终局（ResponseComplete + Complete）
             纯思考            → nudge 催促（预算内）/ 强制收尾
             空回合 ×2         → 终止（Error）
             调用批次          → 防环裁决 → 串行执行（权限门 → 流式执行
                                → 回执）→ 回执轮落账 → 压缩检查 → 下一梭
```

- **防环**：批次指纹（同名同参）三次告诫、四次强制收敛（拒绝执行重复
  批次，改织「总结现状」指令）；
- **容错**：TRANSIENT 指数退避重试（默认 3 次，`LlmRetryScheduled` 事件
  可见）；CONTEXT_OVERFLOW 压缩后重试一次；
- **截断续跑**：`finishReason=length` 时织入断点续跑指令（限 2 次）；
- **中止**：协作信号（回合边界 + 流帧边界检查）+ 问答/布样抢占收口 →
  `Aborted` + `Complete` 正常收场。

## 五、权限三态门（LoomPermissionGovernor）

判定层级：**会话批准记忆 → 显式规则（命令规则优先）→ 模态兜底**。

| 模态 | 映射自宿主 | READ | 工作区内写 | 越界写 | 敏感 |
|---|---|---|---|---|---|
| OBSERVE 巡视 | PLAN | 放行 | 问 | 问 | 问 |
| FABRIC 布面 | DEFAULT / ACCEPT_EDITS | 放行 | 放行 | 问 | 问 |
| UNBOUND 无界 | BYPASS | 放行 | 放行 | 放行 | 问 |

- 效应画像：内置册（code_* / git / 文件 / 终端 / web / 技能）+ 命名启发
  （write/edit/commit → WRITE；未知 MCP 一律 WRITE 保守）；
- 边界语义：路径参数经 canonical 判定（guest `/workspace` 挂载换算回宿主
  根；相对路径以根为基）；
- 会话批准记忆：批准一次（工具 id / 命令首两词前缀）同会话直通；
- 问答窗口：超时按拒绝收口（`UserInputExpired`）；abort 抢占即时收场；
- 子线程策略：AskPolicy.AUTO_DENY——无交互窗口，需确认的操作自动拒绝
  并引导回主线程执行。

## 六、子线程群（LoomSwarmHub）

主模型经四工具委派可隔离子目标：

| 工具 | 语义 |
|---|---|
| `loom_thread_start` | 起飞（label + 首条任务，起飞即首飞） |
| `loom_thread_send` | 追加消息推进到本飞收口（可带 `expect` 条件判定） |
| `loom_thread_status` | 快照（阶段 / 轮数 / 截断回执；不传 id 列全部） |
| `loom_thread_observe` | 不推进摘录近期织物 |

- 每条子线程 = 独立会话 + 独立回合机（共享模型/执行/权限源）；
- 三预算闸：并发上限（默认 2）/ 回合上限 / 壁钟超时（默认 240 秒，
  超时 FAILED 附已织内容）；
- 防递归：子线程工具面排除 `loom_*` 本域工具。

## 七、织造包（LoomPackager）

技能/方法论的**运行时激活**（区别于系统提示词常驻注入）：

- `loom_package_list / use / drop` 三工具（目录 → 激活 → 停用）；
- 激活即生效：**回执载荷即包内指引全文**（模型立即可读可遵）+ 激活集合
  注入系统提示词织造包段（每回合 JIT）持续生效；
- 目录端口接宿主技能仓库 coding 域（`SkillRegistry.getSkillDigests` +
  `get`）——与深潜/标准线共享同一技能资产；
- 会话级生命周期（新会话清空）。

## 八、记忆与压缩（LoomMemoryWeaver）

- 独立通道 `code_memory_loom`（per-workspace JSON 文件，与深潜/标准隔离
  ——切线现场保留、切回恢复）；
- 阈值：占窗口比 0.8 × 窗口（模型感知真实窗口，缺省 128K）；
- 主路径 LOOM_DIGEST：旧轮（含既有摘要轮——摘要可再结晶）交 SUMMARY
  角色（contextTag=`loom_digest`）压成摘要轮；
- 兜底 SLIDING_WINDOW：摘要请求失败时保留近期轮 + 既有摘要轮；
- digest 源限幅（每轮 600 字符 / 摘要 4000）防摘要请求撞窗。

## 九、门面契约与接线（库模块化）

### 拆库布局（同 apex-kit-standard 纪律）

织造循环已提取为独立库仓 **Ultra-Guru/apex-kit-loom**（坐标
`com.apex.loomkit:loom-weave`）——全部循环语义（标记协议 / 梭回合机 /
权限门 / 子线程群 / 织造包 / digest 压缩 / 引擎测试矩阵）在库仓演进：

- **库仓**：纯 Kotlin JVM（依赖仅 coroutines + serialization-json），自有
  协议类型（`WeaveMessage` / `WeaveEvent` 22 型 / `WeaveToolStream` 等
  ——零宿主依赖的可执行证明）；自带 CI（build-test + consumer-check）+
  词汇护栏（上游项目词汇零容忍扫描）+ notify-host 通知链；
- **宿主**：`core/code-engine` 仅保留 `loom/LoomKitAdapter.kt`（端口-适配
  器——双向映射 AgentEvent↔WeaveEvent / LlmMessage↔WeaveMessage /
  ToolDefinition↔WeaveToolDefinition / 档位八折六折叠 + 宿主域知识段
  渲染：工作区上下文 / 全局与项目规则）；
- **接入**：composite build（settings.gradle.kts 条件 includeBuild 兄弟/
  嵌套目录，按坐标自动替换为本地构建）+ `gradle/loom-kit.lock` 钉版
  （ci / apk / release 三流水线 pinned checkout）+ `sync-loom-kit`
  工作流自动跟绿 bump（notify-host 事件路 + 30 分钟 cron 兜底）；
- **执行器纪律（#230 同款）**：库内权限门 → 注入
  `@Named("loomEngineTools")` **仅环境门**执行器（限流/熔断/策略/追踪/
  钩子/脱敏保留，权限确认归库内权限门独占——防双弹窗）。

### 宿主门面

- `LoomKitAdapter : AgentEngine, CodeEngineFacade`——17 方法全直通
  （execute ×2 / abort / submitUserInput / cancelUserInput + 16 facade）；
- DI（`CodeModule`）：`@Named("codeLoom")` 记忆通道 + `LoomKitAdapter`
  装配 + 三路 `DualLogicCodeEngine`；
- 工具面：宿主注册表 + 库内本地 8 工具（子线程 5 + 织造包 3）；
  forcedTools 非空 = 只暴露选中；
- 布样确认：`submitPlanConfirmation` 经库内 `LoomPlanGate`（布样事件走
  宿主 `PlanGenerated/Awaiting/Confirmed` 三连——计划卡 UI 零改动）。

## 十、测试矩阵

| 套件 | 覆盖 |
|---|---|
| LoomMarkupParserTest（26） | 六类块解析 / 跨块截断 / 实体转义 / finish 语义 |
| LoomNativeBridgeTest（12） | 增量装配 / 交错次序 / 降级 / 幂等 |
| LoomOutcomeFormatterTest（13） | 裁剪三级 / 四态回执 / 用户轮织入 |
| LoomSessionTest（9） | 轮次落账 / 提示词段序 / 记忆联动 |
| LoomGuardsSteerApprovalTest（13） | 防环 / 转向 / 问答信箱 |
| LoomMemoryWeaverTest（7） | digest 主路径 / 滑窗兜底 / 限幅 |
| LoomPermissionGovernorTest（21） | 模态矩阵 / 规则优先级 / 边界 / glob 族 |
| LoomRoundRunnerTest（11） | 剧本式集成（双协议往返 / 问答 / 布样 / 中止） |
| LoomSwarmHubTest + LoomConditionTest（16） | 子线程生命周期 / 条件 DSL |
| LoomEngineTest（5） | 门面组装 / 工具面 / forcedTools / 路由 |
| DualLogicCodeEngineTest + StandardLogicModeTest | 三路路由 / 档位别名 |

全部纯 JVM（JUnit4 + runTest + 手写 fake——无 mock 框架）。引擎套件随
**apex-kit-loom** 库仓 CI 跑（`:loom-weave:test`）；宿主侧保留
`DualLogicCodeEngineTest` + `StandardLogicModeTest`（三路路由 / 档位别名，
随 `:core:code-engine:test` 跑入 CI）。

## 十一、与另两线的分工表

| 维度 | 深潜 | 标准 | 织造 |
|---|---|---|---|
| 循环宿主 | ApexAgentEngine | standard-loop 库 | loom-weave 库 |
| 工具协议 | 原生函数调用 | 原生函数调用 | in-band XML（桥接翻译） |
| 权限门 | 主执行器组合门 | 库内权限门 | 织造权限门 |
| 子代理 | CodeTask | task 委派 | loom_thread_* 子线程群 |
| 压缩 | ContextCompressor | 库内压缩 | digest 织入 + 滑窗 |
| 记忆通道 | code_memory | code_memory_standard | code_memory_loom |

三线并联、现场隔离、事件同协议——用户按任务形态选线，切换即路由。
