package com.apex.agent.core.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * LLM客户端工厂
 * 根据配置创建对应的客户端实例
 *
 * #259：重试已上移至 suspend 层（[SuspendHttpRetry]，由 StreamingOpenAiClient
 * 的 chat / chatStream 调用点接线）—— 旧 RetryInterceptor 在拦截器内
 * Thread.sleep 退避：不响应协程取消（用户「停止」在退避窗口内失效）且
 * 独占 OkHttp dispatcher 线程。callTimeout 保留为**单次请求**的硬上限；
 * 含重试的总预算护栏由 SuspendHttpRetry 的 withTimeoutOrNull 承担。
 *
 * #817：callTimeout 的真实语义是「call 创建 → 响应体关闭」的绝对墙钟
 *（覆盖响应体读取），此前注释「连接+请求头/体+响应头」的假设是错的 ——
 * 该值若作用于流式路径，SSE 流总时长被钉死在 requestTimeoutMs（默认 120s），
 * 推理型模型长输出必被强制掐断。现 callTimeout 仅对非流式 chat() 生效；
 * chatStream 在 [StreamingOpenAiClient.streamHttpClient] 派生
 * callTimeout=0 的专用 client（共享连接池），流式活性由 readTimeout
 * 逐包保障。
 */
object LlmClientFactory {

    /**
     * #835：连接池 / 调度器单源 —— 旧实现每次建 LlmClient（含每次切换
     * Profile）各造一套池；newBuilder 派生**共享** base 的连接池与调度器，
     * 逐客户端只覆写超时等自身配置（OkHttp newBuilder 语义）。
     */
    private val baseClient: OkHttpClient by lazy { OkHttpClient() }

    fun create(config: LlmConfig): LlmClient {
        val httpClient = baseClient.newBuilder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            // 单次请求的硬上限；跨重试的总预算见 SuspendHttpRetry
            //（withTimeoutOrNull(requestTimeoutMs)）。
            // #817：流式路径不受此约束 —— StreamingOpenAiClient 用
            // newBuilder().callTimeout(0) 派生专用 client（见其 KDoc）。
            .callTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        return StreamingOpenAiClient(
            config = config,
            httpClient = httpClient
        )
    }
}
