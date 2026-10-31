package com.apex.agent.github.mcp

/**
 * # GitHub 官方远程 MCP 工具集（toolsets）模型
 *
 * 用户规格：「github mcp 要放在输入框的 GitHub 图标里，点击后可以在那里
 * 配置」。官方远程服务器（[OfficialGithubMcp.REMOTE_URL]）支持按工具集
 * 过量订阅：URL 带 `?toolsets=a,b` 查询参数时只暴露所选集的工具面；
 * 不带参数时用服务器默认集。
 *
 * 官方工具集清单（github-mcp-server 文档口径）：repos / issues /
 * pull_requests / actions / code_security / users / orgs / notifications /
 * discussions / gists。
 *
 * ## 语义约定（UI 与配置存储共用）
 *
 * - **全选 = 不带参数**（服务器默认集）——「全开」是最常见形态，存储为
 *   无参数 URL，行为与官方默认完全一致；
 * - 部分选择 = `?toolsets=…` 显式列出（官方端点按序消费，逗号分隔）；
 * - [parseSelected] 对未知参数值防御式过滤（官方未来新增集 → 旧 App
 *   读到未知 key 静默丢弃，不炸不写坏配置）。
 *
 * 纯 JVM 可测（无 Android 依赖），单测见 GithubMcpToolsetsTest。
 */
object GithubMcpToolsets {

    /** 单个工具集：key（官方参数名）+ 中文显示名 + 能力摘要。 */
    data class Toolset(
        val key: String,
        val zhLabel: String,
        val summary: String
    )

    /** 官方工具集全清单（展示序 = 官方文档序）。 */
    val ALL: List<Toolset> = listOf(
        Toolset("repos", "仓库", "分支创建/推送、文件读写、fork、搜索"),
        Toolset("issues", "议题", "创建/更新/评论/列表/搜索"),
        Toolset("pull_requests", "拉取请求", "创建/合并/评审/评论"),
        Toolset("actions", "工作流", "run 触发/列表/日志/产物"),
        Toolset("code_security", "代码安全", "code scanning 告警"),
        Toolset("users", "用户", "资料/搜索"),
        Toolset("orgs", "组织", "组织/成员/角色"),
        Toolset("notifications", "通知", "列表/已读标记"),
        Toolset("discussions", "讨论", "列表/创建/评论"),
        Toolset("gists", "Gist", "创建/列表/读取")
    )

    /** 全集 key（参数形态判断用）。 */
    private val allKeys: Set<String> = ALL.map { it.key }.toSet()

    /** 默认选中集 = 全部（= 无 toolsets 参数，走服务器默认）。 */
    val DEFAULT_SELECTED: Set<String> = allKeys

    /**
     * 选中集 → 官方远程 URL。
     *
     * 全选/空集 → 裸端点（服务器默认集）；部分选择 → `?toolsets=` 显式
     * 列出（官方文档口径：逗号分隔、按声明序）。
     */
    fun urlWithToolsets(selected: Set<String>): String {
        val sanitized = selected.filter { it in allKeys }.toSet()
        if (sanitized.isEmpty() || sanitized == allKeys) return OfficialGithubMcp.REMOTE_URL
        return OfficialGithubMcp.REMOTE_URL + "?toolsets=" +
            ALL.filter { it.key in sanitized }.joinToString(",") { it.key }
    }

    /**
     * 官方远程 URL → 选中集（[urlWithToolsets] 的逆函数）。
     *
     * - 无参数 → 全集（服务器默认语义的 UI 映射）；
     * - `?toolsets=a,b` → 未知 key 过滤后的子集；
     * - 空/无效参数 → 空集回退全集口径由调用方决定（这里返回全集 ——
     *   「读不懂的配置 = 全开」比「读不懂就全关」更接近用户预期）。
     */
    fun parseSelected(url: String): Set<String> {
        val base = OfficialGithubMcp.REMOTE_URL
        if (!url.startsWith(base)) return allKeys
        val query = url.removePrefix(base)
        if (query.isBlank()) return allKeys
        val params = query.trimStart('?')
        val toolsetsParam = params.split('&')
            .firstOrNull { it.startsWith("toolsets=") }
            ?: return allKeys
        val selected = toolsetsParam.removePrefix("toolsets=")
            .split(',')
            .map { it.trim() }
            .filter { it in allKeys }
            .toSet()
        return selected.ifEmpty { allKeys }
    }

    /** key → 显示用 Toolset（未知 key → null）。 */
    fun byKey(key: String): Toolset? = ALL.firstOrNull { it.key == key }
}
