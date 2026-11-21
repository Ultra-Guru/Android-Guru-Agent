package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #354 ④ 回归：用户可见文案不得残留英文裸句。
 *
 * `listServerTools` / `callTool` 的失败文案会被 `MarketViewModel` 包成
 * 「工具发现失败：…」直接进 snackbar 与启动进度弹窗，也会作为工具结果
 * 回到模型上下文。旧实现是 `Server 'xxx' not connected` —— 中文界面里
 * 冒出一句英文，且没说用户能做什么。
 */
class McpManagerUserFacingErrorTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager() = McpManager(configDir = tmp.root)

    @Test
    fun `未连接服务器列出工具时报中文可行动文案`() = runTest {
        val result = manager().listServerTools("ghost")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("应含服务器名: $message", message.contains("ghost"))
        assertTrue("应说明未连接: $message", message.contains("未连接"))
        assertTrue("应给出下一步: $message", message.contains("连接"))
        assertFalse("不应残留英文裸句: $message", message.contains("not connected"))
    }

    @Test
    fun `未连接服务器调用工具时报中文可行动文案`() = runTest {
        val result = manager().callTool("ghost", "search", "{}")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("应含服务器名: $message", message.contains("ghost"))
        assertTrue("应含工具名: $message", message.contains("search"))
        assertTrue("应说明未连接: $message", message.contains("未连接"))
        assertFalse("不应残留英文裸句: $message", message.contains("not connected"))
    }
}
