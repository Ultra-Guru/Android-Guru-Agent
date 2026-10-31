package com.apex.agent.ui.screen.templates

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.experience.ExperienceScenario
import com.apex.agent.core.code.experience.RoleExperience
import com.apex.agent.core.code.gitskill.GitRoleSkill
import com.apex.agent.core.code.gitskill.GitRoleSkillEngine
import com.apex.agent.ui.icons.AgentRoleAvatarIcon
import com.apex.agent.ui.screen.settings.AgentRole
import java.text.DateFormat
import java.util.Date

/**
 * # v1.9 角色详情面板 —— 模板工坊「点击具体 agent 模板查看」的落点
 *
 * 用户规格两条：
 * 1. 「角色的经验，用户可在模板工坊点击具体 agent 模板查看」—— 经验卡
 *    列表（场景/工具链/结果/下次注意四要素 + 强化信号）；
 * 2. 「Git 角色专属技能，点击后可具体设置开启与关闭，默认全开」——
 *    技能开关区（含上游来源标注）。
 *
 * 面板内保留「激活此角色」入口（行点击从「直接激活」改为「打开详情」，
 * 激活动作沉到面板里 —— 查看与激活不再互相打断）。
 *
 * ## 长列表纪律
 *
 * 经验卡 ≤ 单角色上限（120）且滚动容器封顶（UI 规约 max-h + overflow），
 * 技能 9 行恒定 —— ModalBottomSheet 内容纵向滚动 + 工具链横向滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoleDetailSheet(
    role: AgentRole,
    experiences: List<RoleExperience>,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
    onDeleteExperience: (String) -> Unit,
    onClearExperiences: () -> Unit,
    skillToggles: Map<String, Boolean>,
    onToggleSkill: (String, Boolean) -> Unit
) {
    // 「清空经验」二次确认（破坏性操作守卫，与仓库既有删除弹层同款纪律）。
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ═══ 角色头：品牌图标 + 名称 + 内置锁 + 摘要 ═══
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentRoleAvatarIcon(role = role, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = role.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (role.isBuiltIn) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.settings_roles_builtin),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (role.roleDefinition.isNotBlank()) {
                        Text(
                            text = role.roleDefinition,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ═══ 激活入口（行点击已改为打开详情，激活沉到面板）═══
            if (isActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.templates_role_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.templates_role_activate))
                }
            }

            // ═══ 经验卡区（跨会话积累的结构化经验）═══
            RoleExperienceSection(
                experiences = experiences,
                onDelete = onDeleteExperience,
                onClear = { confirmClear = true }
            )

            // ═══ Git 专属技能区（默认全开，可逐个关闭）═══
            if (GitRoleSkillEngine.skillsApplyToRole(role.id, role.roleDefinition)) {
                RoleSkillSection(
                    skills = GitRoleSkillEngine.ALL,
                    toggles = skillToggles,
                    onToggle = onToggleSkill
                )
            }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.templates_role_experience_clear_confirm_title)) },
            text = { Text(stringResource(R.string.templates_role_experience_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearExperiences()
                    confirmClear = false
                }) { Text(stringResource(R.string.templates_role_experience_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.templates_cancel))
                }
            }
        )
    }
}

/** 经验卡区：标题 + 计数 + 清空入口 + 卡片列表（空态文案）。 */
@Composable
private fun RoleExperienceSection(
    experiences: List<RoleExperience>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.templates_role_experience_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.templates_role_experience_count, experiences.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (experiences.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.templates_role_experience_clear))
                }
            }
        }
        if (experiences.isEmpty()) {
            Text(
                text = stringResource(R.string.templates_role_experience_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 长列表纪律：滚动容器封顶（≤ 12 卡可见高度），不撑爆面板。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                experiences.asReversed().forEach { exp ->  // 新经验置顶（时间倒序展示）
                    RoleExperienceCard(experience = exp, onDelete = { onDelete(exp.id) })
                }
            }
        }
    }
}

/** 单张经验卡：场景/工具链/结果/下次注意四要素 + 强化信号。 */
@Composable
private fun RoleExperienceCard(
    experience: RoleExperience,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 场景行 + 结果徽标 + 删除
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = experience.scenario,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = outcomeBadge(experience.outcome),
                    style = MaterialTheme.typography.labelSmall,
                    color = outcomeColor(experience.outcome),
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDelete,
                    // UI-012：48dp 触区红线（与全仓输入行按钮统一 48 系）。
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.templates_role_delete_exp),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            // 场景标签 + 强化信号
            if (experience.scenarioTags.isNotEmpty() || experience.hitCount > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    experience.scenarioTags.forEach { key ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    ExperienceScenario.fromKey(key)?.zhLabel ?: key,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                    if (experience.hitCount > 1) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(
                                        R.string.templates_role_experience_hits, experience.hitCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
            // 工具链（真序列，横向滚动）
            if (experience.toolSequence.isNotEmpty()) {
                Text(
                    text = experience.toolSequence.joinToString(" → "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 下次注意（核心四要素）
            if (experience.notes.isNotBlank()) {
                Text(
                    text = stringResource(R.string.templates_role_experience_notes, experience.notes),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // 元信息行：工作区 + 计数 + 时间
            Text(
                text = buildString {
                    if (experience.workspaceName.isNotBlank()) append(experience.workspaceName)
                    append(" · ")
                    append(stringResource(R.string.templates_role_experience_meta, experience.iterations, experience.toolCalls))
                    if (experience.createdAt > 0) {
                        append(" · ")
                        append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(experience.createdAt)))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Git 专属技能区：逐技能开关（默认全开）+ 上游来源标注。 */
@Composable
private fun RoleSkillSection(
    skills: List<GitRoleSkill>,
    toggles: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.templates_role_skills_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.templates_role_skills_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        skills.forEach { skill ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${skill.emoji} ${skill.name}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = skill.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (skill.sourceRepo != null) {
                            Text(
                                text = stringResource(R.string.templates_role_skill_source, skill.sourceRepo!!),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Switch(
                        checked = GitRoleSkillEngine.isEnabled(skill, toggles),
                        onCheckedChange = { onToggle(skill.id, it) }
                    )
                }
            }
        }
    }
}

/** 结果徽标文案（经验卡口径：COMPLETED/FAILED/ABORTED）。 */
private fun outcomeBadge(outcome: String): String = when (outcome) {
    "COMPLETED" -> "✓"
    "FAILED" -> "✗"
    else -> "◐"
}

@Composable
private fun outcomeColor(outcome: String): androidx.compose.ui.graphics.Color =
    when (outcome) {
        "COMPLETED" -> MaterialTheme.colorScheme.primary
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
