package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 分区导航条（记忆页 v3）—— 列表视图顶部的粘性芯片行。
 *
 * 单列列表重构后页面有四五个分区、可达数十屏；本条提供一键直达：
 *  - 「全部」→ 滚回顶部（概览区）；
 *  - 四分区芯片 → animateScrollToItem(分区锚点)（锚点由
 *    [sectionAnchors] 从计划扫描而来，增删条目不漂移）；
 *  - 当前分区高亮：首可见条目下标对锚点表取「已越过的最后一个头」
 *    （derivedStateOf 读 layoutInfo，滚动即更新，不逐帧重组芯片）。
 *
 * 搜索态 / 图谱视图由宿主隐藏本条（搜索结果整段替换分区内容，
 * 锚点无意义）。
 */

/** 导航芯片描述（label / icon / 目标分区；null = 「全部」滚回顶）。 */
private data class NavChip(
    val label: String,
    val icon: ImageVector,
    val section: MemorySection?,
)

/** 当前分区推导：锚点表内「下标 ≤ 首可见项」的最大分区；无 → null（概览区）。 */
fun currentSection(
    firstVisibleIndex: Int,
    anchors: Map<MemorySection, Int>,
): MemorySection? {
    var current: MemorySection? = null
    anchors.entries
        .sortedBy { it.value }
        .forEach { (section, index) -> if (index <= firstVisibleIndex) current = section }
    return current
}

/**
 * 分区导航条本体。
 *
 * @param anchors 分区 → 列表下标（[sectionAnchors] 产物）
 * @param listStateFirstVisible 首可见条目下标（宿主以 derivedStateOf 传入）
 * @param onNavigate 目标分区（null = 回顶）—— 宿主负责 animateScrollToItem
 */
@Composable
internal fun MemorySectionNavRow(
    anchors: Map<MemorySection, Int>,
    listStateFirstVisible: Int,
    onNavigate: (MemorySection?) -> Unit,
) {
    val chips: List<NavChip> = listOf(
        NavChip(stringResource(R.string.memory_section_all), Icons.Outlined.Apps, section = null),
        NavChip(stringResource(R.string.memory_section_chat), Icons.Outlined.Forum, MemorySection.CHAT),
        NavChip(stringResource(R.string.memory_section_knowledge), Icons.Outlined.Hub, MemorySection.KNOWLEDGE),
        NavChip(stringResource(R.string.memory_section_episodes), Icons.Outlined.History, MemorySection.EPISODES),
        NavChip(stringResource(R.string.memory_section_macros), Icons.Outlined.Repeat, MemorySection.MACROS),
    )
    val current = currentSection(listStateFirstVisible, anchors)

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.section?.name ?: "all" }) { chip ->
            FilterChip(
                selected = current == chip.section,
                onClick = { onNavigate(chip.section) },
                label = { Text(chip.label) },
                leadingIcon = {
                    Icon(
                        chip.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            )
        }
    }
}
