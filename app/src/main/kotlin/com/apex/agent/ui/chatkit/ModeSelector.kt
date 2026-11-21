package com.apex.agent.ui.chatkit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Flag
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.apex.agent.R
import com.apex.agent.core.engine.AgentMode

/**
 * 可选模式（#197：plan/build；v3 新增 GOAL 目标驱动）—— Agent 屏移除后
 * 本软件唯一工位。v4 起选择器为级联菜单（ui/screen/code 的
 * CodeModeCascadeSelector：Build 二级自主级别 / Plan / Goal 直选），本
 * 文件只保留跨文件共享的模式助手（可选集 / 图标 / 描述映射）。
 */
val CODING_SCREEN_MODES: List<AgentMode> =
    listOf(AgentMode.BUILD, AgentMode.PLAN, AgentMode.GOAL)

/** 模式图标映射 —— 图标属 UI 层关注点，不放核心枚举（core 无 Compose 依赖）。 */
fun agentModeIcon(mode: AgentMode): ImageVector = when (mode) {
    AgentMode.BUILD -> Icons.Default.Build
    AgentMode.PLAN -> Icons.AutoMirrored.Filled.ListAlt
    AgentMode.GOAL -> Icons.Default.Flag
}

/**
 * 模式描述映射 —— i18n 同款理由（文案属 UI 层关注点；core 枚举 description 保持引擎侧不动）。
 */
@Composable
fun agentModeDescription(mode: AgentMode): String = when (mode) {
    AgentMode.BUILD -> stringResource(R.string.chat_mode_build_desc)
    AgentMode.PLAN -> stringResource(R.string.chat_mode_plan_desc)
    AgentMode.GOAL -> stringResource(R.string.chat_mode_goal_desc)
}
