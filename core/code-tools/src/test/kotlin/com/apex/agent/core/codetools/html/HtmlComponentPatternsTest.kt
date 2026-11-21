package com.apex.agent.core.codetools.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件模式识别行为契约：
 * 指纹归一化（class 前缀/状态尾缀切断）· 相邻游程检测 · 种类猜测 ·
 * 跨容器去重 · 报告渲染。
 */
class HtmlComponentPatternsTest {

    private fun parseDoc(html: String): HtmlDocument = LightweightHtmlParser.parse(html)

    private fun analyze(html: String): HtmlLayoutReport =
        HtmlLayoutAnalyzer.analyzeWithCascade(parseDoc(html))

    // ═══ 指纹归一化 ═══

    @Test
    fun `class prefix strips numeric and state suffixes`() {
        val doc = parseDoc("""<div class="card-item-3"><div class="nav-link active">x</div></div>""")
        val card = doc.firstByTag("div")!!
        val nav = card.children.first { it.tag == "div" }
        assertEquals("card-item", HtmlComponentPatterns.classPrefix(card))
        assertEquals("nav-link", HtmlComponentPatterns.classPrefix(nav))
    }

    @Test
    fun `class prefix of bare tag is null`() {
        val doc = parseDoc("<div><span>x</span></div>")
        assertNull(HtmlComponentPatterns.classPrefix(doc.firstByTag("div")!!))
        assertNull(HtmlComponentPatterns.classPrefix(doc.firstByTag("span")!!))
    }

    @Test
    fun `fingerprint includes child tag skeleton`() {
        val doc = parseDoc("""<div class="card"><img src="a"><h3>t</h3><p>x</p></div>""")
        val card = doc.firstByTag("div")!!
        val fp = HtmlComponentPatterns.fingerprintOf(card)!!
        assertTrue(fp.startsWith("div(card)|"))
        assertTrue(fp.contains("img"))
        assertTrue(fp.contains("h3"))
        assertTrue(fp.contains(";p"))
    }

    // ═══ 重复检测 ═══

    @Test
    fun `three identical cards detected as card kind`() {
        val cards = (1..4).joinToString(" ") { i ->
            """<div class="card"><img src="p$i.png"><h3>卡 $i</h3><p>内容 $i</p></div>"""
        }
        val report = analyze("<html><body><main><div class=\"grid\">$cards</div></main></body></html>")
        val patterns = report.components
        assertTrue("expected card pattern, got: ${patterns.map { it.kind to it.count }}", patterns.isNotEmpty())
        val card = patterns.first()
        assertEquals(4, card.count)
        assertEquals(HtmlComponentPatterns.ComponentKind.CARD, card.kind)
        assertTrue(card.sampleClasses.contains("card"))
    }

    @Test
    fun `ul list items detected`() {
        val items = (1..5).joinToString(" ") { """<li class="menu-item">项 $it</li>""" }
        val report = analyze("<html><body><nav><ul>$items</ul></nav></body></html>")
        val listPattern = report.components.firstOrNull { it.kind == HtmlComponentPatterns.ComponentKind.LIST_ITEM }
        assertTrue("expected list item pattern", listPattern != null)
        assertEquals(5, listPattern!!.count)
    }

    @Test
    fun `table rows detected`() {
        val rows = (1..6).joinToString(" ") { """<tr><td>a$it</td><td>b$it</td></tr>""" }
        val report = analyze("<html><body><table><tbody>$rows</tbody></table></body></html>")
        val rowPattern = report.components.firstOrNull { it.kind == HtmlComponentPatterns.ComponentKind.TABLE_ROW }
        assertTrue(rowPattern != null)
        assertEquals(6, rowPattern!!.count)
    }

    @Test
    fun `nav links detected`() {
        val links = (1..4).joinToString(" ") { """<a href="/$it" class="nav-link">L$it</a>""" }
        val report = analyze("<html><body><nav>$links</nav></body></html>")
        val nav = report.components.firstOrNull { it.kind == HtmlComponentPatterns.ComponentKind.NAV_ITEM }
        assertTrue(nav != null)
        assertEquals(4, nav!!.count)
    }

    @Test
    fun `two instances below threshold not a pattern`() {
        val two = """<div class="card"><h3>a</h3></div><div class="card"><h3>b</h3></div>"""
        val report = analyze("<html><body><main>$two</main></body></html>")
        assertTrue(report.components.none { it.count < HtmlComponentPatterns.MIN_REPEAT })
    }

    @Test
    fun `separator element inside run does not break it`() {
        // 3 个同形卡片，中间插 1 个 hr 分隔 —— 分组语义下不打散
        val inner = """
            <div class="card"><h3>a</h3></div><hr>
            <div class="card"><h3>b</h3></div>
            <div class="card"><h3>c</h3></div>
        """.trimIndent()
        val report = analyze("<html><body><main>$inner</main></body></html>")
        assertTrue(report.components.any { it.count >= 3 })
    }

    @Test
    fun `different shapes not merged`() {
        // 3 个同形卡片 + 1 个异形横幅（真实布局常见：列表夹 promo）—— 卡片仍是 3 连
        val mixed = """
            <div class="card"><h3>a</h3><p>x</p></div>
            <div class="card"><h3>b</h3><p>y</p></div>
            <div class="banner"><h2>promo</h2></div>
            <div class="card"><h3>c</h3><p>z</p></div>
        """.trimIndent()
        val report = analyze("<html><body><main>$mixed</main></body></html>")
        val cardPattern = report.components.firstOrNull { it.sampleClasses.contains("card") }
        assertTrue(cardPattern != null)
        assertEquals(3, cardPattern!!.count)
        // 横幅单实例不成组件
        assertTrue(report.components.none { it.sampleClasses.contains("banner") })
    }

    @Test
    fun `same shape across containers deduped keeping max`() {
        val blockA = (1..3).joinToString(" ") { """<li class="item">a$it</li>""" }
        val blockB = (1..5).joinToString(" ") { """<li class="item">b$it</li>""" }
        val report = analyze("<html><body><ul>$blockA</ul><ul>$blockB</ul></body></html>")
        val items = report.components.filter { it.sampleClasses.contains("item") }
        assertEquals(1, items.size.toLong())
        assertEquals(5, items.first().count)
    }

    // ═══ 报告渲染 ═══

    @Test
    fun `markdown contains component section`() {
        val items = (1..4).joinToString(" ") { """<li class="menu-item">项 $it</li>""" }
        val report = analyze("<html><body><ul>$items</ul></body></html>")
        val md = report.markdown()
        assertTrue(md.contains("## 重复组件"))
        assertTrue(md.contains("列表项 ×4"))
        assertTrue(md.contains("menu-item"))
    }

    @Test
    fun `plain page has no components section`() {
        val report = analyze("<html><body><header><h1>标题</h1></header><main><p>正文</p></main></body></html>")
        assertTrue(report.components.isEmpty())
    }
}
