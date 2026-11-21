package com.apex.agent.core.tools

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：畸形工具名（空串 / 字面 "null"）的专项未找到文案
 * （2026-11「MCP null」全链失效事故）。
 *
 * 事故：provider 把 function.name 丢成 JSON null 后，注册表按通用文案报
 * `Tool 'null' not found (N tools registered)` + 类别清单 —— 模型看到自己
 * 明明调用了有效 MCP 工具却被报 null，无从修复只能连环换名重试直至任务
 * 报废。修复后：空名/null 名走专项文案（明说「名称在传输中丢失，原样重发
 * 一次」），普通未知 id 保持通用文案（相近建议 + 类别清单）。
 *
 * 两条执行入口（[DefaultToolExecutor.execute] / [EnhancedToolExecutor]）的
 * 未找到路径共用 [ToolExecutionPipeline.notFoundMessage]，此处经默认执行器
 * 验证流式入口的完整链路（纯 JVM，无 mock：真实 DefaultToolRegistry）。
 */
class DegenerateToolNameGuidanceTest {

    @Test
    fun `空名调用收到传输丢名专项指引`() = runTest {
        val events = DefaultToolExecutor(DefaultToolRegistry())
            .executeStream("", "{}").toList()
        val error = events.filterIsInstance<ToolStreamEvent.Error>().single()
        assertTrue(
            "文案应指明名称在传输中丢失：${error.message}",
            error.message.contains("no usable tool name")
        )
        assertTrue(
            "文案应给出自愈动作（原样重发）：${error.message}",
            error.message.contains("re-issue")
        )
    }

    @Test
    fun `字面 null 名调用收到同款专项指引`() = runTest {
        val result = DefaultToolExecutor(DefaultToolRegistry()).execute("null", "{}")
        assertTrue(result.contains("no usable tool name"))
        // 专项文案绝不能再出现 "Tool 'null' not found" 通用句式
        assertTrue(!result.contains("'null' not found"))
    }

    @Test
    fun `普通未知 id 保持通用未找到文案与类别指引`() = runTest {
        val registry = DefaultToolRegistry()
        val events = DefaultToolExecutor(registry)
            .executeStream("NoSuchToolAnywhere", "{}").toList()
        val error = events.filterIsInstance<ToolStreamEvent.Error>().single()
        assertTrue(
            "通用文案应保留 not found 句式：${error.message}",
            error.message.contains("Tool 'NoSuchToolAnywhere' not found")
        )
        assertTrue(
            "通用文案应保留 ToolList 指引：${error.message}",
            error.message.contains("ToolList()")
        )
    }

    @Test
    fun `专项文案对空名与 null 名完全一致 无注册表依赖`() = runTest {
        val message = ToolExecutionPipeline.notFoundMessage("null", DefaultToolRegistry())
        assertEquals(
            message,
            ToolExecutionPipeline.notFoundMessage("", DefaultToolRegistry())
        )
    }
}
