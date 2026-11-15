package com.apex.agent.core.tools.pluginhost.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * # Worker IPC 协议（spec §6.3）—— 换行分隔 JSON 的进程间消息
 *
 * Plugin Host（Kotlin 宿主）与 Plugin Worker（沙箱内 node 进程）之间的
 * 唯一通信通道：**STDOUT 专用于协议消息（每行一个 JSON 对象），普通日志
 * 走 STDERR** —— 禁止插件的 console.log 污染 IPC 消息流（spec 明令）。
 *
 * 信封结构：
 * - `protocol` = "plugin-host"，`version` = 1（版本协商失败的握手直接失败）；
 * - `type` 消息类型（见 [Type]）；
 * - `requestId` 请求关联 id（host 生成，worker 原样回带）；
 * - `payload` 类型相关负载。
 *
 * 纯数据编解码，无 IO —— 编解码正确性由 [WorkerProtocolTest] 与真实
 * node 端到端测试共同锁定。
 */
object WorkerProtocol {
    const val PROTOCOL_NAME = "plugin-host"
    const val VERSION = 1

    /** 消息类型常量（host↔worker 双向）。 */
    object Type {
        /** host→worker 握手。 */
        const val HELLO = "hello"

        /** worker→host 握手应答（含运行时版本）。 */
        const val READY = "ready"

        /** host→worker 加载插件模块。 */
        const val LOAD = "load"

        /** worker→host 加载完成（工具/钩子发现结果）。 */
        const val LOADED = "loaded"

        /** host→worker 工具调用。 */
        const val TOOL_EXECUTE = "tool.execute"

        /** worker→host 工具结果。 */
        const val TOOL_RESULT = "tool.result"

        /** host→worker 事件下发。 */
        const val EVENT_EMIT = "event.emit"

        /** worker→host 插件内部事件上报。 */
        const val PLUGIN_EVENT = "plugin.event"

        /** host→worker 心跳。 */
        const val PING = "ping"

        /** worker→host 心跳应答。 */
        const val PONG = "pong"

        /** host→worker 取消在途请求。 */
        const val CANCEL = "cancel"

        /** host→worker 优雅停机。 */
        const val SHUTDOWN = "shutdown"

        /** worker→host 致命错误（worker 自知无法继续）。 */
        const val FATAL = "fatal"
    }

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // 信封的 protocol/version 是带默认值的字段，但**线格式要求它们始终
        // 在场**（spec §6.3 的版本协商依据）—— encodeDefaults=false 会把
        // 默认值剔除，自家构造的消息自家都解析不了（协议自洽性测试锁定）。
        encodeDefaults = true
    }

    /** 解码后的消息（信封字段 + 原始 payload）。 */
    @Serializable
    data class Message(
        val protocol: String = PROTOCOL_NAME,
        val version: Int = VERSION,
        val type: String,
        val requestId: String? = null,
        val payload: JsonObject? = null
    ) {
        companion object {
            fun parse(line: String): Message? = runCatching {
                val obj = json.parseToJsonElement(line).jsonObject
                val protocol = obj["protocol"]?.jsonPrimitive?.content
                val version = obj["version"]?.jsonPrimitive?.content?.toIntOrNull()
                if (protocol != PROTOCOL_NAME || version == null) return null
                json.decodeFromJsonElement(serializer(), obj)
            }.getOrNull()
        }
    }

    // ── host→worker 构造器 ────────────────────────────────────────────────

    fun hello(requestId: String, pluginId: String): String =
        encode(Type.HELLO, requestId, buildJsonObject {
            put("pluginId", pluginId)
        })

    fun load(
        requestId: String,
        entryPath: String,
        format: String,
        ecosystem: String = "",
        pluginId: String = "",
        pluginDir: String = "",
        optionsJson: String = "{}",
        storageFile: String = ""
    ): String = encode(Type.LOAD, requestId, buildJsonObject {
        put("entryPath", entryPath)
        put("format", format)
        if (ecosystem.isNotBlank()) put("ecosystem", ecosystem)
        if (pluginId.isNotBlank()) put("pluginId", pluginId)
        if (pluginDir.isNotBlank()) put("pluginDir", pluginDir)
        if (optionsJson.isNotBlank() && optionsJson != "{}") {
            runCatching {
                json.parseToJsonElement(optionsJson)
            }.getOrNull()?.let { put("options", it) }
        }
        if (storageFile.isNotBlank()) put("storageFile", storageFile)
    })

    fun toolExecute(requestId: String, toolName: String, argsJson: String): String =
        encode(Type.TOOL_EXECUTE, requestId, buildJsonObject {
            put("toolName", toolName)
            put("argsJson", argsJson)
        })

    fun eventEmit(requestId: String, eventName: String, payloadJson: String): String =
        encode(Type.EVENT_EMIT, requestId, buildJsonObject {
            put("eventName", eventName)
            put("payloadJson", payloadJson)
        })

    fun ping(requestId: String): String = encode(Type.PING, requestId, null)

    fun cancel(requestId: String, targetRequestId: String): String =
        encode(Type.CANCEL, requestId, buildJsonObject {
            put("targetRequestId", targetRequestId)
        })

    fun shutdown(requestId: String): String = encode(Type.SHUTDOWN, requestId, null)

    // ── worker→host 解析器 ────────────────────────────────────────────────

    /** ready 消息负载。 */
    @Serializable
    data class Ready(
        val runtime: String = "",
        val runtimeVersion: String = "",
        val protocolVersion: Int = VERSION
    ) {
        companion object {
            fun from(message: Message): Ready? {
                if (message.type != Type.READY) return null
                val payload = message.payload ?: return Ready()
                return runCatching {
                    Ready(
                        runtime = payload.str("runtime"),
                        runtimeVersion = payload.str("runtimeVersion"),
                        protocolVersion = payload["protocolVersion"]
                            ?.jsonPrimitive?.content?.toIntOrNull() ?: VERSION
                    )
                }.getOrNull()
            }
        }
    }

    /** loaded 消息负载：模块加载后发现的工具与钩子清单。 */
    @Serializable
    data class Loaded(
        val tools: List<DiscoveredTool> = emptyList(),
        /** 已注册的钩子名（OpenCode 兼容层上报，如 tool.execute.before）。 */
        val hooks: List<String> = emptyList(),
        /** ctx.event.subscribe 订阅的事件名。 */
        val events: List<String> = emptyList(),
        val setupOk: Boolean = true,
        val error: String? = null
    ) {
        companion object {
            fun from(message: Message): Loaded? {
                if (message.type != Type.LOADED) return null
                val payload = message.payload ?: return Loaded()
                return runCatching {
                    Loaded(
                        tools = payload["tools"]?.let { el ->
                            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                                val obj = item as? JsonObject ?: return@mapNotNull null
                                DiscoveredTool(
                                    name = obj.str("name"),
                                    description = obj.str("description"),
                                    parametersSchema = obj.str("parametersSchema").ifBlank { null }
                                )
                            }
                        } ?: emptyList(),
                        hooks = payload.stringList("hooks"),
                        events = payload.stringList("events"),
                        setupOk = payload["setupOk"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                        error = (payload["error"] as? JsonPrimitive)?.content
                    )
                }.getOrNull()
            }
        }
    }

    /** worker 发现的单个工具描述。 */
    @Serializable
    data class DiscoveredTool(
        val name: String,
        val description: String = "",
        /** JSON Schema 文本（宿主侧再经 ToolSchemaSanitizer 清洗）。 */
        val parametersSchema: String? = null
    )

    /** tool.result 消息负载。 */
    @Serializable
    data class ToolResult(
        val requestId: String,
        val ok: Boolean,
        val output: String = "",
        val error: String? = null
    ) {
        companion object {
            fun from(message: Message): ToolResult? {
                if (message.type != Type.TOOL_RESULT) return null
                val id = message.requestId ?: return null
                val payload = message.payload
                return runCatching {
                    ToolResult(
                        requestId = id,
                        ok = payload?.get("ok")?.jsonPrimitive?.content?.toBoolean() ?: false,
                        output = payload?.str("output").orEmpty(),
                        error = (payload?.get("error") as? JsonPrimitive)?.content
                    )
                }.getOrNull()
            }
        }
    }

    /** plugin.event 消息负载。 */
    @Serializable
    data class PluginEvent(
        val name: String,
        val payloadJson: String = "{}"
    ) {
        companion object {
            fun from(message: Message): PluginEvent? {
                if (message.type != Type.PLUGIN_EVENT) return null
                val payload = message.payload ?: return null
                return runCatching {
                    PluginEvent(
                        name = payload.str("name"),
                        payloadJson = payload.str("payloadJson").ifEmpty { "{}" }
                    )
                }.getOrNull()
            }
        }
    }

    /** fatal 消息负载。 */
    fun fatalReason(message: Message): String =
        message.payload?.str("reason")?.ifEmpty { null } ?: "unknown fatal"

    private fun encode(type: String, requestId: String?, payload: JsonObject?): String =
        json.encodeToString(
            Message.serializer(),
            Message(type = type, requestId = requestId, payload = payload)
        )

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { v -> v.isNotBlank() } }
            ?: emptyList()
}

/** 宿主↔Worker 事件（Worker 生命周期观测，供 Supervisor 与诊断消费）。 */
sealed interface WorkerEvent {
    val pluginId: String

    data class StderrLine(override val pluginId: String, val line: String) : WorkerEvent
    data class PluginEventFired(override val pluginId: String, val event: WorkerProtocol.PluginEvent) : WorkerEvent
    data class Exited(override val pluginId: String, val exitCode: Int?) : WorkerEvent
}
