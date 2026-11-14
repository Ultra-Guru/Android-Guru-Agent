package com.apex.agent.core.llm

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
/**
 * OpenAI兼容流式客户端
 * 支持任何 /v1/chat/completions 端点
 */
class StreamingOpenAiClient(
    private val config: LlmConfig,
    private val httpClient: OkHttpClient
) : LlmClient {

    /** 解析失败限次计数（防整页 HTML 错误片段刷屏日志；实例可能被并发流共享 → 原子）。 */
    private val parseFailuresLogged = java.util.concurrent.atomic.AtomicInteger()
    private val sanitizeFallbacksLogged = java.util.concurrent.atomic.AtomicInteger()

    /**
     * diag-202611（422 unknown variant 复盘）：server-side 搜索 tool 降级标记。
     * 置位后本实例所有请求不再注入非标准 tools[].type 搜索条目 —— 由
     * [executeWithServerToolDegrade] 在「未知 tools[].type」类请求级拒绝后
     * 置位（每实例一次，防重复烧请求）。
     */
    private val serverSearchToolDegraded = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 端点门控跳过留痕限次（首 3 次 + 之后每 50 条一条，与 #501 同模式）。 */
    private val serverSearchSkipsLogged = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse = executeWithServerToolDegrade(
        buildBody = { buildRequestBody(messages, tools, temperature, maxTokens, stream = false) },
        issueRequest = { body -> executeNonStream(buildRequest(body)) }
    )

    // ═══ Tool System v4 — per-request tool choice（强制函数调用）═══

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): LlmResponse = executeWithServerToolDegrade(
        buildBody = {
            buildRequestBody(
                messages, tools, temperature, maxTokens,
                stream = false, toolChoiceOverride = toolChoice
            )
        },
        issueRequest = { body -> executeNonStream(buildRequest(body)) }
    )

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> =
        chatStream(messages, tools, temperature, maxTokens, null)

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: ToolChoiceSpec?
    ): Flow<LlmStreamChunk> = flow {
        // diag-202611（422 unknown variant 复盘）：请求头阶段包一层
        // [executeWithServerToolDegrade] —— 端点以「未知 tools[].type」拒绝时
        // 剔除 server-side 搜索 tool 重发一次。失败发生在任何流片段发射前，
        // 不属于 #762/#765 禁区的流重放；成功获取响应后的读循环不变。
        val response = executeWithServerToolDegrade(
            buildBody = {
                buildRequestBody(
                    messages, tools, temperature, maxTokens,
                    stream = true, toolChoiceOverride = toolChoice
                )
            },
            issueRequest = { body -> acquireStreamResponse(buildRequest(body)) }
        )

        val responseBody = response.body ?: run {
            response.close()
            throw LlmException.EmptyBody()
        }
        val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))

        // 修复取消传播：当收集者取消 flow 时，把 OkHttp 响应体关闭，
        // 这样 readLine() 会立即抛出 IOException 而不是阻塞到 readTimeoutMs
        // （旧实现等 await() 返回后便不再监听取消，取消后还要阻塞最多 120s）。
        val cancelHandle = coroutineContext[Job]
            ?.invokeOnCompletion { runCatching { response.close() } }

        try {
            // 末帧兜底（v1.5.x 修复）：部分网关（智谱等）把完整正文挂在最后一帧
            // 的 choice.message 上（增量 delta 缺失/为空）—— 记录本流是否见过
            // 增量正文，parseStreamChunk 据此决定是否用 message.content 兜底
            //（见过则不用，防正文重复一遍）。
            var sawDeltaContent = false
            // 截断守卫（#762/#765）：本流是否见过任何服务端完成标记 ——
            // `[DONE]` 帧或携带 finish_reason 的帧（stop / tool_calls / length /
            // content_filter … 均算正常收尾；兼容发完 finish_reason 帧就断流、
            // 不补 [DONE] 的网关）。
            var sawFinish = false
            // #765：SSE 逐行读取是阻塞 IO，但本 flow 不再用 flowOn(Dispatchers.IO)
            // 整体搬运 —— flowOn 会在 emit 与收集者之间引入 ChannelFlow 中转，
            // 上游「发完最后一块后立刻抛异常」（断线 / 截断守卫）时，异常投递
            // 可能跑赢缓冲里的部分输出，把它整个丢掉 —— 恰好违背本 PR
            // 「部分输出留存」的目标（复现：runTest 下约 1/3 概率丢块）。
            // 改为仅把阻塞读取包进 withContext(IO)：发射是收集者上下文里的
            // 直接挂起调用，「先收到部分输出、后收到异常」的顺序有了硬保证。
            while (true) {
                // 每行检查一次取消状态（readLine 阻塞期间靠上面的 invokeOnCompletion 兜底）
                coroutineContext.ensureActive()
                val currentLine = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                // 兼容 OpenAI SSE 规范允许的两种前缀：`data:` 与 `data: `。
                // 旧实现只认 `data: `（带一个空格），部分代理发 `data:{...}` 会被静默丢弃。
                if (!currentLine.startsWith("data:")) continue

                val data = currentLine.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    sawFinish = true
                    emit(LlmStreamChunk(isFinish = true))
                    break
                }

                parseStreamChunk(data, sawDeltaContent)?.let {
                    if (!it.content.isNullOrEmpty()) sawDeltaContent = true
                    if (it.isFinish || it.finishReason != null) sawFinish = true
                    emit(it)
                }
            }
            // 截断守卫：EOF 前未见任何完成标记 —— 这不是一份完整回答。已发射
            // 的部分输出不吞块（收集者已拿到、对话里原样保留），但流必须以异常
            // 收尾，防止上层把残段当完整结果写进历史 / 触发后续工具轮次。
            // 不自动重放（见上方请求阶段注释）；弱网下是否重发由用户手动决定。
            if (!sawFinish) {
                throw IOException(
                    "SSE 流意外终止：未见完成标记（[DONE]/finish_reason），" +
                        "输出可能被截断——已收到的部分内容已保留"
                )
            }
        } finally {
            cancelHandle?.dispose()
            // 关闭是阻塞 IO：即便协程已被取消也要完成（NonCancellable），
            // 且 runCatching 防止 close 自身的异常掩盖真正的失败原因。
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { reader.close() }
                runCatching { response.close() }
            }
        }
    }

    // ═══ 内部方法 ═══
    
    private fun buildRequest(body: JsonObject): Request {
        // 完全自定义端点（用户反馈）：baseUrl 已含 /chat/completions（或旧版
        // /completions）时原样直发，不再双拼；根路径 / /v1 等照旧追加。
        // 归一化口径见 [EndpointResolver.chatCompletions]（设置页预览同源）。
        val builder = Request.Builder()
            .url(EndpointResolver.chatCompletions(config.baseUrl))
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        
        // API Key（某些API用Bearer，某些用自定义header）
        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        
        // 自定义headers
        config.customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }
        
        return builder.build()
    }
    
    /**
     * 构造 /chat/completions 请求体。
     *
     * 可见性 internal（非 private）：同模块单测直接断言请求体字段
     * （哨兵回退 / o-series 兼容 / Provider 差异化思考字段），免起 MockWebServer。
     */
    internal fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        stream: Boolean,
        toolChoiceOverride: ToolChoiceSpec? = null
    ): JsonObject {
        // ── B1/B2：采样参数哨兵回退（显式传参优先，否则用 Profile 值）──────────
        // temperature/maxTokens 是方法入参，旧实现直接写入请求体——引擎每处调用
        // 都显式传 AgentConfig 快照（0.7f/4096），Profile（设置页/小大脑菜单）的
        // temperature / maxOutputTokens 被永久覆盖（参数死链）。现在：
        //   effectiveTemperature = 调用方显式传值（>=0） ?: config.temperature
        //   requestedTokens      = 调用方显式传值（>0） ?: config.maxTokens ?: 4096（旧行为兜底）
        val effectiveTemperature = if (temperature >= 0f) temperature else config.temperature
        val requestedTokens = when {
            maxTokens > 0 -> maxTokens
            config.maxTokens > 0 -> config.maxTokens
            else -> 4096
        }

        // 客户侧预校验 maxTokens：超出 contextWindow - reservedOutputTokens 的请求
        // 会被服务端以模糊的 HTTP 400 拒绝，用户难以定位。这里提前裁减并保留安全余量。
        val effectiveMaxTokens = if (config.contextWindow > 0) {
            val cap = (config.contextWindow - config.reservedOutputTokens).coerceAtLeast(256)
            requestedTokens.coerceAtMost(cap)
        } else {
            requestedTokens
        }

        // ── B3/T4：OpenAI 官方严格推理模型识别（o1/o3/o4-mini/gpt-5）──────────
        // 这些模型的 API 硬性拒绝 temperature 与 max_tokens（400
        // "Unsupported parameter"）；正则口径统一收口至
        // [EndpointResolver.isStrictOpenAiReasoner]（Coding 屏思考档位生效
        // 提示与其共用同一份判定，防两处漂移）。
        val isStrictOpenAiReasoner = EndpointResolver.isStrictOpenAiReasoner(config.model)
        // T4：OpenAI 官方端点 + 推理模型 → max_tokens 必须换成 max_completion_tokens。
        // 官方端点判定 = baseUrl 含 "api.openai.com"（第三方 OpenAI 兼容网关大多
        // 仍接受 max_tokens，不误伤）。
        val requiresMaxCompletionTokens = config.baseUrl.contains("api.openai.com", ignoreCase = true) &&
            (config.capabilities.reasoning || isStrictOpenAiReasoner)

        // P1-3 修复：max_tokens 与 max_completion_tokens 互斥。旧实现两者同时发送
        //（thinkingBudget 非空或 ReasoningEffort.MAX 时），OpenAI 端点对同时携带
        // 两个参数的请求直接 400（"Unsupported parameter: 'max_tokens' is not
        // supported with this model"）。现在发送 max_completion_tokens 时不再发
        // max_tokens；未设置思维预算时才发 max_tokens。
        // 思维预算与 reasoning_effort 不强行绑定：显式设置时以此为准。
        val maxCompletion = config.thinkingBudget ?: run {
            if (config.reasoningEffort == ReasoningEffort.MAX &&
                (config.capabilities.reasoning || isStrictOpenAiReasoner)
            ) maxOf(requestedTokens, 8192) else null
        }

        // ── 原生联网搜索（Provider 分支）────────────────────────────
        // 为什么在 buildJsonObject 之前算：搜索参数分两处注入请求体——
        // ① OPENAI / DASHSCOPE / OPENROUTER 用各自的独立顶层字段（下方 when 注入）；
        // ② ZHIPU / DEEPSEEK / ANTHROPIC 的搜索开关是 server-side tool，必须写进
        //    tools 数组 —— 而 kotlinx.serialization 的 JsonArray 构建后不可变，
        //    无法事后追加，只能在构建期与 function tools 一并写入。
        // OFF（默认）时 searchToolEntries 为空且下方 when 走 else —— 请求体
        // 与旧版完全一致，兼容所有端点。
        //
        // diag-202611（422 unknown variant 复盘）：注入逻辑收敛至
        // [buildSearchToolEntries] —— ①端点能力门控：显式选择的 server tool
        // 模式与真实端点不匹配（如 ZHIPU 形态 + 通用 OpenAI 兼容网关）时不再
        // 注入；旧实现照发非标准 tools[].type，会让整请求在 Rust serde 风格
        // 端点上 422（unknown variant `WebSearch`, expected `function`）。
        // ②自愈降级：端点曾以该形态拒绝后，本实例后续请求一律不再注入。
        // searchMode 同时供下方「独立顶层字段形态」 when 使用（单次解析双用）。
        val searchMode = config.webSearch.resolveFor(config.baseUrl, config.providerId)
        val searchToolEntries = buildSearchToolEntries(searchMode)

        return buildJsonObject {
            put("model", config.model)
            // B3 修复：仅 OpenAI o-series / gpt-5（API 硬性拒绝）不发 temperature，
            // 让服务端用模型默认值；其他 reasoning 模型（DeepSeek-R1 / QwQ /
            // Qwen3-thinking / GLM-Z1 等）的兼容端**接受** temperature，照常发送
            // ——旧实现只要 capabilities.reasoning=true 就静默丢弃该参数，
            // 用户在设置页调温对这些模型完全无效。
            if (!isStrictOpenAiReasoner) {
                put("temperature", effectiveTemperature)
            }
            if (maxCompletion != null || requiresMaxCompletionTokens) {
                put("max_completion_tokens", maxCompletion ?: effectiveMaxTokens)
            } else {
                put("max_tokens", effectiveMaxTokens)
            }
            put("stream", stream)
            // ── 真实用量统计（stream_options.include_usage）──
            // OpenAI 协议的流式响应默认**不携带** usage —— 不发这个开关，
            // 顶部上下文仪表盘的「已用 Token」就永远是启发式估算值，用户会质疑
            // 统计是假的。开启后服务端在流尾追加一帧 choices 为空 + usage 完整的
            // 统计帧（OpenAI/vLLM/DeepSeek/OneAPI 等均已支持；对不认识该字段的
            // 旧网关，OpenAI 协议要求未知字段应被忽略，风险可控）。
            // 注意：统计帧 choices 为空 —— parseStreamChunk 对空 choices 原本直接
            // 返回 null 丢弃，改为先提取 usage 再返回（见下方）。
            if (stream) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }

            // ── Sampling 参数（完整开放）─────────────────────────
            if (config.topP != 1.0f) put("top_p", config.topP)
            // T72 §二十二：top_k / min_p / repetition_penalty 为非标准参数，
            // OpenAI/Anthropic 端点会 400 拒绝。仅在已知接受这些参数的 provider
            // 上发送（本地推理 / 自定义兼容端点）。
            val acceptsLocalSampling = config.providerId in LOCAL_SAMPLING_PROVIDERS
            if (config.topK != 0 && acceptsLocalSampling) put("top_k", config.topK)
            if (config.minP != 0.0f && acceptsLocalSampling) put("min_p", config.minP)
            if (config.presencePenalty != 0.0f) put("presence_penalty", config.presencePenalty)
            if (config.frequencyPenalty != 0.0f) put("frequency_penalty", config.frequencyPenalty)
            if (config.repetitionPenalty != 1.0f && acceptsLocalSampling) put("repetition_penalty", config.repetitionPenalty)
            config.seed?.let { put("seed", it) }
            if (config.stopSequences.isNotEmpty()) {
                putJsonArray("stop") { config.stopSequences.forEach { s -> add(s) } }
            }

            // ── Reasoning（原生思考强度 + 思维预算，T1/T3 修复）──────────
            // Provider 差异化思考字段（思考深度档位 → 各家 API 的真实参数）：
            //  - Anthropic（Claude 3.7+/4，OpenAI 兼容层）：thinking: {type:"enabled", budget_tokens:N}
            //  - Qwen / DashScope 兼容端：enable_thinking: true/false
            //  - 其他 OpenAI 兼容端：reasoning_effort（模型声明 reasoning 能力时）
            val effort = config.reasoningEffort
            val isAnthropicEndpoint = config.providerId.equals("anthropic", ignoreCase = true) ||
                config.baseUrl.contains("anthropic", ignoreCase = true)
            val isQwenEndpoint = config.baseUrl.contains("dashscope", ignoreCase = true) ||
                config.model.startsWith("qwen", ignoreCase = true)
            when {
                isAnthropicEndpoint -> {
                    // effort 为 NONE（null）时不发 thinking 字段（Claude 默认不思考）。
                    // budget 优先级：thinkingBudget（>0）> effort 档位映射 > 4096。
                    if (effort != ReasoningEffort.NONE) {
                        val budget = config.thinkingBudget?.takeIf { it > 0 }
                            ?: EFFORT_THINKING_BUDGETS[effort]
                            ?: 4096
                        putJsonObject("thinking") {
                            put("type", "enabled")
                            put("budget_tokens", budget)
                        }
                    }
                }
                isQwenEndpoint -> {
                    // Qwen3 混合思考开关：effort NONE = 关闭思考，其余档位 = 开启。
                    put("enable_thinking", effort != ReasoningEffort.NONE)
                }
                config.capabilities.reasoning || isStrictOpenAiReasoner -> {
                    // T72 §二十二修复：仅当模型声明 reasoning 能力时才发送
                    // reasoning_effort。旧实现默认 MEDIUM → 对所有端点（含非推理
                    // 模型）发 "reasoning_effort":"medium"，部分服务端会 400。
                    // o-series 正则兜底：存量 Profile 未勾 reasoning 位也能发出。
                    effort.apiValue?.let { value ->
                        put("reasoning_effort", value)
                    }
                }
            }
            // max_completion_tokens 已在上方请求体开头写入（P1-3：与 max_tokens 互斥）

            // ── Structured Output ───────────────────────────────
            // T72 §二十二修复：仅当模型声明 structuredOutput 能力时才发送
            // response_format；schema 使用 config.jsonSchema（非空时 parse，
            // 为空回退 {"type":"object"}，兼容旧行为）。
            when (config.structuredOutputMode) {
                StructuredOutputMode.TEXT -> Unit
                StructuredOutputMode.JSON -> {
                    if (config.capabilities.jsonMode || config.capabilities.structuredOutput) {
                        putJsonObject("response_format") {
                            put("type", "json_object")
                        }
                    }
                }
                StructuredOutputMode.JSON_SCHEMA -> {
                    if (config.capabilities.structuredOutput) {
                        putJsonObject("response_format") {
                            put("type", "json_schema")
                            put("strict", config.structuredOutputStrict)
                            putJsonObject("json_schema") {
                                put("name", "structured_output")
                                val schemaEl = config.jsonSchema
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                                if (schemaEl != null) put("schema", schemaEl)
                                else putJsonObject("schema") { put("type", "object") }
                            }
                        }
                    }
                }
            }

            putJsonArray("messages") {
                for (msg in messages) {
                    addJsonObject {
                        when (msg) {
                            is LlmMessage.System -> {
                                put("role", "system")
                                put("content", msg.content)
                            }
                            is LlmMessage.User -> {
                                put("role", "user")
                                if (msg.images.isEmpty()) {
                                    put("content", msg.content)
                                } else {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        }
                                        msg.images.forEach { img ->
                                            addJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    // URL 直传优先（ImageContent.url）；
                                                    // 否则 base64 内联。旧实现只支持 base64，
                                                    // 已有远程图 URL 的场景被迫重编码。
                                                    put(
                                                        "url",
                                                        img.url?.takeIf { it.isNotBlank() }
                                                            ?: "data:${img.mimeType};base64,${img.base64Data}"
                                                    )
                                                    put("detail", img.detail)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is LlmMessage.Assistant -> {
                                put("role", "assistant")
                                if (msg.content.isNotBlank()) {
                                    put("content", msg.content)
                                } else {
                                    put("content", JsonNull)
                                }
                                if (msg.toolCalls.isNotEmpty()) {
                                    putJsonArray("tool_calls") {
                                        for (tc in msg.toolCalls) {
                                            addJsonObject {
                                                put("id", tc.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", tc.name)
                                                    put("arguments", tc.arguments)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is LlmMessage.ToolResult -> {
                                put("role", "tool")
                                put("tool_call_id", msg.toolCallId)
                                put("content", msg.content)
                            }
                        }
                    }
                }
            }
            
            // ── Tools（function tools + 原生搜索 server tools）────────
            // 旧逻辑：enableTools 且 tools 非空才发送 tools 数组。扩展：ZHIPU /
            // DEEPSEEK / ANTHROPIC 的搜索开关就住在 tools 里，即使没有 function
            // tools 也必须创建数组。OFF（默认）时 searchToolEntries 为空，
            // 条件退化为旧版 `config.enableTools && tools.isNotEmpty()` —— 请求体
            // 与旧版逐字节一致。
            val hasFunctionTools = config.enableTools && tools.isNotEmpty()
            if (hasFunctionTools || searchToolEntries.isNotEmpty()) {
                putJsonArray("tools") {
                    if (hasFunctionTools) {
                        for (tool in tools) {
                            addJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    // v4：请求侧描述限额——超大描述直接截断，避免单个
                                    // 工具（含全量文档式的 description）拖爆请求体。
                                    put("description", clampRequestDescription(tool.description))
                                    // P0 防爆：单个工具的 parameters 非法 JSON 时，旧实现
                                    // 直接抛异常 → 所有带工具的请求整体失败（一个坏
                                    // schema 拖死全部 ~113 个工具）。现在降级为空对象
                                    // schema（模型仍可调用，参数不校验），并保留该工具。
                                    // v4 增强：Gemini OpenAI 兼容层拒绝 enum/format/
                                    // additionalProperties 等关键字——按 baseUrl 命中
                                    // gemini 端点时递归剔除（rikkahub 同款策略）。
                                    put(
                                        "parameters",
                                        sanitizeParameters(tool.parameters)
                                    )
                                }
                            }
                        }
                    }
                    // server-side 搜索 tool 追加在 function tools 之后
                    // （JsonArray 不可变，构建期一并写入 —— 见上方说明）。
                    searchToolEntries.forEach { add(it) }
                }
                // tool_choice / parallel_tool_calls 仅在存在 function tools 时发送：
                // server-side 搜索 tool 由服务端自行调度、不参与 tool_choice 语义，
                // 只带搜索 tool 却发 tool_choice 的请求在部分端点会 400。
                if (hasFunctionTools) {
                    // v4：toolChoiceOverride（强制函数调用）优先；否则回退 Profile 值。
                    // 注意：put() 返回“旧值”（首次写入恒 null），不能用 elvis——
                    // 否则 override 分支写入后还会被 Profile 分支覆盖（实测教训）。
                    // Function 形态：{"type":"function","function":{"name":…}} ——
                    // 必须指向本请求 tools 里存在的名字，由引擎层保证（只暴露被选中的工具）。
                    val spec = toolChoiceOverride
                    if (spec != null) {
                        put(
                            "tool_choice",
                            when (spec) {
                                ToolChoiceSpec.Auto -> JsonPrimitive("auto")
                                ToolChoiceSpec.Required -> JsonPrimitive("required")
                                ToolChoiceSpec.None -> JsonPrimitive("none")
                                is ToolChoiceSpec.Function -> buildJsonObject {
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", spec.name)
                                    }
                                }
                            }
                        )
                    } else {
                        put(
                            "tool_choice",
                            when (config.toolChoice) {
                                ToolChoiceMode.AUTO -> "auto"
                                ToolChoiceMode.REQUIRED -> "required"
                                ToolChoiceMode.NONE -> "none"
                            }
                        )
                    }
                    if (config.parallelToolCalls) {
                        put("parallel_tool_calls", true)
                    } else {
                        // T72 §二十二修复：旧实现 false 时直接省略键，导致用户无法
                        // 关闭并行工具调用（服务端默认 true）。现在显式发送 false。
                        put("parallel_tool_calls", false)
                    }
                }
            }

            // ── 原生联网搜索（独立顶层字段形态）─────────────────────
            // OPENAI / DASHSCOPE / OPENROUTER 不走 tools 数组，用各自的专有顶层
            // 字段开关；ZHIPU / DEEPSEEK / ANTHROPIC 的 server tool 已并入上方
            // tools 数组（else 分支覆盖）。when 单分支命中，每个键至多写一次，
            // 不存在重复键问题。OFF（默认）时不写任何键 —— 未知/严格端点
            // 看不到非标准字段（防 400）。
            when (searchMode) {
                WebSearchMode.OPENAI ->
                    // OpenAI gpt-4o-search-preview / gpt-4o-mini-search-preview 的
                    // 原生开关（Chat Completions 的 web_search_options）；
                    // search_context_size 控制抓取量，medium 为官方推荐平衡档。
                    putJsonObject("web_search_options") {
                        put("search_context_size", "medium")
                    }
                WebSearchMode.DASHSCOPE ->
                    // 阿里百炼 compatible-mode 的 Qwen 联网开关：顶层布尔字段。
                    put("enable_search", true)
                WebSearchMode.OPENROUTER ->
                    // OpenRouter 原生 web 插件（等价给模型名加 :online 后缀）。
                    // 它同时兼容 OpenAI 的 web_search_options，但官方文档推荐的
                    // plugins 形态在旧网关上兼容面更广，故采用 plugins。
                    putJsonArray("plugins") {
                        addJsonObject {
                            put("id", "web")
                            put("max_results", 5)
                        }
                    }
                else -> Unit
            }

            // ── 多模态输出（图片生成）─────────────────────────────
            // OpenRouter 等网关要求图像输出模型显式声明 modalities，否则不返回
            // 图片 part。仅在 Profile 声明 imageGeneration 能力时发送——非生图
            // 端点不会看到这个非标准字段（避免严格端点 400）。
            if (config.capabilities.imageGeneration) {
                putJsonArray("modalities") {
                    add("text")
                    add("image")
                }
            }
        }
    }
    
    // ═══ diag-202611：WebSearch server tool 注入（端点门控 + 422 自愈降级）═══

    /**
     * 构建注入 tools 数组的 server-side 搜索 tool 条目（ZHIPU / DEEPSEEK /
     * ANTHROPIC 形态）。三种情况返回空列表（请求体与旧版 OFF 行为一致，
     * 兼容所有端点）：
     * 1. 解析后非 server-tool 模式（OFF / AUTO 推断不出 / 顶层字段形态）；
     * 2. 端点能力门控不匹配（[WebSearchMode.serverToolSupportedBy]）；
     * 3. 本实例已因「未知 tools[].type」拒绝降级（[serverSearchToolDegraded]）。
     */
    private fun buildSearchToolEntries(searchMode: WebSearchMode): List<JsonObject> {
        if (serverSearchToolDegraded.get()) return emptyList()
        if (!searchMode.serverToolSupportedBy(config.baseUrl, config.providerId)) {
            logServerSearchToolSkipped(searchMode)
            return emptyList()
        }
        return when (searchMode) {
            WebSearchMode.ZHIPU -> listOf(
                // 智谱 GLM：WebSearch 作为 server-side tool 挂进 tools 数组；
                // search_result=true 让响应携带 message.search_result[] 引用列表。
                buildJsonObject {
                    put("type", "WebSearch")
                    putJsonObject("WebSearch") {
                        put("enable", true)
                        put("search_result", true)
                    }
                }
            )
            WebSearchMode.DEEPSEEK -> listOf(
                // DeepSeek Responses 兼容端点：接受 {"type":"WebSearch"}
                // （与 GLM 同为 server-side tool 形态，但无嵌套配置）。
                buildJsonObject { put("type", "WebSearch") }
            )
            WebSearchMode.ANTHROPIC -> listOf(
                // Anthropic 原生 server tool（经 OpenAI 兼容代理透传时保持同形）。
                buildJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "WebSearch")
                    put("max_uses", 3)
                }
            )
            else -> emptyList()
        }
    }

    /** 端点门控跳过留痕（限次：首 3 次 + 之后每 50 条一条，与 #501 同模式）。 */
    private fun logServerSearchToolSkipped(mode: WebSearchMode) {
        val skippedNo = serverSearchSkipsLogged.incrementAndGet()
        if (skippedNo <= 3 || skippedNo % 50 == 0) {
            AppLogger.instance.warn(
                LogCategory.LLM, "StreamingOpenAiClient",
                "webSearch mode=$mode ignored at ${config.baseUrl}: endpoint does not " +
                    "match that provider — non-standard tools[].type would be rejected " +
                    "(422 unknown variant) by strict OpenAI-compatible gateways " +
                    "(skip #$skippedNo)"
            )
        }
    }

    /**
     * 「未知 tools[].type」类请求级拒绝的一次性降级重发（每客户端实例一次）。
     *
     * 触发：[LlmException.Http]（400/422）且响应体命中 [ToolTypeRejection]
     *（Rust serde "unknown variant" / Pydantic "Input should be 'function'" 等）。
     * 动作：置位 [serverSearchToolDegraded] → [buildBody] 重建的请求体自动剔除
     * server-side 搜索 tool → 重发一次。重发仍失败则原样上抛（诚实暴露，
     * 不吞错）；非该形态的 4xx 不重试 —— 重发同一请求体只会重复失败
     *（与 [SuspendHttpRetry]「4xx 不重试」语义一致，不烧预算）。
     *
     * 安全性：失败发生在响应头阶段，尚未发射任何流片段 / 产生任何工具副作用，
     * 不属于 #762/#765 禁区的流重放。
     */
    private suspend fun <T> executeWithServerToolDegrade(
        buildBody: () -> JsonObject,
        issueRequest: suspend (JsonObject) -> T
    ): T {
        try {
            return issueRequest(buildBody())
        } catch (e: LlmException.Http) {
            if ((e.code != 400 && e.code != 422) ||
                !ToolTypeRejection.isUnknownToolTypeRejection(e.body)
            ) throw e
            if (!serverSearchToolDegraded.compareAndSet(false, true)) throw e
            AppLogger.instance.warn(
                LogCategory.LLM, "StreamingOpenAiClient",
                "Endpoint rejected non-standard tools[].type (HTTP ${e.code}): " +
                    "${e.body.take(200)} — stripping server-side web-search tools " +
                    "and retrying once"
            )
            return issueRequest(buildBody())
        }
    }

    /**
     * 非流式请求执行（chat 两个重载共用）。
     * #259：重试在 suspend 层（可取消的 delay 退避，总预算见 [SuspendHttpRetry]）。
     */
    private suspend fun executeNonStream(request: Request): LlmResponse =
        SuspendHttpRetry.execute(config) { httpClient.newCall(request).await() }.use { response ->
            if (!response.isSuccessful) {
                throw LlmException.Http(response.code, response.errorBodyHead())
            }
            val body = response.body ?: throw LlmException.EmptyResponse()
            val bytes = body.byteStream().use { stream ->
                BoundedReads.readAtMost(stream, MAX_NON_STREAM_RESPONSE_BYTES)
            } ?: throw LlmException.ResponseTooLarge(MAX_NON_STREAM_RESPONSE_BYTES)
            parseNonStreamResponse(bytes.toString(Charsets.UTF_8))
        }

    /**
     * 流式响应获取（chatStream 头阶段）。
     *
     * #762 第 4 项安全切片（#765）：OpenAI Chat Completions 的 POST 流式
     * **没有**可依赖的稳定事件 ID / 断点续传 —— 断线（含响应头返回前的
     * IOException）与可重试状态码（503 等）一律直接上抛，**绝不自动重放**。
     * 重放 = 同一请求计两次费 + 已执行的工具副作用再执行一遍；且旧路径经
     * SuspendHttpRetry 重试时复用同一 Call，会触发 OkHttp
     * IllegalStateException("Already Executed")。是否重发由用户在 UI 手动决定
     *（本方法只在响应头阶段抛错；「未知 tools[].type」降级重发见
     * [executeWithServerToolDegrade]，其重发请求体与首发不同，非重放）。
     */
    private suspend fun acquireStreamResponse(request: Request): Response {
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            // 错误体读取是阻塞 IO —— 包在 IO 调度器（本 flow 不再整体 flowOn，见下方读循环注释）
            val errorBody = withContext(Dispatchers.IO) {
                response.body?.string() ?: "Unknown error"
            }
            response.close()
            throw LlmException.Http(response.code, errorBody)
        }
        return response
    }

    // ═══ Tool System v4 — 请求侧工具描述/Schema 硬化 ═══

    /** 单工具描述在请求里的硬上限（v4：防"文档式 description"拖爆请求体）。 */
    private fun clampRequestDescription(description: String, maxChars: Int = 1024): String =
        if (description.length <= maxChars) description
        else description.take(maxChars).trimEnd() + "\n[description truncated for request size]"

    /**
     * 请求侧 schema 硬化（P0 兜底的 v4 升级）：
     * 1. 非法 JSON → 空对象骨架（原 P0 行为）；
     * 2. baseUrl 命中 Gemini OpenAI 兼容端点（generativelanguage / gemini）→
     *    递归剔除 Gemini 拒绝的关键字（enum / format / additionalProperties /
     *    propertyNames / exclusive* / examples）——rikkahub 同款策略；
     * 3. 其余端点原样透传（OpenAI/兼容网关自己能吃下的就不动，避免过度剪裁
     *    破坏语义）。
     */
    private fun sanitizeParameters(parameters: String): JsonElement {
        val parsed = runCatching { Json.parseToJsonElement(parameters) }
            .getOrElse {
                // #500：静默空 schema 会把该工具的调用能力一并静默禁用 ——
                // 限次留痕（与 #501 parseFailuresLogged 同模式）供诊断。
                logSanitizeFallback("tool parameters are not valid JSON", it, parameters)
                return Json.parseToJsonElement("""{"type":"object","properties":{}}""")
            }
        if (!isGeminiCompatEndpoint()) return parsed
        return runCatching { stripGeminiUnsupported(parsed) }
            .getOrElse {
                logSanitizeFallback("stripGeminiUnsupported failed", it, parameters)
                Json.parseToJsonElement("""{"type":"object","properties":{}}""")
            }
    }

    /** #500：sanitize 回退空 schema 的限次日志（首 3 次 + 之后每 50 条一条）。 */
    private fun logSanitizeFallback(reason: String, e: Throwable, parameters: String) {
        val failureNo = sanitizeFallbacksLogged.incrementAndGet()
        if (failureNo <= 3 || failureNo % 50 == 0) {
            AppLogger.instance.warn(
                LogCategory.LLM, "StreamingOpenAiClient",
                "sanitizeParameters fallback #$failureNo: $reason — " +
                    "${e::class.simpleName}: ${e.message?.take(120)} — schema head: ${parameters.take(80)}"
            )
        }
    }

    private fun isGeminiCompatEndpoint(): Boolean {
        val url = config.baseUrl.lowercase()
        return url.contains("generativelanguage") || url.contains("gemini")
    }

    private val GEMINI_UNSUPPORTED_KEYS = setOf(
        "enum", "format", "additionalProperties", "propertyNames",
        "exclusiveMinimum", "exclusiveMaximum", "examples", "\$schema",
        "patternProperties", "const"
    )

    private fun stripGeminiUnsupported(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((key, value) in element) {
                if (key in GEMINI_UNSUPPORTED_KEYS) continue
                put(key, stripGeminiUnsupported(value))
            }
        }
        is JsonArray -> buildJsonArray {
            for (item in element) add(stripGeminiUnsupported(item))
        }
        else -> element
    }

    private fun parseStreamChunk(data: String, sawDeltaContent: Boolean = false): LlmStreamChunk? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            // ── 真实用量统计帧（stream_options.include_usage）──
            // 统计帧的形态：choices 为空数组 + usage 完整。旧实现首行就因空 choices
            // 返回 null，统计被丢弃。先提取 usage；空 choices 且有 usage 的帧构造
            // 仅携带 usage 的 chunk 返回（引擎据此刷新上下文仪表盘）。
            // 安全转换（as? JsonObject）：OpenAI 协议下普通帧携带 "usage": null ——
            // 直接 .jsonObject 会抛异常把整帧正文一起丢掉，绝不能用。
            // DeepSeek 风格端点把 usage 放在末帧（choices 非空），下方正常帧构造
            // 同样透传 usage，两种形态都覆盖。
            val usage = (json["usage"] as? JsonObject)?.let { u ->
                // intOrNull：容忍代理返回的 "1234.0" / 字符串数值（与非流式路径同口径）
                Usage(
                    promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalTokens = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    cachedTokens = parseCachedTokens(u)
                )
            }
            val choices = json["choices"]?.jsonArray
            if (choices == null || choices.isEmpty()) {
                return if (usage != null) LlmStreamChunk(usage = usage) else null
            }

            val choice = choices[0].jsonObject
            val delta = choice["delta"]?.jsonObject
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

            // 多模态输出：content 可能是 content-parts 数组（OpenRouter / Gemini
            // 兼容层的图像输出）。旧实现只按 jsonPrimitive 解析，数组形态抛异常
            // 被整体丢弃 —— 图片模型表现为“模型什么都没说”。
            val contentMedia = MultimodalOutputExtractor.parseContent(delta?.get("content"))

            // 原生联网搜索引用：OpenAI/DeepSeek 的 delta.annotations（或 content-parts
            // 内嵌 annotations）、智谱的 delta.search_result；部分网关（智谱等）把
            // 引用挂在流式最后一帧的 message 上（delta 缺失）—— 用 message 兜底。
            // 注意：引用是元数据、无重复风险，无条件兜底；**正文**的 message 兜底
            // 见下方末帧兜底块（v1.5.x —— 按 sawDeltaContent 门控，防正文重复一遍）。
            // 提取后格式化为 Markdown Sources 块追加到 content 尾部，让引擎/UI 都能
            // 看到搜索来源；无引用时 content 保持原样（非搜索模型行为不变）。
            val citations = SearchCitations.extractAndFormat(
                delta ?: choice["message"]?.let { m -> runCatching { m.jsonObject }.getOrNull() }
            )
            val baseText = contentMedia.text
            var content = when {
                citations == null -> baseText
                baseText == null || baseText.isEmpty() -> citations
                else -> baseText + citations
            }

            // 原生思考内容（DeepSeek-R1 `reasoning_content`、部分 Anthropic 代理 `reasoning`）。
            // 旧实现丢弃，导致思考类模型的思维链在 UI 上不可见。
            var reasoningOut = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull

            // ═══ 末帧正文兜底（v1.5.x 修复「流式只有思考、没有回答」）═══
            // 部分网关把完整正文挂在最后一帧的 choice.message.content 上
            //（增量 delta 缺失或为空）—— 旧实现刻意不读 message 正文，本流
            // 全程零 content，引擎 contentBuilder 恒空 → 终局按空响应报错。
            // 门控（防重复）：仅末帧（finishReason 非空）且本流此前从未见过
            // 增量正文（sawDeltaContent=false）且当前帧 delta 正文为空时，才
            // 用 message.content 补发；思考内容挂末帧 message 的网关同理兜底。
            if (finishReason != null && !sawDeltaContent && content.isNullOrEmpty()) {
                val message = choice["message"]?.let { m -> runCatching { m.jsonObject }.getOrNull() }
                if (message != null) {
                    val msgText = MultimodalOutputExtractor.parseContent(message["content"]).text
                    if (!msgText.isNullOrEmpty()) content = msgText
                    if (reasoningOut.isNullOrEmpty()) {
                        reasoningOut = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                            ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
                    }
                }
            }

            // message 级 images / video_url（GLM CogView chat 式生图 / 生视频网关）
            val images = contentMedia.images +
                MultimodalOutputExtractor.parseImagesArray(delta?.get("images"))
            val videos = contentMedia.videos +
                listOfNotNull(MultimodalOutputExtractor.parseVideo(delta?.get("video_url"))) +
                MultimodalOutputExtractor.parseVideosArray(delta?.get("videos"))

            // 流式tool_calls
            val toolCalls = mutableListOf<ToolCall>()
            delta?.get("tool_calls")?.jsonArray?.forEach { tc ->
                val tcObj = tc.jsonObject
                val func = tcObj["function"]?.jsonObject
                // 读取 index（并行工具调用必需），默认 -1 表示非流式/不适用。
                val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: -1
                toolCalls.add(ToolCall(
                    id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                    name = func?.get("name")?.jsonPrimitive?.content ?: "",
                    arguments = func?.get("arguments")?.jsonPrimitive?.content ?: "",
                    index = idx
                ))
            }

            LlmStreamChunk(
                content = content,
                toolCalls = toolCalls,
                reasoningContent = reasoningOut,
                images = images,
                videos = videos,
                usage = usage,
                isFinish = finishReason == "stop" || finishReason == "tool_calls",
                finishReason = finishReason
            )
        } catch (e: Exception) {
            // ═══ 可观测性修复：不再无声丢弃 ═══
            // 服务端中途返回非 JSON 片段（网关 HTML 错误页 / 截断帧）时，旧实现
            // 静默吞掉 → 用户只看到气泡停滞，无从诊断。限次记录（首 3 条 +
            // 之后每 50 条一条）防整页失败时的日志洪峰；不影响热路径重试语义。
            val failureNo = parseFailuresLogged.incrementAndGet()
            if (failureNo <= 3 || failureNo % 50 == 0) {
                AppLogger.instance.warn(
                    LogCategory.LLM, "StreamingOpenAiClient",
                    "SSE chunk parse failed (#$failureNo): " +
                        "${e::class.simpleName}: ${e.message?.take(120)} — data head: ${data.take(80)}"
                )
            }
            null
        }
    }
    
    private fun parseNonStreamResponse(body: String): LlmResponse {
        // P2 fix（边界值，两轮混沌审查同题确认）：旧实现对非流式响应无防护——
        //   ① 代理/网关对 200 返回 HTML/空体时 Json.parseToJsonElement 抛
        //      SerializationException，绕过 LlmException 体系，ErrorClassifier 无法归类
        //      → 重试/降级策略失效。抛 LlmException.Parse 精确映射 ModelResponseInvalid
        //      （混沌审查 CR #D3 的 Http(-1) 伪码方案会误标为"请求被拒绝"，故取本方案）；
        //   ② vLLM / Gemini-OpenAI 代理把 usage 返回成 128.0（浮点）或字符串时
        //      jsonPrimitive.int 抛 NumberFormatException —— choices 已解析成功却因统计
        //      字段炸掉整个响应（该处下方已换 intOrNull 容忍）。
        // 流式路径（parseStreamChunk 全量 catch{null}）防护等级对齐。
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw LlmException.Parse(it)
        }
        val choices = json["choices"]?.jsonArray
        val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject

        // 多模态输出：message.content 可能为 content-parts 数组（同流式路径）。
        val contentMedia = MultimodalOutputExtractor.parseContent(message?.get("content"))

        // 原生联网搜索引用（OpenAI/DeepSeek 的 message.annotations、智谱的
        // message.search_result）→ Markdown Sources 块。非搜索模型无这些字段，
        // 提取结果为 null，content 保持原样（行为不变）。
        val citations = SearchCitations.extractAndFormat(message)
        val baseText = contentMedia.text
        var content = when {
            citations == null -> baseText
            baseText == null || baseText.isEmpty() -> citations
            else -> baseText + citations
        }
        // reasoning-only 兜底（v1.5.x，与流式末帧兜底同族）：思考型模型把
        // 最终回答整体写进 message.reasoning_content、正文为空 —— 旧实现
        // 返回空 content，调用方按无效响应处理。正文为空且思考通道非空时
        // 以思考文本兜底（非思考模型 content 非空，行为不变）。
        if (content.isNullOrEmpty()) {
            val reasoning = message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: message?.get("reasoning")?.jsonPrimitive?.contentOrNull
            if (!reasoning.isNullOrEmpty()) content = reasoning
        }
        val images = contentMedia.images +
            MultimodalOutputExtractor.parseImagesArray(message?.get("images"))
        val videos = contentMedia.videos +
            listOfNotNull(MultimodalOutputExtractor.parseVideo(message?.get("video_url"))) +
            MultimodalOutputExtractor.parseVideosArray(message?.get("videos"))
        val toolCalls = message?.get("tool_calls")?.jsonArray?.map { tc ->
            val tcObj = tc.jsonObject
            ToolCall(
                id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                name = tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                arguments = tcObj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
            )
        } ?: emptyList()

        val usage = json["usage"]?.jsonObject?.let { u ->
            // intOrNull：容忍代理返回的 "1234.0" / 字符串数值，不再抛 NumberFormatException
            Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                totalTokens = u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                cachedTokens = parseCachedTokens(u)
            )
        }

        return LlmResponse(content = content, toolCalls = toolCalls, usage = usage, images = images, videos = videos)
    }
    
    // OkHttp suspend扩展
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!cont.isCancelled) {
                    // T72：把原始 IOException 包成 [LlmException.Network]，供
                    // 上层 [com.apex.agent.core.llm.runtime.ErrorClassifier] 精确分类。
                    cont.resumeWithException(LlmException.Network(e))
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private companion object {
        const val MAX_NON_STREAM_RESPONSE_BYTES = 8_000_000
        const val MAX_ERROR_BODY_BYTES = 200
        /**
         * 已知接受 top_k / min_p / repetition_penalty 等非标准采样参数的 Provider。
         * OpenAI / Anthropic / Google / DeepSeek / OpenRouter 的官方端点不接受这些参数
         * （会返回 400），故仅对本地推理与自定义兼容端点发送。
         */
        val LOCAL_SAMPLING_PROVIDERS: Set<String> = setOf(
            "ollama", "lmstudio", "vllm", "custom_openai"
        )

        /**
         * T1/T3：Anthropic `thinking.budget_tokens` 的 effort 档位映射
         * （thinkingBudget 未显式设置时使用）。
         *
         * 注：OPENAI_STRICT_REASONER 正则已上提至
         * [EndpointResolver.isStrictOpenAiReasoner]（单一口径）。
         */
        val EFFORT_THINKING_BUDGETS: Map<ReasoningEffort, Int> = mapOf(
            ReasoningEffort.LOW to 2048,
            ReasoningEffort.MEDIUM to 4096,
            ReasoningEffort.HIGH to 8192,
            ReasoningEffort.MAX to 16384
        )
    }

    private fun Response.errorBodyHead(): String = body?.byteStream()?.use { stream ->
        BoundedReads.readHead(stream, MAX_ERROR_BODY_BYTES).toString(Charsets.UTF_8)
    } ?: "Unknown error"
}

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
}

/**
 * 解析 usage 里的缓存命中 token（OpenAI 兼容端点：`prompt_tokens_details.
 * cached_tokens`；Anthropic 兼容字段 `cache_read_input_tokens` 兜底）。
 *
 * 两个安全口径：`prompt_tokens_details` 可能缺席 / 为 null / 非对象（部分
 * 中转站裁剪该字段）——`as? JsonObject` 静默降级 0；数值宽容 `intOrNull`
 * （容忍字符串 / 小数形态），与两条 usage 解析路径同口径。
 */
private fun parseCachedTokens(usage: JsonObject): Int {
    (usage["prompt_tokens_details"] as? JsonObject)?.let { details ->
        details["cached_tokens"]?.jsonPrimitive?.intOrNull?.let { return it }
    }
    usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull?.let { return it }
    return 0
}

