package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import com.apex.agent.ui.screen.code.ConversationSkinSnapshot
import com.apex.agent.ui.screen.code.SkinActivation
import com.apex.agent.ui.screen.code.diffParts
import com.apex.agent.ui.screen.settings.AgentSettings
import com.apex.agent.ui.screen.settings.DeviceTierProvider
import com.apex.agent.ui.theme.PartSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.SkinApplyMode
import com.apex.agent.ui.theme.SkinApplyPolicy
import com.apex.agent.ui.theme.SkinContext
import com.apex.agent.ui.theme.UiStyle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════
// PartSkinOps — 会话边界皮肤状态机的 VM 扩展（Spec v1.2 §7.5 / v1.3 §7.9）
//
// v1.2 全改写（H-5）：废除 v1.1 的 idle 期热切换（observePartSkins 直接
// _uiState.update）与 run 粒度捕获（captureRunPartSkins）—— 皮肤类设置
// 改完**当前对话一个部件都不重绘**，只写 pending；唯一生效边界：
//   ① 新对话 / 恢复历史 / 切工作区（promotePendingAtConversationBoundary）
//   ② IMMEDIATE 策略的 idle 提升点（stage 内联提升 + onRunSettled 收束
//     后第一个 idle 点，v1.3）
//
// H-6（v1.3）：isRunning == true 时任何模式都不提升 —— stage 只挂
// pending（stagedDuringRun），收束后由 onRunSettled 按策略决定。
//
// God-file 预算纪律（NFR-12）：会话状态机全部住本文件与
// ConversationSkin.kt，VM 与 CodeHistoryController 各只加单行调用点。
// ═══════════════════════════════════════════════════════════════════════

/** IMMEDIATE 提升防抖（NFR-15）：连续改多项 / 拖滑杆只在最后一次提交提升。 */
private const val STAGE_DEBOUNCE_MS = 250L

/**
 * 皮肤上下文组装（§7.2）：上层把输出风格 / 全局风格 / 光晕压成
 * [SkinContext]。v1.2 起入参取自 [ConversationSkinSnapshot]（四维度同
 * epoch，杜绝半套皮肤）。
 *
 * @param outputStyle 当前输出风格（TIMELINE / RIKKA）—— §4 注册表两套
 *   风格共享同一 Part ID，当前仅作预留，不参与本次回退链取值
 * @param uiStyle 会话快照的材质风格（决定 globalDefault 与 minimal 降级）
 * @param glow 光晕强度 0..1（越界 clamp）
 */
internal fun skinContextFor(
    @Suppress("UNUSED_PARAMETER") outputStyle: CodeOutputStyle,
    uiStyle: UiStyle,
    glow: Float
): SkinContext = SkinContext(
    globalDefault = if (uiStyle == UiStyle.LIQUID_GLASS) PartSkin.CLASSIC_GLASS else PartSkin.FLAT,
    presetSkin = null,
    minimal = uiStyle == UiStyle.MINIMAL,
    forceGlassInMinimal = false,
    glow = glow.coerceIn(0f, 1f)
)

/**
 * AgentSettings → 会话皮肤快照（四维度打包；容错解析收敛在
 * [PartSkinOverrides.fromPersisted]）。
 */
internal fun snapshotFrom(settings: AgentSettings, epoch: Long): ConversationSkinSnapshot =
    ConversationSkinSnapshot(
        epoch = epoch,
        uiStyle = UiStyle.fromKey(settings.uiStyle),
        outputStyle = settings.codeOutputStyle.toCodeOutputStyle(),
        renderMode = settings.codeOutputMode.toCodeOutputMode(),
        partSkins = PartSkinOverrides.fromPersisted(settings.partSkins, settings.partSkinGlow),
        glow = settings.partSkinGlow.coerceIn(0f, 1f)
    )

/**
 * 冷启动恢复（NFR-13）：pending 不落盘 → 直接从 AgentSettings 建 active
 * （epoch = 0），`pending = null`；顺带第 1 次采样设备分级（NFR-14：只在
 * 冷启动 / 会话边界采样，Composable 绝不读系统服务）。
 */
internal fun CodeViewModel.restoreActiveAtColdStart() {
    val settings = settingsRepository.agentSettings.value
    val snap = snapshotFrom(settings, epoch = 0L)
    _uiState.update {
        it.copy(
            conversationSkin = SkinActivation(
                active = snap,
                applyMode = SkinApplyMode.fromKey(settings.skinApplyMode),
                tier = deviceTierProvider.currentTier()
            )
        )
    }
}

/**
 * 设置流订阅（v1.2 H-5 / v1.3）：**只 stage，绝不改 active**。
 *
 *  - 250ms 防抖（NFR-15）：连续改多项 / 拖滑杆只在最后一次提交提升；
 *  - applyMode 同步进状态机（自身不触发换皮，只决定后续提升点）；
 *  - `stage(next, changed, isRunning)`：内容全等不产生 pending
 *    （NFR-15）；H-6 运行中一律挂起；idle 且 effectivePolicy ==
 *    IMMEDIATE → 立刻 promote（HIGH 机型 / 手动立即的旁路）；
 *  - LOW 档在 resolvePolicy 硬拒（NFR-16），不信任 UI 置灰。
 */
@OptIn(FlowPreview::class)
internal fun CodeViewModel.observeApplyModeAndTier() {
    viewModelScope.launch {
        settingsRepository.agentSettings
            .debounce(STAGE_DEBOUNCE_MS)
            .collect { settings ->
                val mode = SkinApplyMode.fromKey(settings.skinApplyMode)
                val next = snapshotFrom(
                    settings,
                    _uiState.value.conversationSkin.active.epoch + 1
                )
                val changed = diffParts(
                    _uiState.value.conversationSkin.active.partSkins,
                    next.partSkins
                )
                _uiState.update { state ->
                    val activation = state.conversationSkin.withApplyMode(mode)
                    state.copy(
                        conversationSkin = activation.stage(next, changed, state.isRunning)
                    )
                }
            }
    }
}

/**
 * 会话边界唯一提升点（新对话 / 恢复历史 / 切工作区，三处挂点各调一行）：
 *  - 顺带重采样设备分级（省电 / 减少动效是动态状态，边界重采样而非重组
 *    轮询，NFR-14）；
 *  - promote 幂等（无 pending 时原样返回，epoch 不虚增）；
 *  - 运行中由调用方守卫拦住（三个边界函数开头 `if (isRunning) return`，
 *    与 H-6 天然一致：运行中连「新对话」都不允许）。
 */
internal fun CodeViewModel.promotePendingAtConversationBoundary() {
    val tier = deviceTierProvider.currentTier()
    _uiState.update {
        it.copy(conversationSkin = it.conversationSkin.withTier(tier).promote())
    }
}

/**
 * 收束后第一个 idle 点（v1.3，J-3）：挂在 `CodeViewModel` 两处
 * `isRunning = false` 之后（runEngine finally 正常收束 / abort 用户点停）。
 *
 * 仅 `!isRunning && effectivePolicy == IMMEDIATE && hasPending` 时提升；
 * promote 幂等保证 abort + finally 双挂点只生效一次，不重复换皮。
 */
internal fun CodeViewModel.onRunSettled() {
    val activation = _uiState.value.conversationSkin
    if (
        !_uiState.value.isRunning &&
        activation.effectivePolicy == SkinApplyPolicy.IMMEDIATE &&
        activation.hasPending
    ) {
        _uiState.update { it.copy(conversationSkin = it.conversationSkin.promote()) }
    }
}

/**
 * 撤销待生效（FR-22）：把 AgentSettings 的皮肤字段**回滚成 active 对应
 * 值**（持久化一并回滚，防止下次冷启动又冒出同一批 pending）+ pending
 * 清空；active 与当前对话保持原样，不新建对话。
 */
internal fun CodeViewModel.discardPendingSkinChange() {
    val active = _uiState.value.conversationSkin.active
    settingsRepository.updateAgentSettings {
        copy(
            uiStyle = active.uiStyle.key,
            codeOutputStyle = active.outputStyle.persistenceName,
            codeOutputMode = active.renderMode.persistenceName,
            partSkins = active.partSkins.toPersisted(),
            partSkinGlow = active.glow
        )
    }
    _uiState.update { it.copy(conversationSkin = it.conversationSkin.discard()) }
}
