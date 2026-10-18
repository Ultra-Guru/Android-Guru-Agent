package com.apex.agent.core.code.loom.markup

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomInvocation
import com.apex.agent.core.code.loom.LoomOutcomeBlock
import com.apex.agent.core.code.loom.LoomOutcomeStatus
import com.apex.agent.core.code.loom.LoomPrompts

/**
 * # Loom Outcome Formatter — 回执织入格式化器
 *
 * 把工具执行结果、权限决策、问答超时、转向指令、催促反馈……一切需要
 * 「织回模型上下文」的东西格式化为 outcome 块 / 用户轮文本。
 *
 * ## 裁剪纪律（预算三级）
 *
 * 1. [LoomEngineConfig.outcomeBudgetChars]：常规预算——超了就截断 +
 *    标注截断行（截断是**状态可见的**：模型看得到 elided 字数，自己
 *    决定要不要换更小范围重读）；
 * 2. [LoomEngineConfig.outcomeHardCapChars]：绝对上限——病态巨输出
 *    （如误读二进制文件）即使预算调得再大也不越过这里；
 * 3. 截断按 **Unicode 标量**切（不劈代理对）——低配 surrogate 拼字
 *    安全。
 *
 * ## 用户轮织入样式
 *
 * 一批回执织成**一条用户轮**（多块以空行分隔，块外零闲字——协议纯度
 * 让模型无法把系统回执与人话混淆）。
 */
class LoomOutcomeFormatter(private val config: LoomEngineConfig) {

    // ═══════════════════════════ 工具结果回执 ═══════════════════════════

    /** 工具执行成功/失败 → 回执块（含预算裁剪）。 */
    fun formatResult(
        toolName: String,
        output: String,
        success: Boolean,
        durationMs: Long
    ): LoomOutcomeBlock = LoomOutcomeBlock(
        token = LoomMarkupGrammar.newOutcomeToken(),
        toolName = toolName,
        status = if (success) LoomOutcomeStatus.OK else LoomOutcomeStatus.ERROR,
        payload = clip(output),
        truncated = isOverBudget(output)
    )

    /** 工具抛异常 → 硬失败回执。 */
    fun formatError(toolName: String, message: String): LoomOutcomeBlock =
        LoomOutcomeBlock(
            token = LoomMarkupGrammar.newOutcomeToken(),
            toolName = toolName,
            status = LoomOutcomeStatus.ERROR,
            payload = clip(message)
        )

    /** 柔性失败（可重试路径）→ warn 回执。 */
    fun formatWarn(toolName: String, message: String): LoomOutcomeBlock =
        LoomOutcomeBlock(
            token = LoomMarkupGrammar.newOutcomeToken(),
            toolName = toolName,
            status = LoomOutcomeStatus.WARN,
            payload = clip(message)
        )

    /** 用户拒绝（权限门）→ warn 回执（模型可调整方案，不该硬撞）。 */
    fun formatDenied(toolName: String, reason: String): LoomOutcomeBlock =
        LoomOutcomeBlock(
            token = LoomMarkupGrammar.newOutcomeToken(),
            toolName = toolName,
            status = LoomOutcomeStatus.WARN,
            payload = clip("用户拒绝了本次调用：" + reason)
        )

    /** 问答窗口超时（按拒绝语义收口）→ warn 回执。 */
    fun formatApprovalLapse(toolName: String, timeoutMs: Long): LoomOutcomeBlock =
        LoomOutcomeBlock(
            token = LoomMarkupGrammar.newOutcomeToken(),
            toolName = toolName,
            status = LoomOutcomeStatus.WARN,
            payload = clip(
                "权限确认等待超时（" + timeoutMs / 1000 + " 秒），本次调用未执行。"
            )
        )

    /** 批量回执 → 一条用户轮文本（块间空行，块外零闲字）。 */
    fun renderOutcomeTurn(outcomes: List<LoomOutcomeBlock>): String =
        outcomes.joinToString("\n\n") { LoomMarkupGrammar.outcomeBlock(it) }

    // ═══════════════════════════ 人控与反馈轮 ═══════════════════════════

    /** 运行中转向 → 优先用户轮。 */
    fun renderSteeringTurn(text: String): String =
        "[转向] 用户在任务运行中补充指令（优先遵循）：\n" + text.trim()

    /** 纯思考退回 → 催促用户轮。 */
    fun renderNudgeTurn(): String = LoomPrompts.nudgeFeedback()

    /** PLAN 确认 → 用户轮（确认/驳回 + 可选步序口径）。 */
    fun renderPlanVerdictTurn(confirmed: Boolean, note: String?): String {
        val head = if (confirmed) {
            "计划已确认，按布样步骤开始执行。"
        } else {
            "计划被驳回，请根据以下反馈重新织一份布样（不要开始执行）。"
        }
        val tail = note?.trim()?.takeIf { it.isNotBlank() }?.let { "\n用户反馈：" + it } ?: ""
        return head + tail
    }

    /** 权限问答提示文案（UserInputRequired.prompt）。 */
    fun renderApprovalQuestion(
        invocation: LoomInvocation,
        riskLine: String
    ): String = buildString {
        append("工具 ").append(invocation.name).append(" 请求执行")
        if (riskLine.isNotBlank()) append("（").append(riskLine).append("）")
        append("。参数：")
        append(renderArgsBrief(invocation))
        append("。允许吗？")
    }

    /** 参数简报（问答弹窗一行——超长折叠）。 */
    private fun renderArgsBrief(invocation: LoomInvocation): String {
        if (invocation.args.isEmpty()) return "（无参数）"
        val raw = invocation.args.joinToString("，") { it.name + "=" + it.value }
        return if (raw.length <= ARGS_BRIEF_LIMIT) raw else raw.substring(0, ARGS_BRIEF_LIMIT) + "…"
    }

    // ═══════════════════════════ 裁剪 ═══════════════════════════

    /** 是否超预算（决定 truncated 标志）。 */
    internal fun isOverBudget(output: String): Boolean =
        output.length > config.outcomeBudgetChars

    /**
     * 预算裁剪：超预算 → 保留头部 + 截断标注行；绝对上限兜底。
     *
     * 截断点按 Unicode 标量对齐（代理对不劈半）。空输出保留空串
     * （工具语义交给 status 表达，不硬造文案）。
     */
    internal fun clip(output: String): String {
        val budget = config.outcomeBudgetChars
        if (output.length <= budget) return output
        var cut = budget
        // 代理对保护：切点落在高代理上 → 回退一位
        if (cut > 0 && Character.isHighSurrogate(output[cut - 1])) cut--
        val head = output.substring(0, cut)
        val elided = output.length - cut
        val marker = "……[输出超预算已截断：省略 " + elided + " 字符；需要更多请用更小范围重读]"
        // 绝对上限兜底（预算被调到病态大时也不至于撑爆上下文）
        return if (head.length + marker.length > config.outcomeHardCapChars) {
            head.substring(0, (config.outcomeHardCapChars - marker.length).coerceAtLeast(0)) + marker
        } else {
            head + marker
        }
    }

    private companion object {
        const val ARGS_BRIEF_LIMIT = 160
    }
}
