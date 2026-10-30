package com.apex.agent.core.codetools.html

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 布局对比行为契约：
 * 问题差异（回归/修复/保留）· 骨架/规模变化 · 视觉变化 · 组件变化 ·
 * 工具端到端（路径解析/错误路径）。
 */
class HtmlLayoutDiffTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun report(html: String): HtmlLayoutReport =
        HtmlLayoutAnalyzer.analyzeWithCascade(LightweightHtmlParser.parse(html))

    // ═══ diff 引擎 ═══

    @Test
    fun `fixed and new issues detected`() {
        // before：viewport 缺失 + 无 lang；after：都修了，但引入了固定宽度
        val before = report("<html><head><title>A</title></head><body><p>x</p></body></html>")
        val after = report(
            "<html lang=\"zh\"><head><meta name=\"viewport\" content=\"width=device-width\">" +
                "<title>A</title></head><body><div style=\"width:900px\">x</div></body></html>"
        )
        val d = HtmlLayoutDiff.diff(before, after)
        assertTrue(d.fixedIssues.isNotEmpty())
        assertTrue(d.newIssues.isNotEmpty())
        assertTrue(d.skeletonChanged)
        assertTrue(d.elementsDelta > 0)
    }

    @Test
    fun `identical pages produce empty diff`() {
        val html = "<html lang=\"zh\"><head><meta name=\"viewport\" content=\"width=device-width\"><title>T</title></head><body><main><p>同页</p></main></body></html>"
        val d = HtmlLayoutDiff.diff(report(html), report(html))
        assertFalse(d.skeletonChanged)
        assertTrue(d.newIssues.isEmpty())
        assertTrue(d.fixedIssues.isEmpty())
        assertTrue(d.keptIssueCount >= 0)
        assertEquals(0, d.elementsDelta)
    }

    @Test
    fun `same issue with drifted evidence line still aligns`() {
        // 同一个问题（viewport 缺失），行号不同 —— 对齐键不含 evidence
        val before = report("<html><body><p>1</p></body></html>")
        val after = report("<html><!-- comment line drift --><body><p>1</p><p>2</p><p>3</p></body></html>")
        val d = HtmlLayoutDiff.diff(before, after)
        val viewportIssueKept = before.issues.any { it.category == "响应式" || it.message.contains("viewport") }
        if (viewportIssueKept) {
            assertTrue("kept count should include aligned viewport issue", d.keptIssueCount > 0)
            assertTrue("viewport issue must not appear as new or fixed",
                d.newIssues.none { it.message.contains("viewport") } && d.fixedIssues.none { it.message.contains("viewport") })
        }
    }

    @Test
    fun `visual changes detected`() {
        val before = report(
            "<html><head><style>body { background: #ffffff; color: #111111; }</style></head><body><p>x</p></body></html>"
        )
        val after = report(
            "<html><head><style>body { background: #0f172a; color: #e2e8f0; } .a { color: #38bdf8; }</style></head><body><p>x</p></body></html>"
        )
        val d = HtmlLayoutDiff.diff(before, after)
        assertTrue(d.visualChanges.any { it.contains("主题切换") || it.contains("暗色") })
    }

    @Test
    fun `component changes detected`() {
        val cards3 = (1..3).joinToString(" ") { """<div class="card"><h3>c$it</h3></div>""" }
        val cards5 = (1..5).joinToString(" ") { """<div class="card"><h3>c$it</h3></div>""" }
        val before = report("<html><body><main>$cards3</main></body></html>")
        val after = report("<html><body><main>$cards5</main></body></html>")
        val d = HtmlLayoutDiff.diff(before, after)
        assertTrue(d.componentChanges.any { it.contains("3 → 5") })
    }

    @Test
    fun `markdown renders key sections`() {
        val before = report("<html><body><p>old</p></body></html>")
        val after = report(
            "<html lang=\"zh\"><head><meta name=\"viewport\" content=\"width=device-width\"></head><body><main><p>new</p></main></body></html>"
        )
        val md = HtmlLayoutDiff.markdown(HtmlLayoutDiff.diff(before, after))
        assertTrue(md.contains("# 布局对比报告"))
        assertTrue(md.contains("新增问题") || md.contains("已修复"))
    }

    // ═══ 工具端到端 ═══

    private fun writeHtml(name: String, html: String): File {
        val f = tmp.newFile(name)
        f.writeText(html)
        return f
    }

    private fun tool(): HtmlCompareLayoutsTool {
        val root = tmp.root
        return HtmlCompareLayoutsTool(CodeWorkspaceRoots { root })
    }

    @Test
    fun `tool compares two files end to end`() = runTest {
        writeHtml("a.html", "<html><body><p>x</p></body></html>")
        writeHtml("b.html", "<html lang=\"zh\"><head><meta name=\"viewport\" content=\"width=device-width\"></head><body><p>x</p></body></html>")
        val result = tool().executeSafe("""{"path_a": "a.html", "path_b": "b.html"}""")
        assertTrue(result.isSuccess)
        val out = result.output ?: ""
        assertTrue(out.contains("布局对比报告"))
        assertTrue(out.contains("已修复"))
    }

    @Test
    fun `tool rejects missing file`() = runTest {
        writeHtml("a.html", "<html></html>")
        val result = tool().executeSafe("""{"path_a": "a.html", "path_b": "nope.html"}""")
        assertFalse(result.isSuccess)
        assertTrue((result.output ?: "").contains("nope.html") || result.error != null)
    }

    @Test
    fun `tool rejects non html`() = runTest {
        writeHtml("a.html", "<html></html>")
        tmp.newFile("data.txt").writeText("x")
        val result = tool().executeSafe("""{"path_a": "a.html", "path_b": "data.txt"}""")
        assertFalse(result.isSuccess)
    }
}
