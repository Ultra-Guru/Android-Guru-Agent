package com.apex.agent.ui.screen.memory

import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.MacroAction
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.MacroStep

/**
 * 记忆页单列列表计划（记忆页 v3）—— 列表视图从「多个嵌套 LazyColumn
 * 各自内滚」重构为一整支可滚动 LazyColumn，本文件把「列表里有什么、
 * 按什么顺序、折叠到什么程度」收敛为纯函数产物：
 *
 *  - [MemoryListItem]：条目 sealed 层次（概览 / 健康 / 隐私 / 四分区头 /
 *    各类卡片 / 搜索态条目 / 尾部留白）—— LazyColumn 直接按计划渲染；
 *  - [buildMemoryListPlan]：数据 + UI 状态 → 计划（分区顺序 / 实体折叠
 *    上限 / 经历状态过滤 / 宏展开集 / 搜索态整段替换）；
 *  - [sectionAnchors]：分区 → 列表下标（分区导航芯片 animateScrollToItem
 *    的目标），由计划扫描而来 —— 下标不手写，增删条目不漂移。
 *
 * 纯 JVM 可测（无 Compose / Hilt 依赖）：分区序 / 锚点 / 折叠 / 过滤 /
 * key 唯一性在 MemoryListPlanTest 直测。
 */

// ═══ 分区与过滤（UI 状态侧的枚举契约）══════════════════════════════

/** 列表分区锚点（分区导航芯片的目标；全部 = 回顶，不在枚举内）。 */
enum class MemorySection { CHAT, KNOWLEDGE, EPISODES, MACROS }

/** 经历分区状态过滤（芯片行；ALL = 不筛）。 */
enum class EpisodeFilter { ALL, RUNNING, SUCCEEDED, FAILED, ABANDONED }

/** 实体分区折叠态下最多直出的实体卡数（超出走「显示全部」展开行）。 */
const val KNOWLEDGE_COLLAPSED_ENTITY_LIMIT = 24

/** 宏卡展开时最多直出的步骤行数（超出折叠为「还有 N 步」提示）。 */
const val MACRO_EXPANDED_STEP_LIMIT = 20

// ═══ 计划条目 ═════════════════════════════════════════════════════

/** 单列列表的一个渲染条目（[key] 供 LazyColumn items key 用）。 */
sealed interface MemoryListItem {

    val key: String

    /** 概览六卡 + 上次刷新行。 */
    data object Stats : MemoryListItem {
        override val key: String get() = "stats"
    }

    /** 记忆健康卡（梦境巩固 + 免疫隔离）。 */
    data object Health : MemoryListItem {
        override val key: String get() = "health"
    }

    /** 隐私告知条。 */
    data object Privacy : MemoryListItem {
        override val key: String get() = "privacy"
    }

    /** 搜索命中摘要行（命中总数）。 */
    data class SearchSummary(val total: Int) : MemoryListItem {
        override val key: String get() = "search_summary"
    }

    /** 搜索分组标签（事实 / 实体 / 场景节点 —— 分组内首条前插一行）。 */
    data class SearchGroupLabel(val type: MemorySearchHitType) : MemoryListItem {
        override val key: String get() = "search_group_${type.name}"
    }

    /** 单条搜索命中卡。 */
    data class SearchHit(val hit: MemorySearchHit) : MemoryListItem {
        override val key: String get() = "search_hit_${hit.uid}"
    }

    /** 分区头：聊天记忆（[MemorySection.CHAT] 锚点）。 */
    data object ChatHeader : MemoryListItem {
        override val key: String get() = "header_chat"
    }

    /** 聊天记忆类别卡（一类一卡：画像 / 近况 / 里程碑）。 */
    data class ChatEntry(val entry: ChatMemoryEntry) : MemoryListItem {
        override val key: String get() = "chat_${entry.kind.name}"
    }

    /** 分区头：知识图谱（[MemorySection.KNOWLEDGE] 锚点）。 */
    data object KnowledgeHeader : MemoryListItem {
        override val key: String get() = "header_knowledge"
    }

    /** 知识实体卡（已按中心度排序）。 */
    data class KnowledgeEntity(val entity: GraphEntity) : MemoryListItem {
        override val key: String get() = "entity_${entity.name}"
    }

    /** 「显示全部 / 收起常用」切换行（实体数超折叠上限时出现）。 */
    data class KnowledgeExpand(val totalCount: Int, val expanded: Boolean) : MemoryListItem {
        override val key: String get() = "knowledge_expand"
    }

    /** 分区头：近期经历（[MemorySection.EPISODES] 锚点）。 */
    data object EpisodesHeader : MemoryListItem {
        override val key: String get() = "header_episodes"
    }

    /** 经历状态过滤芯片行（带各状态计数）。 */
    data class EpisodeFilterRow(
        val current: EpisodeFilter,
        val running: Int,
        val succeeded: Int,
        val failed: Int,
        val abandoned: Int,
    ) : MemoryListItem {
        override val key: String get() = "episode_filter"
    }

    /** 经历卡。 */
    data class EpisodeCard(val episode: EpisodeSummary) : MemoryListItem {
        override val key: String get() = "episode_${episode.episodeId}"
    }

    /** 分区头：高频宏（[MemorySection.MACROS] 锚点）。 */
    data object MacrosHeader : MemoryListItem {
        override val key: String get() = "header_macros"
    }

    /** 宏技能卡（expanded 由宿主的展开集驱动）。 */
    data class MacroCard(val macro: MacroSkill, val expanded: Boolean) : MemoryListItem {
        override val key: String get() = "macro_${macro.skillId}"
    }

    /** 尾部留白。 */
    data object Footer : MemoryListItem {
        override val key: String get() = "footer"
    }
}

// ═══ 计划构建 ═════════════════════════════════════════════════════

/** 列表视图的完整输入（数据快照 + 列表级 UI 状态）。 */
data class MemoryListPlanInput(
    /** 搜索态（query 非空）—— 计划整段替换为命中列表（概览/健康/隐私保留）。 */
    val searchActive: Boolean,
    val searchResults: List<MemorySearchHit>,
    /** 聊天记忆类别卡（空类别已被 toChatMemoryEntries 剔除）。 */
    val chatEntries: List<ChatMemoryEntry>,
    /** 知识实体（宿主已按关联中心度降序排好）。 */
    val knowledgeEntities: List<GraphEntity>,
    val knowledgeExpanded: Boolean,
    val episodes: List<EpisodeSummary>,
    val episodeFilter: EpisodeFilter,
    val macros: List<MacroSkill>,
    /** 展开中的宏技能 id 集合。 */
    val expandedMacros: Set<String>,
)

/**
 * 输入 → 单列列表计划（纯函数）。
 *
 * 非搜索态分区顺序：概览 → 健康 → 隐私 → 聊天记忆 → 知识图谱 →
 * 近期经历 → 高频宏 → 尾部。搜索态：概览 → 健康 → 隐私 → 命中摘要 →
 * 按三轨分组的命中卡（沿用 [MemorySearchHitType] 枚举序）。
 */
fun buildMemoryListPlan(input: MemoryListPlanInput): List<MemoryListItem> {
    if (input.searchActive) return buildSearchPlan(input)

    val plan = mutableListOf<MemoryListItem>()
    plan += MemoryListItem.Stats
    plan += MemoryListItem.Health
    plan += MemoryListItem.Privacy

    // ── 聊天记忆 ──
    plan += MemoryListItem.ChatHeader
    plan += input.chatEntries.map { MemoryListItem.ChatEntry(it) }

    // ── 知识图谱（中心度序 + 折叠上限）──
    plan += MemoryListItem.KnowledgeHeader
    val entities = input.knowledgeEntities
    if (entities.size > KNOWLEDGE_COLLAPSED_ENTITY_LIMIT) {
        if (input.knowledgeExpanded) {
            plan += entities.map { MemoryListItem.KnowledgeEntity(it) }
            plan += MemoryListItem.KnowledgeExpand(entities.size, expanded = true)
        } else {
            plan += entities.take(KNOWLEDGE_COLLAPSED_ENTITY_LIMIT)
                .map { MemoryListItem.KnowledgeEntity(it) }
            plan += MemoryListItem.KnowledgeExpand(entities.size, expanded = false)
        }
    } else {
        plan += entities.map { MemoryListItem.KnowledgeEntity(it) }
    }

    // ── 近期经历（状态过滤 + 计数行）──
    plan += MemoryListItem.EpisodesHeader
    plan += MemoryListItem.EpisodeFilterRow(
        current = input.episodeFilter,
        running = input.episodes.count { it.status == EpisodeStatus.RUNNING },
        succeeded = input.episodes.count { it.status == EpisodeStatus.SUCCEEDED },
        failed = input.episodes.count { it.status == EpisodeStatus.FAILED },
        abandoned = input.episodes.count { it.status == EpisodeStatus.ABANDONED },
    )
    plan += filterEpisodes(input.episodes, input.episodeFilter)
        .map { MemoryListItem.EpisodeCard(it) }

    // ── 高频宏（展开集决定卡内详情形）──
    plan += MemoryListItem.MacrosHeader
    plan += input.macros.map { MemoryListItem.MacroCard(it, expanded = it.skillId in input.expandedMacros) }

    plan += MemoryListItem.Footer
    return plan
}

/** 搜索态计划：概览保留 + 命中摘要 + 三轨分组命中卡。 */
private fun buildSearchPlan(input: MemoryListPlanInput): List<MemoryListItem> {
    val plan = mutableListOf<MemoryListItem>()
    plan += MemoryListItem.Stats
    plan += MemoryListItem.Health
    plan += MemoryListItem.Privacy
    plan += MemoryListItem.SearchSummary(input.searchResults.size)
    input.searchResults.groupBy { it.type }.forEach { (type, hits) ->
        plan += MemoryListItem.SearchGroupLabel(type)
        plan += hits.map { MemoryListItem.SearchHit(it) }
    }
    return plan
}

/** 经历状态过滤（纯函数 —— 芯片行与列表共用同一口径）。 */
fun filterEpisodes(
    episodes: List<EpisodeSummary>,
    filter: EpisodeFilter,
): List<EpisodeSummary> = when (filter) {
    EpisodeFilter.ALL -> episodes
    else -> episodes.filter { it.status.name == filter.name }
}

/**
 * 计划 → 分区锚点（分区头在列表中的下标）。
 *
 * 导航芯片 animateScrollToItem 的目标即此；「全部」芯片固定滚回 0
 * （概览卡），不在映射内。找不到分区头（如空数据被裁剪）时该分区
 * 不入映射 —— 芯片降级为不可点。
 */
fun List<MemoryListItem>.sectionAnchors(): Map<MemorySection, Int> = buildMap {
    this@sectionAnchors.forEachIndexed { index, item ->
        when (item) {
            MemoryListItem.ChatHeader -> put(MemorySection.CHAT, index)
            MemoryListItem.KnowledgeHeader -> put(MemorySection.KNOWLEDGE, index)
            MemoryListItem.EpisodesHeader -> put(MemorySection.EPISODES, index)
            MemoryListItem.MacrosHeader -> put(MemorySection.MACROS, index)
            else -> Unit
        }
    }
}

// ═══ 宏步骤展示（纯映射 —— i18n 标签在 UI 侧）════════════════════

/** 宏动作的展示类型（UI 按此取本地化标签；详形沿用库侧 notation()）。 */
enum class MacroStepKind { TAP, SWIPE, INPUT, BACK, HOME }

/** 动作 → 展示类型（纯函数，JVM 直测）。 */
fun MacroAction.stepKind(): MacroStepKind = when (this) {
    is MacroAction.Tap -> MacroStepKind.TAP
    is MacroAction.Swipe -> MacroStepKind.SWIPE
    is MacroAction.Input -> MacroStepKind.INPUT
    is MacroAction.Back -> MacroStepKind.BACK
    is MacroAction.Home -> MacroStepKind.HOME
}

/** 宏展开时实际展示的步骤数（上限外折叠为「还有 N 步」提示）。 */
fun visibleMacroSteps(macro: MacroSkill): List<MacroStep> =
    macro.steps.take(MACRO_EXPANDED_STEP_LIMIT)
