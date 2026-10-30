package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation

/**
 * 「知识图谱」分区（记忆页 v2）—— 实体/关系的文字化浏览面。
 *
 * 图谱画布（memory-graph-ui）是「空间认知」形态；本分区是同数据的
 * 「线性精读」形态，两者互补：
 *  - 实体卡：名称 + 类型徽章 + 观察列表（可展开）+ 关联关系（方向归一
 *    呈现「本实体 —关系→ 对端」）；
 *  - 点击实体卡 → 回调 [onOpenInGraph]（宿主切到图谱视图并 navigateTo
 *    定位该实体节点 —— 列表 ↔ 图谱双向联动）；
 *  - 排序：关联数降序（最中心的知识排最前），同位数按名称。
 *
 * 数据源 = MemKit.readGraph()（MCP memory 语义的 name / entityType /
 * observations / 三元组），agent 在任务中经 MemoryGraph 工具或经验整理
 * 自动沉淀，本分区全部只读 —— 管理动作在图谱画布的详情 Sheet。
 */
@Composable
fun ColumnScope.KnowledgeGraphSection(
    entities: List<GraphEntity>,
    relations: List<GraphRelation>,
    onOpenInGraph: (entityName: String) -> Unit
) {
    // 中心度排序（关联数降序 → 名称）：快照本身小（数百级），重组成本可忽略
    val relationCounts = remember(relations) {
        relations.flatMap { listOf(it.from, it.to) }
            .groupingBy { it }
            .eachCount()
    }
    val sorted = remember(entities, relationCounts) {
        entities.sortedWith(
            compareByDescending<GraphEntity> { relationCounts[it.name] ?: 0 }.thenBy { it.name }
        )
    }

    SectionHeader(
        title = stringResource(R.string.memory_knowledge_title),
        countText = stringResource(R.string.memory_knowledge_count_fmt, entities.size, relations.size)
    )

    if (sorted.isEmpty()) {
        EmptyHint(stringResource(R.string.memory_knowledge_empty))
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sorted, key = { it.name }) { entity ->
            EntityCard(
                entity = entity,
                relations = relationsOf(entity.name, relations),
                onOpen = { onOpenInGraph(entity.name) }
            )
        }
    }
}

/** 分区头（标题 + 计数行 —— 与 ChatMemorySection 头部同一视觉语言）。 */
@Composable
private fun SectionHeader(title: String, countText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Hub, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            countText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 实体卡：名称行（类型徽章 + 图谱入口）+ 观察 + 关联。 */
@Composable
private fun EntityCard(
    entity: GraphEntity,
    relations: List<EntityRelationView>,
    onOpen: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { expanded = !expanded })
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
                        .clickable(onClick = onOpen)
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
