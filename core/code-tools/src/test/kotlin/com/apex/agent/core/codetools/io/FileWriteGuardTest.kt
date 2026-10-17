package com.apex.agent.core.codetools.io

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.tools.CodeEditTool
import com.apex.agent.core.codetools.tools.CodeWriteTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [FileWriteGuard] / [TextFileStyle] 与两个写工具（CodeEdit / CodeWrite）
 * 的持久化契约测试：
 * - 原子写不留 tmp 残留；per-path 锁可重入（嵌套不自锁）；
 * - BOM/CRLF 探测、归一（normalize）与还原（apply）互为逆变换；
 * - CodeWrite 覆盖保留原 BOM/CRLF；超 4MB 文件 diff 降级为统计行提示；
 * - CodeEdit 编辑 CRLF 文件按原行尾还原。
 */
class FileWriteGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roots(root: File): CodeWorkspaceRoots = CodeWorkspaceRoots { root }

    // ── 原子写 ──────────────────────────────────────────────────────

    @Test
    fun `atomic write persists content and leaves no tmp residue`() {
        val f = tmp.newFile("a.txt")
        FileWriteGuard.writeFileAtomically(f, "hello\nworld")
        assertEquals("hello\nworld", f.readText())
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test
    fun `atomic write creates missing parent directories`() {
        val dir = tmp.newFolder("base")
        val f = File(File(dir, "sub/deep"), "nested.txt")
        FileWriteGuard.writeFileAtomically(f, "content")
        assertEquals("content", f.readText())
    }

    @Test
    fun `path lock is reentrant for nested critical sections`() {
        val f = tmp.newFile("lock.txt")
        val result = FileWriteGuard.withPathLock(f) {
            FileWriteGuard.withPathLock(f) { "inner" }
        }
        assertEquals("inner", result)
    }

    // ── 文本风格探测与还原 ──────────────────────────────────────────

    @Test
    fun `style detects bom and crlf from full text`() {
        val plain = TextFileStyle.of("a\nb\n")
        assertFalse(plain.hasBom)
        assertFalse(plain.hasCrlf)

        val styled = TextFileStyle.of("\uFEFFa\r\nb\r\n")
        assertTrue(styled.hasBom)
        assertTrue(styled.hasCrlf)
    }

    @Test
    fun `normalize and apply are inverse for bom plus crlf text`() {
        val raw = "\uFEFFfirst line\r\nsecond\r\n"
        val style = TextFileStyle.of(raw)
        assertEquals(raw, style.apply(style.normalize(raw)))
    }

    @Test
    fun `apply restores bom and crlf for lf content only`() {
        val style = TextFileStyle(hasBom = true, hasCrlf = true)
        // 纯 LF 内容 → 按原风格还原
        assertEquals("\uFEFFa\r\nb\r\n", style.apply("a\nb\n"))
        // 内容自带 \r（模型显式给出 CRLF）→ 不做二次改写
        assertEquals("\uFEFFa\r\nb\r\n", style.apply("a\r\nb\r\n"))
        // 内容自带 BOM → 不重复前置
        assertEquals("\uFEFFa\r\n", style.apply("\uFEFFa\n"))
    }

    @Test
    fun `plain style passes content through untouched`() {
        assertEquals("a\nb", TextFileStyle.PLAIN.apply("a\nb"))
        assertEquals("a\nb", TextFileStyle.PLAIN.normalize("a\nb"))
    }

    @Test
    fun `sniff detects style from file head without full read`() {
        val f = tmp.newFile("s.txt")
        f.writeText("\uFEFFheader\r\nbody\r\n")
        val style = TextFileStyle.sniff(f)
        assertTrue(style.hasBom)
        assertTrue(style.hasCrlf)
    }

    // ── CodeWrite：BOM/CRLF 保留 + 大文件 diff 降级 ────────────────

    @Test
    fun `CodeWrite overwrite preserves bom and crlf`() = runTest {
        val root = tmp.newFolder("ws1")
        val f = File(root, "win.txt")
        f.writeText("\uFEFFline1\r\nline2\r\n")
        val tool = CodeWriteTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "win.txt", "content": "new1\nnew2\n", "overwrite": true}"""
        ).render()
        assertTrue(out, out.contains("overwrote"))
        assertEquals("\uFEFFnew1\r\nnew2\r\n", f.readText())
    }

    @Test
    fun `CodeWrite skips diff for oversized file instead of oom`() = runTest {
        val root = tmp.newFolder("ws2")
        val f = File(root, "big.txt")
        f.writeText("x".repeat(5 * 1024 * 1024)) // > 4MB MAX_FILE_BYTES
        val tool = CodeWriteTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "big.txt", "content": "small now", "overwrite": true}"""
        ).render()
        assertTrue(out, out.contains("overwrote"))
        assertTrue(out, out.contains("diff budget"))
        assertEquals("small now", f.readText())
    }

    // ── CodeEdit：CRLF 还原 ────────────────────────────────────────

    @Test
    fun `CodeEdit preserves crlf line endings`() = runTest {
        val root = tmp.newFolder("ws3")
        val f = File(root, "crlf.txt")
        f.writeText("alpha\r\nbeta\r\n")
        val tool = CodeEditTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "crlf.txt", "old_string": "beta", "new_string": "gamma"}"""
        ).render()
        assertTrue(out, out.contains("✅"))
        assertEquals("alpha\r\ngamma\r\n", f.readText())
    }

    @Test
    fun `CodeEdit preserves bom on replace`() = runTest {
        val root = tmp.newFolder("ws4")
        val f = File(root, "bom.txt")
        f.writeText("\uFEFFhello")
        val tool = CodeEditTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "bom.txt", "old_string": "hello", "new_string": "world"}"""
        ).render()
        assertTrue(out, out.contains("✅"))
        assertEquals("\uFEFFworld", f.readText())
    }
}
