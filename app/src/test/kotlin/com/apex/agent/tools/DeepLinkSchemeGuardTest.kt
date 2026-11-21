package com.apex.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #460 DeepLink 危险 scheme 守卫契约：
 * - 合法深链（http/https/market/app/settings 等链接型 scheme）放行；
 * - 代码执行（javascript/vbscript/data）、本地文件触达（file/content）、
 *   组件穿透（intent）在工具边界拒绝；
 * - 大小写不敏感（模型输出 JAVASCRIPT: 同样拦截）。
 */
class DeepLinkSchemeGuardTest {

    @Test
    fun `link-like schemes are allowed`() {
        assertTrue(!DeepLinkTool.isDangerousScheme("https"))
        assertTrue(!DeepLinkTool.isDangerousScheme("http"))
        assertTrue(!DeepLinkTool.isDangerousScheme("market"))
        assertTrue(!DeepLinkTool.isDangerousScheme("app"))
        assertTrue(!DeepLinkTool.isDangerousScheme("mailto"))
    }

    @Test
    fun `executable and file schemes are blocked`() {
        assertTrue(DeepLinkTool.isDangerousScheme("javascript"))
        assertTrue(DeepLinkTool.isDangerousScheme("vbscript"))
        assertTrue(DeepLinkTool.isDangerousScheme("data"))
        assertTrue(DeepLinkTool.isDangerousScheme("file"))
        assertTrue(DeepLinkTool.isDangerousScheme("content"))
        assertTrue(DeepLinkTool.isDangerousScheme("intent"))
    }

    @Test
    fun `scheme check is case insensitive`() {
        assertTrue(DeepLinkTool.isDangerousScheme("JAVASCRIPT"))
        assertTrue(DeepLinkTool.isDangerousScheme("File"))
        assertTrue(DeepLinkTool.isDangerousScheme("INTENT"))
        assertFalse(DeepLinkTool.isDangerousScheme("HTTPS"))
    }

    @Test
    fun `plausible uri precheck still accepts dangerous schemes as structurally valid`() {
        // 预检只做结构校验（有合法形态的 scheme）—— 危险性由 isDangerousScheme 分层负责
        assertTrue(DeepLinkTool.isPlausibleUri("javascript:alert(1)"))
        assertTrue(DeepLinkTool.isPlausibleUri("https://example.com"))
        assertFalse(DeepLinkTool.isPlausibleUri("no scheme here"))
    }
}
