package com.apex.agent.core.tools.extension.tools

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.RuntimeDomains
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # ExtensionRun —— 运行已安装的 ubuntu 域扩展（OpenCode / DSH 插件）
 *
 * 方向②「experimental 执行桥生产化」的 agent 侧入口：此前适配器只负责
 * 装进沙箱（install 标注 experimental），本工具补上**调度**半边 ——
 * 从账本寻址 → 校验 runtime=ubuntu → 读安装期落盘的 profile 元数据
 * （entryHint / runtime=node，由适配器写入）→ `NodeInstallPort.exec`
 * 白名单执行 `node <entry> [args…]`。
 *
 * 安全边界（description 如实声明，brief 的「诚实边界」要求）：
 * - entry 来自**自有账本目录**内的 profile 元数据，并经
 *   [ExtensionDirLayout.pkgFile] canonical 归属复核（防元数据篡改/符号链接）；
 * - argv 直接透传（**不经 shell**），但下游 node 脚本如何处理 `process.argv`
 *   属于脚本自身行为 —— 工具不承诺「参数对脚本安全」；
 * - 超时由 NodeInstallPort 下沉到进程层（ExecEngine 强杀 + proot
 *   --kill-on-exit），不是协程取消的假超时。
 */
class ExtensionRunTool(
    private val registry: ExtensionRegistry,
    private val layout: ExtensionDirLayout,
    private val port: NodeInstallPort
) : BaseTool(
    id = "ExtensionRun",
    name = "Extension Run",
    description = """
        Run an installed Ubuntu-domain extension (OpenCode / DSH plugin) inside
        the PRoot sandbox: node <entry> [args...].

        - id: extension id from ExtensionList (e.g. "opencode.scope-tool").
        - args: passed through to the plugin entry as argv. argv is passed
          directly (no shell), but the downstream node script decides how it
          treats process.argv — the tool does not guarantee how a plugin
          interprets its arguments.
        - Requires the extension's runtime to be "ubuntu"; android-domain
          extensions (apps / MCP connectors) are refused honestly.

        Requires node/npm ready in the sandbox (see ExtensionDoctor).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("id", required = true, description = "Extension id from ExtensionList, e.g. \"opencode.scope-tool\"")
        string("args", description = "Space-separated arguments forwarded to the plugin entry as argv")
        integer("timeout_ms", description = "Kill after this many ms (5000..600000, default 120000) — enforced at process level")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("extension", "marketplace", "run")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val id = args.requireString("id")
        val extraArgs = (args.optionalString("args") ?: "")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        val timeoutMs = (args.optionalInt("timeout_ms")?.toLong() ?: DEFAULT_TIMEOUT_MS).coerceIn(5_000L, 600_000L)

        // ── 账本寻址 + 域校验（runtime 字段显式判断，不猜）──
        val record = registry.get(id)
            ?: return ToolResult.fail(ToolErrorCode.NOT_FOUND, "未安装的扩展 id '$id'（用 ExtensionList 查看已装清单）")
        if (record.runtime != RuntimeDomains.UBUNTU) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "扩展 '$id' 运行在 ${record.runtime} 域（App/系统级），不支持沙箱内 node 运行"
            )
        }
        if (record.type != ExtensionTypes.OPENCODE_PLUGIN && record.type != ExtensionTypes.DSH_PLUGIN) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "扩展类型 '${record.type}' 无可运行入口（仅 opencode/dsh 插件提供 node 入口）"
            )
        }

        // ── profile 元数据 → entry（canonical 归属复核在 layout 内）──
        val profileRelative = when (record.type) {
            ExtensionTypes.OPENCODE_PLUGIN -> "opencode.json"
            else -> "dsh/profile.json"
        }
        val profileFile = layout.profileFile(id, profileRelative)
            .getOrElse { return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, it.message ?: "profile 路径校验失败") }
        if (!profileFile.isFile) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "扩展 '$id' 缺少运行元数据（$profileRelative）—— 重新安装以生成")
        }
        val profile = runCatching {
            ExtensionJson.json.parseToJsonElement(profileFile.readText()).jsonObject
        }.getOrElse {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "profile 元数据损坏：${it.message}")
        }
        val runtime = profile["runtime"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (runtime != NodeRuntimeMarker) {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, "扩展运行时 '$runtime' 不受支持（仅 node）")
        }
        val entryHint = profile["entryHint"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: return ToolResult.fail(ToolErrorCode.NOT_FOUND, "profile 缺少 entryHint —— 重新安装以生成")
        // entry 必须真实存在且落在 pkg 内（防 profile 被篡改指向外部脚本）
        val entryRelative = entryHint
            .removePrefix(layout.pkgDir(id).canonicalFile.path)
            .ifBlank { "/" }
        val entryFile = layout.pkgFile(id, entryRelative)
            .getOrElse { return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, it.message ?: "entry 路径校验失败") }
        if (!entryFile.isFile) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "入口文件不存在：${entryFile.path}")
        }

        // ── 白名单执行（argv 直透，不经 shell）──
        val outcome = port.exec(
            command = listOf("node", entryFile.canonicalFile.absolutePath) + extraArgs,
            cwd = layout.pkgDir(id),
            timeoutMs = timeoutMs
        ).getOrElse {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, it.message ?: "执行通道失败")
        }
        return ToolResult.ok(
            buildString {
                appendLine("exit_code: ${outcome.exitCode}")
                if (outcome.stdout.isNotBlank()) appendLine("stdout:\n${outcome.stdout.take(MAX_OUTPUT_CHARS)}")
                if (outcome.stderr.isNotBlank()) appendLine("stderr:\n${outcome.stderr.take(MAX_ERROR_CHARS)}")
                if (outcome.stdout.length > MAX_OUTPUT_CHARS || outcome.stderr.length > MAX_ERROR_CHARS) {
                    append("(输出已截断)")
                }
            }
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MAX_OUTPUT_CHARS = 12_000
        const val MAX_ERROR_CHARS = 6_000

        /** profile 元数据的运行时标记（适配器 install 时写入）。 */
        const val NodeRuntimeMarker = "node"
    }
}
