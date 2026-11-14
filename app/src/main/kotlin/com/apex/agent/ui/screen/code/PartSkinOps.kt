package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import com.apex.agent.ui.screen.settings.AgentSettings
import com.apex.agent.ui.theme.PartSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.SkinContext
import com.apex.agent.ui.theme.UiStyle
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════
// PartSkinOps — 部件级皮肤的 VM 扩展（God-file 预算纪律）
//
// 职责（PartSkin spec §7.5，与 CodeOutputStyleOps 同款双通道）：
//  - restorePartSkins：启动读 AgentSettings.partSkins 稀疏表 →
//    CodeUiState.partSkinOverrides / partSkinSnapshot；
//  - observePartSkins：设置流订阅 —— idle 期实时同步（用户在设置页切完
//    部件皮肤回 Coding 屏立即生效，不必等下一轮）；
//  - captureRunPartSkins：每轮 run 开始时捕获快照 —— 运行中切材质不打断
//    当前轮（FR-8：材质变更按 run 边界应用；流式期间重建 GPU
//    RenderEffect / GraphicsLayer 会与滚动锚定、展开动画争帧，材质是纯
//    视觉，延迟一轮无信息损失）。
//
// skinContextFor 在此组装（theme 包零反向依赖的关键）：CodeOutputStyle /
// UiStyle 压成 SkinContext 经 LocalSkinContext 下发（§7.4）。
// ═══════════════════════════════════════════════════════════════════════

/**
 * 皮肤上下文组装（§7.2）：上层把输出风格 / 全局风格 / 光晕压成
 * [SkinContext]。
 *
 * @param outputStyle 当前输出风格（TIMELINE / RIKKA）—— §4 注册表两套
 *   风格共享同一 Part ID，当前仅作预留（未来按风格差异化预设建议时在此
 *   分派），不参与本次回退链取值
 * @param uiStyle 全局材质风格（决定 globalDefault 与 minimal 降级）
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
 * 启动恢复：AgentSettings.partSkins 稀疏表（容错解析）→
 * [CodeUiState.partSkinOverrides] + [CodeUiState.partSkinSnapshot]。
 * 旧 JSON 无该字段 → 空 Map → 走 §4 新默认皮肤列，零迁移（§10）。
 */
internal fun CodeViewModel.restorePartSkins() {
    val overrides = partSkinOverridesFrom(settingsRepository.agentSettings.value)
    _uiState.update {
        it.copy(partSkinOverrides = overrides, partSkinSnapshot = overrides)
    }
}

/**
 * 设置流订阅：idle 期皮肤热切换（与 observeOutputStyle 同款）。
 *
 * 运行中（isRunning）到达的变更被忽略 —— 当前轮按 runEngine 捕获的快照
 * 渲染到收束（FR-8）；收束后本订阅把最新设置同步进 uiState，下一轮
 * captureRunPartSkins 取到同值。切换不重置任何展开态/折叠态/滚动位置
 * （FR-9 —— rememberSaveable key 不含 skin，LazyColumn key 只用 entry.id，
 * 皮肤快照只经 staticCompositionLocalOf 下发，不参与列表身份）。
 */
internal fun CodeViewModel.observePartSkins() {
    viewModelScope.launch {
        settingsRepository.agentSettings.collect { settings ->
            if (!_uiState.value.isRunning) {
                val overrides = partSkinOverridesFrom(settings)
                if (_uiState.value.partSkinSnapshot != overrides) {
                    _uiState.update {
                        it.copy(partSkinOverrides = overrides, partSkinSnapshot = overrides)
                    }
                }
            }
        }
    }
}

/**
 * 每轮 run 开始捕获部件皮肤快照（runEngine 在 beginRun 前调用，
 * 与 captureRunOutputMode / captureRunOutputStyle 同点位）。
 */
internal fun CodeViewModel.captureRunPartSkins() {
    val overrides = partSkinOverridesFrom(settingsRepository.agentSettings.value)
    _uiState.update {
        it.copy(partSkinOverrides = overrides, partSkinSnapshot = overrides)
    }
}

/** AgentSettings → 皮肤快照（容错解析收敛在 PartSkinOverrides.fromPersisted）。 */
private fun partSkinOverridesFrom(settings: AgentSettings): PartSkinOverrides =
    PartSkinOverrides.fromPersisted(
        raw = settings.partSkins,
        glow = settings.partSkinGlow
    )
