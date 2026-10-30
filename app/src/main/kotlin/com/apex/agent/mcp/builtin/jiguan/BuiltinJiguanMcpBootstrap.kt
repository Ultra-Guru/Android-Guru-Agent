package com.apex.agent.mcp.builtin.jiguan

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置机关（Jiguan）MCP 的启动器：幂等预置配置 + 后台自动连接
 * （模式同 BuiltinMemoryMcpBootstrap —— 用户自建同名配置不被劫持，手动
 * 禁用后尊重偏好不再自动连接）。
 */
object BuiltinJiguanMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinJiguanMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinJiguanMcp",
                    "预置内置 Jiguan MCP 失败: ${it.message}",
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinJiguanMcpServer.ID && it.enabled }
            if (!enabled) return@launch // 用户明确禁用：尊重偏好，不自动连接
            manager.connect(BuiltinJiguanMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinJiguanMcp",
                    "连接内置 Jiguan MCP 失败: ${it.message}",
                )
            }
        }
    }
}
