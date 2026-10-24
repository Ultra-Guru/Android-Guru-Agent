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
        assertNull(StreamingArgsTracker.forKind(ToolKind.PLAN))
        assertNull(StreamingArgsTracker.forKind(ToolKind.MCP_CUSTOM))
        assertNull(StreamingArgsTracker.forKind(ToolKind.GIT))
    }
}
