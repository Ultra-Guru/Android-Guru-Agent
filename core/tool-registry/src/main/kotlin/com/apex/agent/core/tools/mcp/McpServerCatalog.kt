package com.apex.agent.core.tools.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #205 MCP 精选目录 —— assets/mcp_catalog 目录下分类 JSON 文件的数据模型与解析器。
 *
 * 目录是**离线随包分发**的精选清单（16 个分类文件 / 42 条真实服务器条目，
 * 包名与端点均经 registry / 原始 README 联网核对），市场页「精选目录」区
 * 直接浏览：一条条目 = 一份可一键安装的 [McpServerConfig] 预填模板
 * （用户只需补齐密钥类环境变量）。
 *
 * ## 与 [McpConfigImport] 的分工
 * - Import 面向「社区通用 JSON 粘贴」（`{"mcpServers": {...}}`，无目录化元数据）；
 * - Catalog 面向「官方精选 + 分类浏览 + 环境变量引导」—— [McpCatalogEntry.envSchema]
 *   声明每个服务器需要哪些密钥/参数，安装弹窗据此生成表单，而不是让用户
 *   对着 README 猜环境变量名。
 *
 * ## schema：apex-mcp-catalog-v1
 * 顶层 `{schema, category, categoryLabel, entries: [...]}`；未知字段前向兼容
 * （[Json] 配 ignoreUnknownKeys）。
 */
object McpServerCatalog {

    /** 目录 schema 标识（顶层 schema 字段必须等于它才认）。 */
    const val SCHEMA = "apex-mcp-catalog-v1"

    private val json = Json { ignoreUnknownKeys = true }

    /** 合法分类文件名（也是 [McpCatalogEntry.category] 的取值域）。 */
    val CATEGORY_ORDER: List<String> = listOf(
        "Official", "WebSearch", "Browser", "Database", "Git", "Cloud",
        "Observability", "Docs", "Productivity", "Desktop", "Finance",
        "Design", "Communication", "Location", "Data", "Remote"
    )

    /**
     * v5：条目 id 改无分隔符 PascalCase（`Fetch` / `ServerGithub`）。为兼容
     * 仍带 kebab 的第三方目录文件，正则继续接受 `-`，但不再接受 `_`。
     */
    private val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,48}$")

    // ═══ 数据模型 ═══

    /** 目录条目的环境变量声明（安装表单据此渲染）。 */
    @Serializable
    data class McpCatalogEnvVar(
        val key: String,
        val required: Boolean = false,
        val description: String = ""
    )

    /**
     * 一条精选服务器条目。
     *
     * @param id 目录内唯一标识（安装默认名，无分隔符 PascalCase）
     * @param transport STDIO（本地命令）/ HTTP / SSE（远端）
     * @param command STDIO 可执行文件（null 仅远端条目）
     * @param url 远端端点（null 仅 STDIO 条目）
     * @param runtime node | python | java | docker | remote（沙箱就绪提示用）
     * @param tier agent | coding | all（#197 市场分级过滤）
     * @param risk low | medium | high（安装弹窗风险提示）
     * @param sandboxOnly true = 只能以 runInSandbox=true 安装（如 python/docker
     *   型命令在 Android 宿主进程内不存在）
     */
    @Serializable
    data class McpCatalogEntry(
        val id: String,
        val name: String,
        val description: String,
        val descriptionZh: String = "",
        val transport: McpTransport,
        val command: String? = null,
        val args: List<String> = emptyList(),
        val url: String? = null,
        val envSchema: List<McpCatalogEnvVar> = emptyList(),
        val runtime: String = "node",
        val tier: String = "all",
        val risk: String = "low",
        val sandboxOnly: Boolean = false,
        val homepage: String? = null,
        val notes: String? = null,
        /** 解析时由文件名回填（分类文件 category 字段）。 */
        val category: String = ""
    ) {
        /** #197 分级可见性（与 [McpServerConfig.visibleToScope] 同语义）。 */
        fun visibleToTier(tier: String): Boolean =
            tier.isBlank() || this.tier == "all" || this.tier == tier

        /** 用户必填的环境变量声明。 */
        fun requiredEnv(): List<McpCatalogEnvVar> = envSchema.filter { it.required }

        /** 按语言取简介。 */
        fun descriptionFor(langZh: Boolean): String =
            if (langZh && descriptionZh.isNotBlank()) descriptionZh else description
    }

    /** 分类文件（一个 JSON 文件 = 一个分类 + N 条条目）。 */
    @Serializable
    data class McpCatalogFile(
        val schema: String,
        val category: String,
        val categoryLabel: String = "",
        val entries: List<McpCatalogEntry> = emptyList()
    )

    // ═══ 解析 ═══

    /**
     * 解析一个分类文件文本。
     *
     * 校验：schema 必须匹配 [SCHEMA]；`entries` 非空；每条条目回填
     * `category`（缺省取文件名分类）。任何结构性问题返回 failure（带原因），
     * 不做静默吞条目。
     */
    fun parseCategoryFile(text: String, fallbackCategory: String = ""): Result<McpCatalogFile> = runCatching {
        val file = json.decodeFromString<McpCatalogFile>(text)
        require(file.schema == SCHEMA) {
            "未知 schema '${file.schema}'（期望 $SCHEMA）"
        }
        val category = file.category.ifBlank { fallbackCategory }
        require(category.isNotBlank()) { "缺少 category 字段" }
        require(file.entries.isNotEmpty()) { "entries 为空" }
        file.copy(category = category, entries = file.entries.map { it.copy(category = category) })
    }

    // ═══ 校验（数据源质检 / 测试用，安装路径用 [McpConfigValidator] 做运行时校验）═══

    /** 校验问题（id + 原因）。 */
    data class CatalogIssue(val entryId: String, val reason: String)

    /**
     * 目录内一致性校验：id 全局唯一且合法、枚举取值、传输形态与字段匹配、
     * envSchema 键无重复。返回问题清单（空 = 通过）。
     */
    fun validateEntries(entries: List<McpCatalogEntry>): List<CatalogIssue> {
        val issues = mutableListOf<CatalogIssue>()
        val seen = HashSet<String>()
        for (e in entries) {
            fun issue(reason: String) { issues += CatalogIssue(e.id, reason) }
            if (!ID_REGEX.matches(e.id)) issue("id 非法：'${e.id}'（字母/数字开头，2-49 位，禁止下划线）")
            if (!seen.add(e.id)) issue("id 重复：'${e.id}'")
            if (e.name.isBlank()) issue("name 为空")
            if (e.transport !in listOf(McpTransport.STDIO, McpTransport.HTTP, McpTransport.SSE)) {
                issue("transport 非法：${e.transport}（目录不收录 BUILTIN）")
            }
            if (e.transport == McpTransport.STDIO && e.command.isNullOrBlank()) issue("STDIO 缺 command")
            if (e.transport != McpTransport.STDIO && e.url.isNullOrBlank()) issue("远端缺 url")
            if (e.runtime !in listOf("node", "python", "java", "docker", "remote")) {
                issue("runtime 非法：${e.runtime}")
            }
            if (e.tier !in listOf("agent", "coding", "all")) issue("tier 非法：${e.tier}")
            if (e.risk !in listOf("low", "medium", "high")) issue("risk 非法：${e.risk}")
            if (e.runtime == "python" && !e.sandboxOnly) issue("python 运行时必须 sandboxOnly=true")
            val keys = e.envSchema.map { it.key }
            if (keys.size != keys.toSet().size) issue("envSchema 键重复")
            if (keys.any { it.isBlank() }) issue("envSchema 键为空")
        }
        return issues
    }

    // ═══ 条目 → 配置 ═══

    /**
     * 把目录条目装配成可连接的 [McpServerConfig]。
     *
     * @param envValues 用户在安装表单里填写的环境变量（只收非空值；键不在
     *   envSchema 声明内的也会带上——高级用户改 args 时可能配套加变量）
     * @param name 服务器名（默认条目 id；重名时调用方负责加后缀）
     * @param runInSandbox 是否走沙箱（sandboxOnly=true 的条目强制 true）
     */
    fun toServerConfig(
        entry: McpCatalogEntry,
        envValues: Map<String, String> = emptyMap(),
        name: String = entry.id,
        runInSandbox: Boolean = false
    ): McpServerConfig {
        val sandbox = entry.sandboxOnly || runInSandbox
        val env = envValues.filterValues { it.isNotBlank() }
        return when (entry.transport) {
            McpTransport.STDIO -> McpServerConfig(
                name = name,
                transport = McpTransport.STDIO,
                command = entry.command,
                args = entry.args,
                env = env,
                runInSandbox = sandbox,
                enabled = true,
                scope = entry.tier
            )
            McpTransport.HTTP, McpTransport.SSE -> McpServerConfig(
                name = name,
                transport = entry.transport,
                url = entry.url.orEmpty(),
                env = env,
                enabled = true,
                scope = entry.tier
            )
            McpTransport.BUILTIN -> throw IllegalArgumentException(
                "目录条目 '${entry.id}' 是 BUILTIN 传输 —— 仅 App 预置，不可从目录安装"
            )
        }
    }

    /**
     * 市场页「环境变量引导」语义：必填变量缺值时装配出的配置无法工作，
     * 安装弹窗据此拦下并指出缺哪个键（而不是连上之后在握手处报一嘴 401）。
     */
    fun missingRequiredEnv(entry: McpCatalogEntry, envValues: Map<String, String>): List<String> =
        entry.requiredEnv().map { it.key }.filter { envValues[it].isNullOrBlank() }
}
