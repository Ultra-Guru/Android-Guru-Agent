package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage

/**
 * #827 — 历史图片退场（keep-last-K 策略，纯函数）。
 *
 * 问题：User 消息携带的 images 永久驻留 conversationHistory，每轮 ReAct
 * 请求组装时全量重发 —— 旧图每轮重传、重计费（VISION 路由对整段循环
 * 不退出），内存历史也无限驻留。压缩门对图片视而不见的另一半（估算
 * 盲区）由 [TokenEstimator.IMAGE_TOKEN_ESTIMATE] 修复。
 *
 * 策略：仅保留最近 K 个「携图轮」（含图片的 User 消息）的图片，更早的
 * 携图 User 消息以占位文本标注（**原文保留**，仅图片剥离），模型仍知道
 * 此处曾有附件但不为其付费。
 *
 * 纯函数、零副作用：输入列表与元素不被修改（[LlmMessage] 为不可变 data
 * class，替换即 copy）；无退场（携图轮 ≤ K）时原样返回**同一实例**，
 * 调用方可凭引用判等短路。
 */
object HistoryImageEvictor {

    /** 退场占位文本（附在原文末尾，告知模型此处曾有附件）。 */
    const val PLACEHOLDER = "[图片已省略：早前轮次附件]"

    /** 默认保留策略：最近 2 个携图轮。 */
    const val DEFAULT_KEEP_LAST_K = 2

    /**
     * @param history 当前历史（不被修改）
     * @param keepLastK 保留最近 K 个携图轮（≤ 0 = 全部退场）
     * @return 退场后的新历史；无退场时返回入参 history 同一实例（引用判等短路）
     */
    fun evict(history: List<LlmMessage>, keepLastK: Int = DEFAULT_KEEP_LAST_K): List<LlmMessage> {
        val imageTurnIndices = history.indices.filter {
            (history[it] as? LlmMessage.User)?.images?.isNotEmpty() == true
        }
        if (imageTurnIndices.size <= keepLastK) return history
        val protectedTurns = imageTurnIndices.takeLast(keepLastK).toSet()
        return history.mapIndexed { idx, msg ->
            val user = msg as? LlmMessage.User ?: return@mapIndexed msg
            if (idx in protectedTurns || user.images.isEmpty()) msg
            else user.copy(
                content = if (user.content.isBlank()) PLACEHOLDER
                else user.content + "\n" + PLACEHOLDER,
                images = emptyList()
            )
        }
    }
}
