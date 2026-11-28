package com.apex.agent.core.tools

import okhttp3.OkHttpClient

/**
 * ═══ 工具层 OkHttp 连接池单源（#835）═══
 *
 * 旧实现六+ 处独立建造 OkHttpClient（WebTools / McpClient /
 * ConnectorMessenger / 应用侧各服务）：每处自带一套 ConnectionPool +
 * Dispatcher（默认各 5 空闲连接 + 64 并发上限的线程预算），同域请求分散
 * 在多个池里无法复用连接，空闲线程与连接资源随站点数线性放大。
 *
 * 本对象提供**工具层共享 base client**：各站点经 `newBuilder()` 派生
 * （共享 base 的连接池与调度器 —— OkHttp newBuilder 语义），逐站点只
 * 覆写超时 / 重定向等自身配置。超时语义**保持原值不变**。
 *
 * 纯 JVM（core:tool-registry 无 Android 依赖）；app DI 的
 * `provideToolHttpClient` 单例与 base 相互独立（DI 消费方不受影响）。
 */
object ToolsHttpPool {

    /** 工具层共享 base（连接池 + 调度器单源；首次触达即建）。 */
    val base: OkHttpClient by lazy { OkHttpClient() }
}
