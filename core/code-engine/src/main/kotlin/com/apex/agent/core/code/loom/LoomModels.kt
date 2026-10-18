package com.apex.agent.core.code.loom

/**
 * # Loom Models — 织造引擎核心数据模型
 *
 * 织造线的会话数据结构：**分段**（[LoomSegment]，一段流式织物的最小单元）、
 * **调用**（[LoomInvocation]，模型发出的纬线）、**回执**（[LoomOutcomeBlock]，
 * 工具结果织回的纬线）、**布样**（[LoomWovenPlan]，PLAN 模式首轮产出的
 * 结构化计划）、**摘要**（[LoomDigestSummary]，压缩织入的旧轮结晶）。
 *
 * ## 分段语义（与解析器一一对应）
 *
 * 模型的一轮输出（以及回放历史里的用户轮）被增量切成：
 * - [LoomSegment.Text]：普通 Markdown 文本（增量 delta）；
 * - [LoomSegment.Deliberation]：deliberate 思考块内容（增量 delta）；
 * - [LoomSegment.Invocation]：完整解析的 use 调用块（原子——绝不跨块出现）；
 * - [LoomSegment.Outcome]：完整解析的 outcome 回执块（历史回放/防御路径）；
 * - [LoomSegment.Plan]：完整解析的 loom-plan 布样块；
 * - [LoomSegment.Digest]：完整解析的 loom-digest 摘要块。
 *
 * ## 防御纪律
 *
 * 所有从外部文本（模型输出/持久化历史）反序列化出来的结构都遵循
 * 「吞得下任何形状」：字段缺失折叠为默认值，超长字段由消费侧预算裁剪，
 * 本层不做 IO、不抛异常。
 */
/** 织物分段：流式解析的最小产出单元。 */
sealed interface LoomSegment {

    /** 普通 Markdown 文本增量（最终回复的组成部分）。 */
    data class Text(val delta: String) : LoomSegment

    /** 思考块增量（deliberate 内容——UI 呈现为思考链）。 */
    data class Deliberation(val delta: String) : LoomSegment

    /** 完整的 use 调用块（原子产出——解析完成才发射）。 */
    data class Invocation(val invocation: LoomInvocation) : LoomSegment

    /** 完整的 outcome 回执块（历史回放 / 模型防御性回显路径）。 */
    data class Outcome(val outcome: LoomOutcomeBlock) : LoomSegment

    /** 完整的 loom-plan 布样块（PLAN 模式首轮产物）。 */
    data class Plan(val plan: LoomWovenPlan) : LoomSegment

    /** 完整的 loom-digest 摘要块（压缩织入的旧轮结晶）。 */
    data class Digest(val digest: LoomDigestSummary) : LoomSegment
}

/**
 * 一次工具调用（模型 → 引擎的纬线）。
 *
 * [args] 保序——协议允许多个同名 arg（后者覆盖前者，顺序即声明顺序）；
 * [rawJson] 是原生函数调用路径的**原始参数 JSON**（在场时执行优先用它，
 * 避免嵌套结构在 arg 明文化往返中失真）；[rawSpan] 保留原始标记文本
 * （回写历史时原样织回，防语义漂移）。
 */
data class LoomInvocation(
    /** 工具 id（注册表面内的名字）。 */
    val name: String,
    /** 有序参数表（XML arg 子元素按出现顺序）。 */
    val args: List<LoomArg>,
    /** 原生函数调用路径的原始参数 JSON（手写 use 块时为 null）。 */
    val rawJson: String? = null,
    /** 原始标记文本（含 use 开闭标签；缺省时由 formatter 重建）。 */
    val rawSpan: String = ""
) {
    /** 按名取参（首个命中；重复声明时首个生效）。 */
    fun arg(name: String): String? = args.firstOrNull { it.name == name }?.value

    /**
     * 参数 JSON（交给注册表执行用——工具面吃 JSON 字符串）。
     *
     * 原生路径优先回传 [rawJson]（嵌套结构零失真）；手写路径由
     * [argumentsJson] 重建（重复名取后者，声明序即键序）。
     */
    fun argumentsOrJson(): String = rawJson ?: argumentsJson()

    /** 参数 JSON 重建（交给注册表执行用——工具面吃 JSON 字符串）。 */
    fun argumentsJson(): String {
        if (args.isEmpty()) return "{}"
        // 重复名取后者（与提示词口径一致：同名重复声明，后值覆盖）；
        // LinkedHashMap 保序——声明顺序即 JSON 键序。
        val lastByName = LinkedHashMap<String, String>()
        for (a in args) lastByName[a.name] = a.value
        val body = lastByName.entries.joinToString(",") { (k, v) ->
            "\"" + escapeJson(k) + "\":\"" + escapeJson(v) + "\""
        }
        return "{" + body + "}"
    }

    /** 调用指纹（防环检测：同名同参视为重复纬线）。 */
    fun fingerprint(): String =
        name + "(" + args.joinToString(",") { it.name + "=" + it.value } + ")"

    private fun escapeJson(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** use 块的单个 arg 子元素。 */
data class LoomArg(
    val name: String,
    /** 值为 XML 解转义后的明文（数字/布尔由工具面自行 coerce）。 */
    val value: String
)

/** 工具回执状态三态（outcome 块的 status 属性口径）。 */
enum class LoomOutcomeStatus {
    /** 成功。 */
    OK,
    /** 柔性异常（超时 / 裁剪 / 越权拒绝等——模型可自行调整后重试）。 */
    WARN,
    /** 硬失败（工具崩了 / 参数不合法——重试大概率无效）。 */
    ERROR;

    companion object {
        /** 文本 → 枚举（脏值折叠 ERROR——宁可悲观也不误报成功）。 */
        fun fromText(raw: String?): LoomOutcomeStatus = when (raw?.trim()?.lowercase()) {
            "ok", "success" -> OK
            "warn", "warning" -> WARN
            else -> ERROR
        }
    }
}

/**
 * 一次工具结果（引擎 → 模型的纬线，织回用户轮文本）。
 *
 * [token] 是随机短码（outcome_ 前缀后的部分）——每个回执块标签名都
 * 不同，防提示注入伪造「系统回执」、也防模型把上轮标签抄进新输出。
 */
data class LoomOutcomeBlock(
    /** 随机短码（base36 小写 4 位）。 */
    val token: String,
    /** 工具 id。 */
    val toolName: String,
    /** 回执状态。 */
    val status: LoomOutcomeStatus,
    /** 回执载荷（已裁剪、已解转义后的明文）。 */
    val payload: String,
    /** 是否经过预算裁剪。 */
    val truncated: Boolean = false
)

/** PLAN 模式首轮的布样（结构化计划，等待人控门确认）。 */
data class LoomWovenPlan(
    /** 总目标（goal 属性）。 */
    val goal: String,
    /** 步骤清单（step 子元素按序）。 */
    val steps: List<String>,
    /** 可选备注（note 子元素——模型自述计划理由/风险）。 */
    val note: String = ""
) {
    /** 步骤非空才可出示人控门（空步骤计划直接判无效）。 */
    fun isPresentable(): Boolean = goal.isNotBlank() && steps.isNotEmpty()
}

/** 压缩织入的旧轮结晶（loom-digest 块）。 */
data class LoomDigestSummary(
    /** 摘要正文。 */
    val text: String,
    /** 覆盖的轮次数（诊断展示用）。 */
    val roundsCovered: Int = 0
)

/** 附件标签（用户输入携带文件时织入用户轮的描述行）。 */
data class LoomAttachmentTag(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** 子线程运行态（swarm 域——spawn 后的生命周期）。 */
enum class LoomThreadPhase {
    /** 已创建未起飞。 */
    IDLE,
    /** 正在织造（模型循环中）。 */
    WEAVING,
    /** 单飞完成（结果可取）。 */
    SETTLED,
    /** 失败收场。 */
    FAILED,
    /** 被中止。 */
    ABORTED
}

/** 子线程快照（thread_status / thread_observe 工具的回执数据）。 */
data class LoomThreadSnapshot(
    val threadId: String,
    val label: String,
    val phase: LoomThreadPhase,
    /** 已完成的梭回合数。 */
    val rounds: Int,
    /** 最近一条回复文本（预算内截断）。 */
    val lastReply: String,
    /** 起飞以来的错误消息（FAILED 时非空）。 */
    val failure: String? = null
)

/** 激活的织造包（packager 域——技能包的运行时激活态）。 */
data class LoomActivePackage(
    /** 技能 id（宿主 SkillRegistry 目录内）。 */
    val id: String,
    /** 展示名。 */
    val title: String,
    /** 注入正文（SKILL 正文——激活时织入 outcome 回执）。 */
    val instructions: String
)
