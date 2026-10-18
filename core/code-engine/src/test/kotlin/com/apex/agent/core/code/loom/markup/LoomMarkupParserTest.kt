package com.apex.agent.core.code.loom.markup

import com.apex.agent.core.code.loom.LoomOutcomeStatus
import com.apex.agent.core.code.loom.LoomSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomMarkupParser 流式解析测试：
 *
 * - 纯文本 / 未知标签放行；
 * - 六类块的完整解析（deliberate / use / outcome / digest / plan）；
 * - 跨块截断（逐字符喂入——holdback 全路径覆盖）；
 * - 实体转义 / 重复 arg / 自闭 arg / 孤儿闭标签；
 * - 未闭合块的 finish 语义（原子块丢弃、思考内容保留）。
 */
class LoomMarkupParserTest {

    // ── 纯文本与放行 ───────────────────────────────────────────────

    @Test
    fun `plain text flows as text segments`() {
        val p = LoomMarkupParser()
        val out = p.feed("你好，世界。Markdown 加粗与列表：\n- 一\n- 二")
        assertEquals(
            listOf(LoomSegment.Text("你好，世界。Markdown 加粗与列表：\n- 一\n- 二")),
            out
        )
        assertEquals(emptyList<LoomSegment>(), p.finish())
    }

    @Test
    fun `unknown tags flow through as text`() {
        val p = LoomMarkupParser()
        val out = p.feed("前文 <div class=\"x\">内容</div> 后文")
        // '<' 后继与已知名分道扬镳 → 尖括号按字面量放行（切分边界无关紧要，
        // 只验证拼回原文）
        val joined = out.filterIsInstance<LoomSegment.Text>().joinToString("") { it.delta }
        assertEquals("前文 <div class=\"x\">内容</div> 后文", joined)
    }

    @Test
    fun `markdown angle brackets flow as text`() {
        val p = LoomMarkupParser()
        val out = p.feed("a<b 且 x<3，列表: <ul><li>")
        val joined = out.filterIsInstance<LoomSegment.Text>().joinToString("") { it.delta }
        assertEquals("a<b 且 x<3，列表: <ul><li>", joined)
        assertFalse(p.inBlock)
    }

    // ── deliberate 块 ──────────────────────────────────────────────

    @Test
    fun `deliberate block emits streaming deliberation segments`() {
        val p = LoomMarkupParser()
        val out = p.feed("开头<deliberate>先想一下</deliberate>结尾")
        assertEquals(
            listOf(
                LoomSegment.Text("开头"),
                LoomSegment.Deliberation("先想一下"),
                LoomSegment.Text("结尾")
            ),
            out
        )
    }

    @Test
    fun `deliberate content with inner tags stays as deliberation text`() {
        val p = LoomMarkupParser()
        val out = p.feed("<deliberate>比较 方案A 与 方案B</deliberate>")
        val joined = out.filterIsInstance<LoomSegment.Deliberation>()
            .joinToString("") { it.delta }
        assertEquals("比较 方案A 与 方案B", joined)
    }

    // ── use 块 ─────────────────────────────────────────────────────

    @Test
    fun `use block parses into atomic invocation with ordered args`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "说明文字\n" +
                "<use name=\"CodeRead\">\n" +
                "  <arg name=\"path\">src/Main.kt</arg>\n" +
                "  <arg name=\"offset\">120</arg>\n" +
                "</use>\n后续"
        )
        val texts = out.filterIsInstance<LoomSegment.Text>().joinToString("") { it.delta }
        assertTrue(texts.contains("说明文字"))
        assertTrue(texts.contains("后续"))
        val inv = out.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals("CodeRead", inv.name)
        assertEquals(2, inv.args.size)
        assertEquals("path", inv.args[0].name)
        assertEquals("src/Main.kt", inv.args[0].value)
        assertEquals("offset", inv.args[1].name)
        assertEquals("120", inv.args[1].value)
    }

    @Test
    fun `use arg values are entity-unescaped`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "<use name=\"CodeWrite\"><arg name=\"content\">a &lt; b &amp;&amp; c &#65;&#x42;</arg></use>"
        )
        val inv = out.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals("a < b && c AB", inv.arg("content"))
    }

    @Test
    fun `duplicate arg names preserve declaration order`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "<use name=\"T\"><arg name=\"k\">一</arg><arg name=\"k\">二</arg></use>"
        )
        val inv = out.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals(listOf("一", "二"), inv.args.filter { it.name == "k" }.map { it.value })
        // 首个命中语义：arg() 取第一个
        assertEquals("一", inv.arg("k"))
        // JSON 重建取后者（覆盖语义）
        assertTrue(inv.argumentsJson().contains("二"))
    }

    @Test
    fun `self closing arg yields empty value`() {
        val p = LoomMarkupParser()
        val out = p.feed("<use name=\"T\"><arg name=\"q\"/></use>")
        val inv = out.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals("", inv.arg("q"))
    }

    @Test
    fun `use block with missing name attr yields empty-name invocation`() {
        val p = LoomMarkupParser()
        val out = p.feed("<use><arg name=\"x\">1</arg></use>")
        val inv = out.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals("", inv.name)
        assertEquals("1", inv.arg("x"))
    }

    @Test
    fun `empty use invocation carries no args`() {
        val p = LoomMarkupParser()
        val out = p.feed("<use name=\"T\"></use>")
        val inv = out.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals("T", inv.name)
        assertTrue(inv.args.isEmpty())
        assertEquals("{}", inv.argumentsJson())
    }

    // ── outcome 块 ─────────────────────────────────────────────────

    @Test
    fun `outcome block parses token name status and payload`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "<outcome_ab12 name=\"CodeRead\" status=\"ok\">" +
                "<payload>第一行\n第二行 &lt;缩进&gt;</payload></outcome_ab12>"
        )
        val oc = out.filterIsInstance<LoomSegment.Outcome>().single().outcome
        assertEquals("ab12", oc.token)
        assertEquals("CodeRead", oc.toolName)
        assertEquals(LoomOutcomeStatus.OK, oc.status)
        assertEquals("第一行\n第二行 <缩进>", oc.payload)
    }

    @Test
    fun `outcome dirty status folds to error`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "<outcome_zz99 name=\"T\" status=\"weird\"><payload>x</payload></outcome_zz99>"
        )
        assertEquals(
            LoomOutcomeStatus.ERROR,
            out.filterIsInstance<LoomSegment.Outcome>().single().outcome.status
        )
    }

    @Test
    fun `outcome closer token mismatch drops block`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "<outcome_ab12 name=\"T\" status=\"ok\"><payload>x</payload></outcome_ff77>"
        )
        // 闭标签短码不匹配 → 不产出 Outcome；无正确闭标签 → finish 丢弃
        assertTrue(out.filterIsInstance<LoomSegment.Outcome>().isEmpty())
        assertTrue(p.finish().filterIsInstance<LoomSegment.Outcome>().isEmpty())
    }

    @Test
    fun `stray outcome closer in plain flows as text`() {
        val p = LoomMarkupParser()
        val out = p.feed("回显 </outcome_ab12> 杂音")
        val joined = out.filterIsInstance<LoomSegment.Text>().joinToString("") { it.delta }
        assertEquals("回显 </outcome_ab12> 杂音", joined)
    }

    // ── digest / plan 块 ───────────────────────────────────────────

    @Test
    fun `digest block parses rounds and text`() {
        val p = LoomMarkupParser()
        val out = p.feed("<loom-digest rounds=\"7\">已读三文件，改了两处。</loom-digest>")
        val dg = out.filterIsInstance<LoomSegment.Digest>().single().digest
        assertEquals(7, dg.roundsCovered)
        assertEquals("已读三文件，改了两处。", dg.text)
    }

    @Test
    fun `digest dirty rounds folds to zero`() {
        val p = LoomMarkupParser()
        val out = p.feed("<loom-digest rounds=\"abc\">x</loom-digest>")
        assertEquals(0, out.filterIsInstance<LoomSegment.Digest>().single().digest.roundsCovered)
    }

    @Test
    fun `plan block parses goal steps and note`() {
        val p = LoomMarkupParser()
        val out = p.feed(
            "<loom-plan goal=\"修复登录崩溃\">" +
                "<step>复现并定位</step><step>修补并验证</step>" +
                "<note>风险：低</note></loom-plan>"
        )
        val plan = out.filterIsInstance<LoomSegment.Plan>().single().plan
        assertEquals("修复登录崩溃", plan.goal)
        assertEquals(listOf("复现并定位", "修补并验证"), plan.steps)
        assertEquals("风险：低", plan.note)
        assertTrue(plan.isPresentable())
    }

    @Test
    fun `plan without note defaults empty`() {
        val p = LoomMarkupParser()
        val out = p.feed("<loom-plan goal=\"g\"><step>一</step></loom-plan>")
        val plan = out.filterIsInstance<LoomSegment.Plan>().single().plan
        assertEquals("", plan.note)
        assertEquals(listOf("一"), plan.steps)
    }

    // ── 跨块截断（holdback）───────────────────────────────────────

    @Test
    fun `use block split across arbitrary chunks parses identically`() {
        val whole = "前言<use name=\"CodeEdit\"><arg name=\"path\">a/b.kt</arg>" +
            "<arg name=\"old\">fun a()</arg></use>后记"
        val chopped = LoomMarkupParser()
        val collected = mutableListOf<LoomSegment>()
        for (c in whole) collected += chopped.feed(c.toString())
        collected += chopped.finish()
        val joinedText = collected.filterIsInstance<LoomSegment.Text>()
            .joinToString("") { it.delta }
        assertEquals("前言后记", joinedText)
        val inv = collected.filterIsInstance<LoomSegment.Invocation>().single().invocation
        assertEquals("CodeEdit", inv.name)
        assertEquals("a/b.kt", inv.arg("path"))
        assertEquals("fun a()", inv.arg("old"))
    }

    @Test
    fun `deliberate tag split mid-word holds text and completes`() {
        val p = LoomMarkupParser()
        // 「想」先行放行，<deli 憋住
        assertEquals(listOf(LoomSegment.Text("想")), p.feed("想<deli"))
        // 块开成 + 思考内容放行 + </del 憋住
        assertEquals(
            listOf(LoomSegment.Deliberation("思考内容")),
            p.feed("berate>思考内容</del")
        )
        // 闭标签补完：零产出
        assertEquals(emptyList<LoomSegment>(), p.feed("iberate>"))
        assertFalse(p.inBlock)
    }

    @Test
    fun `partial use prefix that diverges flushes as text`() {
        val p = LoomMarkupParser()
        val out = p.feed("a<user_guide>!")
        // user… 与已知名分道扬镳 → 文本放行
        val joined = out.filterIsInstance<LoomSegment.Text>().joinToString("") { it.delta }
        assertEquals("a<user_guide>!", joined)
        assertFalse(p.inBlock)
    }

    // ── finish 语义 ────────────────────────────────────────────────

    @Test
    fun `unterminated use block is dropped at finish`() {
        val p = LoomMarkupParser()
        val out = p.feed("文本<use name=\"T\"><arg name=\"x\">1</arg>")
        assertEquals(1, out.size) // 只有「文本」——块未闭合无产出
        assertTrue(p.inBlock)
        val tail = p.finish()
        assertTrue(tail.filterIsInstance<LoomSegment.Invocation>().isEmpty())
        assertFalse(p.inBlock)
    }

    @Test
    fun `unterminated deliberate flushes content at finish`() {
        val p = LoomMarkupParser()
        p.feed("<deliberate>半截思")
        val tail = p.finish()
        assertEquals(listOf(LoomSegment.Deliberation("半截思")), tail)
        assertFalse(p.inBlock)
    }

    @Test
    fun `reset clears all state`() {
        val p = LoomMarkupParser()
        p.feed("<use name=\"T\">")
        assertTrue(p.inBlock)
        p.reset()
        assertFalse(p.inBlock)
        val out = p.feed("普通文本")
        assertEquals(listOf(LoomSegment.Text("普通文本")), out)
    }

    @Test
    fun `empty feed is a no-op`() {
        val p = LoomMarkupParser()
        assertEquals(emptyList<LoomSegment>(), p.feed(""))
        assertEquals(emptyList<LoomSegment>(), p.finish())
    }
}
