package com.apex.agent.ui.screen.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * mcp.so 安装决策树的预装包提取（[MarketMcpSoController.preinstallPackage]）
 * 纯函数单测 —— 锁定三个不变量：
 * 1. npx 家族命令行：跳过命令与旗标，首个非旗标 token 即包名（**保留
 *    @version 尾缀** —— 预装与启动同版本，MarketHubController /
 *    RegistryMcpInstaller 的版本锁定教训）；
 * 2. 非 npx 家族（uvx/python/自定义二进制）→ null（无预装通道）；
 * 3. 空/全旗标命令行 → null（调用方按「写配置 + 首启下载」路径走）。
 */
class MarketMcpSoPreinstallTest {

    @Test
    fun `npx command line extracts pinned package`() {
        assertEquals(
            "@modelcontextprotocol/server-everything",
            MarketMcpSoController.preinstallPackage(
                "npx", listOf("-y", "@modelcontextprotocol/server-everything")
            )
        )
        // @version 尾缀保留：预装与启动同版本
        assertEquals(
            "@modelcontextprotocol/server-everything@0.5.0",
            MarketMcpSoController.preinstallPackage(
                "npx", listOf("-y", "@modelcontextprotocol/server-everything@0.5.0")
            )
        )
        // 无 scope 包名 / 跨旗标取首个非旗标 token
        assertEquals(
            "mcp-server-fetch",
            MarketMcpSoController.preinstallPackage("npx", listOf("--flag=1", "mcp-server-fetch", "--other"))
        )
        // 大小写归一
        assertEquals(
            "pkg",
            MarketMcpSoController.preinstallPackage("NPX", listOf("-y", "pkg"))
        )
    }

    @Test
    fun `non npm family commands return null`() {
        assertNull(MarketMcpSoController.preinstallPackage("uvx", listOf("mcp-server-fetch")))
        assertNull(MarketMcpSoController.preinstallPackage("python", listOf("-m", "server")))
        assertNull(MarketMcpSoController.preinstallPackage("docker", listOf("run", "img")))
        assertNull(MarketMcpSoController.preinstallPackage(null, listOf("-y", "pkg")))
    }

    @Test
    fun `empty or flag only command lines return null`() {
        assertNull(MarketMcpSoController.preinstallPackage("npx", emptyList()))
        assertNull(MarketMcpSoController.preinstallPackage("npx", listOf("-y", "--silent")))
        assertNull(MarketMcpSoController.preinstallPackage("", listOf("pkg")))
        assertNull(MarketMcpSoController.preinstallPackage("   ", listOf("pkg")))
    }
}
