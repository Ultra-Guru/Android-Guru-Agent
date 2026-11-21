package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T81 (D-7 / §30/§52)：TerminalLinuxRepair —— 单轮自动修复编排
 * （detect → repair（每维度一次）→ verify；无循环）。
 *
 * P0-#3 工具面收敛：本类原与 TerminalLinuxCapabilitiesTool 同文件；
 * Capabilities 随收敛移除后拆出为独立文件（修复入口保留 —— 与
 * TerminalUbuntuEnsure / TerminalLinuxPackages / TerminalLinuxNetwork
 * / TerminalLinuxStatus 同为现役细粒度入口）。
 */
class TerminalLinuxRepairTool(
    private val repairService: com.apex.agent.platform.terminal.health.EnvironmentRepairService
) : TerminalTool {

    override val id: String = "TerminalLinuxRepair"
    override val name: String = id
    override val parametersSchema: String = """
        {"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override val description: String = """
        Run ONE bounded auto-repair round on the Linux environment:
        detect (health check) → repair each repairable dimension once
        (rootfs → provisioner.repair; apt → dpkg --configure -a) → verify.
        No loops, no retry storms — call again explicitly if needed.
        Returns the repair actions taken and the post-repair health report.
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val report = repairService.autoRepair()
        return buildJsonObject {
            put("ok", true)
            put("repairedCount", report.repaired.size)
            put("verifiedHealthy", report.verifiedHealthy)
            put("actions", buildJsonArray {
                report.repaired.forEach { a ->
                    add(buildJsonObject {
                        put("dimension", a.dimension)
                        put("action", a.action)
                        put("outcome", a.outcome)
                        a.detail?.let { put("detail", it.take(300)) }
                    })
                }
            })
            put("overall", report.verification?.overall?.name ?: "UNKNOWN")
        }.toString()
    }
}
