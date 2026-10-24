package com.apex.agent.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 设置抽屉内容（两级分层）。
 *
 * 交互模型（设置页完全重构 v1）：
 * - 第一列 = 一级大分类（模型配置 / Agent 配置 / 界面外观 / 数据与备份）；
 * - 点击分类 → 手风琴式展开其二级叶子（同一时刻仅一个分类展开）；
 * - 点击叶子 → 由上层关闭设置抽屉并打开对应配置页面（PR2 中会追加为
 *   浏览器式标签）。
 *
 * 视觉对齐总抽屉的 GlassNavigationItem 语言：圆角胶囊选中态 + 主色
 * 图标；MINIMAL/LIQUID_GLASS 双风格由全局主题短路（GlassSurface 单点）。
 */
@Composable
fun SettingsDrawerSheet(
    expandedCategory: String?,
    activeLeafRoute: String?,
    onToggleCategory: (String) -> Unit,
    onSelectLeaf: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsDrawerHeader()
            Spacer(Modifier.size(4.dp))

            SettingsNavTree.forEach { category ->
                CategoryItem(
                    category = category,
                    expanded = expandedCategory == category.route,
                    activeLeafRoute = activeLeafRoute,
                    onToggle = { onToggleCategory(category.route) },
                    onSelectLeaf = onSelectLeaf
                )
            }
        }
    }
}

/** 抽屉头：标题 + 一行说明（区别于总抽屉的品牌头）。 */
@Composable
private fun SettingsDrawerHeader() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_drawer_title),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            stringResource(R.string.settings_drawer_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 一级分类行：点击展开/收起二级；所属叶子激活时分类保持高亮。 */
@Composable
private fun CategoryItem(
    category: SettingsCategory,
    expanded: Boolean,
    activeLeafRoute: String?,
    onToggle: () -> Unit,
    onSelectLeaf: (String) -> Unit
) {
    val containsActive = activeLeafRoute != null &&
        category.children.any { it.route == activeLeafRoute }
    val label = stringResource(category.titleRes)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (expanded || containsActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClickLabel = label) { onToggle() }
            .semantics { contentDescription = label }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                category.icon,
                contentDescription = null,
                tint = if (expanded || containsActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (expanded || containsActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }

    // 二级叶子：展开态下缩进列出（点击即进入配置页面）
    AnimatedVisibility(visible = expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            category.children.forEach { leaf ->
                LeafItem(
                    leaf = leaf,
                    active = leaf.route == activeLeafRoute,
                    onClick = { onSelectLeaf(leaf.route) }
                )
            }
        }
    }
}

/** 二级叶子行（最终点击目标 → 打开配置页面）。 */
@Composable
private fun LeafItem(
    leaf: SettingsLeaf,
    active: Boolean,
    onClick: () -> Unit
) {
    val label = stringResource(leaf.titleRes)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label) { onClick() }
            .semantics { contentDescription = label }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                leaf.icon,
                contentDescription = null,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
