package com.apex.agent.ui.screen.settings

/**
 * 旧 route → page.id 别名表(Spec 01 · PR1 · FR-105)。
 *
 * 覆盖旧导航树全部 23 个两段式 route —— 分类重排/页面合并后,已持久化的
 * 标签、恢复串、深链仍可静默解析到稳定 page.id(升级零丢失)。本表纯 JVM
 * 可单测(SettingsNavAliasesTest 全量断言自 SettingsNavTree 自动生成)。
 *
 * 合并语义:
 * - `model/setup` 与 `model/providers-roles` → `model.connection`(合并页);
 * - `interface/appearance` → `appearance.theme`(一拆四中的主页);
 * - `terminal/policy` → `agent.policy`(安全类归并)、`terminal/service` →
 *   `services.keepAlive`(服务类归并)。
 */
object SettingsNavAliases {

    val aliases: Map<String, String> = mapOf(
        // ═══ model(8)═══
        "model/setup" to "model.connection",
        "model/providers-roles" to "model.connection",
        "model/generation" to "model.generation",
        "model/reasoning" to "model.reasoning",
        "model/context" to "model.context",
        "model/tools" to "model.tools",
        "model/network" to "model.network",
        "model/prompt" to "model.prompt",
        // ═══ workspace(6)═══
        "workspace/coding" to "agent.coding",
        "workspace/subagent" to "agent.subagent",
        "workspace/permissions" to "agent.permissions",
        "workspace/rules" to "agent.rules",
        "workspace/hooks" to "agent.hooks",
        "workspace/goal" to "agent.goal",
        // ═══ integrations(3)═══
        "integrations/github" to "services.github",
        "integrations/search" to "services.search",
        "integrations/memory" to "services.memory",
        // ═══ terminal(2)═══
        "terminal/policy" to "agent.policy",
        "terminal/service" to "services.keepAlive",
        // ═══ interface(2)═══
        "interface/appearance" to "appearance.theme",
        "interface/notifications" to "appearance.notifications",
        // ═══ data(2)═══
        "data/backup" to "system.backup",
        "data/update" to "system.update",
    )

    /**
     * 任意输入(route 或 page.id)→ page.id;未知返回 null(调用方丢弃该项,
     * 绝不清空全局状态 —— BUG-08 契约)。page.id 输入幂等返回自身。
     */
    fun resolvePageId(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw in SettingsPages.ids) return raw
        val mapped = aliases[raw] ?: return null
        return mapped.takeIf { it in SettingsPages.ids }
    }

    /** page.id → 主旧 route(展示兼容:抽屉分类高亮/旧图标标题反查);无则 null。 */
    fun legacyRouteOf(pageId: String): String? =
        aliases.entries.firstOrNull { it.value == pageId }?.key
}
