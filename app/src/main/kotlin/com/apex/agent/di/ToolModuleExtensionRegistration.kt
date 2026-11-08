package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.extension.ExtensionAdapterRegistry
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.tools.ExtensionDoctorTool
import com.apex.agent.core.tools.extension.tools.ExtensionInstallTool
import com.apex.agent.core.tools.extension.tools.ExtensionInspectTool
import com.apex.agent.core.tools.extension.tools.ExtensionListTool
import com.apex.agent.core.tools.extension.tools.ExtensionResolveTool
import com.apex.agent.core.tools.extension.tools.ExtensionRunTool
import com.apex.agent.core.tools.extension.tools.ExtensionSyncTool
import com.apex.agent.core.tools.extension.tools.ExtensionUninstallTool
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.doctor.PluginHostDoctorTool
import com.apex.agent.core.tools.pluginhost.lifecycle.PluginLifecycleManager
import com.apex.agent.core.tools.pluginhost.worker.BunRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.NodeRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillRegistry

/**
 * §12c 统一扩展模型（市场完全重构）—— 工具注册体，自 ToolModule.kt
 * 拆分（SRP 预算纪律，同 [ToolModuleRegistrySections] 惯例）。
 *
 * PR-A：Resolve / Inspect / Install（三步入 CORE 集）+ List / Uninstall /
 * Sync / Doctor（CATALOG 渐进披露）；旧入口（SkillInstall / McpConnect）
 * 双轨保留。
 * PR-B：适配器面扩到 opencode / claude / dsh；Doctor 加 ubuntu 域探针与
 * 锁计数；Uninstall 联动清锁。
 */
internal fun registerExtensionTools(
    registry: DefaultToolRegistry,
    installer: ExtensionInstaller,
    adapterRegistry: ExtensionAdapterRegistry,
    ledger: ExtensionRegistry,
    skillRegistry: SkillRegistry,
    mcpManager: McpManager,
    fusionRegistry: com.apex.agent.core.tools.fusion.FusionBundleRegistry,
    nodeInstallPort: NodeInstallPort?,
    extensionLayout: com.apex.agent.core.tools.extension.ExtensionDirLayout,
    pluginLifecycle: PluginLifecycleManager? = null,
    pluginSupervisor: PluginWorkerSupervisor? = null,
    pluginToolBridge: PluginToolBridge? = null
) {
    registry.register(SafeAgentTool(ExtensionResolveTool(installer)))
    registry.register(SafeAgentTool(ExtensionInspectTool(installer)))
    registry.register(SafeAgentTool(ExtensionInstallTool(installer)))
    registry.register(SafeAgentTool(ExtensionListTool(ledger)))
    // PR-D：ubuntu 域扩展运行入口（OpenCode/DSH 执行桥生产化 —— 不再只装不跑）
    nodeInstallPort?.let { port ->
        registry.register(
            SafeAgentTool(
                ExtensionRunTool(
                    registry = ledger,
                    layout = extensionLayout,
                    port = port
                )
            )
        )
    }
    registry.register(SafeAgentTool(ExtensionUninstallTool(installer, ledger)))
    // Native Plugin Host 2.0：宿主诊断（生命周期/Worker 健康/工具绑定/运行时探测）
    if (pluginLifecycle != null && pluginSupervisor != null && pluginToolBridge != null && nodeInstallPort != null) {
        registry.register(
            SafeAgentTool(
                PluginHostDoctorTool(
                    lifecycle = pluginLifecycle,
                    supervisor = pluginSupervisor,
                    toolBridge = pluginToolBridge,
                    nodeProvider = NodeRuntimeProvider(nodeInstallPort),
                    bunProvider = BunRuntimeProvider(nodeInstallPort)
                )
            )
        )
    }
    registry.register(SafeAgentTool(ExtensionSyncTool(installer, ledger)))
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
                        // Claude 适配器：实体 = 聚合包（claude.<slug> → claude-<slug>）
                        "claude_plugin" -> fusionRegistry.get(record.id.replace('.', '-')) != null
                        else -> true // apk_package（PR-C）等占位
                    }
                },
                // ubuntu 域探针（node/npm 版本）—— PR-B 接 NodeInstallPort 后启用
                ubuntuProbe = nodeInstallPort?.let { port ->
                    { port.probeRuntime() }
                },
                lockSummary = { ledger.allLocks().size }
            )
        )
    )
}
