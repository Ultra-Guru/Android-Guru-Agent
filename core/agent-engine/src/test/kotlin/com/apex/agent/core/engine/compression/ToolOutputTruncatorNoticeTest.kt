package com.apex.agent.core.engine.compression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话实录 #6 回归锁 —— 截断产物尾部机器可读告示。
 *
 * 实录事故：HttpRequest 成功取回 75,506 字节 JSON，工具层展示截断插入了
 * “[... 3350 chars omitted ...]” —— 模型把该标记当成“内容到此为止”，拿可见
 * 片段当全文交差，实际正文 20,022 字符。TruncationResult.truncated 标志又在
 * 引擎层被丢弃（ToolResult 无元数据通道），模型唯一的信息源就是文本本身。
 *
 * 修复承诺（必须逐条锁死）：
 * 1. 所有截断路径（默认 head+tail / JSON 字段级 / JSON 回退 / 列表 / 头部 /
 *    Error 前缀）的产物尾部都带 [OUTPUT TRUNCATED: ...] 告示；
 * 2. 告示明说 INCOMPLETE（不能据此下结论）；
 * 3. 未截断输出零污染（不含告示）；
 * 4. 幂等：已含告示的文本再次过截断器不重复追加（压缩器二次截断场景）。
 */
class ToolOutputTruncatorNoticeTest {

    @Test
    fun `default head-tail truncation appends machine-readable notice`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val long = "A".repeat(5150) // 默认预算下省略区恰好 ~3350 —— 实录同款形态
        val result = truncator.smartTruncate(long, "HttpRequest")
        assertTrue(result.truncated)
        assertTrue(
            "尾部必须带统一告示：...${result.text.takeLast(120)}",
            result.text.endsWith(ToolOutputTruncator.TRUNCATION_NOTICE)
        )
        assertTrue("告示必须明说 INCOMPLETE", result.text.contains("INCOMPLETE"))
        assertTrue("省略计数标记保留", result.text.contains("chars omitted"))
    }

    @Test
    fun `json field-level truncation keeps verdict scalars and carries notice`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val stdout = "x".repeat(12_000)
        val json = """{"stdout":"$stdout","stderr":"","exit_code":127,"channel":"proot",""" +
            """"duration_ms":1234,"truncated":false,"timed_out":true}"""
        val result = truncator.smartTruncate(json, "TerminalExec")
        assertTrue(result.truncated)
        assertTrue("exit_code 必须保留", result.text.contains("\"exit_code\":127"))
        assertTrue("timed_out 必须保留", result.text.contains("\"timed_out\":true"))
        assertTrue("截断告示必须在", result.text.contains(ToolOutputTruncator.TRUNCATION_NOTICE))
    }

    @Test
    fun `error-prefixed truncation carries notice`() {
        val truncator = ToolOutputTruncator(maxChars = 500)
        val result = truncator.smartTruncate("Error: " + "e".repeat(3_000), "ShellExecute")
        assertTrue(result.truncated)
        assertTrue(result.text.contains("[... error output truncated"))
        assertTrue(result.text.contains(ToolOutputTruncator.TRUNCATION_NOTICE))
    }

    @Test
    fun `list truncation carries notice`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        // > maxChars 且 > 30 行 —— 命中列表截断路径（首 15 + 尾 10）
        val list = (1..120).joinToString("\n") { "package-number-with-padding-$it" }
        val result = truncator.smartTruncate(list, "TerminalExec")
        assertTrue(result.truncated)
        assertTrue(result.text.contains("items omitted"))
        assertTrue(result.text.contains(ToolOutputTruncator.TRUNCATION_NOTICE))
    }

    @Test
    fun `short output passes through without notice`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val short = "hello world"
        val result = truncator.smartTruncate(short, "WebFetch")
        assertFalse(result.truncated)
        assertEquals(short, result.text)
        assertFalse("未截断输出不得含告示", result.text.contains("OUTPUT TRUNCATED"))
    }

    @Test
    fun `notice is idempotent under double truncation`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val first = truncator.smartTruncate("B".repeat(9_000), "Read")
        assertTrue(first.truncated)
        // 压缩器场景：截断产物作为历史再次过截断器 → 告示只出现一次
        val second = truncator.smartTruncate(first.text, "Read")
        val occurrences = second.text.split(ToolOutputTruncator.TRUNCATION_NOTICE).size - 1
        assertEquals("二次截断后告示恰好一次", 1, occurrences)
    }
}
