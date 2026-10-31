package com.apex.agent.ui.screen.memory

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation

/**
 * 「知识图谱」分区（记忆页 v2，v3 单列扁平化）—— 实体/关系的文字化
 * 精读形态。
 *
 * 图谱画布（memory-graph-ui）是「空间认知」形态；本分区是同数据的
 * 「线性精读」形态，两者互补：
 *  - [MemoryKnowledgeHeader]：分区头（实体 / 关系计数）；
 *  - [MemoryEntityCard]：实体卡 —— 类型徽章 + 观察列表（可展开）+
 *    关联关系（方向归一呈现「本实体 —关系→ 对端」）；
 *  - 点击「在图谱中查看」→ 回调 [onOpenInGraph]（宿主切图谱视图并
 *    navigateTo 定位该实体节点 —— 列表 ↔ 图谱双向联动）。
 *
 * v3 变化：v2 的 ColumnScope 包装拆为头 / 卡两个独立编址部件（单列
 * 列表条目）；展开动画显式 animateContentSize（列表条目级动画由宿主
 * animateItem 承担）。排序（关联中心度降序）与折叠上限住在宿主 /
 * MemoryListPlan。
 *
 * 数据源 = MemKit.readGraph()（MCP memory 语义的 name / entityType /
 * observations / 三元组），agent 在任务中经 MemoryGraph 工具或经验整理
 * 自动沉淀，本分区全部只读 —— 管理动作在图谱画布的详情 Sheet。
 */

/** 分区头（标题 + 计数行 —— 与聊天记忆分区头同一视觉语言）。 */
@Composable
internal fun MemoryKnowledgeHeader(
    entityCount: Int,
    relationCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Hub, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.memory_knowledge_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.memory_knowledge_count_fmt, entityCount, relationCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 实体卡：名称行（类型徽章 + 图谱入口）+ 观察 + 关联（点击展开/收起）。 */
@Composable
internal fun MemoryEntityCard(
    entity: GraphEntity,
    relations: List<EntityRelationView>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenInGraph: (entityName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggle)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntityTypeBadge(entity.entityType)
                Spacer(Modifier.width(8.dp))
                Text(
                    entity.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.memory_knowledge_open_graph),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = { onOpenInGraph(entity.name) })
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (entity.observations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val shown = if (expanded) entity.observations else entity.observations.take(2)
                shown.forEach { obs ->
                    Text(
                        obs,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!expanded && entity.observations.size > 2) {
                    Text(
                        stringResource(R.string.memory_knowledge_more_fmt, entity.observations.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (relations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.memory_knowledge_relations),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val shownRelations = if (expanded) relations else relations.take(3)
                shownRelations.forEach { rel ->
                    Text(
                        relationLine(rel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!expanded && relations.size > 3) {
                    Text(
                        stringResource(R.string.memory_knowledge_more_fmt, relations.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.memory_knowledge_no_relations),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 关系行文案（纯函数）：出边「—关系→ 对端」/ 入边「对端 —关系→」（箭头隐含指向本实体卡标题）。 */
private fun relationLine(rel: EntityRelationView): String =
    if (rel.direction) "—${rel.relationType}→ ${rel.other}"
    else "${rel.other} —${rel.relationType}→"

/** 实体类型徽章（类型名原样 —— MCP memory 的 entityType 是开放词表）。 */
@Composable
private fun EntityTypeBadge(entityType: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
    ) {
        Text(
            entityType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
