package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionJson
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.adapters.DshExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.NodeFamilyInstaller
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DSH Native Plugin Host 契约锁（2026-11，P0+P1 首批）：
 *
 * ① 纯 JVM：能力快照 Store（roundtrip/过期失效）、兼容判定、包分类与指纹、
 *    钉版安装（npm 真实版本+integrity / git SHA）、账本真实版本写入；
 * ② 真实 node 端到端（CI 与本机都有 node）：fixture 插件 → 脚本部署 →
 *    动态验证 → capabilities.json —— **P0 硬门槛的 JVM 侧证明**。
 *
 * 诚实约束：真实 defineTool（@deepseek-ai/dsh-tools）的真机验证不在本类
 * （CI 无 npm 依赖面）—— fixture 用同形桩，真机 P0 由 docs/dsh-compat.md 钉定。
 */
class DshNativeHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newLayout(): ExtensionDirLayout = ExtensionDirLayout(tmp.newFolder("ext-root"))

    // ═══ fake 端口：产物落盘模拟（npm 含真实版本/integrity；git 含 .git+SHA）═══

    private class FakeNodePort : NodeInstallPort {
        val executed = mutableListOf<List<String>>()
        var introspectEnvelope: String = "{}"

        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long,
            stdin: String?
        ): Result<NodeInstallPort.ExecOutcome> {
            executed += command
            if (command.firstOrNull() == "npm") {
                val prefix = command[command.indexOf("--prefix") + 1]
                val pkg = command.last()
                val pkgDir = File(prefix, "node_modules/$pkg")
                pkgDir.mkdirs()
                File(pkgDir, "index.js").writeText("module.exports = function (ctx) {}")
                File(pkgDir, "package.json").writeText("""{"name":"$pkg","version":"2.7.1","main":"index.js"}""")
                File(prefix, "package-lock.json").writeText(
                    """{"packages":{"node_modules/$pkg":{"integrity":"sha512-FAKEINTEGRITY="}}}"""
                )
            }
            if (command.firstOrNull() == "git" && command[1] == "clone") {
                val target = File(command.last())
                target.mkdirs()
                File(target, ".git").mkdirs()
                File(target, "README.md").writeText("# repo")
            }
            if (command.firstOrNull() == "git" && command[1] == "rev-parse") {
                return Result.success(NodeInstallPort.ExecOutcome(0, "abc123def4567890abc123def4567890abc12345", ""))
            }
            if (command.firstOrNull() == "node" && command.any { it.endsWith("dsh-introspect.js") }) {
                return Result.success(NodeInstallPort.ExecOutcome(0, introspectEnvelope, ""))
            }
            if (command.firstOrNull() == "node" && command.any { it.endsWith("dsh-analyze.js") }) {
                return Result.success(
                    NodeInstallPort.ExecOutcome(
                        0,
                        """{"ok":true,"kind":"static-analyze","analyzer":null,"note":"AST 分析器未就绪",""" +
                            """"serviceTouches":{},"declaredInject":[],"dynamic":[],"analyzed":1,"skipped":0,"errors":[]}""",
                        ""
                    )
                )
            }
            return Result.success(NodeInstallPort.ExecOutcome(0, "ok", ""))
        }

        override suspend fun probeRuntime() = NodeInstallPort.RuntimeProbe("v22.1.0", "10.9.0")
    }

    /** 真实能力面 envelope（对齐 dsh-introspect.js 真实输出形态）。 */
    private fun introspectEnvelopeOf(vararg tools: Pair<String, String>): String {
        val toolsJson = tools.joinToString(",") { (name, desc) ->
            """{"name":"$name","description":"$desc",""" +
                """"parametersSchema":"{\"type\":\"object\",\"properties\":{}}","parametersSchemaExact":true,"outputSchema":null}"""
        }
        return """{"ok":true,"kind":"introspect","protocol":1,"form":"cjs-function",""" +
            """"name":"dsh-fake-plugin","entry":"index.js","inject":[],"requiredServices":[],""" +
            """"unsupportedServices":[],"tools":[$toolsJson],"servicesTouched":{},"disposeErrors":[]}"""
    }

    // ═══ ① 纯 JVM 契约 ═══════════════════════════════════════════════

    @Test
    fun `capabilities store roundtrip and expiry on fingerprint change`() {
        val layout = newLayout()
        val store = DshCapabilitiesStore(layout)
        val caps = DshCapabilities(
            plugin = DshCapabilities.PluginIdentity(id = "dsh.demo", version = "1.0.0"),
            inputFingerprint = "fingerprint-v1"
        )
        store.write("dsh.demo", caps).getOrThrow()

        val fresh = store.read("dsh.demo", currentFingerprint = "fingerprint-v1")
        assertNotNull("指纹一致可读", fresh)
        assertEquals("1.0.0", fresh!!.plugin.version)

        assertNull("指纹变化 = 快照过期诚实失效（注册场景绝不拿旧账）", store.read("dsh.demo", currentFingerprint = "changed"))
        assertNotNull("不传指纹 = 展示场景可读", store.read("dsh.demo"))
    }

    @Test
    fun `compatibility resolver honest levels`() = runTest {
        val resolver = DshCompatibilityResolver()
        val ready = NodeInstallPort.RuntimeProbe("v22.0.0", "10.0.0")

        // node 缺失 = incompatible（不虚报可分析）
        assertEquals(
            DshCapabilities.Compatibility.Level.INCOMPATIBLE,
            resolver.resolve(null, null, NodeInstallPort.RuntimeProbe(null, null)).level
        )
        // 未声明 DSH 依赖 = partial + 未声明说明
        val undeclared = resolver.resolve(null, null, ready)
        assertEquals(DshCapabilities.Compatibility.Level.PARTIAL, undeclared.level)
        assertTrue(undeclared.notes.any { it.contains("未声明") })
        // 超出已验证范围 = minimal 谨慎档（如如实实，不 fail-open）
        assertEquals(
            DshCapabilities.Compatibility.Level.MINIMAL,
            resolver.resolve("^9.9.9", null, ready).level
        )
        // slots 永不支持（红线 1）
        assertTrue(
            resolver.serviceSupport["slots"] == DshCompatibilityResolver.ServiceLevel.UNSUPPORTED
        )
    }

    @Test
    fun `artifact analyzer classification fingerprint and budgets`() = runTest {
        val layout = newLayout()
        val extId = "dsh.demo"
        val pkg = layout.pkgDir(extId)
        pkg.mkdirs()
        File(pkg, "package.json").writeText(
            """{"name":"dsh-demo","version":"3.1.4","main":"lib/index.js","engines":{"node":">=18"}}"""
        )
        File(pkg, "lib").mkdirs()
        File(pkg, "lib/index.js").writeText("module.exports = () => {}")
        File(pkg, "node_modules/dep").mkdirs()
        File(pkg, "node_modules/dep/index.js").writeText("// 依赖不入本体清单")
        repeat(5) { File(pkg, "pad-$it.txt").writeText("x") }

        val analyzer = DshArtifactAnalyzer(FakeNodePort(), layout, layout.extensionRoot)
        val report = analyzer.analyze(extId).getOrThrow()

        assertEquals(DshArtifactAnalyzer.PackageClass.CORDIS_CODE, report.packageClass)
        assertEquals("dsh-demo", report.pkgName)
        assertEquals("3.1.4", report.version)
        assertEquals(">=18", report.declaredNodeRange)
        assertEquals("lib/index.js", report.mainEntry)
        assertTrue("指纹非空且稳定", report.fingerprint.length == 64)
        assertEquals("node_modules 不入清单（1 pkg.json + 1 lib + 5 pad）", 7, report.analyzedFiles)
        assertNull("acorn 未 bootstrap = analyzer 诚实 null", report.analyzerUsed)
    }

    @Test
    fun `pinned install captures real version integrity and git sha`() = runTest {
        val port = FakeNodePort()
        val installer = NodeFamilyInstaller(port, newLayout())

        // npm：真实版本 + integrity
        val (pkgFile, npmPinned) = installer.npmInstallPinned("dsh.demo", "dsh-demo").getOrThrow()
        assertTrue(File(pkgFile, "node_modules/dsh-demo/package.json").isFile)
        assertEquals("安装产物真实版本", "2.7.1", npmPinned.version)
        assertEquals("sha512-FAKEINTEGRITY=", npmPinned.integrity)
        assertTrue(npmPinned.toDescriptor().startsWith("npm:2.7.1"))

        // git：commit SHA 钉定
        val (_, gitPinned) = installer.gitClonePinned("dsh.demo2", "owner/dsh-repo").getOrThrow()
        assertEquals("git@abc123def456", gitPinned.toDescriptor())
        assertEquals("abc123def4567890abc123def4567890abc12345", gitPinned.revision)
    }

    @Test
    fun `installer ledger records real version not spec placeholder`() = runTest {
        val port = FakeNodePort()
        port.introspectEnvelope = introspectEnvelopeOf("fake_tool_a" to "工具A")
        val layout = newLayout()
        val store = DshCapabilitiesStore(layout)
        val analyzer = DshArtifactAnalyzer(port, layout, layout.extensionRoot)
        val introspector = DshPluginIntrospector(port, layout, layout.extensionRoot, analyzer, store)
        val adapter = DshExtensionAdapter(port, layout, introspector)
        val ledger = ExtensionRegistry(tmp.newFolder("ledger"))
        val installer = com.apex.agent.core.tools.extension.ExtensionInstaller(
            adapters = com.apex.agent.core.tools.extension.ExtensionAdapterRegistry(listOf(adapter)),
            registry = ledger
        )

        val manifest = installer.resolve("dsh:dsh-demo").getOrThrow()
        installer.install(manifest.manifestHash, confirmToken = "confirm").getOrThrow()

        // 安装产物就位后直过 introspector：失败时异常原样上抛（CI 可见真栈），不吞成 null
        val capsDirect = introspector.introspectAndStore("dsh.dsh-demo", "npm:test").getOrThrow()
        assertEquals("动态验证产出真实版本", "2.7.1", capsDirect.plugin.version)

        val record = ledger.get("dsh.dsh-demo")!!
        assertEquals("账本版本 = 安装产物真实版本（修正四）", "2.7.1", record.version)
        assertTrue("钉定描述入库", record.pinnedSource.startsWith("npm:2.7.1"))
        // 能力快照落盘且与账本一致
        val caps = store.read("dsh.dsh-demo")
        assertNotNull(caps)
        assertEquals("2.7.1", caps!!.plugin.version)
        assertEquals(1, caps.availableTools.size)
        assertEquals("fake_tool_a", caps.availableTools.single().name)
    }

    // ═══ ② 真实 node 端到端（P0 JVM 侧证明）══════════════════════════

    private fun nodeAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("node", "--version").start()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /** 真进程 NodeInstallPort（宿主 node —— fixture 验证专用，不进生产路径）。 */
    private class RealNodePort(private val cwd: File) : NodeInstallPort {
        override suspend fun exec(
            command: List<String>,
            dir: File,
            timeoutMs: Long,
            stdin: String?
        ): Result<NodeInstallPort.ExecOutcome> = runCatching {
            val proc = ProcessBuilder(command).directory(dir.takeIf { it.isDirectory } ?: cwd)
                .redirectErrorStream(false).start()
            if (stdin != null) {
                proc.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
            } else {
                runCatching { proc.outputStream.close() }
            }
            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
            val exited = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            NodeInstallPort.ExecOutcome(if (exited) proc.exitValue() else -1, out, err)
        }

        override suspend fun probeRuntime() =
            NodeInstallPort.RuntimeProbe(nodeVersion = "v22-host", npmVersion = null)
    }

    /** fixture 目录拷贝到扩展布局（真实脚本的验证基座）。 */
    private fun installFixture(layout: ExtensionDirLayout, extId: String, fixture: String): File {
        val fixtureDir = File(
            DshNativeHostTest::class.java.getResource("/dsh-fixture/$fixture")!!.toURI()
        )
        val pkg = layout.pkgDir(extId)
        fixtureDir.copyRecursively(pkg)
        return pkg
    }

    @Test
    fun `real node introspect cjs fixture captures tools and schemas`() = runTest {
        assumeTrue("本环境无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val layout = newLayout()
        installFixture(layout, "dsh.fncjs", "fn-cjs")
        val port = RealNodePort(layout.extensionRoot)
        val deployed = DshHostScripts.deploy(layout.extensionRoot).getOrThrow()

        val outcome = port.exec(
            command = listOf(
                "node", File(deployed, "dsh-introspect.js").absolutePath,
                layout.pkgDir("dsh.fncjs").absolutePath
            ),
            dir = layout.extensionRoot,
            timeoutMs = 60_000
        ).getOrThrow()
        assertTrue("exit 0：${outcome.stderr}", outcome.success)

        val env = ExtensionJson.json.parseToJsonElement(outcome.stdout.trim()).jsonObject
        assertEquals("true", env["ok"]!!.jsonPrimitive.content)
        assertEquals("cjs-function", env["form"]!!.jsonPrimitive.content)
        assertEquals("npm 包名（fn.name 'apply' 不是插件名）", "dsh-fixture-fn-cjs", env["name"]!!.jsonPrimitive.content)
        val tools = env["tools"] as kotlinx.serialization.json.JsonArray
        val toolNames = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("fixture_hello", "fixture_stateful_counter"), toolNames)
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(
            tools.first().jsonObject["parametersSchema"]!!.jsonPrimitive.content
        ).jsonObject
        assertTrue("parameters → JSON Schema 转换含 required", schema["required"].toString().contains("name"))
    }

    @Test
    fun `real node run-tool executes with stdin args`() = runTest {
        assumeTrue("本环境无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val layout = newLayout()
        installFixture(layout, "dsh.fncjs", "fn-cjs")
        val port = RealNodePort(layout.extensionRoot)
        val deployed = DshHostScripts.deploy(layout.extensionRoot).getOrThrow()

        val outcome = port.exec(
            command = listOf(
                "node", File(deployed, "dsh-run-tool.js").absolutePath,
                layout.pkgDir("dsh.fncjs").absolutePath, "fixture_hello"
            ),
            dir = layout.extensionRoot,
            timeoutMs = 60_000,
            stdin = """{"name":"Guru"}"""
        ).getOrThrow()
        assertTrue(outcome.success)
        val env = ExtensionJson.json.parseToJsonElement(outcome.stdout.trim()).jsonObject
        assertEquals("true", env["ok"]!!.jsonPrimitive.content)
        assertEquals("你好, Guru", env["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `real node esm fixture with unsupported inject is honestly empty`() = runTest {
        assumeTrue("本环境无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val layout = newLayout()
        installFixture(layout, "dsh.objesm", "obj-esm")
        val port = RealNodePort(layout.extensionRoot)
        val deployed = DshHostScripts.deploy(layout.extensionRoot).getOrThrow()

        val outcome = port.exec(
            command = listOf(
                "node", File(deployed, "dsh-introspect.js").absolutePath,
                layout.pkgDir("dsh.objesm").absolutePath
            ),
            dir = layout.extensionRoot,
            timeoutMs = 60_000
        ).getOrThrow()
        val env = ExtensionJson.json.parseToJsonElement(outcome.stdout.trim()).jsonObject
        assertEquals("fixture-esm（显式导出名）", "fixture-esm", env["name"]!!.jsonPrimitive.content)
        val unsupported = env["unsupportedServices"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("agents"), unsupported.map { it.jsonPrimitive.content })
        assertEquals("依赖就绪语义：工具面为空，绝不虚报", 0, (env["tools"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `real node abort mid apply reports partial capture`() = runTest {
        assumeTrue("本环境无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val layout = newLayout()
        installFixture(layout, "dsh.abort", "abort-mid-apply")
        val port = RealNodePort(layout.extensionRoot)
        val deployed = DshHostScripts.deploy(layout.extensionRoot).getOrThrow()

        val outcome = port.exec(
            command = listOf(
                "node", File(deployed, "dsh-introspect.js").absolutePath,
                layout.pkgDir("dsh.abort").absolutePath
            ),
            dir = layout.extensionRoot,
            timeoutMs = 60_000
        ).getOrThrow()
        val env = ExtensionJson.json.parseToJsonElement(outcome.stdout.trim()).jsonObject
        assertEquals("true", env["ok"]!!.jsonPrimitive.content)
        val tools = env["tools"] as kotlinx.serialization.json.JsonArray
        assertEquals("已注册部分如实上报", 1, tools.size)
        assertEquals("fixture_before_abort", tools.first().jsonObject["name"]!!.jsonPrimitive.content)
        val unsupported = env["unsupportedServices"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("storage"), unsupported.map { it.jsonPrimitive.content })
    }

    @Test
    fun `real node class form rejected honestly`() = runTest {
        assumeTrue("本环境无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val layout = newLayout()
        installFixture(layout, "dsh.cls", "class-form")
        val port = RealNodePort(layout.extensionRoot)
        val deployed = DshHostScripts.deploy(layout.extensionRoot).getOrThrow()

        val outcome = port.exec(
            command = listOf(
                "node", File(deployed, "dsh-introspect.js").absolutePath,
                layout.pkgDir("dsh.cls").absolutePath
            ),
            dir = layout.extensionRoot,
            timeoutMs = 60_000
        ).getOrThrow()
        val env = ExtensionJson.json.parseToJsonElement(outcome.stdout.trim()).jsonObject
        assertEquals("false", env["ok"]!!.jsonPrimitive.content)
        assertEquals("unsupported-class-form", env["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `full chain real node fixture to capabilities json`() = runTest {
        assumeTrue("本环境无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val layout = newLayout()
        installFixture(layout, "dsh.fncjs", "fn-cjs")
        val port = RealNodePort(layout.extensionRoot)
        val store = DshCapabilitiesStore(layout)
        val analyzer = DshArtifactAnalyzer(port, layout, layout.extensionRoot)
        val introspector = DshPluginIntrospector(port, layout, layout.extensionRoot, analyzer, store)

        val caps = introspector.introspectAndStore("dsh.fncjs", "npm:1.2.3").getOrThrow()
        assertEquals("full chain：observed 工具真实可用", 2, caps.availableTools.size)
        assertEquals(DshCapabilities.Compatibility.Level.PARTIAL, caps.compatibility.level)
        assertEquals("npm:1.2.3", caps.plugin.pinnedSource)
        assertEquals("指纹回写（快照新鲜度依据）", caps.inputFingerprint, store.read("dsh.fncjs", caps.inputFingerprint)!!.inputFingerprint)
        assertTrue("profile/dsh/capabilities.json 落盘", store.read("dsh.fncjs") != null)
    }
}
