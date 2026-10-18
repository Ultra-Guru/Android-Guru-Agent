package com.apex.agent.core.code.loom.markup

import com.apex.agent.core.code.loom.LoomArg
import com.apex.agent.core.code.loom.LoomDigestSummary
import com.apex.agent.core.code.loom.LoomInvocation
import com.apex.agent.core.code.loom.LoomOutcomeBlock
import com.apex.agent.core.code.loom.LoomOutcomeStatus
import com.apex.agent.core.code.loom.LoomSegment
import com.apex.agent.core.code.loom.LoomWovenPlan

/**
 * # Loom Markup Parser — 织造标记流式增量解析器
 *
 * 把模型输出的文本流（以及回放历史里的用户轮文本）增量切成
 * [LoomSegment] 序列。**单一协议来源**：无论工具调用来自模型手写的
 * in-band 标记，还是桥接层从原生函数调用翻译过来的标记，都过本解析器。
 *
 * ## 状态机
 *
 * ```
 * PLAIN ──<deliberate>──► DELIB ──</deliberate>──► PLAIN
 *   ├─────<use …>────────► USE ────</use>─────────► PLAIN（原子 Invocation）
 *   ├─────<outcome_…>────► OUTCOME ─</outcome_…>──► PLAIN（原子 Outcome）
 *   ├─────<loom-digest>──► DIGEST ──</loom-digest>► PLAIN（原子 Digest）
 *   └─────<loom-plan …>──► PLAN ────</loom-plan>──► PLAIN（原子 Plan）
 * ```
 *
 * USE 内部子状态：HEADER（期望 arg 开标签或 use 闭标签）⇄ IN_ARG
 * （累积 arg 值直到 arg 闭标签）；OUTCOME 内部：HEADER（期望 payload
 * 或闭标签）⇄ IN_PAYLOAD；PLAN 内部：HEADER（期望 step / note / 闭标签）
 * ⇄ IN_STEP / IN_NOTE。
 *
 * ## 跨块截断（holdback）
 *
 * 标签可能被网络分块劈开（`<us` + `e …>`）。解析器在**可能长成已知
 * 标签前缀**时憋住尾部不吐（[LoomMarkupGrammar.mayGrowIntoKnownTag]），
 * 与已知名分道扬镳的 `<`（如 `<div`、`a<b`）立即按普通文本放行——
 * holdback 窗口上限为最长已知标签名 + 属性段上限，不会卡流。
 *
 * ## 防御纪律
 *
 * - 非本协议标签（HTML/Markdown 角括号）原样流过为文本；
 * - 块内出现的未知结构标签：静默丢弃（不击穿、不外抛）；
 * - 流结束仍停在块内（模型输出被截断）：该块整体丢弃（不产出半截
 *   Invocation / Outcome / Plan / Digest），文本与思考增量保留已发射部分；
 * - 重复 arg 名：保留声明序，取值语义由 [LoomInvocation] 层口径统一。
 *
 * 线程契约：单协程顺序调用（feed / finish / reset 不并发）。
 */
class LoomMarkupParser {

    /** 主状态。 */
    private enum class State { PLAIN, DELIB, USE, OUTCOME, DIGEST, PLAN }

    /** USE 内部子状态。 */
    private enum class UseSub { HEADER, IN_ARG }

    /** OUTCOME 内部子状态。 */
    private enum class OutcomeSub { HEADER, IN_PAYLOAD }

    /** PLAN 内部子状态。 */
    private enum class PlanSub { HEADER, IN_STEP, IN_NOTE }

    /** 标签扫描结果。 */
    private sealed interface TagScan {
        /** 完整标签（已含属性与闭角括号；consumed = 消费字符数）。 */
        data class Tag(
            val closing: Boolean,
            val selfClosing: Boolean,
            val name: String,
            val attrs: Map<String, String>,
            val consumed: Int
        ) : TagScan

        /** 尾部仍可能长成已知标签——憋住等下一块。 */
        data object NeedsMore : TagScan

        /** 与全部已知名分道扬镳——按普通文本放行。 */
        data object NotOurs : TagScan
    }

    /** scanUntilCloser 的三态结果。 */
    private enum class Scan { CONSUMED_CLOSER, HOLD_TAIL, EMPTY }

    private var state = State.PLAIN
    private val pending = StringBuilder()

    // ── USE 累积器 ──
    private var useName = ""
    private val useArgs = mutableListOf<LoomArg>()
    private var useSub = UseSub.HEADER
    private var argName = ""
    private val argValue = StringBuilder()

    // ── OUTCOME 累积器 ──
    private var outcomeToken = ""
    private var outcomeTool = ""
    private var outcomeStatus: LoomOutcomeStatus = LoomOutcomeStatus.OK
    private var outcomeSub = OutcomeSub.HEADER
    private val outcomePayload = StringBuilder()

    // ── DIGEST 累积器 ──
    private var digestRounds = 0
    private val digestText = StringBuilder()

    // ── PLAN 累积器 ──
    private var planGoal = ""
    private val planSteps = mutableListOf<StringBuilder>()
    private var planNote = ""
    private var planSub = PlanSub.HEADER
    private val noteBuffer = StringBuilder()

    /** 当前是否停在结构块内（诊断用；finish 前为 true 表示模型输出被截断）。 */
    val inBlock: Boolean
        get() = state != State.PLAIN

    // ═══════════════════════════ 对外 API ═══════════════════════════

    /** 喂入一段增量文本，返回本次解析出的分段（可能为空——全在憋块）。 */
    fun feed(chunk: String): List<LoomSegment> {
        if (chunk.isEmpty()) return emptyList()
        pending.append(chunk)
        return drain()
    }

    /**
     * 流结束：把憋住的尾巴按当前状态收尾。
     *
     * - PLAIN：尾巴整体作为文本发射；
     * - DELIB：尾巴作为思考增量发射（未闭合思考块——保内容不保结构）；
     * - USE / OUTCOME / PLAN / DIGEST：**整块丢弃**（原子块不产半截），
     *   尾巴随之作废——半截调用交给上层以截断语义处理。
     */
    fun finish(): List<LoomSegment> {
        val out = mutableListOf<LoomSegment>()
        when (state) {
            State.PLAIN -> if (pending.isNotEmpty()) {
                out.add(LoomSegment.Text(pending.toString()))
            }
            State.DELIB -> if (pending.isNotEmpty()) {
                out.add(LoomSegment.Deliberation(pending.toString()))
            }
            else -> Unit // 未闭合的结构块：丢弃
        }
        reset()
        return out
    }

    /** 复位（新回合复用同一实例）。 */
    fun reset() {
        state = State.PLAIN
        pending.setLength(0)
        useName = ""
        useArgs.clear()
        useSub = UseSub.HEADER
        argName = ""
        argValue.setLength(0)
        outcomeToken = ""
        outcomeTool = ""
        outcomeStatus = LoomOutcomeStatus.OK
        outcomeSub = OutcomeSub.HEADER
        outcomePayload.setLength(0)
        digestRounds = 0
        digestText.setLength(0)
        planGoal = ""
        planSteps.clear()
        planNote = ""
        planSub = PlanSub.HEADER
        noteBuffer.setLength(0)
    }

    // ═══════════════════════════ 驱动循环 ═══════════════════════════

    private fun drain(): List<LoomSegment> {
        val out = mutableListOf<LoomSegment>()
        var keepGoing = true
        while (keepGoing) {
            keepGoing = when (state) {
                State.PLAIN -> drainPlain(out)
                State.DELIB -> drainDelib(out)
                State.USE -> drainUse(out)
                State.OUTCOME -> drainOutcome(out)
                State.DIGEST -> drainDigest(out)
                State.PLAN -> drainPlan(out)
            }
        }
        return out
    }

    // ── PLAIN：文本流 + 块开标签识别 ──────────────────────────────

    private fun drainPlain(out: MutableList<LoomSegment>): Boolean {
        while (pending.isNotEmpty()) {
            val lt = pending.indexOf('<')
            if (lt < 0) {
                out.add(LoomSegment.Text(pending.toString()))
                pending.setLength(0)
                return false
            }
            if (lt > 0) {
                out.add(LoomSegment.Text(pending.substring(0, lt)))
                pending.delete(0, lt)
            }
            when (val scan = matchTag()) {
                TagScan.NeedsMore -> return false
                TagScan.NotOurs -> {
                    out.add(LoomSegment.Text("<"))
                    pending.deleteCharAt(0)
                }
                is TagScan.Tag -> when {
                    scan.selfClosing -> pending.delete(0, scan.consumed) // 违规自闭块：丢弃
                    scan.closing -> {
                        // 孤儿闭标签（历史回显/模型抄错）：原样文本放行
                        out.add(LoomSegment.Text(pending.substring(0, scan.consumed)))
                        pending.delete(0, scan.consumed)
                    }
                    scan.name == LoomMarkupGrammar.TAG_DELIBERATE -> {
                        pending.delete(0, scan.consumed)
                        state = State.DELIB
                        return true
                    }
                    scan.name == LoomMarkupGrammar.TAG_USE -> {
                        pending.delete(0, scan.consumed)
                        beginUse(scan.attrs[LoomMarkupGrammar.ATTR_NAME] ?: "")
                        state = State.USE
                        return true
                    }
                    scan.name.startsWith(LoomMarkupGrammar.OUTCOME_PREFIX) -> {
                        pending.delete(0, scan.consumed)
                        beginOutcome(
                            token = scan.name.substring(LoomMarkupGrammar.OUTCOME_PREFIX.length),
                            tool = scan.attrs[LoomMarkupGrammar.ATTR_NAME] ?: "",
                            status = LoomOutcomeStatus.fromText(
                                scan.attrs[LoomMarkupGrammar.ATTR_STATUS]
                            )
                        )
                        state = State.OUTCOME
                        return true
                    }
                    scan.name == LoomMarkupGrammar.TAG_DIGEST -> {
                        pending.delete(0, scan.consumed)
                        digestRounds =
                            scan.attrs[LoomMarkupGrammar.ATTR_ROUNDS]?.trim()?.toIntOrNull() ?: 0
                        digestText.setLength(0)
                        state = State.DIGEST
                        return true
                    }
                    scan.name == LoomMarkupGrammar.TAG_PLAN -> {
                        pending.delete(0, scan.consumed)
                        planGoal = scan.attrs[LoomMarkupGrammar.ATTR_GOAL] ?: ""
                        planSteps.clear()
                        planNote = ""
                        planSub = PlanSub.HEADER
                        state = State.PLAN
                        return true
                    }
                    else -> {
                        // 结构子元素出现在 PLAIN（arg/payload/step/note）：文本放行
                        out.add(LoomSegment.Text(pending.substring(0, scan.consumed)))
                        pending.delete(0, scan.consumed)
                    }
                }
            }
        }
        return false
    }

    // ── DELIB：思考增量 + 闭标签 ──────────────────────────────────

    private fun drainDelib(out: MutableList<LoomSegment>): Boolean =
        when (scanUntilCloser(closer(LoomMarkupGrammar.TAG_DELIBERATE)) {
            out.add(LoomSegment.Deliberation(it))
        }) {
            Scan.CONSUMED_CLOSER -> {
                state = State.PLAIN
                true
            }
            Scan.HOLD_TAIL, Scan.EMPTY -> false
        }

    // ── USE：arg 子元素装配 + 原子调用产出 ────────────────────────

    private fun beginUse(name: String) {
        useName = name
        useArgs.clear()
        useSub = UseSub.HEADER
        argName = ""
        argValue.setLength(0)
    }

    private fun drainUse(out: MutableList<LoomSegment>): Boolean {
        while (true) {
            when (useSub) {
                UseSub.IN_ARG -> when (
                    scanUntilCloser(closer(LoomMarkupGrammar.TAG_ARG)) {
                        argValue.append(it)
                    }
                ) {
                    Scan.CONSUMED_CLOSER -> {
                        if (argName.isNotEmpty()) {
                            useArgs.add(
                                LoomArg(
                                    name = argName,
                                    value = LoomMarkupGrammar.unescape(argValue.toString())
                                )
                            )
                        }
                        argName = ""
                        argValue.setLength(0)
                        useSub = UseSub.HEADER
                    }
                    Scan.HOLD_TAIL, Scan.EMPTY -> return false
                }
                UseSub.HEADER -> {
                    if (!skipLooseText()) return false
                    when (val scan = matchTag()) {
                        TagScan.NeedsMore -> return false
                        TagScan.NotOurs -> pending.deleteCharAt(0) // '<' 作废一格继续
                        is TagScan.Tag -> when {
                            scan.closing && scan.name == LoomMarkupGrammar.TAG_USE -> {
                                pending.delete(0, scan.consumed)
                                out.add(
                                    LoomSegment.Invocation(
                                        LoomInvocation(name = useName, args = useArgs.toList())
                                    )
                                )
                                state = State.PLAIN
                                return true
                            }
                            !scan.closing && scan.name == LoomMarkupGrammar.TAG_ARG -> {
                                pending.delete(0, scan.consumed)
                                argName = scan.attrs[LoomMarkupGrammar.ATTR_NAME] ?: ""
                                if (scan.selfClosing) {
                                    if (argName.isNotEmpty()) useArgs.add(LoomArg(argName, ""))
                                    argName = ""
                                } else {
                                    argValue.setLength(0)
                                    useSub = UseSub.IN_ARG
                                }
                            }
                            else -> pending.delete(0, scan.consumed) // 未知结构：丢弃
                        }
                    }
                }
            }
        }
    }

    // ── OUTCOME：payload 装配 + 原子回执产出 ──────────────────────

    private fun beginOutcome(token: String, tool: String, status: LoomOutcomeStatus) {
        outcomeToken = token
        outcomeTool = tool
        outcomeStatus = status
        outcomeSub = OutcomeSub.HEADER
        outcomePayload.setLength(0)
    }

    private fun drainOutcome(out: MutableList<LoomSegment>): Boolean {
        while (true) {
            when (outcomeSub) {
                OutcomeSub.IN_PAYLOAD -> when (
                    scanUntilCloser(closer(LoomMarkupGrammar.TAG_PAYLOAD)) {
                        outcomePayload.append(it)
                    }
                ) {
                    Scan.CONSUMED_CLOSER -> outcomeSub = OutcomeSub.HEADER
                    Scan.HOLD_TAIL, Scan.EMPTY -> return false
                }
                OutcomeSub.HEADER -> {
                    if (!skipLooseText()) return false
                    when (val scan = matchTag()) {
                        TagScan.NeedsMore -> return false
                        TagScan.NotOurs -> pending.deleteCharAt(0)
                        is TagScan.Tag -> when {
                            scan.closing &&
                                scan.name == LoomMarkupGrammar.OUTCOME_PREFIX + outcomeToken -> {
                                pending.delete(0, scan.consumed)
                                out.add(
                                    LoomSegment.Outcome(
                                        LoomOutcomeBlock(
                                            token = outcomeToken,
                                            toolName = outcomeTool,
                                            status = outcomeStatus,
                                            payload = LoomMarkupGrammar.unescape(
                                                outcomePayload.toString()
                                            )
                                        )
                                    )
                                )
                                state = State.PLAIN
                                return true
                            }
                            !scan.closing &&
                                scan.name == LoomMarkupGrammar.TAG_PAYLOAD &&
                                !scan.selfClosing -> {
                                pending.delete(0, scan.consumed)
                                outcomeSub = OutcomeSub.IN_PAYLOAD
                            }
                            else -> pending.delete(0, scan.consumed)
                        }
                    }
                }
            }
        }
    }

    // ── DIGEST：正文累积 + 原子摘要产出 ───────────────────────────

    private fun drainDigest(out: MutableList<LoomSegment>): Boolean =
        when (
            scanUntilCloser(closer(LoomMarkupGrammar.TAG_DIGEST)) {
                digestText.append(it)
            }
        ) {
            Scan.CONSUMED_CLOSER -> {
                out.add(
                    LoomSegment.Digest(
                        LoomDigestSummary(
                            text = LoomMarkupGrammar.unescape(digestText.toString()),
                            roundsCovered = digestRounds
                        )
                    )
                )
                state = State.PLAIN
                true
            }
            Scan.HOLD_TAIL, Scan.EMPTY -> false
        }

    // ── PLAN：step/note 装配 + 原子布样产出 ───────────────────────

    private fun drainPlan(out: MutableList<LoomSegment>): Boolean {
        while (true) {
            when (planSub) {
                PlanSub.IN_STEP -> when (
                    scanUntilCloser(closer(LoomMarkupGrammar.TAG_STEP)) {
                        if (planSteps.isEmpty()) planSteps.add(StringBuilder())
                        planSteps.last().append(it)
                    }
                ) {
                    Scan.CONSUMED_CLOSER -> planSub = PlanSub.HEADER
                    Scan.HOLD_TAIL, Scan.EMPTY -> return false
                }
                PlanSub.IN_NOTE -> when (
                    scanUntilCloser(closer(LoomMarkupGrammar.TAG_NOTE)) {
                        noteBuffer.append(it)
                    }
                ) {
                    Scan.CONSUMED_CLOSER -> {
                        planNote = LoomMarkupGrammar.unescape(noteBuffer.toString())
                        planSub = PlanSub.HEADER
                    }
                    Scan.HOLD_TAIL, Scan.EMPTY -> return false
                }
                PlanSub.HEADER -> {
                    if (!skipLooseText()) return false
                    when (val scan = matchTag()) {
                        TagScan.NeedsMore -> return false
                        TagScan.NotOurs -> pending.deleteCharAt(0)
                        is TagScan.Tag -> when {
                            scan.closing && scan.name == LoomMarkupGrammar.TAG_PLAN -> {
                                pending.delete(0, scan.consumed)
                                out.add(
                                    LoomSegment.Plan(
                                        LoomWovenPlan(
                                            goal = planGoal,
                                            steps = planSteps.map { it.toString() },
                                            note = planNote
                                        )
                                    )
                                )
                                state = State.PLAIN
                                return true
                            }
                            !scan.closing &&
                                scan.name == LoomMarkupGrammar.TAG_STEP &&
                                !scan.selfClosing -> {
                                pending.delete(0, scan.consumed)
                                planSteps.add(StringBuilder())
                                planSub = PlanSub.IN_STEP
                            }
                            !scan.closing &&
                                scan.name == LoomMarkupGrammar.TAG_NOTE &&
                                !scan.selfClosing -> {
                                pending.delete(0, scan.consumed)
                                noteBuffer.setLength(0)
                                planSub = PlanSub.IN_NOTE
                            }
                            else -> pending.delete(0, scan.consumed)
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════ 通用扫描 ═══════════════════════════

    /** 闭标签文本构造（形如 闭合标签文本）。 */
    private fun closer(name: String): String = "</" + name + ">"

    /**
     * 跳过块内散文本（空白与协议外噪声）：吞到下一个 '<'。
     *
     * @return false = pending 已空（需要更多输入）。
     */
    private fun skipLooseText(): Boolean {
        skipWhitespace()
        if (pending.isEmpty()) return false
        if (pending[0] == '<') return true
        val next = pending.indexOf('<')
        if (next < 0) {
            pending.setLength(0)
            return false
        }
        pending.delete(0, next)
        return true
    }

    /** 跳过 pending 头部空白。 */
    private fun skipWhitespace() {
        var i = 0
        while (i < pending.length && pending[i].isWhitespace()) i++
        if (i > 0) pending.delete(0, i)
    }

    /**
     * 扫描直到闭标签：闭标签前的内容经 [sink] 吐出（增量），闭标签消费掉。
     *
     * - 尾部是闭标签前缀（跨块截断）→ HOLD_TAIL（憋住等下一块）；
     * - 憋空 → EMPTY；
     * - 内容里出现的 '<' 不是闭标签开头 → 按字面量吐给 [sink] 继续。
     */
    private fun scanUntilCloser(closer: String, sink: (String) -> Unit): Scan {
        while (pending.isNotEmpty()) {
            val lt = pending.indexOf('<')
            if (lt < 0) {
                sink(pending.toString())
                pending.setLength(0)
                return Scan.EMPTY
            }
            if (lt > 0) {
                sink(pending.substring(0, lt))
                pending.delete(0, lt)
            }
            if (regionMatchesAt(0, closer)) {
                pending.delete(0, closer.length)
                return Scan.CONSUMED_CLOSER
            }
            if (pending.length < closer.length && isPrefixAt(0, closer)) {
                return Scan.HOLD_TAIL
            }
            sink("<")
            pending.deleteCharAt(0)
        }
        return Scan.EMPTY
    }

    /** pending[offset, offset+s.length) 是否逐一等于 [s]。 */
    private fun regionMatchesAt(offset: Int, s: String): Boolean {
        if (pending.length - offset < s.length) return false
        for (i in s.indices) {
            if (pending[offset + i] != s[i]) return false
        }
        return true
    }

    /** pending 自 [offset] 起是否是 [s] 的（可能更短的）前缀。 */
    private fun isPrefixAt(offset: Int, s: String): Boolean {
        val n = minOf(pending.length - offset, s.length)
        for (i in 0 until n) {
            if (pending[offset + i] != s[i]) return false
        }
        return true
    }

    /** 标签名合法字符（字母 / 数字 / 下划线 / 连字符）。 */
    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    /**
     * 从 pending 头部（必为 '<'）扫描一个完整标签。
     *
     * - 名字没读断且仍可能长成已知名 → NeedsMore；
     * - 名字读断但不是本协议名（或名字为空）→ NotOurs；
     * - 名字已知但闭角括号未到（属性段未超限）→ NeedsMore；超限 → NotOurs；
     * - 完整读出（含属性、自闭斜杠）→ Tag。
     */
    private fun matchTag(): TagScan {
        var i = 1
        val closing = i < pending.length && pending[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < pending.length && isNameChar(pending[i])) i++
        if (i >= pending.length) {
            val nameSoFar = pending.substring(nameStart)
            return if (LoomMarkupGrammar.mayGrowIntoKnownTag(nameSoFar)) {
                TagScan.NeedsMore
            } else {
                TagScan.NotOurs
            }
        }
        val name = pending.substring(nameStart, i)
        if (name.isEmpty()) return TagScan.NotOurs
        if (!LoomMarkupGrammar.isKnownOrStructural(name)) return TagScan.NotOurs
        var gt = i
        var scanned = 0
        while (gt < pending.length && pending[gt] != '>') {
            gt++
            scanned++
        }
        if (gt >= pending.length) {
            return if (scanned >= LoomMarkupGrammar.ATTR_SECTION_LIMIT) {
                TagScan.NotOurs
            } else {
                TagScan.NeedsMore
            }
        }
        val selfClosing = gt > i && pending[gt - 1] == '/'
        val attrs = if (gt > i) {
            parseAttrs(i, if (selfClosing) gt - 1 else gt)
        } else {
            emptyMap()
        }
        return TagScan.Tag(
            closing = closing,
            selfClosing = selfClosing,
            name = name,
            attrs = attrs,
            consumed = gt + 1
        )
    }

    /** 属性段解析（[from, until) 之间；k="v" / k='v' / k=v 三态容错）。 */
    private fun parseAttrs(from: Int, until: Int): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var i = from
        while (i < until) {
            while (i < until && pending[i].isWhitespace()) i++
            if (i >= until) break
            val keyStart = i
            while (i < until && pending[i] != '=' && !pending[i].isWhitespace()) i++
            val key = pending.substring(keyStart, i)
            while (i < until && pending[i].isWhitespace()) i++
            if (i < until && pending[i] == '=') {
                i++
                while (i < until && pending[i].isWhitespace()) i++
                if (i < until && (pending[i] == '"' || pending[i] == '\'')) {
                    val quote = pending[i]
                    i++
                    val valueStart = i
                    while (i < until && pending[i] != quote) i++
                    val value = pending.substring(valueStart, i)
                    if (i < until) i++ // 跳过闭引号
                    if (key.isNotEmpty()) map[key] = LoomMarkupGrammar.unescape(value)
                } else {
                    val valueStart = i
                    while (i < until && !pending[i].isWhitespace()) i++
                    if (key.isNotEmpty()) {
                        map[key] = LoomMarkupGrammar.unescape(pending.substring(valueStart, i))
                    }
                }
            } else if (key.isNotEmpty()) {
                map[key] = ""
            }
        }
        return map
    }
}
