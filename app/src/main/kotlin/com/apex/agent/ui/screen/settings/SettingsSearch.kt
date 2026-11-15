package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/**
 * 设置全局搜索(Spec 01 · PR3 · FR-301~306,页级索引简版)。
 *
 * 索引单元 = 页(SettingsPage.title/summary/keywords;控件级索引随
 * SettingsSection 完整对象在后续接入)。支持中/英/别名与 `key:` 前缀语法
 * (按 sectionId 匹配);排序 = 精确标题 > 别名;结果点击 → 打开对应
 * page.id 并定位 section。无网络请求、不记录输入。
 */
internal data class SettingsSearchHit(
    val pageId: String,
    val sectionId: String?,
    val titleRes: Int,
    val summaryRes: Int,
    val score: Int
)

internal object SettingsSearchIndex {

    /** `key:xxx` 语法:按 sectionId 精确匹配(如 key:rateLimitMinIntervalMs)。 */
    private const val KEY_PREFIX = "key:"

    fun query(pages: List<SettingsPage>, raw: String): List<SettingsSearchHit> {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<SettingsSearchHit>()
        if (q.startsWith(KEY_PREFIX)) {
            val key = q.removePrefix(KEY_PREFIX)
            pages.forEach { page ->
                page.sectionIds.firstOrNull { it.contains(key, ignoreCase = true) }?.let { section ->
                    hits += SettingsSearchHit(page.id, section, page.titleRes, page.summaryRes, 0)
                }
            }
            return hits.sortedBy { it.pageId }
        }
        pages.forEach { page ->
            val title = titleKeywordOf(page)
            var score = -1
            when {
                page.keywords.any { it.lowercase() == q } -> score = 0
                title.lowercase().contains(q) -> score = 1
                page.keywords.any { it.lowercase().contains(q) } -> score = 2
            }
            if (score >= 0) {
                hits += SettingsSearchHit(page.id, null, page.titleRes, page.summaryRes, score)
            }
        }
        return hits.sortedWith(compareBy({ it.score }, { it.pageId }))
    }

    /** 标题搜索键(page.titleRes 为资源,补一份字面别名供匹配)。 */
    private fun titleKeywordOf(page: SettingsPage): String = page.keywords.firstOrNull() ?: page.id
}

/** 搜索框 + 结果列表(点击 → [onOpenPage](pageId, sectionId))。 */
@Composable
internal fun SettingsSearchField(
    modifier: Modifier = Modifier,
    onOpenPage: (pageId: String, sectionId: String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val hits = remember(query) { SettingsSearchIndex.query(SettingsPages.all, query) }
        if (query.isNotBlank() && hits.isEmpty()) {
            Text(
                stringResource(R.string.settings_search_no_result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        hits.take(8).forEach { hit ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPage(hit.pageId, hit.sectionId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(hit.titleRes), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(hit.summaryRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    stringResource(effectiveLabelRes(hit.pageId)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 页级生效时机 → 徽标文案(§4.3 表;IMMEDIATE 不显示,回退空串由调用方隐藏)。 */
@Composable
internal fun effectiveLabelRes(pageId: String): Int =
    when (SettingsPages.byId[pageId]?.effective) {
        Effective.IMMEDIATE, null -> R.string.settings_effective_immediate
        Effective.NEXT_RUN -> R.string.settings_effective_next_run
        Effective.NEXT_SESSION -> R.string.settings_effective_next_session
        Effective.RESTART -> R.string.settings_effective_restart
    }
