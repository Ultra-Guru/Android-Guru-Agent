package com.apex.agent.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.apex.agent.R

/**
 * ═══ 可靠性定性档位（Reliability Tier）═══
 *
 * #365 可视化反馈：百分比成功率对用户是「假精确」——87% 和 91% 在决策上没有
 * 差别，真正可行动的信息只有三档定性结论。本组件把 0..1 的成功率投影为：
 *
 * - [STABLE]  稳定 —— ≥90%，放心交给 Agent 自动跑；
 * - [FAIR]    一般 —— ≥50%，能用但偶尔需要人工介入；
 * - [UNSTABLE] 不稳定 —— >0 且 <50%，失败为主，建议换方案/换工具；
 * - [UNUSED]  未使用 —— 0 次调用，谈不上可靠性。
 *
 * 刻意**保留**的数字（不在此组件职责内）：下载进度、Step x/y 任务进度、
 * 上下文水位等「进度型」百分比 —— 那些是实时状态而非评分，见
 * [ContextMeterBar]。此组件只替换「评分型」数字。
 *
 * 阈值与旧配色档位（≥90 primary / ≥50 tertiary / <50 error）一致，
 * 颜色语义不变，仅把数字换成定性词。
 */
enum class ReliabilityTier {
    STABLE,
    FAIR,
    UNSTABLE,
    UNUSED;

    /**
     * 定性标签（经 stringResource 本地化：稳定/一般/不稳定/未使用）。
     * 供徽标、StatChip、一行统计等所有展示位复用。
     */
    @Composable
    fun label(): String = when (this) {
        STABLE -> stringResource(R.string.market_reliability_stable)
        FAIR -> stringResource(R.string.market_reliability_fair)
        UNSTABLE -> stringResource(R.string.market_reliability_unstable)
        UNUSED -> stringResource(R.string.market_reliability_unused)
    }
}

/**
 * 把成功率投影为定性档位。
 *
 * @param successRate 0.0..1.0（无调用时为 0）
 * @param hasData 是否存在调用记录 —— 区分「0 次调用」与「全失败」：
 *   无调用 → [UNUSED]（未使用）；有调用且成功率为 0 → [UNSTABLE]。
 */
fun reliabilityTierOf(successRate: Double, hasData: Boolean): ReliabilityTier = when {
    !hasData -> ReliabilityTier.UNUSED
    successRate >= 0.90 -> ReliabilityTier.STABLE
    successRate >= 0.50 -> ReliabilityTier.FAIR
    else -> ReliabilityTier.UNSTABLE
}

/** [reliabilityTierOf] 的 Float 版（cs-mem MacroHealth.successRate 为 Float）。 */
fun reliabilityTierOf(successRate: Float, hasData: Boolean): ReliabilityTier =
    reliabilityTierOf(successRate.toDouble(), hasData)
