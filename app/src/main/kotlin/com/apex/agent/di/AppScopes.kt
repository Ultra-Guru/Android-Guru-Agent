package com.apex.agent.di

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * ═══ 应用层自建 scope 工厂（#834）═══
 *
 * 背景：全仓 37+ 处自建 `CoroutineScope(SupervisorJob() + …)` ——
 * **SupervisorJob 不吞 launch 未捕获异常**（只隔离子协程取消传播）：
 * scope 内任何裸 `launch` 抛出的异常会冒泡到线程默认
 * UncaughtExceptionHandler **直接杀死进程**（本仓自己的闪退事故形态，
 * ApexApp.appScope / CyberNeonBallManager / UpdateCenter 三处已各自补过
 * handler —— 其余站点零防护）。
 *
 * 本工厂统一补上 `CoroutineExceptionHandler`：未捕获异常**留痕不杀进程**
 * （AppLogger error + 站点名），与既有三处手工版同一语义。命名参数
 * （scope 名）进日志 source，事故可定位到具体站点。
 *
 * 替换口径（逐站点等价改造，只加 handler）：
 * - `com.apex.agent.di.AppScopes.io("AppScopes")` → [io]
 * - `com.apex.agent.di.AppScopes.default("AppScopes")` → [default]
 * - `com.apex.agent.di.AppScopes.mainImmediate("AppScopes")` → [mainImmediate]
 *
 * 已自带 handler 的三处（ApexApp / CyberNeonBallManager / UpdateCenter）
 * 保持手工版不动；Compose `rememberCoroutineScope()` 不在本口径内。
 */
object AppScopes {

    /** IO 域（磁盘 / 网络：日志落盘、插件装载、市场安装等长 IO）。 */
    fun io(name: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler(name))

    /** CPU 域（解析 / 派发等计算型后台）。 */
    fun default(name: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler(name))

    /** 主线程 immediate 域（UI 态订阅等）；异常同样留痕不杀进程。 */
    fun mainImmediate(name: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler(name))

    /** 未捕获异常统一留痕（AppLogger error + 站点名；进程存活）。 */
    private fun handler(name: String) = CoroutineExceptionHandler { _, throwable ->
        runCatching {
            AppLogger.instance.error(
                LogCategory.SYSTEM, "AppScopes",
                "后台协程未捕获异常（scope=$name，已留痕进程存活）: " +
                    "${throwable::class.simpleName}: ${throwable.message}",
                throwable
            )
        }
    }
}
