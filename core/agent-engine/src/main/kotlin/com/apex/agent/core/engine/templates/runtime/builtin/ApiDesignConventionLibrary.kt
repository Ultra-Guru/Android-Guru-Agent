package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · API 设计 —— 约定库与骨架生成（PR 18 深化 II）═══
 *
 * API 设计行为的第二层确定性引擎：[ApiDesignAnalyzer] 管「需求里有
 * 哪些资源和操作」，本模块管「端点写法合不合规范、状态码怎么配、
 * 错误模型长什么样、横切关注点齐不齐、OpenAPI 骨架怎么出」——
 * 把 REST 成熟度模型的纪律变成可执行的规则集。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **命名规范校验**（naming lint）：路径段复数名词/kebab-case/
 *    路径禁动词/嵌套深度 ≤2/参数命名 snake-or-camel 一致性；
 * 2. **状态码税则**（status tax table）：操作动作 × 结果类别 →
 *    规范状态码（2xx 成功族/4xx 客户端族/5xx 服务端族的穷举表，
 *    含反模式：用 200 包错误体、用 404 表示业务不存在等）；
 * 3. **错误模型**（RFC 7807 problem+json）：错误响应骨架生成
 *    （type/title/status/detail/instance 五字段）+ 业务错误码
 *    注册表模式建议；
 * 4. **横切关注点清单**（cross-cutting checklist）：分页/过滤/排序/
 *    鉴权/幂等/限流/版本化/审计 —— 每项的约定形态与常见陷阱；
 * 5. **OpenAPI 骨架生成**（skeleton builder）：资源 + 操作 →
 *    OpenAPI 3.1 YAML 文本骨架（paths/components.schemas 分区，
 *    LLM 在骨架上填细节而不是从零排版）；
 * 6. **GraphQL 对照**：REST 端点 → Query/Mutation 对照建议
 *    （设计稿阶段的风格选型证据）。
 *
 * 防御式纪律：无法解析的端点串降级为单条违规，绝不抛。
 */

/** 端点命名违规。 */
data class ApiNamingIssue(
    val kind: Kind,
    val endpoint: String,
    val detail: String,
    /** 确定性修复建议（可机械改写的给出改后形态）。 */
    val fix: String
) {
    enum class Kind(val label: String) {
        VERB_IN_PATH("路径含动词"),
        SINGULAR_NOUN("资源名未复数"),
        BAD_CASE("命名大小写不合规"),
        TOO_DEEP("嵌套过深"),
        INCONSISTENT_PARAMS("参数命名风格不一致")
    }
}

/** 状态码建议条目。 */
data class StatusCodeAdvice(
    val action: String,
    /** 成功族建议（如 "201 Created"）。 */
    val success: String,
    /** 常见错误族建议（如 "404 Not Found / 409 Conflict"）。 */
    val errors: List<String>,
    /** 反模式警告。 */
    val antiPattern: String
)

/** 横切关注点条目。 */
data class CrossCuttingItem(
    val name: String,
    /** 约定形态（喂提示词的确定性建议）。 */
    val convention: String,
    /** 常见陷阱。 */
    val pitfall: String,
    /** 是否必选（清单里标记必查）。 */
    val required: Boolean
)

/** 解析后的端点（路径 + 方法）。 */
data class ParsedEndpoint(
    val method: String,
    /** 路径段列表（去参数段）。 */
    val segments: List<String>,
    /** 参数段（{id} 形态）。 */
    val params: List<String>,
    /** 原始行（报告证据）。 */
    val raw: String
)

/**
 * 约定库主体（纯函数集合）。
 */
object ApiDesignConventionLibrary {

    // ═══ 端点解析 ═══

    /** 端点行形态：`GET /path`（兼容 `- ` / `* ` / `1. ` 列表前缀）。 */
    private val ENDPOINT_LINE: Regex = Regex(
        """(?i)^\s*(?:[-*+]\s*)?(?:\d{1,2}[.)]\s*)?(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(/[\w\-./{}:]*)"""
    )

    /** 从文本提取端点清单（LLM 回包或用户输入均可）。 */
    fun parseEndpoints(text: String): List<ParsedEndpoint> =
        text.lines().mapNotNull { line ->
            val match = ENDPOINT_LINE.find(line) ?: return@mapNotNull null
            val method = match.groupValues[1].uppercase()
            val path = match.groupValues[2]
            val segments = path.split('/')
                .filter { it.isNotBlank() && !it.startsWith("{") }
            val params = path.split('/')
                .filter { it.startsWith("{") }
                .map { it.trim('{', '}') }
            ParsedEndpoint(method = method, segments = segments, params = params, raw = line.trim())
        }

    // ═══ 1) 命名规范校验 ═══

    /** 路径段动词黑名单（REST 路径不应含动词 —— 动作由方法表达）。 */
    private val PATH_VERBS = setOf(
        "get", "list", "create", "add", "update", "modify", "delete", "remove",
        "search", "find", "query", "fetch", "save", "send", "check", "verify",
        "login", "logout", "register"
    )

    /** 已知不规则复数（与 [ApiDesignAnalyzer] 同源的独立副本，防耦合）。 */
    private val IRREGULAR_PLURALS = mapOf(
        "category" to "categories", "entry" to "entries", "policy" to "policies",
        "box" to "boxes", "address" to "addresses", "search" to "searches",
        "analysis" to "analyses", "person" to "people", "child" to "children"
    )

    /** 单数名词判定（粗启发：非复数形态即报）。 */
    private fun isPluralForm(segment: String): Boolean {
        val lower = segment.lowercase()
        if (lower in IRREGULAR_PLURALS.values) return true
        if (lower in IRREGULAR_PLURALS.keys) return false
        // 已知单数尾部 → 未复数
        if (lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is")) return false
        return lower.endsWith("s") && !lower.endsWith("ss")
    }

    /**
     * 端点命名全量校验：
     * - 动词入路径 → 违规（给资源化改写建议）；
     * - 资源段单数 → 违规（给复数建议）；
     * - 大小写（kebab-case：禁下划线/禁驼峰）→ 违规；
     * - 嵌套深度 > 2 → 违规（子资源建议提升为顶层 + 过滤参数）；
     * - 参数命名风格混用（snake/camel 同现）→ 违规。
     */
    fun lintNaming(endpoints: List<ParsedEndpoint>): List<ApiNamingIssue> {
        val issues = mutableListOf<ApiNamingIssue>()
        for (endpoint in endpoints) {
            val path = "/" + endpoint.segments.joinToString("/")
            for (segment in endpoint.segments) {
                val lower = segment.lowercase()
                if (lower in PATH_VERBS) {
                    issues += ApiNamingIssue(
                        kind = ApiNamingIssue.Kind.VERB_IN_PATH,
                        endpoint = path,
                        detail = "路径段「$segment」是动词（动作应由 HTTP 方法表达）",
                        fix = "把动作映射到方法：GET 列表 / POST 新建 / PATCH 修改 / DELETE 删除"
                    )
                    break
                }
                if (segment.contains('_') || segment.any { it.isUpperCase() }) {
                    issues += ApiNamingIssue(
                        kind = ApiNamingIssue.Kind.BAD_CASE,
                        endpoint = path,
                        detail = "路径段「$segment」应使用 kebab-case（小写 + 连字符）",
                        fix = segment.replace('_', '-')
                            .replace(Regex("""([a-z])([A-Z])"""), "$1-$2").lowercase()
                    )
                    break
                }
                if (!isPluralForm(segment)) {
                    issues += ApiNamingIssue(
                        kind = ApiNamingIssue.Kind.SINGULAR_NOUN,
                        endpoint = path,
                        detail = "资源段「$segment」应为复数（集合语义）",
                        fix = IRREGULAR_PLURALS[lower] ?: "${segment}s"
                    )
                    break
                }
            }
            if (endpoint.segments.size > MAX_NESTING_DEPTH) {
                issues += ApiNamingIssue(
                    kind = ApiNamingIssue.Kind.TOO_DEEP,
                    endpoint = path,
                    detail = "嵌套深度 ${endpoint.segments.size} 超过 $MAX_NESTING_DEPTH",
                    fix = "子资源提升为顶层端点 + 过滤参数（如 /order-items?order_id=1）"
                )
            }
        }
        // 参数风格一致性（跨端点聚合判定）
        val allParams = endpoints.flatMap { it.params }
        val hasSnake = allParams.any { it.contains('_') }
        val hasCamel = allParams.any { Regex("""[a-z][A-Z]""").containsMatchIn(it) }
        if (hasSnake && hasCamel) {
            issues += ApiNamingIssue(
                kind = ApiNamingIssue.Kind.INCONSISTENT_PARAMS,
                endpoint = "(aggregate)",
                detail = "参数命名风格混用：snake_case 与 camelCase 同时出现",
                fix = "统一一种风格（推荐与框架生态一致：JVM 系 camelCase / Python 系 snake_case）"
            )
        }
        return issues
    }

    // ═══ 2) 状态码税则 ═══

    /** 操作动作 → 状态码建议表。 */
    val STATUS_TABLE: List<StatusCodeAdvice> = listOf(
        StatusCodeAdvice(
            "list", "200 OK（集合可能为空数组）",
            listOf("400 Bad Request（过滤参数非法）", "401/403（鉴权）"),
            "空集合返回 200 + []，而不是 404（404 表达「端点不存在」）"
        ),
        StatusCodeAdvice(
            "get", "200 OK",
            listOf("404 Not Found（资源不存在）", "401/403（鉴权）", "410 Gone（已下线）"),
            "业务可见性限制用 403 而不是 404（避免资源存在性泄露的例外：对外统一 404）"
        ),
        StatusCodeAdvice(
            "create", "201 Created + Location 头",
            listOf("400（参数校验失败）", "409 Conflict（唯一性冲突）", "422（语义校验失败）"),
            "不要用 200 包错误体 —— 状态码本身就是契约"
        ),
        StatusCodeAdvice(
            "update", "200 OK（或 204 No Content）",
            listOf("404（目标不存在）", "409（版本冲突/乐观锁）", "400（部分更新字段非法）"),
            "PATCH 部分更新与 PUT 全量替换语义不同，别混用"
        ),
        StatusCodeAdvice(
            "delete", "204 No Content",
            listOf("404（目标不存在）", "409（有依赖引用阻止删除）"),
            "幂等：重复删除同一资源应继续 204，而不是第二次 404"
        ),
        StatusCodeAdvice(
            "action", "200 OK（动作结果）",
            listOf("400（动作参数非法）", "409（状态机不允许）"),
            "自定义动作端点（如 /orders/{id}/cancel）路径含动词可接受，但动作名必须收敛"
        )
    )

    /** 动作 → 状态码建议查询（动作词归一后匹配）。 */
    fun statusAdviceFor(action: String): StatusCodeAdvice? {
        val normalized = action.lowercase()
        return STATUS_TABLE.firstOrNull { advice ->
            advice.action == normalized ||
                (advice.action == "list" && (normalized == "query" || normalized == "搜索")) ||
                (advice.action == "get" && (normalized == "detail" || normalized == "查看")) ||
                (advice.action == "create" && (normalized == "new" || normalized == "add" || normalized == "新增")) ||
                (advice.action == "update" && (normalized == "modify" || normalized == "edit" || normalized == "修改")) ||
                (advice.action == "delete" && (normalized == "remove" || normalized == "删除")) ||
                (advice.action == "action" && normalized in CUSTOM_ACTIONS)
        }
    }

    /** 自定义动作集（action 税则的命中面）。 */
    private val CUSTOM_ACTIONS = setOf(
        "cancel", "approve", "publish", "import", "export", "submit", "pay", "refund"
    )

    // ═══ 3) 错误模型（RFC 7807）═══

    /**
     * problem+json 错误响应骨架（确定性生成，LLM 按业务语义填 title/detail）。
     *
     * @param status HTTP 状态码
     * @param errorType 业务错误类型标识（如 out-of-stock）
     */
    fun problemSkeleton(status: Int, errorType: String): String = buildString {
        appendLine("{")
        appendLine("  \"type\": \"https://api.example.com/errors/$errorType\",")
        appendLine("  \"title\": \"（业务错误一句话标题）\",")
        appendLine("  \"status\": $status,")
        appendLine("  \"detail\": \"（该次请求的具体说明，含可操作信息）\",")
        appendLine("  \"instance\": \"/orders/42\"")
        append("}")
    }

    /** 业务错误码注册表建议（骨架）。 */
    fun errorCodeRegistrySkeleton(resource: String): String = buildString {
        appendLine("| 错误码 | HTTP | 含义 | 触发条件 |")
        appendLine("|---|---|---|---|")
        appendLine("| ${resource.uppercase()}_NOT_FOUND | 404 | ${resource}不存在 | id 无对应记录 |")
        appendLine("| ${resource.uppercase()}_VALIDATION_FAILED | 400 | 参数校验失败 | 必填缺失/格式非法 |")
        appendLine("| ${resource.uppercase()}_CONFLICT | 409 | 状态冲突 | 并发修改/唯一性冲突 |")
        append("（业务错误码与 HTTP 状态码一一映射注册，禁止一码多用）")
    }

    // ═══ 4) 横切关注点清单 ═══

    /** 横切关注点出厂清单（required = 设计稿必答项）。 */
    val CROSS_CUTTING_CHECKLIST: List<CrossCuttingItem> = listOf(
        CrossCuttingItem(
            "分页", "游标分页（cursor）优先：?cursor=…&limit=20；偏移分页仅限小数据集",
            "offset 深翻页性能塌方；游标未排序导致翻页丢数据", true
        ),
        CrossCuttingItem(
            "过滤与排序", "?status=active&sort=-created_at（- 前缀降序）；过滤字段白名单化",
            "任意字段过滤 = 注入面；排序无上限 = 全表扫描", true
        ),
        CrossCuttingItem(
            "鉴权", "Bearer Token（OAuth2/JWT）；资源级权限在业务层校验，不只靠网关",
            "端点级鉴权通过但资源级越权（BOLA）是最常见的 API 漏洞", true
        ),
        CrossCuttingItem(
            "幂等", "写操作支持 Idempotency-Key 头；至少 DELETE/PUT 天然幂等",
            "网络重试导致重复创建（用户双击/网关重发）", true
        ),
        CrossCuttingItem(
            "限流", "按 token 维度限流 + 429 + Retry-After 头",
            "无 429 语义时客户端只会更暴力地重试", false
        ),
        CrossCuttingItem(
            "版本化", "URI 前缀 /v1/（显式）或 Accept 头（内容协商）；破坏性变更必须升主版本",
            "隐式版本（无版本号）导致破坏性变更无人能拦", true
        ),
        CrossCuttingItem(
            "审计", "写操作记录：谁/何时/改了什么（含幂等键与请求 id）",
            "事后排障无现场；合规场景直接不过审", false
        )
    )

    /** 必查项（设计稿必须逐项回答的子集）。 */
    fun requiredChecklist(): List<CrossCuttingItem> =
        CROSS_CUTTING_CHECKLIST.filter { it.required }

    // ═══ 5) OpenAPI 骨架生成 ═══

    /**
     * OpenAPI 3.1 YAML 骨架：资源 + 操作 → paths 分区 + schemas 分区。
     * 骨架只含结构与命名（确定性部分），字段细节由 LLM 填充。
     * 中文资源名经 [englishNameOf] 映射为英文后出骨架（API 路径需英文）。
     */
    fun openApiSkeleton(
        analysis: ApiRequirementAnalysis,
        title: String = "API 设计稿"
    ): String = buildString {
        appendLine("openapi: 3.1.0")
        appendLine("info:")
        appendLine("  title: $title")
        appendLine("  version: 0.1.0")
        appendLine("paths:")
        for (operation in analysis.operations.distinctBy { it.action to it.resource }) {
            val rawResource = operation.resource ?: continue
            val resource = englishNameOf(rawResource)
            val path = pathOf(operation.action, resource)
            val method = methodOf(operation.action)
            if (path.isEmpty() || method.isEmpty()) continue
            appendLine("  $path:")
            appendLine("    $method:")
            appendLine("      operationId: ${operationIdOf(operation.action, resource)}")
            appendLine("      summary: （一句话说明）")
            appendLine("      responses:")
            appendLine("        '200':")
            appendLine("          description: （成功响应说明）")
        }
        appendLine("components:")
        appendLine("  schemas:")
        for (resource in analysis.resources) {
            val schemaName = schemaNameOf(englishNameOf(resource.name))
            appendLine("    $schemaName:")
            appendLine("      type: object")
            appendLine("      required: [id]")
            appendLine("      properties:")
            appendLine("        id:")
            appendLine("          type: string")
            appendLine("          description: （唯一标识）")
            appendLine("        # （字段细节按需求补全）")
        }
    }

    /** 动作 → 路径。 */
    internal fun pathOf(action: String, resource: String): String = when (action) {
        "list" -> "/${pluralize(resource)}"
        "get" -> "/${pluralize(resource)}/{id}"
        "create" -> "/${pluralize(resource)}"
        "update" -> "/${pluralize(resource)}/{id}"
        "delete" -> "/${pluralize(resource)}/{id}"
        else -> ""
    }

    /** 动作 → HTTP 方法（小写）。 */
    internal fun methodOf(action: String): String = when (action) {
        "list", "get" -> "get"
        "create" -> "post"
        "update" -> "patch"
        "delete" -> "delete"
        else -> ""
    }

    /** operationId 生成（listOrders / getOrder / createOrder…）。 */
    internal fun operationIdOf(action: String, resource: String): String {
        val singular = singularize(resource)
        val verb = when (action) {
            "list" -> "list"
            "get" -> "get"
            "create" -> "create"
            "update" -> "update"
            "delete" -> "delete"
            else -> action
        }
        val noun = if (action == "list") pluralize(singular) else singular
        return "$verb${noun.replaceFirstChar { it.uppercase() }}"
    }

    /** kebab/snake 资源名 → PascalCase schema 名。 */
    internal fun schemaNameOf(resourceName: String): String =
        resourceName.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
            .ifBlank { "Resource" }

    /** 复数化（含不规则表）。 */
    internal fun pluralize(word: String): String {
        val lower = word.lowercase()
        IRREGULAR_PLURALS[lower]?.let { return it }
        if (lower.endsWith("s")) return word
        return "${word}s"
    }

    /** 单数化（含不规则表反向）。 */
    internal fun singularize(word: String): String {
        val lower = word.lowercase()
        IRREGULAR_PLURALS.entries.firstOrNull { it.value == lower }?.let { return it.key }
        if (lower.endsWith("ies")) return word.dropLast(3) + "y"
        if (lower.endsWith("ses")) return word.dropLast(2)
        if (lower.endsWith("s") && !lower.endsWith("ss")) return word.dropLast(1)
        return word
    }

    // ═══ 6) GraphQL 对照 ═══

    /** REST 操作 → GraphQL 形态建议（设计稿选型证据）。 */
    fun graphQlCounterpartOf(operation: ApiOperation): String {
        val singular = singularize(englishNameOf(operation.resource ?: "resource"))
        return when (operation.action) {
            "list" -> "Query.${pluralize(singular)}(filter, first, after): ${schemaNameOf(singular)}Connection"
            "get" -> "Query.${singular}(id): ${schemaNameOf(singular)}"
            "create" -> "Mutation.create${schemaNameOf(singular)}(input): ${schemaNameOf(singular)}"
            "update" -> "Mutation.update${schemaNameOf(singular)}(id, input): ${schemaNameOf(singular)}"
            "delete" -> "Mutation.delete${schemaNameOf(singular)}(id): Boolean"
            else -> "（自定义动作 → Mutation.${operation.action}${schemaNameOf(singular)}）"
        }
    }

    /**
     * 中文资源名 → 英文名映射（与 [ApiDesignAnalyzer] 的中文资源词表对齐；
     * 未收录的中文名原样返回，LLM 在骨架上自行改写）。
     */
    internal fun englishNameOf(resourceName: String): String =
        ZH_RESOURCE_TO_ENGLISH[resourceName] ?: resourceName

    /** 中文资源词 → 英文资源词（电商/内容/教育/协同/系统五域）。 */
    internal val ZH_RESOURCE_TO_ENGLISH: Map<String, String> = mapOf(
        "商品" to "product", "订单" to "order", "库存" to "inventory",
        "优惠券" to "coupon", "购物车" to "cart", "分类" to "category",
        "标签" to "tag", "用户档案" to "user-profile", "收货地址" to "address",
        "支付单" to "payment", "退款单" to "refund", "发票" to "invoice",
        "评论" to "review", "消息" to "message", "通知" to "notification",
        "公告" to "announcement", "文章" to "article", "帖子" to "post",
        "专辑" to "album", "歌单" to "playlist", "课程" to "course",
        "章节" to "chapter", "题目" to "question", "试卷" to "exam",
        "班级" to "class-group", "教师" to "teacher", "学生" to "student",
        "教室" to "classroom", "日程" to "schedule", "会议" to "meeting",
        "任务" to "task", "项目" to "project", "里程碑" to "milestone",
        "工单" to "ticket", "合同" to "contract", "客户" to "customer",
        "供应商" to "supplier", "采购单" to "purchase-order",
        "入库单" to "inbound-order", "出库单" to "outbound-order",
        "报表" to "report", "指标" to "metric", "角色" to "role",
        "权限" to "permission", "菜单" to "menu", "字典" to "dict",
        "配置" to "config", "日志" to "log", "文件" to "file",
        "图片" to "image", "视频" to "video"
    )

    // ═══ 常量 ═══

    /** 资源嵌套深度上限。 */
    const val MAX_NESTING_DEPTH = 2

    /** 规则目录（工坊能力预览与测试断言锚点）。 */
    val RULE_IDS: List<String> = listOf(
        "api.naming-verb-in-path", "api.naming-singular-noun", "api.naming-bad-case",
        "api.nesting-too-deep", "api.params-inconsistent", "api.status-anti-pattern",
        "api.error-model-missing", "api.cross-cutting-incomplete", "api.openapi-skeleton"
    )
}
