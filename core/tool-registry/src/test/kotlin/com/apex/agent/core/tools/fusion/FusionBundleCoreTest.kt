package com.apex.agent.core.tools.fusion

import kotlinx.coroutines.test.runTest
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
 * FusionBundle 三件套核心不变量（manifest / registry / activator）：
 *
 * 1. manifest：内置 REA 预置可解析、校验防线（非法 schema / 路径穿越 id /
 *    skillId 错位 / 非法 JSON）全部拒绝、未知字段前向兼容；
 * 2. registry：安装/幂等/卸载/bundled 保护/目录摘要；
 * 3. activator：聚合语义 —— 技能装进 SkillInstaller 端口、MCP 连进
 *    Connector 端口、人设注入 Prompt 端口，三者独立可验证；
 *    去激活恢复提示；scope 错位拒绝；未知包拒绝。
 *
 * 纯 JVM（JUnit4 + runTest + TemporaryFolder + 手写 fake，无 mock 框架）。
 */
class FusionBundleCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ manifest ═══════════════════════════════════════════════════════

    @Test
    fun `内置 REA 预置 manifest 可解析且字段齐备`() {
        val manifest = FusionBundleManifest.parse(FusionBundlePresets.REA_BUNDLE_JSON)
        assertNotNull("内置预置必须可解析（编码期错误否则）", manifest)
        manifest!!
        assertEquals("rea", manifest.id)
        assertEquals("6.5.0", manifest.version)
        assertEquals("fusion-bundle-v1", manifest.schema)
        assertTrue(manifest.bundled)
        assertEquals("agent", manifest.scope)
        assertEquals(1, manifest.skills.size)
        assertEquals("ReaWorkflow", manifest.skills[0].skillId)
        assertEquals("ReaWorkflow", manifest.skills[0].manifest.id)
        assertEquals(1, manifest.mcpServers.size)
        assertEquals("rea", manifest.mcpServers[0].serverId)
        assertTrue(manifest.toolActivationHints.isNotEmpty())
        assertTrue(manifest.agent.activationPrompt.isNotBlank())
        assertTrue(manifest.agent.routingHints.isNotBlank())
    }

    @Test
    fun `非法 schema 被拒绝`() {
        val bad = FusionBundlePresets.REA_BUNDLE_JSON
            .replace("\"fusion-bundle-v1\"", "\"fusion-bundle-v9\"")
        assertNull(FusionBundleManifest.parse(bad))
    }

    @Test
    fun `路径穿越形态的 id 被拒绝`() {
        for (evil in listOf("../evil", "a/b", "UPPER", ".hidden", "")) {
            val json = """{"schema":"fusion-bundle-v1","id":"$evil","name":"x","version":"1","description":"d"}"""
            assertNull("id '$evil' 必须被拒绝", FusionBundleManifest.parse(json))
        }
    }

    @Test
    fun `skillId 与内联 manifest id 错位被拒绝`() {
        val json = """
            {"schema":"fusion-bundle-v1","id":"ok-id","name":"x","version":"1","description":"d",
             "skills":[{"skillId":"mismatch",
                        "manifest":{"schema":"apex-skill-v1","id":"real-id","name":"n","version":"1","description":"d"}}]}
        """.trimIndent()
        assertNull(FusionBundleManifest.parse(json))
    }

    @Test
    fun `未知字段前向兼容（上游演进新增字段不炸旧端）`() {
        val json = """
            {"schema":"fusion-bundle-v1","id":"future","name":"x","version":"1","description":"d",
             "upstreamNewField":{"nested":[1,2,3]}}
        """.trimIndent()
        val manifest = FusionBundleManifest.parse(json)
        assertNotNull(manifest)
        assertEquals("future", manifest?.id)
    }

    @Test
    fun `预置清单 all 与解析一致`() {
        val all = FusionBundlePresets.all()
        // 首发 REA + 扩充三大体系（Superpowers / SuperClaude / BMAD）
        // + 第三波三体系（AnthropicSkills / ClaudeFlow / Shrimp）
        assertEquals(7, all.size)
        assertEquals("rea", all[0].first.id)
        assertEquals(FusionBundlePresets.REA_BUNDLE_JSON, all[0].second)
    }

    // ═══ registry ═══════════════════════════════════════════════════════

    private fun newRegistry(): FusionBundleRegistry =
        FusionBundleRegistry(tmp.newFolder("bundles"))

    @Test
    fun `registry 安装与幂等`() {
        val registry = newRegistry()
        val first = registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        assertTrue("首次安装必须成功: ${first.exceptionOrNull()?.message}", first.isSuccess)
        assertEquals(1, registry.list().size)

        val second = registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        assertTrue(second.isSuccess)
        assertEquals("同版本重装幂等（不新增）", 1, registry.list().size)

        val digest = registry.digests().single()
        assertEquals("rea", digest.id)
        assertEquals(1, digest.skillCount)
        assertEquals(1, digest.mcpServerCount)
        assertTrue(digest.bundled)
    }

    @Test
    fun `registry 非法 manifest 安装被拒`() {
        val registry = newRegistry()
        val r = registry.install("""{"schema":"wrong","id":"x","name":"n","version":"1","description":"d"}""")
        assertTrue(r.isFailure)
        assertEquals(0, registry.list().size)
    }

    @Test
    fun `registry bundled 卸载保护与普通包卸载`() {
        val registry = newRegistry()
        registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        assertFalse("bundled 包不可卸载（与 SkillRegistry 同防线）", registry.uninstall("rea"))
        assertEquals(1, registry.list().size)

        val normal = """
            {"schema":"fusion-bundle-v1","id":"user-pack","name":"u","version":"1.0.0","description":"用户自装"}
        """.trimIndent()
        registry.install(normal)
        assertTrue(registry.uninstall("user-pack"))
        assertEquals("卸载后只剩内置", 1, registry.list().size)
        assertNull(registry.get("user-pack"))
    }

    @Test
    fun `registry installBundled 幂等且补 bundled 标记`() {
        val registry = newRegistry()
        val (manifest, json) = FusionBundlePresets.all().first()
        registry.installBundled(manifest, json)
        registry.installBundled(manifest, json)
        assertEquals(1, registry.list().size)
        assertTrue(registry.get("rea")!!.bundled)
        // 落盘文件存在（持久化语义）
        val file = File(tmp.root, "bundles").resolve("rea.json")
        assertTrue(file.exists())
    }

    @Test
    fun `registry 磁盘热对账吸收外部文件`() {
        val dir = tmp.newFolder("hot")
        val json = """{"schema":"fusion-bundle-v1","id":"hot-pack","name":"h","version":"1","description":"外部放入"}"""
        File(dir, "hot-pack.json").writeText(json)
        val registry = FusionBundleRegistry(dir)
        assertEquals("启动时吸收磁盘上的既有包", 1, registry.list().size)
        assertEquals("hot-pack", registry.list().first().id)
    }

    // ═══ activator（聚合语义）══════════════════════════════════════════

    /** 手写 fake：技能安装端口（记录调用 + 可注入失败）。 */
    private class FakeSkillInstaller(
        private val failIds: Set<String> = emptySet()
    ) : FusionSkillInstaller {
        val installed = mutableListOf<String>()
        val onDisk = mutableSetOf<String>()
        override fun isInstalled(skillId: String): Boolean = skillId in onDisk

        override suspend fun ensureInstalled(manifestJson: String): Result<Unit> {
            val id = Regex("\"id\"\\s*:\\s*\"([a-zA-Z0-9_-]+)\"")
                .find(manifestJson)?.groupValues?.get(1)
                ?: return Result.failure(Exception("manifest 无 id"))
            if (id in failIds) return Result.failure(Exception("注入失败（$id）"))
            installed.add(id)
            onDisk.add(id)
            return Result.success(Unit)
        }
    }

    /** 手写 fake：MCP 连接端口。 */
    private class FakeMcpConnector(
        private val failServers: Set<String> = emptySet()
    ) : FusionMcpConnector {
        val connected = mutableListOf<String>()
        override suspend fun ensureConnected(config: com.apex.agent.core.tools.mcp.McpServerConfig): Result<Unit> {
            if (config.name in failServers) return Result.failure(Exception("连接失败（${config.name}）"))
            connected.add(config.name)
            return Result.success(Unit)
        }
    }

    /** 手写 fake：引擎提示注入端口。 */
    private class FakePromptInjector : FusionPromptInjector {
        var applied: String? = null
            private set
        var restoreCount = 0
            private set
        override fun apply(segment: String) {
            applied = segment
        }

        override fun restore() {
            restoreCount++
            applied = null
        }
    }

    @Test
    fun `激活聚合三端口并注入 REA 人设`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("a1"))
        registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        val skills = FakeSkillInstaller()
        val mcp = FakeMcpConnector()
        val prompt = FakePromptInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val r = activator.activate("rea")
        assertTrue("激活必须成功: ${r.exceptionOrNull()?.message}", r.isSuccess)
        val act = r.getOrThrow()

        assertEquals(listOf("ReaWorkflow"), skills.installed)
        assertEquals(listOf("rea"), mcp.connected)
        assertNotNull(prompt.applied)
        assertTrue("注入人设须含 REA 工作流", prompt.applied!!.contains("REA 逆向工程体系"))
        assertTrue("注入人设须含旗舰工具清单", prompt.applied!!.contains("McpReaOpenBinary"))
        assertTrue("注入人设须含证据纪律段", prompt.applied!!.contains("证据纪律"))
        assertEquals("rea", act.bundleId)
        assertEquals(listOf("ReaWorkflow"), act.installedSkills)
        assertEquals(listOf("rea"), act.connectedServers)
        assertTrue(act.skipped.isEmpty())
        assertEquals("rea", activator.activeBundle?.id)
    }

    @Test
    fun `重复激活走幂等分支（技能已装不再装）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("a2"))
        registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        val skills = FakeSkillInstaller()
        val mcp = FakeMcpConnector()
        val prompt = FakePromptInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        activator.activate("rea")
        val act2 = activator.activate("rea").getOrThrow()
        assertEquals("技能装一次（幂等分支）", 1, skills.installed.size)
        assertEquals("MCP 可重复连接（ensureConnected 幂等语义）", 2, mcp.connected.size)
        assertTrue(act2.installedSkills.contains("ReaWorkflow"))
    }

    @Test
    fun `组件失败进 skipped 且整体仍激活（聚合容错）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("a3"))
        registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        val skills = FakeSkillInstaller(failIds = setOf("ReaWorkflow"))
        val mcp = FakeMcpConnector(failServers = setOf("rea"))
        val prompt = FakePromptInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val act = activator.activate("rea").getOrThrow()
        assertEquals(2, act.skipped.size)
        assertTrue(act.skipped.any { it.startsWith("skill:") })
        assertTrue(act.skipped.any { it.startsWith("mcp:") })
        assertNotNull("部分失败不阻断人设注入", prompt.applied)
    }

    @Test
    fun `去激活恢复提示且技能 MCP 留在体系内`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("a4"))
        registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        val skills = FakeSkillInstaller()
        val mcp = FakeMcpConnector()
        val prompt = FakePromptInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        activator.activate("rea")
        val r = activator.deactivate()
        assertTrue(r.isSuccess)
        assertNull(prompt.applied)
        assertEquals(1, prompt.restoreCount)
        assertEquals("技能留在体系（聚合不融合的去激活语义）", 1, skills.onDisk.size)
        assertEquals("MCP 留在体系", 1, mcp.connected.size)
        assertEquals(null, activator.activeBundle)
    }

    @Test
    fun `未知包与 scope 错位被拒绝`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("a5"))
        registry.install(FusionBundlePresets.REA_BUNDLE_JSON)
        val activator = FusionBundleActivator(
            registry, FakeSkillInstaller(), FakeMcpConnector(), FakePromptInjector()
        )
        assertTrue(activator.activate("nope").isFailure)

        // coding 工位激活 agent 域包 → 拒绝
        val r = activator.activate("rea", scope = "coding")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("作用域"))
    }
}
