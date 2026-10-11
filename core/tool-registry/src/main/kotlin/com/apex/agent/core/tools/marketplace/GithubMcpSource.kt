package com.apex.agent.core.tools.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ═══ GitHub MCP 仓库探测源（Anthropic 官方 server 仓库直装）═══
 *
 * 「从 GitHub 安装」的 MCP 分支：把 GitHub 仓库探测为可安装的 MCP server
 * 集合，统一转成 [RegistryServer] —— 复用既有安装决策树（远端直装 /
 * npm 沙箱预装 / 不支持引导），不另造安装管道。
 *
 * 识别三种 MCP 仓库形态（候选合并策略见 [detectServers]）：
 * 1. **根 server.json**（MCP Registry publish_to_registry 规范，snake_case）：
 *    `packages` + `remotes` 直接映射 [RegistryServer] 字段 —— 命中即唯一
 *    候选（一仓一 server 的明确声明）；
 * 2. **根 .mcp.json**（Claude Code / 通用 mcp config）：`mcpServers` 映射表，
 *    每条 entry 转一台服务器（npx 型走 npm 沙箱、http 型走远端直连、
 *    其余 command 诚实跳过）；
 * 3. **monorepo 参考实现形态**（modelcontextprotocol/servers 血统）：
 *    src 一级子目录各含 package.json，mcpName 字段（或 npm 名含 mcp 且
 *    带可执行 bin）判定为 MCP server，npm 包名进 packages。与 .mcp.json
 *    候选合并 —— 根配置常是开发用（如 mcp-docs），真正的 server 在 src。
 *
 * 技能集合仓库（子目录 SKILL.md，anthropics/skills 血统）的树拉取
 * 复用 [fetchRepoTreePaths]（技能候选发现由 app 层 MarketInstallManager
 * 消费，本类只管 MCP 形态）。
 *
 * 纯 JVM（OkHttp + kotlinx.serialization），可单测；网络调用
 * `withContext(Dispatchers.IO)`，响应体 2MB 上限，树条目数上限
 * [MAX_TREE_ENTRIES]（GitHub recursive tree 最大可达十万级，移动端
 * 截断防 OOM），任何失败 Result.failure 不抛异常（与其他源错误契约一致）。
 */
class GithubMcpSource(
    private val httpClient: OkHttpClient,
    /** 附加请求头（GitHub token 等由宿主注入；空 map = 匿名访问）。 */
    private val extraHeaders: () -> Map<String, String> = { emptyMap() }
) {

    /**
     * 探测仓库里的全部 MCP server 候选。返回条目按 displayName 去重 ——
     * 消费点列表 key 不容重复。
     *
     * 合并策略（P1：根 .mcp.json 不短路 monorepo 探测）：根 server.json 是
     * publish_to_registry 声明（一仓一 server 的明确意图），命中即返回唯一
     * 候选；否则根 .mcp.json 的 mcpServers 与 src monorepo 候选**合并** ——
     * 例如 modelcontextprotocol/servers 的根 .mcp.json 只声明开发用的
     * mcp-docs 文档端点，真正可装的是 src 下 7 台 reference server，短路
     * 会让用户想装的官方 server 装不上。合并后全部列出，用户自选。
     */
    suspend fun detectServers(
        owner: String,
        repo: String,
        branches: List<String>
    ): Result<List<RegistryServer>> = withContext(Dispatchers.IO) {
        val branch = branches.firstOrNull() ?: "main"
        // ① 根 server.json（publish_to_registry：一仓一 server 的明确声明）
        val rootJson = fetchText(rawUrl(owner, repo, branch, "server.json"))
        if (rootJson != null) {
            parseServerJson(rootJson, "https://github.com/$owner/$repo")?.let { server ->
                return@withContext Result.success(listOf(server))
            }
        }
        // ② 根 .mcp.json 的 mcpServers + ③ src monorepo —— 合并候选集
        val fromConfig = fetchText(rawUrl(owner, repo, branch, ".mcp.json"))
            ?.let { parseMcpConfigJson(it, "https://github.com/$owner/$repo") }
            .orEmpty()
        val paths = fetchRepoTreePaths(owner, repo, branch)
        val fromMonorepo = if (paths != null) {
            monorepoCandidates(paths, owner, repo, branch)
                .mapNotNull { candidate ->
                    fetchText(rawUrl(owner, repo, branch, candidate))
                        ?.let { parseMonorepoPackageJson(it, "https://github.com/$owner/$repo") }
                }
        } else {
            emptyList()
        }
        val merged = (fromConfig + fromMonorepo)
            .distinctBy { it.displayName }
            // P2：候选列表 key 基于 name.lowercase() —— .mcp.json 与 monorepo
            // 两个家族仅 displayName 大小写不同时（Memory vs memory）displayName
            // 去重保留两条但 lowercase key 相同，LazyColumn 撞 key 直接崩 ——
            // 在源头再按 key 去重一次。
            .distinctBy { it.key }
        if (merged.isEmpty()) {
            if (paths == null) {
                Result.failure(
                    Exception("无法读取 $owner/$repo 的仓库结构（网络不可用、仓库不存在或私有）")
                )
            } else {
                Result.failure(Exception("仓库 $owner/$repo 未探测到 MCP server 形态"))
            }
        } else {
            Result.success(merged)
        }
    }

    /**
     * 拉取仓库全树文件路径（git/trees recursive API）。
     * 条目数超过 [MAX_TREE_ENTRIES] 时截断（截断与否由调用方按需判断；
     * MCP/技能候选路径都在树的前部 —— recursive 树按目录序输出，src 与
     * skills 目录按字母序通常在树前 20%）。
     */
    suspend fun fetchRepoTreePaths(owner: String, repo: String, branch: String): List<String>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .apply { for ((k, v) in extraHeaders()) if (k.isNotBlank()) header(k, v) }
                .build()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    parseTreePaths(body)
                }
            } catch (e: Exception) {
                null
            }
        }

    // ── 内部实现 ──

    private fun rawUrl(owner: String, repo: String, branch: String, path: String): String =
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

    private fun fetchText(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { for ((k, v) in extraHeaders()) if (k.isNotBlank()) header(k, v) }
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null
            else {
                val bytes = resp.body?.byteStream()?.use { stream ->
                    stream.readBytesLimited(MAX_JSON_BYTES)
                }
                bytes?.toString(Charsets.UTF_8)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 读取至多 [max] 字节；超限返回 null（防御超大响应 OOM）。 */
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= max && read() >= 0) return null
        return out.toByteArray()
    }

    private fun parseTreePaths(body: String): List<String>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        // truncated=true 时树不完整（超大仓库）—— 仍返回已拿到的路径，
        // 候选探测照常（截断丢的是深路径长尾，src 一级候选几乎不受影响）
        root["tree"]?.jsonArray?.mapNotNull { el ->
            (el as? JsonObject)?.let { obj ->
                val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
                if (type == "blob") (obj["path"] as? JsonPrimitive)?.contentOrNull else null
            }
        }?.take(MAX_TREE_ENTRIES)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val USER_AGENT = "ApexAgent/1.0 (GitHub MCP detector)"

        /** JSON 响应上限 2MB（与 HubSource / GenericRegistryApi 一致）。 */
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024

        /**
         * 树条目上限：GitHub recursive tree 上限十万条目；src 一级子目录
         * 与技能候选都在浅层 —— 截到 5 万条足够覆盖，同时防超大仓库 OOM。
         */
        private const val MAX_TREE_ENTRIES = 50_000

        /** monorepo 候选数上限（防恶意仓库塞爆安装列表）。 */
        private const val MAX_MONOREPO_CANDIDATES = 32

        private val json = Json { ignoreUnknownKeys = true }

        // ── 纯解析（public：单测直接喂文本；无状态无副作用）──

        /**
         * 树路径 → monorepo 候选 package.json 路径列表（src 一级子目录，
         * 形如「src/filesystem/package.json」；深度固定 2，防深层误报）。
         */
        fun monorepoCandidates(
            paths: List<String>,
            owner: String,
            repo: String,
            @Suppress("UNUSED_PARAMETER") branch: String
        ): List<String> = paths
            .filter { path ->
                // depth==2（src/<dir>/package.json）已保证恰好一层子目录；
                // 不再用 contains 判定（package.json 自身就带斜杠，旧条件全过滤掉了）
                val depth = path.count { it == '/' }
                depth == 2 &&
                    path.startsWith("src/") &&
                    path.endsWith("/package.json")
            }
            .take(MAX_MONOREPO_CANDIDATES)

        /**
         * server.json（publish_to_registry 规范，snake_case）→ [RegistryServer]。
         * 非 MCP 形态（无 packages 且无 remotes）返回 null —— 不把任意
         * JSON 假装成 MCP server（与 diag-20261011「不假安装」契约一致）。
         */
        fun parseServerJson(body: String, repositoryUrl: String): RegistryServer? = try {
            val root = json.parseToJsonElement(body).jsonObject
            val packages = root.optArray("packages")?.mapNotNull { el ->
                (el as? JsonObject)?.let(::parsePublishPackage)
            }.orEmpty()
            val remotes = root.optArray("remotes")?.mapNotNull { el ->
                (el as? JsonObject)?.let(::parsePublishRemote)
            }.orEmpty()
            if (packages.isEmpty() && remotes.isEmpty()) return null
            val serverId = root.str("server_id")?.takeIf { it.isNotBlank() }
                ?: root.str("name")?.takeIf { it.isNotBlank() }
                ?: return null
            RegistryServer(
                name = serverId,
                displayName = root.str("name")?.takeIf { it.isNotBlank() }
                    ?: serverId.substringAfterLast('/'),
                description = root.str("description").orEmpty(),
                repositoryUrl = repositoryUrl,
                version = root.str("version").orEmpty(),
                remotes = remotes,
                packages = packages
            )
        } catch (e: Exception) {
            null
        }

        /** server.json 的 packages 元素 → [RegistryPackage]。 */
        private fun parsePublishPackage(obj: JsonObject): RegistryPackage? {
            val identifier = obj.str("identifier")?.takeIf { it.isNotBlank() } ?: return null
            val envObjs = obj.optArray("environment_variables")?.mapNotNull { it as? JsonObject }.orEmpty()
            val envVars = envObjs.mapNotNull { it.str("name")?.takeIf { n -> n.isNotBlank() } }
            val requiredEnvVars = envObjs.mapNotNull { envObj ->
                val envName = envObj.str("name")?.takeIf { n -> n.isNotBlank() } ?: return@mapNotNull null
                val required = (envObj.get("required") as? JsonPrimitive)?.let { p ->
                    p.booleanOrNull ?: (p.contentOrNull == "true")
                } ?: false
                if (required) envName else null
            }
            return RegistryPackage(
                registryType = obj.str("registry_type").orEmpty().lowercase(),
                identifier = identifier,
                version = obj.str("version").orEmpty(),
                runtimeHint = obj.str("runtime_hint"),
                isStdioTransport = (obj.get("transport") as? JsonObject)
                    ?.str("type")?.equals("stdio", ignoreCase = true) ?: true,
                fileSha256 = obj.str("file_sha256"),
                envVarNames = envVars,
                requiredEnvVarNames = requiredEnvVars,
                runtimeArguments = obj.optArray("run_time_arguments")?.mapNotNull { el ->
                    (el as? JsonObject)?.str("value")?.takeIf { v -> v.isNotBlank() }
                }.orEmpty(),
                packageArguments = obj.optArray("package_arguments")?.mapNotNull { el ->
                    (el as? JsonObject)?.str("value")?.takeIf { v -> v.isNotBlank() }
                }.orEmpty()
            )
        }

        /** server.json 的 remotes 元素 → [RegistryRemote]（白名单同 GenericRegistryApi）。 */
        private fun parsePublishRemote(obj: JsonObject): RegistryRemote? {
            val url = obj.str("url")?.takeIf { it.isNotBlank() } ?: return null
            val type = (obj.str("transport_type") ?: obj.str("type")).orEmpty().lowercase()
            if (type != "streamable_http" && type != "streamable-http" && type != "sse") return null
            val headers = obj.optArray("headers")?.mapNotNull { el ->
                (el as? JsonObject)?.let { h ->
                    val headerName = h.str("name")?.takeIf { n -> n.isNotBlank() }
                        ?: return@let null
                    headerName to h.str("value").orEmpty()
                }
            }.orEmpty().toMap()
            return RegistryRemote(
                url = url,
                isSse = type == "sse",
                headers = headers
            )
        }

        /**
         * .mcp.json（mcpServers 映射表）→ [RegistryServer] 列表。
         *
         * 每条 entry 的决策：
         * - `type` http/sse + url → 远端直装形态；
         * - `command == "npx"` + args 里有包名 → npm 沙箱形态（Android 宿主
         *   无 node，npx 必须路由 PRoot 沙箱 —— 与官方 Registry 源同口径）；
         * - 其他 command（python/docker/自定义二进制）→ 转「不支持」占位
         *   server（installKind=UNSUPPORTED，安装时由 RegistryMcpInstaller
         *   出诚实文案引导手动接入，不静默装残配置）；
         * - 空对象 / 缺字段 → 跳过。
         */
        fun parseMcpConfigJson(body: String, repositoryUrl: String): List<RegistryServer> = try {
            val root = json.parseToJsonElement(body).jsonObject
            val serversObj = root["mcpServers"] as? JsonObject
                ?: root["mcp_servers"] as? JsonObject
                ?: return emptyList()
            serversObj.entries.mapNotNull { (key, value) ->
                (value as? JsonObject)?.let { entry ->
                    buildMcpConfigServer(key, entry, repositoryUrl)
                }
            }.distinctBy { it.displayName }
        } catch (e: Exception) {
            emptyList()
        }

        /** .mcp.json 单条 mcpServers entry → [RegistryServer]（null = 跳过）。 */
        private fun buildMcpConfigServer(
            key: String,
            entry: JsonObject,
            repositoryUrl: String
        ): RegistryServer? {
            val type = entry.str("type")?.lowercase() ?: "stdio"
            val url = entry.str("url")?.takeIf { it.isNotBlank() }
            val command = entry.str("command")?.takeIf { it.isNotBlank() }
            val args = entry.strings("args")
            if (type == "http" || type == "streamable_http" || type == "streamable-http" || type == "sse") {
                if (url == null) return null
                return RegistryServer(
                    name = "github.mcpconfig/$key",
                    displayName = key,
                    description = entry.str("description").orEmpty(),
                    repositoryUrl = repositoryUrl,
                    version = "",
                    remotes = listOf(RegistryRemote(url = url, isSse = type == "sse")),
                    packages = emptyList()
                )
            }
            // stdio 家族：只有 npx 形态可沙箱化安装
            if (command != "npx" && command != "bunx" && command != "pnpx") return null
            val identifier = extractNpmPackageIdentifier(args)
                ?: return null // npx 无包名（如 npx 已装命令）→ 无法定位包，跳过
            val envValues = entry.strMap("env")
            return RegistryServer(
                name = "github.mcpconfig/$key",
                displayName = key,
                description = entry.str("description").orEmpty(),
                repositoryUrl = repositoryUrl,
                version = "",
                remotes = emptyList(),
                packages = listOf(
                    RegistryPackage(
                        registryType = "npm",
                        identifier = identifier,
                        version = "",
                        runtimeHint = "npx",
                        isStdioTransport = true,
                        fileSha256 = null,
                        envVarNames = envValues.keys.toList(),
                        requiredEnvVarNames = emptyList(),
                        runtimeArguments = emptyList(),
                        packageArguments = emptyList()
                    )
                ),
                environmentValues = envValues
            )
        }

        /**
         * 从 npx 参数序列提取 npm 包标识符：
         * - `-p` / `--package` 的值是显式指定的包（优先返回，npx 语义）；
         * - `--call` 的值是命令片段（不是包名），跳过；
         * - 其余首个位置参数即包名（`npx pkg` / `npx -y pkg@1.2 /path`）；
         * - 末段带 @version 的剥版本（安装器按 registry 目录版本锁定，
         *   不透传不可信 pin）；`@scope/name@1.2.3` 的最后一段 @ 才是
         *   版本分隔，scope 前导 @ 不受影响。
         */
        private fun extractNpmPackageIdentifier(args: List<String>): String? {
            var pendingPackage: String? = null
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == "-p" || arg == "--package" -> {
                        if (i + 1 < args.size) pendingPackage = stripVersionPin(args[i + 1])
                        i += 2
                    }
                    arg == "--call" -> i += 2 // 值是命令片段，不是包名
                    arg.startsWith("-") -> i += 1 // 合并旗标（-y / --foo=bar）无独立值
                    else -> {
                        // 首个位置参数；-p 显式指定的包优先于位置参数
                        return (pendingPackage ?: stripVersionPin(arg))
                            .takeIf { it.isNotBlank() }
                    }
                }
            }
            return pendingPackage // npx -p pkg（无位置参数）：包即默认命令
        }

        /** 剥掉包标识符末段的 @version pin。 */
        private fun stripVersionPin(identifier: String): String {
            val atCount = identifier.count { it == '@' }
            return if (atCount > 1 || (!identifier.startsWith("@") && atCount == 1)) {
                identifier.substringBeforeLast('@')
            } else {
                identifier
            }
        }

        /**
         * monorepo 参考实现形态的 package.json（src 一级子目录）→ [RegistryServer]。
         *
         * 判定（两项其一，宁缺勿滥 —— 普通 npm 包不误报）：
         * - `mcpName` 字段非空（MCP Registry 官方约定字段）；
         * - npm 包名含 "mcp"（不区分大小写）且 `bin` 非空。
         */
        fun parseMonorepoPackageJson(body: String, repositoryUrl: String): RegistryServer? = try {
            val root = json.parseToJsonElement(body).jsonObject
            val npmName = root.str("name")?.takeIf { it.isNotBlank() } ?: return null
            val mcpName = root.str("mcpName")?.takeIf { it.isNotBlank() }
            val hasBin = root.get("bin") != null &&
                (root.get("bin") is JsonObject || root.get("bin") is JsonPrimitive)
            val looksLikeMcp = mcpName != null ||
                (npmName.contains("mcp", ignoreCase = true) && hasBin)
            if (!looksLikeMcp) return null
            RegistryServer(
                name = mcpName ?: "github.monorepo/$npmName",
                displayName = npmName.substringAfterLast('/').ifBlank { npmName },
                description = root.str("description").orEmpty(),
                repositoryUrl = repositoryUrl,
                version = root.str("version").orEmpty(),
                remotes = emptyList(),
                packages = listOf(
                    RegistryPackage(
                        registryType = "npm",
                        identifier = npmName,
                        version = root.str("version").orEmpty(),
                        runtimeHint = "npx",
                        isStdioTransport = true,
                        fileSha256 = null,
                        envVarNames = emptyList(),
                        requiredEnvVarNames = emptyList(),
                        runtimeArguments = emptyList(),
                        packageArguments = emptyList()
                    )
                )
            )
        } catch (e: Exception) {
            null
        }

        // JsonObject 安全取值：字段缺失 / 类型不符 / null 均返回安全默认
        private fun JsonObject?.str(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.optArray(key: String): JsonArray? =
            this.get(key) as? JsonArray

        private fun JsonObject.strings(key: String): List<String> =
            (this.get(key) as? JsonArray)
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
