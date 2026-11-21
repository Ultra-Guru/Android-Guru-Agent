package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.apex.memkit.core.model.ChatFact

/**
 * 记忆写入口（记忆页 v2 —— 把 PR#545 的三个 agent 写入工具对等的
 * 「用户侧沉淀面」补上）：
 *  - 顶栏「添加」菜单：两个入口共享一个 [MemoryAddMenu]；
 *  - [SaveFactDialog]：手动记一条长期事实（画像 / 近况 / 里程碑 +
 *    重要度滑杆）→ MemKit.saveFact（来源 MANUAL，与 agent 工具同落点）；
 *  - [TeachExperienceDialog]：教一次经验（目标 / 结果 / 成败 / 教训
 *    逐行）→ MemKit.consolidateExperience（与 MemoryConsolidateExperience
 *    工具同落点 —— 摘要事实 + 教训 PROFILE 事实 + 图谱落库一包完成）。
 *
 * 写入纪律与工具层一致：空白不提交、教训行逐条净化（空行跳过）、
 * 超长拦提交（按钮禁用 + 行内提示，不靠事后 snackbar 纠错）。
 */

/** 输入长度契约（与库侧存储的轻量语义对齐：事实是「一句话」级记忆）。 */
private val CONTENT_MAX = 500
private val GOAL_MAX = 200
private val OUTCOME_MAX = 600
private val LESSON_MAX = 300
private const val LESSONS_MAX_LINES = 12

@Composable
fun MemoryAddMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSaveFact: () -> Unit,
    onTeachExperience: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.memory_write_save_fact)) },
            leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
            onClick = { onDismiss(); onSaveFact() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.memory_write_teach)) },
            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            onClick = { onDismiss(); onTeachExperience() }
        )
    }
}


@Composable
fun SaveFactDialog(
    onDismiss: () -> Unit,
    onSave: (content: String, kind: ChatFact.Kind, importance: Float) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ChatFact.Kind.PROFILE) }
    var importance by remember { mutableFloatStateOf(0.6f) }

    val tooLong = content.length > CONTENT_MAX
    val canSubmit = content.isNotBlank() && !tooLong

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_write_save_fact_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.memory_write_content_label)) },
                    supportingText = {
                        Text(
                            if (tooLong) stringResource(R.string.memory_write_msg_too_long, CONTENT_MAX)
                            else stringResource(R.string.memory_write_content_hint)
                        )
                    },
                    isError = tooLong,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.memory_write_kind_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == ChatFact.Kind.PROFILE,
                        onClick = { kind = ChatFact.Kind.PROFILE },
                        label = { Text(stringResource(R.string.memory_chat_badge_profile)) }
                    )
                    FilterChip(
                        selected = kind == ChatFact.Kind.STATE,
                        onClick = { kind = ChatFact.Kind.STATE },
                        label = { Text(stringResource(R.string.memory_chat_badge_state)) }
                    )
                    FilterChip(
                        selected = kind == ChatFact.Kind.MILESTONE,
                        onClick = { kind = ChatFact.Kind.MILESTONE },
                        label = { Text(stringResource(R.string.memory_chat_badge_milestone)) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.memory_write_importance_fmt, (importance * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 0f..1f,
                    steps = 19 // 5% 步进 —— 粗粒度足够（事实重要度是定性信号）
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onSave(content.trim(), kind, importance)
                    onDismiss()
                }
            ) { Text(stringResource(R.string.memory_write_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) }
        }
    )
}

@Composable
fun TeachExperienceDialog(
    onDismiss: () -> Unit,
    onTeach: (goal: String, outcome: String, success: Boolean, lessons: List<String>) -> Unit
) {
    var goal by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(true) }
    var lessons by remember { mutableStateOf("") }

    val goalTooLong = goal.length > GOAL_MAX
    val outcomeTooLong = outcome.length > OUTCOME_MAX
    val canSubmit = goal.isNotBlank() && outcome.isNotBlank() && !goalTooLong && !outcomeTooLong

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_teach_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text(stringResource(R.string.memory_teach_goal_label)) },
                    isError = goalTooLong,
                    supportingText = {
                        if (goalTooLong) Text(stringResource(R.string.memory_write_msg_too_long, GOAL_MAX))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outcome,
                    onValueChange = { outcome = it },
                    label = { Text(stringResource(R.string.memory_teach_outcome_label)) },
                    isError = outcomeTooLong,
                    supportingText = {
                        if (outcomeTooLong) Text(stringResource(R.string.memory_write_msg_too_long, OUTCOME_MAX))
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (success) R.string.memory_teach_success else R.string.memory_teach_failed
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = success, onCheckedChange = { success = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lessons,
                    onValueChange = { lessons = it },
                    label = { Text(stringResource(R.string.memory_teach_lessons_label)) },
                    supportingText = { Text(stringResource(R.string.memory_teach_lessons_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    // 教训逐行净化：trim + 空行跳过 + 长度截断 + 条数封顶
                    // （与 MemoryConsolidateExperienceTool 的防御式净化同口径）
                    val cleaned = lessons.lines()
                        .map { it.trim().take(LESSON_MAX) }
                        .filter { it.isNotBlank() }
                        .take(LESSONS_MAX_LINES)
                    onTeach(goal.trim(), outcome.trim(), success, cleaned)
                    onDismiss()
                }
            ) { Text(stringResource(R.string.memory_write_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) }
        }
    )
}
