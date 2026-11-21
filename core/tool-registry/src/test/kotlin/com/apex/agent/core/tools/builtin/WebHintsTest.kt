package com.apex.agent.core.tools.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话实录 #1/#4/#5 回归锁 —— Web 工具智能提示（纯函数）。
 *
 * 实录事故链：
 * - #5 裸 curl 打 api/v2 被 WAF 405（无浏览器头）；
 * - #4 api/v1 是 404，无向导提示探 v2 —— 靠盲猜才找到；
 * - #1/#2/#3 WebFetch / 浏览器拿到的都是“Download App”空壳，无任何指引，
 *   模型连换三条路全部空转 —— 正确路径（找 XHR 接口）从未被提示。
 *
 * 修复承诺：
 * 1. spaShellHint：正文极短 + 壳特征命中 → 返回“找 API”指引；正常页面 null；
 * 2. httpStatusHint：404 → 探相邻版本；403/405 + WAF 拦截页 → 补浏览器头重试；
 * 3. originOf：同源 Referer 提取，非常规 URL 静默回退空串。
 */
class WebHintsTest {

    // ═══════════════════════ spaShellHint（实录 #1/#2/#3）═══════════════════════

    @Test
    fun `qwen share app-shell page triggers spa hint`() {
        // 实录实测形态：HTML 只有“下载 App”壳文案 + SPA 挂载点
        val html = """
            <html><body><div id="root"></div>
            <noscript>Current System does not Support</noscript>
            Download App Designed for mobile devices
            <script src="/assets/index.js"></script></body></html>
        """.trimIndent()
        val visibleText = "Download App Designed for mobile devices, offering better experience"
        val hint = spaShellHint(html, visibleText)
        assertTrue("空壳页必须返回提示", hint != null)
        assertTrue("提示必须指向 API 路径", hint!!.contains("XHR/API"))
        assertTrue("提示必须建议 HttpRequest", hint.contains("HttpRequest"))
    }

    @Test
    fun `content-rich page never triggers spa hint`() {
        val html = "<html><body><p>" + "real article content. ".repeat(60) + "</p></body></html>"
        assertNull("正文充分时不得误报", spaShellHint(html, "real article content. ".repeat(60)))
    }

    @Test
    fun `short text without shell markers returns null`() {
        // 短但无壳特征（如极简 404 静态页）—— 不误伤
        assertNull(spaShellHint("<html><body>bye</body></html>", "bye"))
    }

    @Test
    fun `chinese shell phrases are detected`() {
        val html = """<html><body><div id="app"></div>请开启 JavaScript 后重试</body></html>"""
        val hint = spaShellHint(html, "请开启 JavaScript 后重试")
        assertTrue("中文壳文案必须命中", hint != null)
    }

    // ═══════════════════════ httpStatusHint（实录 #4/#5）═══════════════════════

    @Test
    fun `404 hints at sibling api versions`() {
        // 实录：api/v1/chats/share/... 404 → 靠盲猜才试出 v2
        val hint = httpStatusHint(404, "{\"detail\":\"Not Found\"}", "application/json")
        assertTrue(hint!!.contains("v1/v2/v3"))
    }

    @Test
    fun `405 with waf block page hints browser headers`() {
        // 实录：阿里云 WAF 拦截页 <title>405</title>，补 UA+Referer 后 200
        val body = "<!doctypehtml><html lang=\"zh-cn\"><title>405</title>" +
            "<style>a,body,div,h2,html,p{margin:0;padding:0}</style>"
        val hint = httpStatusHint(405, body, "text/html")
        assertTrue(hint!!.contains("User-Agent"))
        assertTrue("必须提到 Referer", hint.contains("Referer"))
    }

    @Test
    fun `403 json rejection hints method check without waf wording`() {
        val hint = httpStatusHint(403, "{\"error\":\"forbidden\"}", "application/json")
        assertTrue("非拦截页也要给方向", hint!!.contains("method"))
    }

    @Test
    fun `success and other statuses return no hint`() {
        assertNull(httpStatusHint(200, "<html></html>", "text/html"))
        assertNull(httpStatusHint(500, "boom", "text/plain"))
    }

    // ═══════════════════════ originOf（实录 #5：Referer 同源提取）═══════════════════════

    @Test
    fun `origin extracts scheme host and nonDefault port`() {
        assertEquals("https://chat.qwen.ai/", originOf("https://chat.qwen.ai/s/481db42a?x=1"))
        assertEquals("https://host.example/", originOf("https://host.example"))
        assertEquals("http://127.0.0.1:8080/", originOf("http://127.0.0.1:8080/api/v2/chats"))
        assertEquals("https://host.example/", originOf("https://host.example:443/")) // 默认端口省略
    }

    @Test
    fun `origin falls back to empty on malformed url`() {
        assertEquals("", originOf("::::not-a-url"))
        assertEquals("", originOf(""))
    }
}
