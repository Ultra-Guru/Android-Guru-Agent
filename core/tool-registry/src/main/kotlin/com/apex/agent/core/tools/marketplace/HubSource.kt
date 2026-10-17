package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * ═══ 官方 Hub 仓库源（技能 + MCP 双目录）═══
 *
 * 学习业界标准 CLI 编码智能体的远程注册表模式：`index.json` 只放元数据（小体积、一次拉全），
 * 技能正文按需单文件下载（raw.githubusercontent.com）；MCP 配置极小直接内联
 * 在索引里（一次请求即完整目录）。
 *
 * - **技能仓库**（apex-skill-hub）：62 个生活/通用型 Agent 技能 —— APK 内置
 *   瘦身到 13 个核心后，其余全部迁往该仓库，市场里按需安装；
 * - **MCP 仓库**（apex-mcp-hub）：沙箱（PRoot Ubuntu 内 npx）与远端 MCP
 *   服务器目录 —— 安装（enabled=false）→ 配置 → 启动 的完整闭环走市场。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测；所有网络调用
 * `withContext(Dispatchers.IO)`，响应体有大小上限（2MB），任何失败返回
 * [Result.failure]，不向调用方抛异常（与 [ClawHubSource]/[ModelScopeSource]
 * 的错误契约一致）。
 */
class HubSource(
    private val httpClient: OkHttpClient
) {

    // ═══ 技能仓库 ═══

    /** 技能目录条目（index.json 的 `skills[]` 元素）。 */
    data class HubSkillEntry(
        val id: String,
        val name: String,
        val version: String = "1.0.0",
        val description: String = "",
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val scope: String = "agent",
        val author: String = "",
        /** 相对仓库根的 manifest 路径（下载时拼 RAW 基址）。 */
        val file: String
    ) {
        /** 条目唯一键。 */
        val key: String get() = "hub/$id"

        /** 对某工位是否可见（scope=all 双工位都保留，与市场分级口径一致）。 */
        fun visibleToScope(scope: String): Boolean =
            scope.isBlank() || this.scope == "all" || this.scope == scope
    }

    /**
     * 拉取技能仓库目录（`index.json`）。成功返回全部条目，调用方按工位/查询过滤。
     *
     * #339：index.json 直连 raw.githubusercontent.com（国内大面积不可达）
     * 改走 [RawGitHubMirrors.fetchWithFallback]（jsdelivr 三级 CDN → 直连）。
     */
    suspend fun listSkills(): Result<List<HubSkillEntry>> = withContext(Dispatchers.IO) {
        val body = try {
            RawGitHubMirrors.fetchWithFallback(httpClient, SKILL_HUB_INDEX_URL, MAX_JSON_BYTES)
                .toString(Charsets.UTF_8)
        } catch (e: IOException) {
            return@withContext Result.failure(Exception(hubUnavailableMessage("skill", e.message)))
        }
        parseSkillIndex(body)
    }

    /**
     * 下载单个技能的完整 manifest JSON 文本（apex-skill-v1），交给
     * MarketInstallManager.installSkillFromJson 走统一安装管道。
     *
     * #339：manifest 直链改走镜像 fallback（jsdelivr 三级 CDN → 直连）。
     */
    suspend fun downloadSkillManifest(entry: HubSkillEntry): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = RawGitHubMirrors.fetchWithFallback(
                    httpClient,
                    skillManifestUrl(entry),
                    MAX_JSON_BYTES
                )
                Result.success(bytes.toString(Charsets.UTF_8))
            } catch (e: IOException) {
                Result.failure(Exception("下载失败: ${e.message}"))
            }
        }

    // ═══ MCP 仓库 ═══

    /** MCP 目录条目（index.json 的 `servers[]` 元素，含完整安装配置）。 */
    data class HubMcpEntry(
        val name: String,
        val description: String = "",
        /** "STDIO" | "HTTP" | "SSE"（安装时映射 McpTransport）。 */
        val transport: String = "STDIO",
        val url: String = "",
        val command: String? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val runInSandbox: Boolean = false,
        /** 安装时初始启停态（仓库默认 false —— 安装 ≠ 启动）。 */
        val enabled: Boolean = false,
        val scope: String = "all",
        /** true = 需要 Ubuntu rootfs（终端页安装）—— UI 据此展示引导。 */
        val requiresRootfs: Boolean = false,
        val vendor: String = "",
        val tags: List<String> = emptyList()
    ) {
        /** 对某工位是否可见。 */
        fun visibleToScope(scope: String): Boolean =
            scope.isBlank() || this.scope == "all" || this.scope == scope

        /** 一行端点摘要（与 MarketMcpRow.endpointSummary 同口径）。 */
        fun endpointSummary(): String = when (transport.uppercase()) {
            "STDIO" -> (listOfNotNull(command?.takeIf { it.isNotBlank() }) + args)
                .joinToString(" ").trim().ifEmpty { "(未配置命令)" }
            else -> url.ifBlank { "(未配置 URL)" }
        }
    }

    /**
     * 拉取 MCP 仓库目录（`index.json`，配置内联 —— 单次请求即完整目录）。
     *
     * #339：同 [listSkills]，改走镜像 fallback。
     */
    suspend fun listMcpServers(): Result<List<HubMcpEntry>> = withContext(Dispatchers.IO) {
        val body = try {
            RawGitHubMirrors.fetchWithFallback(httpClient, MCP_HUB_INDEX_URL, MAX_JSON_BYTES)
                .toString(Charsets.UTF_8)
        } catch (e: IOException) {
            return@withContext Result.failure(Exception(hubUnavailableMessage("mcp", e.message)))
        }
        parseMcpIndex(body)
    }

    // ── 内部实现 ──

    // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    private fun JsonObject.strMap(key: String): Map<String, String> {
        val obj = this.get(key) as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }.toMap()
    }

    private fun hubUnavailableMessage(kind: String, detail: String? = null): String {
        val reason = detail?.let { "：$it" }.orEmpty()
        return "无法连接官方${if (kind == "mcp") " MCP " else " "}仓库（网络不可用或超时，" +
            "jsdelivr 镜像与直连均已尝试$reason）——国内网络可能需要镜像加速"
    }

    companion object {
        /** 官方技能仓库（Ultra-Guru/apex-skill-hub，原 AceGuru-mjh 名下已转移至组织）。 */
        const val SKILL_HUB_REPO = "Ultra-Guru/apex-skill-hub"

        /** 官方 MCP 仓库（Ultra-Guru/apex-mcp-hub，原 AceGuru-mjh 名下已转移至组织）。 */
        const val MCP_HUB_REPO = "Ultra-Guru/apex-mcp-hub"

        private const val SKILL_HUB_RAW_BASE =
            "https://raw.githubusercontent.com/$SKILL_HUB_REPO/main"
        private const val SKILL_HUB_INDEX_URL = "$SKILL_HUB_RAW_BASE/index.json"
        private const val MCP_HUB_INDEX_URL =
            "https://raw.githubusercontent.com/$MCP_HUB_REPO/main/index.json"

        /** JSON 响应上限 2MB（与 ClawHubSource 一致）。 */
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        // ── 纯解析（public：单测直接喋 index.json 文本；无状态无副作用）──

        /**
         * 技能条目 → manifest 下载 URL（raw.githubusercontent.com 直链）。
         *
         * 能力自省闭环：[com.apex.agent.core.tools.catalog.MarketSearchTool]
         * 用它生成 SkillInstall({"source":"url", ...}) 的安装指令——
         * 模型拿到的是可直接执行的完整 URL，而不是需要自行拼接的相对路径。
         */
        fun skillManifestUrl(entry: HubSkillEntry): String =
            "$SKILL_HUB_RAW_BASE/${entry.file}"

        /** 技能目录 index.json 文本 → 条目列表（坏条目跳过，整体损坏转 failure；
         * 上游重复条目按 key 去重 —— LazyColumn 的 key = it.key 不容重复，
         * 不去重会直接抛 IllegalArgumentException 崩市场页）。 */
        fun parseSkillIndex(body: String): Result<List<HubSkillEntry>> = try {
            val root = json.parseToJsonElement(body).jsonObject
            val raw = root["skills"]?.jsonArray ?: emptyList()
            Result.success(
                raw.mapNotNull { el -> (el as? JsonObject)?.let(::parseSkillEntry) }
                    .distinctBy { it.key }
            )
        } catch (e: Exception) {
            Result.failure(Exception("技能仓库目录解析失败: ${e.message}"))
        }

        /** MCP 目录 index.json 文本 → 条目列表（坏条目跳过，整体损坏转 failure；
         * 重复条目按归一化 name 去重（#346：大小写与分隔符变体也折叠）——
         * 消费点列表 key 为 "hub-" + name，不去重会撞 LazyColumn 重复 key 崩溃）。 */
        fun parseMcpIndex(body: String): Result<List<HubMcpEntry>> = try {
            val root = json.parseToJsonElement(body).jsonObject
            val raw = root["servers"]?.jsonArray ?: emptyList()
            Result.success(
                raw.mapNotNull { el -> (el as? JsonObject)?.let(::parseMcpEntry) }
                    .distinctBy { MarketNames.normalize(it.name) }
            )
        } catch (e: Exception) {
            Result.failure(Exception("MCP 仓库目录解析失败: ${e.message}"))
        }

        private fun parseSkillEntry(obj: JsonObject): HubSkillEntry? {
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val file = obj.str("file")?.takeIf { it.isNotBlank() } ?: return null
            return HubSkillEntry(
                id = id,
                name = obj.str("name")?.takeIf { it.isNotBlank() } ?: id,
                version = obj.str("version") ?: "1.0.0",
                description = obj.str("description").orEmpty(),
                category = obj.str("category"),
                tags = obj.strings("tags"),
                scope = obj.str("scope")?.takeIf { it.isNotBlank() } ?: "agent",
                author = obj.str("author").orEmpty(),
                file = file
            )
        }

        private fun parseMcpEntry(obj: JsonObject): HubMcpEntry? {
            val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: return null
            return HubMcpEntry(
                name = name,
                description = obj.str("description").orEmpty(),
                transport = obj.str("transport")?.takeIf { it.isNotBlank() } ?: "STDIO",
                url = obj.str("url").orEmpty(),
                command = obj.str("command"),
                args = obj.strings("args"),
                env = obj.strMap("env"),
                runInSandbox = obj.bool("runInSandbox") ?: false,
                enabled = obj.bool("enabled") ?: false,
                scope = obj.str("scope")?.takeIf { it.isNotBlank() } ?: "all",
                requiresRootfs = obj.bool("requiresRootfs") ?: false,
                vendor = obj.str("vendor").orEmpty(),
                tags = obj.strings("tags")
            )
        }

        // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
        private fun JsonObject?.str(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject?.bool(key: String): Boolean? =
            (this?.get(key) as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.strings(key: String): List<String> =
            (this.get(key) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()

        private fun JsonObject.strMap(key: String): Map<String, String> {
            val obj = this.get(key) as? JsonObject ?: return emptyMap()
            return obj.entries.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }.toMap()
        }
    }
}
