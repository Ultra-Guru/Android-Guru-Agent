package com.apex.agent.usage

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前用量归属会话（#221）。
 *
 * 用量观测发生在 [com.apex.agent.core.llm.runtime.ModelRuntime] 执行层，那里
 * 不知道 UI 当前绑定了哪个工作区会话；而工作区 id 由 ViewModel 在切换时决定。
 * 这个单例是两者之间唯一的弱连接：ViewModel 切会话时写入，观测器读它记账。
 *
 * 语义：null / 空白 = 尚未绑定会话（记账时归一为空串，落入「未知会话」桶，
 * 与 [UsageLedger.record] 既有口径一致）。
 */
@Singleton
class CurrentUsageSession @Inject constructor() {

    @Volatile
    var sessionId: String? = null
        private set

    fun bind(workspaceId: String?) {
        sessionId = workspaceId?.takeIf { it.isNotBlank() }
    }
}
