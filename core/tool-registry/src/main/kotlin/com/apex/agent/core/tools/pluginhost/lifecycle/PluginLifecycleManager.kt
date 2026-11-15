package com.apex.agent.core.tools.pluginhost.lifecycle

import com.apex.agent.core.tools.pluginhost.PluginDescriptor
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import com.apex.agent.core.tools.pluginhost.PluginLifecycleState
import com.apex.agent.core.tools.pluginhost.bridge.PluginAgentTool
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # PluginLifecycleManager —— 统一生命周期驱动（spec §7）
 *
 * 状态机（14 态，[PluginLifecycleState.canTransitionTo] 防乱序）的唯一
 * 驱动者：Worker 启停（经 [PluginWorkerSupervisor]）、工具注册/注销
 * （经 [PluginToolBridge]）、事件订阅清理（经 [PluginEventBus]）三件事
 * 由本类按**正确顺序**编排 —— 顺序错误正是「卸载后残留工具/订阅」类
 * 事故的根源（spec 禁止事项 #10）。
 *
 * 启动序（spec §7.2）：检查启用 → spawn Worker → handshake → load →
 * 工具注册 → ACTIVE。
 *
 * 停止/卸载序（spec §7.3 逆序清理，一步不少）：
 * 1. 标记 STOPPING（拒绝新调用）→ 2. 注销工具 → 3. 取消事件订阅 →
 * 4. 停 Worker（Supervisor 内部取消在途请求）→ 5. STOPPED。
 * 卸载在停止之上加：账本移除（[ledgerRemover]，宿主注入 ExtensionRegistry
 * 的 uninstall —— 保持 core 不依赖账本实现细节）。
 */
class PluginLifecycleManager(
    private val supervisor: PluginWorkerSupervisor,
    private val toolBridge: PluginToolBridge,
    private val eventBus: PluginEventBus,
    /** 账本移除端口（宿主注入：ExtensionRegistry.uninstall 的薄包装）。 */
    private val ledgerRemover: suspend (pluginId: String) -> Unit
) {
    /** 插件运行状态快照（UI/诊断）。 */
    data class PluginStatus(
        val pluginId: String,
        val state: PluginLifecycleState,
        val ecosystem: PluginEcosystem?,
        val lastReason: String?
    )

    private val mutex = Mutex()
    private val states = ConcurrentHashMap<String, PluginLifecycleState>()
    private val ecosystems = ConcurrentHashMap<String, PluginEcosystem>()
    private val reasons = ConcurrentHashMap<String, String>()

    /** 状态流转（校验 + 记录原因；非法流转返回 false 不改状态）。 */
    private fun transition(pluginId: String, next: PluginLifecycleState, reason: String?): Boolean {
        val current = states[pluginId] ?: PluginLifecycleState.DISCOVERED
        if (!current.canTransitionTo(next)) {
            reasons[pluginId] = "非法状态流转 ${current.name} → ${next.name}（${reason ?: "无原因"}）"
            return false
        }
        states[pluginId] = next
        if (reason != null) reasons[pluginId] = reason
        return true
    }

    /**
     * 启动插件：Worker spawn + 握手 + 模块加载 + 工具注册，全程状态机
     * 看护。失败如实进 FAILED（带原因），不留半启动状态。
     */
    suspend fun start(
        descriptor: PluginDescriptor,
        workingDir: File,
        entryPath: String,
        format: String? = null
    ): Result<PluginStatus> = mutex.withLock {
        val id = descriptor.id
        ecosystems[id] = descriptor.ecosystem
        states.putIfAbsent(id, PluginLifecycleState.INSTALLED)
        // enable() 已置 PREPARED（重试/重新启用路径）：跳过自流转
        if (states[id] != PluginLifecycleState.PREPARED) {
            if (!transition(id, PluginLifecycleState.PREPARED, "启动请求")) {
                return@withLock Result.failure(IllegalStateException(reasons[id]))
            }
        }
        if (!transition(id, PluginLifecycleState.LOADING, "Worker 加载中")) {
            return@withLock Result.failure(IllegalStateException(reasons[id]))
        }
        val worker = supervisor.start(id, workingDir, entryPath, format).getOrElse { e ->
            transition(id, PluginLifecycleState.FAILED, "Worker 启动失败: ${e.message}")
            return@withLock Result.failure(e)
        }
        // Worker loaded 消息里的工具清单 → 一等工具注册
        val loaded = worker.load(entryPath, format).getOrElse { e ->
            transition(id, PluginLifecycleState.FAILED, "模块加载失败: ${e.message}")
            return@withLock Result.failure(e)
        }
        if (!loaded.setupOk) {
            transition(id, PluginLifecycleState.FAILED, "插件 setup 失败: ${loaded.error}")
            return@withLock Result.failure(IllegalStateException("插件 setup 失败: ${loaded.error}"))
        }
        val bindResult = toolBridge.bind(
            id, descriptor.ecosystem,
            loaded.tools.map {
                PluginAgentTool.ToolDescriptor(it.name, it.description, it.parametersSchema)
            },
            // 适配层在 lambda 内部消化 Result（Result 不跨 suspend 边界，
            // 双重装箱陷阱见 ToolExecutionOutcome KDoc）
            PluginAgentTool.PluginToolExecutor { pluginId, toolName, argsJson ->
                supervisor.callTool(pluginId, toolName, argsJson).fold(
                    onSuccess = { PluginAgentTool.ToolExecutionOutcome.success(it) },
                    onFailure = { PluginAgentTool.ToolExecutionOutcome.failure(it.message ?: "unknown") }
                )
            }
        )
        if (bindResult.rejected.isNotEmpty()) {
            reasons[id] = "工具注册部分被拒: " +
                bindResult.rejected.joinToString(", ") { it.toolName }
        }
        if (!transition(id, PluginLifecycleState.ACTIVE, "工具 ${bindResult.registered.size} 项已注册")) {
            return@withLock Result.failure(IllegalStateException(reasons[id]))
        }
        eventBus.dispatch(PluginEventBus.PluginHostEvent.PLUGIN_STARTED, """{"pluginId":"$id"}""")
        Result.success(statusOf(id))
    }

    /** 停止插件（spec §7.3 逆序清理；幂等）。 */
    suspend fun stop(pluginId: String): Result<PluginStatus> = mutex.withLock {
        val current = states[pluginId] ?: return@withLock Result.success(PluginStatus(pluginId, PluginLifecycleState.STOPPED, ecosystems[pluginId], reasons[pluginId]))
        if (current == PluginLifecycleState.STOPPED) {
            return@withLock Result.success(statusOf(pluginId))
        }
        if (!transition(pluginId, PluginLifecycleState.STOPPING, "停止请求")) {
            return@withLock Result.failure(IllegalStateException(reasons[pluginId]))
        }
        // 1) 注销工具（模型立即不可见，杜绝停机窗口内的新调用）
        val unbound = toolBridge.unbind(pluginId)
        // 2) 取消事件订阅
        eventBus.unsubscribeAll(pluginId)
        // 3) 停 Worker（在途请求由 Supervisor/Worker 失败收尾）
        supervisor.stop(pluginId)
        transition(pluginId, PluginLifecycleState.STOPPED, "已停止（工具注销 $unbound 项）")
        eventBus.dispatch(PluginEventBus.PluginHostEvent.PLUGIN_STOPPED, """{"pluginId":"$pluginId"}""")
        Result.success(statusOf(pluginId))
    }

    /** 禁用（停止 + DISABLED；重启需显式 enable）。 */
    suspend fun disable(pluginId: String): Result<PluginStatus> {
        stop(pluginId).getOrElse { return Result.failure(it) }
        val disableOk = mutex.withLock {
            transition(pluginId, PluginLifecycleState.DISABLED, "用户禁用")
        }
        if (!disableOk) {
            return Result.failure(IllegalStateException(reasons[pluginId]))
        }
        return Result.success(statusOf(pluginId))
    }

    /** 重新启用（DISABLED → PREPARED，等待下次 start）。 */
    suspend fun enable(pluginId: String): Result<PluginStatus> = mutex.withLock {
        if (!transition(pluginId, PluginLifecycleState.PREPARED, "用户启用")) {
            return@withLock Result.failure(IllegalStateException(reasons[pluginId]))
        }
        Result.success(statusOf(pluginId))
    }

    /** 卸载：停止 + 账本移除（保留/删除数据由账本实现决定，core 不感知）。 */
    suspend fun uninstall(pluginId: String): Result<PluginStatus> {
        stop(pluginId).getOrElse { return Result.failure(it) }
        ledgerRemover(pluginId)
        mutex.withLock {
            states.remove(pluginId)
            ecosystems.remove(pluginId)
            reasons.remove(pluginId)
        }
        return Result.success(
            PluginStatus(pluginId, PluginLifecycleState.STOPPED, null, "已卸载")
        )
    }

    /** Supervisor GaveUp 同步（崩溃恢复耗尽 → CRASHED）。 */
    fun onGaveUp(pluginId: String, reason: String) {
        if (states[pluginId] == PluginLifecycleState.ACTIVE ||
            states[pluginId] == PluginLifecycleState.LOADING
        ) {
            transition(pluginId, PluginLifecycleState.CRASHED, "重启预算耗尽: $reason")
            eventBus.dispatch(
                PluginEventBus.PluginHostEvent.PLUGIN_CRASHED,
                """{"pluginId":"$pluginId","reason":"$reason"}"""
            )
        }
    }

    /** 全量状态快照。 */
    fun statuses(): List<PluginStatus> =
        states.keys.map { statusOf(it) }.sortedBy { it.pluginId }

    /** 单插件状态。 */
    fun statusOf(pluginId: String): PluginStatus = PluginStatus(
        pluginId = pluginId,
        state = states[pluginId] ?: PluginLifecycleState.DISCOVERED,
        ecosystem = ecosystems[pluginId],
        lastReason = reasons[pluginId]
    )
}
