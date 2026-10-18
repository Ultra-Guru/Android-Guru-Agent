package com.apex.agent.core.code.loom.loop

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomInvocation

/**
 * # Loom Guards — 梭循环防环与收敛守卫
 *
 * 织造循环的三类病态形态收口：
 *
 * 1. **重复纬线**（同名同参的调用批次一再出现——模型卡环）：三次起警、
 *    四次强制收敛（引擎拒绝执行重复批次，改织一条「总结现状」指令）；
 * 2. **空转回合**（无文本 / 无思考 / 无调用——模型静默）：连续两次即终止
 *    （防死循环烧 token）；
 * 3. **纯思考回合**（只有思考没有行动——模型「想而不织」）：nudge 催促
 *    上限由配置钳位，超过即强制收尾。
 *
 * 判定基于**批次指纹**（整批调用按序拼接）——单发重复与批次重复都命中。
 */
class LoomGuards(private val config: LoomEngineConfig) {

    /** 守卫裁决。 */
    sealed interface Verdict {
        /** 正常续织。 */
        data object Proceed : Verdict

        /** 重复第 3 次——照常执行，但回执轮附带告诫行。 */
        data class Caution(val message: String) : Verdict

        /** 重复第 4 次起——拒绝执行重复批次，改织收敛指令。 */
        data class ForceConverge(val message: String) : Verdict
    }

    /** 最近批次指纹环（容量 = 收敛阈值）。 */
    private val recentFingerprints = ArrayDeque<String>()

    /** 连续空转回合计数。 */
    private var emptyStreak = 0

    /** nudge（纯思考催促）已用次数。 */
    private var nudgesUsed = 0

    // ═══════════════════════════ 重复纬线 ═══════════════════════════

    /**
     * 观察一个批次的调用，给出防环裁决。
     *
     * 空批次不参与指纹（由空转守卫管辖）。
     */
    fun observeBatch(invocations: List<LoomInvocation>): Verdict {
        if (invocations.isEmpty()) return Verdict.Proceed
        val fingerprint = invocations.joinToString("|") { it.fingerprint() }
        val repeats = recentFingerprints.count { it == fingerprint }
        recentFingerprints.addLast(fingerprint)
        while (recentFingerprints.size > RING_CAPACITY) recentFingerprints.removeFirst()
        return when {
            repeats + 1 < CAUTION_AT -> Verdict.Proceed
            repeats + 1 < CONVERGE_AT -> Verdict.Caution(
                "（系统提示）同一批调用已连续出现 " + (repeats + 1) +
                    " 次——再重复将被强制收敛，请改变策略或总结现状。"
            )
            else -> Verdict.ForceConverge(
                "（系统提示）同一批调用已连续出现 " + (repeats + 1) +
                    " 次，系统已拒绝执行。请停止重复，基于已有回执总结现状" +
                    "（或说明障碍），给出正式回复。"
            )
        }
    }

    // ═══════════════════════════ 空转回合 ═══════════════════════════

    /**
     * 记录一个回合的产出形态。
     *
     * @return true = 空转超限（引擎终止任务）；false = 继续。
     */
    fun noteRoundShape(hasText: Boolean, hasDeliberation: Boolean, hasInvocations: Boolean): Boolean {
        val empty = !hasText && !hasDeliberation && !hasInvocations
        emptyStreak = if (empty) emptyStreak + 1 else 0
        return emptyStreak >= EMPTY_LIMIT
    }

    /** 是否纯思考回合（有思考、无文本、无调用）。 */
    fun isPureDeliberation(hasText: Boolean, hasDeliberation: Boolean, hasInvocations: Boolean): Boolean =
        hasDeliberation && !hasText && !hasInvocations

    /**
     * 是否还能 nudge（纯思考催促预算内）。
     *
     * @return true = 允许再催一次；false = 预算耗尽（引擎收尾）。
     */
    fun tryConsumeNudge(): Boolean {
        if (nudgesUsed >= config.nudgeMaxCount) return false
        nudgesUsed++
        return true
    }

    /** nudge 预算重置（新任务）。 */
    fun resetForTask() {
        recentFingerprints.clear()
        emptyStreak = 0
        nudgesUsed = 0
    }

    companion object {
        /** 指纹环容量（覆盖收敛阈值 + 余量）。 */
        private const val RING_CAPACITY = 8

        /** 第 3 次重复 → 告诫。 */
        private const val CAUTION_AT = 3

        /** 第 4 次重复 → 强制收敛。 */
        private const val CONVERGE_AT = 4

        /** 连续 2 个空转回合 → 终止。 */
        private const val EMPTY_LIMIT = 2
    }
}
