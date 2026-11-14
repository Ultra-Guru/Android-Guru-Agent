package com.apex.agent.ui.screen.code

import com.apex.agent.R
import kotlinx.coroutines.flow.update

/**
 * UI 消息滑动窗口（perf P0-3，解决「长对话闪退」的核心一环）。
 *
 * 引擎侧 `conversationHistory` 有 token 压缩门，但 UI 的 `messages` 无界
 * 累积且不随压缩收敛——每个工具结果卡常驻 4KB 文本，长会话数百条消息
 * 数 MB 起步，叠加落盘全量序列化是最典型 OOM 形状。本窗口把 messages
 * 钉在 [MAX_UI_MESSAGES] 条内：超限裁剪早期消息、以一条固定 id 的系统
 * 摘要条标注折叠数（幂等替换，不重复堆积）。
 *
 * 挂载点：渲染 ticker 每拍 O(1) 检查（超限才 O(n) 裁剪，罕见）；压缩
 * 事件（ContextCompressed）强制触发一次（历史已被引擎收敛，UI 立即
 * 跟进而非等下一个超限点）。
 *
 * 被折叠的早期内容仍完整保留在会话快照/检查点（检查点自身 takeLast(400)
 * 落盘），详情弹层与时间轴胶囊（streamSession.entries，独立窗口见
 * CodeStreamSession）不受影响。
 */
internal const val MAX_UI_MESSAGES = 160

/** 折叠摘要条固定 id（幂等替换的定位键；CodeChatMessage.id 为 Long，
 *  正常消息 id 来自自增发生器永不触及此值）。 */
private const val FOLDED_NOTICE_ID = Long.MAX_VALUE

/** 滑动窗口裁剪（仅超限时 O(n) 一次；ticker 每拍 O(1) 检查）。 */
internal fun CodeViewModel.applyMessagesWindow() {
    if (_uiState.value.messages.size <= MAX_UI_MESSAGES) return
    _uiState.update { state ->
        if (state.messages.size <= MAX_UI_MESSAGES) return@update state
        val keep = state.messages.takeLast(MAX_UI_MESSAGES)
        // 保留段里已有的旧摘要条摘出来（计数合并，不叠两层）
        val rest = keep.filterNot { it.id == FOLDED_NOTICE_ID }
        val foldedCount = state.messages.size - rest.size
        state.copy(
            messages = listOf(
                CodeChatMessage(
                    id = FOLDED_NOTICE_ID,
                    role = CodeChatMessage.Role.SYSTEM,
                    text = languageManager.getString(R.string.code_messages_folded_fmt, foldedCount)
                )
            ) + rest
        )
    }
}
