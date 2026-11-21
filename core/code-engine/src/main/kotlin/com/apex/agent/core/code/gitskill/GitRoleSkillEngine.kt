package com.apex.agent.core.code.gitskill

/**
 * # Git 角色技能引擎 —— 选择 / 开关 / 注入的代码化决策面
 *
 * 用户规格三件事的落点：
 * 1. **「他可看情况选择使用哪个」** → [matchScores]：任务文本 × 触发词表
 *    的确定性匹配（中英不敏感子串），给模型一份带理由的推荐清单；
 * 2. **「模板工坊可逐个开关，默认全开」** → [isEnabled]：开关存
 *    AgentSettings.roleSkillToggles（Map<skillId, Boolean>），
 *    **缺席 = 开**（默认全开语义；false 才是显式关闭）；
 * 3. **注入** → [definitionHintFor]：启用技能的紧凑目录拼进 Git 角色
 *    definition（引擎人设通道，随角色热切换 —— 与专属知识库同款管线）。
 *
 * 纯 JVM 无 Android 依赖（core:code-engine），app 层只做设置读写与 UI。
 */
object GitRoleSkillEngine {

    /** 全量 9 套技能（本地域 1-4 + 跨仓/发布域 5-9，目录声明序即展示序）。 */
    val ALL: List<GitRoleSkill> =
        GitRoleSkillCatalog.LOCAL_WORKFLOW_SKILLS + GitRoleSkillCatalogCrossRepo.CROSS_REPO_SKILLS

    /** id → 技能索引（构造一次；id 唯一性由 [GitRoleSkillCatalogTest] 锁定）。 */
    private val byId: Map<String, GitRoleSkill> = ALL.associateBy { it.id }

    /** 按 id 查（未知 id → null，设置里残留的已删技能键静默降级）。 */
    fun byId(id: String): GitRoleSkill? = byId[id]

    // ═══ 归属判定 ═══

    /**
     * 技能是否归属某角色：Git 内置角色，或**「另存为」副本**（副本 id 是
     * role_<ts> 但 roleDefinition 原样携带 Git 定义段前缀 —— 双判据让
     * 技能跟随用户的定制副本，而不用脆弱的 id 列表）。
     */
    fun skillsApplyToRole(roleId: String, roleDefinition: String): Boolean =
        roleId == GitRoleSkillCatalog.GIT_ROLE_ID ||
            roleDefinition.trim().startsWith(GitRoleSkillCatalog.GIT_ROLE_DEFINITION_PREFIX)

    // ═══ 开关（默认全开）═══

    /**
     * 单技能开关判定：toggles 里显式 false 才关，**缺席 = 开**（默认全开）。
     *
     * @param toggles AgentSettings.roleSkillToggles 快照。
     */
    fun isEnabled(skill: GitRoleSkill, toggles: Map<String, Boolean>): Boolean =
        toggles[skill.id] ?: true

    /** 当前启用集（目录序保持稳定，UI 与注入同源）。 */
    fun enabledSkills(toggles: Map<String, Boolean>): List<GitRoleSkill> =
        ALL.filter { isEnabled(it, toggles) }

    /**
     * 开关翻转的设置副本（Map 拷贝语义，无副作用）：
     * 关 → 写 false；开 → **移除键**（回到默认开，而不是写 true ——
     * 让「默认全开」在存储形态上就是稀疏的，升级后新增技能天然全开）。
     */
    fun withToggled(toggles: Map<String, Boolean>, skillId: String, enabled: Boolean): Map<String, Boolean> {
        if (byId(skillId) == null) return toggles  // 未知技能 id 静默忽略
        return if (enabled) toggles - skillId else toggles + (skillId to false)
    }

    // ═══ 选择（「看情况用哪个」）═══

    /**
     * 任务文本 → 技能推荐度清单（分数降序，零分剔除）。
     *
     * 打分口径（确定性）：
     * - 每个命中触发词 +1.0（小写子串匹配，中英同权）；
     * - 多词短语（含空格）命中 +0.5 额外（短语比单词更特异）；
     * - **启用状态参与过滤**：已关闭的技能不进推荐（工坊开关的第一效果）。
     *
     * 返回空 = 任务与 Git 技能域无关（模型走角色通用能力）。
     */
    fun matchScores(taskText: String, toggles: Map<String, Boolean> = emptyMap()): List<GitSkillMatch> {
        if (taskText.isBlank()) return emptyList()
        val text = taskText.lowercase()
        return enabledSkills(toggles).mapNotNull { skill ->
            val hits = skill.triggers.filter { text.contains(it) }
            if (hits.isEmpty()) return@mapNotNull null
            val phraseBonus = hits.count { it.contains(' ') } * 0.5
            GitSkillMatch(skill = skill, score = hits.size + phraseBonus, hits = hits)
        }.sortedWith(
            compareByDescending<GitSkillMatch> { it.score }
                .thenBy { it.skill.id }  // 同分稳定序（可复现）
        )
    }

    // ═══ 注入段 ═══

    /**
     * 启用技能目录 → Git 角色 definition 追加段（人设通道注入）。
     *
     * 注入形态（紧凑目录，不是全文 runbook —— 渐进披露）：
     * - 目录行：`emoji id — 摘要（触发：…）`，模型按任务就近选择；
     * - 被选中技能的完整 runbook 由 [renderRunbook] 在需要时展开
     *   （工具化入口按需取全文，上下文预算友好）；
     * - 安全护栏逐条下发（硬约束不渐进 —— 破坏性操作的批准线必须在场）。
     *
     * 空启用集 → 空串（用户全关 = 不注入，角色回到纯定义段）。
     */
    fun definitionHintFor(enabled: List<GitRoleSkill>): String {
        if (enabled.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            append("[Git 专属技能·按需取用] 你装配了 ${enabled.size} 套 Git 专属技能（阶段化 runbook，含命令模板与逐阶段验证）：")
            enabled.forEachIndexed { i, skill ->
                val triggers = skill.triggers.take(4).joinToString("/")
                appendLine("${i + 1}) ${skill.emoji} ${skill.id} — ${skill.summary}（任务含「$triggers」类词时优先用）")
            }
            appendLine("选择纪律：${GitRoleSkillCatalog.LOCAL_WORKFLOW_SKILLS[2].whenToUse}")
            appendLine("安全红线（全程有效）：")
            enabled.flatMap { it.guards }.distinct().take(8).forEach { appendLine("- $it") }
            append("（每套技能的完整阶段/命令/验证模板已随技能档案提供；执行时按阶段走并在每阶段自验。）")
        }
    }

    /**
     * 单技能完整 runbook 渲染（选中技能的全文形态；注入/展示两用）。
     *
     * 结构：描述 → 逐阶段（目标/命令/验证）→ 护栏 → 适用判据。
     */
    fun renderRunbook(skill: GitRoleSkill): String = buildString {
        appendLine("### ${skill.emoji} ${skill.name}（${skill.id}）")
        appendLine(skill.description)
        appendLine()
        skill.phases.forEachIndexed { i, phase ->
            appendLine("阶段${i + 1} · ${phase.name} —— ${phase.goal}")
            phase.commands.forEach { appendLine("  $it") }
            appendLine("  验证：${phase.verify}")
        }
        appendLine("护栏：")
        skill.guards.forEach { appendLine("- $it") }
        append("适用判据：${skill.whenToUse}")
    }
}
