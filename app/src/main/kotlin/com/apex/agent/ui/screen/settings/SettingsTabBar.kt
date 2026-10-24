package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 浏览器式标签条（设置页面完全重构第二阶段）。
 *
 * 打开的配置页面以浏览器标签的形式排列在页面最上方（顶栏下、内容区上）：
 * - 点击抽屉二级叶子 → 新标签追加（已打开则仅激活，不重复开）；
 * - 点击标签 → 激活切换（各标签页面状态独立保留）；
 * - 点击叉号 → 关闭该标签：关闭激活标签时回退到**相邻标签**（左优先，
 *   无左取右）；全部关闭 → 页面消失、设置抽屉重新打开（返回选择分类）。
 *
 * 视觉对齐浏览器惯例：激活标签底色主色 tint + 与内容区贴合的顶圆角
 * 底直角造型；横向滚动容纳任意数量标签。
 */
@Composable
fun SettingsTabBar(
    tabs: List<String>,
    activeTab: String,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Settings tabs" },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(tabs, key = { it }) { route ->
            val leaf = leafOf(route)
            SettingsTabChip(
                title = leaf?.let { stringResource(it.titleRes) } ?: route,
                icon = leaf?.icon,
                active = route == activeTab,
                onClick = { onTabClick(route) },
                onClose = { onTabClose(route) }
            )
        }
    }
}

/** 单个标签：图标 + 标题 + 叉号；激活态主色 tint + 浏览器标签造型。 */
@Composable
private fun SettingsTabChip(
    title: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val closeDesc = stringResource(R.string.settings_tab_close, title)
    Surface(
        // 顶圆角、底直角：激活标签与下方内容区视觉连成一体（浏览器 tab 语言）
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        tonalElevation = if (active) 1.dp else 0.dp,
        modifier = Modifier.clickable(onClickLabel = title) { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp)
            )
            // 叉号：点按关闭（触区收紧到 28dp——浏览器 tab 惯例密度，
            // 外层已有 8dp 垂直 padding 补足最小触区）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(28.dp)
                    .clickable(onClickLabel = closeDesc) { onClose() }
                    .semantics { contentDescription = closeDesc }
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 标签关闭回退规则（纯函数，便于单测与边界推演）：
 * - 关闭非激活标签 → 激活态不变；
 * - 关闭激活标签 → 回退到相邻标签（左优先，无左取右）；
 * - 全部关闭 → 返回 null（上层据此重开设置抽屉）。
 *
 * @return 剩余标签列表与新激活 route（null = 无标签）
 */
fun closeSettingsTab(
    tabs: List<String>,
    active: String?,
    route: String
): Pair<List<String>, String?> {
    val idx = tabs.indexOf(route)
    if (idx < 0) return tabs to active
    val next = tabs.toMutableList().apply { removeAt(idx) }
    val nextActive = if (route == active) {
        if (next.isEmpty()) null
        else next[(idx - 1).coerceAtLeast(0).coerceAtMost(next.lastIndex)]
    } else {
        active
    }
    return next.toList() to nextActive
}
