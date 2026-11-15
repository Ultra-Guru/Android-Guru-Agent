package com.apex.agent.core.tools.extension.adapters

import com.apex.agent.core.tools.extension.Actions
import com.apex.agent.core.tools.extension.ExtensionAdapter
import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionSpecParser
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.InstallOutcome
import com.apex.agent.core.tools.extension.InspectReport
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.extension.SourceKinds
import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * # McpExtensionAdapter —— MCP 生态适配器
 *
 * 把统一 spec（`mcp:<json 字面量|import:<文件>|目录名>`）翻译成 McpManager
 * 原生配置写入。brief 明确的 **register / import 语义分离** 在 resolve 阶段
 * 判定并写进 [ExtensionManifest.action]：
 * - 单条 JSON 字面量 / 目录名 = **register**（新增一台服务器）；
 * - `{mcpServers:…}` 整包 / `mcp:import:<文件>`（桌面 Claude/Cursor 导出）=
 *   **import**（导入既有配置）——二者混用会导致配置重复或覆盖，报告里显式
 *   区分，防止「导入」被当「安装」用。
 *
 * 双 runtime 域：`runInSandbox=true` 的 STDIO 配置 → ubuntu 域（PRoot 内
 * 启动），其余 → android 域（宿主进程/远端直连）。
 */
class McpExtensionAdapter(
    /** MCP 配置账目端口（app 层接 McpManager；单测手写 fake —— 不真连进程）。 */
    private val configs: McpConfigPort,
    /** 精选目录查询（`mcp:<目录名>` 的数据源；null = 目录来源诚实失败）。 */
    private val catalogLookup: (suspend (String) -> Result<McpServerConfig>)? = null
) : ExtensionAdapter {

    /** MCP 配置端口（add/remove/list —— 与 McpManager 的最小交面）。 */
    interface McpConfigPort {
        suspend fun add(config: McpServerConfig): Result<Unit>
        suspend fun remove(name: String)
        fun all(): List<McpServerConfig>
    }

    override val supportedTypes: Set<String> = setOf(ExtensionTypes.MCP_CONNECTOR)

    override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
        val (config, action, warnings) = when (draft.sourceKind) {
            SourceKinds.JSON -> resolveJsonLiteral(draft.payload)
            SourceKinds.LOCAL -> resolveImportFile(draft.payload)
            SourceKinds.CATALOG -> resolveCatalog(draft.payload)
            else -> return Result.failure(IllegalArgumentException("mcp spec 载荷无法识别：${draft.raw}"))
        }.getOrElse { return Result.failure(it) }

        val manifest = ExtensionManifest(
            id = "mcp.${config.name}",
            type = ExtensionTypes.MCP_CONNECTOR,
            spec = draft.raw,
            name = config.name,
            version = "1.0.0",
            sourceKind = draft.sourceKind ?: SourceKinds.JSON,
            runtime = if (config.runInSandbox) RuntimeDomains.UBUNTU else RuntimeDomains.ANDROID,
            trustLevel = if (draft.sourceKind == SourceKinds.CATALOG) "verified" else "community",
            action = action,
            permissions = permissionsOf(config),
            resolved = configJson(config),
            summary = "MCP 服务器「${config.name}」（${transportLabel(config)}）：${config.endpointSummary().take(100)}",
            manifestHash = ""
        )
        return Result.success(manifest.copy(manifestHash = manifest.computeHash(), summary = manifest.summary))
    }

    override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> {
        val config = McpConfigCodec.decode(requireNotNull(manifest.resolved))
        val warnings = buildList {
            if (!config.apiKey.isNullOrBlank()) add("配置含 apiKey 明文 —— 将随配置落盘（mcp_servers.json）")
            if (config.env.isNotEmpty()) add("配置含 ${config.env.size} 个环境变量（可能含密钥）—— 落盘同上")
            if (config.transport == McpTransport.STDIO && !config.runInSandbox) {
                add("STDIO 命令将在宿主进程直接 fork —— 无法解析的命令会握手失败（node/npx 等建议 runInSandbox=true 走 PRoot Ubuntu）")
            }
        }
        return Result.success(
            InspectReport(
                manifest = manifest,
                targetRegistry = "McpManager（mcp_servers.json；启用后工具以 Mcp{Server}{Tool} 一等函数进入对话）",
                targetLocation = "MCP 配置表：${config.name}（${if (manifest.action == Actions.IMPORT) "导入既有配置" else "新增注册"}）",
                domainDependencies = if (manifest.runtime == RuntimeDomains.UBUNTU) {
                    listOf("PRoot Ubuntu rootfs 就绪", "guest 内可解析命令：${config.command.orEmpty()}")
                } else {
                    emptyList()
                },
                warnings = warnings
            )
        )
    }

    override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
        val config = McpConfigCodec.decode(requireNotNull(manifest.resolved) { "resolved config 缺失" })
        val existing = configs.all().firstOrNull { it.name == config.name }
        if (existing != null) {
            return if (existing.endpointSummary() == config.endpointSummary() &&
                existing.transport == config.transport
            ) {
                Result.success(
                    InstallOutcome(
                        id = manifest.id, type = manifest.type, version = manifest.version,
                        where = "McpManager: ${config.name}",
                        notes = listOf("已存在同端点配置 —— 幂等命中，未重复写入")
                    )
                )
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "同名 MCP 服务器 '${config.name}' 已存在且端点不同（${existing.endpointSummary()}）" +
                            "—— 覆盖请先 ExtensionUninstall，或换名安装"
                    )
                )
            }
        }
        configs.add(config).getOrElse { return Result.failure(it) }
        return Result.success(
            InstallOutcome(
                id = manifest.id,
                type = manifest.type,
                version = manifest.version,
                where = "McpManager: ${config.name}",
                notes = if (manifest.action == Actions.IMPORT) listOf("以导入语义写入（源自既有配置）") else emptyList()
            )
        )
    }

    override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
        configs.remove(manifest.id.removePrefix("mcp."))
        return Result.success(Unit)
    }

    // ── 三种来源的 resolve ─────────────────────────────────────────

    private fun resolveJsonLiteral(payload: String): Result<Triple<McpServerConfig, String, List<String>>> {
        return if (payload.contains("\"mcpServers\"")) {
            // 整包导入形态（桌面导出的标准 {mcpServers:…}）
            val result = McpConfigImport.parse(payload)
            when {
                result.configs.isEmpty() -> Result.failure(
                    IllegalArgumentException("配置解析零命中：${result.errors.joinToString("; ") { "${it.serverName}: ${it.reason}" }}")
                )
                result.configs.size > 1 -> Result.failure(
                    IllegalArgumentException(
                        "整包含 ${result.configs.size} 台服务器 —— 整包导入请用 ExtensionSync（mcpServers 清单），" +
                            "或逐台以 mcp:{单台配置} 注册"
                    )
                )
                else -> Result.success(Triple(result.configs.first(), Actions.IMPORT, emptyList()))
            }
        } else {
            // 单台字面量 = register（与 claude mcp add 对齐；import 走 import: 前缀）
            val obj = runCatching {
                com.apex.agent.core.tools.extension.ExtensionJson.json.parseToJsonElement(payload).jsonObject
            }.getOrElse { return Result.failure(IllegalArgumentException("配置不是合法 JSON：${it.message}")) }
            val result = McpConfigImport.parseServers(buildJsonObject { put("mcp-extension-entry", obj) })
            val config = result.configs.firstOrNull()
                ?: return Result.failure(
                    IllegalArgumentException("配置无法解析为 MCP 服务器：${result.errors.joinToString("; ") { it.reason }}")
                )
            Result.success(Triple(config.copy(name = config.name.ifBlank { "mcp-extension-entry" }), Actions.REGISTER, emptyList()))
        }
    }

    private suspend fun resolveImportFile(path: String): Result<Triple<McpServerConfig, String, List<String>>> {
        val text = runCatching {
            val f = File(path)
            require(f.isFile) { "导入文件不存在：$path" }
            require(f.length() <= 2L * 1024 * 1024) { "配置文件超过 2MB 上限" }
            f.readText()
        }.getOrElse { return Result.failure(it) }
        val result = McpConfigImport.parse(text)
        return when {
            result.configs.isEmpty() -> Result.failure(
                IllegalArgumentException("导入零命中：${result.errors.joinToString("; ") { "${it.serverName}: ${it.reason}" }}")
            )
            result.configs.size > 1 -> Result.failure(
                IllegalArgumentException(
                    "文件含 ${result.configs.size} 台服务器 —— 批量导入请用 ExtensionSync；本入口只接受单台（整包导入属 import 语义，逐台确认）"
                )
            )
            else -> Result.success(Triple(result.configs.first(), Actions.IMPORT, emptyList()))
        }
    }

    private suspend fun resolveCatalog(name: String): Result<Triple<McpServerConfig, String, List<String>>> {
        val lookup = catalogLookup
            ?: return Result.failure(UnsupportedOperationException("精选目录查询未接线（app 层注入 catalogLookup）"))
        val config = lookup(name).getOrElse { return Result.failure(it) }
        return Result.success(Triple(config, Actions.REGISTER, emptyList()))
    }

    // ── 辅助 ───────────────────────────────────────────────────────

    private fun permissionsOf(config: McpServerConfig): List<String> = buildList {
        when (config.transport) {
            McpTransport.HTTP, McpTransport.SSE -> add("network")
            McpTransport.STDIO -> add("spawn-process")
            McpTransport.BUILTIN -> { /* 进程内，无额外权限 */ }
        }
        if (config.env.isNotEmpty() || !config.apiKey.isNullOrBlank()) add("env-secrets")
    }

    private fun transportLabel(config: McpServerConfig): String = when (config.transport) {
        McpTransport.STDIO -> if (config.runInSandbox) "STDIO · PRoot Ubuntu 沙箱" else "STDIO · 宿主命令"
        McpTransport.HTTP -> "HTTP 远端"
        McpTransport.SSE -> "SSE 远端"
        McpTransport.BUILTIN -> "进程内内置"
    }

    private fun configJson(config: McpServerConfig): JsonObject = McpConfigCodec.encode(config)
}

/** MCP 配置 JSON 编解码（resolved 载体 —— McpServerConfig @Serializable 直译）。 */
internal object McpConfigCodec {
    private val json = com.apex.agent.core.tools.extension.ExtensionJson.json

    fun encode(config: McpServerConfig): JsonObject =
        json.parseToJsonElement(json.encodeToString(McpServerConfig.serializer(), config)).jsonObject

    fun decode(obj: JsonObject): McpServerConfig =
        json.decodeFromJsonElement(McpServerConfig.serializer(), obj)
}
