package com.apex.agent.core.engine.templates.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 3 工具箱测试 · Markdown 结构扫描与保真比对。
 */
class TemplateMarkdownScannerTest {

    @Test
    fun `blank text yields empty scan`() {
        val scan = TemplateMarkdownScanner.scan("")
        assertFalse(scan.hasStructure)
        assertTrue(scan.headings.isEmpty())
        assertEquals(0, scan.blankLineCount)
    }

    @Test
    fun `headings lists and fences are inventoried`() {
        val text = """
            # 标题一
            正文段落。

            ## 标题二
            - 项目甲
            - 项目乙
            - 项目丙

            ```kotlin
            val a = 1
            val b = 2
            ```

            | 列1 | 列2 |
            |---|---|
            | a | b |
        """.trimIndent()
        val scan = TemplateMarkdownScanner.scan(text)
        assertEquals(2, scan.headings.size)
        assertEquals(1, scan.headings[0].level)
        assertEquals("标题一", scan.headings[0].text)
        assertEquals(3, scan.listItems.size)
        assertFalse(scan.listItems[0].ordered)
        assertEquals(1, scan.codeFences.size)
        assertEquals("kotlin", scan.codeFences[0].language)
        assertEquals(2, scan.codeFences[0].lineCount)
        assertTrue(scan.tableRowCount >= 3)
        assertTrue(scan.hasStructure)
    }

    @Test
    fun `ordered list items carry ordered flag`() {
        val scan = TemplateMarkdownScanner.scan("1. 第一\n2. 第二\n3. 第三")
        assertEquals(3, scan.listItems.size)
        assertTrue(scan.listItems.all { it.ordered })
        assertEquals("第一", scan.listItems[0].text)
    }

    @Test
    fun `unclosed fence is recovered defensively`() {
        val scan = TemplateMarkdownScanner.scan("前置说明\n```python\nprint(1)\nprint(2)")
        assertEquals(1, scan.codeFences.size)
        assertEquals("python", scan.codeFences[0].language)
        // 未闭合围栏按已读行数兜底
        assertEquals(2, scan.codeFences[0].lineCount)
    }

    @Test
    fun `fenced content is not parsed as markdown structure`() {
        val text = """
            正文

            ```
            # 这不是标题
            - 这不是列表
            ```
        """.trimIndent()
        val scan = TemplateMarkdownScanner.scan(text)
        assertTrue(scan.headings.isEmpty())
        assertTrue(scan.listItems.isEmpty())
        assertEquals(1, scan.codeFences.size)
    }

    @Test
    fun `placeholder inventory dedupes and counts`() {
        val text = "你好 {{name}}，重试 {{retry_count}} 次，{{name}} 收到 %s 与 %d 以及 {0}。"
        val placeholders = TemplateMarkdownScanner.inventoryPlaceholders(text)
        val names = placeholders.map { it.raw }
        assertTrue("{{name}}" in names)
        assertTrue("%s" in names)
        assertTrue("%d" in names)
        assertTrue("{0}" in names)
        assertEquals(2, placeholders.first { it.raw == "{{name}}" }.count)
    }

    @Test
    fun `fidelity compare reports structural loss`() {
        val source = TemplateMarkdownScanner.scan(
            """
            ## 安装步骤
            - 步骤一
            - 步骤二
            {{version}} 是占位符
            ```bash
            echo hi
            ```
            """.trimIndent()
        )
        val faithful = TemplateMarkdownScanner.scan(
            """
            ## 安装步骤
            - 步骤一
            - 步骤二
            {{version}} 保留了
            ```bash
            echo hi
            ```
            """.trimIndent()
        )
        val lossy = TemplateMarkdownScanner.scan(
            "安装步骤直接合并成一段话，占位符丢了，代码块也没了。"
        )
        val good = TemplateMarkdownScanner.compareFidelity(source, faithful)
        assertTrue(good.isFaithful)
        val bad = TemplateMarkdownScanner.compareFidelity(source, lossy)
        assertFalse(bad.isFaithful)
        assertTrue(bad.missingHeadings.contains("安装步骤"))
        assertTrue(bad.missingPlaceholders.contains("{{version}}"))
        assertEquals(-2, bad.listItemCountDelta)
        assertEquals(-1, bad.codeFenceDelta)
        val described = bad.describe()
        assertTrue(described.any { it.contains("丢失标题") })
        assertTrue(described.any { it.contains("占位符丢失") })
    }

    @Test
    fun `structure signature reflects composition`() {
        val scan = TemplateMarkdownScanner.scan("# A\n## B\n- x\n- y")
        assertTrue(scan.structureSignature().startsWith("H1;H2;L2;"))
    }

    @Test
    fun `table separator detection`() {
        assertTrue(TemplateMarkdownScanner.isTableSeparator("|---|---|"))
        assertTrue(TemplateMarkdownScanner.isTableSeparator("| :--- | ---: |"))
        assertFalse(TemplateMarkdownScanner.isTableSeparator("| a | b |"))
    }
}
