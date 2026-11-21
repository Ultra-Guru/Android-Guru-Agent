package com.apex.agent.core.code.playbook

import com.apex.agent.core.engine.templates.playbook.PlaybookDefinition
import com.apex.agent.core.engine.templates.playbook.TriggerContextGate
import com.apex.agent.core.engine.templates.playbook.TriggerRule

/**
 * 识别结果（可解释口径，对齐 AUTO 档预检决策）。
 *
 * @param playbookId 命中的剧本 id（null = 未命中——双门不满足）
 * @param score 命中剧本的得分
 * @param reason 理由（系统消息/建议条文案用）
 * @param runnerUpId 次优剧本 id（可空）
 * @param runnerUpScore 次优得分
 */
data class PlaybookMatch(
    val playbookId: String?,
    val score: Int,
    val reason: String,
    val runnerUpId: String? = null,
    val runnerUpScore: Int = 0
) {
    /** 是否构成挂载建议（命中双门）。 */
    val hit: Boolean get() = playbookId != null
}

/**
 * ═══ Coding 任务剧本识别器 ═══
 *
 * 发送前预检的「任务→剧本」自动选择器，纪律逐条克隆
 * CodeAdaptiveThinkingSelector（AUTO 档预检）：**纯函数、无状态、加权
 * 信号、可解释理由、双门阈值**——不做 LLM 调用（确定性、零成本、可测）。
 *
 * 评分模型（每条触发规则每轮只计一次，不按命中次数叠加）：
 * - 关键词命中（忽略大小写包含匹配）或正则命中 → 该规则得分；
 * - 上下文门不满足的规则不计分（如无工作区时 Git 剧本的规则全部哑火）；
 * - 剧本总分 = 全部命中规则得分之和。
 *
 * 双门阈值：
 * - 绝对门：最高分 ≥ [SCORE_HIT_FLOOR]（单条弱信号不足以挂载）；
 * - 领先门：最高分 − 次高分 ≥ [LEAD_GAP]（两剧本同分=歧义=不挂载，
 *   宁缺勿错——挂错剧本比不挂更伤信任）。
 */
class CodeTaskClassifier {

    /**
     * 预检识别。
     *
     * @param goalText 任务目标文本（用户输入原文）
     * @param playbooks 候选剧本清单（内置 + 自定义，触发表可空=只能手动）
     * @param hasWorkspace 是否已绑定工作区（上下文门输入）
     * @param hasCodeSignal 输入是否含代码形态信号（上下文门输入；宿主
     *   可用代码块/词密度等既有启发供给，缺省 true = 不设限）
     */
    fun classify(
        goalText: String,
        playbooks: List<PlaybookDefinition>,
        hasWorkspace: Boolean = true,
        hasCodeSignal: Boolean = true
    ): PlaybookMatch {
        val lowered = goalText.lowercase()
        val scored = playbooks.mapNotNull { playbook ->
            val detail = mutableListOf<String>()
            var total = 0
            for (rule in playbook.triggers) {
                if (!gateOpen(rule, hasWorkspace, hasCodeSignal)) continue
                val keyword = rule.keywords.firstOrNull { lowered.contains(it.lowercase()) }
                val pattern = if (keyword == null) {
                    rule.regexes.firstOrNull { candidate ->
                        runCatching { Regex(candidate, RegexOption.IGNORE_CASE).containsMatchIn(goalText) }
                            .getOrDefault(false)
                    }
                } else null
                if (keyword == null && pattern == null) continue
                total += rule.score
                detail.add(
                    if (keyword != null) "关键词「$keyword」+${rule.score}"
                    else "模式「$pattern」+${rule.score}"
                )
            }
            if (total <= 0) null else Triple(playbook, total, detail)
        }.sortedWith(compareByDescending<Triple<PlaybookDefinition, Int, List<String>>> { it.second }
            .thenBy { it.first.id })

        val best = scored.firstOrNull()
            ?: return PlaybookMatch(null, 0, "无触发信号")
        val runnerUp = scored.getOrNull(1)

        val reason = buildString {
            append(best.first.name)
            append("：")
            append(best.third.joinToString("、"))
            append("（总分 ${best.second}）")
        }

        val absoluteOk = best.second >= SCORE_HIT_FLOOR
        val leadOk = runnerUp == null || best.second - runnerUp.second >= LEAD_GAP
        return when {
            absoluteOk && leadOk -> PlaybookMatch(
                playbookId = best.first.id,
                score = best.second,
                reason = reason,
                runnerUpId = runnerUp?.first?.id,
                runnerUpScore = runnerUp?.second ?: 0
            )
            !absoluteOk -> PlaybookMatch(
                playbookId = null, score = best.second,
                reason = reason + "——未过绝对门（≥$SCORE_HIT_FLOOR）",
                runnerUpId = runnerUp?.first?.id, runnerUpScore = runnerUp?.second ?: 0
            )
            else -> PlaybookMatch(
                playbookId = null, score = best.second,
                reason = reason + "——歧义（次优 ${runnerUp?.first?.name} ${runnerUp?.second}，领先差不足 $LEAD_GAP）",
                runnerUpId = runnerUp?.first?.id, runnerUpScore = runnerUp?.second ?: 0
            )
        }
    }

    /** 上下文门（未知门按关闭处理——防御式）。 */
    private fun gateOpen(rule: TriggerRule, hasWorkspace: Boolean, hasCodeSignal: Boolean): Boolean =
        when (rule.contextGate) {
            TriggerContextGate.NONE -> true
            TriggerContextGate.WORKSPACE_PRESENT -> hasWorkspace
            TriggerContextGate.CODE_PRESENT -> hasCodeSignal
        }

    private companion object {
        const val SCORE_HIT_FLOOR = 4
        const val LEAD_GAP = 2
    }
}
