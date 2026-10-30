package com.apex.agent.di

import com.apex.agent.core.jiguan.JiguanOrchestrator
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.jiguan.tools.JiguanCallJavaMethodTool
import com.apex.agent.core.jiguan.tools.JiguanCapabilityReportTool
import com.apex.agent.core.jiguan.tools.JiguanDisassembleTool
import com.apex.agent.core.jiguan.tools.JiguanHardwareBreakpointTool
import com.apex.agent.core.jiguan.tools.JiguanHookJavaMethodTool
import com.apex.agent.core.jiguan.tools.JiguanHookNativeFuncTool
import com.apex.agent.core.jiguan.tools.JiguanJavaClassesTool
import com.apex.agent.core.jiguan.tools.JiguanKnowledgeQueryTool
import com.apex.agent.core.jiguan.tools.JiguanKnowledgeRememberTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryHexDumpTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryScanTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryWriteTool
import com.apex.agent.core.jiguan.tools.JiguanModulesTool
import com.apex.agent.core.jiguan.tools.JiguanPacketCaptureTool
import com.apex.agent.core.jiguan.tools.JiguanProcessesTool
import com.apex.agent.core.jiguan.tools.JiguanRecipesTool
import com.apex.agent.core.jiguan.tools.JiguanSslBypassTool
import com.apex.agent.core.jiguan.tools.JiguanSystemInspectTool
import com.apex.agent.core.jiguan.tools.JiguanUiTouchTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool

/**
 * §12 机关（Jiguan）全能操控域 17 工具的注册体 —— 自 ToolModule.kt 拆分
 * （主文件已 1179/1200 行预算；注册体拆分是本仓库既定模式，见
 * ToolModuleRegistrySections.kt / ToolModuleJvmToolsRegistration.kt 先例）。
 *
 * 五域全量：进程与内存（Processes/Scan/HexDump/Write）· Java/ART
 * （Classes/HookJava/CallJava）· Native（Modules/HookNative/Disassemble/
 * HardwareBreakpoint）· 网络（PacketCapture/SslBypass）· 系统与元能力
 * （UiTouch/SystemInspect/CapabilityReport/Recipes/Knowledge×2）。
 *
 * 全部工具自带结构化降级（通道缺席时输出指引文案而非异常），注册无需
 * 条件开关 —— 与 GitHub 工具族同款「无条件注册 + 运行时语义兜底」。
 */
internal fun registerJiguanTools(
    registry: DefaultToolRegistry,
    runtime: JiguanRuntime,
    orchestrator: JiguanOrchestrator,
) {
    // ═══ 12a. 进程与内存域 ═══
    registry.register(SafeAgentTool(JiguanProcessesTool(runtime)))
    registry.register(SafeAgentTool(JiguanMemoryScanTool(runtime)))
    registry.register(SafeAgentTool(JiguanMemoryHexDumpTool(runtime)))
    registry.register(SafeAgentTool(JiguanMemoryWriteTool(runtime)))
    // ═══ 12b. Java / ART 域 ═══
    registry.register(SafeAgentTool(JiguanJavaClassesTool(runtime)))
    registry.register(SafeAgentTool(JiguanHookJavaMethodTool(runtime)))
    registry.register(SafeAgentTool(JiguanCallJavaMethodTool(runtime)))
    // ═══ 12c. Native 域 ═══
    registry.register(SafeAgentTool(JiguanModulesTool(runtime)))
    registry.register(SafeAgentTool(JiguanHookNativeFuncTool(runtime)))
    registry.register(SafeAgentTool(JiguanDisassembleTool(runtime)))
    registry.register(SafeAgentTool(JiguanHardwareBreakpointTool(runtime)))
    // ═══ 12d. 网络域 ═══
    registry.register(SafeAgentTool(JiguanPacketCaptureTool(runtime)))
    registry.register(SafeAgentTool(JiguanSslBypassTool(runtime)))
    // ═══ 12e. 系统域 + 元能力（RAG / 配方 / 能力报告） ═══
    registry.register(SafeAgentTool(JiguanUiTouchTool(runtime)))
    registry.register(SafeAgentTool(JiguanSystemInspectTool(runtime)))
    registry.register(SafeAgentTool(JiguanCapabilityReportTool(orchestrator)))
    registry.register(SafeAgentTool(JiguanRecipesTool(orchestrator)))
    registry.register(SafeAgentTool(JiguanKnowledgeQueryTool(runtime)))
    registry.register(SafeAgentTool(JiguanKnowledgeRememberTool(runtime)))
}
