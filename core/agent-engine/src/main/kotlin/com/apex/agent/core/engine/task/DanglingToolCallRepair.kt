package com.apex.agent.core.engine.task

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall

/**
 * T76 — 悬空 toolCall 历史修补（审计 R-5；diag-20261011 全面归一化升级）。
 *
 * **缺陷现状**（本任务之前已存在）：
 * 进程死于工具执行中 → `ConversationMemory` 里最后一条
 * `Assistant.toolCalls=[...]` 之后没有配对的 `ToolResult` → App 重启后
 * 引擎 `load()` 恢复该历史 → 下一次 LLM 请求携带不完整的 tool_calls 序列
 * → OpenAI 兼容 API 校验失败（HTTP 400 "tool_calls must be followed by
 * tool messages"）→ 整个对话历史不可用。
 *
 * **修补规则**（纯函数便于单测）：
 * 1. **位置修复（2026-10-07 诊断包 crash 复盘）**：合成 ToolResult 必须插在
 *    「悬空 Assistant 消息（及其已存在的兄弟 ToolResult 块）**之后**、下一条
 *    非工具消息**之前**」——OpenAI 兼容端点（SiliconFlow 等）要求 tool 消息
 *    *紧随* tool_calls 消息。
 * 2. **分离应答回迁（2026-10-11 诊断包 08:33 会话级报废根因）**：
 *    韧性换路提示 / 思考自检提示等 System 消息曾被注入在 Assistant(tool_calls)
 *    与其 ToolResult 之间（EngineToolExecution 旧顺序），形成
 *    `[Assistant(c1,c2), TR(c1), System, TR(c2)]`——旧实现 KDoc 明示
 *    「隔了消息的 ToolResult 不参与该块配对」且被全局已答集合抑制，零动作；
 *    该形态一旦持久化，**每个后续请求都 400**，新任务迭代 #1 即报
 *    ModelRequestRejected（日志 08:33:38 / 08:33:47）。现在把游离
 *    ToolResult 迁回其所属 Assistant 的紧邻块内（前向/后向游离均可认领）。
 * 3. **孤儿 ToolResult 丢弃**：id 不被任何 Assistant.toolCalls 声明、或
 *    最终无人认领（重复应答）的游离 ToolResult 违反配对协议，直接移除。
 * 4. **重复 id 收敛**：同一条 Assistant 内重复 tool_call_id 去重；被更早
 *    Assistant 认领的重复声明从后续 Assistant 的 toolCalls 中剥离（全剥离
 *    则降级为无 toolCalls 的普通 Assistant）——旧实现留下的
 *    `[A1(c1), TR, A2(c1)]` 尾悬空一并消灭。
 * 5. 合成文本提示模型「结果未知、先验证再重做」。
 *
 * 返回修补报告（合成/迁移/丢弃/剥离各自成列），由调用方决定是否 `save()`
 * 回 ConversationMemory 并记录到 checkpoint。
 */
object DanglingToolCallRepair {

    /** 修补结果：无修补 / 各类修补明细。 */
    data class RepairReport(
        /** 本次合成补发的 ToolResult 对应的 callId 列表（有序）。 */
        val repairedCallIds: List<String>,
        /** 修补后的完整历史（仅当有修补时与输入不同）。 */
        val repairedHistory: List<LlmMessage>,
        /** 从分离位置迁回紧邻块的 ToolResult callId（有序）。 */
        val movedCallIds: List<String> = emptyList(),
        /** 被丢弃的孤儿 / 重复 ToolResult callId。 */
        val droppedToolResultIds: List<String> = emptyList(),
        /** 从重复声明 Assistant 中剥离的 callId。 */
        val strippedCallIds: List<String> = emptyList()
    ) {
        val hasRepairs: Boolean
            get() = repairedCallIds.isNotEmpty() || movedCallIds.isNotEmpty() ||
                droppedToolResultIds.isNotEmpty() || strippedCallIds.isNotEmpty()
    }

    /** 合成 ToolResult 的内容模板（LLM 可读，提示结果未知需验证）。 */
    const val UNKNOWN_RESULT_TEXT: String =
        "⚠ Interrupted: outcome UNKNOWN (process was killed during execution). " +
            "Verify whether the action already took effect before repeating it."

    /** 块内空白 id ToolResult 的报表占位（真实 id 为空，无法直接呈现）。 */
    private const val BLANK_ID_LABEL = "(blank)"

    /** 游离 ToolResult 的重建处置：OUTPUT=块内合法原位产出；MOVE=迁入块；DROP=删除。 */
    private const val OUTPUT = "OUTPUT"
    private const val MOVE = "MOVE"
    private const val DROP = "DROP"

    /** 单个 Assistant 的修补计划（阶段一记录，阶段三照单执行）。 */
    private class AssistantPlan(
        val keptCalls: List<ToolCall>,
        val changed: Boolean,
        val movedTrIdx: LinkedHashMap<String, Int>,
        val synthIds: List<String>
    )

    /**
     * 扫描并归一化历史。**纯函数**：不改入参列表，返回修补后的新列表。
     * 无任何修补时返回输入的等值副本（零行为差异）。
     *
     * 两阶段：阶段一按 Assistant 声明序计算认领计划与每个 ToolResult 的
     * 处置（OUTPUT / MOVE / DROP），阶段三按计划重建——游离 ToolResult
     * 即使出现在其 Assistant 之前（倒置残留）也能被正确认领。
     */
    fun repair(history: List<LlmMessage>): RepairReport {
        // 每个 id 的全部 ToolResult 下标（供迁移候选检索）。
        val trIndex = HashMap<String, MutableList<Int>>()
        for ((idx, msg) in history.withIndex()) {
            if (msg is LlmMessage.ToolResult && msg.toolCallId.isNotBlank()) {
                trIndex.getOrPut(msg.toolCallId) { mutableListOf() }.add(idx)
            }
        }

        val plans = HashMap<Int, AssistantPlan>()
        val disposition = HashMap<Int, String>()
        val synthesizedIds = mutableListOf<String>()
        val movedIds = mutableListOf<String>()
        val droppedIds = mutableListOf<String>()
        val strippedIds = mutableListOf<String>()
        val claimedIds = HashSet<String>()

        // ── 阶段一：按声明序认领（决定 keptCalls / 块产出 / 迁移 / 合成）──
        for ((idx, msg) in history.withIndex()) {
            if (msg !is LlmMessage.Assistant || msg.toolCalls.isEmpty()) continue
            val seen = HashSet<String>()
            val kept = mutableListOf<ToolCall>()
            var changed = false
            msg.toolCalls.forEach { tc ->
                val id = tc.id
                when {
                    id.isBlank() -> kept.add(tc)
                    // seen.add 首次返回 true：!add = 消息内重复声明 → 剥离。
                    !seen.add(id) -> { strippedIds.add(id); changed = true }
                    id in claimedIds -> { strippedIds.add(id); changed = true }
                    else -> kept.add(tc)
                }
            }
            // 紧邻块：首个匹配声明者原位产出（块序保留）；其余判废丢弃。
            val callIds = kept.map { it.id }.filterTo(HashSet()) { it.isNotBlank() }
            val blockAnswered = HashSet<String>()
            var j = idx + 1
            while (j < history.size && history[j] is LlmMessage.ToolResult) {
                val tr = history[j] as LlmMessage.ToolResult
                val tid = tr.toolCallId
                if (disposition[j] == null) {
                    if (tid.isNotBlank() && tid in callIds && tid !in blockAnswered) {
                        disposition[j] = OUTPUT
                        blockAnswered.add(tid)
                        claimedIds.add(tid)
                    } else {
                        disposition[j] = DROP
                        droppedIds.add(if (tid.isBlank()) BLANK_ID_LABEL else tid)
                    }
                }
                j++
            }
            // 块内未答：优先认领游离 ToolResult（未被处置者），无游离才合成。
            val movedTrIdx = LinkedHashMap<String, Int>()
            val synthIds = mutableListOf<String>()
            for (tc in kept) {
                val id = tc.id
                if (id.isBlank() || id in blockAnswered) continue
                val candidate = trIndex[id]?.firstOrNull { tIdx -> disposition[tIdx] == null }
                if (candidate != null) {
                    disposition[candidate] = MOVE
                    movedTrIdx[id] = candidate
                    claimedIds.add(id)
                    movedIds.add(id)
                } else {
                    claimedIds.add(id)
                    synthIds.add(id)
                    synthesizedIds.add(id)
                }
            }
            plans[idx] = AssistantPlan(kept, changed, movedTrIdx, synthIds)
        }

        // ── 阶段二：游离 ToolResult 兜底（未被认领 = 孤儿/重复应答 → 丢弃）──
        for ((idx, msg) in history.withIndex()) {
            if (msg is LlmMessage.ToolResult && disposition[idx] == null) {
                disposition[idx] = DROP
                droppedIds.add(
                    if (msg.toolCallId.isBlank()) BLANK_ID_LABEL else msg.toolCallId
                )
            }
        }

        if (synthesizedIds.isEmpty() && movedIds.isEmpty() &&
            droppedIds.isEmpty() && strippedIds.isEmpty()
        ) {
            return RepairReport(emptyList(), history.toList())
        }

        // ── 阶段三：按计划重建 ──
        val patched = mutableListOf<LlmMessage>()
        var i = 0
        while (i < history.size) {
            val msg = history[i]
            val plan = plans[i]
            if (plan != null) {
                val original = history[i] as LlmMessage.Assistant
                patched.add(if (plan.changed) original.copy(toolCalls = plan.keptCalls) else original)
                var j = i + 1
                while (j < history.size && history[j] is LlmMessage.ToolResult) {
                    if (disposition[j] == OUTPUT) patched.add(history[j])
                    j++
                }
                // 迁移与合成按声明序追加（紧邻块尾、下一条非工具消息之前）。
                plan.movedTrIdx.forEach { (_, tIdx) ->
                    patched.add(history[tIdx])
                }
                plan.synthIds.forEach { id ->
                    patched.add(LlmMessage.ToolResult(id, UNKNOWN_RESULT_TEXT))
                }
                i = j
            } else {
                // 游离 ToolResult（MOVE/DROP）原位跳过；其余消息原样产出。
                if (msg !is LlmMessage.ToolResult) patched.add(msg)
                i++
            }
        }
        return RepairReport(synthesizedIds, patched, movedIds, droppedIds, strippedIds)
    }

    /**
     * 便捷入口：修补并检查"末尾 N 条内是否有悬空"（恢复横幅提示用——
     * 悬空紧邻末尾说明死在工具执行中，而非更早的历史分叉）。
     */
    fun tailHasDangling(history: List<LlmMessage>, tailWindow: Int = 4): Boolean {
        val tail = history.takeLast(tailWindow)
        return repair(tail).hasRepairs
    }
}
