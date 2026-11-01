package com.apex.agent.core.tools.extension.tools

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.extension.ExtensionAdapterRegistry
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.toolSchema

/**
 * # Extension 查询族工具 —— Resolve（认路）/ Inspect（看清）/ List（账本）/ Doctor（体检）
 *
 * 全部只读；install/uninstall/sync 见 [ExtensionMutationTools]。
 * 统一输出人读文本；resolve/inspect 尾部带 `manifest_hash` —— install 的
 * 硬凭证（先看后装，见 ExtensionInstaller KDoc）。
 */

/** 认路：spec → 类型判定 + 预览摘要 + 指纹。 */
class ExtensionResolveTool(
    private val installer: ExtensionInstaller
) : BaseTool(
    id = "ExtensionResolve",
    name = "Extension Resolve",
    description = """
        Resolve an extension source spec into an installable manifest (step 1 of 3:
        resolve -> inspect -> install; install requires the manifest_hash printed here).

        Spec grammar (one syntax for every ecosystem):
        - skill:<url|json:{...}|local-path>          apex-skill-v1 manifest
        - mcp:{...} | mcp:import:<file> | mcp:<name> MCP server (register / import / catalog)
        - claude:<name>@<marketplace> | claude:<zip-or-repo>   Claude plugin
        - dsh:<pkg> | npm:<pkg> (dsh-* auto-detected)          DSH / OpenCode plugin
        - github:owner/repo                          content-sniffed
        - apk:<path>                                 APK extension package
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("spec", required = true, description = "Extension source spec, e.g. \"mcp:memory\" or \"skill:https://.../manifest.json\"")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("extension", "marketplace", "resolve")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val spec = args.requireString("spec")
        val manifest = installer.resolve(spec).getOrElse {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, it.message ?: "resolve 失败")
        }
        return ToolResult.ok(
            buildString {
                appendLine("resolved: ${manifest.type} · ${manifest.name} v${manifest.version}")
                appendLine("runtime: ${manifest.runtime} · trust: ${manifest.trustLevel} · action: ${manifest.action}")
                if (manifest.permissions.isNotEmpty()) appendLine("permissions: ${manifest.permissions.joinToString(", ")}")
                appendLine("summary: ${manifest.summary}")
                append("manifest_hash: ${manifest.manifestHash}")
                appendLine()
                append("(next: ExtensionInspect with this spec for full report, then ExtensionInstall with manifest_hash)")
            }
        )
    }
}

/** 看清：将装什么/装到哪/权限/脚本/告警 —— install 前必看。 */
class ExtensionInspectTool(
    private val installer: ExtensionInstaller
) : BaseTool(
    id = "ExtensionInspect",
    name = "Extension Inspect",
    description = """
        Inspect what an extension will install BEFORE installing (step 2 of 3):
        target registry, location, runtime domain + dependencies, declared
        permissions, lifecycle scripts, warnings. Returns the manifest_hash that
        ExtensionInstall requires. NEVER install without inspecting.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("spec", required = true, description = "Extension source spec (same grammar as ExtensionResolve)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("extension", "marketplace", "inspect")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val spec = args.requireString("spec")
        val manifest = installer.resolve(spec).getOrElse {
            return ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT, it.message ?: "resolve 失败")
        }
        val report = installer.inspect(manifest).getOrElse {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, it.message ?: "inspect 失败")
        }
        return ToolResult.ok(
            buildString {
                appendLine("## ${manifest.type} · ${manifest.name} v${manifest.version}")
                appendLine("- spec: ${manifest.spec}")
                appendLine("- runtime: ${manifest.runtime} · trust: ${manifest.trustLevel} · action: ${manifest.action}")
                appendLine("- target: ${report.targetRegistry}")
                appendLine("- location: ${report.targetLocation}")
                if (report.domainDependencies.isNotEmpty()) appendLine("- ubuntu deps: ${report.domainDependencies.joinToString("; ")}")
                if (manifest.permissions.isNotEmpty()) appendLine("- permissions: ${manifest.permissions.joinToString(", ")}")
                appendLine("- postinstall scripts: ${if (manifest.hasPostinstall) "YES (auto-install refused; explicit confirm required)" else "none detected"}")
                if (report.estimatedSize != null) appendLine("- estimated size: ${report.estimatedSize}")
                if (report.warnings.isNotEmpty()) report.warnings.forEach { appendLine("- ⚠️ $it") }
                appendLine("- summary: ${manifest.summary}")
                append("manifest_hash: ${manifest.manifestHash}")
            }
        )
    }
}

/** 账本：统一闸门装过的扩展清单（实体在各原生注册表）。 */
class ExtensionListTool(
    private val registry: ExtensionRegistry
) : BaseTool(
    id = "ExtensionList",
    name = "Extension List",
    description = "List extensions installed through the unified extension gate (source spec, version, runtime domain, install time). Entities live in their native registries (SkillRegistry / McpManager / ...).",
    declaredSchema = toolSchema {}
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("extension", "marketplace", "list")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val all = registry.all()
        if (all.isEmpty()) {
            return ToolResult.ok("（账本为空 —— 尚未通过统一扩展闸门安装过扩展；可用 ExtensionResolve 发现来源）")
        }
        return ToolResult.ok(
            buildString {
                appendLine("共 ${all.size} 个扩展（统一闸门账本）：")
                all.sortedByDescending { it.installedAt }.forEach { r ->
                    appendLine(
                        "- ${r.id} · ${r.type} · ${r.name} v${r.version} · ${r.runtime}" +
                            " · trust=${r.trustLevel} · from=${r.spec.take(60)}"
                    )
                }
            }
        )
    }
}

/** 体检：账本一致性 + 适配器可用面。 */
class ExtensionDoctorTool(
    private val installer: ExtensionInstaller,
    private val registry: ExtensionRegistry,
    private val adapters: ExtensionAdapterRegistry,
    /** 账本一致性探针：id → true=实体仍在原生注册表（适配器各自实现）。 */
    private val existenceProbe: suspend (ExtensionRegistry.Record) -> Boolean = { true }
) : BaseTool(
    id = "ExtensionDoctor",
    name = "Extension Doctor",
    description = "Diagnose the extension subsystem: ledger consistency (recorded extensions whose native entity disappeared), adapter availability, runtime domain readiness. Run this when installs behave oddly.",
    declaredSchema = toolSchema {}
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.UTILITY)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("extension", "marketplace", "diagnostics")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val all = registry.all()
        val missing = mutableListOf<String>()
        for (record in all) {
            val exists = runCatching { existenceProbe(record) }.getOrDefault(true)
            if (!exists) missing += record.id
        }
        return ToolResult.ok(
            buildString {
                appendLine("== Extension Doctor ==")
                appendLine("adapters available: ${adapters.availableTypes().sorted().joinToString(", ")}")
                appendLine("ledger size: ${all.size}")
                if (missing.isEmpty()) {
                    appendLine("ledger consistency: OK（账本与原生注册表一致）")
                } else {
                    appendLine("ledger consistency: ${missing.size} 个账目实体已消失（可 ExtensionUninstall 清账）：")
                    missing.forEach { appendLine("  - $it") }
                }
            }
        )
    }
}
