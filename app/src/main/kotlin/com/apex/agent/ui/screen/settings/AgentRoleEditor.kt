package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ 角色编辑器（Coding 专家角色的通用编辑对话框）═══
 *
 * Agent 聊天工位的角色管理分区（AgentRolesSection + 内置全能角色）已随
 * Agent 模式移除；本文件保留模板工坊 Coding 页签（CodingRolesSection）
 * 复用的编辑对话框与「另存为自定义副本」助手 —— 同一套编辑器/持久化
 * （withRoleUpserted），服务 Coding 工位的专家人设定制。
 */
/** 另存为自定义副本（内置角色的唯一定制路径；模板工坊 Coding 页签复用）。 */
internal fun AgentRole.duplicateForEditing(): AgentRole = copy(
    id = AgentRole.newId(),
    name = if (isBuiltIn) "$name " + "②" else name,
    isBuiltIn = false
)

/**
 * 角色编辑对话框：核心四字段（名字/称呼/角色定义/提示词）+ 更多自定义选项
 * （图标 / 语气风格 / 回复语言）。名字必填（空名保存按钮禁用 + 提示）。
 * 模板工坊 Coding 页签复用（internal），保存链路同源 withRoleUpserted。
 */
@Composable
internal fun AgentRoleEditorDialog(
    initial: AgentRole,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgentRole) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var userTitle by remember { mutableStateOf(initial.userTitle) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var roleDefinition by remember { mutableStateOf(initial.roleDefinition) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var style by remember { mutableStateOf(initial.style) }
    var replyLanguage by remember { mutableStateOf(initial.replyLanguage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.settings_roles_editor_new_title
                    else R.string.settings_roles_editor_edit_title
                )
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.settings_roles_field_name)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_name_desc)) },
                    isError = name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = userTitle,
                    onValueChange = { userTitle = it.take(30) },
                    label = { Text(stringResource(R.string.settings_roles_field_user_title)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_user_title_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(4) },
                    label = { Text(stringResource(R.string.settings_roles_field_emoji)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = roleDefinition,
                    onValueChange = { roleDefinition = it.take(600) },
                    label = { Text(stringResource(R.string.settings_roles_field_definition)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_definition_desc)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it.take(4000) },
                    label = { Text(stringResource(R.string.settings_roles_field_prompt)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_prompt_desc)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()
                Text(
                    stringResource(R.string.settings_roles_more_options),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                DropdownRow(
                    label = stringResource(R.string.settings_roles_field_style),
                    options = listOf(
                        "" to stringResource(R.string.settings_roles_style_default),
                        "professional" to stringResource(R.string.settings_roles_style_professional),
                        "friendly" to stringResource(R.string.settings_roles_style_friendly),
                        "humorous" to stringResource(R.string.settings_roles_style_humorous),
                        "concise" to stringResource(R.string.settings_roles_style_concise),
                    ),
                    selected = style,
                    onSelected = { style = it }
                )
                DropdownRow(
                    label = stringResource(R.string.settings_roles_field_language),
                    options = listOf(
                        "" to stringResource(R.string.settings_roles_language_follow),
                        "zh" to stringResource(R.string.settings_roles_language_zh),
                        "en" to stringResource(R.string.settings_roles_language_en),
                    ),
                    selected = replyLanguage,
                    onSelected = { replyLanguage = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            userTitle = userTitle.trim(),
                            emoji = emoji.trim().ifBlank { "🤖" },
                            roleDefinition = roleDefinition.trim(),
                            systemPrompt = systemPrompt.trim(),
                            style = style,
                            replyLanguage = replyLanguage,
                            isBuiltIn = false   // 经编辑器保存的必然是自定义角色
                        )
                    )
                }
            ) { Text(stringResource(R.string.settings_roles_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_roles_cancel)) }
        }
    )
}
