package com.apex.agent.ui.screen.code

import kotlinx.coroutines.flow.update

// ═══════════════════════════════════════════════════════════════════════
// CodeOutputModeOps — 输出展示模式的 VM 扩展（God-file 预算纪律）
//
// CodeViewModel 行数守 1200 门禁（quality-gate check_file_size）：新增
// 逻辑独立成本文件，VM 只保留单行调用点（模式同 CodeEventReducer /
// CodeSessionSnapshotOps 拆分惯例——依赖成员开放 internal）。
//
// 职责：输出展示档（STREAMING / COMPLETE，见 CodeOutputMode.kt）的
//  - restoreOutputMode：启动恢复持久化档位进 uiState.renderMode；
//  - captureRunOutputMode：每轮 run 开始时捕获档位（「切换只影响下一
//    条消息或完成后的消息」的运行中切换语义）。
//
// 为什么档位放 uiState 而非让 Screen 直读设置流：渲染档必须是**run 粒度
// 的快照**——运行中用户在设置页改档，当前轮继续按旧档渲染到收束，下一轮
// 才按新档。直读设置流会让在飞轮次中途翻面（半轮静默半轮流式的撕裂感），
// 捕获语义只有 VM 的 runEngine 单点能保证（排队任务/重试/斜杠路由等所有
// 发送入口都汇入 runEngine）。
// ═══════════════════════════════════════════════════════════════════════

/**
 * 启动恢复：AgentSettings.codeOutputMode 字符串 → [CodeUiState.renderMode]
 * （空/未知值回退 STREAMING）。idle 期渲染两档等价（终态内容一致），
 * 恢复只为下轮捕获前有一个诚实的当前值。
 */
internal fun CodeViewModel.restoreOutputMode() {
    val mode = settingsRepository.agentSettings.value.codeOutputMode.toCodeOutputMode()
    _uiState.update { it.copy(renderMode = mode) }
}

/**
 * 每轮 run 开始捕获渲染档（runEngine 在 beginRun 前调用）。
 *
 * 捕获而非直读：规格书交互规则「切换只影响下一条消息或完成后的消息」——
 * 运行中改设置不打断当前轮展示；收束后（idle 渲染两档等价）下一轮
 * captureRunOutputMode 自动按新档生效，无需显式回同步。
 */
internal fun CodeViewModel.captureRunOutputMode() {
    val mode = settingsRepository.agentSettings.value.codeOutputMode.toCodeOutputMode()
    _uiState.update { it.copy(renderMode = mode) }
}
