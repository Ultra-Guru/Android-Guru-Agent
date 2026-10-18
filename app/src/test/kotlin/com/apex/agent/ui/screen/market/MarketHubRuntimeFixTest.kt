package com.apex.agent.ui.screen.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 沙箱运行时一键安装命令映射（[MarketHubController.runtimeFixCommand]）的
 * 纯函数单测 —— 锁定三个不变量：
 * 1. nodejs 家族（npx/npm/node/bunx，含路径形态/大小写）→ apt 装
 *    nodejs + npm（且 apt-get update 先行，rootfs 空索引直接 install 必败）；
 * 2. python 家族 → apt 装 python3；
 * 3. uv 家族 / 未知命令 → null（无可靠一键通道，文案引导手动装 ——
 *    不给「假装能修」的按钮）。
 */
class MarketHubRuntimeFixTest {

    @Test
    fun `nodejs family maps to apt install with update first`() {
        val expected = listOf("sh", "-c", "apt-get update -qq && apt-get install -y -qq nodejs npm")
        assertEquals(expected, MarketHubController.runtimeFixCommand("npx"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("npm"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("node"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("bunx"))
        // 大小写 / 路径形态归一
        assertEquals(expected, MarketHubController.runtimeFixCommand("NPX"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("/usr/bin/npx"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("  npx  "))
    }

    @Test
    fun `python family maps to apt install python3`() {
        val expected = listOf("sh", "-c", "apt-get update -qq && apt-get install -y -qq python3")
        assertEquals(expected, MarketHubController.runtimeFixCommand("python"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("python3"))
        assertEquals(expected, MarketHubController.runtimeFixCommand("/usr/bin/python3"))
    }

    @Test
    fun `uv and unknown commands have no one tap fix`() {
        assertNull(MarketHubController.runtimeFixCommand("uvx"))
        assertNull(MarketHubController.runtimeFixCommand("uv"))
        assertNull(MarketHubController.runtimeFixCommand("java"))
        assertNull(MarketHubController.runtimeFixCommand(""))
        assertNull(MarketHubController.runtimeFixCommand("   "))
    }
}
