package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.fusion.FusionActivationSink
import com.apex.agent.core.tools.fusion.FusionActivateTool
import com.apex.agent.core.tools.fusion.FusionCreateTool
import com.apex.agent.core.tools.fusion.FusionInfoTool
import com.apex.agent.core.tools.fusion.FusionInstallMembersTool
import com.apex.agent.core.tools.fusion.FusionListTool
import com.apex.agent.fusion.FusionBundleController

/**
 * §15 Fusion 工具族注册体 —— 自 ToolModule.kt 拆分（SRP 预算：主文件
 * 逼近 1200 行上限；模式同 ToolModuleRegistrySections / V5Registration 卫星）。
 *
 * 三个工具（FusionList / FusionInfo / FusionActivate）：模型可在对话中
 * 自主发现与激活复合技能包 —— 与 Skill 族同构的渐进披露闭环。
 * 依赖单一 [FusionBundleController]（其内部聚合 registry + activator）。
 *
 * 激活回灌：FusionActivate 的成功结果经 [FusionActivationSink] 回灌控制
 * 面 —— 模型侧激活与输入栏按钮路径保持同一激活状态源（UI 横幅 + 持久化）。
 */
internal fun registerFusionBundleTools(
    registry: DefaultToolRegistry,
    fusionController: FusionBundleController,
    skillRegistry: com.apex.agent.core.tools.skill.SkillRegistry,
    mcpManager: com.apex.agent.core.tools.mcp.McpManager,
    activationStore: com.apex.agent.core.tools.skill.SkillActivationStore
) {
    val fusionRegistry = fusionController.registryForTools
    registry.register(SafeAgentTool(FusionListTool(fusionRegistry)))
    registry.register(SafeAgentTool(FusionInfoTool(fusionRegistry)))
    registry.register(
        SafeAgentTool(
            FusionActivateTool(
                registry = fusionRegistry,
                activator = fusionController.activator,
                activationSink = object : FusionActivationSink {
                    override fun onActivated(activation: com.apex.agent.core.tools.fusion.FusionActivation) =
                        fusionController.onModelActivation(activation)

                    override fun onDeactivated(bundleId: String) =
                        fusionController.onModelDeactivation()
                }
            )
        )
    )
    registry.register(
        SafeAgentTool(
            FusionCreateTool(
                registry = fusionRegistry,
                installedSkillIds = { skillRegistry.getInstalled().map { it.manifest.id }.toSet() },
                configuredMcpNames = { mcpManager.getConfigs().map { it.name }.toSet() },
                activeSkillIds = { activationStore.snapshot() }
            )
        )
    )
    registry.register(SafeAgentTool(FusionInstallMembersTool(fusionController.activator)))
}
