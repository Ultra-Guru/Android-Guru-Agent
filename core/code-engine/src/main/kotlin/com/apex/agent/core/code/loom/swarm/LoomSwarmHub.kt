package com.apex.agent.core.code.loom.swarm

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomThreadPhase
import com.apex.agent.core.code.loom.LoomThreadSnapshot
import com.apex.agent.core.code.loom.loop.LoomAbortSignal
import com.apex.agent.core.code.loom.loop.LoomApprovalBox
import com.apex.agent.core.code.loom.loop.LoomEnvironmentSource
import com.apex.agent.core.code.loom.loop.LoomFailureClassifier
import com.apex.agent.core.code.loom.loop.LoomHeuristicEstimator
import com.apex.agent.core.code.loom.loop.LoomLogger
import com.apex.agent.core.code.loom.loop.LoomModelClient
import com.apex.agent.core.code.loom.loop.LoomPlanGate
import com.apex.agent.core.code.loom.loop.LoomRoundRunner
import com.apex.agent.core.code.loom.loop.LoomSession
import com.apex.agent.core.code.loom.loop.LoomSteering
import com.apex.agent.core.code.loom.loop.LoomToolRunner
import com.apex.agent.core.code.loom.loop.LoomToolSource
import com.apex.agent.core.code.loom.markup.LoomOutcomeFormatter
import com.apex.agent.core.code.loom.memory.LoomMemoryWeaver
import com.apex.agent.core.code.loom.permission.LoomPermissionGovernor
import com.apex.agent.core.code.loom.permission.LoomPermissionSource
import com.apex.agent.core.code.loom.thinking.LoomThinkingPolicy
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.UserInput
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Loom Swarm Hub — 子线程群（织造线的 agent-as-tools 委派）
 *
 * 主模型可经 `loom_thread_*` 四工具把**可隔离的子目标**交给子线程：
 * 每条子线程 = 独立会话 + 独立梭回合机（共享模型客户端 / 工具执行 /
 * 权限源，但**无交互窗口**——Ask 自动拒绝、不递归开子线程）。
 *
 * ## 生命周期
 *
 * ```
 * spawn ──► IDLE ──send──► WEAVING ──► SETTLED（可再 send）
 *              │              │
 *              └── 超时/异常 ──► FAILED / ABORTED
 * ```
 *
 * ## 预算三闸
 *
 * - 并发上限（[LoomEngineConfig.threadConcurrencyMax]——超出报忙）；
 * - 回合上限（threadRoundCap → 子配置 shuttleRoundsMax）；
 * - 壁钟超时（[LoomEngineConfig.threadTimeoutMs]——超时按 FAILED 收场，
 *   附截断的已织内容）。
 *
 * ## 事件回灌
 *
 * 子线程不向外发射事件（独立上下文）；主线程以 `loom_thread_send /
 * observe` 的**文本回执**看到子线程产出——隔离是特性，不是缺陷。
 */
class LoomSwarmHub(
    private val config: LoomEngineConfig,
    private val modelClient: LoomModelClient,
    private val parentToolSource: LoomToolSource,
    private val toolRunner: LoomToolRunner,
    private val permissionSource: LoomPermissionSource,
    private val failureClassifier: LoomFailureClassifier,
    private val logger: LoomLogger,
    private val environmentSource: LoomEnvironmentSource,
    private val workspaceRootProvider: () -> File?,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 一条子线程的全部状态。 */
    private class ThreadState(
        val id: String,
        val label: String,
        val session: LoomSession,
        val runner: LoomRoundRunner,
        val abortSignal: LoomAbortSignal,
        @Volatile var phase: LoomThreadPhase = LoomThreadPhase.IDLE,
        @Volatile var rounds: Int = 0,
        @Volatile var lastReply: String = "",
        @Volatile var failure: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val threads = LinkedHashMap<String, ThreadState>()
    private val seq = AtomicInteger(0)
    private val lock = Any()

    // ═══════════════════════════ 起飞 / 推进 / 观测 ═══════════════════════════

    /**
     * 起飞一条子线程（不执行——等第一条 send 才开工）。
     *
     * @return 线程 id（失败 抛 [SwarmBusyException] / 参数异常）。
     */
    fun spawn(label: String): String {
        synchronized(lock) {
            if (activeCount() >= config.threadConcurrencyMax) {
                throw SwarmBusyException("子线程并发已达上限（${config.threadConcurrencyMax}）——先收一条再开")
            }
            val id = "t" + seq.incrementAndGet()
            val childConfig = LoomEngineConfig.forThread().copy(
                contextTag = config.threadContextTag,
                digestContextTag = config.threadContextTag + "_digest",
                shuttleRoundsMax = config.threadRoundCap,
                threadOutputCapChars = config.threadOutputCapChars
            )
            val session = LoomSession(
                config = childConfig,
                memory = null, // 子线程纯内存会话——不落盘
                estimator = LoomHeuristicEstimator,
                environmentSource = environmentSource,
                workspaceContextSource = null,
                modelInfoSource = null,
                logger = logger
            )
            session.bindWorkspace("swarm-$id", label, workspaceRootProvider(), null)
            val governor = LoomPermissionGovernor(
                permissionSource = permissionSource,
                askPolicy = LoomPermissionGovernor.AskPolicy.AUTO_DENY
            )
            val weaver = LoomMemoryWeaver(childConfig, modelClient, LoomHeuristicEstimator, logger)
            val abortSignal = LoomAbortSignal()
            val runner = LoomRoundRunner(
                config = childConfig,
                session = session,
                modelClient = modelClient,
                toolSource = object : LoomToolSource {
                    override fun definitions() = childToolDefinitions()
                },
                toolRunner = toolRunner,
                governor = governor,
                weaver = weaver,
                steering = LoomSteering(childConfig),
                approvalBox = LoomApprovalBox(),
                planGate = LoomPlanGate(),
                formatter = LoomOutcomeFormatter(childConfig),
                thinkingPolicy = LoomThinkingPolicy(),
                failureClassifier = failureClassifier,
                logger = logger,
                abortSignal = abortSignal,
                modelInfoSource = null,
                clock = clock
            )
            threads[id] = ThreadState(id, label, session, runner, abortSignal)
            logger.info(TAG, "thread $id spawned: $label")
            return id
        }
    }

    /**
     * 推进一条子线程：注入消息并跑到本飞收口。
     *
     * @return 最后一条正式回复（预算内裁剪）。
     */
    suspend fun send(threadId: String, message: String): String {
        val state = synchronized(lock) { threads[threadId] }
            ?: throw SwarmNoSuchThreadException("子线程不存在：$threadId")
        if (state.phase == LoomThreadPhase.WEAVING) {
            throw SwarmBusyException("子线程 $threadId 正在织造——用 status 轮询")
        }
        state.phase = LoomThreadPhase.WEAVING
        state.failure = null
        val reply = CompletableDeferred<String>()
        scope.launch {
            try {
                val events = mutableListOf<AgentEvent>()
                val completed = withTimeoutOrNull(config.threadTimeoutMs) {
                    state.runner.run(UserInput.text(message)).toList(events)
                }
                state.rounds += events.lastOrNull { it is AgentEvent.Complete }
                    ?.let { (it as AgentEvent.Complete).totalIterations } ?: 0
                val last = events.lastOrNull { it is AgentEvent.ResponseComplete }
                    ?.let { (it as AgentEvent.ResponseComplete).fullText } ?: ""
                state.lastReply = last
                val errorEvent = events.lastOrNull { it is AgentEvent.Error } as? AgentEvent.Error
                when {
                    completed == null -> {
                        // 壁钟超时：FAILED 收场（附已织内容）
                        state.phase = LoomThreadPhase.FAILED
                        state.failure = "子线程超时（${config.threadTimeoutMs / 1000} 秒）"
                        reply.complete(state.failure + "\n已织内容：\n" + clip(state.lastReply))
                    }
                    errorEvent != null -> {
                        // 回合机内部收口的失败（Error + Complete 正常收场）
                        state.phase = LoomThreadPhase.FAILED
                        state.failure = errorEvent.message
                        reply.complete("子线程失败：" + errorEvent.message)
                    }
                    else -> {
                        state.phase = LoomThreadPhase.SETTLED
                        reply.complete(clip(last))
                    }
                }
            } catch (t: Throwable) {
                state.phase = LoomThreadPhase.FAILED
                state.failure = t.message ?: t::class.simpleName ?: "子线程异常"
                logger.warn(TAG, "thread $threadId faulted: ${state.failure}")
                reply.complete("子线程失败：" + state.failure)
            }
        }
        return reply.await()
    }

    /** 快照（单条 / 全部）。 */
    fun status(threadId: String?): List<LoomThreadSnapshot> = synchronized(lock) {
        val states = if (threadId == null) threads.values.toList() else listOfNotNull(threads[threadId])
        states.map { it.toSnapshot() }
    }

    /**
     * 观测：不推进，取子线程会话的近期摘录（用户轮/助手轮按序裁剪）。
     */
    fun observe(threadId: String): String {
        val state = synchronized(lock) { threads[threadId] }
            ?: throw SwarmNoSuchThreadException("子线程不存在：$threadId")
        val history = state.session.historySnapshot()
        if (history.isEmpty()) return "（子线程 ${state.label} 尚无织造记录）"
        val lines = history.takeLast(OBSERVE_TURNS).joinToString("\n") { m ->
            val role = when (m) {
                is com.apex.agent.core.llm.LlmMessage.User -> "用户"
                is com.apex.agent.core.llm.LlmMessage.Assistant -> "助手"
                is com.apex.agent.core.llm.LlmMessage.System -> "系统"
                is com.apex.agent.core.llm.LlmMessage.ToolResult -> "工具"
            }
            val content = m.let { c ->
                when (c) {
                    is com.apex.agent.core.llm.LlmMessage.User -> c.content
                    is com.apex.agent.core.llm.LlmMessage.Assistant -> c.content
                    is com.apex.agent.core.llm.LlmMessage.System -> c.content
                    is com.apex.agent.core.llm.LlmMessage.ToolResult -> c.content
                }
            }
            "[$role] " + content.replace('\n', ' ').take(OBSERVE_TURN_CAP)
        }
        return lines
    }

    /** 停一条子线程（中止在飞 + 退役）。 */
    fun stop(threadId: String): Boolean = synchronized(lock) {
        val state = threads[threadId] ?: return false
        state.abortSignal.request()
        state.phase = LoomThreadPhase.ABORTED
        threads.remove(threadId)
        true
    }

    /** 全部退役（引擎 clearConversation 时调用）。 */
    fun shutdownAll() {
        synchronized(lock) {
            for (state in threads.values) {
                state.abortSignal.request()
                state.phase = LoomThreadPhase.ABORTED
            }
            threads.clear()
        }
    }

    /** 关停 hub 作用域（引擎释放）。 */
    fun dispose() {
        shutdownAll()
        scope.cancel()
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    private fun activeCount(): Int =
        threads.values.count { it.phase == LoomThreadPhase.WEAVING || it.phase == LoomThreadPhase.IDLE }

    private fun ThreadState.toSnapshot() = LoomThreadSnapshot(
        threadId = id,
        label = label,
        phase = phase,
        rounds = rounds,
        lastReply = clip(lastReply),
        failure = failure
    )

    private fun clip(text: String): String =
        if (text.length <= config.threadOutputCapChars) text
        else text.take(config.threadOutputCapChars) + "…（子线程回执超预算截断）"

    /** 子线程工具面：宿主目录减去 loom_* 本域工具（防递归开线程）。 */
    private fun childToolDefinitions() =
        parentToolSource.definitions().filter { !it.name.startsWith("loom_") }

    /** 并发上限探测（spawn 前可查询）。 */
    fun remainingSlots(): Int = synchronized(lock) {
        (config.threadConcurrencyMax - activeCount()).coerceAtLeast(0)
    }

    class SwarmBusyException(message: String) : Exception(message)
    class SwarmNoSuchThreadException(message: String) : Exception(message)

    companion object {
        private const val TAG = "LoomSwarmHub"
        private const val OBSERVE_TURNS = 8
        private const val OBSERVE_TURN_CAP = 300
    }
}
