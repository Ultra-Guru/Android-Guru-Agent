package com.apex.agent.ui.screen.memory.profile

import com.apex.agent.ui.screen.code.session.CodeSessionStore
import com.apex.agent.ui.screen.code.session.StorableCodeMessage
import com.apex.memkit.core.ChatModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * # 用户画像蒸馏器 —— 「从编码对话提炼」的实现
 *
 * 管线：编码会话存档（CodeSessionStore，跨工作区）→ 对话转写组装 →
 * 蒸馏 LLM（ChatModel 端口：SUMMARY 角色便宜模型 + 低温）→ 防御式
 * JSON 解析（剥围栏 / 花括号平衡块）→ 写入门禁落账（UserProfileStore.apply）。
 *
 * 与聊天记忆蒸馏（memKit.chat，对话内容层）的分工：那边沉淀「发生过
 * 什么」（事实 / 近况 / 里程碑），这边提炼「用户是谁」（五维长期特征）。
 *
 * 防御式口径：LLM 空响应 / 解析失败 / 异常一律折叠为 noSignal 报告，
 * 绝不向上抛 —— 蒸馏是增强路径，失败只损失一次提炼机会。
 */
class ProfileDistiller(
    private val chatModel: ChatModel,
    private val store: UserProfileStore,
    private val sessionStore: CodeSessionStore,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行一次全量蒸馏（记忆页按钮触发；IO + LLM 往返，调用方须在
     * 后台协程调用，VM 已切 Dispatchers.IO）。
     *
     * @return 落账报告；[DistillReport.noSignal]=true 表示无可提炼对话
     *   或模型无有效输出（VM 分别提示）
     */
    suspend fun distill(now: Long = System.currentTimeMillis()): DistillReport =
        withContext(Dispatchers.IO) {
            val transcript = buildTranscript()
            if (transcript == null) return@withContext DistillReport(noSignal = true)

            val existing = store.list()
            val user = buildUserMessage(transcript, existing)
            val raw = runCatching { chatModel.complete(DISTILL_SYSTEM, user) }.getOrNull()
            if (raw.isNullOrBlank()) return@withContext DistillReport(noSignal = true)

            val payload = parsePayload(raw) ?: return@withContext DistillReport(noSignal = true)
            if (payload.skip || payload.actions.isEmpty()) {
                return@withContext DistillReport(noSignal = true)
            }
            store.apply(payload.actions, now)
        }

    // ═══ 转写组装 ═══

    /**
     * 跨工作区组装编码对话转写：快照按 updatedAt 降序取最近
     * [MAX_SNAPSHOTS] 个有消息的；每快照取尾部 [MAX_MESSAGES_PER_SNAPSHOT]
     * 条（长会话保尾不保头，与快照记忆口径一致）；合并后截断
     * [TRANSCRIPT_CHAR_LIMIT]。
     *
     * @return null = 没有任何可提炼内容（无快照 / 全空）
     */
    private fun buildTranscript(): String? {
        val snapshots = sessionStore.listAll()
            .filter { it.messages.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SNAPSHOTS)
        if (snapshots.isEmpty()) return null

        val lines = mutableListOf<String>()
        snapshots.forEach { snapshot ->
            lines += "# 工作区会话（更新于 ${snapshot.updatedAt}）"
            lines += snapshot.messages
                .takeLast(MAX_MESSAGES_PER_SNAPSHOT)
                .map(::renderMessage)
                .filter { it.isNotBlank() }
        }
        val transcript = lines.joinToString("\n").trim()
        if (transcript.isBlank()) return null
        return if (transcript.length > TRANSCRIPT_CHAR_LIMIT) {
            transcript.take(TRANSCRIPT_CHAR_LIMIT) + "\n…（转写超长截断）"
        } else {
            transcript
        }
    }

    /** 单条消息 → 转写行（USER 保全文截断 / ASSISTANT 截短 / TOOL 一行信号）。 */
    private fun renderMessage(message: StorableCodeMessage): String = when (message.role.trim().uppercase()) {
        "USER" -> "用户：${message.text.take(USER_TEXT_LIMIT).replace('\n', ' ').trim()}"
        "ASSISTANT" -> "助手：${message.text.take(ASSISTANT_TEXT_LIMIT).replace('\n', ' ').trim()}"
        "TOOL" -> {
            val name = message.toolName ?: "unknown"
            val outcome = if (message.toolSuccess) "成功" else "失败"
            "（工具：$name $outcome）"
        }
        else -> ""   // SYSTEM 噪声不入转写
    }.let { line -> if (line.isBlank()) "" else line }

    // ═══ 提示词 ═══

    /** LLM 输出防御式解析：剥围栏 + 平衡块 + 反序列化，任一失败 → null。 */
    internal fun parsePayload(raw: String): DistillPayload? {
        val objectText = extractJsonObject(raw) ?: return null
        return runCatching {
            json.decodeFromString(DistillPayload.serializer(), objectText)
        }.getOrNull()
    }

    private fun buildUserMessage(transcript: String, existing: List<UserProfileItem>): String {
        val existingLines = if (existing.isEmpty()) {
            "（暂无）"
        } else {
            existing.joinToString("\n") { item ->
                val aspect = ProfileAspect.fromKey(item.aspect)
                "- [${aspect?.key ?: item.aspect}] ${item.content}"
            }
        }
        return buildString {
            appendLine("# 编码会话转写（多工作区，截断展示）")
            appendLine(transcript)
            appendLine()
            appendLine("# 现有画像账本（参照 —— 同主题演进必须 update，不要重复 new）")
            appendLine(existingLines)
            appendLine()
            appendLine("# 要求")
            appendLine("按系统指令输出 JSON。")
        }
    }

    companion object {
        /** 参与蒸馏的最近快照数（跨工作区，按更新时间）。 */
        const val MAX_SNAPSHOTS = 5

        /** 单快照取尾部消息数。 */
        const val MAX_MESSAGES_PER_SNAPSHOT = 30

        /** 转写总字符上限（token 经济 —— 蒸馏走便宜模型）。 */
        const val TRANSCRIPT_CHAR_LIMIT = 12_000

        /** 单条消息文本截断。 */
        const val USER_TEXT_LIMIT = 600
        const val ASSISTANT_TEXT_LIMIT = 400

        /** 蒸馏系统提示词（写入门禁与输出协议 —— 与 Web 版已验证口径同源）。 */
        internal val DISTILL_SYSTEM = buildString {
            appendLine("你是用户画像提炼引擎。你只输出 JSON，不输出任何其他文字。")
            appendLine()
            appendLine("# 任务")
            appendLine("扫描编码模式对话，提炼【用户特异且长期稳定】的画像条目，维度：")
            appendLine("tech-stack 技术栈 / coding-style 编码风格 / preferences 偏好 / domains 关注领域 / habits 工作习惯")
            appendLine()
            appendLine("# 写入门禁")
            appendLine("- 不记：常识、一次性任务细节、未来计划、助手发言")
            appendLine("- 更新优先：同主题演进必须 update（old 逐字复制原文）；全新概念才 new")
            appendLine("- 每条 ≤ 40 字，第三人称；evidence 是来源对话摘要（≤ 20 字）")
            appendLine("- confidence 0.3~1.0；最多产出 8 个动作；无信号输出 {\"skip\":true}")
            appendLine()
            appendLine("# 输出格式（严格 JSON）")
            appendLine("{\"skip\":true}")
            appendLine("或")
            appendLine("{\"actions\":[{\"op\":\"new\",\"aspect\":\"tech-stack\",\"content\":\"…\",\"confidence\":0.8,\"evidence\":\"…\"},{\"op\":\"update\",\"old\":\"逐字复制原文\",\"content\":\"…\"}]}")
        }.trimEnd()
    }
}
