package com.apex.agent.ui.screen.code.fleet

import com.apex.agent.core.code.stream.ToolKind
import com.apex.agent.core.code.subagent.SubAgentObserver
import com.apex.agent.core.code.subagent.SubAgentRunner
import com.apex.agent.core.engine.AgentEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * # Agent Fleet Store — 子 Agent / 终端命令仪表盘归约器（方案 Spec §3.3）
 *
 * 纯 Kotlin 只读观察层（无 IO、无持久化、无 Android 依赖 → JVM 单测友好）：
 * 三条事件通道在 [onEngineEvent] / [onSubAgentStart] 族方法汇入本归约器，
 * 输出当前会话桶的 `StateFlow<FleetDashboardState>`。
 *
 * ## 会话分桶（「每个对话独立」）
 * 单活跃桶（Map 分桶的简化形态）：[newConversation] 在会话边界（新会话 /
 * 恢复历史 / 切工作区 / VM 冷启动）换新 UUID + 弃旧桶 + Tab 复位。**不落盘、
 * 不随 CodeHistoryStore 归档恢复**——恢复历史后仪表盘为空（严格「关闭即清」）。
 *
 * ## 迟到事件丢弃
 * 全部 ingest 口先比对 `conversationId != current` 直接丢弃：运行中切会话时
 * 旧会话在飞事件（子代理 stop / 命令 Complete）不串入新桶——**宁可缺显不串显**。
 * conversationId 不可用时装配层观察者降级为 [UNBOUND_CONVERSATION_ID]
 * （该桶不展示：仪表盘可见 ⇒ VM 必已铸号）。
 *
 * ## 旁路纪律（性能 / 语义红线）
 * - 只认 `ToolCallStart` / `ToolCallComplete`（不认 ToolOutputChunk）——流式期
 *   不会每 chunk 触发归约与 StateFlow 发布；
 * - 子代理内部事件**只进仪表盘内存态**，不进主对话历史 / CodeConversationMemory /
 *   检查点（隔离上下文设计零变化）；
 * - 成败以 `ToolCallComplete.exitCode / success` 为准（引擎已按 exitCode 判定
 *   success），不用输出文本前缀猜测（T92 口径）。
 *
 * ## 上限（对齐 CODE_SNAPSHOT_MAX_MESSAGES 的「宁可丢头不丢尾」）
 * 每桶子 Agent ≤ 64、命令 ≤ 256，超出丢最旧（插入序 LinkedHashMap 首键）。
 *
 * ## 线程契约
 * ingest 可来自 VM 收集协程（主线程）与工具执行线程（SubAgentObserver 回调）
 * ——全部 synchronized 单锁串行；锁内仅 O(1) 桶更新与 StateFlow 发布。
 */
class AgentFleetStore {

    private val lock = Any()

    /** 当前会话桶（切换即弃；UNBOUND = VM 尚未铸号）。 */
    private var bucket = Bucket(UNBOUND_CONVERSATION_ID)

    private val _state = MutableStateFlow(bucket.snapshot())
    val state: StateFlow<FleetDashboardState> = _state.asStateFlow()

    // ── 会话边界 ────────────────────────────────────────────────────

    /** 当前会话 id（VM 与装配层观察者共用的唯一事实源；「会话级 Provider」）。 */
    fun currentConversationId(): String = synchronized(lock) { bucket.conversationId }

    /** 会话边界：铸新 UUID + 弃旧桶 + Tab 复位（浮层可见性属 uiState，不受影响）。 */
    fun newConversation(): String = synchronized(lock) {
        bucket = Bucket(UUID.randomUUID().toString())
        publish()
        bucket.conversationId
    }

    /** Tab 选择（当前会话内持久；随新桶复位为 SUB_AGENT）。 */
    fun selectTab(tab: FleetTab) {
        synchronized(lock) {
            if (bucket.tab != tab) {
                bucket.tab = tab
                publish()
            }
        }
    }

    // ── 通道一：主代理引擎事件（VM collect 内一行旁路）────────────────

    /** 主代理命令旁路（conversationId 取当前桶——主代理事件与 VM 同步，无迟到形态）。 */
    fun onEngineEvent(event: AgentEvent) {
        onCommandEvent(bucket.conversationId, ownerSubAgentId = null, event)
    }

    // ── 通道二/三：子代理实例与内部命令（SubAgentObserver 观察层）──────

    /** 子代理回合开始（conversationId 由观察者在 onStart 时绑定——切会话后 stop 带旧 id 即丢弃）。 */
    fun onSubAgentStart(conversationId: String, runId: String, typeKey: String, description: String) {
        synchronized(lock) {
            if (conversationId != bucket.conversationId) return
            val existing = bucket.subAgents[runId]
            bucket.subAgents[runId] = FleetSubAgentState(
                id = runId,
                typeKey = typeKey,
                description = description.take(DESCRIPTION_MAX_CHARS),
                status = FleetSubAgentStatus.RUNNING,
                iterations = existing?.iterations ?: 0,
                toolCalls = existing?.toolCalls ?: 0,
                startAt = existing?.startAt ?: System.currentTimeMillis(),
                conversationId = conversationId
            )
            capSubAgents()
            publish()
        }
    }

    /** 回合内进度（计数只增不减——观察侧 max 合并）。 */
    fun onSubAgentProgress(conversationId: String, runId: String, iterations: Int, toolCalls: Int) {
        synchronized(lock) {
            if (conversationId != bucket.conversationId) return
            val existing = bucket.subAgents[runId] ?: return
            if (iterations <= existing.iterations && toolCalls <= existing.toolCalls) return
            bucket.subAgents[runId] = existing.copy(
                iterations = maxOf(existing.iterations, iterations),
                toolCalls = maxOf(existing.toolCalls, toolCalls)
            )
            publish()
        }
    }

    /** 子代理内部引擎事件旁路（命令 Tab 数据源；BASH 族过滤在 [onCommandEvent] 内）。 */
    fun onSubAgentToolEvent(conversationId: String, runId: String, event: AgentEvent) {
        onCommandEvent(conversationId, ownerSubAgentId = runId, event)
    }

    /** 子代理回合收官（cause = 终态唯一事实源；result 携带 iterations/toolCalls/durationMs）。 */
    fun onSubAgentStop(
        conversationId: String,
        runId: String,
        cause: SubAgentObserver.StopCause,
        result: SubAgentRunner.SubAgentResult?
    ) {
        synchronized(lock) {
            if (conversationId != bucket.conversationId) return
            val existing = bucket.subAgents[runId] ?: return
            bucket.subAgents[runId] = existing.copy(
                status = when (cause) {
                    SubAgentObserver.StopCause.COMPLETED -> FleetSubAgentStatus.SUCCESS
                    SubAgentObserver.StopCause.TIMED_OUT -> FleetSubAgentStatus.TIMEOUT
                    SubAgentObserver.StopCause.ABORTED -> FleetSubAgentStatus.CANCELLED
                    SubAgentObserver.StopCause.FAILED -> FleetSubAgentStatus.FAILED
                },
                iterations = result?.iterations ?: existing.iterations,
                toolCalls = result?.toolCalls ?: existing.toolCalls,
                durationMs = result?.durationMs ?: existing.durationMs
            )
            publish()
        }
    }

    // ── 中止收口 ────────────────────────────────────────────────────

    /**
     * 用户中止：在飞行收口（中止后引擎不再发 Complete——不收口则永远挂着
     * 「运行中」）。命令 → PARTIAL、子代理 → CANCELLED，与时间轴
     * `abortLocally` 的在飞胶囊折叠语义对齐（同一根因的同一口径）。
     */
    fun cancelRunning() {
        synchronized(lock) {
            var dirty = false
            bucket.subAgents.entries.forEach { entry ->
                if (entry.value.status == FleetSubAgentStatus.RUNNING) {
                    entry.setValue(entry.value.copy(status = FleetSubAgentStatus.CANCELLED))
                    dirty = true
                }
            }
            bucket.commands.entries.forEach { entry ->
                val status = entry.value.status
                if (status == FleetCommandStatus.RUNNING || status == FleetCommandStatus.WAITING) {
                    entry.setValue(entry.value.copy(status = FleetCommandStatus.PARTIAL))
                    dirty = true
                }
            }
            if (dirty) publish()
        }
    }

    // ── 内部：命令事件归约（主代理 / 子代理内部共用）──────────────────

    private fun onCommandEvent(conversationId: String, ownerSubAgentId: String?, event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolCallStart -> {
                if (!isTerminalCommandTool(event.toolName)) return
                synchronized(lock) {
                    if (conversationId != bucket.conversationId) return
                    upsertCommand(
                        FleetCommandState(
                            callId = event.callId,
                            toolName = event.toolName,
                            target = commandTargetOf(event.toolName, event.arguments),
                            status = FleetCommandStatus.RUNNING,
                            ownerSubAgentId = ownerSubAgentId,
                            startAt = System.currentTimeMillis(),
                            conversationId = conversationId
                        )
                    )
                    publish()
                }
            }
            is AgentEvent.ToolCallComplete -> {
                if (!isTerminalCommandTool(event.toolName)) return
                synchronized(lock) {
                    if (conversationId != bucket.conversationId) return
                    val existing = bucket.commands[event.callId]
                    upsertCommand(
                        FleetCommandState(
                            callId = event.callId,
                            toolName = event.toolName,
                            target = existing?.target
                                ?: commandTargetOf(event.toolName, event.arguments),
                            status = commandStatusOf(event.success),
                            exitCode = event.exitCode,
                            sessionId = event.sessionId,
                            channel = event.channel,
                            ownerSubAgentId = ownerSubAgentId ?: existing?.ownerSubAgentId,
                            startAt = existing?.startAt ?: System.currentTimeMillis(),
                            durationMs = event.durationMs,
                            conversationId = conversationId
                        )
                    )
                    publish()
                }
            }
            else -> Unit // 旁路只认 Start / Complete（不认 ToolOutputChunk，防每 chunk 归约）
        }
    }

    /** 幂等合并 + 上限丢最旧（插入序 LinkedHashMap 首键 = 最旧行）。 */
    private fun upsertCommand(command: FleetCommandState) {
        bucket.commands[command.callId] = command
        while (bucket.commands.size > MAX_COMMANDS) {
            bucket.commands.remove(bucket.commands.keys.first())
        }
    }

    private fun capSubAgents() {
        while (bucket.subAgents.size > MAX_SUB_AGENTS) {
            bucket.subAgents.remove(bucket.subAgents.keys.first())
        }
    }

    /** 锁内发布（StateFlow 值语义：桶内容不变 → 等值零发布）。 */
    private fun publish() {
        _state.value = bucket.snapshot()
    }

    // ── 纯函数（单测直测）───────────────────────────────────────────

    /**
     * 终端命令族判定：`ToolKind.fromToolName == BASH`（TerminalExec /
     * ShellExecute / bash / shell / execute）+ TerminalRun（v2 非阻塞命令投递，
     * ToolKind 归一表未收录——Spec §3.2 明示的补集）。
     */
    internal fun isTerminalCommandTool(toolName: String): Boolean {
        if (ToolKind.fromToolName(toolName) == ToolKind.BASH) return true
        val key = toolName.trim().lowercase().filter { it.isLetterOrDigit() }
        return key == "terminalrun"
    }

    /** 成败映射：exitCode / success 为准（0→SUCCESS；127/126/-1 引擎已判 !success→FAILED）。 */
    internal fun commandStatusOf(success: Boolean): FleetCommandStatus =
        if (success) FleetCommandStatus.SUCCESS else FleetCommandStatus.FAILED

    /**
     * 命令摘要（非 stdout）：参数 command 的首行 ≤80 字；非 JSON / 无 command
     * 键 → 工具名兜底（防御式解析，异常折叠）。
     */
    internal fun commandTargetOf(toolName: String, arguments: String): String {
        val command = runCatching {
            (Json.parseToJsonElement(arguments) as? JsonObject)
                ?.get("command")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        return command
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.take(TARGET_MAX_CHARS)
            ?: toolName
    }

    /** 会话桶（插入序保持；不可变快照经 [snapshot] 发布）。 */
    private class Bucket(val conversationId: String) {
        var tab: FleetTab = FleetTab.SUB_AGENT
        val subAgents: LinkedHashMap<String, FleetSubAgentState> = LinkedHashMap()
        val commands: LinkedHashMap<String, FleetCommandState> = LinkedHashMap()
        fun snapshot() = FleetDashboardState(
            conversationId = conversationId,
            tab = tab,
            subAgents = subAgents.values.toList(),
            commands = commands.values.toList()
        )
    }

    private companion object {
        /** VM 尚未铸号（装配层降级桶；仪表盘不可见——不展示）。 */
        const val UNBOUND_CONVERSATION_ID = "unbound"

        /** 每桶行数上限（丢最旧；Spec §3.3）。 */
        const val MAX_SUB_AGENTS = 64
        const val MAX_COMMANDS = 256

        /** 行文案截断口径（Spec §2.4：description ≤32 字 / target ≤80 字）。 */
        const val DESCRIPTION_MAX_CHARS = 32
        const val TARGET_MAX_CHARS = 80
    }
}

/**
 * 装配层观察者（ToolModule 注入 SubAgentRunner 的旁路实现）：
 * 回调落在工具执行线程——转发进 [AgentFleetStore]（锁内 O(1)）。
 *
 * conversationId 在 **onStart 时绑定**（runId → cid），progress / toolEvent /
 * stop 复用绑定值：运行中切会话后这些事件带**旧 id**进 store，被迟到丢弃
 * （Spec §4「旧会话在飞事件带旧 id → 全部丢弃」）。
 */
class FleetSubAgentObserver(
    private val store: AgentFleetStore
) : SubAgentObserver {

    /** runId → onStart 时绑定的会话 id（stop 后清除；规模 ≤ 并发子代理数）。 */
    private val runBindings = ConcurrentHashMap<String, String>()

    override fun onStart(runId: String, typeKey: String, description: String) {
        val cid = store.currentConversationId()
        runBindings[runId] = cid
        store.onSubAgentStart(cid, runId, typeKey, description)
    }

    override fun onProgress(runId: String, iterations: Int, toolCalls: Int) {
        store.onSubAgentProgress(bound(runId), runId, iterations, toolCalls)
    }

    override fun onToolEvent(runId: String, event: AgentEvent) {
        store.onSubAgentToolEvent(bound(runId), runId, event)
    }

    override fun onStop(runId: String, cause: SubAgentObserver.StopCause, result: SubAgentRunner.SubAgentResult?) {
        store.onSubAgentStop(bound(runId), runId, cause, result)
        runBindings.remove(runId)
    }

    private fun bound(runId: String): String =
        runBindings[runId] ?: UNBOUND_DROP
}

/** runId 未绑定（无 onStart 的病态事件）→ 恒不匹配当前桶，按迟到丢弃。 */
private const val UNBOUND_DROP = "unbound"
