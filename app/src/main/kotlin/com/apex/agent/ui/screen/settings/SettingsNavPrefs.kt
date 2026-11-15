package com.apex.agent.ui.screen.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 设置导航偏好(Spec 01 · PR2 · FR-207 / §8 存储):
 *
 * - **quick 区合成源**:`recent`(LRU 最多 6,page.id)+ `favorites`(收藏集,
 *   抽屉叶子/标签长按进出)+ `seenOnboarding`(新手必配 3 项已处理标记);
 * - 展开分组 / 抽屉滚动位置持久化(FR-202);
 * - **独立于 `apex_settings`**(不进入备份 Bundle 必选字段,formatVersion
 *   兼容),进程重建/旋转/语言切换后保持。
 *
 * 全部值为 page.id 字符串;写入即落盘(低频小体量)。
 */
class SettingsNavPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("settings_nav_prefs", Context.MODE_PRIVATE)

    // ── 最近使用(LRU,最多 6)──

    fun recent(): List<String> =
        prefs.getString(KEY_RECENT, null)?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

    fun recordRecent(pageId: String) {
        if (pageId.isEmpty()) return
        val next = (listOf(pageId) + recent().filterNot { it == pageId }).take(RECENT_LIMIT)
        prefs.edit().putString(KEY_RECENT, next.joinToString(SEP)).apply()
    }

    fun removeRecent(pageId: String) {
        prefs.edit().putString(KEY_RECENT, recent().filterNot { it == pageId }.joinToString(SEP)).apply()
    }

    // ── 收藏 ──

    fun favorites(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun toggleFavorite(pageId: String) {
        val next = favorites().toMutableSet()
        if (!next.add(pageId)) next.remove(pageId)
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
    }

    // ── 抽屉展开分组(多开,FR-202)──

    fun expandedGroups(): Set<String> =
        prefs.getStringSet(KEY_EXPANDED, emptySet())?.toSet() ?: emptySet()

    fun setExpandedGroups(groups: Set<String>) {
        prefs.edit().putStringSet(KEY_EXPANDED, groups).apply()
    }

    // ── 新手必配(3 项置顶;用户点开对应页或手动移除后不再出现)──

    fun seenOnboardingPages(): Set<String> =
        prefs.getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()

    fun markOnboardingSeen(pageId: String) {
        prefs.edit().putStringSet(KEY_SEEN, seenOnboardingPages() + pageId).apply()
    }

    companion object {
        private const val SEP = "\u0000"
        private const val KEY_RECENT = "recent_pages"
        private const val KEY_FAVORITES = "favorite_pages"
        private const val KEY_EXPANDED = "expanded_groups"
        private const val KEY_SEEN = "onboarding_seen"

        /** LRU 上限(Spec 01 FR-207:最多 6)。 */
        const val RECENT_LIMIT = 6

        /** 新手必配 3 项(§4.4):连接与档案 / 工具权限 / 备份与恢复。 */
        val ONBOARDING_PAGES = listOf("model.connection", "agent.permissions", "system.backup")
    }
}
