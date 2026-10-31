package com.apex.agent.ui.screen.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.TemplateScope

/**
 * #197 模板工坊 —— 提示词模板的独立管理页（左侧抽屉直达入口）。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  [Agent 模板]  [Coding 模板]   ← scope 分层（互不串扰）
 * ├──────────────────────────────────────────────┤
 * │  各层：提示词模板列表（新建/编辑/复制/删除）        │
 * │  Coding 层顶部：专家角色区 [CodingRolesSection]   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 模板的 scope 由 [TemplateScope] 分层（scope 分层是模板运行时概念，由
 * 模板运行时会话演进）。Agent 角色详细设置分区（原复用设置页
 * AgentRolesSection 的人设卡 CRUD）已随 Agent 模式移除（PR 5/6）；
 * Coding 页签顶部保留专家角色区 [CodingRolesSection]（品牌图标 + 选择即
 * 激活 codeActiveRoleId，引擎人设通道热切换）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateStudioScreen(
    viewModel: TemplateStudioViewModel = hiltViewModel()
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val useState by viewModel.useState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val agentSettings by viewModel.agentSettings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ═══ 工位分层页签：Agent 模板 / Coding 模板 ═══
            SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == TemplateScope.AGENT,
                    onClick = { viewModel.selectTab(TemplateScope.AGENT) },
                    text = { Text(stringResource(R.string.templates_tab_agent)) }
                )
                Tab(
                    selected = tab == TemplateScope.CODING,
                    onClick = { viewModel.selectTab(TemplateScope.CODING) },
                    text = { Text(stringResource(R.string.templates_tab_coding)) }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (tab) {
                    TemplateScope.AGENT -> {
                        // ═══ Agent 提示词模板（角色详细设置分区已随 Agent 模式移除 PR 5/6）═══
                        item(key = "agent-templates-header") {
                            TemplateListHeader(
                                title = stringResource(R.string.templates_agent_list_title),
                                subtitle = stringResource(R.string.templates_agent_list_subtitle),
                                onCreate = { viewModel.openEditor(null) }
                            )
                        }

                        items(templates, key = { it.id }) { template ->
                            TemplateCard(
                                template = template,
                                onUse = { viewModel.openUse(template) },
                                onEdit = { viewModel.openEditor(template) },
                                onDuplicate = { viewModel.duplicateTemplate(template) },
                                onDelete = { viewModel.deleteTemplate(template.id) }
                            )
                        }
                    }
                    TemplateScope.CODING -> {
                        // ═══ Coding 专家角色（官方品牌图标 + 选择即激活 codeActiveRoleId）═══
                        item(key = "coding-roles") {
                            CodingRolesSection(
                                agent = agentSettings,
                                onAgent = { viewModel.updateAgentSettings { it } }
                            )
                        }

                        item(key = "coding-templates-header") {
                            TemplateListHeader(
                                title = stringResource(R.string.templates_coding_list_title),
                                subtitle = stringResource(R.string.templates_coding_list_subtitle),
                                onCreate = { viewModel.openEditor(null) }
                            )
                        }

                        items(templates, key = { it.id }) { template ->
                            TemplateCard(
                                template = template,
                                onUse = { viewModel.openUse(template) },
                                onEdit = { viewModel.openEditor(template) },
                                onDuplicate = { viewModel.duplicateTemplate(template) },
                                onDelete = { viewModel.deleteTemplate(template.id) }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ═══ 模板编辑器（新建/编辑共用；含变量声明的轻量表单）═══
    editing?.let { template ->
        TemplateEditorDialog(
            initial = template,
            onDismiss = viewModel::closeEditor,
            onSave = { viewModel.saveTemplate(it) }
        )
    }

    // ═══ 使用模板（行为运行时：变量表单 → 生成 → 复制）═══
    useState?.let { state ->
        TemplateUseDialog(
            state = state,
            onVariable = viewModel::updateUseVariable,
            onGenerate = viewModel::generateUsePrompt,
            onDismiss = viewModel::closeUse
        )
    }
}

// ═══ 组件 ═══

@Composable
private fun TemplateListHeader(
    title: String,
    subtitle: String,
    onCreate: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.templates_create))
        }
    }
}

/**
 * 模板卡片：内置模板锁定（点击=使用；无编辑/删除），自定义模板全量操作。
 * 「使用」是行为运行时入口：内置模板走六阶段管线，自定义走变量替换。
 */
@Composable
private fun TemplateCard(
    template: PromptTemplate,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUse)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (template.isBuiltIn) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.templates_builtin_locked),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (template.description.isNotBlank()) {
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                if (template.variables.isNotEmpty()) {
                    Text(
                        text = template.variables.joinToString(" · ") { "{{${it.name}}}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // 使用（行为运行时入口；内置模板主操作）
                IconButton(onClick = onUse, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.templates_use),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (!template.isBuiltIn) {
                    // 内置模板不可更改：编辑/删除不出现（注册表侧同样拒绝）
                    IconButton(onClick = onEdit, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.templates_edit),
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.templates_duplicate),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!template.isBuiltIn) {
                    IconButton(onClick = onDelete, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.templates_delete),
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** 模板编辑器（名称/说明/正文；变量从正文 {{name}} 扫描提示）。 */
@Composable
private fun TemplateEditorDialog(
    initial: PromptTemplate,
    onDismiss: () -> Unit,
    onSave: (PromptTemplate) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var content by remember { mutableStateOf(initial.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.id.isBlank()) stringResource(R.string.templates_editor_new)
                else stringResource(R.string.templates_editor_edit)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.templates_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.templates_field_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.templates_field_content)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.templates_content_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                // 变量提示：从正文扫描（与引擎 renderedVariables 同词法）
                val referenced = remember(content) { scanVariables(content) }
                if (referenced.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.templates_vars_detected, referenced.joinToString(" · ")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || content.isBlank()) return@TextButton
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            description = description.trim(),
                            content = content,
                            variables = scanVariables(content).map {
                                com.apex.agent.core.engine.templates.TemplateVariable(
                                    name = it,
                                    description = "",
                                    required = false
                                )
                            }
                        )
                    )
                },
                enabled = name.isNotBlank() && content.isNotBlank()
            ) { Text(stringResource(R.string.templates_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.templates_cancel)) }
        }
    )
}

/** 轻量变量扫描（与 PromptTemplate.referencedVariables 同词法的 UI 版）。 */
private fun scanVariables(content: String): List<String> {
    val regex = Regex("""[{]{2}\s*([a-zA-Z][a-zA-Z0-9_]*)""")
    return regex.findAll(content).map { it.groupValues[1] }.distinct().toList()
}

/**
 * 使用模板对话框（行为运行时入口）：
 * 1. 变量表单（必填标记 + 默认值预填）；
 * 2. 生成 = 激活 + 六阶段组装（内置模板注入确定性分析附录）；
 * 3. 结果展示（提示词 + 注入说明）+ 一键复制（粘到聊天即用）。
 */
@Composable
private fun TemplateUseDialog(
    state: TemplateUseState,
    onVariable: (String, String) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.templates_use_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.templates_use_title, state.template.name),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 行为能力摘要（内置 = 代码行为；自定义 = 兜底说明）
                Text(
                    text = state.manifestSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.behaviorBound) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 变量表单
                for (variable in state.template.variables) {
                    OutlinedTextField(
                        value = state.variableValues[variable.name].orEmpty(),
                        onValueChange = { onVariable(variable.name, it) },
                        label = {
                            Text(
                                if (variable.required) variable.name
                                else stringResource(R.string.templates_use_optional, variable.name)
                            )
                        },
                        placeholder = {
                            Text(variable.description, style = MaterialTheme.typography.bodySmall)
                        },
                        minLines = 1,
                        maxLines = if (variable.name in setOf("code", "changes", "text", "content", "pattern")) {
                            5
                        } else {
                            1
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 错误回填（缺必填等）
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // 生成结果
                state.generatedPrompt?.let { prompt ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = stringResource(R.string.templates_use_result),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (state.generationNotes.isNotEmpty()) {
                                Text(
                                    text = state.generationNotes.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.generatedPrompt?.let { prompt ->
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(prompt))
                            onDismiss()
                        }
                    ) { Text(copiedMessage) }
                }
                TextButton(onClick = onGenerate) {
                    Text(stringResource(R.string.templates_use_generate))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.templates_cancel)) }
        }
    )
}
