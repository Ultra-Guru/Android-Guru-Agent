package com.apex.agent.di

import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.DeviceAuditQueryTool
import com.apex.agent.core.tools.builtin.DeviceCapabilityTool
import com.apex.agent.core.tools.builtin.DeviceOpsTool
import com.apex.agent.core.tools.device.CapabilityProbe
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.core.tools.device.IntentCatalog

/**
 * # Device Control Plane Tools — 注册段（S07）
 *
 * 自 [ToolModule.provideToolRegistry] 提取的 DCP 工具注册（3 个：
 * DeviceOps / DeviceCapability / DeviceAuditQuery），与既有
 * [ToolModuleDeviceRegistration] 同款 SRP 拆分模式 —— 主注册文件贴近
 * 1200 行预算（scripts/check_file_size.sh 强制），DCP 工具族边界天然清晰。
 *
 * - DeviceOps / DeviceCapability 进 CORE_TOOL_IDS（+2，ToolTierPolicy）；
 * - DeviceAuditQuery 留 CATALOG（ToolSearch 渐进披露，低频观测面）；
 * - 通道与执行面装配见 [DeviceControlModule]（feature flag 默认关：
 *   关闭时 DeviceOps 返回结构化 DCP_DISABLED，不静默降级旧链）。
 */
internal fun registerDeviceControlTools(
    registry: ToolRegistry,
    port: DeviceOpsPort,
    probe: CapabilityProbe,
    catalog: IntentCatalog,
    auditHost: DeviceAuditQueryTool.Host
) {
    registry.register(SafeAgentTool(DeviceOpsTool(port, catalog)))
    registry.register(SafeAgentTool(DeviceCapabilityTool(probe)))
    registry.register(SafeAgentTool(DeviceAuditQueryTool(auditHost)))
}
