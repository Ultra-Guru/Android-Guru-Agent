package com.apex.agent.core.codetools.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSS 样式表解析 + 级联视图行为契约：
 * 规则解析（类/ID/标签/复合/逗号分组/@media）· 容错（坏段跳过）·
 * 媒体评估 · 匹配 · 级联注入（优先级/覆盖顺序）· 布局推断接入。
 */
class CssStylesheetParserTest {

    private fun parseCss(css: String) = CssStylesheetParser.parse(css)

    private fun parseDoc(html: String): HtmlDocument = LightweightHtmlParser.parse(html)

    // ═══ 规则解析 ═══

    @Test
    fun `class selector with declarations`() {
        val sheet = parseCss(".card { display: flex; gap: 16px; }")
        assertEquals(1, sheet.rules.size)
        val rule = sheet.rules.first()
        val sel = rule.matchableSelectors.first()
        assertEquals(listOf("card"), sel.classes)
        assertNull(sel.tag)
        assertEquals("flex", rule.declarations["display"])
        assertEquals("16px", rule.declarations["gap"])
    }

    @Test
    fun `id tag and compound selectors`() {
        val sheet = parseCss("#app { display: grid; } div.box { padding: 8px; } nav { flex-direction: column; }")
        assertEquals(3, sheet.rules.size)
        val idSel = sheet.rules[0].matchableSelectors.first()
        assertEquals("app", idSel.id)
        val compound = sheet.rules[1].matchableSelectors.first()
        assertEquals("div", compound.tag)
        assertEquals(listOf("box"), compound.classes)
        val tagSel = sheet.rules[2].matchableSelectors.first()
        assertEquals("nav", tagSel.tag)
    }

    @Test
    fun `comma group expands to one rule with multiple selectors`() {
        val sheet = parseCss(".a, .b, #c { margin: 0; }")
        assertEquals(1, sheet.rules.size)
        assertEquals(3, sheet.rules.first().selectors.size)
        assertEquals(3, sheet.rules.first().matchableSelectors.size)
    }

    @Test
    fun `descendant and pseudo selectors are complex and skipped from matching`() {
        val sheet = parseCss(".list .item { color: red; } a:hover { color: blue; } [data-x] { color: green; }")
        assertEquals(3, sheet.rules.size)
        assertEquals(3, sheet.complexSelectors)
        assertTrue(sheet.rules.all { it.matchableSelectors.isEmpty() })
    }

    @Test
    fun `media block rules carry condition`() {
        val sheet = parseCss("@media (max-width: 600px) { .sidebar { display: none; } }")
        assertEquals(1, sheet.rules.size)
        assertEquals("(max-width: 600px)", sheet.rules.first().mediaCondition)
    }

    @Test
    fun `keyframes font-face import are skipped as blocks`() {
        val sheet = parseCss(
            "@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } } " +
                "@font-face { font-family: X; src: url(x.woff); } @import url(other.css); .a { color: red; }"
        )
        assertEquals(1, sheet.rules.size)
        assertTrue(sheet.skippedBlocks >= 2)
        assertEquals("red", sheet.rules.first().declarations["color"])
    }

    @Test
    fun `comments stripped`() {
        val sheet = parseCss("/* header styles */ .h { color: red; /* inline */ margin: 0; }")
        assertEquals(1, sheet.rules.size)
        assertEquals("red", sheet.rules.first().declarations["color"])
        assertEquals("0", sheet.rules.first().declarations["margin"])
    }

    @Test
    fun `malformed input never throws`() {
        // 未闭合块 / 空选择器 / 空声明 / 裸冒号 —— 全部吞下
        val sheet = parseCss(".broken { color: red; .next { margin: 0; } {{ }} ;} } } : ; .ok { padding: 1px }")
        assertTrue(sheet.rules.any { it.declarations["padding"] == "1px" })
    }

    @Test
    fun `css variables declarations are not layout props`() {
        // :root 伪类 → complex；其声明全是 CSS 变量（--x）→ 无布局属性 → 规则整体丢弃
        val sheet = parseCss(":root { --main-color: #333; } .a { color: var(--main-color); }")
        assertEquals(1, sheet.rules.size)
        val rule = sheet.rules.first()
        assertTrue(rule.matchableSelectors.isNotEmpty()) // .a 保留
        // 值里的 var() 引用原样保留（不做变量求值 —— 静态分析不追变量链）
        assertEquals("var(--main-color)", rule.declarations["color"])
    }

    // ═══ 匹配 ═══

    @Test
    fun `matches class id and tag`() {
        val doc = parseDoc("""<div class="card item" id="c1"><span>hi</span></div>""")
        val div = doc.firstByTag("div")!!
        val span = doc.firstByTag("span")!!
        fun selOf(css: String) = parseCss("$css { z-index: 1; }").rules.first().matchableSelectors.first()
        assertTrue(CssStylesheetParser.matches(selOf(".card"), div))
        assertTrue(CssStylesheetParser.matches(selOf(".item"), div))
        assertFalse(CssStylesheetParser.matches(selOf(".card"), span))
        assertTrue(CssStylesheetParser.matches(selOf("#c1"), div))
        assertFalse(CssStylesheetParser.matches(selOf("#c2"), div))
        assertTrue(CssStylesheetParser.matches(selOf("div"), div))
        assertFalse(CssStylesheetParser.matches(selOf("div"), span))
        assertTrue(CssStylesheetParser.matches(selOf("div.card"), div))
        assertFalse(CssStylesheetParser.matches(selOf("span.card"), div))
        assertTrue(CssStylesheetParser.matches(selOf("*"), span))
    }

    // ═══ 媒体评估 ═══

    @Test
    fun `media applies by viewport width`() {
        val max600 = "(max-width: 600px)"
        val min768 = "(min-width: 768px)"
        assertTrue(CssStylesheetParser.mediaApplies(max600, viewportWidthPx = 375))
        assertFalse(CssStylesheetParser.mediaApplies(max600, viewportWidthPx = 700))
        assertFalse(CssStylesheetParser.mediaApplies(min768, viewportWidthPx = 375))
        assertTrue(CssStylesheetParser.mediaApplies(min768, viewportWidthPx = 1024))
        assertTrue(CssStylesheetParser.mediaApplies(null))
        assertTrue(CssStylesheetParser.mediaApplies("screen"))
        assertTrue(CssStylesheetParser.mediaApplies("only screen and (max-width: 600px)", viewportWidthPx = 375))
        assertFalse(CssStylesheetParser.mediaApplies("print"))
        assertFalse(CssStylesheetParser.mediaApplies("(min-width: 40em)")) // em 视口宽度保守 false
        assertTrue(CssStylesheetParser.mediaApplies("(orientation: portrait)")) // 非宽度特征放行
    }

    // ═══ 级联注入 ═══

    @Test
    fun `cascade resolves css class rules onto nodes`() {
        val doc = parseDoc(
            "<html><head><style>.row { display: flex; gap: 8px; } .row > .cell { flex: 1; }</style></head>" +
                "<body><div class=\"row\"><div class=\"cell\">a</div></div></body></html>"
        )
        CssStylesheetParser.resolveCascadeStyles(doc, CssStylesheetParser.stylesheetOf(doc))
        val row = doc.firstByTag("body")!!.children.first { it.tag == "div" }
        assertNotNull(row.resolvedStyle)
        assertEquals("flex", row.resolvedStyle!!["display"])
        assertEquals("8px", row.resolvedStyle!!["gap"])
        // 后代选择器不参与 → cell 无注入
        val cell = row.children.first { it.tag == "div" }
        assertNull(cell.resolvedStyle)
    }

    @Test
    fun `inline style wins over css rules`() {
        val doc = parseDoc(
            "<html><head><style>.a { display: flex; width: 100px; }</style></head>" +
                "<body><div class=\"a\" style=\"display: grid\"></div></body></html>"
        )
        CssStylesheetParser.resolveCascadeStyles(doc, CssStylesheetParser.stylesheetOf(doc))
        val div = doc.firstByTag("div")!!
        assertEquals("grid", div.resolvedStyle!!["display"])   // inline 覆盖
        assertEquals("100px", div.resolvedStyle!!["width"])    // 规则保留
    }

    @Test
    fun `later rules override earlier ones`() {
        val doc = parseDoc(
            "<html><head><style>.a { color: red; } .a { color: blue; }</style></head>" +
                "<body><div class=\"a\"></div></body></html>"
        )
        CssStylesheetParser.resolveCascadeStyles(doc, CssStylesheetParser.stylesheetOf(doc))
        assertEquals("blue", doc.firstByTag("div")!!.resolvedStyle!!["color"])
    }

    @Test
    fun `media gated rules applied by viewport`() {
        val doc = parseDoc(
            "<html><head><style>@media (max-width: 600px) { .sidebar { display: none; } }</style></head>" +
                "<body><div class=\"sidebar\"></div></body></html>"
        )
        val sheet = CssStylesheetParser.stylesheetOf(doc)
        CssStylesheetParser.resolveCascadeStyles(doc, sheet, viewportWidthPx = 375)
        assertEquals("none", doc.firstByTag("div")!!.resolvedStyle!!["display"])
        // 桌面视口下不应用
        val doc2 = parseDoc(doc.source)
        CssStylesheetParser.resolveCascadeStyles(doc2, sheet, viewportWidthPx = 1200)
        assertNull(doc2.firstByTag("div")!!.resolvedStyle)
    }

    @Test
    fun `cascade feeds layout model display and width`() {
        val doc = parseDoc(
            "<html><head><style>.layout { display: grid; grid-template-columns: 1fr 2fr; } .hero { width: 800px; }</style></head>" +
                "<body><div class=\"layout\"><div class=\"hero\">x</div></div></body></html>"
        )
        CssStylesheetParser.resolveCascadeStyles(doc, CssStylesheetParser.stylesheetOf(doc))
        val layout = doc.firstByTag("div")!!
        val hero = layout.children.first { it.tag == "div" }
        assertEquals(HtmlLayoutModel.DisplayKind.GRID, HtmlLayoutModel.displayOf(layout))
        assertEquals(HtmlLayoutModel.DisplayKind.BLOCK, HtmlLayoutModel.displayOf(hero))
        val w = HtmlLayoutModel.widthOf(hero)
        assertTrue(w.isFixedPx)
        assertEquals(800, w.px)
        assertEquals("1fr 2fr", HtmlLayoutModel.gridPropsOf(layout)?.templateColumns)
    }

    @Test
    fun `analyzeWithCascade counts css rules in meta`() {
        val doc = parseDoc(
            "<html><head><style>.a { display: flex; }</style></head><body><div class=\"a\"></div></body></html>"
        )
        val report = HtmlLayoutAnalyzer.analyzeWithCascade(doc)
        assertTrue(report.meta.cssCascadeApplied)
        assertEquals(1, report.meta.cssRules)
        assertEquals(1, report.stats.flexContainers)
    }

    @Test
    fun `analyze without style blocks keeps legacy behavior`() {
        val doc = parseDoc("<html><body><div class=\"flex\"><span>x</span></div></body></html>")
        val report = HtmlLayoutAnalyzer.analyzeWithCascade(doc)
        assertFalse(report.meta.cssCascadeApplied)
        assertEquals(0, report.meta.cssRules)
        // 框架类推断照旧生效
        assertEquals(1, report.stats.flexContainers)
    }

    @Test
    fun `multiple style blocks aggregated`() {
        val doc = parseDoc(
            "<html><head><style>.a { color: red; }</style><style>.b { color: blue; }</style></head>" +
                "<body><div class=\"a b\"></div></body></html>"
        )
        val sheet = CssStylesheetParser.stylesheetOf(doc)
        assertEquals(2, sheet.rules.size)
        CssStylesheetParser.resolveCascadeStyles(doc, sheet)
        // .b 在后 → blue 胜出
        assertEquals("blue", doc.firstByTag("div")!!.resolvedStyle!!["color"])
    }

    @Test
    fun `unclosed block swallows rest without throw`() {
        // 未闭合块按浏览器容错语义应用其声明（不抛）
        val sheet = parseCss(".a { color: red; } .broken { margin: 0;")
        assertEquals(2, sheet.rules.size)
    }

    @Test
    fun `empty and blank inputs return empty sheet`() {
        assertTrue(CssStylesheetParser.parse("").isEmpty)
        assertTrue(CssStylesheetParser.parse("   \n  ").isEmpty)
        assertTrue(CssStylesheetParser.parse("/* only comment */").isEmpty)
    }
}
