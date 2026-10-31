package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════
// CodeOutputStyleOps — 输出风格的 VM 扩展（God-file 预算纪律）
//
// 职责：输出风格（TIMELINE / RIKKA，见 CodeOutputStyle.kt）的
//  - restoreOutputStyle：启动恢复持久化风格进 uiState.outputStyle；
//  - observeOutputStyle：设置流订阅 —— idle 期实时同步（用户在设置页
//    切完风格回 Coding 屏立即生效，不必等下一轮）；
//  - captureRunOutputStyle：每轮 run 开始时捕获风格（「切换只影响下一
//    条消息或完成后的消息」——运行中改设置不打断当前轮皮肤）。
//
// 与 CodeOutputModeOps 同款 run 粒度快照语义，差异只在 idle 期：风格是
// 纯皮肤（idle 期两风格渲染的都是终态内容，热切换无撕裂风险），故叠加
// 设置流实时同步——「切了没反应」正是本次需求要消灭的体验。
// ═══════════════════════════════════════════════════════════════════════

/**
 * 启动恢复：AgentSettings.codeOutputStyle 字符串 →
 * [CodeUiState.outputStyle]（空/未知值回退 TIMELINE）。
 */
internal fun CodeViewModel.restoreOutputStyle() {
    val style = settingsRepository.agentSettings.value.codeOutputStyle.toCodeOutputStyle()
    _uiState.update { it.copy(outputStyle = style) }
}

/**
 * 设置流订阅：idle 期风格热切换。
 *
 * 运行中（isRunning）到达的变更被忽略——当前轮按 runEngine 捕获的
 * 皮肤渲染到收束；收束后（idle 两风格渲染的都是终态内容）本订阅把
 * 最新设置同步进 uiState，下一轮 captureRunOutputStyle 取到同值。
 */
internal fun CodeViewModel.observeOutputStyle() {
    viewModelScope.launch {
        settingsRepository.agentSettings.collect { settings ->
            if (!_uiState.value.isRunning) {
                val style = settings.codeOutputStyle.toCodeOutputStyle()
                if (_uiState.value.outputStyle != style) {
                    _uiState.update { it.copy(outputStyle = style) }
                }
            }
        }
    }
}

/**
 * 每轮 run 开始捕获输出风格（runEngine 在 beginRun 前调用）。
 *
 * 捕获而非直读：与输出方式档同款语义——运行中在设置页改风格不打断
 * 当前轮的渲染组件分派，收束后 idle 订阅/下一轮捕获自动按新风格生效。
 */
internal fun CodeViewModel.captureRunOutputStyle() {
    val style = settingsRepository.agentSettings.value.codeOutputStyle.toCodeOutputStyle()
    _uiState.update { it.copy(outputStyle = style) }
}
