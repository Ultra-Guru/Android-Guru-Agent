package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.ChatFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天记忆分区纯映射逻辑单测（#219 v2 账本化 → apex-memory-kit 迁移版）。
 *
 * MemoryViewModel 的加载/清空路径经 [toChatMemoryEntries] 把账本全量
 * 事实按类别分组；VM 本身依赖 Hilt/Context 等安卓件，不做 JVM 直测，
 * 分组规则在此覆盖（数据源：memory_kit/chat.json 的 ChatFact 账本）。
 */
class ChatMemoryModelsTest {

    private fun fact(
        content: String,
        kind: ChatFact.Kind,
        createdAt: Long
    ): ChatFact = ChatFact(
        id = "${kind}_$createdAt",
        content = content,
        kind = kind,
        source = ChatFact.Source.HEURISTIC,
        importance = 0.5f,
        credibility = 1f,
        createdAt = createdAt,
        lastAccessedAt = createdAt,
        accessCount = 1
    )

    @Test
    fun `groups facts by kind in canonical order`() {
        // 乱序 + 混入记录：输出必须是 画像 → 近况 → 里程碑，组内 createdAt 升序
        val entries = listOf(
            fact("下周三面试", ChatFact.Kind.MILESTONE, 300L),
            fact("我叫张三", ChatFact.Kind.PROFILE, 100L),
            fact("近期情绪基调:偏低落", ChatFact.Kind.STATE, 200L),
            fact("我在学 React", ChatFact.Kind.PROFILE, 50L)
        ).toChatMemoryEntries()

        assertEquals(
            listOf(ChatFact.Kind.PROFILE, ChatFact.Kind.STATE, ChatFact.Kind.MILESTONE),
            entries.map { it.kind }
        )
        // 组内按 createdAt 升序（时间序呈现）
        assertEquals(listOf("我在学 React", "我叫张三"), entries[0].records.map { it.content })
        assertEquals(listOf("近期情绪基调:偏低落"), entries[1].records.map { it.content })
        assertEquals(listOf("下周三面试"), entries[2].records.map { it.content })
    }

    @Test
    fun `skips empty kinds so cleared memory shows the empty state`() {
        // 一键清空后账本为空 —— 不应渲染三张空卡
        val entries = emptyList<ChatFact>().toChatMemoryEntries()

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `display label maps kind to contract entity name`() {
        assertEquals("用户画像", ChatFact.Kind.PROFILE.displayLabel())
        assertEquals("用户近况", ChatFact.Kind.STATE.displayLabel())
        assertEquals("里程碑", ChatFact.Kind.MILESTONE.displayLabel())
    }
}
