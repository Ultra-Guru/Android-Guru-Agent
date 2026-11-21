package com.apex.agent.core.engine.templates.playbook

/**
 * ═══ 剧本指令构建器（纯函数）═══
 *
 * (剧本定义, 当前幕, 已完成幕, 侦察附录) → 会话上下文附加段。产出由宿主
 * 并进 sessionExtras 管道（四条引擎线统一生效），是 agent 侧感知剧本的
 * 唯一指令面。
 *
 * Token 纪律：
 * - SEQUENCED 只注入当前幕子提示词（渐进披露），幕历史压成一行状态；
 * - 侦察附录行数截断（侦察是增益不是负担）；
 * - 超预算时从附录 → 验收 → 子提示词尾部依次收缩（主提示词与幕头永不截断）。
 */
object PlaybookDirectiveBuilder {

    /** 指令总字符预算（超出按优先级收缩）。 */
    const val MAX_DIRECTIVE_CHARS = 2400

    /** 侦察附录行数上限。 */
    const val MAX_RECON_LINES = 10

    /** 幕完成标记（master 协议与判定器共用措辞）。 */
    const val COMPLETION_TOKEN = "《剧本完成》"

    /** 幕章节头形态（判定器解析同款）：【幕2·根因假设排序】。 */
    const val STAGE_HEADER_FORMAT = "【幕%d·%s】"

    /**
     * 构建指令段。
     *
     * @param playbook 已挂载剧本
     * @param stageIndex 当前幕 0 基下标（COMPOSED 模式忽略，全幕并置）
     * @param completedStageIndexes 已完成幕下标（升序）
     * @param reconLines 侦察附录行（可为空）
     */
    fun build(
        playbook: PlaybookDefinition,
        stageIndex: Int,
        completedStageIndexes: List<Int> = emptyList(),
        reconLines: List<String> = emptyList()
    ): String = when (playbook.mode) {
        PlaybookExecutionMode.COMPOSED -> buildComposed(playbook)
        PlaybookExecutionMode.SEQUENCED -> buildSequenced(playbook, stageIndex, completedStageIndexes, reconLines)
    }

    // ═══ SEQUENCED ═══

    private fun buildSequenced(
        playbook: PlaybookDefinition,
        stageIndex: Int,
        completedStageIndexes: List<Int>,
        reconLines: List<String>
    ): String {
        val stage = playbook.stageAt(stageIndex) ?: playbook.stages.first()
        val effectiveIndex = playbook.stages.indexOf(stage)
        val header = STAGE_HEADER_FORMAT.format(effectiveIndex + 1, stage.title)
        val progress = progressLine(playbook, effectiveIndex, completedStageIndexes)
        val acceptance = stage.acceptance.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "；", prefix = "本幕验收：", postfix = "。")
            ?: ""
        val recon = reconLines.take(MAX_RECON_LINES).takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n", prefix = "── 侦察附录（确定性发现，仅供核对，有误报率）──\n", postfix = "\n")
            ?: ""

        // 收缩顺序：附录行 → 验收 → 子提示词尾部；幕头/主提示词/进度永不截断。
        var body = recon + acceptance + stage.subPrompt
        val fixed = fixedPart(playbook, header, progress)
        val budget = MAX_DIRECTIVE_CHARS - fixed.length
        if (body.length > budget) {
            body = shrink(body, budget.coerceAtLeast(200))
        }
        return fixed + body
    }

    private fun fixedPart(playbook: PlaybookDefinition, stageHeader: String, progress: String): String =
        "═══ 任务剧本《${playbook.name}》· 当前幕：$stageHeader ═══\n" +
            playbook.master + "\n" +
            progress +
            "── 本幕子提示词 ──\n"

    /** 幕进度一行态（历史压缩，不逐幕展开）。 */
    private fun progressLine(
        playbook: PlaybookDefinition,
        currentIndex: Int,
        completedStageIndexes: List<Int>
    ): String {
        val done = completedStageIndexes.sorted().joinToString("、") { "幕${it + 1}✓" }
        val remaining = (currentIndex + 1 until playbook.stageCount)
            .joinToString("、") { "幕${it + 1}" }
        val parts = mutableListOf("幕进度：")
        if (done.isNotEmpty()) parts.add("已完成 $done")
        parts.add("当前 幕${currentIndex + 1}·${playbook.stageAt(currentIndex)?.title ?: ""}")
        if (remaining.isNotEmpty()) parts.add("待办 $remaining")
        parts.add("产出满足验收后以下一幕章节头继续；全部幕完成时输出「$COMPLETION_TOKEN」")
        return parts.joinToString(" | ") + "\n"
    }

    // ═══ COMPOSED ═══

    private fun buildComposed(playbook: PlaybookDefinition): String {
        val sections = playbook.stages.mapIndexed { index, stage ->
            STAGE_HEADER_FORMAT.format(index + 1, stage.title) + "\n" + stage.subPrompt
        }
        val body = sections.joinToString(separator = "\n\n")
        val header = "═══ 任务剧本《${playbook.name}》· 单轮合成 ═══\n" +
            playbook.master + "\n── 全幕子提示词 ──\n"
        return if (header.length + body.length <= MAX_DIRECTIVE_CHARS) {
            header + body
        } else {
            header + shrink(body, MAX_DIRECTIVE_CHARS - header.length)
        }
    }

    // ═══ 收缩机械 ═══

    /** 尾部截断 + 省略标记（保留开头——剧本指令的约束段都在前部）。 */
    private fun shrink(text: String, budget: Int): String {
        if (text.length <= budget) return text
        return text.take(budget - 1) + "…"
    }
}
