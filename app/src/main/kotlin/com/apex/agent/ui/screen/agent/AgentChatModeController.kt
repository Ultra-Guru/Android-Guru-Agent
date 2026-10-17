package com.apex.agent.ui.screen.agent

import com.apex.agent.R
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.modes.ModePreset
import com.apex.agent.ui.screen.settings.withPresetUpserted
import kotlinx.coroutines.flow.update

// ─────────────────────────────────────────────────────────────────────────────
// 模式 / 思考档位控制（AgentChatViewModel 的 internal 扩展，God-file 预算拆分）
//
// 模式同 AgentChatHistoryController.kt：依赖成员已开放 internal / public，
// 调用点（AgentChatScreen / ModeGuideSheet / init 启动恢复）零改动 ——
// 同包顶层扩展在类体内以隐式接收者调用（installChatHistoryAutoPersist 同款）。
//
// 职责边界：本文件只管「运行模式切换 / 思考档位 / 强制深度思考 / Spec 确认」
// 四组引擎热更新通道；引擎仍经 patchConfig 只改目标字段（P1-1 语义：
// 绝不重置其余配置）。
//
// #197：toolkitStore/函数调用已迁至 Coding 屏（CodeViewModel 注入同一
// @Singleton ChatToolkitStore——Agent 屏不再消费，无跨屏状态串扰）。
// ─────────────────────────────────────────────────────────────────────────────

/** 切换运行模式（BUILD/CHAT/PLAN/SPEC/CUSTOM…）。 */
fun AgentChatViewModel.setMode(mode: AgentMode) {
    _uiState.update { it.copy(mode = mode) }
    // P1-1（6-c）：patchConfig 只改 mode/customInstruction，保留其余引擎配置（原 updateConfig 重置全部）。
    // #168：CUSTOM 模式注入当前生效指令（选中预设优先，回退旧单串）。
    (agentEngine as? ApexAgentEngine)?.patchConfig { cfg ->
        cfg.copy(
            mode = mode,
            customInstruction = if (mode == AgentMode.CUSTOM) {
                settingsRepository.effectiveCustomInstruction().ifBlank { cfg.customInstruction }
            } else cfg.customInstruction
        )
    }
}

/**
 * #168 upsert CUSTOM 模式预设（聊天页顶栏 chip 编辑入口）。
 *
 * 保存后自动选中（withPresetUpserted 语义）；生效链路复用 init 里的
 * agentSettings collector → patchConfig(customInstruction)，下一轮请求生效。
 */
fun AgentChatViewModel.upsertModePreset(preset: ModePreset) {
    settingsRepository.updateAgentSettings { withPresetUpserted(preset) }
}

/** 用户确认/驳回了 Spec 模式的规格，恢复引擎执行。 */
fun AgentChatViewModel.submitSpecConfirmation(confirmed: Boolean) {
    _uiState.update { it.copy(awaitingSpecConfirmation = false) }
    // #340：回执 = false 表示无挂起等待（迟到/被 abort）——显式提示。
    val delivered = (agentEngine as? ApexAgentEngine)?.submitSpecConfirmation(confirmed) ?: false
    if (!delivered) {
        _uiState.update { s ->
            s.copy(messages = s.messages + AgentUiMessage.System(str(R.string.chat_spec_confirm_late)))
        }
    }
}

/** 选择思考档位（六档 + AUTO 自适应）。 */
fun AgentChatViewModel.setThinkingLevel(level: ThinkingLevel) {
    // #168：档位选择持久化到 AgentSettings（跨重启恢复，patchConfig 即时生效）。
    settingsRepository.updateAgentSettings { copy(thinkingLevelOverride = level.name.lowercase()) }
    applyThinkingLevel(level)
    if (level == ThinkingLevel.AUTO) {
        _lastAdaptiveDecision.value = null // 决策理由由下一轮 IterationStart 刷新
    }
    // 双级思考控制（RikkaHub 式）：档位不再自动映射/覆写模型原生 reasoning effort ——
    // 第一级（模型原生强度）由 ReasoningEffort chips 独立控制并持久化到 Profile，
    // 与本档位（引擎提示词层）完全解耦，两者独立生效。
}

/** 档位 → UI 状态 + 引擎配置（setThinkingLevel 与启动恢复共用）。 */
internal fun AgentChatViewModel.applyThinkingLevel(level: ThinkingLevel) {
    _uiState.update { it.copy(thinkingLevel = level, forceDeepThinking = level == ThinkingLevel.MAXIMUM) }
    // P1-1（6-c）：patchConfig 只改 thinkingLevel，保留其余引擎配置。
    (agentEngine as? ApexAgentEngine)?.patchConfig { cfg -> cfg.copy(thinkingLevel = level) }
}

/**
 * 双级思考控制第二级：强制深度思考开关。
 *
 * ON → 引擎 ThinkingLevel 钉 MAXIMUM（七步 ToT 提示词 + 工具自检 + 终检清单，
 * 提示词层强制，对任何模型生效）；OFF → 回退 STANDARD 三步 CoT。
 * 与第一级（模型原生 reasoning effort，Profile 字段）互不干涉。
 */
fun AgentChatViewModel.setForceDeepThinking(enabled: Boolean) {
    settingsRepository.updateAgentSettings {
        copy(forceDeepThinking = enabled, thinkingLevelOverride = if (enabled) "maximum" else "standard")
    }
    applyThinkingLevel(if (enabled) ThinkingLevel.MAXIMUM else ThinkingLevel.STANDARD)
}

/** #168：thinkingLevelOverride 字符串 → ThinkingLevel（未知/空值 → null = 不覆盖）。 */
internal fun AgentChatViewModel.thinkingLevelFromOverride(value: String): ThinkingLevel? =
    runCatching { ThinkingLevel.valueOf(value.trim().uppercase()) }.getOrNull()
