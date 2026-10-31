package com.apex.agent.core.tools.fusion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # FusionBundle 扩充预置测试 —— Superpowers / SuperClaude / BMAD
 *
 * 前四波契约验证（第三波三体系见 FusionBundleWaveThreeTest）：
 * - manifest 五防线（schema/id 形态/skillId 一致性/服务器名非空）全过；
 * - 技能 id 全部 v5 PascalCase（无 `_`/`-`）；
 * - toolActivationHints 全部可解析形态（一等 id 无分隔符、Mcp 组合规则）；
 * - 聚合语义：激活三端口 + MCP 服务器 autoConnect + 人设注入含路由；
 * - 注册表装载（installBundled 幂等 + bundled 保护）。
 */
class FusionBundleExpansionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ 逐包 manifest 契约 ═══════════════════════════════════════════

    @Test
    fun `前四波预置全部解析成功且 id 唯一`() {
        val all = FusionBundlePresets.all()
        assertEquals(8, all.size)
        assertEquals(
            listOf("rea", "superpowers", "superclaude", "bmad"),
            all.map { it.first.id }.take(4)
        )
    }

    @Test
    fun `Superpowers 预置契约（8 技能 v5 命名 + 零 MCP + 上游溯源）`() {
        val m = FusionSuperpowersPreset.manifest()
        assertEquals("superpowers", m.id)
        assertEquals("7.0.0", m.version)
        assertEquals(8, m.skills.size)
        // v5 命名：技能 id 无 `_` 与 `-`（SkillNaming canonical 形态）
        m.skills.forEach { ref ->
            assertTrue(
                "技能 id '${ref.skillId}' 须为 PascalCase（v5）",
                ref.skillId.matches(Regex("^[A-Z][A-Za-z0-9]+$"))
            )
        }
        // 上游如实：Superpowers 是纯方法论体系（零 MCP 服务器）
        assertTrue(m.mcpServers.isEmpty())
        // 方法论主干八件齐备
        val expected = setOf(
            "SuperpowersBrainstorming", "SuperpowersWritingPlans",
            "SuperpowersExecutingPlans", "SuperpowersTdd",
            "SuperpowersSystematicDebugging", "SuperpowersVerification",
            "SuperpowersCodeReview", "SuperpowersFinishing"
        )
        assertEquals(expected, m.skills.map { it.skillId }.toSet())
        // 人设三件套非空（bootstrap / 路由 / 主线纪律）
        assertTrue(m.agent.persona.isNotBlank())
        assertTrue(m.agent.routingHints.isNotBlank())
        assertTrue(m.agent.activationPrompt.contains("TDD"))
    }

    @Test
    fun `SuperClaude 预置契约（5 模式技能 + 四台内置 MCP 聚合）`() {
        val m = FusionSuperClaudePreset.manifest()
        assertEquals("superclaude", m.id)
        assertEquals("4.3.0", m.version)
        assertEquals(5, m.skills.size)
        assertEquals(
            setOf(
                "SuperClaudeBrainstormMode", "SuperClaudeDeepResearch",
                "SuperClaudeTokenEfficiency", "SuperClaudeOrchestration",
                "SuperClaudeBusinessPanel"
            ),
            m.skills.map { it.skillId }.toSet()
        )
        // MCP 聚合：上游 8 台增强 MCP 的设备端等价映射（4 台内置）
        assertEquals(listOf("search", "thinking", "memory", "github"), m.mcpServers.map { it.serverId })
        m.mcpServers.forEach {
            assertEquals("BUILTIN", it.transport)
            assertTrue("聚合服务器默认自动连接", it.autoConnect)
        }
        // 激活提示提及 MCP 增强就绪
        assertTrue(m.agent.activationPrompt.contains("WebSearch"))
    }

    @Test
    fun `BMAD 预置契约（交付环五阶段 + 增益层两台 MCP）`() {
        val m = FusionBmadPreset.manifest()
        assertEquals("bmad", m.id)
        assertEquals("6.12.0", m.version)
        assertEquals(5, m.skills.size)
        assertEquals(
            listOf("BmadClarify", "BmadPlan", "BmadShard", "BmadBuild", "BmadVerify"),
            m.skills.map { it.skillId }
        )
        // 增益层（超出上游）：思考 + 检索两台内置 MCP
        assertEquals(listOf("thinking", "search"), m.mcpServers.map { it.serverId })
        // 交付环人格：环阶段路由 + 规模自适应铁律
        assertTrue(m.agent.activationPrompt.contains("Clarify"))
        assertTrue(m.agent.activationPrompt.contains("规模自适应"))
    }

    @Test
    fun `前四波 toolActivationHints 全部 v5 可解析形态`() {
        val forbidden = Regex("[_\\-]")
        for ((m, _) in FusionBundlePresets.all().drop(1)) {
            m.toolActivationHints.forEach { hint ->
                assertTrue(
                    "包 '${m.id}' 提示 '$hint' 含分隔符（v5 禁）",
                    !forbidden.containsMatchIn(hint)
                )
            }
            assertTrue("包 '${m.id}' 至少一条工具提示", m.toolActivationHints.isNotEmpty())
        }
    }

    // ═══ 聚合语义（激活三端口）════════════════════════════════════════

    @Test
    fun `SuperClaude 激活聚合四台 MCP 并注入模式人设`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("sc"))
        registry.install(FusionSuperClaudePreset.SUPERCLAUDE_BUNDLE_JSON)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val act = activator.activate("superclaude").getOrThrow()
        assertEquals(5, act.installedSkills.size)
        assertEquals(listOf("search", "thinking", "memory", "github"), act.connectedServers)
        assertNotNull(prompt.applied)
        assertTrue(prompt.applied!!.contains("SuperClaude 结构化平台"))
        assertTrue(prompt.applied!!.contains("McpSearchWebSearch"))
        assertTrue(act.skipped.isEmpty())
    }

    @Test
    fun `BMAD 激活聚合增益 MCP 且技能进 SkillRegistry 形态`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("bm"))
        registry.install(FusionBmadPreset.BMAD_BUNDLE_JSON)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val activator = FusionBundleActivator(
            registry, skills, mcp, FakeInjector()
        )
        val act = activator.activate("bmad").getOrThrow()
        assertEquals(5, act.installedSkills.size)
        assertEquals(listOf("thinking", "search"), act.connectedServers)
    }

    @Test
    fun `Superpowers 激活零 MCP 但技能与人设就位（方法论聚合形态）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("sp"))
        registry.install(FusionSuperpowersPreset.SUPERPOWERS_BUNDLE_JSON)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val act = activator.activate("superpowers").getOrThrow()
        assertEquals(8, act.installedSkills.size)
        assertEquals(0, act.connectedServers.size) // 如实：上游无 MCP
        assertTrue(prompt.applied!!.contains("Superpowers 方法论"))
        assertEquals("superpowers", activator.activeBundle?.id)
    }

    @Test
    fun `四包可先后激活（切换语义：人设注入可覆盖）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("multi"))
        for ((_, json) in FusionBundlePresets.all()) registry.install(json)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        activator.activate("rea")
        activator.activate("superpowers")
        assertEquals("superpowers", activator.activeBundle?.id)
        assertTrue(prompt.applied!!.contains("Superpowers"))
        // 既有体系的实体只增不减（聚合不融合）：REA 的技能/MCP 留在体系内
        assertEquals(9, skills.onDisk.size) // ReaWorkflow + 8 Superpowers 技能
        assertTrue(mcp.connected.contains("rea"))
    }

    @Test
    fun `FusionActivate 工具激活回灌观察口（模型侧与 UI 同一激活面）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("sink"))
        registry.install(FusionSuperpowersPreset.SUPERPOWERS_BUNDLE_JSON)
        val activator = FusionBundleActivator(
            registry, FakeInstaller(), FakeConnector(), FakeInjector()
        )
        var activated: FusionActivation? = null
        var deactivated: String? = null
        val tool = FusionActivateTool(
            registry, activator,
            activationSink = object : FusionActivationSink {
                override fun onActivated(a: FusionActivation) { activated = a }
                override fun onDeactivated(bundleId: String) { deactivated = bundleId }
            }
        )

        val r = tool.executeStructured("""{"id":"superpowers"}""")
        assertTrue("工具激活须成功: ${r.render().take(120)}", r.isSuccess)
        assertEquals("superpowers", activated?.bundleId)
        assertEquals(8, activated?.installedSkills?.size)

        val r2 = tool.executeStructured("""{"id":"superpowers","deactivate":true}""")
        assertTrue(r2.isSuccess)
        assertEquals("superpowers", deactivated)
    }

    @Test
    fun `无观察口的 FusionActivate 兼容（默认 null 零负担）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("nosink"))
        registry.install(FusionBmadPreset.BMAD_BUNDLE_JSON)
        val activator = FusionBundleActivator(
            registry, FakeInstaller(), FakeConnector(), FakeInjector()
        )
        val tool = FusionActivateTool(registry, activator) // activationSink 默认 null
        val r = tool.executeStructured("""{"id":"bmad"}""")
        assertTrue(r.isSuccess)
        assertTrue(r.render().contains("BMAD"))
    }

    // ═══ 注册表装载语义 ═══════════════════════════════════════════════

    @Test
    fun `七包 installBundled 释放且 bundled 保护`() {
        val registry = FusionBundleRegistry(tmp.newFolder("bundled"))
        for ((m, _) in FusionBundlePresets.all()) {
            assertTrue("释放 '${m.id}'", registry.installBundled(m, "{}").isSuccess)
        }
        assertEquals(8, registry.list().size)
        assertEquals(8, registry.list().count { it.bundled })
        // 幂等：二次释放不重复
        for ((m, _) in FusionBundlePresets.all()) {
            registry.installBundled(m, "{}")
        }
        assertEquals(8, registry.list().size)
    }

    // ═══ 测试 fake（与 FusionBundleCoreTest 同构）══════════════════════

    private class FakeInstaller : FusionSkillInstaller {
        val installed = mutableListOf<String>()
        val onDisk = mutableSetOf<String>()
        override fun isInstalled(skillId: String): Boolean = skillId in onDisk
        override suspend fun ensureInstalled(manifestJson: String): Result<Unit> {
            val id = Regex("\"id\"\\s*:\\s*\"([a-zA-Z0-9_-]+)\"")
                .find(manifestJson)?.groupValues?.get(1)
                ?: return Result.failure(Exception("manifest 无 id"))
            installed.add(id)
            onDisk.add(id)
            return Result.success(Unit)
        }
    }

    private class FakeConnector : FusionMcpConnector {
        val connected = mutableListOf<String>()
        override suspend fun ensureConnected(config: com.apex.agent.core.tools.mcp.McpServerConfig): Result<Unit> {
            connected.add(config.name)
            return Result.success(Unit)
        }
    }

    private class FakeInjector : FusionPromptInjector {
        var applied: String? = null
            private set
        override fun apply(segment: String) { applied = segment }
        override fun restore() { applied = null }
    }
}
