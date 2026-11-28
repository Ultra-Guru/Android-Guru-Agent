package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ═══ DeviceAuditQuery —— DCP 审计查询（S10）═══
 *
 * 按意图 / 时间 / 决策过滤最近设备操作审计（logs/tool-audit.jsonl 的
 * DeviceOps 段）。一次 dry_run → approved → executed 用同一 auditId 串联，
 * 可完整回放一次设备操作的确认链。
 */
class DeviceAuditQueryTool(
    private val host: Host
) : AgentTool {

    /** 审计查询宿主（app 层包装 ToolAuditLogger.recentEntries 过滤）。 */
    interface Host {
        suspend fun query(intentId: String?, sinceMillis: Long?, decision: String?, limit: Int): List<Entry>
    }

    /** 一条审计记录的查询视图（与 ToolAuditLogger.AuditEntry 的 DCP 字段对齐）。 */
    data class Entry(
        val ts: Long,
        val decision: String,
        val intentId: String?,
        val risk: String?,
        val privilegeTier: String?,
        val uid: Int?,
        val dryRun: Boolean?,
        val confirmToken: String?,
        val reversible: Boolean?,
        val rollbackHint: String?,
        val auditId: String?,
        val command: String?,
        val success: Boolean?
    )

    override val id = "DeviceAuditQuery"
    override val name = "Device Audit Query"
    override val description = """
        Query the DCP device-operation audit trail (tool-audit.jsonl, DeviceOps
        entries). Filter by intent id, time window and decision (preflight /
        approved / executed / denied_by_user / failed). Every operation writes
        three entries sharing one audit_id: dry_run preflight, gate approval
        and the executed/failed outcome — replay a full confirmation chain by
        audit_id.

        Examples:
        - {"intentId": "package.uninstall_for_user"}
        - {"decision": "denied_by_user", "limit": 20}
        - {"auditId": "dcp-ab12cd34"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "intentId": {"type": "string", "description": "Filter by intent id (exact)"},
                "decision": {"type": "string", "enum": ["preflight", "approved", "executed", "denied_by_user", "failed"], "description": "Filter by decision"},
                "auditId": {"type": "string", "description": "Filter by audit id (replay one operation)"},
                "sinceMillis": {"type": "integer", "description": "Only entries at or after this epoch millis"},
                "limit": {"type": "integer", "description": "Max entries (default 50, max 200)"}
            }
        }
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        risk(ToolRisk.LOW)
        annotations { readOnly() }
        tag("device")
        tag("audit")
    }

    override suspend fun execute(arguments: String): String {
        val parsed = try {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments.ifBlank { "{}" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return err("INVALID_JSON", "参数不是合法 JSON：${e.message}")
        }
        val args = parsed as? kotlinx.serialization.json.JsonObject
            ?: return err("INVALID_PARAMS", "参数必须是 JSON 对象")
        fun str(key: String) = (args[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val intentId = str("intentId")
        val decision = str("decision")
        val auditId = str("auditId")
        val sinceMillis = (args["sinceMillis"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toLongOrNull()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: 50).coerceIn(1, 200)

        val entries = host.query(intentId, sinceMillis, decision, limit)
        val filtered = if (auditId != null) entries.filter { it.auditId == auditId } else entries
        return buildJsonObject {
            put("ok", true)
            put("count", filtered.size)
            put("entries", buildJsonArray {
                filtered.forEach { e ->
                    add(buildJsonObject {
                        put("ts", e.ts)
                        put("decision", e.decision)
                        e.intentId?.let { put("intent", it) }
                        e.risk?.let { put("risk", it) }
                        e.privilegeTier?.let { put("tier", it) }
                        e.uid?.let { put("uid", it) }
                        e.dryRun?.let { put("dry_run", it) }
                        e.confirmToken?.let { put("confirm_token", it) }
                        e.reversible?.let { put("reversible", it) }
                        e.rollbackHint?.let { put("rollback_hint", it) }
                        e.auditId?.let { put("audit_id", it) }
                        e.command?.let { put("command", it) }
                        e.success?.let { put("success", it) }
                    })
                }
            })
        }.toString()
    }

    private fun err(error: String, hint: String): String =
        """{"ok":false,"error":"$error","hint":"$hint"}"""
}
