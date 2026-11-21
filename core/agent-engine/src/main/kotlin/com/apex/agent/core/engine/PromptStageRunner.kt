package com.apex.agent.core.engine

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.prompt.PromptContext
import com.apex.agent.core.tools.prompt.PromptSnapshot
import com.apex.agent.core.tools.prompt.PromptStage
import com.apex.agent.core.tools.prompt.PromptStageHook
import com.apex.agent.core.tools.prompt.PromptStageResult
import com.apex.agent.core.tools.prompt.PromptTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * # Prompt Stage Runner — 段级钩子派发 + 快照存储（P97）
 *
 * 引擎每次组装 system prompt 时调用 [run]：
 * 1. **零侵入解析**（[PromptSnapshotBuilder.parseSections]）——无钩子注册时
 *    返回原文**逐字节一致**（P97 字节不变约束：本类只读不写渲染串）；
 * 2. **快照落账**：`PromptSnapshot`（段/回合/工具/行标识）进环形存储
 *    （最近 [SNAPSHOT_CAPACITY] 次组装）——`PromptDump` / `PromptSectionList`
 *    工具与诊断页消费；
 * 3. **钩子派发**（注册 [PromptStageHook] 才发生）：BEFORE_COMPOSE →
 *    SECTION_COMPOSE → AFTER_COMPOSE → BEFORE_SEND（同一快照两侧观测口）；
 *    [dispatchAfterSend] fire-and-forget（SupervisorJob + Default 调度，
 *    不阻塞回包）。
 *
 * 预算与降级（规格 01 §4）：单轮改写 ≤[REPLACE_BUDGET] 段、追加
 * ≤[APPEND_BUDGET_BYTES] 字节、DropTurns 累计 ≤[DROP_BUDGET] 条；超限按
 * Pass 处理并留痕；钩子异常捕获 → Pass + 日志，**绝不向上抛**——一轮请求
 * 不因插件失败而毁掉。BLOCK 语义不存在（阻断归 RiskAwareToolGate）。
 *
 * 可见性：public（app 层 DI 挂载需要）；[run]/[lastDroppedTurnIndices]/
 * [dispatchAfterSend] 保持 internal（引擎模块内消费）。
 *
 * 线程模型：[run] 在引擎组装路径（suspend，收集者上下文）；快照存储
 * synchronized 防御（工具读侧在其他调度器）。
 */
class PromptStageRunner {

    @Volatile
    private var hooks: List<PromptStageHook> = emptyList()

    /** 上一次 run 的 DropTurns 结果（引擎 buildMessages 过滤消费；每次 run 复位）。 */
    var lastDroppedTurnIndices: Set<Int> = emptySet()
        private set

    private val lock = Any()
    private val snapshots = ArrayDeque<PromptSnapshot>(SNAPSHOT_CAPACITY + 1)

    /** 注册段级钩子（同 id 覆盖；快照观测不依赖钩子注册）。 */
    fun register(hook: PromptStageHook) {
        hooks = (hooks.filterNot { it.id == hook.id } + hook).sortedBy { it.id }
    }

    /**
     * 组装管道：解析段 → 派发钩子（预算内变异）→ 落快照 → 返回最终提示词。
     * 无钩子时 = 原文直通（逐字节一致，零开销路径只有解析 + 记账）。
     */
    suspend fun run(
        rawPrompt: String,
        turns: List<PromptTurn>,
        toolIds: List<String>,
        line: String,
        chatId: String? = null
    ): String {
        lastDroppedTurnIndices = emptySet()
        if (hooks.isEmpty()) {
            record(PromptSnapshotBuilder.build(rawPrompt, turns, toolIds, line))
            return rawPrompt
        }

        var sections = PromptSnapshotBuilder.parseSections(rawPrompt)
        var finalPrompt = rawPrompt
        var effectiveTurns = turns
        var replaced = 0
        var appendedBytes = 0
        var dropped = 0

        suspend fun dispatch(stage: PromptStage): List<PromptStageResult> {
            val ctx = PromptContext(
                stage = stage,
                chatId = chatId,
                sections = sections,
                turns = effectiveTurns
            )
            return hooks.mapNotNull { hook ->
                runCatching { hook.onStage(stage, ctx) }.getOrElse { e ->
                    AppLogger.instance.warn(
                        LogCategory.LLM, "PromptStageRunner",
                        "prompt hook '${hook.id}' threw at $stage (treated as Pass): ${e.message}"
                    )
                    PromptStageResult.Pass
                }
            }
        }

        // 阶段序派发；变异在预算内才生效（超限 Pass + 留痕）。
        dispatch(PromptStage.BEFORE_COMPOSE)
        dispatch(PromptStage.SECTION_COMPOSE)
        dispatch(PromptStage.AFTER_COMPOSE)
        dispatch(PromptStage.BEFORE_SEND).forEach { result ->
            when (result) {
                is PromptStageResult.Pass -> Unit
                is PromptStageResult.ReplaceSection -> {
                    if (replaced >= REPLACE_BUDGET) {
                        logBudget("ReplaceSection", "#$replaced")
                    } else {
                        replaced++
                        sections = sections.map { s ->
                            if (s.id == result.id) s.copy(
                                text = result.text + '\n',
                                tokenEstimate = result.text.length / 4
                            ) else s
                        }
                        finalPrompt = applyReplace(finalPrompt, result.id, result.text)
                    }
                }
                is PromptStageResult.AppendSection -> {
                    if (appendedBytes + result.text.length > APPEND_BUDGET_BYTES) {
                        logBudget("AppendSection", "${appendedBytes}B")
                    } else {
                        appendedBytes += result.text.length
                        sections = sections.map { s ->
                            if (s.id == result.id) s.copy(
                                text = s.text + result.text + '\n',
                                tokenEstimate = (s.text.length + result.text.length) / 4
                            ) else s
                        }
                        finalPrompt = applyAppend(finalPrompt, result.id, result.text)
                    }
                }
                is PromptStageResult.DropTurns -> {
                    val eligible = result.indices.filter { idx ->
                        idx in effectiveTurns.indices &&
                            // SYSTEM 永不丢弃（压缩保护，规格 01 §4）
                            effectiveTurns[idx].kind != "SYSTEM"
                    }
                    if (dropped + eligible.size > DROP_BUDGET) {
                        logBudget("DropTurns", "#$dropped")
                    } else {
                        dropped += eligible.size
                        effectiveTurns = effectiveTurns.filterIndexed { idx, _ -> idx !in eligible }
                        lastDroppedTurnIndices = lastDroppedTurnIndices + eligible
                    }
                }
            }
        }

        record(
            PromptSnapshot(
                systemPrompt = finalPrompt,
                sections = sections,
                turns = effectiveTurns,
                toolIds = toolIds,
                tokenEstimate = (finalPrompt.length + effectiveTurns.sumOf { it.content.length }) / 4,
                line = line,
                redactions = emptyList()
            )
        )
        return finalPrompt
    }

    /** AFTER_SEND 派发（fire-and-forget；无钩子零开销）。 */
    fun dispatchAfterSend(chatId: String? = null) {
        if (hooks.isEmpty()) return
        fireAndForgetScope.launch {
            runCatching {
                val last = synchronized(lock) { snapshots.lastOrNull() } ?: return@launch
                hooks.forEach { hook ->
                    runCatching {
                        hook.onStage(
                            PromptStage.AFTER_SEND,
                            PromptContext(
                                stage = PromptStage.AFTER_SEND,
                                chatId = chatId,
                                sections = last.sections,
                                turns = last.turns
                            )
                        )
                    }.onFailure { e ->
                        AppLogger.instance.warn(
                            LogCategory.LLM, "PromptStageRunner",
                            "prompt hook '${hook.id}' threw at AFTER_SEND (ignored): ${e.message}"
                        )
                    }
                }
            }
        }
    }

    /** 最近组装快照（新→旧；空 = 本进程尚无组装；app 层 PromptDump 工具消费）。 */
    fun recentSnapshots(): List<PromptSnapshot> = synchronized(lock) {
        snapshots.toList().asReversed()
    }

    /** 最近一次快照（PromptDump scope=current/last 通道）。 */
    fun lastSnapshot(): PromptSnapshot? = synchronized(lock) { snapshots.lastOrNull() }

    private fun record(snapshot: PromptSnapshot) {
        synchronized(lock) {
            if (snapshots.size >= SNAPSHOT_CAPACITY) snapshots.removeFirst()
            snapshots.addLast(snapshot)
        }
    }

    // ── 变异应用到渲染串（段边界手术：目标段之外的文本逐字节保守）──

    private fun applyReplace(prompt: String, sectionId: String, text: String): String {
        val headStart = findHeadingIndex(prompt, sectionId) ?: return prompt
        val headEnd = prompt.indexOf('\n', headStart) + 1 // 标题行保留
        val nextHeading = findHeadingIndexAfter(prompt, headEnd) ?: prompt.length
        if (headEnd > nextHeading) return prompt
        return prompt.substring(0, headEnd) + text + "\n\n" + prompt.substring(nextHeading)
    }

    private fun applyAppend(prompt: String, sectionId: String, text: String): String {
        val headStart = findHeadingIndex(prompt, sectionId) ?: return prompt
        val nextHeading = findHeadingIndexAfter(prompt, headStart + 1) ?: prompt.length
        return prompt.substring(0, nextHeading) + text + "\n\n" + prompt.substring(nextHeading)
    }

    /** 段标题行起始索引（行首 `## ` + 段 id 匹配；不匹配 → null）。 */
    private fun findHeadingIndex(prompt: String, sectionId: String): Int? {
        var searchFrom = 0
        while (searchFrom <= prompt.length) {
            // 行首标题判定：位置 0 或紧跟 '\n' 之后
            val lineStart = when {
                searchFrom == 0 && prompt.startsWith("## ") -> 0
                else -> {
                    val idx = prompt.indexOf("\n## ", searchFrom)
                    if (idx < 0) return null
                    idx + 1
                }
            }
            val lineEnd = prompt.indexOf('\n', lineStart).let { if (it < 0) prompt.length else it }
            val heading = prompt.substring(lineStart, lineEnd)
            if (PromptSnapshotBuilder.sectionIdOf(heading) == sectionId) return lineStart
            if (lineEnd >= prompt.length) return null
            searchFrom = lineEnd + 1
        }
        return null
    }

    private fun findHeadingIndexAfter(prompt: String, from: Int): Int? {
        val idx = prompt.indexOf("\n## ", from)
        return if (idx < 0) null else idx + 1
    }

    private fun logBudget(kind: String, detail: String) {
        AppLogger.instance.warn(
            LogCategory.LLM, "PromptStageRunner",
            "prompt hook budget exceeded: $kind $detail → treated as Pass"
        )
    }

    private companion object {
        const val SNAPSHOT_CAPACITY = 8
        const val REPLACE_BUDGET = 2
        const val APPEND_BUDGET_BYTES = 4096
        const val DROP_BUDGET = 8

        val fireAndForgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
