package com.apex.agent.ui.screen.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import com.apex.agent.core.engine.templates.TemplateScope
import com.apex.agent.core.engine.templates.runtime.TemplateActivation
import com.apex.agent.core.engine.templates.runtime.TemplateRuntime
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「使用模板」对话框的状态机。
 *
 * @param template 目标模板（快照）
 * @param behaviorBound true = 已绑定代码行为（内置模板：分析附录可用）
 * @param manifestSummary 行为能力摘要（徽标 + 确定性说明；无行为为空）
 */
data class TemplateUseState(
    val template: PromptTemplate,
    val behaviorBound: Boolean,
    val manifestSummary: String,
    /** 变量当前值（对话框表单编辑中）。 */
    val variableValues: Map<String, String>,
    /** 生成结果（null = 未生成）。 */
    val generatedPrompt: String?,
    /** 生成时的注入说明（附录里带了几条确定性发现等）。 */
    val generationNotes: List<String>,
    /** 生成失败原因（缺必填等）。 */
    val error: String?
)

/**
 * #197 模板工坊 ViewModel —— Agent / Coding 双层模板管理 + 「使用模板」运行时接线。
 *
 * - Agent 层：Agent 角色详细设置（人设卡编辑）+ agent 域提示词模板；
 * - Coding 层：coding 域提示词模板（代码评审/提交信息等）；
 * - **使用模板（行为运行时）**：内置模板经 [TemplateRuntime] 走六阶段管线
 *   （确定性分析 → 附录注入），自定义模板走兜底行为（纯变量替换）——
 *   模板切换从「注入一段文本」升级为「装配一条分析管线」。
 */
@HiltViewModel
class TemplateStudioViewModel @Inject constructor(
    val settingsRepository: SettingsRepository,
    private val templateRegistry: PromptTemplateRegistry,
    private val templateRuntime: TemplateRuntime
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

    /** 「使用模板」对话框状态（null = 关闭）。 */
    private val _useState = MutableStateFlow<TemplateUseState?>(null)
    val useState: StateFlow<TemplateUseState?> = _useState.asStateFlow()

    /** 一次性提示（保存/删除/使用结果）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 注册表变更（同 VM 内的保存/删除/重播种）后刷新当前页签清单。
        // ★ 首载先播种内置模板（幂等：缺则补）再刷新 —— 修复全新安装模板
        //   工坊两个页签恒为空清单、切换「没有反应」的数据面根因。
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

    // ═══ 使用模板（行为运行时）═══

    /**
     * 打开「使用模板」：绑定行为能力摘要（内置模板显示徽标与确定性说明）。
     */
    fun openUse(template: PromptTemplate) {
        val manifest = templateRuntime.behaviorManifests()
            .firstOrNull { it.behaviorId == template.id }
        _useState.value = TemplateUseState(
            template = template,
            behaviorBound = manifest != null,
            manifestSummary = manifest?.let {
                "${it.displayName} · ${it.summary}"
            } ?: "自定义模板（变量替换 + 通用净化）",
            variableValues = template.variables.associate { variable ->
                variable.name to variable.defaultValue
            },
            generatedPrompt = null,
            generationNotes = emptyList(),
            error = null
        )
    }

    /** 对话框内修改变量值。 */
    fun updateUseVariable(name: String, value: String) {
        _useState.value = _useState.value?.let { state ->
            state.copy(
                variableValues = state.variableValues + (name to value),
                error = null
            )
        }
    }

    /** 关闭使用对话框。 */
    fun closeUse() {
        _useState.value = null
    }

    /**
     * 生成提示词：激活 → 组装（内置模板 = 六阶段管线 + 附录注入；
     * 自定义模板 = 兜底行为变量替换）。失败原因回填 error（不抛）。
     */
    fun generateUsePrompt() {
        val state = _useState.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val overrides = state.variableValues.filterValues { it.isNotBlank() }
            val activation: TemplateActivation = templateRuntime.activate(
                state.template.id,
                overrides
            )
            if (!activation.ok) {
                _useState.value = state.copy(
                    error = when (activation.failureReason) {
                        TemplateRuntime.REASON_MISSING_REQUIRED ->
                            "缺少必填变量：${activation.missingRequired.joinToString("、")}"
                        else -> "模板不存在或不可用"
                    }
                )
                return@launch
            }
            val outcome = templateRuntime.prepareTurn(activation)
            if (!outcome.ok) {
                _useState.value = state.copy(error = "组装失败：${outcome.failure ?: "未知原因"}")
                return@launch
            }
            templateRuntime.deactivate(activation)
            _useState.value = state.copy(
                generatedPrompt = outcome.request!!.prompt,
                generationNotes = outcome.request!!.enrichmentNotes,
                error = null
            )
        }
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
