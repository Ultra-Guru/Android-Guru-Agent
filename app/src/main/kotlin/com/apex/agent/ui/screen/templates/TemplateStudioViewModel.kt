package com.apex.agent.ui.screen.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import com.apex.agent.core.engine.templates.TemplateScope
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * #197 模板工坊 ViewModel —— Agent / Coding 双层模板管理。
 *
 * - Agent 层：Agent 角色详细设置（人设卡编辑）+ agent 域提示词模板；
 * - Coding 层：coding 域提示词模板（代码评审/提交信息等）。
 * 两层完全独立（[TemplateScope] 分层持久化），与市场分级/斜杠过滤同源。
 */
@HiltViewModel
class TemplateStudioViewModel @Inject constructor(
    val settingsRepository: SettingsRepository,
    private val templateRegistry: PromptTemplateRegistry
) : ViewModel() {

    /** 当前页签（Agent 模板 / Coding 模板）。 */
    private val _tab = MutableStateFlow(TemplateScope.AGENT)
    val tab: StateFlow<TemplateScope> = _tab.asStateFlow()

    /** 当前页签下的模板清单（内置在前）。 */
    private val _templates = MutableStateFlow<List<PromptTemplate>>(emptyList())
    val templates: StateFlow<List<PromptTemplate>> = _templates.asStateFlow()

    /** 正在编辑的模板（null = 编辑器关闭；id 空 = 新建）。 */
    private val _editing = MutableStateFlow<PromptTemplate?>(null)
    val editing: StateFlow<PromptTemplate?> = _editing.asStateFlow()

    /** 一次性提示（保存/删除结果）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 注册表变更（同 VM 内的保存/删除/重播种）后刷新当前页签清单。
        // ★ 首载先播种内置模板（幂等：缺则补）再刷新 —— 修复全新安装模板
        //   工坊两个页签恒为空清单、切换「没有反应」的数据面根因：生产端
        //   此前从未播种（注册表 init 只建目录），内置 10 套模板从未出现。
        //   DI 模块也异步播种（其它消费者兜底），此处先 reseed 再取清单
        //   消除竞态 —— 首个页签打开即见内置模板。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { templateRegistry.reseedBuiltIns() }
                .onFailure { android.util.Log.w("TemplateStudio", "seed built-ins failed: ${it.message}") }
            refresh()
        }
    }

    fun selectTab(scope: TemplateScope) {
        _tab.value = scope
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val scope = _tab.value
            _templates.value = templateRegistry.list()
                .filter { it.scope == scope }
                .sortedWith(compareByDescending<PromptTemplate> { it.isBuiltIn }.thenBy { it.id })
        }
    }

    fun openEditor(template: PromptTemplate?) {
        // 新建：当前页签决定作用域（Agent 页签建 agent 模板，Coding 页签建 coding 模板）。
        _editing.value = template ?: PromptTemplate(
            id = "",
            name = "",
            description = "",
            category = if (_tab.value == TemplateScope.CODING) {
                com.apex.agent.core.engine.templates.TemplateCategory.CODING
            } else {
                com.apex.agent.core.engine.templates.TemplateCategory.CUSTOM
            },
            content = "",
            scope = _tab.value
        )
    }

    fun closeEditor() {
        _editing.value = null
    }

    fun saveTemplate(template: PromptTemplate) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val toSave = if (template.id.isBlank()) {
                    template.copy(id = PromptTemplate.newId(), createdAt = System.currentTimeMillis())
                } else template
                templateRegistry.save(toSave)
            }.fold(
                onSuccess = {
                    _message.value = "模板已保存：${template.name}"
                    _editing.value = null
                    refresh()
                },
                onFailure = { _message.value = "保存失败：${it.message}" }
            )
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = templateRegistry.delete(id)
            _message.value = if (ok) "模板已删除" else "内置模板不可删除（可复制后修改）"
            refresh()
        }
    }

    fun duplicateTemplate(template: PromptTemplate) {
        openEditor(
            template.copy(
                id = "",
                name = template.name + "（副本）",
                isBuiltIn = false
            )
        )
    }

    fun clearMessage() {
        _message.value = null
    }

    /** AgentSettings 快照（Agent 页签的角色分区消费）。 */
    val agentSettings = settingsRepository.agentSettings

    /** 角色激活/编辑落回设置仓库（生效链路与聊天顶栏选择器同源）。 */
    fun updateAgentSettings(transform: (com.apex.agent.ui.screen.settings.AgentSettings) ->
        com.apex.agent.ui.screen.settings.AgentSettings) {
        settingsRepository.updateAgentSettings { transform(this) }
    }
}
