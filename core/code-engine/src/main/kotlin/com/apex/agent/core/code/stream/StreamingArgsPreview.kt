package com.apex.agent.core.code.stream

/**
 * # Streaming Args Preview — 半截 JSON 参数的实时预览提取
 *
 * ## 背景（参数流式可视化）
 *
 * LLM 流式生成 tool_calls 时参数（arguments JSON）按字节分片到达。
 * [CodeStreamSession] 收到 [com.apex.agent.core.engine.AgentEvent.ToolCallArgsDelta]
 * 后需要**在 JSON 尚未闭合时**就提取出文件内容 / 编辑旧串 / 编辑新串，
 * 让胶囊内嵌代码窗实时展示「正在写入 / 正在编辑」—— 等完整 JSON 再解析
 * 就没有「过程感」了。
 *
 * ## 策略：增量键锚定解码（O(总长)，无重扫）
 *
 * 每个关注键（content / old_string / …）一个 [KeySlot]：
 * 1. **锚定**：在原始字节流里搜索 `"key"` 字面量（只前进不回扫），命中后
 *    校验「结构性位置」——开引号前（忽略空白）必须是 `{` 或 `,`，防止
 *    匹配到字符串值内部转义引号包裹的同名文本（值里写 `"content"` 字样
 *    是真实场景）；随后跳过空白与 `:`，值必须是字符串（`"` 开头）；
 * 2. **解码**：从值起始位置增量解码 JSON 字符串（处理 `\"` `\\` `\n`
 *    `\uXXXX` 等转义）。尾部不完整的转义（孤立的 `\`、位数不足的
 *    `\u12`）**不推进游标**——下个分片到达后从同一位置重试，天然等待；
 * 3. **封口**：未转义的 `"` 即值结束，slot 定格（后续分片不再解码该键）。
 *
 * 容错边界（全部只影响预览质量，绝不抛异常）：
 * - 值非字符串（数字/对象/数组）→ 该键放弃预览；
 * - 键重复出现 → 首个结构性命中生效；
 * - 超长值按 [CODE_CAP] 尾窗截断（预览窗展示的本来就是尾部），
 *   [StreamArgsPreview] 带 truncated 标记供 UI 提示「上文已省略」；
 * - 原始流根本不是 JSON / 键不存在 → 预览为 null（胶囊只显等待态文案）。
 *
 * 兼容口径：CodeEdit 用 `old_string`/`new_string`，Agent 屏 FileEdit 用
 * `edits[].search`/`edits[].replace` —— 两组键名都监听，首个到达者生效。
 *
 * 线程模型：与 CodeStreamSession.onEvent 同线程串行调用，无并发需求。
 */

/** 一次提取的不可变预览快照（进 [StreamToolCall.streamingPreview]）。 */
data class StreamArgsPreview(
    /** 部分目标（path / 命令首行；截断至 80 字符）。 */
    val target: String? = null,
    /** 写文件实时内容（CodeWrite / Write 的 content 值，尾窗截断）。 */
    val writeContent: String? = null,
    /** [writeContent] 是否因超长被截头（UI 显示省略提示）。 */
    val writeContentTruncated: Boolean = false,
    /** 编辑旧串实时内容（old_string / edits[].search；红 = 将删除）。 */
    val editOld: String? = null,
    /** 编辑新串实时内容（new_string / edits[].replace；绿 = 将写入）。 */
    val editNew: String? = null,
    /** [editOld] / [editNew] 是否因超长被截头。 */
    val editTruncated: Boolean = false
) {
    /** 是否有任何可展示的代码预览。 */
    val hasCode: Boolean
        get() = writeContent != null || editOld != null || editNew != null
}

/**
 * 按胶囊族配置的参数追踪器：吃增量分片，吐 [StreamArgsPreview] 快照。
 *
 * [forKind] 返回 null 表示该族不做参数预览（PLAN / MCP 等——参数小且
 * 无「写入过程」可展示，省一个追踪器）。
 */
class StreamingArgsTracker private constructor(
    private val codeSlots: Map<String, KeySlot>,
    private val targetSlot: MultiKeySlot?
) {
    /** 追加一个参数分片（增量解码，O(分片长)）。 */
    fun append(delta: String) {
        if (delta.isEmpty()) return
        raw.append(delta)
        codeSlots.values.forEach { it.advance(raw) }
        targetSlot?.advance(raw)
    }

    /** 当前预览快照（无任何已提取内容时返回 null）。 */
    fun preview(): StreamArgsPreview? {
        // 目标只取首行（命令类）且限长 —— 与终态胶囊 targetOf 摘要同口径
        val target = targetSlot?.firstValue
            ?.lineSequence()?.firstOrNull()?.take(TARGET_MAX)
            ?.takeIf { it.isNotBlank() }
        fun slotOf(candidates: Array<String>): KeySlot? =
            candidates.firstNotNullOfOrNull { codeSlots[it]?.takeIf { slot -> slot.hasValue } }
        val write = slotOf(WRITE_KEYS)
        val old = slotOf(EDIT_OLD_KEYS)
        val new = slotOf(EDIT_NEW_KEYS)
        val preview = StreamArgsPreview(
            target = target,
            writeContent = write?.value?.toString(),
            writeContentTruncated = write?.truncated == true,
            editOld = old?.value?.toString(),
            editNew = new?.value?.toString(),
            editTruncated = (old?.truncated == true) || (new?.truncated == true)
        )
        return preview.takeIf { it.target != null || it.hasCode }
    }

    private val raw = StringBuilder()

    // ═══ 内部：键槽（锚定 + 增量解码状态机）═══

    /**
     * 多候选键的目标槽（path / command / pattern…同族工具只出现其一）：
     * 逐候选推进，首个有值者即目标。
     */
    private class MultiKeySlot(candidates: Array<String>, cap: Int) {
        private val slots = candidates.map { KeySlot(it, cap) }
        fun advance(raw: StringBuilder) = slots.forEach { it.advance(raw) }
        val firstValue: CharSequence?
            get() = slots.firstOrNull { it.hasValue }?.value
    }

    /**
     * 单个关注键的提取状态。
     *
     * 游标不回头：搜索/解码位置只前进；尾部不完整 token（孤立 `\`、
     * `\u12` 位数不足）不推进游标 → 下个分片从原位重试，无需显式挂起态。
     */
    private class KeySlot(val key: String, private val cap: Int) {
        /** `"key"` 字面量搜索位置（未锚定时前进）。 */
        private var searchAt = 0
        /** 值解码位置（锚定后前进；-1 = 未锚定）。 */
        private var decodeAt = -1
        /** 值已封口（未转义闭引号到达）。 */
        private var closed = false
        /** 值不是字符串（放弃该键）。 */
        private var rejected = false
        /** 值超长被截头（UI 省略提示）。 */
        private var headDropped = false
        private val builder = StringBuilder()

        val value: CharSequence get() = builder
        val truncated: Boolean get() = headDropped
        val hasValue: Boolean get() = decodeAt >= 0 && builder.isNotEmpty()

        fun advance(raw: StringBuilder) {
            if (closed || rejected) return
            if (decodeAt < 0 && anchor(raw) == null) return
            decode(raw)
        }

        /** 锚定：搜结构性 `"key"` + 冒号 + 字符串值开头。未就绪返回 null。 */
        private fun anchor(raw: StringBuilder): Int? {
            val pattern = "\"$key\""
            var from = searchAt
            while (true) {
                val hit = raw.indexOf(pattern, from)
                if (hit < 0) {
                    // 未命中：等下片再搜（窗口只留 pattern-1 的回退位）
                    searchAt = (raw.length - pattern.length + 1).coerceAtLeast(0)
                    return null
                }
                // 结构性校验：开引号前（忽略空白）必须是 { 或 ,
                var before = hit - 1
                while (before >= 0 && raw[before].isWhitespace()) before--
                if (before >= 0 && raw[before] != '{' && raw[before] != ',') {
                    from = hit + 1
                    continue
                }
                // 跳过冒号（可能未到 → 等下片）
                var i = hit + pattern.length
                while (i < raw.length && raw[i].isWhitespace()) i++
                if (i >= raw.length) return null
                if (raw[i] != ':') {
                    from = hit + 1
                    continue
                }
                i++
                while (i < raw.length && raw[i].isWhitespace()) i++
                if (i >= raw.length) return null          // 值首字符未到
                if (raw[i] != '"') {
                    rejected = true
                    return null
                }
                decodeAt = i + 1
                return decodeAt
            }
        }

        /** 增量解码 JSON 字符串值（转义感知；尾部不完整转义原地等待）。 */
        private fun decode(raw: StringBuilder) {
            var i = decodeAt
            val end = raw.length
            while (i < end) {
                val c = raw[i]
                if (c == '"') {           // 未转义闭引号 → 值封口
                    decodeAt = i + 1
                    closed = true
                    trimHeadIfNeeded()
                    return
                }
                if (c == '\\') {
                    if (i + 1 >= end) {
                        decodeAt = i          // 孤立反斜杠：存进度再等下片（防重消费）
                        return
                    }
                    when (val esc = raw[i + 1]) {
                        '"' -> { builder.append('"'); i += 2 }
                        '\\' -> { builder.append('\\'); i += 2 }
                        '/' -> { builder.append('/'); i += 2 }
                        'b' -> { builder.append('\b'); i += 2 }
                        'f' -> { builder.append('\u000C'); i += 2 }
                        'n' -> { builder.append('\n'); i += 2 }
                        'r' -> { builder.append('\r'); i += 2 }
                        't' -> { builder.append('\t'); i += 2 }
                        'u' -> {
                            if (i + 6 > end) {
                                decodeAt = i      // \u 位数不足：存进度再等下片（防重消费）
                                return
                            }
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code == null) i += 2  // 坏十六进制：吞掉 \u 容错继续
                            else {
                                builder.append(code.toChar())
                                i += 6
                            }
                        }
                        else -> { builder.append(esc); i += 2 }  // 非法转义容错
                    }
                    continue
                }
                builder.append(c)
                i++
            }
            decodeAt = i
            trimHeadIfNeeded()
        }

        /** 超长截头：预览窗展示尾部，头部丢弃并置 [headDropped]。 */
        private fun trimHeadIfNeeded() {
            if (builder.length > cap + cap / 2) {
                builder.delete(0, builder.length - cap)
                headDropped = true
            }
        }
    }

    companion object {
        private val WRITE_KEYS = arrayOf("content")
        private val EDIT_OLD_KEYS = arrayOf("old_string", "search")
        private val EDIT_NEW_KEYS = arrayOf("new_string", "replace")
        private val TARGET_KEYS = arrayOf("path", "command", "pattern", "query", "keyword")

        /** 写文件实时内容尾窗（24K 字符 ≈ 足够覆盖一屏代码）。 */
        private const val CODE_CAP = 24_000
        /** 编辑旧/新串预览上限（典型 hunk 远小于此）。 */
        private const val EDIT_CAP = 8_000
        /** 目标（路径/命令）限长 —— 与终态 targetOf 摘要口径一致。 */
        private const val TARGET_MAX = 80

        /** 按胶囊族建追踪器；null = 该族不做参数预览。 */
        fun forKind(kind: ToolKind): StreamingArgsTracker? {
            val targetSlot = MultiKeySlot(TARGET_KEYS, TARGET_MAX)
            return when (kind) {
                ToolKind.WRITE_FILE -> StreamingArgsTracker(
                    codeSlots = WRITE_KEYS.associateWith { KeySlot(it, CODE_CAP) },
                    targetSlot = targetSlot
                )
                ToolKind.EDIT_FILE -> StreamingArgsTracker(
                    codeSlots = (EDIT_OLD_KEYS + EDIT_NEW_KEYS)
                        .associateWith { KeySlot(it, EDIT_CAP) },
                    targetSlot = targetSlot
                )
                ToolKind.BASH, ToolKind.READ_FILE, ToolKind.GREP_SEARCH ->
                    StreamingArgsTracker(codeSlots = emptyMap(), targetSlot = targetSlot)
                else -> null
            }
        }
    }
}
