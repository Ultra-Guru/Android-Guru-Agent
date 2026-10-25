package com.apex.agent.ui.screen.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.platform.memory.work.MaintenanceScheduler
import com.apex.memkit.core.MemKit
import com.apex.memkit.graph.graph.MemKitGraphSource
import com.apex.memkit.graph.graph.MemoryGraphSource
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.ExperienceReport
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.store.clearFacts
import com.apex.memkit.core.store.removeFact
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 记忆可视化页 ViewModel —— 提供 Episode / 宏 / 节点检索与管理。
 *
 * 拆库后（apex-kit-memory）：数据面直读库门面 [MemKit]，本 VM 只做
 * 状态编排与轻量 UI 模型（旧版直连 Room store / DreamRenderer /
 * MemoryImmuneSystem / MemoryLedgerStore 四个单例的接线全部收编）。
 *
 * 记忆页 v2（写入面 + 知识面）：
 *  - 六项统计（会话 / 节点 / 宏 / 事实 / 实体 / 关系）；
 *  - 知识图谱快照（readGraph —— 列表视图的「知识图谱」分区数据源）；
 *  - 统一搜索（场景节点 + 聊天事实 + 知识实体 三轨命中）；
 *  - 手动写入口：[saveFactManually] / [teachExperience]（与 agent 写入
 *    工具同落点 —— MemKit.saveFact / consolidateExperience）。
 *  - 写入 / 删除后的图谱同步不由 VM 驱动：库侧 MemKitGraphSource 已接
 *    store 修订号信号，MemoryGraphState 自动软刷新（保位 / 保选中），
 *    宿主无需重复握手。
 *
 * 删除为破坏性操作，由 UI 二次确认后调用。维护周期（能量衰减 / 剪枝 /
 * 晶化 / 别名发现）与隔离区能力保留 UI 入口（维护本体住库
 * MacroMaintenance，经 [maintenanceNow] 前台触发）。
 * #219 隐私合规：「聊天记忆」分区直读库聊天事实账本，可查看 / 逐条删 /
 * 一键清空。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memKit: MemKit,
    private val maintenanceScheduler: MaintenanceScheduler,
    // 记忆页文案走资源（en/zh 双语）；VM 层非 Compose 场景按仓库惯例经
    // LanguageManager 取词（SettingsViewModel 同款模式）。
    private val languageManager: LanguageManager
) : ViewModel() {

    data class MemoryStats(
        val episodeCount: Int = 0,
        val nodeCount: Int = 0,
        val macroCount: Int = 0,
        val factCount: Int = 0,
        val entityCount: Int = 0,
        val relationCount: Int = 0
    )

    /** 知识图谱快照（MCP memory 语义的实体 + 三元组 —— 只读视图数据）。 */
    data class KnowledgeSnapshot(
        val entities: List<GraphEntity> = emptyList(),
        val relations: List<GraphRelation> = emptyList()
    )

    /**
     * 记忆图谱数据源（apex-kit-memory:memory-graph-ui 的端口）—— 直读
     * 库门面五轨快照。VM 持有一等来源而非 Composable 内构造：图谱状态
     * （缩放 / 选中 / 过滤）跨视图切换存活，切回列表再切回不丢探索现场。
     */
    val graphSource: MemoryGraphSource = MemKitGraphSource(memKit)

    private val _episodes = MutableStateFlow<List<EpisodeSummary>>(emptyList())
    val episodes: StateFlow<List<EpisodeSummary>> = _episodes.asStateFlow()

    private val _macros = MutableStateFlow<List<MacroSkill>>(emptyList())
    val macros: StateFlow<List<MacroSkill>> = _macros.asStateFlow()

    private val _stats = MutableStateFlow(MemoryStats())
    val stats: StateFlow<MemoryStats> = _stats.asStateFlow()

    private val _knowledge = MutableStateFlow(KnowledgeSnapshot())
    val knowledge: StateFlow<KnowledgeSnapshot> = _knowledge.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 统一搜索命中（事实 → 实体 → 场景节点，见 [buildUnifiedSearchResults]）。 */
    private val _searchResults = MutableStateFlow<List<MemorySearchHit>>(emptyList())
    val searchResults: StateFlow<List<MemorySearchHit>> = _searchResults.asStateFlow()

    /** 删除结果提示（如 "已删除 Episode xxx"），UI 消费后清空 */
    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    // #219：聊天记忆条目（画像 → 近况 → 里程碑；空观察实体不占位）。
    private val _chatMemory = MutableStateFlow<List<ChatMemoryEntry>>(emptyList())
    val chatMemory: StateFlow<List<ChatMemoryEntry>> = _chatMemory.asStateFlow()

    init {
        refresh()
        // P2-12（6-c）：搜索防抖——query 流经 debounce(250) + distinctUntilChanged
        // 统一触发检索（UI 调用点不变）。v2：三轨统一命中（节点检索走 store
        // 既有匹配；事实 / 实体在快照上本地匹配 —— 键盘连击不再打三次全量拉取）。
        viewModelScope.launch {
            _searchQuery
                .debounce(250L)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                    } else {
                        _searchResults.value = runCatching {
                            val nodes = memKit.searchNodes(query, limit = 30)
                            val facts = withContext(Dispatchers.IO) { memKit.allFacts() }
                            val graph = withContext(Dispatchers.IO) { memKit.readGraph() }
                            buildUnifiedSearchResults(nodes, facts, graph.first, query)
                        }.getOrDefault(emptyList())
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val eps = memKit.recentEpisodes(limit = 50)
                val macs = memKit.topMacros(limit = 20)
                val counts = memKit.counts()
                val facts = withContext(Dispatchers.IO) { memKit.allFacts() }
                val graph = withContext(Dispatchers.IO) { memKit.readGraph() }
                _episodes.value = eps
                _macros.value = macs
                _chatMemory.value = facts.toChatMemoryEntries()
                _knowledge.value = KnowledgeSnapshot(graph.first, graph.second)
                _stats.value = MemoryStats(
                    episodeCount = eps.size,
                    nodeCount = counts.nodes,
                    macroCount = counts.macros,
                    factCount = facts.size,
                    entityCount = graph.first.size,
                    relationCount = graph.second.size
                )
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_msg_load_failed, e.message ?: ""
                )
            }
        }
    }

    fun onSearch(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun deleteEpisode(episodeId: String) {
        viewModelScope.launch {
            runCatching {
                val deleted = memKit.deleteEpisode(episodeId)
                if (deleted) {
                    _lastMessage.value = languageManager.getString(
                        R.string.memory_episode_msg_deleted, episodeId
                    )
                } else {
                    _lastMessage.value = languageManager.getString(
                        R.string.memory_episode_msg_missing, episodeId
                    )
                }
                refresh()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_msg_delete_failed, e.message ?: ""
                )
            }
        }
    }

    fun clearMessage() { _lastMessage.value = null }

    // ═══ 手动写入口（记忆页 v2 —— 与 agent 写入工具同落点）═══

    /**
     * 手动记一条长期事实（「添加记忆 → 记一条事实」）。
     *
     * 落点与 MemorySaveFact 工具完全一致：MemKit.saveFact（来源 MANUAL，
     * 幂等 —— 同内容重复保存自动归并强化）。
     */
    fun saveFactManually(content: String, kind: ChatFact.Kind, importance: Float) {
        val text = content.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    memKit.saveFact(text, kind, importance.coerceIn(0f, 1f))
                }
            }.onSuccess { result ->
                _lastMessage.value = if (result.added) {
                    languageManager.getString(R.string.memory_save_fact_msg_added)
                } else {
                    languageManager.getString(R.string.memory_save_fact_msg_reinforced)
                }
                refresh()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_write_msg_failed, e.message ?: ""
                )
            }
        }
    }

    /**
     * 教一次经验（「添加记忆 → 教一次经验」）。
     *
     * 落点与 MemoryConsolidateExperience 工具一致：consolidateExperience
     * 整包沉淀（摘要事实 + 教训 PROFILE 事实 + 实体 / 关系图谱）。用户
     * 亲述的教训与 agent 自整理的教训同池同权 —— 后续会话一并召回。
     */
    fun teachExperience(goal: String, outcome: String, success: Boolean, lessons: List<String>) {
        val g = goal.trim()
        val o = outcome.trim()
        if (g.isEmpty() || o.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    memKit.consolidateExperience(
                        ExperienceReport(goal = g, outcome = o, success = success, lessons = lessons)
                    )
                }
            }.onSuccess { result ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_teach_msg_done, result.totalWrites
                )
                refresh()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_write_msg_failed, e.message ?: ""
                )
            }
        }
    }

    // ═══ 记忆健康：维护周期 + 隔离区（原「梦境整理 / 免疫隔离」入口）═══

    /** 隔离区规模（被隔离的可疑 UI 指纹条数；0 = 正常态）。 */
    private val _quarantinedCount = MutableStateFlow(0)
    val quarantinedCount: StateFlow<Int> = _quarantinedCount.asStateFlow()

    /** 维护整理进行中。 */
    private val _maintenanceRunning = MutableStateFlow(false)
    val maintenanceRunning: StateFlow<Boolean> = _maintenanceRunning.asStateFlow()

    init { refreshQuarantineCount() }

    fun refreshQuarantineCount() {
        viewModelScope.launch {
            _quarantinedCount.value = runCatching { memKit.quarantineCount() }.getOrDefault(0)
        }
    }

    /**
     * 立即维护整理：衰减 + 修剪 + 晶化 + 别名发现（后台执行，完成后刷新全部数据）。
     * 周期调度由 ApexApp 的 MaintenanceScheduler 负责（WorkManager KEEP 幂等）。
     */
    fun maintenanceNow() {
        if (_maintenanceRunning.value) return
        _maintenanceRunning.value = true
        viewModelScope.launch {
            val report = runCatching { memKit.runMaintenanceNow() }
            _maintenanceRunning.value = false
            _lastMessage.value = when {
                report.isFailure ->
                    languageManager.getString(
                        R.string.memory_dream_msg_failed,
                        report.exceptionOrNull()?.message ?: ""
                    )

                report.getOrThrow().errors.isNotEmpty() ->
                    languageManager.getString(
                        R.string.memory_dream_msg_errors,
                        report.getOrThrow().errors.size,
                        report.getOrThrow().errors.first().take(80)
                    )

                else -> languageManager.getString(
                    R.string.memory_dream_msg_done, report.getOrThrow().describe()
                )
            }
            refresh()
            refreshQuarantineCount()
        }
    }

    /** 清除隔离名单（App 更新后可重新评估）。 */
    fun clearQuarantine() {
        viewModelScope.launch {
            runCatching { memKit.clearQuarantine() }
                .onSuccess {
                    _quarantinedCount.value = 0
                    _lastMessage.value = languageManager.getString(R.string.memory_quarantine_msg_cleared)
                }
                .onFailure {
                    _lastMessage.value = languageManager.getString(
                        R.string.memory_quarantine_msg_failed, it.message ?: ""
                    )
                }
        }
    }

    // ═══ 聊天记忆（#219：三类自动沉淀事实）═══

    /** 刷新聊天记忆快照（账本内存快照，删除/清空后的回刷也走这里）。 */
    private fun refreshChatMemory() {
        viewModelScope.launch {
            runCatching {
                val facts = withContext(Dispatchers.IO) { memKit.allFacts() }
                _chatMemory.value = facts.toChatMemoryEntries()
                _stats.value = _stats.value.copy(factCount = facts.size)
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_chat_msg_failed, e.message ?: ""
                )
            }
        }
    }

    /** 删除单条聊天记忆（#219 逐条删除；UI 二次确认后调用）。 */
    fun deleteChatMemoryRecord(recordId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { memKit.store.removeFact(recordId) }
            }.onSuccess { removed ->
                _lastMessage.value = if (removed) {
                    languageManager.getString(R.string.memory_chat_msg_deleted)
                } else {
                    // 幂等兜底：条目已被并发删除（如基调替换竞态）
                    languageManager.getString(R.string.memory_chat_msg_missing)
                }
                refreshChatMemory()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_chat_msg_failed, e.message ?: ""
                )
            }
        }
    }

    /**
     * 一键清空聊天记忆（#219；UI 二次确认后调用）。
     *
     * 语义边界：只清自动沉淀的三类记忆（画像 / 近况 / 里程碑）；
     * memory MCP 图谱里模型显式建的主题实体不属于自动聊天记忆，
     * 不在此清空范围。
     */
    fun clearChatMemory() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    memKit.store.clearFacts(
                        setOf(ChatFact.Kind.PROFILE, ChatFact.Kind.STATE, ChatFact.Kind.MILESTONE)
                    )
                }
            }.onSuccess { removed ->
                _lastMessage.value = if (removed > 0) {
                    languageManager.getString(R.string.memory_chat_msg_cleared, removed)
                } else {
                    languageManager.getString(R.string.memory_chat_msg_clear_empty)
                }
                refreshChatMemory()
            }.onFailure { e ->
                _lastMessage.value = languageManager.getString(
                    R.string.memory_chat_msg_failed, e.message ?: ""
                )
            }
        }
    }
}
