package com.apex.agent.core.tools.fusion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # FusionBundle 第三波预置测试 —— AnthropicSkills / ClaudeFlow / Shrimp
 *
 * 第三波三体系的内置预置逐包契约验证：
 * - manifest 五防线（schema/id 形态/skillId 一致性/服务器名非空）全过；
 * - 技能 id 全部 v5 PascalCase（无 `_`/`-`）；
 * - toolActivationHints 全部可解析形态（一等 id 无分隔符、Mcp 组合规则）；
 * - 聚合语义：激活三端口（技能/MCP/人设注入）；
 * - 七包目录完整（含前四波回归）与 bundled 保护。
 */
class FusionBundleWaveThreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ 逐包 manifest 契约 ═══════════════════════════════════════════

    @Test
    fun `第三波三包预置全部解析成功且 id 唯一`() {
        val ids = FusionBundlePresets.all().map { it.first.id }
        assertEquals(7, ids.size)
        assertEquals(ids.size, ids.toSet().size) // 全目录无重复
        assertTrue(ids.containsAll(listOf("anthropicskills", "claudeflow", "shrimp")))
    }

    @Test
    fun `AnthropicSkills 预置契约（8 技能 + 零 MCP + BYO Python 工具链）`() {
        val m = FusionAnthropicSkillsPreset.manifest()
        assertEquals("anthropicskills", m.id)
        assertEquals(8, m.skills.size)
        // v5 命名：技能 id 无 `_` 与 `-`
        m.skills.forEach { ref ->
            assertTrue(
                "技能 id '${ref.skillId}' 须为 PascalCase（v5）",
                ref.skillId.matches(Regex("^[A-Z][A-Za-z0-9]+$"))
            )
        }
        assertEquals(
            setOf(
                "DocumentDocx", "DocumentXlsx", "DocumentPptx", "DocumentPdf",
                "DocumentSkillCreator", "DocumentMcpBuilder",
                "DocumentWebappTesting", "DocumentFrontendDesign"
            ),
            m.skills.map { it.skillId }.toSet()
        )
        // 上游如实：无 MCP 服务器（方法论 + 原生工具形态）
        assertTrue(m.mcpServers.isEmpty())
        // 文档工具链 BYO 引擎面（python3 + 四库 + reportlab）
        assertTrue(m.runtime.engines.map { it.name }.containsAll(
            listOf("python3", "python-docx", "openpyxl", "python-pptx", "pypdf")
        ))
        // 许可如实：文档四件标注原创重述（上游 source-available）
        assertTrue(m.license.contains("original"))
        assertTrue(m.agent.activationPrompt.contains("PRoot"))
    }

    @Test
    fun `ClaudeFlow 预置契约（SPARC 七技能 + thinking 与 memory 两台 MCP 聚合）`() {
        val m = FusionClaudeFlowPreset.manifest()
        assertEquals("claudeflow", m.id)
        assertEquals("3.56.10", m.version)
        assertEquals(7, m.skills.size)
        assertEquals(
            listOf(
                "SparcSpecify", "SparcArchitect", "SparcTdd",
                "SparcReview", "SparcComplete", "SparcSwarm", "SparcMemory"
            ),
            m.skills.map { it.skillId }
        )
        // MCP 聚合：上游增强服务的内置等价映射
        assertEquals(listOf("thinking", "memory"), m.mcpServers.map { it.serverId })
        m.mcpServers.forEach {
            assertEquals("BUILTIN", it.transport)
            assertTrue(it.autoConnect)
        }
        // 人设：五阶段门 + 并行优先 + 顺序思考
        assertTrue(m.agent.activationPrompt.contains("Specify"))
        assertTrue(m.agent.activationPrompt.contains("并行"))
        assertTrue(m.agent.routingHints.contains("McpThinkingSequentialthinking"))
    }

    @Test
    fun `Shrimp 预置契约（2 技能 + shrimp MCP 16 工具进程内实现）`() {
        val m = FusionShrimpPreset.manifest()
        assertEquals("shrimp", m.id)
        assertEquals("1.0.21", m.version)
        assertEquals(2, m.skills.size)
        assertEquals(
            listOf("ShrimpWorkflow", "ShrimpResearch"),
            m.skills.map { it.skillId }
        )
        // 自带 MCP：16 工具进程内服务器（第七台）
        assertEquals(listOf("shrimp"), m.mcpServers.map { it.serverId })
        assertTrue(m.mcpServers.first().autoConnect)
        // 旗舰工具提示遵循 v5 组合规则 Mcp{Server}{Tool}
        assertTrue(m.toolActivationHints.contains("McpShrimpPlanTask"))
        assertTrue(m.toolActivationHints.contains("McpShrimpExecuteTask"))
        // 零外部引擎（纯进程内实现）
        assertTrue(m.runtime.engines.isEmpty())
    }

    @Test
    fun `第三波三包 toolActivationHints 全部 v5 可解析形态`() {
        val forbidden = Regex("[_\\-]")
        for ((m, _) in FusionBundlePresets.all().drop(4)) {
            assertTrue("包 '${m.id}' 至少一条工具提示", m.toolActivationHints.isNotEmpty())
            m.toolActivationHints.forEach { hint ->
                assertTrue(
                    "包 '${m.id}' 提示 '$hint' 含分隔符（v5 禁）",
                    !forbidden.containsMatchIn(hint)
                )
            }
        }
    }

    // ═══ 聚合语义（激活三端口）════════════════════════════════════════

    @Test
    fun `AnthropicSkills 激活八技能零 MCP 且人设注入含工具链指引`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("as"))
        registry.install(FusionAnthropicSkillsPreset.ANTHROPIC_SKILLS_BUNDLE_JSON)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val act = activator.activate("anthropicskills").getOrThrow()
        assertEquals(8, act.installedSkills.size)
        assertEquals(0, act.connectedServers.size) // 如实：上游无 MCP
        assertNotNull(prompt.applied)
        assertTrue(prompt.applied!!.contains("python-docx"))
        assertTrue(prompt.applied!!.contains("BYO"))
        assertTrue(act.skipped.isEmpty())
    }

    @Test
    fun `ClaudeFlow 激活聚合两台 MCP 且群智编排提示就位`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("cf"))
        registry.install(FusionClaudeFlowPreset.CLAUDEFLOW_BUNDLE_JSON)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val act = activator.activate("claudeflow").getOrThrow()
        assertEquals(7, act.installedSkills.size)
        assertEquals(listOf("thinking", "memory"), act.connectedServers)
        assertTrue(prompt.applied!!.contains("SPARC"))
        assertTrue(prompt.applied!!.contains("McpMemoryCreateEntities"))
    }

    @Test
    fun `Shrimp 激活连接进程内 MCP 且任务账本人设注入`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("sh"))
        registry.install(FusionShrimpPreset.SHRIMP_BUNDLE_JSON)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        val act = activator.activate("shrimp").getOrThrow()
        assertEquals(2, act.installedSkills.size)
        assertEquals(listOf("shrimp"), act.connectedServers)
        assertTrue(prompt.applied!!.contains("McpShrimpPlanTask"))
        assertTrue(prompt.applied!!.contains("前置门禁"))
        assertEquals("shrimp", activator.activeBundle?.id)
    }

    @Test
    fun `七包可先后激活（第三波与前四波共存，聚合不融合）`() = runTest {
        val registry = FusionBundleRegistry(tmp.newFolder("multi7"))
        for ((_, json) in FusionBundlePresets.all()) registry.install(json)
        val skills = FakeInstaller()
        val mcp = FakeConnector()
        val prompt = FakeInjector()
        val activator = FusionBundleActivator(registry, skills, mcp, prompt)

        // 前四波 → 第三波逐一切换
        activator.activate("rea")
        activator.activate("bmad")
        activator.activate("anthropicskills")
        activator.activate("claudeflow")
        val last = activator.activate("shrimp").getOrThrow()
        assertEquals("shrimp", last.bundleId)
        assertEquals("shrimp", activator.activeBundle?.id)
        // 实体只增不减：本测试激活的 5 包技能（1 + 5 + 8 + 7 + 2 = 23）
        assertEquals(23, skills.onDisk.size)
        // MCP 聚合账本：rea + thinking + search + memory + shrimp 全连过
        assertTrue(mcp.connected.containsAll(listOf("rea", "thinking", "search", "memory", "shrimp")))
    }

    @Test
    fun `第三波 installBundled 释放且幂等`() {
        val registry = FusionBundleRegistry(tmp.newFolder("bundled3"))
        val wave3 = FusionBundlePresets.all().drop(4)
        for ((m, _) in wave3) {
            assertTrue("释放 '${m.id}'", registry.installBundled(m, "{}").isSuccess)
        }
        assertEquals(3, registry.list().size)
        assertEquals(3, registry.list().count { it.bundled })
        for ((m, _) in wave3) registry.installBundled(m, "{}") // 幂等
        assertEquals(3, registry.list().size)
    }

    // ═══ 测试 fake（与 FusionBundleExpansionTest 同构）════════════════

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
