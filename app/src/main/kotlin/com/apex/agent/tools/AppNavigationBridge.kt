package com.apex.agent.tools

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # 应用导航桥（工具 → UI 的真实导航通道）
 *
 * 用户诉求：「能帮我打开设置，这些类似能力不能用提示词注入，要给 agent
 * 工具」。工具运行在引擎协程里，拿不到 Compose 状态 —— 本桥是两者之间
 * 的投递箱：工具侧 [requestOpen] 入队请求，UI 侧（ApexRoot）收集并执行
 * 真实的返回栈压栈导航。
 *
 * 设计（StateFlow 而非 SharedFlow）：
 * - 工具执行时 UI 可能暂时不可达（如后台执行），SharedFlow 无订阅者即丢；
 *   StateFlow 持留最新未消费请求，UI 恢复组合后仍能补导航 —— 「打开了」
 *   的承诺不因时序落空；
 * - 每个请求带自增 [NavRequest.id]：同路由重复请求各自触发一次导航；
 * - 消费即清空（[consume]），null 值被收集方过滤，不产生重复导航。
 *
 * 路由白名单 [KNOWN_ROUTES] 与 DrawerDestination.route 镜像（工具入参
 * 校验之外的第二道防线 —— 未知路由直接拒绝，杜绝任意路由注入）。
 */
@Singleton
class AppNavigationBridge @Inject constructor() {

    /** 一次导航请求（id 自增去重；section 为设置页页签提示，仅展示用）。 */
    data class NavRequest(
        val id: Long,
        val route: String,
        val section: String? = null,
        val at: Long = System.currentTimeMillis()
    )

    private val _requests = MutableStateFlow<NavRequest?>(null)
    val requests: StateFlow<NavRequest?> = _requests.asStateFlow()

    private val counter = AtomicLong(0)

    /**
     * 请求打开应用页面。返回 true = 路由合法且已入队；false = 未知路由。
     * 线程安全（StateFlow.value 原子写；工具线程任意）。
     */
    fun requestOpen(route: String, section: String? = null): Boolean {
        if (route !in KNOWN_ROUTES) return false
        _requests.value = NavRequest(counter.incrementAndGet(), route, section)
        return true
    }

    /** UI 消费完当前请求后清空（收集方导航成功后调用）。 */
    fun consume() {
        _requests.value = null
    }

    companion object {
        /**
         * 路由白名单 —— 与 ui/DrawerDestination.route 一一镜像。
         * 刻意不含 agent（当前页）与 workspace-browser（Coding 上下文专属，
         * 需携带工作区参数，不适合工具盲开）。
         */
        val KNOWN_ROUTES: Set<String> = setOf(
            "settings", "diagnostics", "market", "terminal", "terminal-m3", "browser",
            "storage", "usage", "vault", "log", "tasks", "memory",
            "permissions", "templates", "about", "playground", "glasslab"
        )
    }
}
