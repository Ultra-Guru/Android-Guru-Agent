package com.apex.agent.ui.screen.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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

/**
 * # Code Playbook Bars — 任务剧本 UI 组件族（task-playbooks PR-B）
 *
 * 四件套（全部纯展示：状态与动作由 CodeScreen 注入）：
 * - [CodePlaybookSuggestBar] 识别命中建议条（SUGGEST 档）：「检测到 X 任务
 *   → 挂载剧本？」[挂载][本次不用]；
 * - [CodePlaybookChip] 挂载态 chip（composer 选择器行）：「剧本《X》·幕k/n」，
 *   点击开幕进度 sheet；
 * - [CodePlaybookStageSheet] 幕进度弹层（各幕标题/状态 + 跳幕 + 卸载）；
 * - [CodePlaybookHandoffBar] 工坊手递手待发送条（补齐「使用模板」断环）。
 */

/**
 * 组合挂载入口一（CodeScreen 单行调用）：建议条 + 工坊手递手条 +
 * 幕进度弹层（sheet 可见性由 CodeScreen 持有——chip 在选择器行内触发）。
 */
@Composable
internal fun CodePlaybookBarsSection(
    viewModel: CodeViewModel,
    showSheet: Boolean,
    onDismissSheet: () -> Unit
) {
    val state by viewModel.playbookController.state.collectAsStateWithLifecycle()
    val handoff by viewModel.playbookHandoffStore.pending.collectAsStateWithLifecycle()
    state.suggest?.let { suggestion ->
        CodePlaybookSuggestBar(
            playbookName = suggestion.playbook.name,
            reason = suggestion.reason,
            onAccept = viewModel.playbookController::acceptSuggestion,
            onDismiss = viewModel.playbookController::dismissSuggestion
        )
    }
    handoff?.let {
        CodePlaybookHandoffBar(
            onSend = {
                val text = viewModel.playbookHandoffStore.consume() ?: return@CodePlaybookHandoffBar
                viewModel.sendMessage(text)
            },
            onDiscard = viewModel.playbookHandoffStore::discard
        )
    }
    if (showSheet) {
        state.mounted?.let { mounted ->
            CodePlaybookStageSheet(
                playbookName = mounted.playbook.name,
                stageTitles = mounted.playbook.stages.map { it.title },
                stageIndex = mounted.stageIndex,
                completedStages = mounted.completedStages,
                onJump = { index ->
                    viewModel.playbookController.jumpToStage(index)
                    viewModel.repushPlaybookExtras()
                },
                onUnmount = {
                    viewModel.playbookController.unmount()
                    onDismissSheet()
                },
                onDismiss = onDismissSheet
            )
        }
    }
}

/** 组合挂载入口二（选择器行内）：挂载态 chip（收集控制器状态，点击回调）。 */
@Composable
internal fun CodePlaybookChipMount(
    viewModel: CodeViewModel,
    onClick: () -> Unit
) {
    val state by viewModel.playbookController.state.collectAsStateWithLifecycle()
    state.mounted?.let { mounted ->
        CodePlaybookChip(
            playbookName = mounted.playbook.name,
            stageIndex = mounted.stageIndex,
            stageCount = mounted.playbook.stageCount,
            onClick = onClick
        )
    }
}

/** 建议条：识别命中 → 用户一键确认（SUGGEST 档默认交互）。 */
@Composable
internal fun CodePlaybookSuggestBar(
    playbookName: String,
    reason: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.TheaterComedy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.playbook_suggest_title, playbookName),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (reason.isNotBlank()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.playbook_suggest_dismiss))
                }
                TextButton(onClick = onAccept) {
                    Text(stringResource(R.string.playbook_suggest_accept))
                }
            }
        }
    }
}

/** 挂载态 chip（composer 选择器行内；点击开幕进度 sheet）。 */
@Composable
internal fun CodePlaybookChip(
    playbookName: String,
    stageIndex: Int,
    stageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                text = stringResource(R.string.playbook_chip_label, playbookName, stageIndex + 1, stageCount),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.TheaterComedy,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

/** 幕进度弹层：各幕标题/状态 + 跳幕 + 卸载（纯展示，动作注入）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodePlaybookStageSheet(
    playbookName: String,
    stageTitles: List<String>,
    stageIndex: Int,
    completedStages: List<Int>,
    onJump: (Int) -> Unit,
    onUnmount: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.playbook_sheet_title, playbookName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.playbook_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            stageTitles.forEachIndexed { index, title ->
                val state = when {
                    index in completedStages -> stringResource(R.string.playbook_stage_done)
                    index == stageIndex -> stringResource(R.string.playbook_stage_current)
                    else -> stringResource(R.string.playbook_stage_pending)
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (index == stageIndex) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onJump(index) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.playbook_stage_item_fmt, index + 1),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            text = state,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == stageIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                TextButton(onClick = onUnmount) {
                    Text(stringResource(R.string.playbook_unmount))
                }
            }
        }
    }
}

/** 工坊手递手待发送条：生成结果落地为可一键发送的输入（补齐剪贴板断环）。 */
@Composable
internal fun CodePlaybookHandoffBar(
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Default.TheaterComedy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.playbook_handoff_title),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onDiscard, modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.playbook_handoff_discard), Modifier.size(14.dp))
            }
            TextButton(onClick = onSend) {
                Text(stringResource(R.string.playbook_handoff_send))
            }
        }
    }
}
