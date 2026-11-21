package com.apex.agent.ui.screen.code.fleet

/**
 * # Agent Fleet Models — 子 Agent / 终端命令轻量仪表盘数据契约（方案 Spec §3.1）
 *
 * 只读观察层的数据面：Coding 工位工具栏行右端小按钮弹出的浮层，集中展示
 * **当前对话**的子 Agent 列表与其终端命令执行状态。全部字段只含状态与
 * 摘要——**不含输出正文**（stdout 全文不进本模型，P0 能力 = 状态显示 only）。
 *
 * 幂等键纪律：
 * - [FleetCommandState.callId] = 引擎 `AgentEvent.callId`（与胶囊时间轴同源）；
 * - [FleetSubAgentState.id] = `CodeTask-<typeKey>-<desc32>`（与 SubagentStop
 *   钩子的 subagentId 同构）。
 * 迟到 / 重复事件按键合并，不追加重复行。
 */
enum class FleetTab { SUB_AGENT, COMMAND }

/**
 * 子代理生命周期状态（观察通道映射 [com.apex.agent.core.code.subagent.SubAgentObserver.StopCause]）：
 * QUEUED（预留——准入排队期）/ RUNNING / SUCCESS / FAILED / TIMEOUT / CANCELLED。
 */
enum class FleetSubAgentStatus { QUEUED, RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED }

/**
 * 终端命令状态（对齐胶囊时间轴 [com.apex.agent.core.code.stream.ToolCallStatus]）：
 * WAITING / RUNNING / SUCCESS / FAILED / PARTIAL（PARTIAL = 中止时无结局的
 * 在飞调用按中断折叠——与时间轴 abortLocally 同语义）。
 */
enum class FleetCommandStatus { WAITING, RUNNING, SUCCESS, FAILED, PARTIAL }

/**
 * 子代理行（每行 ≤2 行文字 + 状态点）。
 *
 * @param id 稳定幂等键：`CodeTask-<typeKey>-<desc32>`（与 SubagentStop 钩子同构）。
 * @param typeKey 类型 key（explore / research / general / reviewer / custom:xxx）。
 * @param description 一句话任务描述（≤32 字，对齐 subagentId 截断口径）。
 * @param status 生命周期状态（Start→RUNNING，Stop→终态）。
 * @param iterations / toolCalls 回合内计数（观察通道实时推进，终态取 SubAgentResult）。
 * @param startAt 开跑时刻（System.currentTimeMillis）。
 * @param durationMs 端到端耗时（终态来自 SubAgentResult；运行中为 0，UI 按至今推算）。
 * @param conversationId 归属会话（仪表盘按会话分桶，切换即弃）。
 */
data class FleetSubAgentState(
    val id: String,
    val typeKey: String,
    val description: String,
    val status: FleetSubAgentStatus,
    val iterations: Int = 0,
    val toolCalls: Int = 0,
    val startAt: Long,
    val durationMs: Long = 0,
    val conversationId: String
)

/**
 * 终端命令行。
 *
 * @param callId 幂等键 = AgentEvent.callId（主代理 / 子代理内部命令同源）。
 * @param toolName 工具名（TerminalExec / ShellExecute / TerminalRun / bash…）。
 * @param target 命令摘要（参数 command 首行 ≤80 字；**非 stdout**）。
 * @param status WAITING→RUNNING→终态（成败以 exitCode / success 为准，勿用文本猜测）。
 * @param exitCode 真实进程退出码；null = 非进程类 / 未知。
 * @param sessionId / channel 会话标注与执行通道（proot-ubuntu / root-su / shizuku / local-sh；可空）。
 * @param ownerSubAgentId 归属子代理（null = 主代理命令，UI 显示「主代理」）。
 * @param startAt / durationMs 起止与耗时（Complete 携带 durationMs 权威值）。
 * @param conversationId 归属会话。
 */
data class FleetCommandState(
    val callId: String,
    val toolName: String,
    val target: String,
    val status: FleetCommandStatus,
    val exitCode: Int? = null,
    val sessionId: String? = null,
    val channel: String? = null,
    val ownerSubAgentId: String? = null,
    val startAt: Long,
    val durationMs: Long = 0,
    val conversationId: String
)

/**
 * 仪表盘快照（当前会话桶的不可变值；StateFlow 值语义发布，桶内容不变零重组）。
 *
 * @param conversationId 当前会话（VM 会话边界换号；迟到事件 id 不符即丢弃）。
 * @param tab 双 Tab 选择（会话内持久；新会话随桶复位为 SUB_AGENT）。
 * @param subAgents / commands 只读状态列表（插入序；超上限丢最旧）。
 */
data class FleetDashboardState(
    val conversationId: String,
    val tab: FleetTab = FleetTab.SUB_AGENT,
    val subAgents: List<FleetSubAgentState> = emptyList(),
    val commands: List<FleetCommandState> = emptyList()
)
