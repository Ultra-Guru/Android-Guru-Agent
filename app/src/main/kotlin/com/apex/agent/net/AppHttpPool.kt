package com.apex.agent.net

import okhttp3.OkHttpClient

/**
 * ═══ 应用层 OkHttp 连接池单源（#835）═══
 *
 * 旧实现应用侧多处独立建造 OkHttpClient（GithubApiService /
 * GithubTokenManager / SearxngHttpClient / UpdateHttp 等）：每处自带一套
 * ConnectionPool + Dispatcher，同域请求（api.github.com 等）分散在多个
 * 池里无法复用连接，空闲线程与连接资源随站点数线性放大。
 *
 * 本对象提供**应用层共享 base client**：各站点经 `newBuilder()` 派生
 * （共享 base 的连接池与调度器 —— OkHttp newBuilder 语义），逐站点只
 * 覆写超时 / 重试语义等自身配置（全部保持原值不变）。
 *
 * 与 DI 的 `provideToolHttpClient` 单例相互独立（该单例消费者带各自的
 * 注入路径，不受影响）；core 层站点见 core:tool-registry 的
 * ToolsHttpPool（纯 JVM，模块隔离）。
 */
object AppHttpPool {

    /** 应用层共享 base（连接池 + 调度器单源；首次触达即建）。 */
    val base: OkHttpClient by lazy { OkHttpClient() }
}
