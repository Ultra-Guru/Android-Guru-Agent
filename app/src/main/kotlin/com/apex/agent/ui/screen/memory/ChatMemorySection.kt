package com.apex.agent.ui.screen.memory

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
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.memkit.core.model.ChatFact

/**
 * 「聊天记忆」分区（#219 隐私合规，v2 账本化，v3 单列扁平化）——
 * 聊天记忆库的可见性与管理入口。
 *
 * 数据源是 apex-kit-memory 的聊天事实账本（memory_kit/chat.json），与
 * 轨迹库、memory MCP 图谱同库不同文档：ChatEngine 在对话中自动沉淀三类
 * 记忆（画像 / 近况 / 里程碑，含「近期偏低落」这类敏感情绪判断）。
 *
 * v3 拆分：v2 的 ColumnScope 包装（自带内滚 LazyColumn + 删除确认
 * 对话框）拆成两个可独立编址的部件 —— [MemoryChatHeader]（分区头：
 * 标题 + 条数 + 一键清空入口）与 [MemoryChatEntryCard]（类别卡：逐条
 * 记录含元数据行，每条可删）。二者作为单列列表的独立条目渲染；删除
 * / 清空的二次确认对话框上提到 MemoryScreen 统一持有（对话框与触发的
 * 卡片分离 —— LazyColumn 条目离屏不丢确认现场）。
 *
 * 视觉沿用记忆页既有语言：ElevatedCard 条目 / titleSmall 分区标题 /
 * labelSmall 徽章 / error 色破坏性动作。
 */

/** 分区头：标题 + 已记条数 + 一键清空（有内容才出现清空入口）。 */
@Composable
internal fun MemoryChatHeader(
    recordCount: Int,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Forum, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.memory_chat_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        if (recordCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.memory_chat_count_fmt, recordCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        if (recordCount > 0) {
            TextButton(onClick = onClearAll) {
                Text(
                    stringResource(R.string.memory_chat_clear),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 单个聊天记忆类别的卡片：类别徽章 + 名称 + 逐条记录（含元数据行，每条可删）。 */
@Composable
internal fun MemoryChatEntryCard(
    entry: ChatMemoryEntry,
    onDeleteRecord: (record: ChatFact) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatMemoryTypeBadge(entry.kind)
                Spacer(Modifier.width(8.dp))
                Text(
                    // i18n 修复：类别标题原为硬编码中文（displayLabel），现走资源
                    // —— 与徽章同源同词，英文环境不再中英混杂
                    chatMemoryKindLabel(entry.kind),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            entry.records.forEach { record ->
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        record.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.memory_chat_meta_fmt,
                                (record.importance * 100).toInt(),
                                chatMemorySourceLabel(record.source),
                                record.accessCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDeleteRecord(record) }) {
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

/** 类别徽章：画像 / 近况 / 里程碑。 */
@Composable
private fun ChatMemoryTypeBadge(kind: ChatFact.Kind) {
    val (label, color) = when (kind) {
        ChatFact.Kind.PROFILE ->
            stringResource(R.string.memory_chat_badge_profile) to MaterialTheme.colorScheme.primary
        ChatFact.Kind.STATE ->
            stringResource(R.string.memory_chat_badge_state) to MaterialTheme.colorScheme.tertiary
        ChatFact.Kind.MILESTONE ->
            stringResource(R.string.memory_chat_badge_milestone) to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 类别标题（与徽章同源同词 —— 集中在此便于语言切换一致性）。 */
@Composable
private fun chatMemoryKindLabel(kind: ChatFact.Kind): String = when (kind) {
    ChatFact.Kind.PROFILE -> stringResource(R.string.memory_chat_badge_profile)
    ChatFact.Kind.STATE -> stringResource(R.string.memory_chat_badge_state)
    ChatFact.Kind.MILESTONE -> stringResource(R.string.memory_chat_badge_milestone)
}

/** 来源标签（v2 元数据行用 —— 启发式 / 蒸馏 / 手动 / 迁移）。 */
@Composable
private fun chatMemorySourceLabel(source: ChatFact.Source): String = when (source) {
    ChatFact.Source.HEURISTIC -> stringResource(R.string.memory_chat_source_heuristic)
    ChatFact.Source.DISTILLED -> stringResource(R.string.memory_chat_source_distill)
    ChatFact.Source.MANUAL -> stringResource(R.string.memory_chat_source_manual)
    ChatFact.Source.MIGRATED -> stringResource(R.string.memory_chat_source_migrated)
}
