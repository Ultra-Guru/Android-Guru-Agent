package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * Agent 配置大分类的叶子页面（v2 抽屉化重构：每个二级抽屉项对应一个页面）。
 *
 * 页面内容 = 旧 AgentTab 中对应分区的原样搬移（零行为变更），仅重排
 * 展示位置：执行 / 角色人设 / 权限 / 规则 / 钩子 / 模式预设 / 子代理 /
 * 目标与循环 / 编程工位 / GitHub / 上下文压缩 / 视觉。
 */
@Composable
internal fun AgentSection(agent: AgentSettings, onUpdate: (AgentSettings) -> Unit) {
    // #236：Keep Alive 开关的运行时接线需要 Context（start/stopService）。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SectionCard(stringResource(R.string.settings_section_agent), Icons.Outlined.Psychology, initiallyExpanded = true) {
        DropdownRow("Execution Mode",
            listOf(
                "auto" to stringResource(R.string.settings_mode_auto),
                "build" to stringResource(R.string.settings_mode_build),
                "chat" to stringResource(R.string.settings_mode_chat),
                "plan" to stringResource(R.string.settings_mode_plan),
                "goal" to stringResource(R.string.settings_mode_goal),
                "loop" to stringResource(R.string.settings_mode_loop),
                "spec" to stringResource(R.string.settings_mode_spec),
                "reflect" to stringResource(R.string.settings_mode_reflect),
                "assist" to stringResource(R.string.settings_mode_assist),
                "custom" to stringResource(R.string.settings_mode_custom),
            ),
            agent.defaultMode,
            description = stringResource(R.string.settings_mode_desc)) {
            onUpdate(agent.copy(defaultMode = it))
        }
        DropdownRow("Think Level",
            listOf(
                "auto" to stringResource(R.string.settings_think_auto),
                "minimal" to stringResource(R.string.settings_think_minimal),
                "light" to stringResource(R.string.settings_think_light),
                "standard" to stringResource(R.string.settings_think_standard),
                "deep" to stringResource(R.string.settings_think_deep),
                "maximum" to stringResource(R.string.settings_think_maximum),
            ),
            agent.thinkLevel,
            description = stringResource(R.string.settings_think_desc)) {
            onUpdate(agent.copy(thinkLevel = it))
        }
        IntFieldRow(stringResource(R.string.settings_max_iterations), agent.maxIterations,
            description = stringResource(R.string.settings_max_iterations_desc), min = 1, max = 200) {
            onUpdate(agent.copy(maxIterations = it))
        }
        // #236：Keep Alive 真接线（关=立即停服务）。
        SwitchRow(stringResource(R.string.settings_keep_alive_service), agent.keepAlive) { on -> com.apex.agent.service.CoreServiceGate.apply(ctx, on); onUpdate(agent.copy(keepAlive = on)) }
        // v1.4.4 #4 任务完成通知与循环运行通知已聚合至「界面外观 → 通知」叶子
        // （interface/notifications，SettingsSystemPages）—— 此处不再重复入口。
        // #218：聊天记忆自动摄取（关闭 = 新对话不落盘 + 召回静默；已有记忆去记忆页管理）。
        SwitchRow(stringResource(R.string.settings_memory_capture_title), agent.chatMemoryCapture,
            description = stringResource(R.string.settings_memory_capture_desc)) {
            onUpdate(agent.copy(chatMemoryCapture = it))
        }
        SliderRow(stringResource(R.string.settings_reflection_rounds), agent.reflectionRounds.toFloat(), 1f..3f, 2,
            description = stringResource(R.string.settings_reflection_rounds_desc),
            onValueChange = { onUpdate(agent.copy(reflectionRounds = it.toInt())) },
            fmt = { it.toInt().toString() })

        HorizontalDivider()
        Text(stringResource(R.string.settings_retry_loop_section), style = MaterialTheme.typography.labelMedium)
        SwitchRow(stringResource(R.string.settings_auto_retry), agent.autoRetry) { onUpdate(agent.copy(autoRetry = it)) }
        IntFieldRow(stringResource(R.string.settings_max_retry_per_action), agent.maxRetryPerAction, min = 0, max = 10) {
            onUpdate(agent.copy(maxRetryPerAction = it))
        }
        SwitchRow(stringResource(R.string.settings_loop_detection), agent.loopDetection) { onUpdate(agent.copy(loopDetection = it)) }
        IntFieldRow(stringResource(R.string.settings_loop_detection_window), agent.loopDetectionWindow, min = 2, max = 50) {
            onUpdate(agent.copy(loopDetectionWindow = it))
        }
        IntFieldRow(stringResource(R.string.settings_same_action_threshold), agent.sameActionThreshold, min = 2, max = 20) {
            onUpdate(agent.copy(sameActionThreshold = it))
        }
        SwitchRow(stringResource(R.string.settings_auto_recovery), agent.autoRecovery) { onUpdate(agent.copy(autoRecovery = it)) }

        HorizontalDivider()
        Text(stringResource(R.string.settings_advanced_behavior), style = MaterialTheme.typography.labelMedium)
        SwitchRow(stringResource(R.string.settings_reflection_label), agent.reflection,
            description = stringResource(R.string.settings_reflection_desc)) { onUpdate(agent.copy(reflection = it)) }
        SwitchRow(stringResource(R.string.settings_planning), agent.planning) { onUpdate(agent.copy(planning = it)) }
        SwitchRow(stringResource(R.string.settings_replanning), agent.replanning) { onUpdate(agent.copy(replanning = it)) }
        SwitchRow(stringResource(R.string.settings_parallel_tool_execution), agent.parallelToolExecution) {
            onUpdate(agent.copy(parallelToolExecution = it))
        }
        SwitchRow(stringResource(R.string.settings_background_execution), agent.backgroundExecution) {
            onUpdate(agent.copy(backgroundExecution = it))
        }
    }
}

/** 叶子页面通用容器：与旧 Tab 相同的滚动列布局（16dp 边距 + 12dp 间距）。 */
@Composable
internal fun SettingsLeafColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}
