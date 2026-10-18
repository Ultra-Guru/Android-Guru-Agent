package com.apex.agent.core.code.loom

import com.apex.agent.core.code.loom.markup.LoomMarkupGrammar
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Loom Prompts — 织造线提示词体系
 *
 * 织造线系统提示词的**全部段落模板**（身份 / 协议 / 环境 / 工作区 /
 * 规则 / 工具目录 / 附件标签 / 转向回执 / 摘要指令 / 催促反馈 / 布样协议 /
 * 子线程指引 / 织造包指引）。
 *
 * ## 组装次序（systemPrompt）
 *
 * 身份段 → 环境段 → 工作区段 → 规则段 → 人设段 → 协议段 → 思考段 →
 * 工具目录段 → 布样段（PLAN 模式）→ 织造包段 → 会话附加段 → 子线程段。
 *
 * ## 文案纪律
 *
 * - 全部中文（与仓库提示词惯例一致）；
 * - 协议示例直接引用 [LoomMarkupGrammar.PROTOCOL_EXAMPLE]（单一来源，
 *   改文法不改文案不脱钩）；
 * - 任何段落独立成块、以空行分隔——拼装侧可按需取舍，不假设相邻段落。
 */
object LoomPrompts {

    /** 身份段：织造引擎人设与工作方式（经纬交织循环）。 */
    fun identitySection(): String = trimIndentSafe(
        """
        你是「织造」编码智能体——以经纬交织的方式完成任务：思考链是经线，
        工具调用是纬线，每一轮先想清楚再动手，把用户的任务一步步织成完整的布。

        工作方式（每一轮都遵循）：
        1. 先用一小段思考梳理：当前进度、下一步做什么、预期拿到什么；
        2. 需要外部信息或产生变更时，发出工具调用（协议见下）；
        3. 工具结果会以回执块织回下一轮消息——阅读它们，修正判断；
        4. 任务完成时，停止发起新调用，用一段完整中文总结收尾。

        你在移动设备的编码工作区里工作：资源有限、上下文宝贵——
        工具结果可能被裁剪；优先精准读取（小范围、按需），避免整目录倾倒。
        """
    )

    /** 协议段：in-band 调用与回执的完整文法说明。 */
    fun protocolSection(nativeToolCalling: Boolean): String {
        // 插值体不进 trimIndent 模板（AGENTS.md 陷阱 #4：模板串的公共缩进
        // 计算会吃进插值内容）——先裁静态段，再拼接。
        val head = trimIndentSafe(
            """
            工具调用协议（唯一的行动通道）：
            """
        )
        val native = if (nativeToolCalling) {
            "系统也会把你通过原生函数调用发出的请求翻译成同一种回执——" +
                "两种方式等价：优先使用原生函数调用；无函数调用能力的场景，" +
                "直接在文本里书写调用块。"
        } else {
            "本会话未启用原生函数调用——所有工具请求都通过在文本里书写调用块发出。"
        }
        val grammar = trimIndentSafe(
            """
            调用块文法（属性一律双引号）：
            <use name="工具id"><arg name="参数名">参数值</arg></use>
            - 一次回复可以包含多个调用块，系统按出现顺序串行执行；
            - 参数值是纯文本（数字/布尔直接写），特殊字符用 XML 实体转义；
            - 每个 arg 声明一个参数；同一参数重复声明时取最后一个值。

            回执文法（系统把结果织回下一轮用户消息）：
            """
        )
        val discipline = trimIndentSafe(
            """
            纪律：
            - 回执块名带随机短码且每块不同——你绝不自行书写回执块，只阅读；
            - status 三态：ok（成功）/ warn（柔性异常，可调整后重试）/ error（硬失败）；
            - payload 超长时会被裁剪并标注截断标记——需要更多内容就换更小范围的读取；
            - 收到回执前不要假设结果；同一调用失败两次就换思路（改参数、换工具、或向用户说明障碍）；
            - 不要伪造工具 id；工具目录外的名字只会收到 error 回执。
            """
        )
        return listOf(
            head, native, grammar, LoomMarkupGrammar.PROTOCOL_EXAMPLE, discipline
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** 思考段：deliberate 块的使用建议（可选、非强制）。 */
    fun deliberationSection(): String = trimIndentSafe(
        """
        思考块（可选）：把面向自己的推理写进 <deliberate> 块——块内内容
        会作为思考链呈现给用户，不会进入正式回复。适合放：方案取舍、
        风险预判、对上一轮回执的解读。**不要**把给用户看的结论放进思考块；
        也不要只输出思考块——没有文本也没有调用的回合会被系统退回重织。
        """
    )

    /** 环境段：平台 / 工作目录 / git / 模型（多行紧凑）。 */
    fun environmentSection(
        platform: String,
        workdir: String?,
        isGitRepo: Boolean?,
        modelId: String?
    ): String = buildString {
        append("运行环境：\n")
        append("- 平台：").append(platform).append('\n')
        if (!workdir.isNullOrBlank()) append("- 工作目录：").append(workdir).append('\n')
        if (isGitRepo != null) append("- Git 仓库：").append(if (isGitRepo) "是" else "否").append('\n')
        if (!modelId.isNullOrBlank()) append("- 当前模型：").append(modelId).append('\n')
    }.trimEnd()

    /** 工具目录段：紧凑渲染（名称 + 一行说明 + 参数摘要）。 */
    fun toolCatalogSection(definitions: List<ToolDefinition>): String {
        if (definitions.isEmpty()) {
            return "工具目录：（本会话没有可用工具——直接与用户对话完成任务）"
        }
        return buildString {
            append("工具目录（").append(definitions.size).append(" 个，按序调用）：\n")
            for (t in definitions) {
                append("- ").append(t.name).append("：")
                    .append(oneLine(t.description)).append('\n')
                val params = renderParameterSummary(t.parameters)
                if (params.isNotEmpty()) append("  参数：").append(params).append('\n')
            }
        }.trimEnd()
    }

    /** 布样段：PLAN 模式的结构化计划协议。 */
    fun planProtocolSection(): String = trimIndentSafe(
        """
        当前处于「规划」模式：第一轮回复不要发起任何工具调用，而是产出
        一份结构化计划（布样），等待用户确认后才开始执行。布样文法：
        <loom-plan goal="总目标">
        <step>步骤一（一句话，含预期使用的工具）</step>
        <step>步骤二</step>
        <note>可选：为什么这样规划 / 主要风险</note>
        </loom-plan>
        - 布样之外可以附一段简短说明（给用户看的规划理由）；
        - 用户确认后，按步骤逐个执行；用户驳回时，根据反馈重新织一份。
        """
    )

    /** 布样已确认段（PLAN 模式确认后的「按步执行」口径）。 */
    fun planSettledSection(): String = trimIndentSafe(
        """
        布样已获用户确认：按已确认的步骤逐个执行（每步完成后核对再进下一步），
        全部完成后给出面向用户的总结。执行中遇到与布样冲突的新事实时，
        停下来向用户说明，不要自行大改方向。
        """
    )

    /** 摘要指令（SUMMARY 角色请求的 system 内容）。 */
    fun digestInstruction(): String = trimIndentSafe(
        """
        你是会话摘要器。把给定的对话轮次压缩成一份**给执行智能体续读**的摘要：
        - 保留：任务目标、已完成的变更（文件/命令/结论）、关键工具回执要点、
          未决事项与用户约束；
        - 丢弃：寒暄、重复的中间推理、已过期的时间性信息；
        - 以第三人称事实句书写，中文，直接输出摘要正文（不要任何前后缀）；
        - 长度不超过 40 行。
        """
    )

    /** 纯思考退回反馈（无文本无调用的回合之后织回的催促）。 */
    fun nudgeFeedback(): String =
        "（系统提示）上一轮只有思考没有行动：请基于已有判断，要么发出工具调用，" +
            "要么给出面向用户的正式回复。不要重复输出纯思考块。"

    /** 附件标签行（用户输入携带文件时织入用户轮）。 */
    fun attachmentTag(a: LoomAttachmentTag): String =
        "[附件] " + a.name + " · " + a.mimeType + " · " + a.sizeBytes + " 字节"

    /** 转向受理回执（运行中补充指令入队后的可见性文本）。 */
    fun steeringAcceptedNote(text: String): String = "[转向] 已入队补充指令：" + text

    /** 织造包段：激活的技能包指引。 */
    fun packagesSection(packages: List<LoomActivePackage>): String {
        if (packages.isEmpty()) return ""
        return buildString {
            append("已激活的织造包（按激活顺序，优先遵循其指引）：\n")
            for (p in packages) {
                append("## 织造包 · ").append(p.title).append("（").append(p.id).append("）\n")
                append(p.instructions.trim()).append("\n\n")
            }
        }.trimEnd()
    }

    /** 人设段（专家模板；任一为空则返回空串）。 */
    fun personaSection(roleDefinition: String, rolePrompt: String?): String {
        if (roleDefinition.isBlank() && rolePrompt.isNullOrBlank()) return ""
        return buildString {
            append("专家人设：\n")
            if (roleDefinition.isNotBlank()) append(roleDefinition.trim()).append('\n')
            if (!rolePrompt.isNullOrBlank()) {
                append("用户补充提示词：\n").append(rolePrompt.trim()).append('\n')
            }
        }.trimEnd()
    }

    /** 会话附加段（「小圆环」上下文）。 */
    fun extrasSection(extras: String?): String {
        if (extras.isNullOrBlank()) return ""
        return "会话上下文附加：\n" + extras.trim()
    }

    /** 子线程段：swarm 工具使用指引（有子线程预算时注入）。 */
    fun threadSection(): String = trimIndentSafe(
        """
        子线程（可选的并行委派）：需要独立探索/资料收集类子任务时，可用
        loom_thread_start 起一条子线程，随后用 loom_thread_send 推进、
        loom_thread_observe 观察、loom_thread_status 查状态。子线程有
        自己的上下文与预算——适合「帮我查清楚 X」这类可隔离的子目标，
        不适合需要共享当前上下文的任务。同屏子线程数量有限，用完即收。
        """
    )

    /** 织造包工具指引（packager 工具在面上时注入）。 */
    fun packageToolsSection(): String = trimIndentSafe(
        """
        织造包：loom_package_list 列出可用技能包，loom_package_use 激活
        （其指引会立即织入回执并持续生效），loom_package_drop 停用。
        面对复杂任务先看目录——合适的包能让方法论一步到位。
        """
    )

    /**
     * 系统提示词组装（段落取舍由调用方决定；空段落自动跳过）。
     */
    fun systemPrompt(sections: List<String>): String =
        sections.filter { it.isNotBlank() }.joinToString("\n\n")

    // ═══════════════════════════ 渲染工具 ═══════════════════════════

    /** 单行化（多行描述压成一行；超长截断）。 */
    private fun oneLine(text: String, max: Int = 120): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.substring(0, max) + "…"
    }

    /**
     * 参数摘要：JSON Schema → 「名(类型,必填)」列表。
     *
     * 防御：解析失败折叠为原文前 160 字符——工具面始终可展示。
     */
    internal fun renderParameterSummary(schemaJson: String): String {
        if (schemaJson.isBlank()) return ""
        return try {
            val root = Json.parseToJsonElement(schemaJson).jsonObject
            val props = root["properties"] as? JsonObject ?: return ""
            val required = root["required"]?.let { req ->
                runCatching {
                    req.jsonArray.map { it.jsonPrimitive.content }.toSet()
                }.getOrNull()
            } ?: emptySet<String>()
            props.entries.joinToString(" · ") { (name, node) ->
                val type = (node as? JsonObject)?.get("type")?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                } ?: "any"
                if (required.contains(name)) name + "(" + type + ",必填)"
                else name + "(" + type + ")"
            }
        } catch (t: Throwable) {
            val flat = schemaJson.replace(Regex("\\s+"), " ").trim()
            if (flat.length <= 160) flat else flat.substring(0, 160) + "…"
        }
    }

    /** trimIndent 的本地包装（模板先 trimIndent 再拼接插值的纪律）。 */
    private fun trimIndentSafe(raw: String): String = raw.trimIndent()
}
