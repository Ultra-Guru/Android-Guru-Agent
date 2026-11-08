package com.apex.agent.core.tools.pluginhost

import com.apex.agent.core.tools.extension.ExtensionManifest
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.SourceKinds
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 契约锁：统一插件核心模型 + 兼容性分析引擎。
 *
 * 断言值与 [PluginEcosystemMatrix] / [PluginCompatibilityAnalyzer] 的审计
 * 基线一一对应 —— 矩阵状态随后续 Phase PR 变化时，本测试同步演进
 * （这是刻意的：评级必须始终反映真实实现状态）。
 */
class PluginHostModelTest {

    // ── 模型：生态映射 ────────────────────────────────────────────────────

    @Test
    fun `extension type maps to ecosystem`() {
        assertEquals(PluginEcosystem.CLAUDE, PluginEcosystem.fromExtensionType(ExtensionTypes.CLAUDE_PLUGIN))
        assertEquals(PluginEcosystem.OPENCODE_V2, PluginEcosystem.fromExtensionType(ExtensionTypes.OPENCODE_PLUGIN))
        assertEquals(PluginEcosystem.DSH, PluginEcosystem.fromExtensionType(ExtensionTypes.DSH_PLUGIN))
        assertEquals(PluginEcosystem.MCP, PluginEcosystem.fromExtensionType(ExtensionTypes.MCP_CONNECTOR))
        assertEquals(PluginEcosystem.APK, PluginEcosystem.fromExtensionType(ExtensionTypes.APK_PACKAGE))
    }

    @Test
    fun `skill and functional types have no plugin host runtime semantics`() {
        assertNull(PluginEcosystem.fromExtensionType(ExtensionTypes.SKILL))
        assertNull(PluginEcosystem.fromExtensionType(ExtensionTypes.FUNCTIONAL_PLUGIN))
        assertNull(PluginEcosystem.fromExtensionType("nonsense"))
    }

    @Test
    fun `fromExtensionManifest carries provenance into descriptor`() {
        val manifest = ExtensionManifest(
            id = "opencode.scope-tool",
            type = ExtensionTypes.OPENCODE_PLUGIN,
            spec = "npm:@scope/tool",
            name = "Scope Tool",
            version = "2.1.0",
            sourceKind = SourceKinds.NPM
        )
        val descriptor = PluginDescriptor.fromExtensionManifest(manifest)

        assertTrue(descriptor != null)
        descriptor!!
        assertEquals("opencode.scope-tool", descriptor.id)
        assertEquals("Scope Tool", descriptor.name)
        assertEquals("2.1.0", descriptor.version)
        assertEquals(PluginEcosystem.OPENCODE_V2, descriptor.ecosystem)
        assertEquals(SourceKinds.NPM, descriptor.source.kind)
        assertEquals("npm:@scope/tool", descriptor.source.location)
        // OpenCode 生态典型运行时候选：node + bun（任一可用即具备执行前提）
        assertEquals(
            setOf(PluginRuntimeKind.NODE, PluginRuntimeKind.BUN),
            descriptor.runtimeRequirements
        )
        // OpenCode 典型能力面来自矩阵（Phase 1 基线：全 PLANNED/UNSUPPORTED）
        assertTrue(descriptor.declaredCapabilities.contains(PluginCapability.TOOLS))
        assertTrue(descriptor.declaredCapabilities.contains(PluginCapability.STORAGE))
    }

    @Test
    fun `skill manifest maps to null descriptor`() {
        val manifest = ExtensionManifest(
            id = "skill.PdfHelper",
            type = ExtensionTypes.SKILL,
            spec = "skill:https://example.com/s.zip",
            name = "Pdf Helper",
            sourceKind = SourceKinds.URL
        )
        assertNull(PluginDescriptor.fromExtensionManifest(manifest))
    }

    // ── 模型：生命周期状态机 ──────────────────────────────────────────────

    @Test
    fun `happy path lifecycle transitions are legal`() {
        val path = listOf(
            PluginLifecycleState.DISCOVERED, PluginLifecycleState.RESOLVED,
            PluginLifecycleState.INSPECTED, PluginLifecycleState.INSTALLED,
            PluginLifecycleState.PREPARED, PluginLifecycleState.LOADING,
            PluginLifecycleState.ACTIVE, PluginLifecycleState.STOPPING,
            PluginLifecycleState.STOPPED
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue("$from → $to 应合法", from.canTransitionTo(to))
        }
    }

    @Test
    fun `crash and recovery transitions are legal`() {
        assertTrue(PluginLifecycleState.ACTIVE.canTransitionTo(PluginLifecycleState.CRASHED))
        assertTrue(PluginLifecycleState.CRASHED.canTransitionTo(PluginLifecycleState.LOADING))
        assertTrue(PluginLifecycleState.FAILED.canTransitionTo(PluginLifecycleState.PREPARED))
        assertTrue(PluginLifecycleState.DISABLED.canTransitionTo(PluginLifecycleState.PREPARED))
    }

    @Test
    fun `illegal jumps are rejected`() {
        assertFalse(PluginLifecycleState.DISCOVERED.canTransitionTo(PluginLifecycleState.ACTIVE))
        assertFalse(PluginLifecycleState.STOPPED.canTransitionTo(PluginLifecycleState.ACTIVE))
        assertFalse(PluginLifecycleState.INCOMPATIBLE.canTransitionTo(PluginLifecycleState.LOADING))
    }

    @Test
    fun `terminal states`() {
        assertTrue(PluginLifecycleState.STOPPED.isTerminal)
        assertTrue(PluginLifecycleState.FAILED.isTerminal)
        assertTrue(PluginLifecycleState.INCOMPATIBLE.isTerminal)
        assertTrue(PluginLifecycleState.DISABLED.isTerminal)
        assertFalse(PluginLifecycleState.CRASHED.isTerminal)
        assertFalse(PluginLifecycleState.ACTIVE.isTerminal)
    }

    // ── 矩阵：审计基线锁 ──────────────────────────────────────────────────

    @Test
    fun `claude matrix reflects audit baseline`() {
        assertEquals(
            CapabilitySupport.SUPPORTED,
            PluginEcosystemMatrix.statusOf(PluginEcosystem.CLAUDE, PluginCapability.SKILLS)?.support
        )
        assertEquals(
            CapabilitySupport.PLANNED,
            PluginEcosystemMatrix.statusOf(PluginEcosystem.CLAUDE, PluginCapability.HOOKS)?.support
        )
        assertEquals(
            CapabilitySupport.PLANNED,
            PluginEcosystemMatrix.statusOf(PluginEcosystem.CLAUDE, PluginCapability.MCP_SERVERS)?.support
        )
        assertEquals(
            CapabilitySupport.UNSUPPORTED,
            PluginEcosystemMatrix.statusOf(PluginEcosystem.CLAUDE, PluginCapability.GUI)?.support
        )
    }

    @Test
    fun `opencode matrix reflects audit baseline`() {
        // Phase 1 基线：安装链路已通、能力桥未实现 —— TOOLS 必须是 PLANNED
        assertEquals(
            CapabilitySupport.PLANNED,
            PluginEcosystemMatrix.statusOf(PluginEcosystem.OPENCODE_V2, PluginCapability.TOOLS)?.support
        )
        assertEquals(
            CapabilitySupport.UNSUPPORTED,
            PluginEcosystemMatrix.statusOf(PluginEcosystem.OPENCODE_V2, PluginCapability.TUI)?.support
        )
        // V1 与 V2 共享槽位矩阵，但 ABI 探测在加载器侧分别进行
        assertEquals(
            PluginEcosystemMatrix.statusOf(PluginEcosystem.OPENCODE_V2, PluginCapability.TOOLS),
            PluginEcosystemMatrix.statusOf(PluginEcosystem.OPENCODE_V1, PluginCapability.TOOLS)
        )
    }

    @Test
    fun `slot absent from ecosystem format yields null`() {
        assertNull(PluginEcosystemMatrix.statusOf(PluginEcosystem.MCP, PluginCapability.SKILLS))
        assertNull(PluginEcosystemMatrix.statusOf(PluginEcosystem.APK, PluginCapability.HOOKS))
    }

    // ── 分析器：评级 ──────────────────────────────────────────────────────

    @Test
    fun `opencode plugin today rates D - installable but not runnable`() {
        val plugin = PluginDescriptor(
            id = "opencode.demo",
            name = "Demo",
            ecosystem = PluginEcosystem.OPENCODE_V2,
            runtimeRequirements = setOf(PluginRuntimeKind.NODE)
        )
        val env = PluginEnvironmentSnapshot(nodeVersion = "v22.11.0", npmVersion = "10.9.0")
        val report = PluginCompatibilityAnalyzer.analyze(plugin, env)

        assertEquals(PluginCompatibilityLevel.D, report.level)
        // 诚实边界：即使 node 齐备，能力面全 PLANNED 仍评 D
        assertTrue(report.reasons.any { it.contains("PLANNED") || it.contains("无法真正运行") })
        assertTrue(report.runtimeVerdicts.single().available)
    }

    @Test
    fun `missing runtimes rate D with explicit reason`() {
        val plugin = PluginDescriptor(
            id = "opencode.demo",
            name = "Demo",
            ecosystem = PluginEcosystem.OPENCODE_V2,
            runtimeRequirements = setOf(PluginRuntimeKind.NODE)
        )
        val report = PluginCompatibilityAnalyzer.analyze(plugin, PluginEnvironmentSnapshot())

        assertEquals(PluginCompatibilityLevel.D, report.level)
        assertTrue(report.reasons.any { it.contains("所需运行时全部缺失") })
        assertFalse(report.runtimeVerdicts.single().available)
    }

    @Test
    fun `claude plugin with skills rates B today`() {
        // skills/commands SUPPORTED + agents PARTIAL + hooks/mcp/lsp PLANNED → B
        val plugin = PluginDescriptor(
            id = "claude.pdf-helper",
            name = "PDF Helper",
            ecosystem = PluginEcosystem.CLAUDE
        )
        val report = PluginCompatibilityAnalyzer.analyze(plugin, PluginEnvironmentSnapshot())

        assertEquals(PluginCompatibilityLevel.B, report.level)
        assertTrue(report.summary().contains("B"))
        assertTrue(report.summary().contains("claude.pdf-helper"))
    }

    @Test
    fun `tui-only plugin rates F`() {
        val plugin = PluginDescriptor(
            id = "opencode.tui-dashboard",
            name = "TUI Dashboard",
            ecosystem = PluginEcosystem.OPENCODE_V2,
            declaredCapabilities = setOf(PluginCapability.TUI, PluginCapability.GUI),
            runtimeRequirements = setOf(PluginRuntimeKind.NODE)
        )
        val env = PluginEnvironmentSnapshot(nodeVersion = "v22.11.0")
        val report = PluginCompatibilityAnalyzer.analyze(plugin, env)

        assertEquals(PluginCompatibilityLevel.F, report.level)
        assertTrue(report.reasons.any { it.contains("平台不支持") })
    }

    @Test
    fun `mcp connector rates B via existing first-class support`() {
        val plugin = PluginDescriptor(
            id = "mcp.memory",
            name = "Memory",
            ecosystem = PluginEcosystem.MCP,
            runtimeRequirements = setOf(PluginRuntimeKind.MCP_PROCESS)
        )
        val report = PluginCompatibilityAnalyzer.analyze(
            plugin, PluginEnvironmentSnapshot(prootReady = true)
        )

        assertEquals(PluginCompatibilityLevel.B, report.level)
        assertTrue(report.capabilityStatuses.any { it.capability == PluginCapability.TOOLS })
    }

    @Test
    fun `mcp connector without proot rates D`() {
        val plugin = PluginDescriptor(
            id = "mcp.memory",
            name = "Memory",
            ecosystem = PluginEcosystem.MCP,
            runtimeRequirements = setOf(PluginRuntimeKind.MCP_PROCESS)
        )
        val report = PluginCompatibilityAnalyzer.analyze(plugin, PluginEnvironmentSnapshot())

        assertEquals(PluginCompatibilityLevel.D, report.level)
    }

    @Test
    fun `functional plugin is native runtime and rates B`() {
        val plugin = PluginDescriptor(
            id = "functional.daily-briefing",
            name = "Daily Briefing",
            ecosystem = PluginEcosystem.FUNCTIONAL,
            runtimeRequirements = setOf(PluginRuntimeKind.NATIVE_KOTLIN)
        )
        val report = PluginCompatibilityAnalyzer.analyze(plugin, PluginEnvironmentSnapshot())

        assertEquals(PluginCompatibilityLevel.B, report.level)
        assertTrue(report.runtimeVerdicts.single().available)
    }

    @Test
    fun `bun missing is informational not level capping when node present`() {
        val plugin = PluginDescriptor(
            id = "opencode.demo",
            name = "Demo",
            ecosystem = PluginEcosystem.OPENCODE_V2
        )
        val env = PluginEnvironmentSnapshot(nodeVersion = "v22.11.0", npmVersion = "10.9.0")
        val report = PluginCompatibilityAnalyzer.analyze(plugin, env)

        // node+bun 候选只缺 bun：不触发「全部缺失」的 D 封顶路径
        val bun = report.runtimeVerdicts.first { it.runtime == PluginRuntimeKind.BUN }
        assertFalse(bun.available)
        assertTrue(report.reasons.any { it.contains("bun") && it.contains("不可默认以 node 等价替代") })
    }

    @Test
    fun `undeclared slot in ecosystem is unknown not crash`() {
        val plugin = PluginDescriptor(
            id = "claude.weird",
            name = "Weird",
            ecosystem = PluginEcosystem.CLAUDE,
            declaredCapabilities = setOf(PluginCapability.STORAGE) // Claude 格式不含该槽位
        )
        val report = PluginCompatibilityAnalyzer.analyze(plugin, PluginEnvironmentSnapshot())

        val storage = report.capabilityStatuses.single()
        assertEquals(CapabilitySupport.UNKNOWN, storage.support)
        assertTrue(storage.reason.contains("典型格式不含"))
    }

    // ── 环境快照：probe 通道 ──────────────────────────────────────────────

    @Test
    fun `environment snapshot probes node install port`() = runTest {
        val port = FakeNodePort(node = "v20.18.0", npm = "10.8.2")
        val snapshot = PluginEnvironmentSnapshot.probe(port)
        assertEquals("v20.18.0", snapshot.nodeVersion)
        assertEquals("10.8.2", snapshot.npmVersion)
        // 未探测字段保持缺失（诚实边界：不默认乐观）
        assertNull(snapshot.bunVersion)
        assertFalse(snapshot.prootReady)
    }

    /** 手写 fake（仓库纪律：无 mock 框架）。 */
    private class FakeNodePort(
        private val node: String?,
        private val npm: String?
    ) : NodeInstallPort {
        override suspend fun exec(command: List<String>, cwd: File, timeoutMs: Long): Result<NodeInstallPort.ExecOutcome> =
            Result.failure(UnsupportedOperationException("fake"))

        override suspend fun probeRuntime() = NodeInstallPort.RuntimeProbe(node, npm)
    }
}
