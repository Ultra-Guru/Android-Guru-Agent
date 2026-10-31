package com.apex.agent.core.codetools.html

import com.apex.agent.core.codetools.html.HtmlLayoutModel.DisplayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局分析引擎行为契约：display 推断 / 尺寸估算 / 结构统计 /
 * 响应式画像 / 问题检测 / 报告渲染。
 */
class HtmlLayoutAnalyzerTest {

    private fun analyze(html: String): HtmlLayoutReport {
        val doc = LightweightHtmlParser.parse(html)
        return HtmlLayoutAnalyzer.analyze(doc)
    }

    // ── display 推断 ────────────────────────────────────────────────

    @Test
    fun `display from inline style wins`() {
        val doc = LightweightHtmlParser.parse("<div style=\"display:flex\">a</div>")
        assertEquals(DisplayKind.FLEX, HtmlLayoutModel.displayOf(doc.firstByTag("div")!!))
    }

    @Test
    fun `display from tailwind classes`() {
        val doc = LightweightHtmlParser.parse("<div class=\"flex items-center gap-2\">a</div>")
        assertEquals(DisplayKind.FLEX, HtmlLayoutModel.displayOf(doc.firstByTag("div")!!))
        val grid = LightweightHtmlParser.parse("<div class=\"grid grid-cols-3\">a</div>")
        assertEquals(DisplayKind.GRID, HtmlLayoutModel.displayOf(grid.firstByTag("div")!!))
    }

    @Test
    fun `hidden class means display none`() {
        val doc = LightweightHtmlParser.parse("<div class=\"hidden\">a</div>")
        assertEquals(DisplayKind.NONE, HtmlLayoutModel.displayOf(doc.firstByTag("div")!!))
    }

    // ── 尺寸估算 ────────────────────────────────────────────────

    @Test
    fun `width from tailwind fraction`() {
        val doc = LightweightHtmlParser.parse("<div class=\"w-1/2\">a</div>")
        val w = HtmlLayoutModel.widthOf(doc.firstByTag("div")!!)
        assertTrue(w.isPercent)
        assertEquals(50.0, w.percent!!, 0.01)
    }

    @Test
    fun `width from tailwind spacing scale`() {
        val doc = LightweightHtmlParser.parse("<div class=\"w-64\">a</div>")
        val w = HtmlLayoutModel.widthOf(doc.firstByTag("div")!!)
        assertTrue(w.isFixedPx)
        assertEquals(256, w.px)
    }

    @Test
    fun `width from bootstrap col`() {
        val doc = LightweightHtmlParser.parse("<div class=\"col-md-6\">a</div>")
        val w = HtmlLayoutModel.widthOf(doc.firstByTag("div")!!)
        assertTrue(w.isPercent)
        assertEquals(50.0, w.percent!!, 0.5)
    }

    @Test
    fun `margins from tailwind spacing`() {
        val doc = LightweightHtmlParser.parse("<div class=\"mt-4 mx-2\">a</div>")
        val m = HtmlLayoutModel.marginsOf(doc.firstByTag("div")!!)
        assertEquals(16, m.top)
        assertEquals(8, m.left)
        assertEquals(8, m.right)
        assertNull(m.bottom)
    }

    // ── 结构与响应式 ────────────────────────────────────────────────

    @Test
    fun `structure stats counted`() {
        val report = analyze(
            """
            <html><body>
            <header><nav>nav</nav></header>
            <main><div class="flex"><p>a</p><p>b</p></div></main>
            <footer>foot</footer>
            </body></html>
            """.trimIndent()
        )
        assertTrue(report.stats.elementCount >= 8)
        assertEquals(1, report.stats.flexContainers)
        // header + nav(嵌套于 header) + main + footer = 4 个 landmark 区域
        assertEquals(4, report.stats.landmarkRegions)
    }

    @Test
    fun `responsive viewport parsed`() {
        val good = analyze(
            "<html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>t</title></head><body><p>x</p></body></html>"
        )
        assertTrue(good.responsive.hasResponsiveViewport)
        assertTrue(good.issues.none { it.category == "responsive" })

        val bad = analyze("<html><body><p>x</p></body></html>")
        assertFalse(bad.responsive.hasResponsiveViewport)
        assertTrue(bad.issues.any { it.category == "responsive" })
    }

    @Test
    fun `media queries counted from style blocks`() {
        val report = analyze(
            """
            <html><head><style>
            .a { color: red }
            @media (max-width: 768px) { .a { color: blue } }
            @media print { .a { color: black } }
            </style></head><body><p class="md:hidden">x</p></body></html>
            """.trimIndent()
        )
        assertEquals(2, report.responsive.mediaQueries)
        assertTrue(report.responsive.responsiveUtilityClasses >= 1)
    }

    @Test
    fun `fixed width overflow detected`() {
        val report = analyze(
            """
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>t</title></head><body>
            <div class="w-96" style="width: 900px">too wide</div>
            </body></html>
            """.trimIndent()
        )
        assertTrue(report.responsive.mobileOverflowRisk)
        assertTrue(report.issues.any { it.category == "overflow" })
        assertTrue(report.responsive.fixedWidthElements.any { it.widthPx == 900 })
    }

    // ── 问题检测 ────────────────────────────────────────────────

    @Test
    fun `document level errors`() {
        val report = analyze("<html><body><p>x</p></body></html>")
        assertTrue(report.issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("title") })
        assertTrue(report.issues.any { it.message.contains("lang") })
        assertTrue(report.issues.any { it.message.contains("charset") })
    }

    @Test
    fun `duplicate ids detected`() {
        val report = analyze(
            "<html><head><title>t</title><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width\"></head>" +
                "<body><div id=\"a\">1</div><div id=\"a\">2</div></body></html>"
        )
        assertTrue(report.issues.any { it.category == "quality" && it.message.contains("重复 id") })
    }

    @Test
    fun `accessibility issues detected`() {
        val report = analyze(
            """
            <html lang="zh"><head><title>t</title><meta charset="utf-8">
            <meta name="viewport" content="width=device-width"></head><body>
            <img src="a.png">
            <a href="#"></a>
            <button></button>
            <h1>ok</h1>
            <h4>skipped</h4>
            </body></html>
            """.trimIndent()
        )
        assertTrue(report.issues.any { it.message.contains("alt") })
        assertTrue(report.issues.any { it.message.contains("链接") })
        assertTrue(report.issues.any { it.message.contains("按钮") })
        assertTrue(report.issues.any { it.message.contains("标题层级") })
    }

    @Test
    fun `absolute without positioned ancestor flagged`() {
        val report = analyze(
            """
            <html><head><title>t</title><meta charset="utf-8">
            <meta name="viewport" content="width=device-width"></head><body>
            <div><p style="position:absolute;top:0">floating</p></div>
            </body></html>
            """.trimIndent()
        )
        assertTrue(report.issues.any { it.category == "positioning" && it.message.contains("已定位祖先") })
    }

    @Test
    fun `table layout smell detected`() {
        val report = analyze(
            """
            <html><head><title>t</title><meta charset="utf-8">
            <meta name="viewport" content="width=device-width"></head><body>
            <table><tr><td><div>menu</div></td><td><p>content</p><ul><li>x</li></ul></td></tr></table>
            </body></html>
            """.trimIndent()
        )
        assertTrue(report.issues.any { it.message.contains("table") && it.message.contains("布局") })
    }

    @Test
    fun `multiple main and h1 flagged`() {
        val report = analyze(
            "<html><head><title>t</title><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width\"></head>" +
                "<body><main>1</main><main>2</main><h1>a</h1><h1>b</h1></body></html>"
        )
        assertTrue(report.issues.any { it.message.contains("<main>") })
        assertTrue(report.issues.any { it.message.contains("h1") })
    }

    @Test
    fun `sync head script flagged as perf info`() {
        val report = analyze(
            "<html><head><title>t</title><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width\">" +
                "<script src=\"heavy.js\"></script></head><body><p>x</p></body></html>"
        )
        assertTrue(report.issues.any { it.category == "perf" && it.message.contains("阻塞") })
    }

    // ── 报告渲染 ────────────────────────────────────────────────

    @Test
    fun `bracket summary produced`() {
        val report = analyze(
            """
            <html><head><title>demo</title><meta charset="utf-8">
            <meta name="viewport" content="width=device-width"></head><body>
            <header>h</header>
            <nav>n</nav>
            <main><div class="flex"><aside class="w-1/3">a</aside><article class="w-2/3">b</article></div></main>
            <footer>f</footer>
            </body></html>
            """.trimIndent()
        )
        val summary = report.bracketSummary
        assertTrue(summary.startsWith("body >"))
        assertTrue(summary.contains("header"))
        assertTrue(summary.contains("nav"))
        assertTrue(summary.contains("[flex]"))
        assertTrue(summary.contains("footer"))
    }

    @Test
    fun `markdown report renders sections`() {
        val report = analyze(
            "<html lang=\"zh\"><head><title>t</title><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body><header><h1>标题</h1></header><main class=\"container\"><p>正文</p></main></body></html>"
        )
        val md = report.markdown()
        assertTrue(md.contains("# HTML 布局分析报告"))
        assertTrue(md.contains("## 布局树"))
        assertTrue(md.contains("页面骨架"))
        assertFalse(md.contains("固定宽度元素（手机溢出风险）")) // 该例无固定宽
    }

    @Test
    fun `oneLiner compact`() {
        val report = analyze(
            "<html><head><title>t</title></head><body><p>x</p></body></html>"
        )
        val line = report.oneLiner()
        assertTrue(line.contains("body >"))
        assertTrue(line.contains("err"))
    }

    @Test
    fun `truncated document still analyzable`() {
        val deep = buildString {
            append("<html><head><title>t</title></head><body>")
            repeat(400) { append("<div>") }
            append("</body></html>")
        }
        val report = analyze(deep)
        assertNotNull(report.truncated)
        assertTrue(report.markdown().contains("截断"))
    }

    @Test
    fun `empty document gives honest empty report`() {
        val report = analyze("")
        assertEquals(0, report.stats.elementCount)
        assertTrue(report.issues.isNotEmpty()) // 至少报文档级缺失
        assertEquals("(空文档)", report.bracketSummary)
    }
}
