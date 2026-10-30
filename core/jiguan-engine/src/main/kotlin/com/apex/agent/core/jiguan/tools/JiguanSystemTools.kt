package com.apex.agent.core.jiguan.tools

import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk

/**
 * # 系统自动化域工具（System & UI）
 *
 * - [JiguanUiTouchTool]：ui_inject_touch —— Root 级物理输入注入（input tap/swipe，
 *   比无障碍通道更隐蔽 —— 不依赖 a11y 树）；
 * - [JiguanSystemInspectTool]：SELinux 模式 / 自身上下文 / Binder 服务面巡检。
 */
// ═══════════════════════════════════════════════════════════════════

class JiguanUiTouchTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanUiTouch"
    override val name = "Jiguan: Root Touch Injection"
    override val description = """
        Inject physical touch events at the system level (ui_inject_touch) via root
        `input tap/swipe` — stealthier than accessibility clicks (no a11y tree
        dependency). action=tap or swipe. Find coordinates with Screenshot/UiDump.

        Example tap: {"action": "tap", "x": 540, "y": 1200}
        Example swipe: {"action": "swipe", "x": 540, "y": 1600, "x2": 540, "y2": 400, "duration_ms": 300}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["tap", "swipe"]},
                "x": {"type": "integer", "description": "Tap x (or swipe start x)"},
                "y": {"type": "integer", "description": "Tap y (or swipe start y)"},
                "x2": {"type": "integer", "description": "Swipe end x"},
                "y2": {"type": "integer", "description": "Swipe end y"},
                "duration_ms": {"type": "integer", "description": "Swipe duration (default 300)"}
            },
            "required": ["action", "x", "y"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.UI)
        .risk(ToolRisk.MEDIUM)
        .tag("jiguan", "ui", "input", "root")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val action = JiguanArgs.string(args, "action")
        val x = JiguanArgs.int(args, "x", -1)
        val y = JiguanArgs.int(args, "y", -1)
        if (x < 0 || y < 0) return "Error: missing x/y"
        return when (action) {
            "tap" -> {
                runtime.system.injectTouch(x, y, swipe = false, 0, 0, 0).fold(
                    onSuccess = { "Injected tap @ ($x, $y) via root input channel." },
                    onFailure = {
                        "Error: root input unavailable (${it.message ?: "offline"}) — " +
                            "needs root (PrivilegeManager); a11y fallback exists via UiTap."
                    },
                )
            }
            "swipe" -> {
                val x2 = JiguanArgs.int(args, "x2", -1)
                val y2 = JiguanArgs.int(args, "y2", -1)
                if (x2 < 0 || y2 < 0) return "Error: swipe requires x2/y2"
                val duration = JiguanArgs.int(args, "duration_ms", 300).coerceIn(50, 5000).toLong()
                runtime.system.injectTouch(x, y, swipe = true, x2, y2, duration).fold(
                    onSuccess = { "Injected swipe ($x,$y)→($x2,$y2) ${duration}ms via root input channel." },
                    onFailure = {
                        "Error: root input unavailable (${it.message ?: "offline"}) — needs root; a11y fallback exists via UiSwipe."
                    },
                )
            }
            else -> "Error: 'action' must be tap | swipe"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanSystemInspectTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanSystemInspect"
    override val name = "Jiguan: System Domain Inspect"
    override val description = """
        Inspect the system/firmware dimension: SELinux mode (Enforcing/Permissive),
        our own SELinux context (u:r:...), root availability, and the Binder service
        surface sample (service list). Use this to plan whether a technique will
        survive the device's security posture before attempting it.

        Example: {}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {},
            "required": []
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "system", "selinux", "binder")
        .build()

    override suspend fun execute(arguments: String): String {
        val result = runtime.system.inspect().fold(
            onSuccess = { it },
            onFailure = {
                return "Error: system inspection unavailable (${it.message ?: "offline"}) — " +
                    "getenforce/ls -Z need shell channel (root or Shizuku)."
            },
        )
        return buildString {
            appendLine("System domain (机关 系统维度):")
            appendLine("- SELinux: ${result.selinuxMode}")
            appendLine("- self context: ${result.selfContext.ifBlank { "?" }}")
            appendLine("- root: ${if (result.rootAvailable) "available" else "unavailable"}")
            if (result.serviceSample.isNotEmpty()) {
                appendLine("- binder services (${result.serviceSample.size} sampled):")
                result.serviceSample.take(30).forEach { appendLine("  - $it") }
            }
            if (result.selinuxMode == "Enforcing") {
                appendLine("Note: SELinux Enforcing — /proc of other UIDs may still be blocked even with root;")
                appendLine("ptrace/injection channels are subject to domain transitions (u:r:su:s0 vs u:r:shell:s0).")
            }
        }
    }
}
