package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.templates.playbook.PlaybookAdvanceJudge
import kotlinx.coroutines.launch

/**
 * 任务剧本 Ops 扩展（CodeViewModel internal 扩展，Ops 拆分模式同
 * CodeOutputModeOps.kt —— God-file 预算：VM 只留单行事件钩子）。
 *
 * - 幕推进：`AgentEvent.ResponseComplete(fullText)` → 判定器 → 状态迁移 +
 *   系统消息 + extras 热重织（updateSessionExtras 整串重推 + prepareForTask，
 *   与 maybeEscalateOnDeepWater 同款手法——本轮后续迭代即生效）；
 * - 系统消息文案由本层组装（控制器保持无字符串依赖）。
 */

/** 幕推进事件消费（VM 事件 collect 单行调用）。 */
internal fun CodeViewModel.maybeAdvancePlaybook(event: AgentEvent) {
    if (event !is AgentEvent.ResponseComplete) return
    val outcome = playbookController.onAdvanceJudged(event.fullText) ?: return
    when (outcome.kind) {
        PlaybookAdvanceJudge.AdvanceKind.ADVANCED -> {
            appendSystemMessage(
                languageManager.getString(
                    R.string.playbook_stage_advanced,
                    outcome.playbookName,
                    outcome.newStageIndex + 1,
                    outcome.reason
                )
            )
            repushPlaybookExtras()
        }
        PlaybookAdvanceJudge.AdvanceKind.COMPLETED -> {
            appendSystemMessage(
                languageManager.getString(R.string.playbook_completed, outcome.playbookName)
            )
            // 收尾不热重织：下一轮发送自然回落无剧本上下文。
        }
        PlaybookAdvanceJudge.AdvanceKind.STAYED -> {
            // 原地：只在产出明显越轨（缺章节头）时提示一次口径，不刷屏。
            if (outcome.reason.contains("未按幕协议")) {
                appendSystemMessage(
                    languageManager.getString(
                        R.string.playbook_stage_stayed,
                        outcome.playbookName,
                        outcome.newStageIndex + 1
                    )
                )
            }
        }
    }
}

/**
 * 工具通道 advance 命令收集（VM init 单行调用，viewModelScope 常驻）：
 * PlaybookTool.advance → store.commands → 控制器手动推进 → 系统消息 +
 * extras 热重织（与回包判定推进同一收口）。
 */
internal fun CodeViewModel.collectPlaybookCommands() {
    viewModelScope.launch {
        playbookSessionStore.commands.collect { command ->
            val outcome = playbookController.manualAdvance(command.fromStageIndex) ?: return@collect
            appendSystemMessage(
                languageManager.getString(
                    R.string.playbook_stage_advanced,
                    outcome.playbookName,
                    outcome.newStageIndex + 1,
                    outcome.reason
                )
            )
            repushPlaybookExtras()
        }
    }
}

/** 幕推进/跳幕后 extras 整串重推 + prepareForTask 热重织。 */
internal fun CodeViewModel.repushPlaybookExtras() {
    val recomposed = playbookController.recomposedExtras() ?: return
    codeEngineImpl?.updateSessionExtras(recomposed)
    codeEngineImpl?.prepareForTask()
}
