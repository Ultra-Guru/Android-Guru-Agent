package com.apex.agent.core.tools.pluginhost.claude

import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # ClaudeMcpBridge —— 插件内 `.mcp.json` → 既有 McpManager（spec §3.7）
 *
 * 官方 `.mcp.json` 形状（与桌面 Claude/Cursor 导出同构）：
 *
 * ```json
 * { "mcpServers": {
 *     "fs": { "command": "npx", "args": ["-y", "@mcp/server-fs"] },
 *     "hub": { "type": "http", "url": "https://…", "headers": { … } } } }
 * ```
 *
 * 纪律（spec §3.7 + 禁止事项 #6）：**复用既有 McpManager，不建第二套
 * MCP 连接管理** —— 经 [McpExtensionAdapter.McpConfigPort] 注册；
 * stdio 命令在 Android 上必须走沙箱（runInSandbox=true）。
 *
 * 来源归属（spec：插件卸载不得误删用户自建服务器）：本桥维护
 * pluginId → 服务器名的落盘账本，[removeOwned] 只删插件带来的那批。
 */
class ClaudeMcpBridge(
    /** 账本目录（extensions 注册表同级；JSON 文件一行一插件）。 */
    ledgerDir: File,
    private val port: McpExtensionAdapter.McpConfigPort
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ledgerFile = File(ledgerDir.apply { mkdirs() }, "claude-mcp-owned.json")

    /** 应用产物。 */
    data class Applied(
        val pluginId: String,
        val addedServers: List<String>,
        val skipped: List<String>
    )

    /**
     * 解析并注册插件声明的 MCP 服务器。
     *
     * @param mcpJsonText `.mcp.json` 文本（损坏 → failure，不装半截）
     */
    suspend fun apply(pluginId: String, mcpJsonText: String): Result<Applied> = runCatching {
        val root = runCatching { json.parseToJsonElement(mcpJsonText).jsonObject }
            .getOrElse { error(".mcp.json 解析失败: ${it.message}") }
        val servers = root["mcpServers"] as? JsonObject
            ?: error(".mcp.json 缺少 mcpServers 字段")
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((name, cfgEl) in servers) {
            val cfg = cfgEl as? JsonObject
            if (cfg == null) {
                skipped += name
                continue
            }
            val config = toServerConfig(name, cfg)
                ?: run {
                    skipped += name
                    continue
                }
            // 已存在同名服务器：跳过并留痕（不覆盖用户/其它来源配置）
            if (port.all().any { it.name == name }) {
                skipped += name
                continue
            }
            port.add(config).getOrElse {
                skipped += name
                return@getOrElse
            }
            added += name
        }
        recordOwnership(pluginId, added)
        Applied(pluginId, added, skipped)
    }

    /** 卸载路径：只删除该插件带来的服务器（用户自建的不动）。 */
    suspend fun removeOwned(pluginId: String): Int {
        val owned = readOwnership()[pluginId] ?: return 0
        owned.forEach { port.remove(it) }
        val updated = readOwnership().toMutableMap()
        updated.remove(pluginId)
        writeOwnership(updated)
        return owned.size
    }

    /** 该插件带来的服务器名（诊断）。 */
    fun ownedServers(pluginId: String): Set<String> = readOwnership()[pluginId].orEmpty().toSet()

    // ── 官方配置 → 宿主 McpServerConfig ────────────────────────────────

    private fun toServerConfig(name: String, cfg: JsonObject): McpServerConfig? {
        val type = cfg["type"]?.jsonPrimitive?.content ?: ""
        val url = cfg["url"]?.jsonPrimitive?.content ?: ""
        val command = cfg["command"]?.jsonPrimitive?.content
        return when {
            // 显式 http/sse 或只有 url → 远端
            type.equals("http", true) || type.equals("sse", true) || url.isNotBlank() -> {
                if (url.isBlank()) return null
                McpServerConfig(
                    name = name,
                    url = url,
                    transport = if (type.equals("sse", true)) McpTransport.SSE else McpTransport.HTTP,
                    headers = cfg.stringMap("headers"),
                    scope = "all"
                )
            }
            // stdio：command 形态 —— Android 上必须走沙箱（node/npx 在 rootfs 内）
            !command.isNullOrBlank() -> McpServerConfig(
                name = name,
                transport = McpTransport.STDIO,
                command = command,
                args = cfg.stringList("args"),
                env = cfg.stringMap("env"),
                runInSandbox = true,
                scope = "all"
            )
            else -> null
        }
    }

    // ── 来源归属账本（tmp + rename 原子写）─────────────────────────────

    private fun recordOwnership(pluginId: String, servers: List<String>) {
        if (servers.isEmpty()) return
        val ownership = readOwnership().toMutableMap()
        ownership.merge(pluginId, servers) { old, new -> (old + new).distinct() }
        writeOwnership(ownership)
    }

    private fun readOwnership(): Map<String, List<String>> = runCatching {
        if (!ledgerFile.isFile) return emptyMap()
        val obj = json.parseToJsonElement(ledgerFile.readText()).jsonObject
        obj.entries.associate { (k, v) ->
            k to ((v as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: emptyList())
        }
    }.getOrDefault(emptyMap())

    private fun writeOwnership(map: Map<String, List<String>>) {
        val obj = kotlinx.serialization.json.buildJsonObject {
            for ((k, list) in map) {
                put(k, kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        }
        val tmp = File(ledgerFile.parentFile, ledgerFile.name + ".tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(ledgerFile)) {
            ledgerFile.writeText(obj.toString())
        }
    }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()

    private fun JsonObject.stringMap(key: String): Map<String, String> =
        ((this[key] as? JsonObject)?.entries ?: emptySet()).mapNotNull { (k, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it }
        }.toMap()
}

/**
 * # ClaudeLspReport —— `.lsp.json` 诚实检测（spec §3.8）
 *
 * spec 明令：「不能只读取配置却不启动语言服务器」—— 统一 LSP Host 是
 * 独立子系统（供 Code Mode 与插件共用，见路线图 Phase 6+）。本对象只做
 * 检测与结构上报：检测到即报告「已识别、宿主运行时未就绪」，绝不宣称
 * 已支持 LSP。
 */
object ClaudeLspReport {

    data class Report(
        val detected: Boolean,
        /** 声明的语言服务器条目名（结构识别，不启动）。 */
        val serverNames: List<String>,
        /** 诚实结论（报告/UI 直用）。 */
        val verdict: String
    )

    fun scan(pluginRoot: File): Report {
        val lspFile = File(pluginRoot, ".lsp.json")
        if (!lspFile.isFile) {
            return Report(false, emptyList(), "未检测到 .lsp.json")
        }
        val names = runCatching {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(lspFile.readText()).jsonObject
            // 官方形状：lspServers 映射（宽容：任意顶层对象的键也识别）
            val servers = root["lspServers"] as? JsonObject ?: root
            servers.keys.toList()
        }.getOrDefault(emptyList())
        return Report(
            detected = true,
            serverNames = names,
            verdict = "已识别 ${names.size} 个语言服务器声明 —— 宿主 LSP 运行时尚未就绪" +
                "（统一 LSP Host 属后续阶段；当前不启动、不伪装支持）"
        )
    }
}
