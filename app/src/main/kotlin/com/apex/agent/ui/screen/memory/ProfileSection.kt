package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.apex.agent.ui.screen.memory.profile.ProfileAspect
import com.apex.agent.ui.screen.memory.profile.ProfileAspectEntry
import com.apex.agent.ui.screen.memory.profile.UserProfileItem

/**
 * 「用户画像」分区 —— 跨会话五维度长期特征的可见性与管理入口。
 *
 * 与聊天记忆分区的分工：那边是「发生过什么」（对话事实账本），这边是
 * 「用户是谁」（技术栈 / 编码风格 / 偏好 / 关注领域 / 工作习惯）。
 *
 * 组成（单列列表的独立条目）：
 *  - [MemoryProfileHeader]：分区头 + 条数 + 「从编码对话提炼」主入口 +
 *    手动添加 / 一键清空；
 *  - [MemoryProfileAspectCard]：维度卡（一维度一卡，条目按置信度降序，
 *    单条可编辑 / 删除）；
 *  - [ProfileEditDialog]：添加 / 编辑双态对话框（维度芯片 + 内容 +
 *    置信度滑杆）。
 *
 * 视觉沿用记忆页既有语言（ElevatedCard / titleSmall 分区标题 /
 * labelSmall 徽章 / error 色破坏性动作）。
 */

/** 分区头：标题 + 条数 + 提炼（主入口）+ 添加 + 清空（有内容才出现）。 */
@Composable
internal fun MemoryProfileHeader(
    itemCount: Int,
    distilling: Boolean,
    onDistill: () -> Unit,
    onAdd: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Person, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.memory_profile_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        if (itemCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.memory_profile_count_fmt, itemCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        if (itemCount > 0) {
            IconButton(onClick = onClearAll, enabled = !distilling) {
                Icon(
                    Icons.Outlined.DeleteSweep,
                    contentDescription = stringResource(R.string.memory_profile_clear),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        IconButton(onClick = onAdd, enabled = !distilling) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.memory_profile_add),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        TextButton(onClick = onDistill, enabled = !distilling) {
            if (distilling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Icon(
                    Icons.Outlined.AutoAwesome, contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                stringResource(
                    if (distilling) R.string.memory_profile_distilling
                    else R.string.memory_profile_distill
                ),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** 单个维度的卡片：维度徽章 + 标题 + 逐条记录（点击编辑 / 每条可删）。 */
@Composable
internal fun MemoryProfileAspectCard(
    entry: ProfileAspectEntry,
    onEdit: (item: UserProfileItem) -> Unit,
    onDelete: (item: UserProfileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAspectBadge(entry.aspect)
                Spacer(Modifier.width(8.dp))
                Text(
                    profileAspectLabel(entry.aspect),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            entry.items.forEach { item ->
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        item.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.memory_profile_meta_fmt,
                                (item.confidence * 100).toInt(),
                                profileSourceLabel(item.source)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (!item.evidence.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.evidence,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onEdit(item) }) {
                            Text(
                                stringResource(R.string.memory_profile_edit),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.memory_delete),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 画像条目编辑对话框（双态：[existing] 为 null → 添加；非 null → 编辑，
 * 维度芯片锁定不可切 —— 跨维度挪动语义模糊，宁缺毋滥）。
 */
@Composable
internal fun ProfileEditDialog(
    existing: UserProfileItem?,
    onDismiss: () -> Unit,
    onConfirm: (aspect: ProfileAspect, content: String, confidence: Float) -> Unit,
) {
    var content by remember(existing?.id) {
        mutableStateOf(existing?.content ?: "")
    }
    var aspect by remember(existing?.id) {
        mutableStateOf(existing?.aspect ?: ProfileAspect.TECH_STACK.key)
    }
    var confidence by remember(existing?.id) {
        mutableFloatStateOf(existing?.confidence ?: 1.0f)
    }
    val editing = existing != null
    val valid = content.isNotBlank() && ProfileAspect.fromKey(aspect) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editing) R.string.memory_profile_edit_title
                    else R.string.memory_profile_add_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!editing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ProfileAspect.entries.take(3).forEach { candidate ->
                            FilterChip(
                                selected = aspect == candidate.key,
                                onClick = { aspect = candidate.key },
                                label = { Text(profileAspectLabel(candidate)) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProfileAspect.entries.drop(3).forEach { candidate ->
                            FilterChip(
                                selected = aspect == candidate.key,
                                onClick = { aspect = candidate.key },
                                label = { Text(profileAspectLabel(candidate)) }
                            )
                        }
                    }
                } else {
                    ProfileAspect.fromKey(aspect)?.let {
                        ProfileAspectBadge(it)
                        Spacer(Modifier.size(4.dp))
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(120) },
                    label = { Text(stringResource(R.string.memory_profile_content_label)) },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = {
                        Text(
                            stringResource(R.string.memory_profile_content_hint),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text(
                        stringResource(R.string.memory_profile_confidence_fmt, (confidence * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = confidence,
                        onValueChange = { confidence = it },
                        valueRange = 0.3f..1.0f,
                        steps = 6,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = ProfileAspect.fromKey(aspect) ?: return@TextButton
                    onConfirm(target, content.trim(), confidence)
                },
                enabled = valid
            ) { Text(stringResource(R.string.memory_profile_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_profile_cancel)) }
        }
    )
}

/** 维度徽章（五维各配色，视觉沿用类别徽章口径）。 */
@Composable
private fun ProfileAspectBadge(aspect: ProfileAspect) {
    val (labelRes, color) = when (aspect) {
        ProfileAspect.TECH_STACK ->
            R.string.memory_profile_aspect_tech_stack to MaterialTheme.colorScheme.primary
        ProfileAspect.CODING_STYLE ->
            R.string.memory_profile_aspect_coding_style to MaterialTheme.colorScheme.secondary
        ProfileAspect.PREFERENCES ->
            R.string.memory_profile_aspect_preferences to MaterialTheme.colorScheme.tertiary
        ProfileAspect.DOMAINS ->
            R.string.memory_profile_aspect_domains to MaterialTheme.colorScheme.primary
        ProfileAspect.HABITS ->
            R.string.memory_profile_aspect_habits to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 维度标题（与徽章同源同词 —— 语言切换一致性）。 */
@Composable
internal fun profileAspectLabel(aspect: ProfileAspect): String = stringResource(
    when (aspect) {
        ProfileAspect.TECH_STACK -> R.string.memory_profile_aspect_tech_stack
        ProfileAspect.CODING_STYLE -> R.string.memory_profile_aspect_coding_style
        ProfileAspect.PREFERENCES -> R.string.memory_profile_aspect_preferences
        ProfileAspect.DOMAINS -> R.string.memory_profile_aspect_domains
        ProfileAspect.HABITS -> R.string.memory_profile_aspect_habits
    }
)

/** 来源标签（蒸馏 / 手动）。 */
@Composable
private fun profileSourceLabel(source: String): String = stringResource(
    if (source == UserProfileItem.SOURCE_MANUAL) R.string.memory_profile_source_manual
    else R.string.memory_profile_source_distilled
)
