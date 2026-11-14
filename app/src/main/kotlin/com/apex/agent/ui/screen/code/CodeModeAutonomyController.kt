package com.apex.agent.ui.screen.code

import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AutonomyLevel
import kotlinx.coroutines.flow.update

// ─────────────────────────────────────────────────────────────────────────────
// S2 — Coding 模式级联选择器控制（CodeViewModel 的 internal 扩展，God-file 预算拆分）
//
// 组织方式同 CodeGoalController.kt：依赖成员已开放 internal
// （settingsRepository / codeEngineImpl / goalCoordinator），调用点
// （CodeScreen 的级联选择器、VM init 的 restoreAutonomy、GoalSetupSheet 的
// 启动回调）零感知。
//
// 职责边界：三档自主级别（提问策略）的状态同步 / 每模式记忆持久化 /
// 引擎四线推送 / 级联菜单的模式切换编排。指令正文在
// core.engine.AutonomyLevel（单一事实源），引擎注入通道见其 KDoc。
//
// 记忆口径：Build 与 Goal 各自从 AgentSettings（codeAutonomyLevel /
// codeGoalAutonomyLevel）记忆上次选择（Build 默认交互式）；前置澄清开关
// 全局一份（codeFullAutoPreClarify），切级别隐藏但保留状态。PLAN 模式无
// 自主级别（引擎侧不注入指令，行为零变化）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 启动恢复（VM init 在 setMode 之后调用一次）：按当前模式从设置层取回
 * 自主级别与前置澄清开关 → uiState 镜像 + 引擎四线推送。
 */
internal fun CodeViewModel.restoreAutonomy() {
    val s = settingsRepository.agentSettings.value
    val level = autonomyForMode(_uiState.value.mode, s.codeAutonomyLevel, s.codeGoalAutonomyLevel)
    _uiState.update {
        it.copy(autonomyLevel = level, fullAutoPreClarify = s.codeFullAutoPreClarify)
    }
    pushAutonomyToEngine()
}

/**
 * 级联菜单一级直接选中（Plan / Goal）：沿用既有 setMode 全链路（持久化 +
 * 引擎 updateMode + GOAL 模式附属动作），随后按新模式同步自主级别。
 */
internal fun CodeViewModel.switchCascadeMode(mode: AgentMode) {
    setMode(mode)
    syncAutonomyForMode()
}

/**
 * 级联菜单二级选中（Build 的三档自主级别）：持久化该模式的记忆 →
 * 切换 CodingMode=Build（不改变则幂等）→ uiState 镜像 + 引擎推送。
 * 菜单关闭由 UI 层负责（点二级项即全关）。
 */
internal fun CodeViewModel.selectCascadeAutonomy(mode: AgentMode, level: AutonomyLevel) {
    settingsRepository.updateAgentSettings {
        when (mode) {
            AgentMode.GOAL -> copy(codeGoalAutonomyLevel = level.persistenceName)
            else -> copy(codeAutonomyLevel = level.persistenceName)
        }
    }
    if (_uiState.value.mode != mode) setMode(mode)
    _uiState.update { it.copy(autonomyLevel = level) }
    pushAutonomyToEngine()
}

/**
 * 「开始前集中澄清」开关（级联菜单 / 目标设定弹层共用）：设置层持久化 +
 * uiState 镜像 + 引擎推送。开关状态独立于级别记忆（切级别保留状态）。
 */
internal fun CodeViewModel.toggleFullAutoPreClarify(enabled: Boolean) {
    settingsRepository.updateAgentSettings { copy(codeFullAutoPreClarify = enabled) }
    _uiState.update { it.copy(fullAutoPreClarify = enabled) }
    pushAutonomyToEngine()
}

/**
 * 模式切换后的自主级别同步（switchCascadeMode / setMode 尾部口径）：
 * 从设置层取新模式的记忆级别 → uiState + 引擎推送（PLAN → null 清除）。
 */
internal fun CodeViewModel.syncAutonomyForMode() {
    val s = settingsRepository.agentSettings.value
    val level = autonomyForMode(_uiState.value.mode, s.codeAutonomyLevel, s.codeGoalAutonomyLevel)
    _uiState.update { it.copy(autonomyLevel = level) }
    pushAutonomyToEngine()
}

/** 目标设定弹层的自主级别默认值（Goal 模式记忆，脏值兜底交互式）。 */
internal fun CodeViewModel.goalSetupDefaultAutonomy(): AutonomyLevel =
    AutonomyLevel.fromName(settingsRepository.agentSettings.value.codeGoalAutonomyLevel)
        ?: AutonomyLevel.INTERACTIVE

/** 目标设定弹层的前置澄清默认值（设置层真值）。 */
internal fun CodeViewModel.goalSetupDefaultPreClarify(): Boolean =
    settingsRepository.agentSettings.value.codeFullAutoPreClarify

/**
 * Plan 确认卡「切换到 Goal」入口（右侧 Flag 图标）：驳回挂起的计划
 * （人控门收口，引擎 Aborted）→ 切 GOAL 模式（无活动目标会弹设定 Sheet）
 * → 用计划目标预填目标陈述（复用 showGoalSetup 覆盖 onEnterGoalMode 的
 * 输入框草稿预填，见 CodeGoalController）。
 */
internal fun CodeViewModel.switchToGoalFromPlan() {
    val planGoal = _uiState.value.plan?.goal?.trim().orEmpty()
    confirmPlan(false)
    switchCascadeMode(AgentMode.GOAL)
    if (goalCoordinator.activeSpec() == null && planGoal.isNotBlank()) {
        showGoalSetup(planGoal)
    }
}

/** 引擎四线推送（DualLogicCodeEngine.updateAutonomy 同步四条线；Goal 启动扩展共用）。 */
internal fun CodeViewModel.pushAutonomyToEngine() {
    val mode = _uiState.value.mode
    // PLAN 无自主级别：null = 清除指令（EnginePrompts 段落省略）。
    val level = if (mode == AgentMode.PLAN) null else _uiState.value.autonomyLevel
    codeEngineImpl?.updateAutonomy(level, _uiState.value.fullAutoPreClarify)
}

/** 模式 → 生效自主级别（设置层记忆解析；未知值兜底交互式）。 */
private fun autonomyForMode(
    mode: AgentMode,
    buildSetting: String,
    goalSetting: String
): AutonomyLevel = when (mode) {
    AgentMode.GOAL -> AutonomyLevel.fromName(goalSetting) ?: AutonomyLevel.INTERACTIVE
    AgentMode.BUILD -> AutonomyLevel.fromName(buildSetting) ?: AutonomyLevel.INTERACTIVE
    AgentMode.PLAN -> AutonomyLevel.INTERACTIVE
}
