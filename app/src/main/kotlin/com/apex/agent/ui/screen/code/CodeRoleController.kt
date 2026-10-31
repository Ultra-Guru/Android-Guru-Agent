package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.gitskill.GitRoleSkillEngine
import com.apex.agent.ui.screen.settings.AgentRole
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.screen.settings.activeCodingRole
import com.apex.agent.ui.screen.settings.codingRoles
import com.apex.agent.ui.screen.settings.withCodingRoleActivated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * # Code Role Controller — Coding 专家模板控制器（v6）
 *
 * 用户规格：「coding 模式增加一些重要的 agent 模板 —— 置顶全栈、Git
 * 角色、Android 专家、每门语言一个专家」。模板数据在
 * [AgentRole.CODING_EXPERTS]（内置不落盘）；本控制器负责：
 *
 * - 角色列表/激活角色的 StateFlow（设置层 agentSettings 派生，含用户
 *   自定义角色跨模式复用）；
 * - 选择即持久化（codeActiveRoleId）→ 设置流回灌 → 引擎人设通道
 *   （CodeEngineFacade.updateRolePersona，双引擎同步）—— 下一轮请求
 *   生效，无需重启；
 * - 启动即应用一次当前激活角色（VM 构造后首次组合时接线）。
 *
 * 独立文件（God-file 预算纪律：CodeViewModel 只减不增，模式同
 * CodeLongTaskCenterOps 的 internal 扩展拆分——本类经 VM 的 lazy
 * 属性暴露，CodeScreen 直取）。
 *
 * v1.8.0：[knowledgeBaseService] —— 角色绑定了专属知识库时，
 * applyToEngine 把检索指引（must_read → index/search → read）追加进
 * Role definition，随引擎人设通道下发。
 *
 * v1.9：Git 角色专属技能（用户规格 9 套阶段化 runbook）—— 归属角色
 * （含「另存为」副本）时，启用技能的紧凑目录 + 安全红线同样追加进
 * Role definition；开关（AgentSettings.roleSkillToggles）变化也走
 * 人设热重下（distinctUntilChangedBy 的键扩了 roleSkillToggles ——
 * 模板工坊里拨开关，下一轮请求即生效，无需重启）。
 */
class CodeRoleController(
    private val settingsRepository: SettingsRepository,
    private val engineAccessor: () -> com.apex.agent.core.code.CodeEngineFacade?,
    private val knowledgeBaseService: com.apex.agent.knowledge.KnowledgeBaseService,
    scope: CoroutineScope
) {

    /** 全量 Coding 角色列表（内置专家置顶 + 自定义接续）。 */
    val roles: StateFlow<List<AgentRole>> = settingsRepository.agentSettings
        .map { it.codingRoles() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前激活角色（悬空 → 全栈置顶兜底）。 */
    val active: StateFlow<AgentRole> = settingsRepository.agentSettings
        .map { it.activeCodingRole() }
        .stateIn(scope, SharingStarted.Eagerly, AgentRole.CODING_EXPERTS.first())

    init {
        // 设置流 → 引擎人设（启动首帧与运行时热切换同一条通道）。
        // v1.9：键扩 roleSkillToggles —— 技能开关变化同样触发人设重下
        //（Git 技能目录拼在 definition 里，开关即时生效）。
        scope.launch {
            settingsRepository.agentSettings
                .distinctUntilChangedBy { it.codeActiveRoleId to it.roleSkillToggles }
                .collect { settings -> applyToEngine(settings.activeCodingRole(), settings) }
        }
    }

    /** 激活角色（模式行胶囊入口；持久化 + collector 负责引擎生效）。 */
    fun select(roleId: String) {
        settingsRepository.updateAgentSettings { withCodingRoleActivated(roleId) }
    }

    private suspend fun applyToEngine(role: AgentRole, settings: com.apex.agent.ui.screen.settings.AgentSettings) {
        // v2 多卷制：角色绑定的全部知识卷（可无限扩充）都写进 Role definition
        // —— 检索指引按卷 id 逐一给出（must_read → index/search → read）。
        val kbHint = knowledgeBaseService.roleDefinitionHintFor(
            knowledgeBaseService.volumesForRole(role.id)
        )
        engineAccessor()?.updateRolePersona(
            roleDefinition = role.roleDefinition + kbHint + gitSkillHintFor(role, settings),
            rolePrompt = role.systemPrompt.ifBlank { null }
        )
    }

    /**
     * v1.9 Git 角色专属技能注入段：归属角色（含副本）且存在启用技能时，
     * 拼紧凑目录 + 安全红线（完整 runbook 由 [GitRoleSkillEngine.renderRunbook]
     * 按需展开）。非 Git 角色 → 空串零影响。
     */
    private fun gitSkillHintFor(
        role: AgentRole,
        settings: com.apex.agent.ui.screen.settings.AgentSettings
    ): String {
        if (!GitRoleSkillEngine.skillsApplyToRole(role.id, role.roleDefinition)) return ""
        return GitRoleSkillEngine.definitionHintFor(
            GitRoleSkillEngine.enabledSkills(settings.roleSkillToggles)
        )
    }
}
