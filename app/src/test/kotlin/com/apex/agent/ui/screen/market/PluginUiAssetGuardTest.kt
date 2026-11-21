package com.apex.agent.ui.screen.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PluginUiAssetGuard] 单元测试 —— #818 资产域路径守卫的纯逻辑契约。
 *
 * 覆盖：
 * - 入口 URL 构造：正常映射 / 相对路径百分号编码（中文·空格·特殊字符）/
 *   root 外 entry 拒绝；
 * - 子资源解析：正常映射 / `..` 穿越段拒绝 / 挂载点外路径拒绝 / 空相对
 *   路径拒绝 / canonical 边界（绝对段注入仍被钉在 root 内）；
 * - 编码往返：encodePath ↔ decodePath 对任意形状保持恒等（坏编码折叠
 *   原文段，不向上抛 —— AGENTS.md 防御式 IO）；
 * - 资产域 host 判定的大小写不敏感。
 *
 * （WebView 装配 / shouldInterceptRequest / MIME 流打开依赖 Android 运行时，
 * 不在 JVM 单测射程 —— 真机插件页「打开页面」人工验收。）
 */
class PluginUiAssetGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pluginRoot(): File = tmp.newFolder("plugin-a").apply {
        resolve("ui").mkdirs()
        resolve("ui").resolve("index.html").createNewFile()
        resolve("页面 目录").mkdirs()
        resolve("页面 目录").resolve("主 页面.html").createNewFile()
    }

    // ── 入口 URL 构造 ─────────────────────────────────────────────────

    @Test
    fun `入口文件映射到资产域 URL`() {
        val root = pluginRoot()
        val entry = File(root, "ui/index.html")
        assertEquals(
            "https://appassets.androidplatform.net/assets/ui/index.html",
            PluginUiAssetGuard.entryUrl(root, entry)
        )
    }

    @Test
    fun `中文与空格路径逐段百分号编码`() {
        val root = pluginRoot()
        val entry = File(root, "页面 目录/主 页面.html")
        val url = PluginUiAssetGuard.entryUrl(root, entry)
        assertNotNull(url)
        // 空格必须是 %20（path 语义），中文按 UTF-8 百分号编码
        assertTrue("空格应编码为 %20：$url", url!!.contains("%20"))
        assertFalse("不应出现 form 编码的加号：$url", url.contains("+"))
        // 编码往返恒等：解码后的 path 再 resolve 必须回到同一文件
        val encoded = url.removePrefix("https://${PluginUiAssetGuard.ASSET_DOMAIN}")
        val decoded = PluginUiAssetGuard.decodePath(encoded)
        assertEquals(entry.canonicalPath, PluginUiAssetGuard.resolve(decoded, root)?.canonicalPath)
    }

    @Test
    fun `root 外的 entry 拒绝构造 URL`() {
        val root = pluginRoot()
        val outsider = tmp.newFile("outsider.html")
        assertNull(PluginUiAssetGuard.entryUrl(root, outsider))
    }

    // ── 子资源解析 ─────────────────────────────────────────────────────

    @Test
    fun `资产域 path 解析回插件目录内文件`() {
        val root = pluginRoot()
        val resolved = PluginUiAssetGuard.resolve("/assets/ui/index.html", root)
        assertNotNull(resolved)
        assertTrue(
            resolved!!.canonicalPath.startsWith(root.canonicalPath + File.separator)
        )
    }

    @Test
    fun `双点穿越段拒绝`() {
        val root = pluginRoot()
        // 直接字面 ..（URL 未编码形态）
        assertNull(PluginUiAssetGuard.resolve("/assets/../secret.txt", root))
        // 深层混合：合法前缀 + 中段 .. + 合法尾段
        assertNull(PluginUiAssetGuard.resolve("/assets/ui/../../secret.txt", root))
        // 解码后的 %2E%2E（百分号编码形态经 WebView 解码后同样落网）
        assertNull(PluginUiAssetGuard.resolve(PluginUiAssetGuard.decodePath("/assets/%2E%2E/secret.txt"), root))
    }

    @Test
    fun `挂载点外路径拒绝`() {
        val root = pluginRoot()
        assertNull(PluginUiAssetGuard.resolve("/ui/index.html", root))
        assertNull(PluginUiAssetGuard.resolve("/", root))
        assertNull(PluginUiAssetGuard.resolve("", root))
    }

    @Test
    fun `空相对路径拒绝`() {
        val root = pluginRoot()
        // 恰好挂载点自身（目录，不是文件资源）
        assertNull(PluginUiAssetGuard.resolve("/assets/", root))
    }

    @Test
    fun `绝对段注入仍被钉在 root 内`() {
        val root = pluginRoot()
        // child 以分隔符开头时 java.io.File(File, String) 仍拼进 root ——
        // 解析结果不会逃逸插件目录（不存在即调用方 isFile 拒绝 → 404）
        val resolved = PluginUiAssetGuard.resolve("/assets//etc/passwd", root)
        if (resolved != null) {
            assertTrue(
                "绝对段不得逃逸插件目录",
                resolved.canonicalPath.startsWith(root.canonicalPath + File.separator)
            )
        }
    }

    // ── 编码往返 ───────────────────────────────────────────────────────

    @Test
    fun `编码解码往返恒等`() {
        val samples = listOf(
            "ui/index.html",
            "页面 目录/主 页面.html",
            "a+b/c d.js",
            "100%.json",
            "emoji-🎉.css"
        )
        for (sample in samples) {
            assertEquals(
                "往返恒等：$sample",
                sample,
                PluginUiAssetGuard.decodePath(PluginUiAssetGuard.encodePath(sample))
            )
        }
    }

    @Test
    fun `坏百分号序列折叠原文不抛出`() {
        // 尾部孤立的 % 与非十六进制续段：防御式 IO —— 折叠原文段
        assertEquals("a%.js", PluginUiAssetGuard.decodePath("a%.js"))
        assertEquals("a%zz.js", PluginUiAssetGuard.decodePath("a%zz.js"))
    }

    // ── host 判定 ──────────────────────────────────────────────────────

    @Test
    fun `资产域 host 判定大小写不敏感`() {
        assertTrue(PluginUiAssetGuard.isAssetHost("appassets.androidplatform.net"))
        assertTrue(PluginUiAssetGuard.isAssetHost("APPASSETS.ANDROIDPLATFORM.NET"))
        assertFalse(PluginUiAssetGuard.isAssetHost("evil.com"))
        assertFalse(PluginUiAssetGuard.isAssetHost(null))
    }
}
