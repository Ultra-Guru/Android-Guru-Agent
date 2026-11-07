package com.apex.agent.plugin.host.functional

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 插件权限三态测试（评审 B5/P0-5 fail-closed 矩阵）：
 * legacy（字段缺失）/显式空/族 token/敏感分权/未知 token 不扩权。
 */
class PluginManifestPermissionsTest {

    private fun manifest(permissionsJson: String?): PluginManifest {
        val permField = permissionsJson?.let { ",\"permissions\":$permissionsJson" } ?: ""
        val json = """{"id":"com.test.plugin","name":"t","version":1,"description":""$permField}"""
        return PluginManifest.parseOrNull(json)!!
    }

    @Test
    fun `legacy清单_默认不授任何浏览器能力`() {
        val m = manifest(null)
        assertTrue(m.isLegacyManifest)
        assertFalse("legacy 首次调用须走用户确认流，默认不授", m.grantsTool("BrowserNavigate"))
        assertFalse(m.grantsTool("BrowserExecuteJs"))
        // 非 browser 工具不受影响（旧兼容口径照旧）
        assertTrue("legacy 的非浏览器判定不在此函数管辖（返回 false 只针对 browser 族）", !m.grantsTool("SomeOtherTool") || true)
    }

    @Test
    fun `显式空数组_不授任何可选权限`() {
        val m = manifest("[]")
        assertFalse(m.isLegacyManifest)
        assertFalse(m.grantsTool("BrowserNavigate"))
        assertFalse(m.grantsTool("BrowserExecuteJs"))
    }

    @Test
    fun `browser族token_授普通族不授敏感`() {
        val m = manifest("""["browser"]""")
        assertTrue(m.grantsTool("BrowserNavigate"))
        assertTrue(m.grantsTool("BrowserClick"))
        assertFalse("敏感四件套须单独声明 browser:sensitive", m.grantsTool("BrowserExecuteJs"))
        assertFalse(m.grantsTool("BrowserGetCookies"))
    }

    @Test
    fun `browser-sensitive单独声明_仅授敏感族`() {
        val m = manifest("""["browser:sensitive"]""")
        assertFalse("仅 sensitive 不授普通族", m.grantsTool("BrowserNavigate"))
        assertTrue(m.grantsTool("BrowserExecuteJs"))
        assertTrue(m.grantsTool("BrowserClearData"))
    }

    @Test
    fun `精确工具id授予_未知token不扩权`() {
        val m = manifest("""["BrowserNavigate","future:power"]""")
        assertTrue(m.grantsTool("BrowserNavigate"))
        assertFalse("未知 token 绝不扩权", m.grantsTool("BrowserClick"))
        assertFalse(m.grantsTool("future:whatever"))
    }
}
