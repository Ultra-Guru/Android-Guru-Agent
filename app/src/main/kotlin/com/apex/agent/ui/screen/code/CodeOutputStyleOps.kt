package com.apex.agent.ui.screen.code

import kotlinx.coroutines.flow.update

// ═══════════════════════════════════════════════════════════════════════
// CodeOutputStyleOps — 输出风格的 VM 扩展（God-file 预算纪律）
//
// 职责：输出风格（TIMELINE / RIKKA，见 CodeOutputStyle.kt）的启动恢复
// （restoreOutputStyle → uiState.outputStyle 历史镜像）。
//
// v1.2（PartSkin spec H-5）：observeOutputStyle 的 idle 热同步与
// captureRunOutputStyle 的 run 粒度捕获均已废除 —— 渲染分派改读会话
// 快照 conversationSkin.active（与部件皮肤/uiStyle/renderMode 同
// epoch 生效，FR-21），唯一提升点 = 会话边界或 IMMEDIATE idle 点。
// ═══════════════════════════════════════════════════════════════════════

/**
 * 启动恢复：AgentSettings.codeOutputStyle 字符串 →
 * [CodeUiState.outputStyle]（空/未知值回退 TIMELINE）。
 */
internal fun CodeViewModel.restoreOutputStyle() {
    val style = settingsRepository.agentSettings.value.codeOutputStyle.toCodeOutputStyle()
    _uiState.update { it.copy(outputStyle = style) }
}


