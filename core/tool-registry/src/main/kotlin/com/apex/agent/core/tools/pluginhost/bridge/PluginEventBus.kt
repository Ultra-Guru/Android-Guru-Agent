package com.apex.agent.core.tools.pluginhost.bridge

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * # PluginEventBus —— 统一插件事件总线（spec §13）
 *
 * Agent 生命周期事件 → 插件订阅方的异步分发中枢。设计要点（逐条对齐
 * spec §13 的「必须具备」清单）：
 *
 * - **异步分发**：dispatch 即返回，处理器在独立 scope 上跑 —— 插件处理
 *   慢/挂起永不阻塞 Agent 主循环（spec 禁止事项 #9）；
 * - **异常隔离**：处理器抛错只留日志（AppLogger），不传染其它订阅方、
 *   不上抛调用方；
 * - **超时**：单次处理器执行有界（[handlerTimeoutMs]），超时取消该处理
 *   任务但总线继续；
 * - **并发限制**：全局信号量（[maxConcurrentHandlers]）防事件风暴时
 *   插件侧被打爆；
 * - **订阅管理**：按 pluginId 分组，[unsubscribeAll] 供插件卸载全量清理
 *   （spec 禁止事项 #10：卸载后不留订阅）。
 *
 * 事件类型（[PluginHostEvent]）是**宿主侧规范名** —— 各生态适配器
 * （Phase 4 OpenCode / Phase 5 Claude Hooks）负责把生态事件映射到这组
 * 规范名；映射不存在的事件必须明确标记不支持，不伪造（spec §3.6）。
 */
class PluginEventBus(
    parentScope: CoroutineScope,
    private val handlerTimeoutMs: Long = 10_000,
    private val maxConcurrentHandlers: Int = 8
) {
    /** 宿主规范事件名（Agent 生命周期视角；生态映射表见各 Adapter）。 */
    enum class PluginHostEvent(val wireName: String) {
        SESSION_START("session.start"),
        SESSION_END("session.end"),
        USER_PROMPT_SUBMIT("user.prompt.submit"),
        BEFORE_TOOL_CALL("tool.before"),
        AFTER_TOOL_CALL("tool.after"),
        AFTER_TOOL_CALL_FAILURE("tool.after.failure"),
        NOTIFICATION("notification"),
        PLUGIN_STARTED("plugin.started"),
        PLUGIN_STOPPED("plugin.stopped"),
        PLUGIN_CRASHED("plugin.crashed")
    }

    /** 事件处理器（payload 为原始 JSON 文本，生态适配器负责解释）。 */
    fun interface Handler {
        suspend fun onEvent(event: PluginHostEvent, payloadJson: String)
    }

    private data class Subscription(
        val pluginId: String,
        val handler: Handler,
        val job: Job? = null
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val semaphore = Semaphore(maxConcurrentHandlers)
    private val mutex = Mutex()
    private val subscriptions = ConcurrentHashMap<PluginHostEvent, MutableList<Subscription>>()

    /** 订阅（同插件同事件重复订阅幂等去重）。 */
    suspend fun subscribe(pluginId: String, event: PluginHostEvent, handler: Handler) {
        mutex.withLock {
            val list = subscriptions.getOrPut(event) { mutableListOf() }
            list.removeAll { it.pluginId == pluginId }
            list += Subscription(pluginId, handler)
        }
    }

    /** 取消插件全部订阅（禁用/卸载路径）。 */
    suspend fun unsubscribeAll(pluginId: String): Int = mutex.withLock {
        var removed = 0
        subscriptions.values.forEach { list ->
            removed += list.count { it.pluginId == pluginId }
            list.removeAll { it.pluginId == pluginId }
        }
        subscriptions.entries.removeAll { it.value.isEmpty() }
        removed
    }

    /** 异步分发（立即返回；处理器异常/超时隔离，不影响调用方）。 */
    fun dispatch(event: PluginHostEvent, payloadJson: String = "{}") {
        val subs = subscriptions[event]?.toList() ?: return
        if (subs.isEmpty()) return
        subs.forEach { sub ->
            scope.launch {
                semaphore.acquire()
                try {
                    withTimeout(handlerTimeoutMs) {
                        runCatching { sub.handler.onEvent(event, payloadJson) }
                            .onFailure {
                                AppLogger.instance.warn(
                                    LogCategory.PLUGIN, TAG,
                                    "插件事件处理失败 plugin=${sub.pluginId} " +
                                        "event=${event.wireName}: ${it.message}"
                                )
                            }
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
    }

    /** 指定事件的活跃订阅数（诊断）。 */
    fun subscriberCount(event: PluginHostEvent): Int =
        subscriptions[event]?.size ?: 0

    /** 订阅了任意事件的插件 id 快照（诊断）。 */
    fun subscribedPluginIds(): Set<String> =
        subscriptions.values.flatten().map { it.pluginId }.toSet()

    /** 关停总线（宿主退出路径）。 */
    fun shutdown() {
        subscriptions.clear()
        scope.cancel()
    }

    private companion object {
        const val TAG = "PluginEventBus"
    }
}
