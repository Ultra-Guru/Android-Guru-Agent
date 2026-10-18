package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.llm.LlmMessage

/**
 * # 上下文归档 —— 压缩无损检索的地基（#396 上下文自主检索）
 *
 * 痛点：压缩器（滑动窗口 / LLM 摘要）把历史消息从活跃上下文里裁掉之后，
 * 旧实现里这些消息就**永久消失**了——`memory.save(compressed)` 覆写持久化
 * 记忆，#172 回顾三件套的数据源（[SessionContextProvider] 读
 * `ConversationMemory.load()`）也随之只剩压缩后的残影。模型"想回去看一眼
 * 原文"没有任何自救手段，只能凭摘要复述。
 *
 * 本接口补上这一层：**压缩前的被裁剪消息先进归档，再从活跃历史移除**。
 * 归档记录按会话内稳定序号（0 起、只增不减）编址，配合：
 * - [ContextSearchTool] —— 归档 + 活跃双源检索，命中以 `a#N` / `#N` 定位；
 * - [ContextFetchTool] —— 按定位符取**全文**（不截断，支持 offset/limit 分窗）。
 *
 * ## 模块边界
 *
 * 与 [SessionContextProvider] 同款 lambda 解耦策略：接口住在
 * `core:tool-registry`（工具层可读），实现在 app 层（文件持久化），
 * 写入方是 `core:agent-engine` 的压缩装饰器（ArchivingCompressor）——
 * 三方只共享本接口，无环依赖。
 *
 * ## 会话生命周期
 *
 * 归档是**会话级**的：新会话 / 恢复其它会话时引擎 `clearHistory()` /
 * `restoreHistory()` 会调用 [clear] 清空，防止上个会话的残影泄漏进
 * 新会话的检索结果。实现方自行保证线程安全（写入在 IO 线程、读取在
 * 工具执行线程）。
 */
interface SessionContextArchive {

    /**
     * 追加被压缩裁剪的原始消息（压缩装饰器调用）。
     *
     * 传入的是**压缩前**的原始形态（含未被截断的工具输出），由实现方
     * 负责去重（同一 toolCallId 保留更长的原文）与容量封顶。
     */
    fun appendArchived(messages: List<LlmMessage>)

    /**
     * 归档记录只读快照（index 为归档内稳定序号，从 0 起）。
     *
     * ⚠️ 与 [SessionContextProvider.records] 不同：归档记录的 content
     * **不做 2000 字符截断**——它就是"被裁剪原文"本身，截断会背叛
     * "无损检索"的立意。消费方（检索 / 取全文）自行控制窗口。
     */
    fun archivedRecords(): List<ContextRecord>

    /** 归档内记录条数（0 = 无归档，检索工具据此走纯活跃源）。 */
    fun size(): Int

    /** 清空归档（新会话 / 恢复会话 / 用户清空历史时调用）。 */
    fun clear()
}

/** 进程内归档实现（测试与 DI 兜底；线程安全，全文不截断）。 */
class InMemoryContextArchive : SessionContextArchive {

    private val messages = mutableListOf<LlmMessage>()
    private val lock = Any()

    override fun appendArchived(msgs: List<LlmMessage>) {
        if (msgs.isEmpty()) return
        synchronized(lock) { messages += msgs }
    }

    override fun archivedRecords(): List<ContextRecord> = synchronized(lock) {
        toArchivedRecords(messages)
    }

    override fun size(): Int = synchronized(lock) { messages.size }

    override fun clear() = synchronized(lock) { messages.clear() }
}

/**
 * LlmMessage → 归档记录（全文、稳定序号）。
 *
 * 与 [toContextRecords] 同口径（assistant 的工具调用追加 `[tool_call]`
 * 标记行），唯一差异是 content **不截断**。
 */
internal fun toArchivedRecords(messages: List<LlmMessage>): List<ContextRecord> =
    messages.mapIndexed { index, message ->
        when (message) {
            is LlmMessage.System -> ContextRecord(index, "system", message.content, 0L)
            is LlmMessage.User -> ContextRecord(index, "user", message.content, 0L)
            is LlmMessage.Assistant -> {
                val callLines = message.toolCalls
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { "$TOOL_CALL_MARKER ${it.name}" }
                val content = if (callLines != null) {
                    if (message.content.isBlank()) callLines else message.content + "\n" + callLines
                } else {
                    message.content
                }
                ContextRecord(index, "assistant", content, 0L)
            }
            is LlmMessage.ToolResult -> ContextRecord(index, "tool", message.content, 0L)
        }
    }
