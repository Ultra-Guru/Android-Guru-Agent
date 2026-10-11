package com.apex.agent.core.llm

/**
 * ═══ 端点解析器 —— 自定义 Base URL 的「完全自定义」语义 ═══
 *
 * ## 背景（用户反馈）
 *
 * 设置页配置自定义 Base URL 时，客户端无条件追加 `/chat/completions` ——
 * 用户填了带完整路径的中转端点（如 `https://relay.xxx.com/v1/chat/completions`）
 * 会被拼成 `.../chat/completions/chat/completions` 双段路径；填非标准路径
 * （如 Azure 风格 `.../deployments/gpt-4o/chat/completions`）同样被破坏。
 * 「自定义」应当是**完全自定义**：用户填了完整端点就原样直发。
 *
 * ## 规则（chat 端点）
 *
 * 1. 去首尾空白 + 去结尾斜杠；
 * 2. 已以 `/chat/completions` 结尾 → 原样使用（完全自定义）；
 * 3. 已以 `/completions` 结尾（OpenAI 旧版 legacy 路径）→ 原样使用；
 * 4. 其余（根路径 / `/v1` / `/v4` 等）→ 追加 `/chat/completions`
 *    （兼容既有行为：填 `https://api.openai.com/v1` 的用户零感知）。
 *
 * ## 规则（models 端点）
 *
 * 1. 同样收敛空白与结尾斜杠；
 * 2. 若误粘了 `/chat/completions` 或 `/completions` 尾巴 → 剥掉再拼
 *    `/models`（用户从 chat 端点复制过来是常见操作）；
 * 3. 已以 `/models` 结尾 → 原样使用。
 *
 * 可见性 public：app 层设置页的 URL 预览提示（「请求发往 …」）与
 * core 层客户端共用同一份归一化逻辑，杜绝「提示显示 A、实际请求 B」
 * 的口径漂移。
 */
object EndpointResolver {

    /** chat/completions 请求端点归一化（规则见类 KDoc）。 */
    fun chatCompletions(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        // 完全自定义：完整端点原样直发（chat/completions 与 legacy completions）
        if (trimmed.endsWith("/chat/completions") || trimmed.endsWith("/completions")) {
            return trimmed
        }
        return "$trimmed/chat/completions"
    }

    /** GET /models 模型清单端点归一化（规则见类 KDoc）。 */
    fun models(baseUrl: String): String {
        var trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        // 误粘 chat 端点尾巴：剥掉再拼 /models（注释意图兑现——旧实现只写没做）
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.removeSuffix("/chat/completions")
        } else if (trimmed.endsWith("/completions")) {
            trimmed = trimmed.removeSuffix("/completions")
        }
        if (trimmed.endsWith("/models")) return trimmed
        return "$trimmed/models"
    }

    /**
     * OpenAI 官方严格推理模型识别（o1/o3/o4-mini/gpt-5 系）。
     *
     * 从 [StreamingOpenAiClient] 内部正则上提为公共工具：Coding 屏思考档位
     * 的「原生 reasoning 参数是否生效」提示、客户端的
     * max_completion_tokens 判定共用同一份口径。
     *
     * 词边界正则（大小写不敏感）：`o1`/`o3`/`o4-mini`/`o1-preview` 前后
     * 不能是字母数字（拦截 neo3x / hero1 / o1abc），`gpt-5` 前缀匹配
     * （gpt-5 / gpt-5-mini / chatgpt-5-latest）。
     */
    private val OPENAI_STRICT_REASONER_REGEX = Regex(
        "(?<![a-z0-9])o[134](-mini|-preview)?(?![a-z0-9])|gpt-5",
        RegexOption.IGNORE_CASE
    )

    /** 是否为 OpenAI 官方严格推理模型（o-series / gpt-5 系）。 */
    fun isStrictOpenAiReasoner(modelId: String): Boolean =
        OPENAI_STRICT_REASONER_REGEX.containsMatchIn(modelId)
}
