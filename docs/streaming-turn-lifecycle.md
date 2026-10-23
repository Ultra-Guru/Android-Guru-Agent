# Agent 屏流式回合生命周期（Turn Lifecycle）收口

> 修复主线：用户反馈「流式输出问题很大 —— 流式输出完成后输入框之间的逻辑，
> 任务结束后输入框发送按钮还显示执行中」。本文记录根因分析与修复后的
> 回合生命周期契约，供后续改动（尤其新增执行路径时）遵守。

## 1. 根因分析

旧实现把 `isLoading`（驱动发送键 ↔ 停止键切换、顶部加载条）的复位完全
寄托在「终止事件（Complete / Error / Aborted）能送达一个**活着的**收集器」
上。事件链：`ApexAgentEngine.execute()`（冷流，finally 无条件 emit
`Complete`）→ `TaskRuntime` 镜像收集器（独立 runtimeScope，UNLIMITED tap
Channel）→ VM 的 `currentJob` 收集器 → `AgentChatEventApplier.handleEvent`。

一旦 VM 收集协程在终止事件到达前被取消，事件永久滞留 tap 缓冲
（TaskRuntime 保证持久层/checkpoint 正确收口，但 UI 层无人复位），
`isLoading` 永久为 true → 发送按钮卡「停止」。已确认的触发路径：

| # | 路径 | 旧表现 |
|---|---|---|
| 1 | `runEngine` / `collectEngineFlowSafely` 的 `catch(CancellationException){throw}` 后无 finally | 约定外取消（resume 覆盖、Activity 重建等）→ isLoading 卡死 |
| 2 | Complete 分支先 `withContext(Default)` 算指标再复位 | 挂起瞬间被取消 → 复位整段跳过 |
| 3 | 会话切换只 cancel UI 收集器，不 cancel TaskRuntime | 僵尸任务后台烧 token；`restoreHistory/clearHistory` 与引擎循环并发修改无锁 ArrayList；下轮 sendMessage 阻塞最长 10s |
| 4 | resume/retry/resumeCrashed 不置 isLoading=true | 运行期间输入栏显示「发送」与任务卡「执行中」矛盾 |
| 5 | abort() 清理面不全 | pendingUserInput / 计划与 Spec 确认卡 / 步骤索引悬空 |
| 6 | sendMessage 先清草稿、后等僵尸收尾 | 最长 10s 空窗：文字消失、无气泡、无加载条 |
| 7 | IME 发送键在 isLoading 时静默 no-op | 用户感觉「输入框坏了」 |

## 2. 修复后的回合生命周期契约（AgentEngineFlowSafety.kt）

```
beginEngineTurn()            发送瞬间：isLoading = true（不等僵尸收尾）
        │
launchEngineTurn { ... }     统一启动器：try { block() } finally { finalize }
        │                                    │
        │ 任何退出路径（正常/异常/取消） ──────┘
finalizeEngineTurn(turnJob)  守卫式幂等收尾：
  ├─ 守卫：turnJob !== currentJob → 跳过（新回合已接管，不踩新执行态）
  ├─ salvageStreamingOutput()：flush 缓冲 → 残留回复/思考落 isPartial → 清工具运行态
  └─ 复位 isLoading / pendingUserInput / awaitingPlan(Spec)Confirmation / currentStepIndex
```

五条执行路径全部接入（缺一即重新引入漏洞）：

1. `sendMessage` / `submitLoopTurn` → `beginEngineTurn` + `launchEngineTurn { taskController.cancel(); executeNormalMessage() }`
2. `retry` → 同上（补互斥串行化）
3. `resumeTask` / `retryTask` / `resumeCrashedTask` → `beginEngineTurn` + `launchEngineTurn { collectEngineFlowSafely(flow) }`
4. 斜杠单 chip / 多 chip 管线 → `launchEngineTurn { execute 安全求值 + collect }`
5. `restoreChatSession` / `newChat` → 独立协程先 `taskController.cancel()` 串行化，
   再动引擎历史；期间用户发新消息（currentJob 更替）→ 放弃切换（新意图优先）

### 关键不变式

- **收尾不依赖事件到达**：finally 必经，`Complete` 事件分支只负责增量
  （RunSummary / salvage / 确认门收口），不承担「唯一复位点」职责；
- **旧回合收尾不得踩新回合**：所有收尾以 `currentJob === turnJob` 为守卫；
  新回合 `runEngine` 启动段保留同款 salvage（覆盖「旧 finally 被跳过」的
  时序空隙）；
- **salvage 幂等**：`salvageStreamingOutput` 以「槽位非空才落消息」实现，
  abort() 本地补偿与被取消回合的 finally 无论谁先执行都不产生重复消息；
- **Complete 轻重分离**：`isLoading=false` + salvage + 总结卡在挂起前原子
  落定；重指标（historyCount 全量 JSON 解析 / token 估算）与完成通知经
  `completeTurnMetricsAsync` 脱离收集协程后台补（防御式 runCatching）。

## 3. 会话切换的串行化

`restoreChatSession`：并行（僵尸任务收尾 × 历史读盘）→ `await` 串行 →
守卫检查（currentJob 未被替换）→ salvage 在途产物 → flushChatHistoryNow
→ `restoreHistory`（此时引擎循环必已停止，无并发修改）→ 回填 UI。

`newChat`：salvage → flush → resetStreamingState（同步段）→ 协程内
`taskController.cancel()` → 守卫检查 → `clearHistory` → 清空 UI。

## 4. 新增执行路径检查单

后续若新增「驱动引擎跑一轮」的入口（新模式 / 插件触发等），必须：

1. `currentJob?.cancel()` 取消旧收集器；
2. `beginEngineTurn()` 即刻置执行态（若有人机交互间隙）；
3. `currentJob = launchEngineTurn { taskController.cancel(); ... execute ... collect }`
   —— 不得裸 `viewModelScope.launch`（绕过 finally 兜底即回到旧世界）；
4. 引擎历史变更（restore/clear）前必须先 `await taskController.cancel()`。

## 5. 验证

- 门禁：`kotlin_balance.py`（7 文件）/ `check_file_size.sh` / `check_code_quality.sh` / `check_string_mirror.py` 全绿；
- 语法级：kotlinc 2.1 批量编译 agent 包（无 Android SDK 环境下过滤
  classpath 噪音），新增错误模式仅剩 lifecycle/compose 缺失导致的级联；
  `coroutineContext` 成员优先级、`async` import 形态、`return@launchEngineTurn`
  标签、try-finally 组合均以最小复现（真实 kotlinx-coroutines 1.9.0 jar）逐一验证通过；
- 回归面：`AgentChatViewModel`（1159/1200 行）、`AgentChatScreen`（1150/1200）预算内。
