package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.ChatFact

/**
 * 「聊天记忆」分区的一条展示条目（#219 隐私合规）。
 *
 * 数据源是 apex-memory-kit 的聊天事实账本（memory_kit/chat.json），
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
 */
fun List<ChatFact>.toChatMemoryEntries(): List<ChatMemoryEntry> =
    ChatFact.Kind.DISPLAY_ORDER.mapNotNull { kind ->
        filter { it.kind == kind }
            .takeIf { it.isNotEmpty() }
            ?.sortedBy { it.createdAt }
            ?.let { ChatMemoryEntry(kind, it) }
    }

/** 类别的用户可读名称（契约外类别兜底显示枚举名）。 */
fun ChatFact.Kind.displayLabel(): String = when (this) {
    ChatFact.Kind.PROFILE -> "用户画像"
    ChatFact.Kind.STATE -> "用户近况"
    ChatFact.Kind.MILESTONE -> "里程碑"
}

/** 来源的用户可读名称。 */
fun ChatFact.Source.displayLabel(): String = when (this) {
    ChatFact.Source.HEURISTIC -> "启发式"
    ChatFact.Source.DISTILLED -> "蒸馏"
    ChatFact.Source.MANUAL -> "手动"
    ChatFact.Source.MIGRATED -> "迁移"
}
