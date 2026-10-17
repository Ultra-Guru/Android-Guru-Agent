package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.fusion.FusionActivateTool
import com.apex.agent.core.tools.fusion.FusionInfoTool
import com.apex.agent.core.tools.fusion.FusionListTool
import com.apex.agent.fusion.FusionBundleController

/**
 * §15 fusion_* 工具族注册体 —— 自 ToolModule.kt 拆分（SRP 预算：主文件
 * 逼近 1200 行上限；模式同 ToolModuleRegistrySections / V5Registration 卫星）。
 *
 * 三个工具（FusionList / FusionInfo / FusionActivate）：模型可在对话中
 * 自主发现与激活复合技能包 —— 与 skill_* 族同构的渐进披露闭环。
 * 依赖单一 [FusionBundleController]（其内部聚合 registry + activator）。
 */
internal fun registerFusionBundleTools(
    registry: DefaultToolRegistry,
    fusionController: FusionBundleController
) {
    val fusionRegistry = fusionController.registryForTools
    registry.register(SafeAgentTool(FusionListTool(fusionRegistry)))
    registry.register(SafeAgentTool(FusionInfoTool(fusionRegistry)))
    registry.register(
        SafeAgentTool(FusionActivateTool(fusionRegistry, fusionController.activator))
    )
}
