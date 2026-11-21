package com.apex.agent.core.engine.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具输出截断器
 *
 * 在工具输出进入对话历史之前进行截断。
 * 这是第一道防线，始终生效，不需要等待压缩触发。
 *
 * 策略：
 * - 短输出（< maxChars）：不截断
 * - 长输出（≥ maxChars）：保留 head + tail，中间省略
 * - 特殊格式处理：JSON保留结构，代码保留首尾
 *
 * ## JSON 字段级截断（P1 修复：模型失明决策）
 *
 * TerminalExec / terminal_run 等结构化工具返回的 JSON 里，决定命令
 * 成败的字段（exit_code / timed_out / truncated / duration_ms / channel）
 * 排在 stdout 之后 —— 旧实现的「head + 小尾巴」把中后部的这些字段全切进
 * 省略区：stdout 超过 ~1.2KB 时模型只能从输出开头猜成败，把失败当成功
 * （或反之），后续步骤建立在错误假设上 —— 「Agent 任务出错率高」的
 * 机制性根因之一。现在 JSON 走字段级截断：
 * - 标量字段（数字/布尔/短字符串）原样保留 —— exit_code 等永不丢；
 * - 长字符串字段（stdout/stderr/content…）在剩余预算内单独 head+tail 截断；
 * - 数组截前 N + 后 M 项，标注省略数量；
 * - 解析失败（半截 JSON / 伪装 JSON 的普通文本）或病态产物超限 →
 *   回退旧的整串 head+tail 策略（防御式：任何形状都吞得下）。
 *
 * ## 按调用预算覆盖（P0 修复：截断预算链路打通）
 *
 * 旧链路断层：模型按工具 schema 显式请求 `max_output_chars=12000`，
 * TerminalExec 忠实执行并返回 12K JSON；但引擎用配置预算
 * （Agent 默认 2000 / Coding 8000~16000）在回灌前**再次**截断 ——
 * 模型请求的超预算被静默作废，stdout 实际只见 ~1.9K（扣掉 JSON 键开销），
 * 「输出不完整」成了常态且模型无从得知是被哪一层截的。
 *
 * 现在 [smartTruncate] / [truncate] 接受可选 [maxCharsOverride]：调用方
 * （EngineToolExecution）从工具调用参数里解析模型显式声明的输出预算，
 * 以 max(配置预算, 声明预算) 为本次调用的生效预算。两层语义从此打通：
 * 模型要多少（在硬顶内）就能拿到多少；没声明时行为与旧版完全一致。
 */
class ToolOutputTruncator(
    val maxChars: Int = 2000,
    private val headChars: Int = 1200,
    private val tailChars: Int = 600
) {

    // ═══ 混沌工程加固（CR #D1）：head/tail 必须在构造期收敛到 maxChars 预算内 ═══
    //
    // 触发路径：设置页允许 maxToolOutputLength 低至 200（合法值），经 AgentConfig
    // 原样流入本类；而默认 headChars+tailChars = 1800。旧实现此时：
    //   1. take(headChars) / takeLast(tailChars) 各自返回全文 → "截断"产物 ≈ 2× 原文，
    //      截断防线反向膨胀上下文；
    //   2. "[... output.length - headChars - tailChars chars omitted ...]" 计数为负，
    //      例如 "[... -1550 chars omitted ...]"。
    // 修复：按 2:1 比例把 head/tail 收敛进 maxChars，并在截断期 coerce 省略计数 ≥ 0。
    //（按调用覆盖的预算走同一收敛逻辑 —— 见 [resolveBudget]。）

    /** JSON 截断用的宽松解析器（结构校验不是它的职责）。 */
    private val jsonFormat: Json = Json { ignoreUnknownKeys = true }

    /**
     * 单次截断的生效预算（max + head + tail 三元组）。
     *
     * head/tail 按构造参数比例（headChars:tailChars）收敛进 [max]：
     * 覆盖预算大于构造预算时 head/tail 随比例放大（模型要了更多 → 首尾都更多），
     * 小于构造预算时沿用 CR #D1 的 2:1 收敛（防负计数与产物膨胀）。
     */
    private data class Budget(val max: Int, val head: Int, val tail: Int)

    private fun resolveBudget(override: Int?): Budget {
        val max = (override ?: maxChars).coerceAtLeast(1)
        val head: Int
        val tail: Int
        if (override != null && override > maxChars) {
            // 覆盖预算：按构造比例放大（headChars/(headChars+tailChars)），
            // 但至少留 1/5 给尾部（错误摘要常在尾部，全给头部会重演失明决策）。
            val ratio = if (headChars + tailChars > 0) {
                headChars.toFloat() / (headChars + tailChars)
            } else DEFAULT_HEAD_RATIO
            val safeRatio = ratio.coerceIn(0.2f, 0.8f)
            head = (max * safeRatio).toInt().coerceAtLeast(1)
            tail = (max - head).coerceAtLeast(0)
        } else {
            // 构造预算（CR #D1 原逻辑）
            head = minOf(headChars, (max * 2 / 3).coerceAtLeast(1))
            tail = minOf(tailChars, (max - head).coerceAtLeast(0))
        }
        return Budget(max, head, tail)
    }

    /**
     * 截断工具输出
     *
     * @param maxCharsOverride 本次调用的预算覆盖（null = 用构造预算）；
     *   生效值 = 覆盖值本身（调用方负责 max(配置, 声明) 合并与硬顶钳制）。
     * @return 截断后的文本（如果未截断则返回原文）
     */
    fun truncate(output: String, maxCharsOverride: Int? = null): TruncationResult {
        val budget = resolveBudget(maxCharsOverride)
        if (output.length <= budget.max) {
            return TruncationResult(output, truncated = false)
        }

        val truncated = buildString {
            append(output.take(budget.head))
            append("\n\n")
            append("[... ${(output.length - budget.head - budget.tail).coerceAtLeast(0)} chars omitted ...]")
            append("\n\n")
            append(output.takeLast(budget.tail))
        }

        return TruncationResult(withNotice(truncated), truncated = true)
    }

    /**
     * 智能截断：根据内容类型选择策略
     *
     * @param maxCharsOverride 本次调用的预算覆盖（null = 用构造预算），
     *   见类 KDoc「按调用预算覆盖」。
     */
    fun smartTruncate(output: String, toolName: String, maxCharsOverride: Int? = null): TruncationResult {
        val budget = resolveBudget(maxCharsOverride)
        if (output.length <= budget.max) {
            return TruncationResult(output, truncated = false)
        }

        return when {
            // JSON输出：字段级截断（保留 exit_code 等成败标量，见类 KDoc）
            isJson(output) -> truncateJson(output, budget)

            // 列表输出（如pm list packages）：保留首尾
            isListOutput(output) -> truncateList(output, budget)

            // 代码输出：保留头部（通常包含关键信息）
            toolName in listOf("Read", "CodeRead") -> truncateHead(output, budget)

            // 错误输出：尽量完整保留（通常不长但很重要）。修复两个 bug：
            // (1) 旧实现的 truncated 标志恒为 false（条件 output.length < maxChars*2 已保证
            //     output.length > maxChars*2 不成立），即便发生截断也不会上报；
            // (2) take(maxChars*2) 可返回 2× 名义上限，使截断器失效。
            // 现在统一截到 maxChars，并正确标记 truncated。
            output.startsWith("Error") -> {
                if (output.length <= budget.max) {
                    TruncationResult(output, truncated = false)
                } else {
                    TruncationResult(
                        withNotice(
                            output.take(budget.max) +
                                "\n\n[... error output truncated at ${budget.max} chars, total ${output.length} chars]"
                        ),
                        truncated = true
                    )
                }
            }

            // 默认：head + tail
            else -> truncate(output, maxCharsOverride)
        }
    }

    private fun isJson(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun isListOutput(text: String): Boolean {
        val lines = text.lines()
        return lines.size > 20 && lines.take(5).all { it.length < 200 }
    }

    /**
     * JSON截断：字段级预算分配。
     *
     * 解析成功且产物收敛（≤ [MAX_EXPANSION_FACTOR]×budget.max）时：顶层标量
     * 字段（exit_code / timed_out / channel / duration_ms 等成败元数据）原样
     * 保留，长字符串字段（stdout / stderr 等）在剩余预算内 head+tail。
     * 解析失败或产物仍超限（病态多键对象）→ 回退整串 head+tail。
     */
    private fun truncateJson(json: String, budget: Budget): TruncationResult {
        val rebuilt = runCatching {
            truncateJsonValue(jsonFormat.parseToJsonElement(json), budget.max).toString()
        }.getOrNull()

        if (rebuilt != null && rebuilt.length <= budget.max * MAX_EXPANSION_FACTOR) {
            return TruncationResult(withNotice(rebuilt), truncated = true)
        }

        // 回退：旧整串策略（JSON 尾部通常是闭合括号；exit_code 等若在
        // 尾部仍会被 tail 段捞回一部分）
        val head = json.take(budget.head)
        val tail = json.takeLast(minOf(200, (budget.max / 5).coerceAtLeast(1)))

        val truncated = "$head\n\n[... JSON truncated, ${json.length} chars total ...]\n\n$tail"
        return TruncationResult(withNotice(truncated), truncated = true)
    }

    /**
     * 递归截断一个 JSON 值，总字符预算 [budget]。
     *
     * - 标量（数字/布尔/null）：原样保留 —— 成败元数据永不丢；
     * - 短字符串（≤ [SHORT_STRING_KEEP]）：原样保留（channel:"proot" 这类）；
     * - 长字符串：head+tail 截断 + 省略标注（嵌在字符串值内）；
     * - 对象：键全部保留；重字段（长字符串/嵌套容器）均摊剩余预算；
     * - 数组：截前 [ARRAY_KEEP_HEAD] + 后 [ARRAY_KEEP_TAIL] 项，标注省略数。
     *
     * @return 截断后的新 JsonElement（不可变重建，不修改原树）
     */
    private fun truncateJsonValue(element: JsonElement, budget: Int): JsonElement {
        if (budget < MIN_FIELD_BUDGET) {
            // 预算耗尽：整串截断兜底（保结构不如保预算，防膨胀）
            return when (element) {
                is JsonObject, is JsonArray ->
                    JsonPrimitive(clipString(element.toString(), MIN_FIELD_BUDGET))
                else -> element
            }
        }
        return when (element) {
            is JsonObject -> truncateObject(element, budget)
            is JsonArray -> truncateArray(element, budget)
            else -> element
        }
    }

    private fun truncateObject(obj: JsonObject, budget: Int): JsonObject {
        // 1) 标量保留区：键名 + 短值（exit_code / timed_out 等成败元数据）
        val reserved = obj.entries.sumOf { (k, v) ->
            val valueCost = when {
                v is JsonPrimitive && !v.isString -> v.toString().length
                v is JsonPrimitive && v.content.length <= SHORT_STRING_KEEP -> v.content.length + 2
                else -> 0
            }
            k.length + 4 + valueCost
        }
        // 2) 重字段（长字符串 / 嵌套容器）均摊剩余预算
        val heavyCount = obj.entries.count { (_, v) ->
            v !is JsonPrimitive || (v.isString && v.content.length > SHORT_STRING_KEEP)
        }
        val remaining = (budget - reserved).coerceAtLeast(MIN_FIELD_BUDGET)
        val perHeavy = if (heavyCount > 0) remaining / heavyCount else remaining

        val out = LinkedHashMap<String, JsonElement>(obj.size)
        obj.forEach { (key, value) ->
            out[key] = when {
                value is JsonPrimitive && !value.isString -> value
                value is JsonPrimitive && value.content.length <= SHORT_STRING_KEEP -> value
                value is JsonPrimitive -> JsonPrimitive(clipString(value.content, perHeavy))
                else -> truncateJsonValue(value, perHeavy)
            }
        }
        return JsonObject(out)
    }

    private fun truncateArray(array: JsonArray, budget: Int): JsonElement {
        val small = array.size <= ARRAY_KEEP_HEAD + ARRAY_KEEP_TAIL
        if (small && array.toString().length <= budget) return array

        val head = array.take(ARRAY_KEEP_HEAD).map { truncateJsonValue(it, budget / 2) }
        val tail = if (array.size > ARRAY_KEEP_HEAD + ARRAY_KEEP_TAIL) {
            array.takeLast(ARRAY_KEEP_TAIL).map { truncateJsonValue(it, budget / 4) }
        } else {
            emptyList()
        }
        val omitted = array.size - head.size - tail.size
        val out = mutableListOf<JsonElement>()
        out.addAll(head)
        if (omitted > 0) out += JsonPrimitive("[... $omitted items omitted ...]")
        out.addAll(tail)
        return JsonArray(out)
    }

    /** 字符串 head+tail 截断，带省略标注（错误摘要常在尾部，首尾都保）。 */
    private fun clipString(text: String, budget: Int): String {
        if (budget <= 0) return "[... ${text.length} chars omitted ...]"
        if (text.length <= budget) return text
        val head = (budget * 2 / 3).coerceAtLeast(1)
        val tail = (budget - head).coerceAtLeast(0)
        val omitted = (text.length - head - tail).coerceAtLeast(0)
        return buildString {
            append(text.take(head))
            append(" [... $omitted chars omitted ...] ")
            append(text.takeLast(tail))
        }
    }

    /**
     * 列表截断：保留前N项和后N项
     */
    private fun truncateList(text: String, budget: Budget): TruncationResult {
        val lines = text.lines()
        if (lines.size <= 30) return TruncationResult(text, truncated = false)

        // 行数收敛进预算（预算放大时保留更多行；默认维持旧的 15+10）
        val keepTop = if (budget.max > maxChars) {
            (KEEP_TOP_LINES * budget.max / maxChars.coerceAtLeast(1)).coerceIn(KEEP_TOP_LINES, 200)
        } else KEEP_TOP_LINES
        val keepBottom = if (budget.max > maxChars) {
            (KEEP_BOTTOM_LINES * budget.max / maxChars.coerceAtLeast(1)).coerceIn(KEEP_BOTTOM_LINES, 150)
        } else KEEP_BOTTOM_LINES

        val truncated = buildString {
            appendLine(lines.take(keepTop).joinToString("\n"))
            appendLine()
            appendLine("[... ${lines.size - keepTop - keepBottom} items omitted, ${lines.size} total ...]")
            appendLine()
            appendLine(lines.takeLast(keepBottom).joinToString("\n"))
        }

        return TruncationResult(withNotice(truncated), truncated = true)
    }

    /**
     * 头部截断：只保留前面部分
     */
    private fun truncateHead(text: String, budget: Budget): TruncationResult {
        val truncated = text.take(budget.max) +
            "\n\n[... truncated at ${budget.max} chars, total ${text.length} chars]"
        return TruncationResult(withNotice(truncated), truncated = true)
    }

    /**
     * 尾部机器可读告示（对话实录 #6 —— 最大的一次误判）：
     *
     * “[... 3350 chars omitted ...]” 曾被模型当成“内容到此为止”，把 20,022 字符
     * 的正文当成摘要交差。TruncationResult.truncated 标志在引擎层被丢弃
     * （LlmMessage.ToolResult 无元数据通道），模型唯一能看到的就是文本本身
     * —— 所以告示必须直接写进文本，并且明说三件事：
     *   1. 省略区还有更多内容（不是结束）；
     *   2. 当前结果不完整，不能据此下结论；
     *   3. 怎么取回被省略的部分（收窄 / 分页 / 指定字段）。
     *
     * 幂等：已含告示的文本（压缩器二次截断时）不重复追加。
     */
    private fun withNotice(text: String): String =
        if (text.contains(TRUNCATION_NOTICE)) text else text + "\n" + TRUNCATION_NOTICE

    companion object {
        /** JSON 数组截断保留的前部项数。 */
        private const val ARRAY_KEEP_HEAD = 5

        /** JSON 数组截断保留的尾部项数。 */
        private const val ARRAY_KEEP_TAIL = 2

        /** 单字段最小预算（更小直接整串截断，避免碎片抖动）。 */
        private const val MIN_FIELD_BUDGET = 32

        /** 短字符串免截断阈值（channel:"proot" 这类元数据直接保留）。 */
        private const val SHORT_STRING_KEEP = 120

        /** 字段级产物的膨胀容忍（键名开销；超限回退整串策略）。 */
        private const val MAX_EXPANSION_FACTOR = 2

        /** 列表截断默认保留头部行数（预算放大时按比例增长）。 */
        private const val KEEP_TOP_LINES = 15

        /** 列表截断默认保留尾部行数。 */
        private const val KEEP_BOTTOM_LINES = 10

        /** 覆盖预算时的默认 head 占比（构造参数异常时的兜底）。 */
        private const val DEFAULT_HEAD_RATIO = 2f / 3f

        /**
         * 截断产物尾部统一告示（见 [withNotice]）。公共常量：EnginePrompts
         * 的 Output Management 段落向模型文档化同一标记形状。
         */
        const val TRUNCATION_NOTICE =
            "[OUTPUT TRUNCATED: MORE content exists in the omitted part — this result " +
                "is INCOMPLETE. Do not conclude from it: narrow the output " +
                "(offset / page / head / grep / max_chars) or paginate to read the rest.]"
    }
}

data class TruncationResult(
    val text: String,
    val truncated: Boolean
)
