package com.apex.agent.core.code.gitskill

/**
 * # Git 角色专属技能 —— 数据模型
 *
 * 用户规格：Git 角色模板装配一套专属 skills（高级工作流/跨仓补丁/
 * Token 安全/发布编排），「他可看情况选择使用哪个」；模板工坊里点击
 * Git 角色可逐个开关，**默认全开**。
 *
 * ## 为什么是独立目录而不是装进 SkillRegistry
 *
 * - SkillRegistry 的技能是**全局**的（per-scope agent/coding 启停），
 *   没有「角色归属」语义 —— 9 套 Git 技能装进去会泄漏给 Kotlin/前端等
 *   所有角色，违背「Git 角色专属」；
 * - 开关语义不同：市场技能默认关（装≠开），角色专属技能默认**全开**
 *   （用户规格），开关面在模板工坊的角色详情里而非市场页；
 * - 注入通道不同：技能目录经 [GitRoleSkillEngine.definitionHintFor]
 *   拼进角色 definition（引擎人设通道，随角色热切换），不走斜杠菜单。
 *
 * ## 「代码化」的体现
 *
 * 每套技能不是一段提示词，而是结构化的**阶段化 runbook**：
 * - [GitRoleSkill.phases]：阶段 → 目标 → 可执行命令模板 → 验证方式，
 *   注入给模型的是「按阶段执行 + 每阶段自验」的纪律，不是散文方法论；
 * - [GitRoleSkill.triggers]：触发关键词表（代码匹配，任务文本命中即
 *   推荐该技能 —— 模型「看情况选择」的确定性入口）；
 * - [GitRoleSkill.guards]：安全护栏（不可逆操作强制确认等），作为
 *   硬约束随技能一起注入。
 *
 * @param id           稳定标识（kebab-case，与上游技能名对齐）。
 * @param name         显示名。
 * @param emoji        列表徽标。
 * @param summary      一句话摘要（工坊开关行副标题）。
 * @param description  完整描述（能力边界：管什么/不管什么）。
 * @param sourceRepo   上游仓库（用户指定的社区源；null = 本仓原生设计）。
 * @param sourceNote   上游归属说明（工坊详情展示，尊重社区来源）。
 * @param triggers     触发关键词（小写子串匹配；命中即推荐）。
 * @param phases       阶段化 runbook（见 [GitSkillPhase]）。
 * @param guards       安全护栏（硬约束，注入时随技能下发）。
 * @param whenToUse    适用/不适用判据（帮模型在多技能间做选择）。
 */
data class GitRoleSkill(
    val id: String,
    val name: String,
    val emoji: String,
    val summary: String,
    val description: String,
    val sourceRepo: String? = null,
    val sourceNote: String = "",
    val triggers: List<String> = emptyList(),
    val phases: List<GitSkillPhase> = emptyList(),
    val guards: List<String> = emptyList(),
    val whenToUse: String = ""
)

/**
 * 技能的一个执行阶段（runbook 的最小单元）。
 *
 * 「阶段」是代码化的核心：每个阶段自带**验证方式**（verify），模型执行
 * 后必须自验通过才进下一阶段 —— 没有验证的步骤链只是提示词，有验证的
 * 才是管线。
 *
 * @param name     阶段名（短；注入段里逐条列出）。
 * @param goal     本阶段要达成什么（一句话，模型对齐目标用）。
 * @param commands 命令模板（真实可执行的 git/gh/CLI 序列；`<占位符>`
 *   表示按任务填充的参数）。
 * @param verify   验证方式（命令或判据；不通过不进下一阶段）。
 */
data class GitSkillPhase(
    val name: String,
    val goal: String,
    val commands: List<String> = emptyList(),
    val verify: String
)

/**
 * 技能命中评分（任务文本 → 技能推荐度）。
 *
 * @param skill 命中的技能。
 * @param score 分数（每个命中关键词 +1；乘以短语权重 —— 见
 *   [GitRoleSkillEngine.matchScores] 的口径）。
 * @param hits  命中的关键词清单（透明化：为什么推荐这套技能）。
 */
data class GitSkillMatch(
    val skill: GitRoleSkill,
    val score: Double,
    val hits: List<String>
)
