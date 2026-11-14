package com.apex.agent.ui.screen.code

import com.apex.agent.R
import com.apex.agent.core.code.standard.StandardLogicMode
import kotlinx.coroutines.flow.update

// ═══════════════════════════════════════════════════════════════════════
// CodeLogicModeOps — 思考逻辑双线切换的 VM 扩展（God-file 预算纪律）
//
// CodeViewModel 行数守 1200 门禁（quality-gate check_file_size）：新增
// 逻辑独立成本文件，VM 只保留单行调用点（模式同 CodeOutputModeOps /
// CodeSessionSnapshotOps 拆分惯例——依赖成员开放 internal）。
//
// 职责：双思考逻辑（STANDARD / LOOM / COURIER / DEEP_DIVE，见
// DualLogicCodeEngine）的运行时切换与启动恢复——
//  - setLogicMode：选择器入口（持久化 + 门面即时路由 + 系统消息 + 运行中拒绝）；
//  - restoreLogicMode：启动恢复持久化档位（空/未知 → 深潜兜底）。
//
// 各线现场独立保留，切回即恢复（引擎侧门面负责）；档位持久化于
// AgentSettings.codeThinkingLogic。
// ═══════════════════════════════════════════════════════════════════════

/**
 * 切换思考逻辑（Coding 屏右上角选择器入口）：持久化 + 四引擎门面
 * 即时路由 + 系统消息（各线现场独立保留，切回即恢复）+ 运行中拒绝
 * （防丢现场，由 UI 弹提示）。
 *
 * @return true = 切换成功；false = 正在运行（UI 提示稍后再切）
 */
internal fun CodeViewModel.setLogicMode(mode: StandardLogicMode): Boolean {
    if (_uiState.value.isRunning) return false
    if (_uiState.value.logicMode == mode) return true
    val switched = dualLogicEngine?.switchLogic(mode) ?: true
    if (!switched) return false
    settingsRepository.updateAgentSettings {
        copy(codeThinkingLogic = mode.persistenceName)
    }
    _uiState.update { it.copy(logicMode = mode) }
    appendSystemMessage(
        languageManager.getString(
            when (mode) {
                StandardLogicMode.STANDARD -> R.string.code_logic_switched_standard
                StandardLogicMode.LOOM -> R.string.code_logic_switched_loom
                StandardLogicMode.COURIER -> R.string.code_logic_switched_courier
                StandardLogicMode.DEEP_DIVE -> R.string.code_logic_switched_deep_dive
            }
        )
    )
    // 引擎侧同步当前模式/档位（新激活线接收与 UI 一致的 Build/Plan 与档位）
    codeEngineImpl?.updateMode(_uiState.value.mode)
    codeEngineImpl?.updateThinkingLevel(_uiState.value.thinkingLevel)
    return true
}

/** 启动恢复：codeThinkingLogic 字符串 → 逻辑（空/未知 → 深潜兜底）。 */
internal fun CodeViewModel.restoreLogicMode() {
    val mode = StandardLogicMode.fromName(
        settingsRepository.agentSettings.value.codeThinkingLogic
    ) ?: StandardLogicMode.DEEP_DIVE
    dualLogicEngine?.switchLogic(mode)
    _uiState.update { it.copy(logicMode = mode) }
    // 新激活线同步当前 Build/Plan 与档位（与 setLogicMode 运行时切换同口径）
    codeEngineImpl?.updateMode(_uiState.value.mode)
    codeEngineImpl?.updateThinkingLevel(_uiState.value.thinkingLevel)
}
