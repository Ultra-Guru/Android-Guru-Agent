package com.apex.agent.core.tools.extension.tools

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * # Extension 写入族工具 —— Install / Uninstall / Sync
 *
 * **ExtensionInstall 的 hash 门是本重构的核心安全语义**：必须先
 * ExtensionResolve/ExtensionInspect 拿到 manifest_hash 才能装（跳步硬拒）；
 * 敏感扩展（权限声明 / 生命周期脚本）还需 confirm="confirm" 显式确认，
 * BYPASS 权限模式不豁免。
 *
 * **ExtensionSync 的锁文件语义**（brief：锁文件防悄悄换版本）：安装成功即钉版
 * （lock.json）；后续 sync 时源指纹 ≠ 锁 → 版本漂移，默认跳过锁定项如实报告，
 * `update=true` 才重新钉版。
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
    private val installer: ExtensionInstaller,
    private val registry: ExtensionRegistry
) : BaseTool(
    id = "ExtensionUninstall",
    name = "Extension Uninstall",
    description = "Uninstall an extension installed through the unified gate (removes the native entity from its registry, clears the ledger record and any sync lock). Use ExtensionList to find ids.",
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
            onSuccess = {
                registry.unlock(id)
                ToolResult.ok("uninstalled: $id（原生注册表实体已移除，账本与锁已清）")
            },
            onFailure = {
                ToolResult.fail(ToolErrorCode.NOT_FOUND, it.message ?: "uninstall 失败")
            }
        )
    }
}

/**
 * 声明式批量同步：`{"extensions": ["mcp:memory", "skill:<url>", …]}` 逐个
 * resolve → inspect → install，**安装成功即钉版**（lock.json）。默认 dry-run
 * （只报告不落盘）；`install=true` 才真装。锁漂移（源指纹 ≠ 锁）默认跳过锁定
 * 项并如实报告，`update=true` 才重钉 —— 防 Agent 在后续会话里悄悄换版本。
 */
class ExtensionSyncTool(
    private val installer: ExtensionInstaller,
    private val registry: ExtensionRegistry
) : BaseTool(
    id = "ExtensionSync",
    name = "Extension Sync",
    description = """
        Declarative batch sync: resolve -> inspect -> install a whole manifest of
        extensions at once; successful installs are pinned into the lock file
        (lock.json). Body: {"extensions": ["mcp:memory", "skill:https://...", ...]}.
        Default is a dry run (report only); install=true actually installs.
        Drift handling: if a source's current hash differs from its lock, the
        locked entry is SKIPPED and reported (no silent version swaps) unless
        update=true re-pins it. Entries requiring confirmation (permissions /
        postinstall) are refused unless confirm="confirm".
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("content", required = true, description = "Declarative manifest JSON: {\"extensions\": [spec, ...]}")
        boolean("install", description = "false = dry run (default); true = install all resolvable entries")
        boolean("update", description = "true = re-pin drifted locks to the source's current version (default: skip drifted, report)")
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
        val update = args.optionalBoolean("update") == true
        val confirm = args.optionalString("confirm")

        val specs = runCatching {
            val obj = ExtensionJson.json.parseToJsonElement(content).jsonObject
            (obj["extensions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: throw IllegalArgumentException("顶层缺少 extensions 数组（{\"extensions\": [spec, …]}）")
        }.getOrElse {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, it.message ?: "manifest 解析失败")
        }
        if (specs.isEmpty()) return ToolResult.ok("extensions 清单为空 —— 无事可做")

        if (!install) {
            // dry-run：逐个 resolve 报告 + 锁漂移检测，不落盘。
            val lines = specs.map { spec ->
                installer.resolve(spec).fold(
                    onSuccess = { m ->
                        val locked = registry.getLock(m.id)
                        val drift = locked != null && locked.manifestHash != m.manifestHash
                        "  ✓ $spec → ${m.type} · ${m.name} v${m.version} · ${m.runtime}" +
                            (if (m.requiresConfirm()) "（需确认）" else "") +
                            (if (drift && locked != null) {
                                " ⚠️ 版本漂移（锁 v${locked.version}，源 v${m.version}）—— update=true 重钉"
                            } else "")
                    },
                    onFailure = { "  ✗ $spec → ${it.message?.take(120)}" }
                )
            }
            return ToolResult.ok(
                buildString {
                    appendLine("sync dry-run（${specs.size} 项，未安装）:")
                    lines.forEach { appendLine(it) }
                    append("install=true 执行实际安装（成功项自动钉版进 lock.json）")
                }
            )
        }

        val results = installer.installFromSpecs(
            specs,
            confirmToken = confirm,
            // 锁漂移闸：锁定中且源指纹变了 —— 默认跳过（防悄悄换版本），update=true 才放行重钉
            filter = { manifest ->
                val locked = registry.getLock(manifest.id)
                update || locked == null || locked.manifestHash == manifest.manifestHash
            }
        )
        val ok = results.count { it.second.isSuccess }
        return ToolResult.ok(
            buildString {
                appendLine("sync done: $ok/${specs.size} 安装成功")
                results.forEach { (spec, result) ->
                    result.fold(
                        onSuccess = { o ->
                            registry.lock(
                                ExtensionRegistry.LockEntry(
                                    id = o.id,
                                    manifestHash = registry.get(o.id)?.manifestHash.orEmpty(),
                                    version = o.version,
                                    spec = spec,
                                    lockedAt = System.currentTimeMillis()
                                )
                            )
                            appendLine("  ✓ $spec → ${o.where}（已钉版）")
                        },
                        onFailure = { appendLine("  ✗ $spec → ${it.message?.take(160)}") }
                    )
                }
            }
        )
    }
}
