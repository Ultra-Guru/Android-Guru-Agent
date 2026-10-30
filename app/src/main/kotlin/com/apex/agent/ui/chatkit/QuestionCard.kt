package com.apex.agent.ui.chatkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.ui.glass.Backdrop
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassStyle

/**
 * 工具/权限门的结构化提问卡（Agent 屏历史形态 + Coding 屏 v6 玻璃接线）。
 *
 * [backdrop]：悬浮栈与时间轴 layerBackdrop 源互为兄弟时传入 —— 提问卡从
 * GlassCard 获得真实 backdrop 采样（白天模式乳白磨砂而非实色 ElevatedCard）；
 * null 时保持原 ElevatedCard 形态（Agent 屏历史调用零变更）。
 */
@Composable
internal fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (List<String>, String?) -> Unit,
    onCancel: () -> Unit,
    backdrop: Backdrop? = null
) {
    if (backdrop != null) {
        GlassCard(
            backdrop = backdrop,
            style = GlassStyle.Card,
            shape = RoundedCornerShape(12.dp),
            accent = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth()
        ) {
            QuestionCardBody(question, onAnswer, onCancel, compactPadding = true)
        }
    } else {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), // 统一卡片半径（Plan/Spec/ToolCall/TaskStatus 均 12dp，原 16 为孤例）
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            )
        ) {
            QuestionCardBody(question, onAnswer, onCancel, compactPadding = false)
        }
    }
}

@Composable
private fun QuestionCardBody(
    question: AgentQuestion,
    onAnswer: (List<String>, String?) -> Unit,
    onCancel: () -> Unit,
    compactPadding: Boolean
) {
    // 多选（allowMultiSelect）用集合状态；单选沿用单值状态。
    val multiSelect = question.allowMultiSelect
    var selectedOptionIds by remember { mutableStateOf(setOf<String>()) }
    var selectedOptionId by remember { mutableStateOf<String?>(null) }
    var customSelected by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    val canSubmit = if (multiSelect) {
        selectedOptionIds.isNotEmpty() || (customSelected && customText.isNotBlank())
    } else {
        selectedOptionId != null || (customSelected && customText.isNotBlank())
    }

    fun toggleOption(id: String) {
        if (multiSelect) {
            selectedOptionIds = if (id in selectedOptionIds) selectedOptionIds - id
            else selectedOptionIds + id
            customSelected = false
        } else {
            selectedOptionId = id
            customSelected = false
        }
    }

    val innerPadding = if (compactPadding) 12.dp else 16.dp
    Column(modifier = Modifier.padding(innerPadding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_question_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (multiSelect) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_multi_select),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = question.title,
                style = MaterialTheme.typography.bodyLarge
            )

            question.description?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            question.options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleOption(option.id) }
                        .padding(vertical = 4.dp)
                ) {
                    if (multiSelect) {
                        Checkbox(
                            checked = option.id in selectedOptionIds,
                            onCheckedChange = { toggleOption(option.id) }
                        )
                    } else {
                        RadioButton(
                            selected = selectedOptionId == option.id,
                            onClick = { toggleOption(option.id) }
                        )
                    }

                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (option.recommended) {
                                Text(
                                    text = stringResource(R.string.chat_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        option.description?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (question.allowCustom) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            customSelected = true
                            selectedOptionIds = emptySet()
                            selectedOptionId = null
                        }
                        .padding(vertical = 4.dp)
                ) {
                    if (multiSelect) {
                        Checkbox(
                            checked = customSelected,
                            onCheckedChange = {
                                customSelected = it
                                if (it) {
                                    selectedOptionIds = emptySet()
                                    selectedOptionId = null
                                }
                            }
                        )
                    } else {
                        RadioButton(
                            selected = customSelected,
                            onClick = {
                                customSelected = true
                                selectedOptionId = null
                            }
                        )
                    }

                    Text(
                        text = stringResource(R.string.chat_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (customSelected) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = { Text(question.customPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (question.allowSkip) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_skip))
                    }
                }

                androidx.compose.material3.Button(
                    onClick = {
                        if (customSelected) {
                            onAnswer(emptyList(), customText.trim())
                        } else if (multiSelect) {
                            onAnswer(selectedOptionIds.toList(), null)
                        } else {
                            onAnswer(listOfNotNull(selectedOptionId), null)
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.chat_continue))
                }
            }
        }
}
