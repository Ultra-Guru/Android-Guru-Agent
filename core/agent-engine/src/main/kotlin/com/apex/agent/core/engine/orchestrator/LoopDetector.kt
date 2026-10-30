package com.apex.agent.core.engine.orchestrator

/**
 * A68.2 — Loop detection.
 *
 * Detects two pathologies in the ReAct tool-call stream:
 *
 * 1. **Repetition**: the identical call (tool name + arguments) is executed
 *    [maxRepetitions] or more times — the LLM is "re-issuing the same
 *    command hoping for a different result".
 *
 * 2. **Periodic pattern (oscillation)**: the recent call sequence repeats
 *    with a small period — e.g. `A,B,A,B,A,B` (period 2) or
 *    `A,B,C,A,B,C` (period 3). Classic when the LLM ping-pongs between a
 *    read and a failed write without making progress.
 *
 * Detection looks at a sliding window of the most recent [windowSize]
 * calls. When a signal fires, the orchestrator (a) injects a recovery
 * prompt via [RecoveryPlanner] and (b) calls [acknowledge] to cool the
 * detector down, so one loop triggers one recovery — not one per call.
 *
 * 幂等只读白名单（对话实录 #11）：AgentProfileList 连调 3 次确认配置状态是
 * 正常工作流，却被误判为死循环触发 RECOVERY NOTICE。白名单工具
 * （[IDEMPOTENT_READ_TOOLS]）用更宽的 [idempotentReadRepetitions] 阈值
 * —— 重试无副作用，宽容确认式重读；真循环（阈值后）仍然拦截。
 *
 * Pure in-memory state, single-threaded use (BUILD loop) — no locking.
 */
class LoopDetector(
    /** Identical calls needed within the window to flag repetition. */
    val maxRepetitions: Int = 3,
    /** How many recent calls to consider. */
    val windowSize: Int = 10,
    /** Max period length checked for oscillation (A-B-A-B is period 2). */
    val maxOscillationPeriod: Int = 3,
    /** Full pattern repetitions needed to flag oscillation. */
    val oscillationRepeats: Int = 2,
    /** 白名单幂等只读工具的重复阈值（实录 #11：确认式重读不算循环）。 */
    val idempotentReadRepetitions: Int = 6
) {
    init {
        require(maxRepetitions >= 2) { "maxRepetitions must be >= 2" }
        require(windowSize >= 2) { "windowSize must be >= 2" }
        require(maxOscillationPeriod >= 1) { "maxOscillationPeriod must be >= 1" }
        require(oscillationRepeats >= 2) { "oscillationRepeats must be >= 2" }
        require(idempotentReadRepetitions >= maxRepetitions) {
            "idempotentReadRepetitions must be >= maxRepetitions"
        }
    }

    /** One recorded tool call, normalized for loop comparison. */
    private data class RecordedCall(val toolName: String, val arguments: String) {
        val signature: String get() = "$toolName:$arguments"
    }

    private val history = ArrayDeque<RecordedCall>()

    /** Record an executed tool call. Call BEFORE [detect]. */
    fun record(toolName: String, arguments: String) {
        history.addLast(RecordedCall(toolName, arguments))
        while (history.size > windowSize) history.removeFirst()
    }

    /**
     * Check the window for a loop signal. Returns the signal or null.
     * Does NOT mutate state — call [acknowledge] when the orchestrator has
     * reacted (injected recovery prompt), otherwise the same loop keeps
     * re-firing on every subsequent call.
     */
    fun detect(): LoopSignal? {
        val window = history.toList()
        if (window.isEmpty()) return null

        // 1. Repetition: most frequent signature count within the window.
        val counts = window.groupingBy { it.signature }.eachCount()
        val (topSignature, topCount) = counts.maxByOrNull { it.value } ?: return null
        val example = window.last { it.signature == topSignature }
        // 实录 #11：幂等只读自省工具（AgentProfileList 等）的确认式重读
        // 用宽阈值 —— 重试无副作用，3 次不算循环，6 次才是真异常。
        val threshold = if (example.toolName in IDEMPOTENT_READ_TOOLS) {
            idempotentReadRepetitions
        } else {
            maxRepetitions
        }
        if (topCount >= threshold) {
            return LoopSignal.Repetition(
                toolName = example.toolName,
                arguments = example.arguments,
                repetitions = topCount
            )
        }

        // 2. Oscillation: does the tail of the window repeat with period p?
        //    Period starts at 2 — period 1 IS repetition (handled above with
        //    its own threshold); allowing it here would flag any 2 identical
        //    calls as an "oscillation" false positive.
        for (period in 2..maxOscillationPeriod.coerceAtMost(window.size / oscillationRepeats)) {
            val needed = period * oscillationRepeats
            if (window.size < needed) continue
            val tail = window.takeLast(needed)
            val pattern = tail.take(period)
            // 全同模式（pattern 只有一种签名）是重复不是振荡 —— 交给（可能已
            // 按白名单放宽的）重复阈值判定。否则幂等工具 4+ 次确认式重读会在
            // period-2 振荡分支被误报，白名单的宽容形同虚设（实录 #11 补丁）。
            if (pattern.map { it.signature }.distinct().size == 1) continue
            var repeats = true
            for (i in tail.indices) {
                if (tail[i].signature != pattern[i % period].signature) {
                    repeats = false
                    break
                }
            }
            if (repeats && period < needed) { // period == needed means all identical — covered by repetition
                return LoopSignal.Oscillation(
                    pattern = pattern.map { it.toolName },
                    period = period,
                    repeats = oscillationRepeats
                )
            }
        }
        return null
    }

    /**
     * Cool-down after the orchestrator reacted to a signal: forget the
     * recorded history so the same pattern doesn't immediately re-fire.
     * The LLM gets a fresh window to demonstrate it changed strategy.
     */
    fun acknowledge() {
        history.clear()
    }

    /** Reset for a new task. */
    fun reset() {
        history.clear()
    }

    /** Number of calls currently in the window (for tests). */
    val windowCount: Int get() = history.size

    companion object {
        /**
         * 幂等只读自省工具白名单（实录 #11）：无副作用、高频用于“确认状态”
         *（AgentProfileList 连调 3 次看 isDefault 是正常工作流）。
         * 只放宽重复阈值；振荡检测（A-B-A-B 乒乓）不豁免 —— 那仍是病态。
         */
        val IDEMPOTENT_READ_TOOLS: Set<String> = setOf(
            "AgentProfileList", "AgentSettingGet", "AgentRoleList",
            "ToolList", "CapabilityReport", "TerminalBackends", "TerminalSnapshot",
            "McpList", "SkillList", "ConnectorList", "GetDeviceInfo", "Time"
        )
    }
}

/** What the detector found. */
sealed interface LoopSignal {
    /** The same call was repeated [repetitions] times. */
    data class Repetition(
        val toolName: String,
        val arguments: String,
        val repetitions: Int
    ) : LoopSignal

    /** The call sequence repeats with a small [period] (A-B-A-B …). */
    data class Oscillation(
        val pattern: List<String>,
        val period: Int,
        val repeats: Int
    ) : LoopSignal
}
