package com.apex.agent.core.tools.pluginhost.doctor

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.pluginhost.PluginCompatibilityAnalyzer
import com.apex.agent.core.tools.pluginhost.PluginDescriptor
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import com.apex.agent.core.tools.pluginhost.PluginEnvironmentSnapshot
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.lifecycle.PluginLifecycleManager
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import com.apex.agent.core.tools.pluginhost.worker.RuntimeProvider
import com.apex.agent.core.tools.toolSchema

/**
 * # PluginHostDoctor —— 插件宿主诊断工具（spec §11.3 / §16.5）
 *
 * Agent/用户侧的统一诊断面（只读）：
 * - **生命周期状态**：每个受管插件的 14 态状态机快照 + 最近原因；
 * - **Worker 健康**：受看护插件、运行实例存活、ping 往返、放弃原因；
 * - **工具绑定**：每插件已注册进 ToolRegistry 的工具 id；
 * - **运行时环境**：node/bun 真实探测（不臆测）；
 * - **兼容性评级**：对给定生态快速给出当前评级与原因（矩阵实时反映实现状态）。
 *
 * 对照 spec 的「检查运行时 / 检查工具注册 / 检查 Worker / 导出诊断信息」
 * 清单 —— 本工具是 Agent 可自诊断的入口，结论可直接进入恢复提示词。
 */
class PluginHostDoctorTool(
    private val lifecycle: PluginLifecycleManager,
    private val supervisor: PluginWorkerSupervisor,
    private val toolBridge: PluginToolBridge,
    private val nodeProvider: RuntimeProvider,
    private val bunProvider: RuntimeProvider
) : BaseTool(
    id = "PluginHostDoctor",
    name = "Plugin Host Doctor",
    description = """
        Diagnose the Native Plugin Host: lifecycle states, worker health,
        bound tools, and runtime environment (node/bun probes).

        - ecosystem: optional; when set (claude | opencode | dsh | mcp),
          also reports the current compatibility level for that ecosystem.
        - Read-only; safe to call any time. Use it before retrying after a
          plugin tool failure to learn whether the worker crashed or the
          plugin was stopped.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("ecosystem", description = "Optional ecosystem id for a compatibility verdict: claude | opencode | dsh | mcp")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.PLUGIN)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("plugin-host", "diagnostics")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val ecosystem = args.optionalString("ecosystem")?.trim()?.lowercase()

        val report = buildString {
            appendLine("== Plugin Host Doctor ==")
            // ── 生命周期状态 ──────────────────────────────────────────
            val statuses = lifecycle.statuses()
            appendLine("[lifecycle] managed plugins: ${statuses.size}")
            statuses.forEach { s ->
                appendLine("- ${s.pluginId}: ${s.state.name}" +
                    (s.ecosystem?.let { " (${it.name})" } ?: "") +
                    (s.lastReason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
            }
            // ── Worker 健康 ───────────────────────────────────────────
            appendLine("[workers] supervised: ${supervisor.supervisedIds().size}")
            for (id in supervisor.supervisedIds().sorted()) {
                val worker = supervisor.worker(id)
                val alive = worker?.alive == true
                val ping = if (alive) {
                    supervisor.healthCheck(id).fold(
                        onSuccess = { "ping=${it}ms" },
                        onFailure = { "ping failed: ${it.message}" }
                    )
                } else {
                    "not running"
                }
                val gaveUp = supervisor.gaveUpReason(id)?.let { " GAVE_UP: $it" } ?: ""
                appendLine("- $id: alive=$alive $ping$gaveUp")
            }
            // ── 工具绑定 ──────────────────────────────────────────────
            appendLine("[tools] bound plugins: ${toolBridge.boundPluginIds().size}")
            for (id in toolBridge.boundPluginIds().sorted()) {
                appendLine("- $id: ${toolBridge.boundToolIds(id).size} tools: " +
                    toolBridge.boundToolIds(id).sorted().joinToString(", ").take(200))
            }
            // ── 运行时环境（真实探测）────────────────────────────────
            val node = nodeProvider.probe()
            val bun = bunProvider.probe()
            appendLine("[runtime] node=${node ?: "missing"} bun=${bun ?: "missing"}")
            appendLine(
                "[runtime] bun 缺失不回退 node 冒充（OpenCode 官方运行时纪律）；" +
                    "node 缺失时 OpenCode/DSH Worker 无法启动"
            )
            // ── 兼容性评级（可选生态）────────────────────────────────
            if (ecosystem != null) {
                val eco = parseEcosystem(ecosystem)
                if (eco == null) {
                    appendLine("[compat] unknown ecosystem '$ecosystem'")
                } else {
                    val env = PluginEnvironmentSnapshot(
                        nodeVersion = node,
                        bunVersion = bun
                    )
                    val probe = PluginDescriptor(
                        id = "compat.probe",
                        name = "Probe",
                        ecosystem = eco
                    )
                    val verdict = PluginCompatibilityAnalyzer.analyze(probe, env)
                    appendLine("[compat] ${eco.name} → ${verdict.level.name}: ${verdict.level.label}")
                    verdict.reasons.take(6).forEach { appendLine("  · $it") }
                }
            }
            appendLine("== end ==")
        }
        return ToolResult.ok(report)
    }

    private fun parseEcosystem(raw: String): PluginEcosystem? = when (raw) {
        "claude" -> PluginEcosystem.CLAUDE
        "opencode", "opencode-v2" -> PluginEcosystem.OPENCODE_V2
        "opencode-v1" -> PluginEcosystem.OPENCODE_V1
        "dsh" -> PluginEcosystem.DSH
        "mcp" -> PluginEcosystem.MCP
        else -> null
    }
}
