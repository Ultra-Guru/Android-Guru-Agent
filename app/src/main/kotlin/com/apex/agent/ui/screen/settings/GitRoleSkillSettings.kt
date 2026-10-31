package com.apex.agent.ui.screen.settings

import com.apex.agent.core.code.gitskill.GitRoleSkillEngine

/**
 * # 角色专属技能开关 —— AgentSettings 操作助手
 *
 * 与 AgentRole.kt 里的角色操作助手（withRoleUpserted 等）同款定位：
 * 纯副本语义 + 内置语义守护，UI 与 ViewModel 只管调用。
 *
 * 存储口径（见 AgentSettings.roleSkillToggles 字段文档）：
 * - 关闭 → 写 `skillId to false`；
 * - 开启 → **移除键**（缺席 = 默认开）—— 稀疏存储让「默认全开」
 *   在升级新增技能时天然保持，无需迁移逻辑。
 */

/**
 * 翻转一个角色专属技能的开关（副本语义，无副作用）。
 *
 * 未知 skillId 静默忽略（目录演进后设置里残留的键不炸，与
 * [GitRoleSkillEngine.withToggled] 同一口径）。
 */
fun AgentSettings.withRoleSkillToggled(skillId: String, enabled: Boolean): AgentSettings =
    copy(
        roleSkillToggles = GitRoleSkillEngine.withToggled(roleSkillToggles, skillId, enabled)
    )

/** 当前某技能是否启用（缺席 = 默认开；见 GitRoleSkillEngine.isEnabled）。 */
fun AgentSettings.roleSkillEnabled(skillId: String): Boolean =
    GitRoleSkillEngine.byId(skillId)?.let { GitRoleSkillEngine.isEnabled(it, roleSkillToggles) }
        ?: false
