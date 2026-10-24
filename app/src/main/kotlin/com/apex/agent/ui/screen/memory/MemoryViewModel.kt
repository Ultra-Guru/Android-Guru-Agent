package com.apex.agent.ui.screen.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.platform.memory.work.MaintenanceScheduler
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.store.NodeDictEntry
import com.apex.memkit.core.store.allFacts
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
 * 只读为主；删除 Episode 为破坏性操作，由 UI 二次确认后调用 [deleteEpisode]。
 * 维护周期（能量衰减 / 剪枝 / 晶化 / 别名发现）与隔离区能力保留 UI 入口
 * （维护本体住库 MacroMaintenance，经 [maintenanceNow] 前台触发）。
 * #219 隐私合规：「聊天记忆」分区直读库聊天事实账本，可查看 / 逐条删 /
 * 一键清空。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memKit: MemKit,
    private val maintenanceScheduler: MaintenanceScheduler,
    // 记忆页新文案走资源（en/zh 双语）；VM 层非 Compose 场景按仓库惯例经
    // LanguageManager 取词（SettingsViewModel 同款模式）。
    private val languageManager: LanguageManager
) : ViewModel() {

    data class MemoryStats(
        val episodeCount: Int = 0,
        val nodeCount: Int = 0,
        val macroCount: Int = 0
    )

    private val _episodes = MutableStateFlow<List<EpisodeSummary>>(emptyList())
    val episodes: StateFlow<List<EpisodeSummary>> = _episodes.asStateFlow()

    private val _macros = MutableStateFlow<List<MacroSkill>>(emptyList())
    val macros: StateFlow<List<MacroSkill>> = _macros.asStateFlow()

    private val _stats = MutableStateFlow(MemoryStats())
    val stats: StateFlow<MemoryStats> = _stats.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<NodeDictEntry>>(emptyList())
    val searchResults: StateFlow<List<NodeDictEntry>> = _searchResults.asStateFlow()

    /** 删除结果提示（如 "已删除 Episode xxx"），UI 消费后清空 */
    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    // #219：聊天记忆条目（画像 → 近况 → 里程碑；空观察实体不占位）。
    private val _chatMemory = MutableStateFlow<List<ChatMemoryEntry>>(emptyList())
    val chatMemory: StateFlow<List<ChatMemoryEntry>> = _chatMemory.asStateFlow()

    init {
        refresh()
        // P2-12（6-c）：搜索防抖——query 流经 debounce(250) + distinctUntilChanged
        // 统一触发检索（UI 调用点不变）。
        viewModelScope.launch {
            _searchQuery
                .debounce(250L)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                    } else {
                        _searchResults.value = runCatching {
                            memKit.searchNodes(query, limit = 50)
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
                _episodes.value = eps
                _macros.value = macs
                _stats.value = MemoryStats(
                    episodeCount = eps.size,
                    nodeCount = counts.nodes,
                    macroCount = counts.macros
                )
            }.onFailure { e ->
                _lastMessage.value = "加载记忆失败：${e.message}"
            }
            // #219：聊天记忆独立加载 —— 轨迹库失败不拖累聊天画像区
            refreshChatMemory()
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
                    _lastMessage.value = "已删除 Episode $episodeId"
                } else {
                    _lastMessage.value = "删除失败：$episodeId 不存在"
                }
                refresh()
            }.onFailure { e ->
                _lastMessage.value = "删除失败：${e.message}"
            }
        }
    }

    fun clearMessage() { _lastMessage.value = null }

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
                    "维护整理失败：${report.exceptionOrNull()?.message ?: "未知错误"}"

                report.getOrThrow().errors.isNotEmpty() ->
                    "维护整理完成（${report.getOrThrow().errors.size} 项异常）：${report.getOrThrow().errors.first().take(80)}"

                else -> "维护整理完成：${report.getOrThrow().describe()}"
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
                    _lastMessage.value = "隔离区已清空"
                }
                .onFailure { _lastMessage.value = "清除隔离区失败：${it.message}" }
        }
    }

    // ═══ 聊天记忆（#219：三类自动沉淀事实）═══

    /** 刷新聊天记忆快照（账本内存快照，删除/清空后的回刷也走这里）。 */
    private fun refreshChatMemory() {
        viewModelScope.launch {
            runCatching {
                _chatMemory.value = withContext(Dispatchers.IO) {
                    memKit.store.allFacts().toChatMemoryEntries()
                }
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
