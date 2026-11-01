package com.apex.agent.core.tools.extension.tools

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.toolSchema

/**
 * # Extension 写入族工具 —— Install / Uninstall / Sync
 *
 * **ExtensionInstall 的 hash 门是本重构的核心安全语义**：必须先
 * ExtensionResolve/ExtensionInspect 拿到 manifest_hash 才能装（跳步硬拒）；
 * 敏感扩展（权限声明 / 生命周期脚本）还需 confirm="confirm" 显式确认，
 * BYPASS 权限模式不豁免。
 */

class ExtensionInstallTool(
    private val installer: ExtensionInstaller
) : BaseTool(
    id = "ExtensionInstall",
    name = "Extension Install",
    description = """
        Install an extension (step 3 of 3; the gate REQUIRES you inspected first).

        - manifest_hash (required): the hash printed by ExtensionResolve /
          ExtensionInspect. Installing without inspecting first is refused —
          this is a hard gate, not a suggestion.
        - confirm: required ("confirm") only when the inspect report listed
          permissions or postinstall scripts — show the user the report and get
          their agreement first. Never auto-confirm; permission BYPASS mode does
          not waive this.

        Examples:
        - {"manifest_hash": "ab12…"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("manifest_hash", required = true, description = "The manifest_hash from ExtensionResolve/ExtensionInspect output")
        string("confirm", description = "Explicit consent token \"confirm\" — required only when inspect reported permissions/postinstall")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("extension", "marketplace", "install")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val hash = args.requireString("manifest_hash")
        val confirm = args.optionalString("confirm")
        val outcome = installer.install(hash, confirmToken = confirm).getOrElse {
            return ToolResult.fail(
                when (it) {
                    is SecurityException -> ToolErrorCode.PERMISSION_DENIED
                    is IllegalArgumentException -> ToolErrorCode.INVALID_ARGUMENT
                    else -> ToolErrorCode.EXECUTION_FAILED
                },
                it.message ?: "install 失败"
            )
        }
        return ToolResult.ok(
            buildString {
                appendLine("installed: ${outcome.id} (${outcome.type}) v${outcome.version}")
                appendLine("where: ${outcome.where}")
                if (outcome.notes.isNotEmpty()) outcome.notes.forEach { appendLine("note: $it") }
            }
        )
    }
}

class ExtensionUninstallTool(
    private val installer: ExtensionInstaller
) : BaseTool(
    id = "ExtensionUninstall",
    name = "Extension Uninstall",
    description = "Uninstall an extension installed through the unified gate (removes the native entity from its registry and clears the ledger record). Use ExtensionList to find ids.",
    declaredSchema = toolSchema {
        string("id", required = true, description = "Extension id from ExtensionList, e.g. \"mcp.memory\"")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("extension", "marketplace", "uninstall")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val id = args.requireString("id")
        return installer.uninstall(id).fold(
            onSuccess = { ToolResult.ok("uninstalled: $id（原生注册表实体已移除，账本已清）") },
            onFailure = {
                ToolResult.fail(ToolErrorCode.NOT_FOUND, it.message ?: "uninstall 失败")
            }
        )
    }
}

/**
 * 声明式批量同步：`{"extensions": ["mcp:memory", "skill:<url>", …]}` 逐个
 * resolve → inspect → install。默认 dry-run（只报告不落盘）；`install=true`
 * 才真装。敏感扩展被确认门拦截 —— 在 install=true 时携带 confirm。
 */
class ExtensionSyncTool(
    private val installer: ExtensionInstaller
) : BaseTool(
    id = "ExtensionSync",
    name = "Extension Sync",
    description = """
        Declarative batch sync: resolve -> inspect -> install a whole manifest of
        extensions at once. Body format: {"extensions": ["mcp:memory",
        "skill:https://.../manifest.json", ...]}. Default is a dry run (report
        only); pass install=true to actually install. Extensions requiring
        confirmation (permissions / postinstall) are refused unless
        confirm="confirm" is passed — surface them to the user instead.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("content", required = true, description = "Declarative manifest JSON: {\"extensions\": [spec, ...]}")
        boolean("install", description = "false = dry run (default); true = install all resolvable entries")
        string("confirm", description = "Explicit consent token for entries that require confirmation")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.MEDIUM)
        tag("extension", "marketplace", "sync")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val content = args.requireString("content")
        val install = args.optionalBoolean("install") == true
        val confirm = args.optionalString("confirm")

        val specs = runCatching {
            val obj = com.apex.agent.core.tools.extension.ExtensionJson.json
                .parseToJsonElement(content).jsonObjectSafe()
            (obj["extensions"]?.let { it as? kotlinx.serialization.json.JsonArray })
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: throw IllegalArgumentException("顶层缺少 extensions 数组（{\"extensions\": [spec, …]}）")
        }.getOrElse {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, it.message ?: "manifest 解析失败")
        }
        if (specs.isEmpty()) return ToolResult.ok("extensions 清单为空 —— 无事可做")

        if (!install) {
            // dry-run：逐个 resolve + inspect 报告，不落盘。
            val lines = specs.map { spec ->
                installer.resolve(spec).fold(
                    onSuccess = { m ->
                        "  ✓ $spec → ${m.type} · ${m.name} v${m.version} · ${m.runtime}" +
                            (if (m.requiresConfirm()) "（需确认）" else "")
                    },
                    onFailure = { "  ✗ $spec → ${it.message?.take(120)}" }
                )
            }
            return ToolResult.ok(
                buildString {
                    appendLine("sync dry-run（${specs.size} 项，未安装）:")
                    lines.forEach { appendLine(it) }
                    append("install=true 执行实际安装")
                }
            )
        }

        val results = installer.installFromSpecs(specs, confirmToken = confirm)
        val ok = results.count { it.second.isSuccess }
        return ToolResult.ok(
            buildString {
                appendLine("sync done: $ok/${specs.size} 安装成功")
                results.forEach { (spec, result) ->
                    result.fold(
                        onSuccess = { o -> appendLine("  ✓ $spec → ${o.where}") },
                        onFailure = { appendLine("  ✗ $spec → ${it.message?.take(160)}") }
                    )
                }
            }
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): kotlinx.serialization.json.JsonObject =
        this as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("manifest 顶层必须是 JSON 对象")
}
