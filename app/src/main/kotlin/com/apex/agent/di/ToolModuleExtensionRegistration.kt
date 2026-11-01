package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.extension.ExtensionAdapterRegistry
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.tools.ExtensionDoctorTool
import com.apex.agent.core.tools.extension.tools.ExtensionInstallTool
import com.apex.agent.core.tools.extension.tools.ExtensionInspectTool
import com.apex.agent.core.tools.extension.tools.ExtensionListTool
import com.apex.agent.core.tools.extension.tools.ExtensionResolveTool
import com.apex.agent.core.tools.extension.tools.ExtensionSyncTool
import com.apex.agent.core.tools.extension.tools.ExtensionUninstallTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillRegistry

/**
 * §12c 统一扩展模型（市场完全重构 PR-A）—— 七工具注册体，自 ToolModule.kt
 * 拆分（SRP 预算纪律，同 [ToolModuleRegistrySections] 惯例）。
 *
 * 工具面：Resolve / Inspect / Install（三步入 CORE 集，ToolTierPolicy）+
 * List / Uninstall / Sync / Doctor（CATALOG 渐进披露）。旧入口
 * （SkillInstall / McpConnect）本 PR 原样保留 —— 双轨，PR-B 再内部转接。
 */
internal fun registerExtensionTools(
    registry: DefaultToolRegistry,
    installer: ExtensionInstaller,
    adapterRegistry: ExtensionAdapterRegistry,
    ledger: ExtensionRegistry,
    skillRegistry: SkillRegistry,
    mcpManager: McpManager
) {
    registry.register(SafeAgentTool(ExtensionResolveTool(installer)))
    registry.register(SafeAgentTool(ExtensionInspectTool(installer)))
    registry.register(SafeAgentTool(ExtensionInstallTool(installer)))
    registry.register(SafeAgentTool(ExtensionListTool(ledger)))
    registry.register(SafeAgentTool(ExtensionUninstallTool(installer)))
    registry.register(SafeAgentTool(ExtensionSyncTool(installer)))
    registry.register(
        SafeAgentTool(
            ExtensionDoctorTool(
                installer = installer,
                registry = ledger,
                adapters = adapterRegistry,
                // 账本一致性探针：实体仍在原生注册表才算健康。
                existenceProbe = { record ->
                    when (record.type) {
                        "skill" -> skillRegistry.get(record.id.removePrefix("skill.")) != null
                        "mcp_connector" -> mcpManager.getConfigs().any {
                            it.name == record.id.removePrefix("mcp.")
                        }
                        else -> true // PR-B/C 类型占位 —— 适配器接入时补探针
                    }
                }
            )
        )
    )
}
