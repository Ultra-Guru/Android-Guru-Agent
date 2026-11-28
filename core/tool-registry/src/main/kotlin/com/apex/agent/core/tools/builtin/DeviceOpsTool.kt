package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.core.tools.device.IntentCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ═══ DeviceOps —— 设备控制统一入口（S07）═══
 *
 * LLM 面对设备操作（卸载 / 停用 / 清数据 / 强停 / 高级文件操作 / 系统设置）
 * 的唯一入口：`intent + params + dry_run + confirm_token` 四字段，不需要懂
 * `pm` / `am` 语法。intent 枚举由 [IntentCatalog]（app/src/main/assets/device
 * 目录下的 intents.json，单一事实源）派生并写进 description —— 加能力只改
 * JSON，不改 Kotlin。
 *
 * 使用契约（提示词同口径）：
 * 1. 先 DeviceCapability 自省档位；
 * 2. 不可逆操作先 `"dry_run": true` 预演 → 拿 confirm_token →
 *    `"confirm_token": "<token>"` 执行；
 * 3. 越权 / 守卫命中 / 令牌过期都会**结构化拒绝**（绝不冒充成功）；
 * 4. ShellExecute 是自由文本逃生舱 —— 设备增删改一律走本工具。
 *
 * `selfGated`：本工具自带 preflight + gate + token 三级确认流
 * （见 DeviceOpsExecutor），执行闸的会话级审批不再叠加，避免双弹窗。
 */
class DeviceOpsTool(
    private val port: DeviceOpsPort,
    catalog: IntentCatalog
) : AgentTool {

    override val id = "DeviceOps"
    override val name = "Device Operations (structured)"

    override val description = buildString {
        append("Structured device control plane: uninstall / disable / clear /")
        append(" force-stop apps, advanced file ops on /sdcard (soft-delete with")
        append(" undo), settings & appops. All operations are declared in")
        append(" intents.json (single source of truth) and executed as argv")
        append(" arrays over the Shizuku (uid=2000) channel — never shell strings.\n")
        append("Workflow: (1) call DeviceCapability first to check privilege;")
        append(" (2) for irreversible intents always dry_run first, show the user")
        append(" the predicted impact, then re-call with confirm_token;")
        append(" (3) failures are structured (see error codes in results).\n")
        append("Available intents (id | risk | reversible | params):\n")
        catalog.all().forEach { intent ->
            append("- ").append(intent.id)
            append(" | risk=").append(intent.risk.name)
            append(" | reversible=").append(intent.reversible)
            val params = intent.params.joinToString(",") { p ->
                val vals = if (p.kind == "enum" && p.values.isNotEmpty()) {
                    "(" + p.values.filter { it.isNotEmpty() }.joinToString("/") + ")"
                } else ""
                p.name + (if (p.required) "" else "?") + vals
            }
            if (params.isNotEmpty()) append(" | params: ").append(params)
            append(" — ").append(intent.blastRadius)
            append("\n")
        }
        append("Examples:\n")
        append("- {\"intent\": \"package.uninstall_for_user\", \"params\": {\"package\": \"com.example.preinstalled\"}, \"dry_run\": true}\n")
        append("- {\"intent\": \"package.uninstall_for_user\", \"params\": {\"package\": \"com.example.preinstalled\"}, \"confirm_token\": \"<from dry_run>\"}\n")
        append("- {\"intent\": \"file.delete\", \"params\": {\"path\": \"/sdcard/Download/a.txt\"}, \"dry_run\": true}")
    }

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "intent": {"type": "string", "description": "Intent id from the list in this tool's description"},
                "params": {"type": "object", "description": "Intent params (see description)", "additionalProperties": true},
                "dry_run": {"type": "boolean", "description": "true = read-only preflight only, returns diff + confirm_token (default: false)"},
                "confirm_token": {"type": "string", "description": "Token from a previous dry_run; required for irreversible intents"}
            },
            "required": ["intent"]
        }
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        risk(ToolRisk.HIGH)
        // 自带 preflight + gate + token 三级确认流（selfGated 语义）
        annotations {
            mutating()
        }
        tag("device")
        tag("shizuku")
    }

    override suspend fun execute(arguments: String): String {
        val json = try {
            Json.parseToJsonElement(arguments).jsonObject
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return err("INVALID_JSON", "参数不是合法 JSON：${e.message}")
        }
        val intentId = (json["intent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return err("MISSING_ARGUMENT", "缺少必填参数 'intent'")
        val params = ((json["params"] as? JsonObject) ?: JsonObject(emptyMap()))
            .mapValues { (_, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: (v as? JsonPrimitive)?.content
                    ?: return err("INVALID_PARAMS", "params 的值必须是字符串（嵌套对象不支持）")
            }
        val dryRun = (json["dry_run"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val token = (json["confirm_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val result = port.execute(intentId, params, dryRun, token)
        return result.toJson()
    }

    private fun err(error: String, hint: String): String =
        """{"ok":false,"intent":"","channel":"none","dry_run":false,"error":"$error","hint":"$hint"}"""
}
