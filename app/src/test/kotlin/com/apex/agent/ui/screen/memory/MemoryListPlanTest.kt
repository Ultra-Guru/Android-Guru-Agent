package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆页单列列表计划的纯逻辑单测（记忆页 v3）。
 *
 * 计划（[buildMemoryListPlan]）是列表视图的唯一内容来源：分区顺序 /
 * 搜索态替换 / 实体折叠 / 经历过滤 / 宏展开 / 分区锚点 / key 唯一性
 * 在此锁定。渲染层（Compose）不做 JVM 直测。
 */
class MemoryListPlanTest {

    // ═══ 测试数据工厂 ═══

    private fun fact(id: String, kind: ChatFact.Kind = ChatFact.Kind.PROFILE): ChatFact = ChatFact(
        id = id,
        content = "content-$id",
        kind = kind,
        source = ChatFact.Source.MANUAL,
        importance = 0.6f,
        credibility = 1f,
        createdAt = 100L,
        lastAccessedAt = 100L,
        accessCount = 0
    )

    private fun chatEntry(kind: ChatFact.Kind, vararg ids: String): ChatMemoryEntry =
        ChatMemoryEntry(kind, ids.map { fact(it, kind) })

    private fun entity(name: String): GraphEntity = GraphEntity(name = name, entityType = "app")

    private fun episode(
        id: String,
        status: EpisodeStatus = EpisodeStatus.SUCCEEDED,
    ): EpisodeSummary = EpisodeSummary(
        episodeId = id,
        goal = "goal-$id",
        appPackage = "com.example",
        status = status,
        startedAt = 1000L,
        finishedAt = 2000L,
        actionCount = 3,
        macroIds = emptyList(),
    )

    private fun macro(id: String): MacroSkill = MacroSkill(
        skillId = id,
        name = "macro-$id",
        goal = "goal-$id",
        appPackage = null,
        entryFingerprints = emptySet(),
        exitFingerprint = "fp",
        steps = emptyList(),
        stats = MacroStats(),
        sourceEpisodeId = null,
        createdAt = 100L,
    )

    private fun input(
        searchActive: Boolean = false,
        searchResults: List<MemorySearchHit> = emptyList(),
        chatEntries: List<ChatMemoryEntry> = listOf(chatEntry(ChatFact.Kind.PROFILE, "f1")),
        knowledgeEntities: List<GraphEntity> = listOf(entity("E1"), entity("E2")),
        knowledgeExpanded: Boolean = false,
        episodes: List<EpisodeSummary> = listOf(episode("ep1")),
        episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
        macros: List<MacroSkill> = listOf(macro("m1")),
        expandedMacros: Set<String> = emptySet(),
    ): MemoryListPlanInput = MemoryListPlanInput(
        searchActive = searchActive,
        searchResults = searchResults,
        chatEntries = chatEntries,
        knowledgeEntities = knowledgeEntities,
        knowledgeExpanded = knowledgeExpanded,
        episodes = episodes,
        episodeFilter = episodeFilter,
        macros = macros,
        expandedMacros = expandedMacros,
    )

    private fun hit(uid: String, type: MemorySearchHitType = MemorySearchHitType.FACT): MemorySearchHit =
        MemorySearchHit(type = type, title = "t-$uid", subtitle = null, jumpId = null, uid = uid)

    // ═══ 分区顺序与锚点 ═══

    @Test
    fun `normal plan keeps canonical section order with anchors at headers`() {
        val plan = buildMemoryListPlan(input())

        // 骨架：概览 → 健康 → 隐私 → 四分区（头 + 卡）→ 尾部
        assertEquals(MemoryListItem.Stats, plan.first())
        assertEquals(MemoryListItem.Health, plan[1])
        assertEquals(MemoryListItem.Privacy, plan[2])
        assertTrue(plan.last() is MemoryListItem.Footer)

        val anchors = plan.sectionAnchors()
        assertEquals(4, anchors.size)
        assertEquals(MemoryListItem.ChatHeader, plan[anchors.getValue(MemorySection.CHAT)])
        assertEquals(MemoryListItem.KnowledgeHeader, plan[anchors.getValue(MemorySection.KNOWLEDGE)])
        assertEquals(MemoryListItem.EpisodesHeader, plan[anchors.getValue(MemorySection.EPISODES)])
        assertEquals(MemoryListItem.MacrosHeader, plan[anchors.getValue(MemorySection.MACROS)])

        // 分区序：聊天 < 知识 < 经历 < 宏
        assertTrue(anchors.getValue(MemorySection.CHAT) < anchors.getValue(MemorySection.KNOWLEDGE))
        assertTrue(anchors.getValue(MemorySection.KNOWLEDGE) < anchors.getValue(MemorySection.EPISODES))
        assertTrue(anchors.getValue(MemorySection.EPISODES) < anchors.getValue(MemorySection.MACROS))
    }

    @Test
    fun `all item keys are unique`() {
        val plan = buildMemoryListPlan(
            input(
                chatEntries = listOf(
                    chatEntry(ChatFact.Kind.PROFILE, "f1"),
                    chatEntry(ChatFact.Kind.STATE, "f2"),
                ),
                knowledgeEntities = (1..30).map { entity("E$it") },
                episodes = listOf(episode("ep1"), episode("ep2", EpisodeStatus.FAILED)),
                macros = listOf(macro("m1"), macro("m2")),
                expandedMacros = setOf("m1"),
            )
        )
        val keys = plan.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    // ═══ 搜索态整段替换 ═══

    @Test
    fun `search mode replaces sections with grouped hits and keeps overview`() {
        val plan = buildMemoryListPlan(
            input(
                searchActive = true,
                searchResults = listOf(
                    hit("h1", MemorySearchHitType.FACT),
                    hit("h2", MemorySearchHitType.FACT),
                    hit("h3", MemorySearchHitType.ENTITY),
                ),
            )
        )

        // 概览三件保留，分区内容整段替换
        assertEquals(MemoryListItem.Stats, plan[0])
        assertEquals(MemoryListItem.Health, plan[1])
        assertEquals(MemoryListItem.Privacy, plan[2])
        assertEquals(MemoryListItem.SearchSummary(3), plan[3])
        assertTrue(plan.none { it is MemoryListItem.ChatEntry || it is MemoryListItem.EpisodeCard || it is MemoryListItem.MacroCard })

        // 分组标签插在各组首条之前（枚举序：事实 → 实体）
        assertEquals(MemoryListItem.SearchGroupLabel(MemorySearchHitType.FACT), plan[4])
        assertEquals(MemoryListItem.SearchHit(hit("h1")), plan[5])
        assertEquals(MemoryListItem.SearchGroupLabel(MemorySearchHitType.ENTITY), plan[7])
    }

    // ═══ 知识实体折叠 ═══

    @Test
    fun `knowledge entities collapse beyond limit with expand row`() {
        val entities = (1..(KNOWLEDGE_COLLAPSED_ENTITY_LIMIT + 5)).map { entity("E$it") }

        val collapsed = buildMemoryListPlan(input(knowledgeEntities = entities))
        val collapsedCards = collapsed.filterIsInstance<MemoryListItem.KnowledgeEntity>()
        assertEquals(KNOWLEDGE_COLLAPSED_ENTITY_LIMIT, collapsedCards.size)
        val expandRow = collapsed.filterIsInstance<MemoryListItem.KnowledgeExpand>().single()
        assertEquals(entities.size, expandRow.totalCount)
        assertEquals(false, expandRow.expanded)

        val expanded = buildMemoryListPlan(input(knowledgeEntities = entities, knowledgeExpanded = true))
        assertEquals(entities.size, expanded.filterIsInstance<MemoryListItem.KnowledgeEntity>().size)
        val expandedRow = expanded.filterIsInstance<MemoryListItem.KnowledgeExpand>().single()
        assertEquals(true, expandedRow.expanded)
    }

    @Test
    fun `knowledge entities under limit have no expand row`() {
        val plan = buildMemoryListPlan(input(knowledgeEntities = (1..KNOWLEDGE_COLLAPSED_ENTITY_LIMIT).map { entity("E$it") }))
        assertTrue(plan.none { it is MemoryListItem.KnowledgeExpand })
        assertEquals(KNOWLEDGE_COLLAPSED_ENTITY_LIMIT, plan.filterIsInstance<MemoryListItem.KnowledgeEntity>().size)
    }

    // ═══ 经历状态过滤 ═══

    @Test
    fun `episode filter row carries per-status counts`() {
        val plan = buildMemoryListPlan(
            input(
                episodes = listOf(
                    episode("a", EpisodeStatus.RUNNING),
                    episode("b", EpisodeStatus.SUCCEEDED),
                    episode("c", EpisodeStatus.SUCCEEDED),
                    episode("d", EpisodeStatus.FAILED),
                ),
            )
        )
        val row = plan.filterIsInstance<MemoryListItem.EpisodeFilterRow>().single()
        assertEquals(EpisodeFilter.ALL, row.current)
        assertEquals(1, row.running)
        assertEquals(2, row.succeeded)
        assertEquals(1, row.failed)
        assertEquals(0, row.abandoned)
    }

    @Test
    fun `episode filter narrows cards to matching status`() {
        val episodes = listOf(
            episode("a", EpisodeStatus.RUNNING),
            episode("b", EpisodeStatus.SUCCEEDED),
            episode("c", EpisodeStatus.FAILED),
        )
        assertEquals(3, filterEpisodes(episodes, EpisodeFilter.ALL).size)
        assertEquals(listOf("a"), filterEpisodes(episodes, EpisodeFilter.RUNNING).map { it.episodeId })
        assertEquals(listOf("b"), filterEpisodes(episodes, EpisodeFilter.SUCCEEDED).map { it.episodeId })
        assertEquals(listOf("c"), filterEpisodes(episodes, EpisodeFilter.FAILED).map { it.episodeId })
        assertEquals(0, filterEpisodes(episodes, EpisodeFilter.ABANDONED).size)

        val plan = buildMemoryListPlan(input(episodes = episodes, episodeFilter = EpisodeFilter.SUCCEEDED))
        assertEquals(1, plan.filterIsInstance<MemoryListItem.EpisodeCard>().size)
    }

    // ═══ 宏展开集 ═══

    @Test
    fun `macro expansion follows the expanded id set`() {
        val macros = listOf(macro("m1"), macro("m2"))
        val collapsed = buildMemoryListPlan(input(macros = macros))
        assertTrue(collapsed.filterIsInstance<MemoryListItem.MacroCard>().none { it.expanded })

        val expanded = buildMemoryListPlan(input(macros = macros, expandedMacros = setOf("m2")))
        val cards = expanded.filterIsInstance<MemoryListItem.MacroCard>()
        assertEquals(listOf(false, true), cards.map { it.expanded })
    }

    // ═══ 宏步骤类型映射 ═══

    @Test
    fun `macro action maps to display kind`() {
        assertEquals(MacroStepKind.TAP, MacroAction.Tap(1, 2).stepKind())
        assertEquals(MacroStepKind.SWIPE, MacroAction.Swipe(0, 0, 1, 1).stepKind())
        assertEquals(MacroStepKind.INPUT, MacroAction.Input("hi").stepKind())
        assertEquals(MacroStepKind.BACK, MacroAction.Back.stepKind())
        assertEquals(MacroStepKind.HOME, MacroAction.Home.stepKind())
    }

    // ═══ 当前分区推导 ═══

    @Test
    fun `current section follows first visible index against anchors`() {
        val plan = buildMemoryListPlan(input())
        val anchors = plan.sectionAnchors()
        val chat = anchors.getValue(MemorySection.CHAT)
        val knowledge = anchors.getValue(MemorySection.KNOWLEDGE)

        // 概览区（首可见在第一个分区头之前）→ null（「全部」芯片高亮）
        assertNull(currentSection(0, anchors))
        assertNull(currentSection(chat - 1, anchors))
        assertEquals(MemorySection.CHAT, currentSection(chat, anchors))
        assertEquals(MemorySection.KNOWLEDGE, currentSection(knowledge, anchors))
        assertEquals(MemorySection.MACROS, currentSection(plan.lastIndex, anchors))
    }

    // ═══ 宏步骤上限 ═══

    @Test
    fun `visible macro steps cap at limit`() {
        val big = macro("m1").let { base ->
            base.copy(
                steps = (1..(MACRO_EXPANDED_STEP_LIMIT + 3)).map {
                    com.apex.memkit.core.model.MacroStep(
                        action = MacroAction.Tap(it, it),
                        anchorFingerprints = emptySet(),
                        nextAnchorFingerprints = emptySet(),
                    )
                }
            )
        }
        assertEquals(MACRO_EXPANDED_STEP_LIMIT, visibleMacroSteps(big).size)
    }
}
