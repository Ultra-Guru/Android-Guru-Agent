package com.apex.agent.core.llm

/**
 * T72 §十四 — 适配层异常类型化。
 *
 * 旧实现仅有一个 `class LlmException(message: String)`，上层只能靠字符串匹配
 * 区分"超时/限流/鉴权失败"——脆弱且不可靠。现在拆成 sealed 层级，运行时
 * [com.apex.agent.core.llm.runtime.ErrorClassifier] 据子类型精确映射到
 * [com.apex.agent.core.llm.runtime.ModelRuntimeException]。
 *
 * 向后兼容：仍是 [Exception] 子类，旧的 `catch (e: Exception)` / `catch (e: LlmException)`
 * 仍能捕获（sealed 基类即 [LlmException]）。
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** HTTP 非 2xx 响应。携带状态码 [code] 与响应体 [body]。 */
    class Http(val code: Int, val body: String) : LlmException("API error $code: $body")

    /** 响应体为空（非流式）。 */
    class EmptyResponse : LlmException("Empty response")

    /** 流式响应体为空。 */
    class EmptyBody : LlmException("Empty response body")

    /** The endpoint returned more than the bounded non-stream response limit. */
    class ResponseTooLarge(val limitBytes: Int) :
        LlmException("Response body exceeds the $limitBytes-byte limit")

    /** 网络层错误（连接失败 / DNS / SocketTimeout 等），包装原始 [IOException]。 */
    class Network(cause: java.io.IOException) : LlmException("Network error: ${cause.message}", cause)

    /** P2 fix：响应体非法（HTML 错误页/畸形 JSON/数值类型漂移），供 ErrorClassifier 映射到
     *  ModelResponseInvalid 而非落入未分类异常。 */
    class Parse(cause: Throwable? = null) : LlmException("Response parse error: ${cause?.message}", cause)

    /**
     * SPE-05 P0-3：输出被服务端截断（`finish_reason = length`——受
     * max_tokens / max_completion_tokens 上限裁断），流以显式错误收尾
     * （不再被当「正常收尾」把半截回答写进历史）。
     *
     * 携带 [partialChars]（已保留的正文长度），供上层 UI 显示
     * 「回答可能被截断 · 已保留 N 字」与续写/重发决策。
     * 不自动重放（#762/#765 禁区）——重发由用户手动决定。
     */
    class TruncatedStream(val partialChars: Int) :
        LlmException("回答可能被截断（finish_reason=length）：已保留 $partialChars 字")

    /**
     * SPE-08 P1-2：内容被服务端过滤拒发（`finish_reason = content_filter`）。
     * 携带已接收正文字符数；与截断/网络错误**独立类型独立文案**——
     * 「模型拒答这段内容」与「网断了」「输出上限」是三种不同的用户决策。
     */
    class ContentFiltered(val partialChars: Int) :
        LlmException("内容被服务端过滤（finish_reason=content_filter）：已保留 $partialChars 字")

    /**
     * SPE-08 P1-2 首 token 看门狗：帧在到但迟迟无任何内容帧（含思考/工具
     * 调用）——服务端有响应但不出 token，区别于网络不通。
     */
    class NoFirstToken(val elapsedMs: Long) :
        LlmException("首 token 超时（${elapsedMs}ms 无任何内容帧）")

    /**
     * SPE-08 P1-2 心跳停滞看门狗：连接只发注释/心跳行、迟迟无数据帧
     * （由 [SseFrameReader] 内联判定上抛——心跳会持续重置 readTimeout，
     * 该形态只能在读取器内部拦截）。
     */
    class StreamStalled(val gapMs: Long, val commentLines: Int) :
        LlmException("流停滞：${gapMs}ms 仅心跳无数据（$commentLines 条心跳行）")
}

