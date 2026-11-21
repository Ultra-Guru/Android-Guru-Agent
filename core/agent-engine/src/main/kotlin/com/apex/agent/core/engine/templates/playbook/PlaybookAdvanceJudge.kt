package com.apex.agent.core.engine.templates.playbook

/**
 * 幕推进判定（纯函数，确定性）：
 * - [ADVANCED] 本幕章节切片实质非空壳，**且**回包出现下一幕章节头 → 放行；
 * - [STAYED] 未构成推进形态（缺章节头 / 本幕空洞 / 无下一幕头）→ 原地；
 * - [COMPLETED] 末幕章节实质产出 + 完成标记 → 剧本收尾。
 *
 * 切片归属模型：每个章节头到下一个章节头之间的正文归属该头；回包开头
 * 到首个章节头之间的正文归属「响应开头段」（agent 跳过本幕头直接报下一
 * 幕头时，视为本幕产出的收尾段）。
 *
 * 诚实门哲学（对齐模板运行时 residualViolations）：验收条款在 P0 只做
 * **结构化核验**（本幕切片非空壳），不做逐条款语义校验——达不了结构核验
 * 的产出绝不静默放行；条款级校验是 PR-D 的事，这里不假装。
 */
object PlaybookAdvanceJudge {

    /** 判定结果。 */
    data class AdvanceDecision(
        val kind: AdvanceKind,
        /** 推进后的当前幕下标（STAYED = 原值；ADVANCED/COMPLETED = 新值）。 */
        val nextStageIndex: Int,
        /** 可解释理由（系统消息用）。 */
        val reason: String
    )

    enum class AdvanceKind { ADVANCED, STAYED, COMPLETED }

    /**
     * 章节头解析：与 [PlaybookDirectiveBuilder.STAGE_HEADER_FORMAT] 同款
     * 「【幕k·标题】」形态（容忍全角点/冒号变体）。
     */
    private val STAGE_HEADER = Regex("""【幕(\d{1,2})[·・:：]\s*([^】]{1,40})】""")

    /** 本幕正文的最小非空壳阈值（切片 80 字符以上视为真实产出）。 */
    private const val MIN_BODY_CHARS = 80

    /**
     * 判定回包是否推进幕。
     *
     * @param rawResponse agent 本轮回包全文
     * @param playbook 当前剧本
     * @param currentStageIndex 当前幕 0 基下标
     */
    fun judge(rawResponse: String, playbook: PlaybookDefinition, currentStageIndex: Int): AdvanceDecision {
        val last = playbook.stageCount - 1
        val headers = STAGE_HEADER.findAll(rawResponse).toList()
        val completion = rawResponse.contains(PlaybookDirectiveBuilder.COMPLETION_TOKEN)

        // 本幕切片：本幕章节头 → 下一章节头；无本幕头但下一幕头在最前时，
        // 开头段视为本幕收尾。
        val currentHeader = headers.firstOrNull {
            (it.groupValues[1].toIntOrNull() ?: -1) == currentStageIndex + 1
        }
        val currentBody: String? = when {
            currentHeader != null -> sliceAfter(headers, currentHeader, rawResponse)
            headers.firstOrNull()?.let { (it.groupValues[1].toIntOrNull() ?: -1) == currentStageIndex + 2 } == true ->
                rawResponse.substring(0, headers.first().range.first)
            else -> null
        }
        val substantial = currentBody != null && currentBody.length >= MIN_BODY_CHARS
        val nextHeaderPresent = headers.any {
            (it.groupValues[1].toIntOrNull() ?: -1) == currentStageIndex + 2
        }

        return when {
            // 末幕：实质产出 + 完成标记 → 收尾。
            currentStageIndex == last && substantial && completion ->
                AdvanceDecision(AdvanceKind.COMPLETED, last, "末幕实质产出 + 完成标记")
            // 非末幕：本幕实质 + 下一幕头已出现 → 放行。
            currentStageIndex < last && substantial && nextHeaderPresent -> AdvanceDecision(
                AdvanceKind.ADVANCED, currentStageIndex + 1,
                "幕${currentStageIndex + 1}产出实质（${currentBody!!.length} 字符），推进至幕${currentStageIndex + 2}·${playbook.stageAt(currentStageIndex + 1)?.title ?: ""}"
            )
            // 其余一律原地，理由可解释。
            currentHeader == null && !nextHeaderPresent -> AdvanceDecision(
                AdvanceKind.STAYED, currentStageIndex,
                "未按幕协议输出章节头（期望「${PlaybookDirectiveBuilder.STAGE_HEADER_FORMAT.format(currentStageIndex + 1, playbook.stageAt(currentStageIndex)?.title ?: "")}」）"
            )
            currentHeader != null && !substantial -> AdvanceDecision(
                AdvanceKind.STAYED, currentStageIndex,
                "本幕产出空洞（切片 ${currentBody?.length ?: 0} 字符 < $MIN_BODY_CHARS），不静默放行"
            )
            else -> AdvanceDecision(
                AdvanceKind.STAYED, currentStageIndex,
                "本幕产出已入段但未见下一幕章节头，按仍在本幕处理"
            )
        }
    }

    /** 章节头到下一章节头之间的正文切片（末头取到文尾）。 */
    private fun sliceAfter(
        headers: List<MatchResult>,
        header: MatchResult,
        rawResponse: String
    ): String {
        val start = header.range.last + 1
        val end = headers.firstOrNull { it.range.first > header.range.first }?.range?.first
            ?: rawResponse.length
        return rawResponse.substring(start.coerceAtMost(rawResponse.length), end)
    }
}
