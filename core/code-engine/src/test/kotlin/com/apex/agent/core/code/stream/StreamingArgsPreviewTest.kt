package com.apex.agent.core.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamingArgsTracker] 半截 JSON 参数的增量提取测试。
 *
 * 覆盖：写内容/编辑旧新串的分片累积 / 转义解码（含跨分片的不完整
 * `\uXXXX` 与代理对）/ 结构性锚定防字符串内同名文本误命中 / 非字符串
 * 值与重复键容错 / 非 JSON 流放弃 / 超长尾窗截断 / 命令目标提取 /
 * 非预览族返回 null。
 */
class StreamingArgsPreviewTest {

    private fun tracker(kind: ToolKind): StreamingArgsTracker =
        requireNotNull(StreamingArgsTracker.forKind(kind))

    @Test
    fun `write content grows across fragments`() {
        val t = tracker(ToolKind.WRITE_FILE)
        t.append("""{"path":"src/App.kt","content":"fun main() {""")
        // 半截值：未闭合 → 预览已可见（这正是「实时写入」的语义）
        t.append("""\n    println(1)""")
        t.append("""\n}"}""")
        val p = t.preview()!!
        assertEquals("src/App.kt", p.target)
        assertEquals("fun main() {\n    println(1)\n}", p.writeContent)
        assertFalse(p.writeContentTruncated)
        assertTrue(p.hasCode)
    }

    @Test
    fun `edit old and new strings extract independently`() {
        val t = tracker(ToolKind.EDIT_FILE)
        t.append("""{"path":"a.kt","old_string":"foo""")
        t.append("""bar","new_string":"baz""")
        t.append("""qux"}""")
        val p = t.preview()!!
        assertEquals("a.kt", p.target)
        assertEquals("foobar", p.editOld)
        assertEquals("bazqux", p.editNew)
    }

    @Test
    fun `agent fileedit search-replace keys are honored`() {
        // Agent 屏 FileEdit 的 edits[].search / edits[].replace 键名兼容
        val t = tracker(ToolKind.EDIT_FILE)
        t.append("""{"path":"b.kt","edits":[{"search":"old""")
        t.append("""code","replace":"new code"}]}""")
        val p = t.preview()!!
        assertEquals("oldcode", p.editOld)
        assertEquals("new code", p.editNew)
    }

    @Test
    fun `incomplete unicode escape waits for next fragment`() {
        val t = tracker(ToolKind.WRITE_FILE)
        // \uD83 只到 3 位十六进制：不得吞半，等下片补齐
        t.append("{\"content\":\"ok \\uD83")
        val early = t.preview()!!
        assertEquals("ok ", early.writeContent)
        t.append("D\\uDE0D!\"}")
        val p = t.preview()!!
        assertEquals("ok 😍!", p.writeContent)
    }

    @Test
    fun `non-string value rejects that key but keeps target`() {
        val t = tracker(ToolKind.WRITE_FILE)
        t.append("""{"path":"p.kt","content":123}""")
        val p = t.preview()!!
        assertEquals("p.kt", p.target)
        assertNull(p.writeContent)
        assertFalse(p.hasCode)
    }

    @Test
    fun `same key inside a string value is not anchored`() {
        // 值里写 "content" 字样（转义引号包裹）不得劫持锚定——结构性校验
        // （开引号前必须 { 或 ,）先命中真键
        val t = tracker(ToolKind.WRITE_FILE)
        t.append("""{"note":"see \"content\" docs","content":"real code"}""")
        val p = t.preview()!!
        assertEquals("real code", p.writeContent)
    }

    @Test
    fun `duplicate key keeps first occurrence`() {
        val t = tracker(ToolKind.WRITE_FILE)
        t.append("""{"content":"first","content":"second"}""")
        val p = t.preview()!!
        assertEquals("first", p.writeContent)
    }

    @Test
    fun `non-json stream yields no preview`() {
        val t = tracker(ToolKind.WRITE_FILE)
        t.append("not json at all, just tokens ")
        t.append("still not json")
        assertNull(t.preview())
    }

    @Test
    fun `oversized content keeps tail window with truncated flag`() {
        val t = tracker(ToolKind.WRITE_FILE)
        // CODE_CAP=24K，触发阈值 cap*1.5=36K —— 40K 必然截头留尾窗
        val big = "x".repeat(40_000)
        t.append("{\"content\":\"$big\"}")
        val p = t.preview()!!
        assertTrue(p.writeContentTruncated)
        // 尾窗语义：预览展示的本来就是尾部（写入增量永远在尾部）
        assertEquals(24_000, p.writeContent!!.length)
        assertTrue(p.writeContent!!.all { it == 'x' })
    }

    @Test
    fun `bash command target extracts across fragments`() {
        val t = tracker(ToolKind.BASH)
        t.append("""{"command":"./gradlew assembleDebug""")
        t.append(""" --refresh-dependencies","timeout":60}""")
        val p = t.preview()!!
        assertEquals("./gradlew assembleDebug --refresh-dependencies", p.target)
        assertFalse(p.hasCode)
    }

    @Test
    fun `tracker replay of full buffered stream reproduces preview`() {
        // 名字晚到的 provider：会话层在名字到达后建槽并回放全量缓冲分片——
        // 回放（单次 append 大块）与逐片喂入的提取结果必须一致（O(n) 等价）
        val piecemeal = tracker(ToolKind.WRITE_FILE)
        """{"content":"early bytes","path":"c.kt"}""".chunked(7).forEach { piecemeal.append(it) }
        val byFragments = piecemeal.preview()!!
        val replayed = tracker(ToolKind.WRITE_FILE)
        replayed.append("""{"content":"early bytes","path":"c.kt"}""")
        val byBulk = replayed.preview()!!
        assertEquals(byFragments.writeContent, byBulk.writeContent)
        assertEquals(byFragments.target, byBulk.target)
        assertEquals("early bytes", byBulk.writeContent)
        assertEquals("c.kt", byBulk.target)
    }

    @Test
    fun `non-preview kinds get no tracker`() {
        assertNull(StreamingArgsTracker.forKind(ToolKind.MCP_CUSTOM))
        assertNull(StreamingArgsTracker.forKind(ToolKind.GIT))
    }

    @Test
    fun `web search query streams into target`() {
        val t = tracker(ToolKind.WEB_SEARCH)
        // 半截 JSON：query 值未闭合时 target 应已有部分内容（流水感）
        t.append("""{"query": "今天上海的""")
        val partial = t.preview()
        assertEquals("今天上海的", partial?.target)

        t.append("""天气"}""")
        val done = t.preview()
        assertEquals("今天上海的天气", done?.target)
        assertFalse(done?.hasCode == true)
    }

    @Test
    fun `web search alternate arg keys still stream`() {
        // 候选查询键（query/keyword/… 均在 TARGET_KEYS）——换键名流水不中断
        val t = tracker(ToolKind.WEB_SEARCH)
        t.append("""{"keyword": "compose streaming"}""")
        assertEquals("compose streaming", t.preview()?.target)
    }

    // ── PLAN 族：todos 随参数生成逐条落地 ──────────────────────

    @Test
    fun `plan todos stream item by item from inline array`() {
        // 三段人工切分（substring 规避原始字符串尾引号歧义）：第一条闭合后
        // 预览即出 1 条，第二条闭合出 2 条，全量到达出 3 条 ——
        // 「随生成逐条落地、不等参数 JSON 结束」的行为断言
        val args = """{"todos": [{"content": "克隆仓库", "status": "completed"}, {"content": "修复参数", "status": "in_progress", "priority": "high"}, {"content": "提交 PR", "status": "pending"}]}"""
        val cut1 = args.indexOf("修复参数")
        val cut2 = args.indexOf("提交 PR")

        val t = tracker(ToolKind.PLAN)
        t.append(args.substring(0, cut1))
        val early = t.preview()!!
        assertEquals(1, early.todos?.size)
        assertTrue(early.hasTodos)
        assertEquals("1 项", early.target)

        t.append(args.substring(cut1, cut2))
        val mid = t.preview()!!
        assertEquals(2, mid.todos!!.size)
        assertEquals("修复参数", mid.todos!![1].content)
        assertEquals("in_progress", mid.todos!![1].status)
        assertEquals("high", mid.todos!![1].priority)

        t.append(args.substring(cut2))
        val done = t.preview()!!
        assertEquals(3, done.todos!!.size)
        assertEquals("3 项", done.target)
    }

    @Test
    fun `plan todos accept string-encoded array with brace escapes`() {
        // 编码形态：值是字符串，内部转义解析后条目逐个落地；字符串值里的
        // 花括号（a}b{c）不得破坏对象扫描。chunked 模拟逐 token 分片。
        val args =
            """{"todos": "[{\"content\": \"a}b{c\", \"status\": \"pending\"}, {\"content\": \"x\"}]"}"""
        val t = tracker(ToolKind.PLAN)
        args.chunked(9).forEach { t.append(it) }
        val p = t.preview()!!
        assertEquals(2, p.todos?.size)
        assertEquals("a}b{c", p.todos!![0].content)
        assertEquals("pending", p.todos!![0].status)
        assertEquals("x", p.todos!![1].content)
        assertEquals("medium", p.todos!![1].priority)
        assertEquals("2 项", p.target)
    }

    @Test
    fun `plan todos incomplete unicode escape waits for next fragment`() {
        val t = tracker(ToolKind.PLAN)
        // \u4e2 只到 3 位十六进制：不得吞半，等下片补齐（编码形态解码器）
        t.append("""{"todos": "[{\"content\": \"修复\u4e2""")
        // 半截转义：对象未闭合 → 零条目 → 无预览（等待，不硬建胶囊）
        assertNull(t.preview())
        t.append("""d\", \"status\": \"in_progress\"}]"}""")
        val done = t.preview()!!
        assertEquals(1, done.todos?.size)
        assertEquals("修复中", done.todos!![0].content)
    }

    @Test
    fun `plan non-array todos value yields no preview`() {
        val t = tracker(ToolKind.PLAN)
        t.append("""{"todos": 42}""")
        assertNull(t.preview())
    }

    @Test
    fun `plan key inside another string is not anchored`() {
        // 其他字符串值里写 "todos" 字样不得劫持锚定（结构性校验）
        val t = tracker(ToolKind.PLAN)
        t.append("""{"note": "see \"todos\" docs", "todos": [{"content": "real", "status": "pending"}]}""")
        val p = t.preview()!!
        assertEquals(1, p.todos?.size)
        assertEquals("real", p.todos!![0].content)
    }
}
