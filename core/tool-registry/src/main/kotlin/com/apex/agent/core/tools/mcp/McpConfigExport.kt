package com.apex.agent.core.tools.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ═══ MCP 配置导出器（MCP 市场诚实化）═══
 *
 * [McpConfigImport] 的对偶：把本机的 [McpServerConfig] 装配回社区通用的
 * `{"mcpServers": {...}}` 形态（Claude Desktop / Cursor / Cline 通用），
 * 供「复制桌面配置」使用 —— 场景：沙箱型 STDIO 服务器在本机没有运行时
 * （node/uvx 未装）时，用户可以把它导出到电脑上用，而不是卡在必败的
 * 启动上。
 *
 * ## 字段映射（与 Import 的判定规则严格对偶）
 * - STDIO → `{"command", "args", "env"}`（args/env 为空时省略，输出更干净；
 *   **不含** `runInSandbox` —— 那是本 App 的私有字段，桌面端不认识）；
 * - HTTP → `{"type": "streamable_http", "url", "headers"}`；
 * - SSE → `{"type": "sse", "url", "headers"}`（headers 为空时省略）；
 * - BUILTIN → **不可导出**（能力编译在本 App 二进制里，桌面端无从运行），
 *   [exportServers] 静默跳过、[exportServer] 返回 null。
 *
 * ## 敏感值语义
 * `env`/`headers` 原样导出（落盘形态）。GitHub 官方预置里的
 * `${GITHUB_TOKEN}` 占位符导出后仍是占位符 —— 真 PAT 从不落盘，桌面端
 * 用户需自行替换（这正是令牌桥的设计承诺）。
 *
 * ## 往返不变量（单测锁定）
 * `McpConfigImport.parse(McpConfigExport.exportServers(cs)).configs` 与
 * `cs` 中非 BUILTIN 条目逐字段相等（名字/传输/命令/参数/env/url）——
 * 导出的配置能在桌面端用，重新导回本机也不失真。
 *
 * 纯 JVM 零 Android 依赖，与 Import 同包同纪律，单测直接跑。
 */
object McpConfigExport {

    private val json = Json { prettyPrint = true }

    /**
     * 导出多台服务器为完整配置文本；BUILTIN 条目静默跳过（见类 KDoc）。
     * 空输入 / 全 BUILTIN 输入返回 `{"mcpServers": {}}` 骨架（仍是合法 JSON）。
     */
    fun exportServers(configs: List<McpServerConfig>): String {
        val servers = buildJsonObject {
            for (config in configs) {
                val entry = entryFor(config) ?: continue
                put(config.name, entry)
            }
        }
        val root = JsonObject(mapOf("mcpServers" to servers))
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * 导出单台服务器；BUILTIN（不可导出）返回 null。
     * 调用方据此给出「该服务器无法导出」的诚实提示，而不是输出空壳。
     */
    fun exportServer(config: McpServerConfig): String? {
        val entry = entryFor(config) ?: return null
        val root = JsonObject(
            mapOf("mcpServers" to buildJsonObject { put(config.name, entry) })
        )
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** 单台 → 条目对象；BUILTIN 返回 null（唯一不可导出的传输形态）。 */
    private fun entryFor(config: McpServerConfig): JsonObject? = when (config.transport) {
        McpTransport.STDIO -> buildJsonObject {
            put("command", config.command.orEmpty())
            if (config.args.isNotEmpty()) {
                putJsonArray("args") {
                    config.args.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (config.env.isNotEmpty()) {
                putJsonObject("env") {
                    config.env.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        McpTransport.HTTP -> buildJsonObject {
            put("type", "streamable_http")
            put("url", config.url)
            if (config.headers.isNotEmpty()) {
                putJsonObject("headers") {
                    config.headers.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        McpTransport.SSE -> buildJsonObject {
            put("type", "sse")
            put("url", config.url)
            if (config.headers.isNotEmpty()) {
                putJsonObject("headers") {
                    config.headers.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        McpTransport.BUILTIN -> null
    }
}
