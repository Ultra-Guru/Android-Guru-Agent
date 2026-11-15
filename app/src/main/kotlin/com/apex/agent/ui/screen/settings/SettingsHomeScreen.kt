package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 生效时机/作用域徽标行(Spec 01 · §7 简版;Spec 03 U1 完整化)。
 * 页面顶部汇总:「作用域 · 生效时机」;IMMEDIATE 只显示作用域。
 */
@Composable
internal fun SettingsPageMetaBar(page: SettingsPage, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MetaChip(text = stringResource(scopeLabelRes(page.scope)))
        if (page.effective != Effective.IMMEDIATE) {
            MetaChip(text = stringResource(effectiveLabelRes(page.id)))
        }
        if (page.danger) {
            MetaChip(text = stringResource(R.string.settings_scope_danger))
        }
        // 页内 sectionId 计数提示(锚点 chips 见 SettingsAnchorChips)
        Text(
            stringResource(R.string.settings_meta_sections_fmt, page.sectionIds.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun scopeLabelRes(scope: Scope): Int = when (scope) {
    Scope.PROFILE -> R.string.settings_scope_profile
    Scope.GLOBAL -> R.string.settings_scope_global
    Scope.DEVICE -> R.string.settings_scope_device
    Scope.ACCOUNT -> R.string.settings_scope_account
}

/** 收藏星标(抽屉叶子/首页行共用;长按语义由外层 clickable 提供说明)。 */
@Composable
internal fun FavoriteStar(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = if (isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
        contentDescription = stringResource(
            if (isFavorite) R.string.settings_favorite_remove else R.string.settings_favorite_add
        ),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(20.dp)
            .clickable(onClickLabel = null, onClick = onToggle)
    )
}

/** 分组卡(设置首页;点击展开组内页面列表)。 */
@Composable
internal fun SettingsGroupCard(
    group: SettingsGroup,
    expanded: Boolean,
    favorites: Set<String>,
    onToggleExpand: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(group.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(group.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.settings_group_page_count_fmt, group.pageIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                group.pageIds.forEach { pageId ->
                    val page = SettingsPages.byId[pageId] ?: return@forEach
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPage(pageId) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            stringResource(page.titleRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        FavoriteStar(
                            isFavorite = pageId in favorites,
                            onToggle = { onToggleFavorite(pageId) }
                        )
                    }
                }
            }
        }
    }
}

/** quick 区行(收藏/最近/新手必配,≤6;Spec 01 FR-201/§4.4)。 */
@Composable
internal fun SettingsQuickRow(
    pageId: String,
    badge: ImageVector?,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val page = SettingsPages.byId[pageId] ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenPage(pageId) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (badge != null) {
            Icon(badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(stringResource(page.titleRes), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 设置首页(Spec 01 · FR-201):搜索 + quick(收藏/最近/新手必配,≤6)+
 * 五个分组卡(点击展开组内页面)。手机竖屏的抽屉保留为二级入口。
 */
@Composable
internal fun SettingsHomeScreen(
    navPrefs: SettingsNavPrefs,
    favoritesTick: Int,
    onOpenPage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenDrawer: () -> Unit
) {
    // favoritesTick 触发重组读取最新收藏
    val favorites = remember(favoritesTick) { navPrefs.favorites() }
    val recent = remember(favoritesTick) { navPrefs.recent() }
    val seen = remember(favoritesTick) { navPrefs.seenOnboardingPages() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSearchField(onOpenPage = { pageId, _ -> onOpenPage(pageId) })

        // ═══ quick 区:新手必配(未处理的排前) + 收藏 + 最近(去重 ≤6)═══
        val onboarding = SettingsNavPrefs.ONBOARDING_PAGES.filter { it !in seen }
        val quick = (onboarding + favorites + recent)
            .distinct()
            .filter { it in SettingsPages.ids }
            .take(SettingsNavPrefs.RECENT_LIMIT)
        if (quick.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_quick_title),
                style = MaterialTheme.typography.titleSmall
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    quick.forEach { pageId ->
                        val isFavorite = pageId in favorites
                        val isOnboarding = pageId in onboarding
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenPage(pageId)
                                    if (isOnboarding) navPrefs.markOnboardingSeen(pageId)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                stringResource(SettingsPages.byId[pageId]!!.titleRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isOnboarding) {
                                Text(
                                    stringResource(R.string.settings_quick_onboarding),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            FavoriteStar(
                                isFavorite = isFavorite,
                                onToggle = { onToggleFavorite(pageId) }
                            )
                        }
                    }
                }
            }
        }

        // ═══ 分组卡 ×5 ═══
        var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
        SettingsNavGroups.all.forEach { group ->
            SettingsGroupCard(
                group = group,
                expanded = group.groupId in expandedGroupIds,
                favorites = favorites,
                onToggleExpand = {
                    expandedGroupIds = if (group.groupId in expandedGroupIds)
                        expandedGroupIds - group.groupId else expandedGroupIds + group.groupId
                },
                onToggleFavorite = onToggleFavorite,
                onOpenPage = onOpenPage
            )
        }
    }
}
