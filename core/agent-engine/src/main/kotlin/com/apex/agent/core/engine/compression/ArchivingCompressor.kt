package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.builtin.context.SessionContextArchive

/**
 * # 归档压缩装饰器 —— 「压缩 ≠ 销毁」（#396 上下文自主检索）
 *
 * 旧链路里压缩器把消息从活跃历史裁掉后，`memory.save(compressed)` 覆写
 * 持久化记忆，被裁剪的消息**永久消失**——上下文检索工具（ContextSearch /
 * ContextFetch）的数据源只剩压缩后的残影，"AI 想回去看原文"无从谈起。
 *
 * 本装饰器包在任意 [ContextCompressor] 外面，做三件零侵入的事：
 *
 * 1. **压缩前快照** —— 记下 `before`；
 * 2. **差集归档** —— 压缩后凡是不再完整存在于历史中的消息（被移除、
 *    或被就地截断的工具输出）以**原始形态**进 [archive]；完好保留的
 *    消息不重复归档。就地截断的消息两次判不等 → 原文进归档，恰好实现
 *    "归档里永远留着未截断版本"；
 * 3. **检索单（upsert 一条 System 注记）** —— 在头部系统块之后插入一条
 *    `[CONTEXT ARCHIVE]` 注记，告诉模型有多少历史被裁剪、去哪找回来。
 *    注记全局唯一（重复压缩时原位更新计数），常态约 50 token——比把
 *    全部历史塞回上下文便宜三个数量级。
 *
 * 失败纪律：归档与注记任何异常都不阻断压缩主流程（压缩本身已成功），
 * 留痕降级继续。
 *
 * 会话生命周期：归档清空由引擎侧负责（`clearHistory()` /
 * `restoreHistory()` 调 [SessionContextArchive.clear]），本装饰器只写不清。
 */
class ArchivingCompressor(
    private val delegate: ContextCompressor,
    private val archive: SessionContextArchive
) : ContextCompressor {

    override fun needsCompression(
        history: List<LlmMessage>,
        maxTokens: Int,
        threshold: Float
    ): Boolean = delegate.needsCompression(history, maxTokens, threshold)

    override fun updateContextWindow(tokens: Int) {
        delegate.updateContextWindow(tokens)
    }

    override suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int
    ): CompressionReport {
        val before = history.toList()
        val report = delegate.compress(history, preserveRecent)

        // 归档 + 注记：压缩已成功，此处失败只降级不回滚。
        runCatching { archivePruned(before, history) }
            .onFailure { e ->
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ArchivingCompressor",
                    "压缩归档失败（压缩本身已成功，被裁剪消息本次未进归档）: ${e.message}"
                )
            }
        return report
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 差集归档：`before` 中不再完整存在于 `after` 的消息进归档，
     * 然后按归档总量 upsert 检索单注记。
     */
    private fun archivePruned(before: List<LlmMessage>, after: MutableList<LlmMessage>) {
        val pruned = before.filter { msg -> after.none { it == msg } }
        if (pruned.isNotEmpty()) {
            archive.appendArchived(pruned)
        }
        upsertArchiveNote(after, archive.size())
    }

    /**
     * 检索单注记：插在头部连续 System 块（系统提示词 + 压缩摘要）之后；
     * 已有同前缀注记则原位替换（多次压缩不堆叠，计数保持最新）。
     * 归档为空时移除旧注记（新会话清档后的残余防御）。
     */
    private fun upsertArchiveNote(history: MutableList<LlmMessage>, archivedTotal: Int) {
        val existing = history.indexOfFirst {
            it is LlmMessage.System && it.content.startsWith(NOTE_PREFIX)
        }
        if (archivedTotal <= 0) {
            if (existing >= 0) history.removeAt(existing)
            return
        }
        val note = LlmMessage.System(
            NOTE_PREFIX +
                "$archivedTotal earlier message(s) were compressed out of the live context " +
                "but preserved verbatim in the archive. When you need the original wording " +
                "(user requests, tool outputs, decisions), run ContextSearch to locate it " +
                "('a#N' archived / '#N' live) and ContextFetch with that locator to read " +
                "the full text. Do not guess from the summary alone."
        )
        if (existing >= 0) {
            history[existing] = note
        } else {
            var insertAt = 0
            while (insertAt < history.size && history[insertAt] is LlmMessage.System) insertAt++
            history.add(insertAt, note)
        }
    }

    private companion object {
        /** 注记前缀（upsert 的幂等锚点）。 */
        const val NOTE_PREFIX = "[CONTEXT ARCHIVE] "
    }
}
