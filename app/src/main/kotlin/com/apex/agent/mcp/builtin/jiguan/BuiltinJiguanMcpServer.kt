package com.apex.agent.mcp.builtin.jiguan

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 内置机关（Jiguan）MCP 服务器的注册元数据（第八台 BUILTIN 进程内服务器）。
 *
 * 价值：把机关五域操控面（进程内存 / Java-ART / Native / 网络与系统）以
 * 标准 MCP 协议暴露 —— 手机作为 MCP Server，PC 端 / 本机 Agent 与 Coding
 * 两模式经 mcp__jiguan__* 一等工具驱动同一套 17 工具（与注册进 ToolRegistry
 * 的直连工具同源：同一个 JiguanRuntime，无平行实现）。
 *
 * [ID] 与 McpManager 配置名、builtinTransports 工厂 key、McpCall 的 server
 * 参数四处约定一致（模式同 BuiltinMemoryMcpServer）。
 */
object BuiltinJiguanMcpServer {

    /** 服务器名（= McpCall 的 server 参数 = 斜杠指令 /mcp:jiguan 的 id）。 */
    const val ID = "jiguan"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true,
        scope = "agent",
    )
}
