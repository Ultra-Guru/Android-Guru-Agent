package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.ChatFact

/**
 * 「聊天记忆」分区的一条展示条目（#219 隐私合规）。
 *
 * 数据源是 apex-kit-memory 的聊天事实账本（memory_kit/chat.json），
 * 由 ChatEngine 在对话中自动沉淀三类记忆（画像 / 近况 / 里程碑，
 * 含「近期偏低落」这类敏感情绪判断），本模型把账本记录按类别分组
 * 呈现给记忆页。
 *
 * @param kind 类别（画像 / 近况 / 里程碑，展示序见 [ChatFact.Kind.DISPLAY_ORDER]）
 * @param records 该类别当前的全部记录（createdAt 升序）
 */
data class ChatMemoryEntry(
    val kind: ChatFact.Kind,
    val records: List<ChatFact>
)

/**
 * 账本全量记录 → 聊天记忆条目（纯函数，独立成文件便于 JVM 直测）。
 *
 * 按契约顺序（画像 → 近况 → 里程碑）分组排列；空类别不占位 ——
 * 「还没记住什么」由分区空态文案表达，避免清空后残留三张空卡。
 *
 * 类别 / 来源的用户可读名称住 strings_core.xml（memory_chat_badge_* /
 * memory_chat_source_*）—— 原硬编码中文 displayLabel 已在记忆页 v2
 * 的 i18n 修复中移除（英文环境不再中英混杂）。
 */
fun List<ChatFact>.toChatMemoryEntries(): List<ChatMemoryEntry> =
    ChatFact.Kind.DISPLAY_ORDER.mapNotNull { kind ->
        filter { it.kind == kind }
            .takeIf { it.isNotEmpty() }
            ?.sortedBy { it.createdAt }
            ?.let { ChatMemoryEntry(kind, it) }
    }
