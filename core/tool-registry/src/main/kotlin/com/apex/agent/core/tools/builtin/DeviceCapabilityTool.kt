package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.device.CapabilityProbe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ═══ DeviceCapability —— 设备能力自省（S01）═══
 *
 * 执行任何设备意图前的第一反动作：拿档位 / uid / binder 态 / supports /
 * denies 两组面板，杜绝「落到 NORMAL_SHELL 还硬跑 pm uninstall」的盲试。
 *
 * 只读工具：readOnlyHint=true + [ToolRisk.LOW]（进 PermissionDecider
 * 只读快路径）；返回结构化 JSON。
 */
class DeviceCapabilityTool(
    private val probe: CapabilityProbe
) : AgentTool {

    override val id = "DeviceCapability"
    override val name = "Device Capability Introspection"
    override val description = """
        Probe the current device-control privilege level before any device
        operation (uninstall / file ops / settings). Returns a structured
        JSON snapshot: privilege (ROOT/SHIZUKU/NORMAL_SHELL), uid, shizuku
        binder state, supported intents and denied intents with actionable
        steps to unlock. Call this FIRST when a device operation fails with
        permission errors, or before planning device mutations.

        Examples:
        - {}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {},
            "required": []
        }
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        risk(ToolRisk.LOW)
        // 显式声明只读（S01：进 PermissionDecider 只读快路径，不弹确认）。
        annotations {
            readOnly()
        }
        tag("device")
        tag("probe")
    }

    override suspend fun execute(arguments: String): String {
        val snapshot = try {
            probe.snapshot()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return buildJsonObject {
                put("ok", false)
                put("error", "PROBE_FAILED")
                put("hint", "能力探测失败：${e::class.simpleName}: ${e.message}")
            }.toString()
        }
        return buildJsonObject {
            put("ok", true)
            put("privilege", snapshot.privilege.name)
            snapshot.uid?.let { put("uid", it) }
            putJsonObject("shizuku") {
                put("binderAlive", snapshot.shizukuBinderAlive)
                put("granted", snapshot.shizukuGranted)
                snapshot.shizukuApiLevel?.let { put("apiLevel", it) }
            }
            put("supports", buildJsonArray {
                snapshot.supports.sorted().forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            })
            putJsonObject("denies") {
                snapshot.denies.entries.sortedBy { it.key }.forEach { (k, v) ->
                    put(k, v)
                }
            }
            put("probedAt", snapshot.probedAtMillis)
        }.toString()
    }
}
