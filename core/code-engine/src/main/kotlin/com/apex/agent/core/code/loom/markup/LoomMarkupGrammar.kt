package com.apex.agent.core.code.loom.markup

import com.apex.agent.core.code.loom.LoomArg
import com.apex.agent.core.code.loom.LoomDigestSummary
import com.apex.agent.core.code.loom.LoomInvocation
import com.apex.agent.core.code.loom.LoomOutcomeBlock
import com.apex.agent.core.code.loom.LoomOutcomeStatus
import com.apex.agent.core.code.loom.LoomWovenPlan
import kotlin.random.Random

/**
 * # Loom Markup Grammar — 织造线 in-band XML 协议文法与编解码工具
 *
 * 织造线的工具协议是**文本内嵌标记**（in-band markup）：调用与回执都以
 * XML 块形式织在会话文本里，与任何 provider 的原生函数调用能力解耦——
 * 无函数调用的模型按提示词直接产出标记，有函数调用的模型由桥接层
 * （LoomNativeBridge）把原生增量翻译成同一种标记，循环内只有一种协议。
 *
 * ## 块文法（全部为块级标签，大小写敏感、属性一律双引号）
 *
 * - 思考块：`<deliberate>` 内潜文本 `</deliberate>`——可多段穿插；
 * - 调用块：`<use name="工具id">` 内含若干 `<arg name="参数名">值</arg>`
 *   子元素，`</use>` 闭合；
 * - 回执块：`<outcome_TOKEN name="工具id" status="ok|warn|error">` 内含
 *   `<payload>` 明文 `</payload>`，`</outcome_TOKEN>` 闭合——TOKEN 为
 *   每块随机的 4 位 base36 短码（防伪造 / 防跨轮抄标签）；
 * - 摘要块：`<loom-digest rounds="N">` 摘要正文 `</loom-digest>`；
 * - 布样块：`<loom-plan goal="总目标">` 内含若干 `<step>` 文本
 *   `</step>` 子元素（可选一个 `<note>`），`</loom-plan>` 闭合。
 *
 * ## 实体转义
 *
 * arg 值与 payload 一律 XML 实体转义（五个命名实体 + 数字实体）；
 * 解析侧 [unescape] 逆操作。转义发生在标记边界——正文 Markdown 不动。
 */
object LoomMarkupGrammar {

    // ═══════════════════════════ 标签名常量 ═══════════════════════════

    /** 思考块标签。 */
    const val TAG_DELIBERATE = "deliberate"

    /** 调用块标签。 */
    const val TAG_USE = "use"

    /** 回执块标签前缀（后接随机短码）。 */
    const val OUTCOME_PREFIX = "outcome_"

    /** 摘要块标签。 */
    const val TAG_DIGEST = "loom-digest"

    /** 布样块标签。 */
    const val TAG_PLAN = "loom-plan"

    /** 调用块参数子元素标签。 */
    const val TAG_ARG = "arg"

    /** 回执块载荷子元素标签。 */
    const val TAG_PAYLOAD = "payload"

    /** 布样块步骤子元素标签。 */
    const val TAG_STEP = "step"

    /** 布样块备注子元素标签。 */
    const val TAG_NOTE = "note"

    /** 属性名：工具名。 */
    const val ATTR_NAME = "name"

    /** 属性名：回执状态。 */
    const val ATTR_STATUS = "status"

    /** 属性名：目标。 */
    const val ATTR_GOAL = "goal"

    /** 属性名：覆盖轮次。 */
    const val ATTR_ROUNDS = "rounds"

    /** 短码字符表（base36 小写）。 */
    private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** 短码长度。 */
    private const val TOKEN_LENGTH = 4

    /** 属性段长度上限（超限视为非本协议标签——防病态占位hold住流）。 */
    internal const val ATTR_SECTION_LIMIT = 512

    /** 提示词里出示的协议示例（调用 → 回执的最小闭环）。 */
    const val PROTOCOL_EXAMPLE =
        "<use name=\"CodeRead\"><arg name=\"path\">src/Main.kt</arg></use>\n" +
            "……工具执行后，系统把结果织回下一轮用户消息：\n" +
            "<outcome_ab12 name=\"CodeRead\" status=\"ok\"><payload>文件内容……</payload></outcome_ab12>"

    // ═══════════════════════════ 短码与标签生成 ═══════════════════════════

    /** 新随机短码（4 位 base36 小写；非安全用途——仅去重/防抄）。 */
    fun newOutcomeToken(): String = buildString {
        repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[Random.nextInt(TOKEN_ALPHABET.length)]) }
    }

    /** 调用块开标签。 */
    fun useOpen(name: String): String = "<use " + ATTR_NAME + "=\"" + escape(name) + "\">"

    /** 调用块闭标签。 */
    const val USE_CLOSE = "</use>"

    /** 单个 arg 子元素。 */
    fun argTag(name: String, value: String): String =
        "<arg " + ATTR_NAME + "=\"" + escape(name) + "\">" + escape(value) + "</arg>"

    /** 完整调用块（按 [LoomInvocation] 重建；rawSpan 在场时原样回写）。 */
    fun useBlock(invocation: LoomInvocation): String =
        if (invocation.rawSpan.isNotBlank()) invocation.rawSpan
        else renderUseBlock(invocation.name, invocation.args)

    /** 完整调用块（从结构渲染——rawSpan 缺席时的重建路径）。 */
    fun renderUseBlock(name: String, args: List<LoomArg>): String = buildString {
        append(useOpen(name))
        for (a in args) append(argTag(a.name, a.value))
        append(USE_CLOSE)
    }

    /** 回执块开标签。 */
    fun outcomeOpen(token: String, toolName: String, status: LoomOutcomeStatus): String =
        "<" + OUTCOME_PREFIX + token + " " + ATTR_NAME + "=\"" + escape(toolName) +
            "\" " + ATTR_STATUS + "=\"" + statusText(status) + "\">"

    /** 回执块闭标签。 */
    fun outcomeClose(token: String): String = "</" + OUTCOME_PREFIX + token + ">"

    /** 完整回执块。 */
    fun outcomeBlock(outcome: LoomOutcomeBlock): String = buildString {
        append(outcomeOpen(outcome.token, outcome.toolName, outcome.status))
        append(payloadTag(outcome.payload))
        append(outcomeClose(outcome.token))
    }

    /** 回执块载荷子元素。 */
    fun payloadTag(payload: String): String =
        "<" + TAG_PAYLOAD + ">" + escape(payload) + "</" + TAG_PAYLOAD + ">"

    /** 状态枚举 → 文本属性值。 */
    fun statusText(status: LoomOutcomeStatus): String = when (status) {
        LoomOutcomeStatus.OK -> "ok"
        LoomOutcomeStatus.WARN -> "warn"
        LoomOutcomeStatus.ERROR -> "error"
    }

    /** 摘要块（rounds 属性 + 正文）。 */
    fun digestBlock(digest: LoomDigestSummary): String =
        "<" + TAG_DIGEST + " " + ATTR_ROUNDS + "=\"" + digest.roundsCovered + "\">" +
            escape(digest.text) + "</" + TAG_DIGEST + ">"

    /** 布样块（goal 属性 + step 子元素 + 可选 note）。 */
    fun planBlock(plan: LoomWovenPlan): String = buildString {
        append("<").append(TAG_PLAN).append(" ").append(ATTR_GOAL).append("=\"")
            .append(escape(plan.goal)).append("\">")
        for (s in plan.steps) {
            append("<").append(TAG_STEP).append(">").append(escape(s))
                .append("</").append(TAG_STEP).append(">")
        }
        if (plan.note.isNotBlank()) {
            append("<").append(TAG_NOTE).append(">").append(escape(plan.note))
                .append("</").append(TAG_NOTE).append(">")
        }
        append("</").append(TAG_PLAN).append(">")
    }

    /** 思考块包裹（桥接层把原生 reasoning 增量包成块时使用）。 */
    fun wrapDeliberation(text: String): String =
        "<" + TAG_DELIBERATE + ">" + text + "</" + TAG_DELIBERATE + ">"

    // ═══════════════════════════ XML 实体转义 ═══════════════════════════

    /** XML 转义（五个命名实体；Markdown 正文不经此通道，只作用于标记值域）。 */
    fun escape(raw: String): String = buildString(raw.length) {
        for (c in raw) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /**
     * XML 解转义：命名实体 + 十进制/十六进制数字实体。
     *
     * 防御：未知实体原样保留（宁可模型看到原文也不吞内容）；半截实体
     * （流截断场景）原样保留——增量流不在此层做跨块拼接。
     */
    fun unescape(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                // 无分号或实体名超长：原样吐出 '&'，继续扫描
                out.append(c)
                i++
                continue
            }
            val entity = raw.substring(i + 1, semi)
            when (entity) {
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "amp" -> out.append('&')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> when {
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let { out.append(it.toChar()) }
                            ?: out.append(raw.substring(i, semi + 1))
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let { out.append(it.toChar()) }
                            ?: out.append(raw.substring(i, semi + 1))
                    else -> out.append(raw.substring(i, semi + 1))
                }
            }
            i = semi + 1
        }
        return out.toString()
    }

    // ═══════════════════════════ 已知标签族 ═══════════════════════════

    /** 块级标签种类（解析器的状态机驱动力）。 */
    enum class TagKind { DELIBERATE, USE, OUTCOME, DIGEST, PLAN }

    /**
     * 标签名 → 块种类（outcome_ 前缀族特殊：后接任意短码）。
     *
     * @return null = 不是本协议的标签（按普通文本流过）。
     */
    fun kindOfTag(tagName: String): TagKind? = when {
        tagName == TAG_DELIBERATE -> TagKind.DELIBERATE
        tagName == TAG_USE -> TagKind.USE
        tagName.startsWith(OUTCOME_PREFIX) && tagName.length > OUTCOME_PREFIX.length ->
            TagKind.OUTCOME
        tagName == TAG_DIGEST -> TagKind.DIGEST
        tagName == TAG_PLAN -> TagKind.PLAN
        else -> null
    }

    /** 标签名是否可能是 outcome 族（前缀匹配——holdback 判定用）。 */
    fun couldBeOutcomePrefix(tagNameSoFar: String): Boolean =
        tagNameSoFar.length <= OUTCOME_PREFIX.length &&
            OUTCOME_PREFIX.startsWith(tagNameSoFar)

    /** 已知标签名集合（块级 + 结构子元素；holdback 前缀判定用）。 */
    private val knownNames = listOf(
        TAG_DELIBERATE, TAG_USE, TAG_DIGEST, TAG_PLAN,
        TAG_ARG, TAG_PAYLOAD, TAG_STEP, TAG_NOTE
    )

    /** 结构子元素标签名集合（只在块内有效——PLAIN 态按普通文本放行）。 */
    private val structuralNames = setOf(TAG_ARG, TAG_PAYLOAD, TAG_STEP, TAG_NOTE)

    /** 是否本协议的已知标签（块级或结构子元素）。 */
    fun isKnownOrStructural(tagName: String): Boolean =
        kindOfTag(tagName) != null || tagName in structuralNames

    /** 短码合法性（base36 小写 1..16 位）。 */
    fun isValidOutcomeToken(token: String): Boolean =
        token.isNotEmpty() && token.length <= 16 &&
            token.all { it in 'a'..'z' || it in '0'..'9' }

    /**
     * 标签名前缀判定：[tagNameSoFar] 是否仍可能长成某个已知标签名
     * （或 outcome 族）。
     *
     * holdback 语义：流尾出现 `<deli` 时还不能断定是不是 deliberate，
     * 憋住等下一块；`<div` 与全部已知名分道扬镳 → 立即按文本放行。
     */
    fun mayGrowIntoKnownTag(tagNameSoFar: String): Boolean {
        if (tagNameSoFar.isEmpty()) return true
        for (n in knownNames) {
            if (n.startsWith(tagNameSoFar)) return true
        }
        if (couldBeOutcomePrefix(tagNameSoFar)) return true
        // outcome 短码中段：前缀已完整命中（如 outcome_ab1）
        if (tagNameSoFar.length > OUTCOME_PREFIX.length &&
            tagNameSoFar.startsWith(OUTCOME_PREFIX) &&
            isValidOutcomeToken(tagNameSoFar.substring(OUTCOME_PREFIX.length))
        ) return true
        return false
    }
}
