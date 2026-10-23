package com.apex.agent.ui.screen.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * [StreamingParagraphSlicer] 契约测试（2026-10 流水式输出优化）。
 *
 * 核心不变量（每条用例都直接或间接锁死）：
 * 1. 重组等价 —— 正常 append-only 增长路径下
 *    `committed.joinToString("") { it + "\n" } + liveTail == 全文`
 *    （分段渲染与单 Text 视觉逐行等价的前提）；
 * 2. 配平才定稿 —— 行内 ` / ** 标记未配平的行组绝不切开（跨行 code/bold
 *    与代码围栏块作为整体定稿，样式化产物与旧全量 styleInline 一致）；
 * 3. 收缩/改写自动全量重建 —— 新一轮回复（text=""）与任何非前缀延伸的
 *    输入都收敛到正确切片，无需显式 reset；
 * 4. 降级语义 —— 永不闭合的标记让相关行组留在 liveTail（退化为旧实现的
 *    全量重排成本，不会更差）。
 */
class StreamingParagraphSlicerTest {

    /** 不变量 1 的断言助手：切片重组 == 全文。 */
    private fun assertReconstruction(slicer: StreamingParagraphSlicer, text: String) {
        val slices = slicer.update(text)
        val reconstructed = slices.committed.joinToString("") { it + "\n" } + slices.liveTail
        assertEquals("切片重组必须等于全文", text, reconstructed)
    }

    @Test fun `empty text yields empty slices`() {
        val slicer = StreamingParagraphSlicer()
        val s = slicer.update("")
        assertTrue(s.committed.isEmpty())
        assertEquals("", s.liveTail)
    }

    @Test fun `single open line stays entirely in live tail`() {
        val slicer = StreamingParagraphSlicer()
        val s = slicer.update("hello streaming world")
        assertTrue("无换行的开放行不定稿", s.committed.isEmpty())
        assertEquals("hello streaming world", s.liveTail)
    }

    @Test fun `completed lines commit incrementally across flushes`() {
        val slicer = StreamingParagraphSlicer()
        // flush 1：第一行未闭合
        var s = slicer.update("第一行")
        assertTrue(s.committed.isEmpty())
        // flush 2：第一行闭合 → 定稿；第二行开放
        s = slicer.update("第一行\n第二行")
        assertEquals(listOf("第一行"), s.committed)
        assertEquals("第二行", s.liveTail)
        // flush 3：第二行也闭合；第三行开放
        s = slicer.update("第一行\n第二行\n第三行进行中")
        assertEquals(listOf("第一行", "第二行"), s.committed)
        assertEquals("第三行进行中", s.liveTail)
    }

    @Test fun `trailing newline commits the line and leaves empty tail`() {
        val slicer = StreamingParagraphSlicer()
        val s = slicer.update("abc\n")
        assertEquals(listOf("abc"), s.committed)
        assertEquals("", s.liveTail)
    }

    @Test fun `consecutive newlines commit empty lines as blocks`() {
        val slicer = StreamingParagraphSlicer()
        // "a\n\nb"：三行（a / 空 / b）—— 空行也占一行高度，必须保留为空块
        val s = slicer.update("a\n\nb")
        assertEquals(listOf("a", ""), s.committed)
        assertEquals("b", s.liveTail)
    }

    @Test fun `cross-line backtick pair commits as one block`() {
        val slicer = StreamingParagraphSlicer()
        // 第一行反引号未闭合 → 不定稿；第二行闭合 → 两行作为一个块
        var s = slicer.update("abc `def")
        assertTrue(s.committed.isEmpty())
        s = slicer.update("abc `def\nghi` jkl\nnext")
        assertEquals(listOf("abc `def\nghi` jkl"), s.committed)
        assertEquals("next", s.liveTail)
    }

    @Test fun `code fence block commits as a single block when closed`() {
        val slicer = StreamingParagraphSlicer()
        var s = slicer.update("```kotlin\n")
        assertTrue("围栏开启（3 个反引号 = 奇配平）不定稿", s.committed.isEmpty())
        s = slicer.update("```kotlin\nval x = 1\n```\n后续文字")
        assertEquals(
            listOf("```kotlin\nval x = 1\n```"),
            s.committed
        )
        assertEquals("后续文字", s.liveTail)
    }

    @Test fun `bold markers pair across lines like backticks`() {
        val slicer = StreamingParagraphSlicer()
        var s = slicer.update("a **bold")
        assertTrue(s.committed.isEmpty())
        s = slicer.update("a **bold\nspan** done\n")
        assertEquals(listOf("a **bold\nspan** done"), s.committed)
        assertEquals("", s.liveTail)
    }

    @Test fun `unclosed marker degrades to live tail without loss`() {
        val slicer = StreamingParagraphSlicer()
        // 永不闭合的反引号：相关行组整体留在尾段（降级语义）
        var s = slicer.update("never closed `code\nsecond line\nthird")
        assertTrue(s.committed.isEmpty())
        s = slicer.update("never closed `code\nsecond line\nthird line")
        assertTrue("未配平的行组绝不定稿", s.committed.isEmpty())
        assertEquals("never closed `code\nsecond line\nthird line", s.liveTail)
        // 重组不变量仍然成立
        assertReconstruction(slicer, "never closed `code\nsecond line\nthird line")
    }

    @Test fun `shrink triggers full rebuild`() {
        val slicer = StreamingParagraphSlicer()
        slicer.update("第一行\n第二行\n第三行")
        assertEquals(listOf("第一行", "第二行"), slicer.update("第一行\n第二行\n第三行").committed)
        // 媒体尾段截除等场景：文本收缩 → 前缀校验失败 → 全量重建
        val s = slicer.update("第一行")
        assertTrue(s.committed.isEmpty())
        assertEquals("第一行", s.liveTail)
        // 重建后继续正常增量
        val s2 = slicer.update("第一行\n第二行新内容")
        assertEquals(listOf("第一行"), s2.committed)
        assertEquals("第二行新内容", s2.liveTail)
    }

    @Test fun `new response round starts from clean state`() {
        val slicer = StreamingParagraphSlicer()
        slicer.update("上一轮回复 line1\nline2\ndone")
        // 新一轮回复：text 先清空再增长
        var s = slicer.update("")
        assertTrue(s.committed.isEmpty())
        assertEquals("", s.liveTail)
        s = slicer.update("新一轮")
        assertTrue(s.committed.isEmpty())
        assertEquals("新一轮", s.liveTail)
    }

    @Test fun `mid-text rewrite triggers rebuild`() {
        val slicer = StreamingParagraphSlicer()
        slicer.update("abc\ndef")
        // 长度足够但中段被改写（防御路径，正常流不会发生）→ 全量重建后
        // 仍按行定稿：abc 行配平可定稿，改写只影响尾段
        val s = slicer.update("abc\ndXf")
        assertEquals(listOf("abc"), s.committed)
        assertEquals("dXf", s.liveTail)
    }

    @Test fun `reconstruction invariant holds across a long streaming session`() {
        val slicer = StreamingParagraphSlicer()
        val lines = listOf(
            "# 标题行",
            "普通段落，包含 `inline code` 和 **bold**。",
            "```python",
            "def hello():",
            "    print('world')",
            "```",
            "围栏后的正文，反引号 `再开一个",
            "跨行闭合` 收尾。",
            "最后一行永远在流"
        )
        val full = lines.joinToString("\n")
        // 模拟逐字符/逐片段流入：每个前缀都过一遍 update 并验证重组等价
        var prefix = ""
        for (chunk in full.chunked(7)) {
            prefix += chunk
            assertReconstruction(slicer, prefix)
        }
        // 终态：定稿块边界正确（围栏块整体 + 跨行 code 块整体）
        val final = slicer.update(full)
        assertEquals(full, final.committed.joinToString("") { it + "\n" } + final.liveTail)
        assertEquals("最后一行永远在流", final.liveTail)
        assertTrue(
            "代码围栏块必须作为单一多行块定稿",
            final.committed.any { it.contains("```python") && it.contains("print('world')") }
        )
        assertTrue(
            "跨行 code 行组必须作为单一多行块定稿",
            final.committed.any { it.contains("再开一个") && it.contains("跨行闭合") }
        )
    }

    @Test fun `marker parity is computed per pending region not per line`() {
        val slicer = StreamingParagraphSlicer()
        // 行1 单个反引号（奇）；行2 又一个反引号（偶）→ 两行整体配平定稿
        val s = slicer.update("open ` then close\non next line`\n")
        assertEquals(listOf("open ` then close\non next line`"), s.committed)
        assertEquals("", s.liveTail)
    }

    @Test fun `crlf line endings commit blocks without trailing carriage return`() {
        val slicer = StreamingParagraphSlicer()
        // Text 对尾随 \r 会多渲染一个空行 → \r 必须随 \n 一起消费、不进定稿块
        var s = slicer.update("a\r\nb\r\n")
        assertEquals(listOf("a", "b"), s.committed)
        assertEquals("", s.liveTail)
        // 前缀校验（含 \r）在后续增量下仍然成立
        s = slicer.update("a\r\nb\r\nc")
        assertEquals(listOf("a", "b"), s.committed)
        assertEquals("c", s.liveTail)
    }

    @Test fun `lone carriage return stays inside its block`() {
        val slicer = StreamingParagraphSlicer()
        // 孤立 \r（非 \r\n）：单 Text 把它当换行渲染 → 留在块内保持行为一致
        val s = slicer.update("a\rb\n")
        assertEquals(listOf("a\rb"), s.committed)
        assertEquals("", s.liveTail)
    }
}
