package com.apex.agent.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 技能 per-scope 启停（双工位隔离）+ 就绪门禁（配置）单元测试。
 *
 * 锁定的不变量（用户需求：「市场 coding/agent 完全隔离」「未配置好的不进菜单」）：
 * 1. setEnabledForScope 只写指定工位，另一工位状态不动（市场分级隔离）；
 * 2. 三 sidecar（.disabled 双禁 / .disabled-agent / .disabled-coding）持久化
 *    并跨注册表重建存活；旧版 .disabled（双禁）语义零迁移兼容；
 * 3. 生效工位集合 = manifest 声明 ∩ 双工位启用（effectiveScopesOf）：
 *    getSkillDigests / getActiveToolsWithScope / getPromptInjections 同口径过滤；
 * 4. setEnabled（旧 API）= 双工位同开闭（安装工具/内置升级路径不变）；
 * 5. 必填配置未填齐（isConfigured=false）不影响启停存储，但由
 *    SkillMenuProvider / SkillAutoActivator 消费方过滤（规则见 SkillConfigRulesTest）；
 *    配置值经 setConfigValues 持久化（.config.json）并跨 manifest 升级存活。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder），与同包 BundledSkillsTest 同构。
 */
class SkillRegistryScopeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var registry: SkillRegistry

    @Before
    fun setUp() {
        skillsDir = tmp.newFolder()
        registry = SkillRegistry(skillsDir)
    }

    // ═══ helpers ═══════════════════════════════════════════════════════

    /** 最小可安装 prompt 型 manifest（scope / userConfig 由参数控制）。 */
    private fun manifestJson(
        id: String,
        scope: String? = null,
        userConfig: String = "",
        prompt: String = "方法论内容 ${id}"
    ): String {
        val scopeField = scope?.let { ",\n  \"scope\": \"$it\"" } ?: ""
        return """
        {
          "schema": "apex-skill-v1",
          "id": "$id",
          "name": "$id",
          "version": "1.0.0",
          "description": "测试技能 $id",
          "promptInjection": "$prompt"$scopeField,
          "tools": [],
          "configuration": {"autoSetup": [], "userConfig": [$userConfig]}
        }
        """.trimIndent()
    }

    private fun install(id: String, scope: String? = null, userConfig: String = "") {
        val result = registry.install(manifestJson(id, scope, userConfig))
        assertTrue("安装 $id 应成功：${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    // ═══ 1. 分级独立启停 ═════════════════════════════════════════════

    @Test
    fun `scope toggle only touches its own workstation`() {
        install("dual", scope = "all")

        registry.setEnabledForScope("dual", SkillRegistry.SCOPE_CODING, false)

        assertTrue("agent 工位不受影响", registry.isEnabledForScope("dual", SkillRegistry.SCOPE_AGENT))
        assertFalse("coding 工位已关", registry.isEnabledForScope("dual", SkillRegistry.SCOPE_CODING))
        // 旧口径：enabled = 任一工位开
        assertTrue(registry.get("dual")!!.enabled)

        registry.setEnabledForScope("dual", SkillRegistry.SCOPE_AGENT, false)
        assertFalse("双关后 enabled 派生为 false", registry.get("dual")!!.enabled)
    }

    @Test
    fun `enabling one scope from fully-disabled keeps the other disabled`() {
        install("dual")
        registry.setEnabled("dual", false) // 双禁（旧 API）

        registry.setEnabledForScope("dual", SkillRegistry.SCOPE_CODING, true)

        assertTrue("单开的工位为 true", registry.isEnabledForScope("dual", SkillRegistry.SCOPE_CODING))
        assertFalse("另一工位保持禁用", registry.isEnabledForScope("dual", SkillRegistry.SCOPE_AGENT))
    }

    @Test
    fun `legacy setEnabled toggles both scopes`() {
        install("dual")
        registry.setEnabledForScope("dual", SkillRegistry.SCOPE_CODING, false)

        registry.setEnabled("dual", true)

        assertTrue(registry.isEnabledForScope("dual", SkillRegistry.SCOPE_AGENT))
        assertTrue(registry.isEnabledForScope("dual", SkillRegistry.SCOPE_CODING))
    }

    // ═══ 2. sidecar 持久化与旧格式兼容 ═════════════════════════════

    @Test
    fun `scope disabled state survives registry reload`() {
        install("solo", scope = "all")
        registry.setEnabledForScope("solo", SkillRegistry.SCOPE_AGENT, false)

        val reloaded = SkillRegistry(skillsDir)
        assertFalse("重启后 agent 工位仍是关", reloaded.isEnabledForScope("solo", SkillRegistry.SCOPE_AGENT))
        assertTrue("coding 工位仍不受影响", reloaded.isEnabledForScope("solo", SkillRegistry.SCOPE_CODING))
    }

    @Test
    fun `legacy flat disabled sidecar loads as both-scopes-disabled`() {
        install("legacy")
        registry.setEnabled("legacy", false)
        // 旧格式即 .disabled 平面文件（无 per-scope 行）
        val disabledText = File(skillsDir, ".disabled").readText()
        assertTrue("旧格式文件含禁用 id", disabledText.contains("legacy"))

        val reloaded = SkillRegistry(skillsDir)
        assertFalse(reloaded.isEnabledForScope("legacy", SkillRegistry.SCOPE_AGENT))
        assertFalse(reloaded.isEnabledForScope("legacy", SkillRegistry.SCOPE_CODING))
    }

    // ═══ 3. 生效工位集合贯穿读者 ═════════════════════════════════

    @Test
    fun `effective scopes intersect manifest declaration with enablement`() {
        install("declared-coding", scope = "coding")
        // manifest 声明 coding，但 coding 工位关掉 → 生效集合为空
        registry.setEnabledForScope("declared-coding", SkillRegistry.SCOPE_CODING, false)
        assertTrue("生效集合为空", registry.effectiveScopesOf("declared-coding").isEmpty())

        registry.setEnabledForScope("declared-coding", SkillRegistry.SCOPE_CODING, true)
        assertEquals(
            "生效集合 = {coding}",
            setOf(SkillRegistry.SCOPE_CODING),
            registry.effectiveScopesOf("declared-coding")
        )
    }

    @Test
    fun `coding-only-enabled skill drops out of agent digests and injections`() {
        install("shared", scope = "all")

        registry.setEnabledForScope("shared", SkillRegistry.SCOPE_AGENT, false)

        assertTrue(
            "agent 目录不含该技能",
            registry.getSkillDigests(SkillRegistry.SCOPE_AGENT).none { it.id == "shared" }
        )
        assertTrue(
            "coding 目录仍含该技能",
            registry.getSkillDigests(SkillRegistry.SCOPE_CODING).any { it.id == "shared" }
        )
        assertTrue(
            "agent prompt 注入不含该技能",
            registry.getPromptInjections(SkillRegistry.SCOPE_AGENT).none { it.contains("方法论内容 shared") }
        )
        assertTrue(
            "coding prompt 注入仍含该技能",
            registry.getPromptInjections(SkillRegistry.SCOPE_CODING).any { it.contains("方法论内容 shared") }
        )
    }

    @Test
    fun `all-manifest skill enabled in single scope registers tools with that scope`() {
        install(
            "withtool", scope = "all",
            userConfig = ""
        )
        // 给它一个工具再测 getActiveToolsWithScope 的 scope 收窄
        val withTool = """
        {
          "schema": "apex-skill-v1",
          "id": "withtool2",
          "name": "withtool2",
          "version": "1.0.0",
          "description": "带工具技能",
          "scope": "all",
          "tools": [{
            "id": "demo_tool",
            "name": "Demo Tool",
            "description": "demo",
            "parameters": "{\"type\":\"object\",\"properties\":{}}",
            "implementation": {"type": "prompt"}
          }],
          "configuration": {"autoSetup": [], "userConfig": []}
        }
        """.trimIndent()
        assertTrue(registry.install(withTool).isSuccess)

        registry.setEnabledForScope("withtool2", SkillRegistry.SCOPE_AGENT, false)

        val scoped = registry.getActiveToolsWithScope().first { it.def.id == "demo_tool" }
        assertEquals(
            "只在 coding 启用的 all 技能，工具注册 scope 收窄为 coding",
            SkillRegistry.SCOPE_CODING,
            scoped.scope
        )
    }

    // ═══ 4. 配置值持久化（就绪门禁数据面）══════════════════════════

    @Test
    fun `config values persist and survive manifest upgrade`() {
        install(
            "cfg",
            userConfig = """
            {"key": "api_key", "type": "string", "description": "API Key", "required": true}
            """.trimIndent()
        )
        assertFalse("必填未填 = 未配置好", registry.isConfigured("cfg"))

        registry.setConfigValues("cfg", mapOf("api_key" to "sk-123"))
        assertTrue("填齐后 = 配置好", registry.isConfigured("cfg"))
        assertEquals(mapOf("api_key" to "sk-123"), registry.getConfigValues("cfg"))

        // manifest 升级（版本更高重装）：配置值住 .config.json sidecar，跨升级存活
        val upgraded = manifestJson("cfg").replace("\"version\": \"1.0.0\"", "\"version\": \"2.0.0\"")
        assertTrue(registry.install(upgraded).isSuccess)
        assertEquals(
            "升级后配置值仍在",
            mapOf("api_key" to "sk-123"),
            registry.getConfigValues("cfg")
        )
        assertTrue(registry.isConfigured("cfg"))
    }

    @Test
    fun `menu provider hides unconfigured skill until config saved`() {
        install(
            "gated",
            userConfig = """
            {"key": "token", "type": "string", "description": "Token", "required": true}
            """.trimIndent()
        )
        val provider = SkillMenuProvider(registry)

        assertTrue(
            "未配置好的技能不进菜单",
            provider.getActiveSkills().none { it.id == "gated" }
        )

        registry.setConfigValues("gated", mapOf("token" to "tk-1"))
        val item = provider.getActiveSkills().first { it.id == "gated" }
        assertEquals(
            "配置齐后出现且生效工位为双工位",
            setOf(SkillRegistry.SCOPE_AGENT, SkillRegistry.SCOPE_CODING),
            item.enabledScopes
        )
    }

    @Test
    fun `menu provider filters items by workstation scope`() {
        install("scoped", scope = "all")
        val provider = SkillMenuProvider(registry)

        registry.setEnabledForScope("scoped", SkillRegistry.SCOPE_AGENT, false)

        assertTrue(
            "agent 菜单不含该技能",
            provider.getActiveSkills(SkillRegistry.SCOPE_AGENT).none { it.id == "scoped" }
        )
        assertTrue(
            "coding 菜单仍含该技能",
            provider.getActiveSkills(SkillRegistry.SCOPE_CODING).any { it.id == "scoped" }
        )
    }

    @Test
    fun `uninstall clears scope and config sidecars`() {
        install(
            "bye",
            userConfig = """
            {"key": "k", "type": "string", "description": "K"}
            """.trimIndent()
        )
        registry.setConfigValues("bye", mapOf("k" to "v"))
        registry.setEnabledForScope("bye", SkillRegistry.SCOPE_AGENT, false)

        assertTrue(registry.uninstall("bye"))

        val reloaded = SkillRegistry(skillsDir)
        assertTrue("卸载后无残留条目", reloaded.getInstalled().isEmpty())
        assertTrue("配置值已清理", reloaded.getConfigValues("bye").isEmpty())
    }
}
