package com.apex.agent.core.tools.extension

import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.SkillExtensionAdapter
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 统一扩展模型（市场完全重构 PR-A）的契约锁：
 *
 * 1. spec 语法表（[ExtensionSpecParser]）—— 各生态前缀的判定与载荷提取；
 * 2. 账本（[ExtensionRegistry]）—— 记账/幂等覆盖/清账/落盘重载；
 * 3. 闸门（[ExtensionInstaller]）—— **hash 门（先看后装）**、篡改检测、
 *    确认门（BYPASS 不豁免的模型侧协议）、缓存取回、账本登记；
 * 4. 双适配器 —— Skill 走真实 SkillRegistry（临时目录）、MCP 走 fake 端口
 *   （不真连进程），register/import 语义分离。
 *
 * 纯 JVM（JUnit4 + runTest + TemporaryFolder + 手写 fake，无 mock 框架）。
 */
class ExtensionModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ ① spec 语法 ═══════════════════════════════════════════════════

    @Test
    fun `spec 语法表 各前缀判定与载荷提取`() {
        fun typeOf(spec: String): String? = ExtensionSpecParser.parse(spec).getOrNull()?.type
        fun payloadOf(spec: String): String? = ExtensionSpecParser.parse(spec).getOrNull()?.payload

        assertEquals(ExtensionTypes.SKILL, typeOf("skill:https://example.com/m.json"))
        assertEquals("https://example.com/m.json", payloadOf("skill:https://example.com/m.json"))
        assertEquals(ExtensionTypes.SKILL, typeOf("skill:json:{\"id\":\"x\"}"))
        assertEquals("{\"id\":\"x\"}", payloadOf("skill:json:{\"id\":\"x\"}"))
        assertEquals(SourceKinds.LOCAL, ExtensionSpecParser.parse("skill:/tmp/m.json").getOrNull()?.sourceKind)

        assertEquals(ExtensionTypes.MCP_CONNECTOR, typeOf("mcp:{\"command\":\"npx\"}"))
        assertEquals(ExtensionTypes.MCP_CONNECTOR, typeOf("mcp:import:/sdcard/mcp.json"))
        assertEquals(ExtensionTypes.MCP_CONNECTOR, typeOf("mcp:memory"))

        assertEquals(ExtensionTypes.CLAUDE_PLUGIN, typeOf("claude:foo@bar"))
        assertEquals(ExtensionTypes.DSH_PLUGIN, typeOf("dsh:dsh-web"))
        assertEquals(ExtensionTypes.OPENCODE_PLUGIN, typeOf("npm:@scope/pkg"))
        assertEquals(ExtensionTypes.DSH_PLUGIN, typeOf("npm:dsh-something"))
        assertEquals(ExtensionTypes.APK_PACKAGE, typeOf("apk:/sdcard/x.apk"))

        // 裸 https URL 以 .json 结尾 → skill 猜测；其它 → 未知交给适配器探测
        assertEquals(ExtensionTypes.SKILL, typeOf("https://cdn.example.com/m.json?raw=1") ?: null)
        assertTrue(ExtensionSpecParser.parse("https://example.com/plugin-page").getOrNull()?.type == null)

        // 未知前缀诚实失败
        assertTrue(ExtensionSpecParser.parse("foobar:x").isFailure)
        assertTrue(ExtensionSpecParser.parse("   ").isFailure)
    }

    @Test
    fun `npm 包名白名单 防注入字符拒绝`() {
        assertTrue(ExtensionSpecParser.isNpmName("web-scraper"))
        assertTrue(ExtensionSpecParser.isNpmName("@modelcontextprotocol/server-memory"))
        assertTrue(!ExtensionSpecParser.isNpmName("pkg; rm -rf /"))
        assertTrue(!ExtensionSpecParser.isNpmName("pkg\nmalicious"))
        assertTrue(!ExtensionSpecParser.isNpmName(""))
    }

    // ═══ ② 账本 ═════════════════════════════════════════════════════════

    private fun record(id: String, version: String = "1.0.0") = ExtensionRegistry.Record(
        id = id, type = "skill", spec = "skill:json:{}", name = id, version = version,
        runtime = RuntimeDomains.ANDROID, sourceKind = SourceKinds.JSON,
        installedAt = System.currentTimeMillis()
    )

    @Test
    fun `账本 记账 幂等覆盖 清账 落盘重载`() {
        val dir = tmp.newFolder("ledger")
        val registry = ExtensionRegistry(dir)
        registry.record(record("skill.a"))
        registry.record(record("skill.b", "2.0.0"))
        assertEquals(2, registry.all().size)

        // 同 id 覆盖 = 升级语义
        registry.record(record("skill.a", "1.1.0"))
        assertEquals(2, registry.all().size)
        assertEquals("1.1.0", registry.get("skill.a")!!.version)

        assertTrue(registry.remove("skill.a"))
        assertTrue(!registry.contains("skill.a"))
        assertTrue(!registry.remove("skill.a")) // 二次清账诚实 false

        // 落盘重载：新实例吸收既有账
        val reloaded = ExtensionRegistry(dir)
        assertEquals(1, reloaded.all().size)
        assertEquals("skill.b", reloaded.all().single().id)
    }

    // ═══ ③ 闸门 ═════════════════════════════════════════════════════════

    /** 手写 fake 适配器（记录调用序；inspect 报告可控）。 */
    private class FakeAdapter(
        override val supportedTypes: Set<String>,
        private val requireConfirm: Boolean = false
    ) : ExtensionAdapter {
        var installed = 0
        var uninstalled = 0

        override suspend fun resolve(draft: ExtensionSpecParser.SpecDraft): Result<ExtensionManifest> {
            val m = ExtensionManifest(
                id = "fake.${draft.raw}", type = supportedTypes.first(), spec = draft.raw,
                name = "Fake", version = "1.0", sourceKind = SourceKinds.JSON,
                permissions = if (requireConfirm) listOf("network") else emptyList(),
                resolved = buildJsonObject { put("spec", draft.raw) },
                summary = "fake"
            )
            return Result.success(m.copy(manifestHash = m.computeHash()))
        }

        override suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> =
            Result.success(InspectReport(manifest, targetRegistry = "FakeRegistry", targetLocation = "fake"))

        override suspend fun install(manifest: ExtensionManifest): Result<InstallOutcome> {
            installed++
            return Result.success(InstallOutcome(manifest.id, manifest.type, manifest.version, "FakeRegistry: fake"))
        }

        override suspend fun uninstall(manifest: ExtensionManifest): Result<Unit> {
            uninstalled++
            return Result.success(Unit)
        }
    }

    @Test
    fun `闸门 hash 门 未 inspect 直接装被拒`() = runTest {
        val ledgerDir = tmp.newFolder("gate-ledger")
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(listOf(FakeAdapter(setOf("skill")))),
            ExtensionRegistry(ledgerDir)
        )
        // 凭空指纹 → 缓存未命中 → SecurityException
        val result = installer.install("deadbeef")
        assertTrue("未 resolve/inspect 过的指纹必须被拒", result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `闸门 resolve 后凭指纹安装 账本登记`() = runTest {
        val ledgerDir = tmp.newFolder("gate-ledger-ok")
        val adapter = FakeAdapter(setOf("skill"))
        val ledger = ExtensionRegistry(ledgerDir)
        val installer = ExtensionInstaller(ExtensionAdapterRegistry(listOf(adapter)), ledger)

        val manifest = installer.resolve("skill:json:{x}").getOrThrow()
        val outcome = installer.install(manifest.manifestHash).getOrThrow()

        assertEquals(1, adapter.installed)
        assertEquals("FakeRegistry: fake", outcome.where)
        assertTrue(ledger.contains(manifest.id))
    }

    @Test
    fun `闸门 显式 manifest 原文 篡改检测`() = runTest {
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(listOf(FakeAdapter(setOf("skill")))),
            ExtensionRegistry(tmp.newFolder("gate-ledger-tamper"))
        )
        val manifest = installer.resolve("skill:json:{x}").getOrThrow()
        val genuine = ExtensionManifest.canonicalJson(manifest)
        // 正本 + 正确指纹 → 过
        assertTrue(installer.install(manifest.manifestHash, manifestJson = genuine).isSuccess)
        // 正本 + 错误指纹 → 篡改拒绝
        val result = installer.install("00${manifest.manifestHash.drop(2)}", manifestJson = genuine)
        assertTrue("指纹不匹配必须拒绝", result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `确认门 敏感扩展需显式 confirm`() = runTest {
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(listOf(FakeAdapter(setOf("skill"), requireConfirm = true))),
            ExtensionRegistry(tmp.newFolder("gate-ledger-confirm"))
        )
        val manifest = installer.resolve("skill:json:{x}").getOrThrow()
        assertTrue("无 confirm 被拒", installer.install(manifest.manifestHash).exceptionOrNull() is SecurityException)
        assertTrue("错误令牌被拒", installer.install(manifest.manifestHash, confirmToken = "yes").exceptionOrNull() is SecurityException)
        assertTrue(installer.install(manifest.manifestHash, confirmToken = "confirm").isSuccess)
    }

    @Test
    fun `未注册适配器的类型 诚实报错`() = runTest {
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(listOf(FakeAdapter(setOf("skill")))),
            ExtensionRegistry(tmp.newFolder("gate-ledger-ni"))
        )
        // claude: 语法合法但 PR-A 未接适配器 → UnsupportedOperationException
        val result = installer.resolve("claude:foo@bar")
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("适配器尚未接入"))
    }

    // ═══ ④ Skill 适配器（真实 SkillRegistry，临时目录）══════════════════

    private fun newSkillRegistry(): SkillRegistry =
        SkillRegistry(skillsDir = tmp.newFolder("skills-${System.nanoTime()}"))

    private fun skillManifestJson(id: String = "web-scraper") = """
        {"schema":"apex-skill-v1","id":"$id","name":"Web Scraper","version":"1.2.0",
         "description":"抓取网页正文","promptInjection":"抓网页时用此方法论"}
    """.trimIndent()

    @Test
    fun `Skill 适配器 resolve→install 落真实 SkillRegistry 并进激活集`() = runTest {
        val registry = newSkillRegistry()
        val activation = com.apex.agent.core.tools.skill.SkillActivationStore()
        val adapter = SkillExtensionAdapter(skillRegistry = registry, activation = activation)

        val manifest = adapter.resolve(ExtensionSpecParser.parse("skill:json:${skillManifestJson()}").getOrThrow()).getOrThrow()
        assertEquals("skill.web-scraper", manifest.id)
        assertEquals("1.2.0", manifest.version)
        assertEquals(RuntimeDomains.ANDROID, manifest.runtime)
        assertTrue("resolve 产物必须携带指纹", manifest.manifestHash.isNotBlank())

        val report = adapter.inspect(manifest).getOrThrow()
        assertTrue(report.targetRegistry.contains("SkillRegistry"))

        val outcome = adapter.install(manifest).getOrThrow()
        assertEquals("SkillRegistry: web-scraper", outcome.where)
        // 实体真落 SkillRegistry
        assertNotNull(registry.get("web-scraper"))
    }

    @Test
    fun `Skill 适配器 非法 id 与本地缺失文件诚实失败`() = runTest {
        val adapter = SkillExtensionAdapter(skillRegistry = newSkillRegistry())
        val bad = adapter.resolve(ExtensionSpecParser.parse("skill:json:{\"id\":\"../evil\",\"name\":\"x\"}").getOrThrow())
        assertTrue("路径穿越 id 必须拒绝", bad.isFailure)

        val ghost = adapter.resolve(ExtensionSpecParser.parse("skill:/nonexistent/m.json").getOrThrow())
        assertTrue(ghost.isFailure)
    }

    @Test
    fun `Skill 适配器 uninstall 拒绝 bundled 技能`() = runTest {
        val registry = newSkillRegistry()
        registry.installBundled(listOf(skillManifestJson("builtin-skill")))
        val adapter = SkillExtensionAdapter(skillRegistry = registry)
        val m = ExtensionManifest(
            id = "skill.builtin-skill", type = ExtensionTypes.SKILL, spec = "x", name = "x",
            version = "1", sourceKind = SourceKinds.JSON
        )
        assertTrue("内置 bundled 技能不可卸载", adapter.uninstall(m).isFailure)
    }

    // ═══ ⑤ MCP 适配器（fake 端口，register/import 语义分离）═════════════

    private class FakeMcpPort : McpExtensionAdapter.McpConfigPort {
        val configs = mutableListOf<McpServerConfig>()
        override suspend fun add(config: McpServerConfig): Result<Unit> {
            configs += config; return Result.success(Unit)
        }
        override suspend fun remove(name: String) {
            configs.removeAll { it.name == name }
        }
        override fun all(): List<McpServerConfig> = configs.toList()
    }

    @Test
    fun `MCP 适配器 单台字面量为 register import 文件为 import`() = runTest {
        val port = FakeMcpPort()
        val adapter = McpExtensionAdapter(configs = port)

        // 单台字面量 → register
        val registerSpec = "mcp:{\"command\":\"npx\",\"args\":[\"-y\",\"@modelcontextprotocol/server-memory\"]}"
        val registerManifest = adapter.resolve(ExtensionSpecParser.parse(registerSpec).getOrThrow()).getOrThrow()
        assertEquals(Actions.REGISTER, registerManifest.action)
        assertEquals("mcp.mcp-extension-entry", registerManifest.id)
        assertTrue("STDIO 命令需 spawn-process 权限", "spawn-process" in registerManifest.permissions)

        // mcpServers 整包 → import；多台拒绝（指向 sync）
        val importSpec = "mcp:{\"mcpServers\":{\"memory\":{\"command\":\"npx\",\"args\":[\"-y\",\"@mcp/memory\"]},\"fs\":{\"command\":\"npx\",\"args\":[\"-y\",\"@mcp/fs\"]}}}"
        val multi = adapter.resolve(ExtensionSpecParser.parse(importSpec).getOrThrow())
        assertTrue("整包多台必须指向 ExtensionSync", multi.isFailure)

        // 单台整包 → import
        val singleImport = "mcp:{\"mcpServers\":{\"memory\":{\"command\":\"npx\",\"args\":[\"-y\",\"@mcp/memory\"]}}}"
        val importManifest = adapter.resolve(ExtensionSpecParser.parse(singleImport).getOrThrow()).getOrThrow()
        assertEquals(Actions.IMPORT, importManifest.action)
        assertEquals("mcp.memory", importManifest.id)

        // import 文件来源
        val importFile = tmp.newFile("mcp.json").apply {
            writeText("""{"mcpServers":{"cursor-memory":{"command":"npx"}}}""")
        }
        val fileManifest = adapter.resolve(ExtensionSpecParser.parse("mcp:import:${importFile.absolutePath}").getOrThrow()).getOrThrow()
        assertEquals(Actions.IMPORT, fileManifest.action)

        // 两种语义都写入同一注册表；同名同端点幂等，不同端点冲突
        adapter.install(registerManifest).getOrThrow()
        assertEquals(1, port.configs.size)
        val again = adapter.resolve(ExtensionSpecParser.parse(registerSpec).getOrThrow()).getOrThrow()
        val idempotent = adapter.install(again).getOrThrow()
        assertTrue(idempotent.notes.any { it.contains("幂等") })
        assertEquals(1, port.configs.size)

        val different = adapter.resolve(
            ExtensionSpecParser.parse("mcp:{\"command\":\"python\",\"args\":[\"-m\",\"other\"]}").getOrThrow()
        ).getOrThrow()
        assertTrue("同名不同端点必须冲突拒绝", adapter.install(different).isFailure)
    }

    @Test
    fun `MCP 适配器 沙箱配置映射 ubuntu 域`() = runTest {
        val adapter = McpExtensionAdapter(configs = FakeMcpPort())
        val manifest = adapter.resolve(
            ExtensionSpecParser.parse("mcp:{\"command\":\"npx\",\"args\":[\"-y\",\"@m/x\"],\"runInSandbox\":true}").getOrThrow()
        ).getOrThrow()
        assertEquals(RuntimeDomains.UBUNTU, manifest.runtime)
        val report = adapter.inspect(manifest).getOrThrow()
        assertTrue(report.domainDependencies.any { it.contains("PRoot") })
    }

    @Test
    fun `MCP 适配器 目录来源经 lookup 且信任级 verified`() = runTest {
        val adapter = McpExtensionAdapter(
            configs = FakeMcpPort(),
            catalogLookup = { name ->
                if (name == "memory") Result.success(
                    McpServerConfig(name = "memory", transport = McpTransport.HTTP, url = "http://127.0.0.1:3000")
                ) else Result.failure(IllegalArgumentException("未收录"))
            }
        )
        val manifest = adapter.resolve(ExtensionSpecParser.parse("mcp:memory").getOrThrow()).getOrThrow()
        assertEquals("mcp.memory", manifest.id)
        assertEquals("verified", manifest.trustLevel)
        assertEquals("network", manifest.permissions.single())
        assertTrue(adapter.install(manifest).isSuccess)
    }

    // ═══ ⑥ 编排器整链（resolve→inspect→install→list→uninstall）═════════

    @Test
    fun `编排整链 Skill 生态 resolve到uninstall 全通`() = runTest {
        val ledger = ExtensionRegistry(tmp.newFolder("e2e-ledger"))
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(
                listOf(
                    SkillExtensionAdapter(skillRegistry = newSkillRegistry()),
                    McpExtensionAdapter(configs = FakeMcpPort())
                )
            ),
            ledger
        )
        val manifest = installer.resolve("skill:json:${skillManifestJson("e2e-skill")}").getOrThrow()
        val report = installer.inspectSpec("skill:json:${skillManifestJson("e2e-skill")}").getOrThrow()
        assertEquals(manifest.manifestHash, report.manifest.manifestHash)
        installer.install(report.manifest.manifestHash).getOrThrow()
        assertEquals(1, ledger.all().size)
        installer.uninstall("skill.e2e-skill").getOrThrow()
        assertTrue(ledger.all().isEmpty())
    }
}
