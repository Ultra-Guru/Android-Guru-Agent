package com.apex.agent.core.codetools.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉画像分析器行为契约：
 * 颜色解析（hex/rgb/hsl/命名色/不可解析容错）· WCAG 对比度 ·
 * 调色板聚合 · 字体/间距/圆角分布 · 暗色主题判定 · 低对比度问题注入。
 */
class HtmlVisualProfileTest {

    private fun parseDoc(html: String): HtmlDocument = LightweightHtmlParser.parse(html)

    private fun analyze(html: String): HtmlLayoutReport {
        val doc = parseDoc(html)
        return HtmlLayoutAnalyzer.analyzeWithCascade(doc)
    }

    // ═══ 颜色解析 ═══

    @Test
    fun `hex 3 6 8 digit parsing`() {
        assertEquals("#ff0000", HtmlVisualProfile.parseColor("#f00")?.hex)
        assertEquals("#336699", HtmlVisualProfile.parseColor("#336699")?.hex)
        val semi = HtmlVisualProfile.parseColor("#33669980")
        assertNotNull(semi)
        assertTrue(semi!!.alpha < 1.0)
    }

    @Test
    fun `rgb rgba parsing with modern syntax`() {
        assertEquals("#0a141e", HtmlVisualProfile.parseColor("rgb(10, 20, 30)")?.hex)
        assertEquals("#0a141e", HtmlVisualProfile.parseColor("rgba(10, 20, 30, 0.5)")?.hex)
        assertEquals("#0a141e", HtmlVisualProfile.parseColor("rgb(10 20 30 / 50%)")?.hex)
    }

    @Test
    fun `hsl parsing`() {
        val c = HtmlVisualProfile.parseColor("hsl(0, 100%, 50%)")
        assertNotNull(c)
        assertEquals("#ff0000", c!!.hex)
    }

    @Test
    fun `named colors and unparseable values`() {
        assertEquals("#000000", HtmlVisualProfile.parseColor("black")?.hex)
        assertEquals("#ffffff", HtmlVisualProfile.parseColor("WHITE")?.hex)
        assertNull(HtmlVisualProfile.parseColor("var(--main)"))
        assertNull(HtmlVisualProfile.parseColor("inherit"))
        assertNull(HtmlVisualProfile.parseColor("transparent"))
        assertNull(HtmlVisualProfile.parseColor(""))
        assertNull(HtmlVisualProfile.parseColor(null))
    }

    @Test
    fun `contrast ratio and wcag levels`() {
        val black = HtmlVisualProfile.parseColor("#000")!!
        val white = HtmlVisualProfile.parseColor("#fff")!!
        assertEquals(21.0, HtmlVisualProfile.contrastRatio(black, white), 0.01)
        assertEquals(21.0, HtmlVisualProfile.contrastRatio(white, black), 0.01)
        val gray = HtmlVisualProfile.parseColor("#999999")!!
        val ratio = HtmlVisualProfile.contrastRatio(gray, white)
        assertTrue("gray on white should fail AA: $ratio", ratio < 4.5)
        assertEquals("FAIL", HtmlVisualProfile.wcagLevel(ratio))
        assertEquals("AAA", HtmlVisualProfile.wcagLevel(21.0))
        assertEquals("AA", HtmlVisualProfile.wcagLevel(4.5))
        assertEquals("AA-large", HtmlVisualProfile.wcagLevel(3.2))
    }

    // ═══ 画像聚合 ═══

    @Test
    fun `palette aggregates colors by usage`() {
        val report = analyze(
            "<html><head><style>" +
                "body { background: #111827; color: #f9fafb; }" +
                ".btn { background-color: #3b82f6; color: #ffffff; }" +
                ".btn2 { background: #3b82f6; }" +
                "</style></head><body><div class=\"btn\">Go</div></body></html>"
        )
        val v = report.visual
        assertNotNull(v)
        assertEquals("#111827", v!!.backgroundColor?.hex)
        assertEquals("#f9fafb", v.textColor?.hex)
        assertTrue(v.darkThemeCandidate)
        // #3b82f6 出现两次（btn 背景 + btn2）→ 调色板在榜
        val blue = v.palette.firstOrNull { it.color.hex == "#3b82f6" }
        assertNotNull(blue)
        assertTrue(blue!!.count >= 2)
        // 蓝色非灰 → 强调色候选
        assertTrue(v.accentColors.any { it.hex == "#3b82f6" })
    }

    @Test
    fun `fonts and sizes collected`() {
        val report = analyze(
            "<html><head><style>" +
                "body { font-family: 'Inter', sans-serif; font-size: 16px; }" +
                "h1 { font-family: Inter; font-size: 2rem; }" +
                "</style></head><body><h1>T</h1></body></html>"
        )
        val v = report.visual!!
        assertTrue(v.fontFamilies.any { it.family == "Inter" })
        assertEquals(16, v.baseFontSizePx)
        assertTrue(v.fontSizes.any { it.px == 32 }) // 2rem → 32px
    }

    @Test
    fun `spacing scale and rhythm`() {
        val report = analyze(
            "<html><head><style>" +
                ".a { margin: 8px; padding: 16px; } .b { margin: 8px; } .c { padding: 24px; }" +
                "</style></head><body><div class=\"a\">x<div class=\"b\">y<div class=\"c\">z</div></div></div></body></html>"
        )
        val v = report.visual!!
        assertTrue(v.spacingScale.isNotEmpty())
        assertEquals("8px 节奏", v.spacingRhythm)
    }

    @Test
    fun `radii and shadows counted`() {
        val report = analyze(
            "<html><head><style>" +
                ".card { border-radius: 12px; box-shadow: 0 2px 8px rgba(0,0,0,0.3); }" +
                ".pill { border-radius: 9999px; }" +
                "</style></head><body><div class=\"card\">c</div></body></html>"
        )
        val v = report.visual!!
        assertTrue(v.radii.any { it.raw == "12px" })
        assertEquals(1, v.shadowCount)
    }

    @Test
    fun `no style blocks yields null visual`() {
        val report = analyze("<html><body><div class=\"flex\"><p>plain</p></div></body></html>")
        assertNull(report.visual)
        assertFalse(report.meta.cssCascadeApplied)
    }

    // ═══ 对比度问题 ═══

    @Test
    fun `low contrast text reported as issue`() {
        val report = analyze(
            "<html><head><style>" +
                "body { background: #ffffff; }" +
                ".muted { color: #aaaaaa; }" +
                "</style></head><body><p class=\"muted\">灰得看不清的文字</p></body></html>"
        )
        val contrastIssues = report.issues.filter { it.category == "可读性" }
        assertTrue("expected contrast issue, got: ${report.issues.map { it.category to it.message }}", contrastIssues.isNotEmpty())
        val issue = contrastIssues.first()
        assertTrue(issue.message.contains("#aaaaaa"))
        assertTrue(issue.message.contains("#ffffff"))
        assertTrue(issue.severity == IssueSeverity.WARN || issue.severity == IssueSeverity.ERROR)
    }

    @Test
    fun `good contrast not reported`() {
        val report = analyze(
            "<html><head><style>" +
                "body { background: #ffffff; color: #111111; }" +
                "</style></head><body><p>清晰可读</p></body></html>"
        )
        assertTrue(report.issues.none { it.category == "可读性" })
    }

    @Test
    fun `contrast inherits background from ancestor`() {
        // 后代选择器（.banner p）是 complex 不参与级联注入 —— 用简单选择器给文本上色验证背景继承链
        val report = analyze(
            "<html><head><style>" +
                ".banner { background-color: #1e293b; } .t { color: #94a3b8; }" +
                "</style></head><body><div class=\"banner\"><p class=\"t\">slate on slate</p></div></body></html>"
        )
        val report2 = analyze(
            "<html><head><style>" +
                ".banner { background-color: #1e293b; } .t { color: #475569; }" +
                "</style></head><body><div class=\"banner\"><p class=\"t\">too close</p></div></body></html>"
        )
        assertTrue(report.issues.none { it.category == "可读性" })
        assertTrue(report2.issues.any { it.category == "可读性" })
    }

    @Test
    fun `large text uses aa-large threshold`() {
        val report = analyze(
            "<html><head><style>" +
                "body { background: #ffffff; }" +
                ".big { color: #999999; font-size: 24px; }" +
                "</style></head><body><p class=\"big\">大字</p></body></html>"
        )
        val issues = report.issues.filter { it.category == "可读性" }
        // #999 on #fff ≈ 2.85:1 —— 大字阈值 3.0 也不达 → 仍报 ERROR
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.first().severity == IssueSeverity.ERROR)
    }

    // ═══ 报告渲染 ═══

    @Test
    fun `markdown report contains visual section`() {
        val report = analyze(
            "<html><head><style>" +
                "body { background: #0f172a; color: #e2e8f0; font-family: 'JetBrains Mono', monospace; }" +
                ".accent { color: #38bdf8; }" +
                "</style></head><body><p class=\"accent\">hi</p></body></html>"
        )
        val md = report.markdown()
        assertTrue(md.contains("## 视觉画像"))
        assertTrue(md.contains("暗色主题画像"))
        assertTrue(md.contains("JetBrains Mono"))
        assertTrue(md.contains("#38bdf8"))
    }
}
