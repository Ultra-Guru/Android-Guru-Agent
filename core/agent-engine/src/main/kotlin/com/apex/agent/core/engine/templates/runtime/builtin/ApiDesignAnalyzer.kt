package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · API 设计 —— 需求解析与约定库（PR 11）═══
 *
 * API 设计行为的分析层：把自然语言需求解析成「资源 + 操作」骨架，
 * 并提供 REST/GraphQL 两套约定库：
 * 1. **资源抽取**：名词聚类（单复数归一）→ 资源清单；
 * 2. **操作推断**：动词 → HTTP 方法/GraphQL mutation 语义映射；
 * 3. **字段类型推断**：描述词 → 类型（id/时间/枚举/分页容器…）；
 * 4. **REST 约定库**：方法语义表、状态码税则、分页/幂等/鉴权横切模板；
 * 5. **命名归一**：资源复数 kebab-case、操作 camelCase。
 */

/** 抽取出的资源。 */
data class ApiResource(
    /** 规范名（复数 kebab-case，如 order-items）。 */
    val name: String,
    /** 原文出现词。 */
    val rawTerms: List<String>,
    /** 出现次数。 */
    val mentions: Int
)

/** 推断出的操作。 */
data class ApiOperation(
    /** 动作语义（list/get/create/update/delete/…）。 */
    val action: String,
    /** 关联资源（可空 = 全局动作）。 */
    val resource: String?,
    /** HTTP 方法 + 路径建议（REST）或 GraphQL 建议形态。 */
    val restHint: String,
    val graphQlHint: String,
    /** 幂等性评估。 */
    val idempotent: Boolean
)

/** 字段类型推断。 */
data class ApiField(
    val name: String,
    val type: String,
    val required: Boolean,
    /** 推断依据。 */
    val rationale: String
)

/** 需求解析产物。 */
data class ApiRequirementAnalysis(
    val resources: List<ApiResource>,
    val operations: List<ApiOperation>,
    val fields: List<ApiField>,
    /** 需求覆盖注记（未命中任何资源的动词等）。 */
    val notes: List<String>
)

/**
 * API 需求解析器 + 约定库（纯函数）。
 */
object ApiDesignAnalyzer {

    // ═══ 资源抽取 ═══

    /** 中文量词/集合词触发（「订单列表」→ 订单）。 */
    private val COLLECTION_SUFFIXES = listOf("列表", "清单", "列表页", "管理", "模块", "系统")

    /** 已知非资源词（动词/修饰词白名单排除）。 */
    private val NON_RESOURCE_WORDS = setOf(
        "用户", "管理员", "系统", "平台", "后台", "前台", "首页", "页面", "接口", "数据", "信息", "功能",
        "需求", "设计", "支持", "提供", "查询", "新增", "创建", "删除", "修改", "更新", "导入", "导出",
        "需要", "可以", "应该", "必须", "能够", "进行", "实现", "包括", "支持", "管理", "查询", "检索"
    )

    /** 不规则复数表。 */
    private val IRREGULAR_PLURALS = mapOf(
        "category" to "categories", "product" to "products", "order" to "orders",
        "user" to "users", "entry" to "entries", "policy" to "policies",
        "box" to "boxes", "address" to "addresses", "search" to "searches"
    )

    /**
     * 资源抽取：中文双字/三字名词启发 + 英文名词捕获 → 单复数归一 →
     * 聚类计数。阈值：出现 ≥1 次即候选，按次数排序。
     */
    fun extractResources(requirement: String): List<ApiResource> {
        if (requirement.isBlank()) return emptyList()
        val mentions = LinkedHashMap<String, MutableList<String>>()

        // 英文资源词（snake/camel/kebab 均识别）
        for (match in Regex("""[a-zA-Z][a-zA-Z0-9_-]{2,}""").findAll(requirement)) {
            val word = match.value
            if (word.lowercase() in ENGLISH_STOPWORDS) continue
            val normalized = normalizeResourceName(word)
            mentions.getOrPut(normalized) { mutableListOf() }.add(word)
        }
        // 中文资源词：领域词字典命中（确定性、零噪声；长尾交给 LLM 归纳）
        for (word in CHINESE_RESOURCE_WORDS) {
            if (word in NON_RESOURCE_WORDS) continue
            val count = countOccurrences(requirement, word)
            if (count > 0) {
                mentions.getOrPut(word) { mutableListOf() }.apply { repeat(count) { add(word) } }
            }
        }
        return mentions.entries
            .map { (name, terms) -> ApiResource(name, terms.distinct(), terms.size) }
            .filter { it.mentions > 0 }
            .sortedByDescending { it.mentions }
            .take(MAX_RESOURCES)
    }

    /** 规范资源名：英文 → 复数 kebab-case；中文原样（设计稿阶段再映射）。 */
    internal fun normalizeResourceName(word: String): String {
        // 驼峰先切分再小写（lowercase 后驼峰信息就丢了）
        val kebab = word.replace(Regex("""([a-z0-9])([A-Z])"""), "$1-$2")
            .lowercase().replace('_', '-')
        val singular = kebab.removeSuffix("s").takeIf { kebab.length > 3 } ?: kebab
        val plural = IRREGULAR_PLURALS[singular] ?: "${singular}s"
        return if (kebab.endsWith("s") && kebab.length > 3) kebab else plural
    }

    // ═══ 操作推断 ═══

    /** 动词 → 动作语义表（中英双语）。 */
    private val ACTION_TABLE: List<Triple<List<String>, String, String>> = listOf(
        Triple(listOf("详情", "单个", "查看单个", "get by id", "fetch"), "get", "单例读取"),
        Triple(listOf("查询", "检索", "搜索", "列出", "获取", "查看", "list", "get", "search", "query"), "list", "集合读取"),
        Triple(listOf("新增", "创建", "添加", "create", "add", "post"), "create", "创建"),
        Triple(listOf("修改", "更新", "编辑", "update", "edit", "patch", "put"), "update", "整体/部分更新"),
        Triple(listOf("删除", "移除", "delete", "remove"), "delete", "删除"),
        Triple(listOf("导入", "import"), "import", "批量导入"),
        Triple(listOf("导出", "export", "下载"), "export", "批量导出"),
        Triple(listOf("审核", "审批", "approve", "reject", "review"), "review", "状态流转"),
        Triple(listOf("发布", "上线", "publish", "deploy"), "publish", "状态流转"),
        Triple(listOf("登录", "认证", "鉴权", "login", "auth"), "auth", "认证"),
        Triple(listOf("上传", "upload"), "upload", "文件上传"),
        Triple(listOf("统计", "报表", "汇总", "report", "stats", "metrics"), "stats", "聚合统计")
    )

    /**
     * 操作推断：动词命中 + 最近邻资源绑定（同一分句内优先）。
     */
    fun inferOperations(requirement: String, resources: List<ApiResource>): List<ApiOperation> {
        if (requirement.isBlank()) return emptyList()
        val clauses = requirement.split(Regex("""[。；;，,、\n]""")).filter { it.isNotBlank() }
        val operations = mutableListOf<ApiOperation>()
        for (clause in clauses) {
            val actionEntry = ACTION_TABLE.firstOrNull { (verbs, _, _) ->
                verbs.any { clause.contains(it, ignoreCase = true) }
            } ?: continue
            val (verbs, action, semantic) = actionEntry
            val resource = resources.firstOrNull { resource ->
                clause.contains(resource.name) || resource.rawTerms.any { clause.contains(it) }
            }?.name
            operations.add(
                ApiOperation(
                    action = action,
                    resource = resource,
                    restHint = restHintFor(action, resource),
                    graphQlHint = graphQlHintFor(action, resource),
                    idempotent = action in IDEMPOTENT_ACTIONS
                )
            )
        }
        return operations.distinctBy { it.action to it.resource }.take(MAX_OPERATIONS)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun String.containsAny(needles: List<String>): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    private fun restHintFor(action: String, resource: String?): String = when (action) {
        "list" -> "GET /${resource ?: "resources"}"
        "get" -> "GET /${resource ?: "resources"}/{id}"
        "create" -> "POST /${resource ?: "resources"}"
        "update" -> "PATCH /${resource ?: "resources"}/{id}"
        "delete" -> "DELETE /${resource ?: "resources"}/{id}"
        "import" -> "POST /${resource ?: "resources"}/import"
        "export" -> "GET /${resource ?: "resources"}/export"
        "review" -> "POST /${resource ?: "resources"}/{id}/review"
        "publish" -> "POST /${resource ?: "resources"}/{id}/publish"
        "auth" -> "POST /auth/token"
        "upload" -> "POST /${resource ?: "resources"}/upload"
        "stats" -> "GET /${resource ?: "resources"}/stats"
        else -> "GET /${resource ?: "resources"}"
    }

    private fun graphQlHintFor(action: String, resource: String?): String {
        val name = (resource ?: "resource").replace("-", "_")
        return when (action) {
            "list" -> "query: ${name}s(...)"
            "get" -> "query: ${name}(id)"
            "create" -> "mutation: create${name.replaceFirstChar { it.uppercase() }}"
            "update" -> "mutation: update${name.replaceFirstChar { it.uppercase() }}(id, input)"
            "delete" -> "mutation: delete${name.replaceFirstChar { it.uppercase() }}(id)"
            else -> "mutation: ${action.replaceFirstChar { it.uppercase() }}${name.replaceFirstChar { it.uppercase() }}"
        }
    }

    private val IDEMPOTENT_ACTIONS = setOf("list", "get", "delete", "export", "stats")

    // ═══ 字段类型推断 ═══

    /** 描述词 → 类型推断表。 */
    private val FIELD_TYPE_HINTS: List<Triple<Regex, String, String>> = listOf(
        Triple(Regex("""id|标识|编号""", RegexOption.IGNORE_CASE), "string (ID)", "标识语义 → 字符串 ID（避免自增暴露）"),
        Triple(Regex("""时间|日期|createdAt|updatedAt|时间戳""", RegexOption.IGNORE_CASE), "datetime (ISO-8601)", "时间语义 → ISO-8601 字符串"),
        Triple(Regex("""金额|价格|price|amount|费用""", RegexOption.IGNORE_CASE), "decimal (string)", "金额禁止浮点 → 十进制字符串"),
        Triple(Regex("""数量|count|库存|quantity|num""", RegexOption.IGNORE_CASE), "integer", "计数语义 → 整数"),
        Triple(Regex("""状态|state|status|类型|type|kind""", RegexOption.IGNORE_CASE), "enum", "状态/类型 → 枚举（列出取值域）"),
        Triple(Regex("""分页|page|limit|offset|cursor""", RegexOption.IGNORE_CASE), "pagination envelope", "分页容器字段"),
        Triple(Regex("""布尔|是否|enabled|是否|开关""", RegexOption.IGNORE_CASE), "boolean", "是否语义 → 布尔"),
        Triple(Regex("""列表|数组|items|list|批量""", RegexOption.IGNORE_CASE), "array<T>", "集合语义"),
        Triple(Regex("""邮箱|email""", RegexOption.IGNORE_CASE), "string (email)", "格式约束类型"),
        Triple(Regex("""手机|电话|phone|mobile""", RegexOption.IGNORE_CASE), "string (phone)", "格式约束类型"),
        Triple(Regex("""密码|password|secret""", RegexOption.IGNORE_CASE), "string (write-only)", "敏感字段只写"),
        Triple(Regex("""url|链接|link|地址""", RegexOption.IGNORE_CASE), "string (uri)", "URI 格式约束")
    )

    /**
     * 字段类型推断：需求中的常见字段语义词 → 类型建议。
     */
    fun inferFields(requirement: String): List<ApiField> {
        if (requirement.isBlank()) return emptyList()
        val fields = mutableListOf<ApiField>()
        for ((pattern, type, rationale) in FIELD_TYPE_HINTS) {
            val match = pattern.find(requirement) ?: continue
            if (fields.any { it.name == match.value }) continue
            fields.add(
                ApiField(
                    name = match.value,
                    type = type,
                    required = match.value.contains(Regex("""id|必填|required""", RegexOption.IGNORE_CASE)),
                    rationale = rationale
                )
            )
        }
        return fields.take(MAX_FIELDS)
    }

    // ═══ 约定库 ═══

    /** REST 方法语义表（提示词注入用）。 */
    val REST_METHOD_SEMANTICS: List<Pair<String, String>> = listOf(
        "GET" to "安全且幂等：只读，无副作用，可缓存",
        "POST" to "非幂等创建：每次调用产生新资源；批量导入等复合动作也用 POST",
        "PUT" to "幂等整体替换：同请求重复执行结果一致",
        "PATCH" to "部分更新：语义上非幂等（可设计为幂等）",
        "DELETE" to "幂等删除：删除已删除资源返回 204"
    )

    /** 状态码税则（常用子集 + 触发条件）。 */
    val STATUS_CODE_TAXONOMY: List<Triple<Int, String, String>> = listOf(
        Triple(200, "OK", "成功（GET/PATCH/PUT 返回体）"),
        Triple(201, "Created", "POST 创建成功（带 Location 头）"),
        Triple(204, "No Content", "成功但无返回体（DELETE / 部分更新）"),
        Triple(400, "Bad Request", "请求参数校验失败（附字段级错误清单）"),
        Triple(401, "Unauthorized", "未认证（令牌缺失/过期）"),
        Triple(403, "Forbidden", "已认证但无权限"),
        Triple(404, "Not Found", "资源不存在（幂等删除例外：可 204）"),
        Triple(409, "Conflict", "状态冲突（重复创建/并发版本冲突）"),
        Triple(422, "Unprocessable", "语义校验失败（业务规则不满足）"),
        Triple(429, "Too Many Requests", "限流触发（附 Retry-After）"),
        Triple(500, "Internal Error", "服务端异常（不泄露堆栈，返回追踪 ID）")
    )

    /** 横切关注点模板（提示词注入）。 */
    val CROSS_CUTTING_TEMPLATES: List<String> = listOf(
        "分页：游标式（cursor + limit，默认 20，上限 100）优于偏移式（深翻页性能）",
        "幂等：POST 创建支持 Idempotency-Key 头（24h 去重窗口）",
        "鉴权：Bearer Token（OAuth2 client credentials 或 JWT）；敏感操作带 scope",
        "版本：路径版本 /v1/ 起步；破坏性变更升版本不复用路径",
        "错误体：统一信封 { code, message, details[], traceId }",
        "审计：写操作记录操作者/时间/前后快照（合规预留）"
    )

    /** GraphQL 模式时的替代横切说明。 */
    val GRAPHQL_NOTES: List<String> = listOf(
        "GraphQL 模式：查询走 Query，变更走 Mutation，无 REST 路径概念",
        "分页用 Relay Cursor Connections 规范（edges + pageInfo）",
        "错误用 GraphQL errors 数组（code + message + path）",
        "N+1 防护：DataLoader 批量装载"
    )

    /**
     * 分析聚合：资源 + 操作 + 字段 + 注记。
     */
    fun analyze(requirement: String, style: String): ApiRequirementAnalysis {
        val resources = extractResources(requirement)
        val operations = inferOperations(requirement, resources)
        val fields = inferFields(requirement)
        val notes = mutableListOf<String>()
        if (resources.isEmpty()) {
            notes.add("未抽取到明确资源：需求偏动作型，建议 LLM 先归纳资源清单再设计端点")
        }
        if (operations.isEmpty()) {
            notes.add("未命中动词表：操作语义需 LLM 从需求自行归纳")
        }
        if (style.contains("graphql", ignoreCase = true) || style.contains("图")) {
            notes.add("GraphQL 风格：端点清单替换为 Query/Mutation 清单，横切关注点按 GraphQL 规范执行")
        }
        return ApiRequirementAnalysis(resources, operations, fields, notes)
    }

    /** 分析产物 → 发现清单（供行为聚合）。 */
    fun findingsFrom(analysis: ApiRequirementAnalysis): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        if (analysis.resources.isNotEmpty()) {
            findings.add(
                TemplateFinding(
                    ruleId = "api.resources",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "资源清单（${analysis.resources.size} 个）：" +
                        analysis.resources.joinToString("、") { it.name },
                    evidence = analysis.resources.joinToString("；") { "${it.name}（提及 ${it.mentions} 次）" },
                    suggestion = "端点清单以这些资源为中心展开",
                    confidence = 0.85
                )
            )
        }
        for (operation in analysis.operations) {
            findings.add(
                TemplateFinding(
                    ruleId = "api.operation",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "操作推断：${operation.action} ${operation.resource ?: "（全局）"}",
                    evidence = "REST：${operation.restHint}｜GraphQL：${operation.graphQlHint}",
                    suggestion = "幂等性：${if (operation.idempotent) "幂等" else "非幂等（注意重复提交防护）"}",
                    confidence = 0.8
                )
            )
        }
        for (field in analysis.fields) {
            findings.add(
                TemplateFinding(
                    ruleId = "api.field-type",
                    category = TemplateFindingCategory.CONSISTENCY,
                    severity = TemplateSeverity.INFO,
                    title = "字段「${field.name}」→ ${field.type}",
                    evidence = field.rationale,
                    suggestion = if (field.required) "必填字段" else "可选字段（给默认值语义）",
                    confidence = 0.9
                )
            )
        }
        for (note in analysis.notes) {
            findings.add(
                TemplateFinding(
                    ruleId = "api.note",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = note,
                    suggestion = "在报告中显式处理该缺口",
                    confidence = 1.0
                )
            )
        }
        return findings
    }

    private val ENGLISH_STOPWORDS = setOf(
        "the", "and", "for", "with", "api", "app", "should", "must", "will", "need",
        "user", "users", "system", "list", "page", "data", "info", "crud"
    )

    /** 中文资源词字典（常见业务域名词；命中即资源，确定性零噪声）。 */
    private val CHINESE_RESOURCE_WORDS = listOf(
        "商品", "订单", "库存", "优惠券", "购物车", "分类", "标签", "用户档案",
        "收货地址", "支付单", "退款单", "发票", "评论", "消息", "通知", "公告",
        "文章", "帖子", "专辑", "歌单", "课程", "章节", "题目", "试卷", "班级",
        "教师", "学生", "教室", "日程", "会议", "任务", "项目", "里程碑", "工单",
        "合同", "客户", "供应商", "采购单", "入库单", "出库单", "报表", "指标",
        "角色", "权限", "菜单", "字典", "配置", "日志", "文件", "图片", "视频"
    )

    const val MAX_RESOURCES = 10
    const val MAX_OPERATIONS = 14
    const val MAX_FIELDS = 12
}
