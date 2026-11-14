package com.apex.agent.ui.screen.code

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.DevicePerfTier
import com.apex.agent.ui.theme.PartSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.SkinApplyMode
import com.apex.agent.ui.theme.SkinApplyPolicy
import com.apex.agent.ui.theme.UiStyle
import com.apex.agent.ui.theme.resolvePolicy

/**
 * ═══════════════════════════════════════════════════════════════
 *  ConversationSkin — 会话边界皮肤状态机（Spec v1.2 §7.5 / v1.3 §7.9）
 * ═══════════════════════════════════════════════════════════════
 *
 * **H-5（2026-11 修订「呈现立即」）**：皮肤类设置改完，idle 期**立即整体
 * 换皮**（AUTO / IMMEDIATE 全分级；回屏、旋转、深浅切换、下拉刷新、发
 * 消息照旧不触发换皮）；显式 ON_NEW_CHAT 档沿用 v1.2 语义，只在跨越
 * **会话边界**（新对话 / 恢复历史 / 切工作区）才整体换皮。
 *
 * **H-6（硬规则，保留）**：`isRunning == true` 时任何模式都不提升、不改
 * `active`、不重绘；改动只挂 pending（[SkinActivation.stagedDuringRun]），
 * 等收束后的第一个 idle 点（`onRunSettled()`）自动提升（Code 屏配
 * SkinPendingBar 提示 + 「立即应用」显式入口）。
 *
 * 四维度同 epoch（FR-21）：`uiStyle`（材质短路）/ `outputStyle` /
 * `renderMode` / `partSkins + glow` 打包在同一 [ConversationSkinSnapshot]，
 * 杜绝「风格换了但部件皮肤没换」的半套状态。
 *
 * `pending` **只存内存不落盘**（NFR-13）：进程重建后从 `AgentSettings`
 * 直接恢复为 `active`（epoch = 0），不会「上次没确认的改动突然生效」。
 */

/**
 * 会话皮肤快照（四维度 + epoch，@Immutable）。
 *
 * @param epoch 会话边界单调计数；当前对话内恒定（NFR-10：PartSurface 以
 *   `remember(epoch, part, …)` 缓存解析结果，epoch 不变 ⇒ 零皮肤重组）
 * @param uiStyle 材质短路维度（仅 Coding 子树；配色仍由 ApexTheme 全局下发）
 * @param outputStyle 输出风格（TIMELINE / RIKKA）
 * @param renderMode 揭示时机（STREAMING / COMPLETE）
 * @param partSkins 74 部件 + 7 组键稀疏表
 * @param glow 光晕强度 0..1
 */
@Immutable
data class ConversationSkinSnapshot(
    val epoch: Long,
    val uiStyle: UiStyle,
    val outputStyle: CodeOutputStyle,
    val renderMode: CodeOutputMode,
    val partSkins: PartSkinOverrides,
    val glow: Float = 0.35f
) {
    /** 内容四维度全等（epoch 无关）—— NFR-15「内容无差异不升 epoch」。 */
    fun sameContent(other: ConversationSkinSnapshot): Boolean =
        uiStyle == other.uiStyle &&
            outputStyle == other.outputStyle &&
            renderMode == other.renderMode &&
            partSkins == other.partSkins &&
            glow == other.glow
}

/**
 * 会话皮肤状态机（v1.3 增 applyMode / tier / stagedDuringRun）。
 *
 * 状态迁移（§7.5.2 / §7.9.3）：
 *  - `stage(next, changed, isRunning)`：设置页改值 → 只动 pending；
 *    H-6 运行中一律挂起；idle 且 `effectivePolicy == IMMEDIATE` 时立刻
 *    `promote()`（当前对话整体换皮一次，250ms 防抖由观察器保证）；
 *  - `promote()`：会话边界唯一提升点 / IMMEDIATE idle 提升点；幂等
 *    （无 pending 时原样返回，防 abort/finally 双挂重复提升）；
 *  - `discard()`：撤销待生效 —— 丢弃 pending，active 原封不动。
 */
@Immutable
data class SkinActivation(
    val active: ConversationSkinSnapshot,
    val pending: ConversationSkinSnapshot? = null,
    val changedParts: Set<CodingPart> = emptySet(),
    val applyMode: SkinApplyMode = SkinApplyMode.AUTO,
    val tier: DevicePerfTier = DevicePerfTier.MID,
    val stagedDuringRun: Boolean = false
) {
    /** AUTO 按 [resolvePolicy] 解析后的实际策略；状态机只读本值。 */
    val effectivePolicy: SkinApplyPolicy get() = resolvePolicy(applyMode, tier)

    val hasPending: Boolean get() = pending != null

    /**
     * 设置变更落位（§7.9.3）。NFR-15：内容全等 → 不产生 pending、不升
     * epoch（用户把改动改回 active 同值时自动撤销）。
     */
    fun stage(
        next: ConversationSkinSnapshot,
        changed: Set<CodingPart>,
        isRunning: Boolean
    ): SkinActivation {
        if (next.sameContent(active)) {
            return copy(pending = null, changedParts = emptySet(), stagedDuringRun = false)
        }
        val staged = copy(
            pending = next.copy(epoch = active.epoch + 1),
            changedParts = changed,
            stagedDuringRun = isRunning
        )
        // ★ H-6：任务中一律不提升，无论策略；idle 且 IMMEDIATE → 立即提升
        return if (!isRunning && effectivePolicy == SkinApplyPolicy.IMMEDIATE) {
            staged.promote()
        } else {
            staged
        }
    }

    /** 提升点（会话边界 / IMMEDIATE idle）；幂等：无 pending 时原样返回。 */
    fun promote(): SkinActivation = if (pending == null) {
        this
    } else {
        SkinActivation(
            active = pending.copy(epoch = active.epoch + 1),
            pending = null,
            changedParts = emptySet(),
            applyMode = applyMode,
            tier = tier,
            stagedDuringRun = false
        )
    }

    /** 撤销：丢弃 pending，active 不变（持久化回滚由 Ops 层负责）。 */
    fun discard(): SkinActivation =
        copy(pending = null, changedParts = emptySet(), stagedDuringRun = false)

    /** 会话边界重采样分级（省电/减少动效是动态状态，NFR-14）。 */
    fun withTier(tier: DevicePerfTier): SkinActivation =
        if (this.tier == tier) this else copy(tier = tier)

    /** applyMode 自身只决定后续提升点，不触发换皮（§7.5.1）。 */
    fun withApplyMode(mode: SkinApplyMode): SkinActivation =
        if (applyMode == mode) this else copy(applyMode = mode)
}

/**
 * Coding 子树会话皮肤（v1.2 双 Local 分离的关键半边）：
 *  - [com.apex.agent.ui.theme.LocalUiStyle] 继续管**配色**（colorScheme）；
 *  - 本 Local 只管 **Coding 子树的材质短路 / 风格分派 / COMPLETE 判定 /
 *    PartSurface 解析** —— 壳层 / 设置页 / GlassLab 不消费它，外观不受
 *    「待生效」影响；未注入（null）时消费方回落全局 LocalUiStyle。
 */
val LocalConversationSkin = staticCompositionLocalOf<ConversationSkinSnapshot?> { null }

/**
 * 差集：两次皮肤快照间显式意图不同的部件集合（设置页「N 项待生效」）。
 *
 * 部件级覆盖 + 组级覆盖都参与比较（组切换按组内成员数计入 —— 组键变更
 * 时整组成员都算「待生效」）；FOLLOW（缺席）与显式值的差集同理。
 */
fun diffParts(a: PartSkinOverrides, b: PartSkinOverrides): Set<CodingPart> =
    CodingPart.entries.filterTo(LinkedHashSet()) { part ->
        val aPart = a.map[part] ?: PartSkin.FOLLOW
        val bPart = b.map[part] ?: PartSkin.FOLLOW
        if (aPart != bPart) return@filterTo true
        val aGroup = a.groups[part.group] ?: PartSkin.FOLLOW
        val bGroup = b.groups[part.group] ?: PartSkin.FOLLOW
        aGroup != bGroup
    }
