package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CodeRead / CodeEdit / CodeWrite / CodeGlob / CodeGrep 的行为契约测试。
 */
class CodeToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roots(root: File): CodeWorkspaceRoots = CodeWorkspaceRoots { root }

    // ── CodeRead ────────────────────────────────────────────────────

    @Test
    fun `read file renders line numbers and continuation hint`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "a.kt").writeText("line1\nline2\nline3\n")
        val tool = CodeReadTool(roots(root))

        val result = tool.executeSafe("""{"path": "a.kt", "limit": 2}""")
        assertTrue(result.render(), result.render().contains("1: line1"))
        assertTrue(result.render(), result.render().contains("2: line2"))
        assertFalse(result.render(), result.render().contains("3: line3"))
        assertTrue(result.render(), result.render().contains("offset=3"))
    }

    @Test
    fun `read missing file suggests similar names`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "MainActivity.kt").writeText("x")
        val tool = CodeReadTool(roots(root))

        val out = tool.executeSafe("""{"path": "MainActvity.kt"}""").render()
        assertTrue(out, out.contains("did you mean"))
    }

    @Test
    fun `read directory lists entries sorted`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "zdir").mkdirs()
        File(root, "Afile").writeText("a")
        val tool = CodeReadTool(roots(root))

        val out = tool.executeSafe("""{"path": "."}""").render()
        assertTrue(out, out.contains("zdir/"))
        assertTrue(out, out.contains("Afile"))
    }

    @Test
    fun `read file enforces cumulative 50KB output cap with continuation hint`() = runTest {
        val root = tmp.newFolder("ws")
        // 300 行 × 300 字符 ≈ 90KB —— 单轮 limit=2000 旧实现可整读（≈90KB
        // 输出），承诺的 50KB 累计上限此前不存在（#830：实为 4MB）。
        val big = buildString { repeat(300) { appendLine("x".repeat(300)) } }
        File(root, "big.txt").writeText(big)
        val tool = CodeReadTool(roots(root))

        val out = tool.executeSafe("""{"path": "big.txt"}""").render()
        assertTrue(
            "output must stay under ~50KB (was ${out.toByteArray().size})",
            out.toByteArray().size <= 51 * 1024
        )
        assertTrue(out, out.contains("50KB per-read limit"))
        assertTrue(out, out.contains("Use offset="))
        // 截停后给出的续读行号必须是尚未输出的下一行（300B/行 → ~170 行打满）。
        val nextOffset = Regex("Use offset=(\\d+)").find(out)!!.groupValues[1].toInt()
        assertTrue("next offset must be past the shown range (was $nextOffset)", nextOffset in 101..180)
    }

    @Test
    fun `read resumes from hinted offset after cap`() = runTest {
        val root = tmp.newFolder("ws")
        val big = buildString { repeat(300) { appendLine("x".repeat(300)) } }
        File(root, "big.txt").writeText(big)
        val tool = CodeReadTool(roots(root))

        val first = tool.executeSafe("""{"path": "big.txt"}""").render()
        val nextOffset = Regex("Use offset=(\\d+)").find(first)!!.groupValues[1].toInt()
        val second = tool.executeSafe("""{"path": "big.txt", "offset": $nextOffset}""").render()
        assertTrue(second, second.contains("$nextOffset: "))
        // 续读内容与首轮无重叠：首轮最后输出的行号 < 续读首行号。
        val lastShown = Regex("at line (\\d+)").find(first)!!.groupValues[1].toInt()
        assertTrue("no overlap expected (last=$lastShown next=$nextOffset)", lastShown < nextOffset)
    }

    @Test
    fun `read offset beyond EOF clamps to last line`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "five.txt").writeText("l1\nl2\nl3\nl4\nl5\n")
        val tool = CodeReadTool(roots(root))

        val out = tool.executeSafe("""{"path": "five.txt", "offset": 100}""").render()
        // 旧 coerceIn(0, total-1) 行为保留：越界钳到末行（流式窗口同语义）。
        assertTrue(out, out.contains("5: l5"))
        assertFalse(out, out.contains("4: l4"))
        assertTrue(out, out.contains("(Showing lines 5-5 of 5.)"))
    }

    @Test
    fun `read file without trailing newline keeps last line`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "nonl.txt").writeText("a\nb\nc")
        val tool = CodeReadTool(roots(root))
        val out = tool.executeSafe("""{"path": "nonl.txt"}""").render()
        assertTrue(out, out.contains("3: c"))
        assertTrue(out, out.contains("<total_lines>3</total_lines>"))
    }

    // ── CodeEdit ────────────────────────────────────────────────────

    @Test
    fun `edit replaces with fuzzy fallback on indent drift`() = runTest {
        val root = tmp.newFolder("ws")
        val file = File(root, "code.txt")
        file.writeText("start\n    inner\nend\n")
        val tool = CodeEditTool(roots(root))

        val result = tool.executeSafe(
            """{"path": "code.txt", "old_string": "start\ninner\nend", "new_string": "START\ninner\nEND"}"""
        )
        assertTrue(result.render(), result.render().contains("✅"))
        // 语义与业界标准 edit 契约对齐：new_string 原样写入（模糊匹配只定位，不改写替换内容）
        assertEquals("START\ninner\nEND\n", file.readText())
    }

    @Test
    fun `edit empty old string creates file`() = runTest {
        val root = tmp.newFolder("ws")
        val tool = CodeEditTool(roots(root))

        val result = tool.executeSafe(
            """{"path": "new.txt", "old_string": "", "new_string": "hello"}"""
        )
        assertTrue(result.render(), result.render().contains("created"))
        assertEquals("hello", File(root, "new.txt").readText())
    }

    @Test
    fun `edit ambiguous requires unique context`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "dup.txt").writeText("same\nsame\n")
        val tool = CodeEditTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "dup.txt", "old_string": "same", "new_string": "diff"}"""
        ).render()
        assertTrue(out, out.contains("matches 2 locations"))
    }

    // ── CodeWrite ───────────────────────────────────────────────────

    @Test
    fun `write refuses overwrite without flag then succeeds`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "f.txt").writeText("original")
        val tool = CodeWriteTool(roots(root))

        val refused = tool.executeSafe("""{"path": "f.txt", "content": "new"}""").render()
        assertTrue(refused, refused.startsWith("Error"))

        val ok = tool.executeSafe("""{"path": "f.txt", "content": "new", "overwrite": true}""").render()
        assertTrue(ok, ok.contains("overwrote"))
        assertEquals("new", File(root, "f.txt").readText())
    }

    // ── CodeGlob / CodeGrep ────────────────────────────────────────

    @Test
    fun `glob finds by extension recursively`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "src").mkdirs()
        File(root, "src/A.kt").writeText("x")
        File(root, "src/sub").mkdirs()
        File(root, "src/sub/B.kt").writeText("x")
        File(root, "readme.md").writeText("x")

        val out = CodeGlobTool(roots(root)).executeSafe("""{"pattern": "**/*.kt"}""").render()
        assertTrue(out, out.contains("src/A.kt"))
        assertTrue(out, out.contains("src/sub/B.kt"))
        assertFalse(out, out.contains("readme.md"))
    }

    @Test
    fun `grep groups by file with line numbers`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "one.txt").writeText("alpha\nbeta\nalpha\n")
        File(root, "two.txt").writeText("gamma\n")

        val out = CodeGrepTool(roots(root)).executeSafe("""{"pattern": "alpha"}""").render()
        assertTrue(out, out.contains("one.txt:"))
        assertTrue(out, out.contains("Line 1: alpha"))
        assertFalse(out, out.contains("two.txt"))
    }

    // ── CodeTodo ────────────────────────────────────────────────────

    @Test
    fun `todo replaces full list and renders progress`() = runTest {
        val tool = CodeTodoTool()
        val payload = """
            {"todos": "[{\"content\": \"a\", \"status\": \"completed\"}, {\"content\": \"b\", \"status\": \"in_progress\", \"priority\": \"high\"}]"}
        """.trimIndent()
        val out = tool.executeSafe(payload).render()
        assertTrue(out, out.contains("1/2"))
        assertTrue(out, out.contains("[x] 1. a"))
        assertTrue(out, out.contains("(!)"))

        assertEquals(2, tool.snapshot().size)
        tool.clear()
        assertEquals(0, tool.snapshot().size)
    }

    @Test
    fun `todo rejects invalid status`() = runTest {
        val tool = CodeTodoTool()
        val out = tool.executeSafe("""{"todos": "[{\"content\": \"a\", \"status\": \"done\"}]"}""").render()
        assertTrue(out, out.contains("invalid status"))
    }

    // ── 双形态（内联数组 / 字符串编码）与数量上限 ──────────────────

    @Test
    fun `todo accepts inline json array`() = runTest {
        val tool = CodeTodoTool()
        val payload = """
            {"todos": [{"content": "a", "status": "completed"}, {"content": "b", "status": "in_progress", "priority": "high"}]}
        """.trimIndent()
        val out = tool.executeSafe(payload).render()
        assertTrue(out, out.contains("1/2"))
        assertTrue(out, out.contains("[x] 1. a"))
        assertEquals(2, tool.snapshot().size)
    }

    @Test
    fun `todo wraps a single inline object into an array`() = runTest {
        val tool = CodeTodoTool()
        val out = tool.executeSafe("""{"todos": {"content": "only", "status": "pending"}}""").render()
        assertTrue(out, out.contains("1. only"))
        assertEquals(1, tool.snapshot().size)
    }

    @Test
    fun `todo has no item count limit`() = runTest {
        // 用户规格：一个流水式输出不限制清单数量 —— 旧 max 50 硬拒绝已移除
        val tool = CodeTodoTool()
        val items = (1..80).joinToString(",") { """{"content": "step $it", "status": "pending"}""" }
        val out = tool.executeSafe("""{"todos": [$items]}""").render()
        assertTrue(out, out.contains("0/80"))
        assertEquals(80, tool.snapshot().size)
    }
}
