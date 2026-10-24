package com.apex.agent.ui.screen.code

import com.apex.agent.R
import com.apex.agent.core.code.longtask.LongTaskStatus
import com.apex.agent.ui.screen.agent.PendingPipelineCommand
import kotlinx.coroutines.flow.update

/**
 * # Code Task Queue Controller — 任务排队发送（CodeViewModel 扩展）
 *
 * 运行中发出的第二条任务不再被静默丢弃（旧实现 sendMessage 在 isRunning
 * 时直接 return，输入蒸发）。全链路：
 *
 * 1. **入队**（[enqueueWhileRunning]，sendMessage 运行中分支调用）：
 *    文本 + 当前技能 chip 快照入队（[QueuedTask]），输入框清空，输入栏
 *    上方的排队卡接管展示；已有排队任务时替换为最新输入（最新意图优先）
 *    并回执提示；
 * 2. **收尾裁决**（[finalizeQueuedTaskAfterRun]，runEngine finally 调用）：
 *    上一任务干净完成 → 自动发送排队任务（系统消息回执 + 摘卡 dispatch）；
 *    失败/中止 → 转 NEEDS_CONFIRM 等用户点「开始排队」（裁决纯函数住
 *    [QueuedTaskPolicy]，单测覆盖）；
 * 3. **人控入口**（[startQueuedTask] / [cancelQueuedTask]，排队卡按钮）：
 *    确认继续 / 放弃排队。
 *
 * 排队载荷兼容全部发送形态：斜杠命令、多 chip 流水线、@file:line 引用——
 * dispatch 走 sendMessage 正常管线原路复用。
 */
internal fun CodeViewModel.enqueueWhileRunning(
    text: String,
    commands: List<PendingPipelineCommand>
): Boolean {
    val replaced = _uiState.value.queuedTask != null
    _uiState.update {
        it.copy(
            queuedTask = QueuedTask(text = text, commands = commands),
            inputDraft = ""
        )
    }
    setPendingCommands(emptyList())
    if (replaced) {
        appendSystemMessage(languageManager.getString(R.string.code_queue_replaced))
    }
    return true
}

/** runEngine 收尾：排队任务去向裁决（自动接力 / 转待确认）。 */
internal fun CodeViewModel.finalizeQueuedTaskAfterRun(taskStatus: LongTaskStatus) {
    val queued = _uiState.value.queuedTask ?: return
    when (QueuedTaskPolicy.resolveAfterRun(
        completedCleanly = taskStatus == LongTaskStatus.COMPLETED,
        queued = queued
    )) {
        QueuedTaskPolicy.Outcome.AUTO_DISPATCH -> {
            // isRunning 已置 false——直接走 sendMessage 正常管线；新 run 是
            // 新协程，与旧 runJob 无重入。
            _uiState.update { it.copy(queuedTask = null) }
            appendSystemMessage(languageManager.getString(R.string.code_queue_auto_sent))
            dispatchQueuedTask(queued)
        }
        QueuedTaskPolicy.Outcome.NEEDS_CONFIRM -> {
            // 失败/中止不自动接力（用户可能要先看错误、重试或改输入）——
            // 转 NEEDS_CONFIRM，排队卡亮出「开始排队」按钮。
            _uiState.update {
                it.copy(
                    queuedTask = QueuedTaskPolicy.nextQueuedState(
                        QueuedTaskPolicy.Outcome.NEEDS_CONFIRM,
                        queued
                    )
                )
            }
        }
        QueuedTaskPolicy.Outcome.NONE -> Unit
    }
}

/** 用户点击「开始排队」：上一任务失败/中止后确认继续，立即发送排队任务。 */
fun CodeViewModel.startQueuedTask() {
    val queued = _uiState.value.queuedTask ?: return
    if (_uiState.value.isRunning) return
    _uiState.update { it.copy(queuedTask = null) }
    dispatchQueuedTask(queued)
}

/** 取消排队任务（排队卡叉号；等待中/待确认两态通用）。 */
fun CodeViewModel.cancelQueuedTask() {
    _uiState.update { it.copy(queuedTask = null) }
}

/** 发送排队任务（自动接力与确认两路共用）：恢复 chip 快照 → 正常发送管线。 */
private fun CodeViewModel.dispatchQueuedTask(task: QueuedTask) {
    if (task.commands.isNotEmpty()) {
        restorePendingCommands(task.commands)
    }
    sendMessage(task.text)
}
