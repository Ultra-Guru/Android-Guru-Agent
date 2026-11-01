package com.apex.agent.ui.screen.memory.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户画像模型纯函数单测（维度解析 / 分组展示 / 注入取条 / 注入段格式 /
 * LLM 输出容错提取）。
 */
class UserProfileModelsTest {

    private fun item(
        id: String,
        aspect: ProfileAspect,
        content: String,
        confidence: Float = 0.8f,
    ) = UserProfileItem(
        id = id,
        aspect = aspect.key,
        content = content,
        confidence = confidence
    )

    // ═══ 维度解析 ═══

    @Test
    fun `fromKey normalizes case and underscore`() {
        assertEquals(ProfileAspect.TECH_STACK, ProfileAspect.fromKey("tech-stack"))
        assertEquals(ProfileAspect.TECH_STACK, ProfileAspect.fromKey(" TECH_STACK "))
        assertEquals(ProfileAspect.CODING_STYLE, ProfileAspect.fromKey("Coding-Style"))
        assertNull(ProfileAspect.fromKey("unknown"))
        assertNull(ProfileAspect.fromKey(null))
        assertNull(ProfileAspect.fromKey(""))
    }

    // ═══ 分组展示 ═══

    @Test
    fun `toProfileAspectEntries groups in fixed order and sorts by confidence`() {
        val items = listOf(
            item("a", ProfileAspect.HABITS, "夜间编码", confidence = 0.4f),
            item("b", ProfileAspect.TECH_STACK, "Kotlin", confidence = 0.6f),
            item("c", ProfileAspect.TECH_STACK, "Compose", confidence = 0.9f),
        )
        val entries = items.toProfileAspectEntries()
        assertEquals(2, entries.size)
        // 固定五维序：TECH_STACK 在 HABITS 前
        assertEquals(ProfileAspect.TECH_STACK, entries[0].aspect)
        assertEquals(ProfileAspect.HABITS, entries[1].aspect)
        // 维度内按置信度降序
        assertEquals("Compose", entries[0].items[0].content)
        assertEquals("Kotlin", entries[0].items[1].content)
    }

    @Test
    fun `toProfileAspectEntries drops empty aspects`() {
        val entries = listOf(item("a", ProfileAspect.DOMAINS, "安卓")).toProfileAspectEntries()
        assertEquals(1, entries.size)
        assertEquals(ProfileAspect.DOMAINS, entries[0].aspect)
    }

    // ═══ 注入取条 ═══

    @Test
    fun `topForInjection takes top N by confidence`() {
        val items = (1..10).map { item("i$it", ProfileAspect.TECH_STACK, "c$it", confidence = it / 10f) }
        val top = items.topForInjection()
        assertEquals(PROFILE_INJECT_LIMIT, top.size)
        assertEquals(1.0f, top.first().confidence)
    }

    // ═══ 注入段格式 ═══

    @Test
    fun `toSystemContextSection formats with aspect label and confidence`() {
        val section = listOf(
            item("a", ProfileAspect.TECH_STACK, "主用 Kotlin", confidence = 0.9f)
        ).toSystemContextSection()
        assertTrue(section != null && section.contains("## 用户画像"))
        assertTrue(section.contains("- [技术栈] 主用 Kotlin（置信度 90%）"))
    }

    @Test
    fun `toSystemContextSection returns null for empty list`() {
        assertNull(emptyList<UserProfileItem>().toSystemContextSection())
    }

    // ═══ LLM 输出容错提取 ═══

    @Test
    fun `extractJsonObject strips code fences`() {
        val raw = "```json\n{\"skip\":true}\n```"
        assertEquals("{\"skip\":true}", extractJsonObject(raw))
    }

    @Test
    fun `extractJsonObject skips leading prose`() {
        val raw = "好的，结果如下：{\"actions\":[]}"
        assertEquals("{\"actions\":[]}", extractJsonObject(raw))
    }

    @Test
    fun `extractJsonObject balances braces inside strings`() {
        val raw = """{"content":"包含 } 花括号 { 的文本","op":"new"}"""
        assertEquals(raw, extractJsonObject(raw))
    }

    @Test
    fun `extractJsonObject returns null when unbalanced`() {
        assertNull(extractJsonObject("{\"actions\":[{\"op\":\"new\"}"))
        assertNull(extractJsonObject("完全没有 JSON"))
    }
}
