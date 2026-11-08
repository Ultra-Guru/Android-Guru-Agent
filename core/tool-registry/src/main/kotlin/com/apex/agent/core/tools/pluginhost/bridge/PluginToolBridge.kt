package com.apex.agent.core.tools.pluginhost.bridge

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DuplicateToolIdPolicy
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # PluginToolBridge —— 插件工具 → ToolRegistry 的注册/注销桥（spec §12）
 *
 * 职责（对齐 spec §12.3 工具生命周期）：
 * - **bind**：Worker 发现的工具批量注册为一等 [PluginAgentTool]；
 *   与**非本插件的既有工具**冲突时 REJECT（第三方数据永不覆盖内置/其它
 *   插件工具 —— 与 plugin-system-v2 的「数据永不 REPLACE」同纪律）；
 *   同插件重复 bind（Worker 重启重放）= 幂等 REPLACE；
 * - **unbind**：插件停止/禁用/卸载时全量注销 —— 失效工具绝不残留
 *   （spec 禁止事项 #10）；
 * - **追踪**：pluginId → 已注册 id 集合，供诊断与生命周期清理。
 *
 * 渐进披露（spec §12.2）：注册即入目录，ToolSearch/ToolOpen/请求预算的
 * 既有机制自动覆盖插件工具 —— 本桥不另建第二套目录体系。
 */
class PluginToolBridge(private val registry: ToolRegistry) {

    /** bind 结果（部分失败也如实报告 —— 全成功才静默）。 */
    data class BindResult(
        val registered: List<String>,
        val rejected: List<RejectedTool>
    ) {
        data class RejectedTool(val toolName: String, val id: String, val reason: String)
    }

    private val mutex = Mutex()
    private val bound = ConcurrentHashMap<String, MutableSet<String>>()

    /** 批量注册插件工具（Worker loaded 后调用）。 */
    suspend fun bind(
        pluginId: String,
        ecosystem: PluginEcosystem,
        tools: List<PluginAgentTool.ToolDescriptor>,
        executor: PluginAgentTool.PluginToolExecutor
    ): BindResult = mutex.withLock {
        val registered = mutableListOf<String>()
        val rejected = mutableListOf<BindResult.RejectedTool>()
        val mine = bound.getOrPut(pluginId) { ConcurrentHashMap.newKeySet() }
        for (tool in tools) {
            val agentTool = PluginAgentTool(pluginId, ecosystem, tool, executor)
            val existing = registry.getTool(agentTool.id)
            if (existing != null && existing !is PluginAgentTool) {
                // 与内置/其它来源工具撞名：拒绝（数据永不覆盖代码）
                rejected += BindResult.RejectedTool(
                    tool.name, agentTool.id,
                    "id 冲突：已存在非插件工具 '${agentTool.id}'"
                )
                continue
            }
            registry.register(agentTool, DuplicateToolIdPolicy.REPLACE)
            registered += agentTool.id
            mine += agentTool.id
        }
        BindResult(registered, rejected)
    }

    /** 注销插件全部工具（停止/禁用/卸载路径；幂等）。 */
    suspend fun unbind(pluginId: String): Int = mutex.withLock {
        val ids = bound.remove(pluginId) ?: return@withLock 0
        ids.forEach { registry.unregister(it) }
        ids.size
    }

    /** 已绑定的工具 id 快照（诊断/市场 UI）。 */
    fun boundToolIds(pluginId: String): Set<String> =
        bound[pluginId]?.toSet() ?: emptySet()

    /** 全部已绑定插件 id（生命周期管理器全量清理用）。 */
    fun boundPluginIds(): Set<String> = bound.keys.toSet()
}
