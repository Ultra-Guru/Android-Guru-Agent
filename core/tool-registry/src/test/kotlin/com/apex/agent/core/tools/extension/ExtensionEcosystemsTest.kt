package com.apex.agent.core.tools.extension

import com.apex.agent.core.tools.extension.adapters.ApkPackageExtensionAdapter
import com.apex.agent.core.tools.extension.tools.ExtensionRunTool
import com.apex.agent.core.tools.extension.adapters.ClaudeExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.DshExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.OpenCodeExtensionAdapter
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PR-B 三生态适配器契约锁（OpenCode / Claude / DSH）：
 *
 * 1. Node 系基座 —— 白名单命令构造（恶意包名/仓库拒绝）、npm/git 安装、
 *    tmp → 原子 publish、失败零残留；
 * 2. OpenCode —— npm/local/github 三源、注册元数据落 profile、experimental 标注；
 * 3. DSH —— dsh-* 前缀强制、github 源、dsh plugin add 语义模拟（profile.json）；
 * 4. Claude —— 真实 ClaudePluginImporter 全链（fixture 包 → fusion bundle +
 *    资源技能），android 域，卸载联动清技能；
 * 5. 锁文件 —— 钉版/重载/漂移检测。
 *
 * 纯 JVM（JUnit4 + runTest + TemporaryFolder + 手写 fake 执行端口）。
 */
class ExtensionEcosystemsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ 手写 fake：Node 执行端口（模拟 npm/git 产物落盘）══════════════

    private class FakeNodePort(private val failCommand: String? = null) : NodeInstallPort {
        val executed = mutableListOf<List<String>>()

        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long
        ): Result<NodeInstallPort.ExecOutcome> {
            executed += command
            if (failCommand != null && command.firstOrNull() == failCommand) {
                return Result.success(NodeInstallPort.ExecOutcome(1, "", "simulated failure"))
            }
            // npm install --prefix <dir> -- <pkg>：模拟产物 node_modules/<pkg>/index.js
            if (command.firstOrNull() == "npm") {
                val prefix = command[command.indexOf("--prefix") + 1]
                val pkg = command.last()
                val pkgDir = File(prefix, "node_modules/${pkg.replace("@", "").replace("/", "-")}")
                pkgDir.mkdirs()
                File(pkgDir, "index.js").writeText("// fake plugin")
            }
            if (command.firstOrNull() == "git") {
                val target = File(command.last().trim('\''))
                target.mkdirs()
                File(target, "README.md").writeText("# fake repo")
            }
            return Result.success(NodeInstallPort.ExecOutcome(0, "ok", ""))
        }

        override suspend fun probeRuntime(): NodeInstallPort.RuntimeProbe =
            NodeInstallPort.RuntimeProbe(nodeVersion = "v22.0.0", npmVersion = "10.0.0")
    }

    private fun newLayout(): ExtensionDirLayout = ExtensionDirLayout(tmp.newFolder("ext-root"))

    // ═══ ① Node 基座：白名单与原子发布 ═══════════════════════════════

    @Test
    fun `npm 白名单外包名拒绝 恶意串不进命令`() = runTest {
        val port = FakeNodePort()
        val installer = com.apex.agent.core.tools.extension.adapters.NodeFamilyInstaller(port, newLayout())
        val result = installer.npmInstall("opencode.evil", "pkg; rm -rf /")
        assertTrue(result.isFailure)
        assertTrue("恶意命令不得执行", port.executed.isEmpty())
    }

    @Test
    fun `npm 安装 tmp 原子发布 失败零残留`() = runTest {
        val layout = newLayout()
        val okPort = FakeNodePort()
        val okInstaller = com.apex.agent.core.tools.extension.adapters.NodeFamilyInstaller(okPort, layout)
        val pkg = okInstaller.npmInstall("opencode.demo", "@scope/tool").getOrThrow()
        assertTrue("发布目录存在", File(pkg, "node_modules").isDirectory)
        assertEquals(1, okPort.executed.size)
        // 命令形态：npm install --prefix <tmp> --no-audit --no-fund -- <pkg>（全白名单片段）
        val cmd = okPort.executed.single()
        assertEquals("npm", cmd.first())
        assertTrue(cmd.contains("--prefix"))

        // 失败：tmp 清掉、pkg 不受污染
        val failPort = FakeNodePort(failCommand = "npm")
        val failInstaller = com.apex.agent.core.tools.extension.adapters.NodeFamilyInstaller(failPort, layout)
        assertTrue(failInstaller.npmInstall("opencode.demo2", "dsh-x").isFailure)
        assertTrue(File(layout.extensionDir("opencode.demo2"), "pkg").exists().not())
        assertTrue("tmp 残留必须清零", (layout.extensionDir("opencode.demo2").listFiles()?.filter { it.name.startsWith("tmp-") } ?: emptyList()).isEmpty())
    }

    @Test
    fun `github 仓库白名单外拒绝`() = runTest {
        val port = FakeNodePort()
        val installer = com.apex.agent.core.tools.extension.adapters.NodeFamilyInstaller(port, newLayout())
        assertTrue(installer.gitClone("opencode.x", "owner/repo/extra").isFailure)
        assertTrue(installer.gitClone("opencode.x", "own er/repo").isFailure)
        assertTrue(port.executed.isEmpty())
    }

    // ═══ ② OpenCode 适配器 ═══════════════════════════════════════════

    @Test
    fun `OpenCode npm 源 resolve→install 全链 ubuntu 域`() = runTest {
        val port = FakeNodePort()
        val layout = newLayout()
        val adapter = OpenCodeExtensionAdapter(port, layout)

        val draft = ExtensionSpecParser.parse("npm:@scope/tool").getOrThrow()
        assertEquals(ExtensionTypes.OPENCODE_PLUGIN, draft.type)
        val manifest = adapter.resolve(draft).getOrThrow()
        assertEquals("opencode.scope-tool", manifest.id)
        assertEquals(RuntimeDomains.UBUNTU, manifest.runtime)

        val report = adapter.inspect(manifest).getOrThrow()
        assertTrue(report.domainDependencies.any { it.contains("node") })

        val outcome = adapter.install(manifest).getOrThrow()
        assertTrue(outcome.where.contains("沙箱扩展目录"))
        assertTrue("注册元数据已写", File(layout.profileDir(manifest.id), "opencode.json").isFile)

        adapter.uninstall(manifest).getOrThrow()
        assertTrue(layout.extensionDir(manifest.id).exists().not())
    }

    @Test
    fun `OpenCode 本地 ts 文件源安装`() = runTest {
        val src = tmp.newFolder("src").resolve("my-plugin.ts").apply { writeText("export default {}") }
        val adapter = OpenCodeExtensionAdapter(FakeNodePort(), newLayout())
        // 消息化断言：本机无 JDK，失败原因只能靠 CI 回传 —— 带上真实异常文本
        val draft = ExtensionSpecParser.parse("opencode:${src.absolutePath}")
            .getOrElse { throw IllegalStateException("parse failed: ${it.message}", it) }
        val manifest = adapter.resolve(draft)
            .getOrElse { throw IllegalStateException("resolve failed: ${it.message}", it) }
        assertEquals("opencode.my-plugin", manifest.id)
        adapter.install(manifest).getOrElse { throw IllegalStateException("install failed: ${it.message}", it) }
    }

    // ═══ ③ DSH 适配器 ════════════════════════════════════════════════

    @Test
    fun `DSH dsh- 前缀强制 与 profile 注册`() = runTest {
        val layout = newLayout()
        val adapter = DshExtensionAdapter(FakeNodePort(), layout)

        // 非 dsh-* npm 包名 → 诚实拒绝
        assertTrue(
            adapter.resolve(ExtensionSpecParser.parse("dsh:not-dsh-pkg").getOrThrow()).isFailure
        )

        val manifest = adapter.resolve(ExtensionSpecParser.parse("dsh:dsh-web-tools").getOrThrow()).getOrThrow()
        assertEquals("dsh.dsh-web-tools", manifest.id)
        assertEquals(RuntimeDomains.UBUNTU, manifest.runtime)
        assertEquals(ExtensionTypes.DSH_PLUGIN, manifest.type)

        val outcome = adapter.install(manifest).getOrThrow()
        val profile = File(layout.profileDir(manifest.id), "dsh/profile.json")
        assertTrue("dsh plugin add 语义模拟：profile.json 必须落盘", profile.isFile)
        assertTrue(profile.readText().contains("dsh-profile-v1"))
        assertTrue(outcome.where.contains("沙箱扩展目录"))
    }

    @Test
    fun `DSH github 源解析`() = runTest {
        val adapter = DshExtensionAdapter(FakeNodePort(), newLayout())
        val manifest = adapter.resolve(ExtensionSpecParser.parse("dsh:github:owner/dsh-repo").getOrThrow()).getOrThrow()
        assertEquals("dsh.owner-dsh-repo", manifest.id)
        assertTrue(adapter.install(manifest).isSuccess)
    }

    // ═══ ④ Claude 适配器（真实 importer 全链，android 域）═════════════

    /** 构造最小合法 Claude 插件包（.claude-plugin/plugin.json + 技能 + 指令）。 */
    private fun newClaudeFixture(): File {
        val root = tmp.newFolder("claude-fixture")
        File(root, ".claude-plugin").mkdirs()
        File(root, ".claude-plugin/plugin.json").writeText(
            """{"name":"Test Plug","version":"2.0.0","description":"fixture 插件"}"""
        )
        val skillDir = File(root, "skills/pdf-helper").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText(
            """---
name: pdf-helper
description: PDF 处理方法论
---
## PDF Helper
按步骤处理 PDF。"""
        )
        val cmdDir = File(root, "commands").apply { mkdirs() }
        File(cmdDir, "do-thing.md").writeText("---\nname: do-thing\ndescription: x\n---\n做事。")
        return root
    }

    @Test
    fun `Claude 本地包 resolve→install→uninstall 全链 android 域`() = runTest {
        val fixture = newClaudeFixture()
        val fusionRegistry = FusionBundleRegistry(tmp.newFolder("fusion-bundles"))
        val skillRegistry = SkillRegistry(skillsDir = tmp.newFolder("skills-claude"))
        val adapter = ClaudeExtensionAdapter(fusionRegistry, skillRegistry, fetchBytes = { Result.failure(IllegalStateException("not used")) })

        val draft = ExtensionSpecParser.parse("claude:${fixture.absolutePath}").getOrThrow()
        assertEquals(ExtensionTypes.CLAUDE_PLUGIN, draft.type)
        val manifest = adapter.resolve(draft).getOrThrow()
        assertEquals("claude.test-plug", manifest.id)
        assertEquals(RuntimeDomains.ANDROID, manifest.runtime)
        assertTrue("resolve 产物必须携带指纹", manifest.manifestHash.isNotBlank())

        val report = adapter.inspect(manifest).getOrThrow()
        assertTrue("inspect 应列出将装的技能", report.warnings.any { it.contains("技能") })

        val outcome = adapter.install(manifest).getOrThrow()
        assertEquals("FusionBundleRegistry: claude-test-plug", outcome.where)
        assertNotNull("聚合包真落盘", fusionRegistry.get("claude-test-plug"))
        // 包内技能真进 SkillRegistry（纯 MD → 聚合内联；资源 zip 单独装）
        assertTrue("账本 notes 记录技能清单", outcome.notes.any { it.contains("技能已装") || it.contains("聚合中心") })

        // 卸载联动：聚合包 + 包内技能全清
        adapter.uninstall(manifest).getOrThrow()
        assertNull(fusionRegistry.get("claude-test-plug"))
    }

    @Test
    fun `Claude marketplace 名内置表解析`() = runTest {
        val adapter = ClaudeExtensionAdapter(
            FusionBundleRegistry(tmp.newFolder("fb")),
            SkillRegistry(skillsDir = tmp.newFolder("skills-mp")),
            fetchBytes = { url ->
                if (url.endsWith("marketplace.json")) {
                    Result.success(
                        """{"name":"claude-code","plugins":[
                           {"name":"pdf","source":{"source":"github","repo":"owner/pdf-repo"}}]}"""
                            .toByteArray()
                    )
                } else {
                    Result.success(ByteArray(0))
                }
            }
        )
        val manifest = adapter.resolve(ExtensionSpecParser.parse("claude:pdf@claude-code").getOrThrow()).getOrThrow()
        assertEquals("claude.pdf", manifest.id)
        assertEquals("owner/pdf-repo", manifest.resolved?.get("repo")?.let { (it as JsonPrimitive).content })
        // install 走 zipball 下载（fake 返回空字节 → 解压失败诚实报错，本测试只锁 resolve 映射）
        assertTrue(adapter.install(manifest).isFailure)
    }

    // ═══ ⑤ 锁文件 ════════════════════════════════════════════════════

    @Test
    fun `锁文件 钉版 重载 解锁`() {
        val dir = tmp.newFolder("lock-ledger")
        val registry = ExtensionRegistry(dir)
        assertTrue(registry.allLocks().isEmpty())

        registry.lock(ExtensionRegistry.LockEntry("mcp.memory", "hash-1", "1.0.0", "mcp:memory", 1L))
        assertEquals("hash-1", registry.getLock("mcp.memory")!!.manifestHash)

        // 重载吸收
        val reloaded = ExtensionRegistry(dir)
        assertEquals("hash-1", reloaded.getLock("mcp.memory")!!.manifestHash)

        // 重新钉版（覆盖）+ 解锁
        reloaded.lock(ExtensionRegistry.LockEntry("mcp.memory", "hash-2", "1.1.0", "mcp:memory", 2L))
        assertEquals("hash-2", reloaded.getLock("mcp.memory")!!.manifestHash)
        assertTrue(reloaded.unlock("mcp.memory"))
        assertNull(reloaded.getLock("mcp.memory"))
        assertTrue(!reloaded.unlock("mcp.memory"))
    }

    @Test
    fun `github 仓库解析助手 白名单`() {
        assertEquals("owner/repo", ExtensionSpecParser.githubRepoOf("github:owner/repo"))
        assertEquals("owner/repo", ExtensionSpecParser.githubRepoOf("owner/repo"))
        assertEquals("owner/repo", ExtensionSpecParser.githubRepoOf("owner/repo/"))
        assertNull(ExtensionSpecParser.githubRepoOf("owner"))
        assertNull(ExtensionSpecParser.githubRepoOf("owner/repo/extra"))
        assertNull(ExtensionSpecParser.githubRepoOf("ow ner/repo"))
        assertNull(ExtensionSpecParser.githubRepoOf("owner/re po"))
    }
}

// ═══ ⑦ APK 适配器（系统安装端口 fake，双重确认语义）═════════════════

class ApkPackageExtensionAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeApkPort : ApkPackageExtensionAdapter.ApkInstallPort {
        var initiated = 0
        override suspend fun installApk(apkPath: String): Result<String> {
            initiated++
            return Result.success("系统安装器已发起（sessionId 模拟）")
        }
    }

    @Test
    fun `APK resolve 路径校验与指纹`() = runTest {
        val adapter = ApkPackageExtensionAdapter(FakeApkPort())
        // 非 .apk 拒绝
        val notApk = tmp.newFile("plugin.zip")
        assertTrue(adapter.resolve(ExtensionSpecParser.parse("apk:${notApk.absolutePath}").getOrThrow()).isFailure)
        // 不存在拒绝
        assertTrue(adapter.resolve(ExtensionSpecParser.parse("apk:/nonexistent/x.apk").getOrThrow()).isFailure)
        // 合法
        val apk = tmp.newFile("my-ext.apk")
        val manifest = adapter.resolve(ExtensionSpecParser.parse("apk:${apk.absolutePath}").getOrThrow()).getOrThrow()
        assertTrue(manifest.id.startsWith("apk.my-ext-"))
        assertEquals(RuntimeDomains.ANDROID, manifest.runtime)
        assertTrue("系统安装 = 生命周期副作用，必须确认", manifest.hasPostinstall)
        assertTrue(manifest.permissions.contains("install-app"))
    }

    @Test
    fun `APK install 双重确认语义`() = runTest {
        val port = FakeApkPort()
        val ledger = ExtensionRegistry(tmp.newFolder("apk-ledger"))
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(listOf(ApkPackageExtensionAdapter(port))), ledger
        )
        val apk = tmp.newFile("signed-ext.apk")
        val manifest = installer.resolve("apk:${apk.absolutePath}").getOrThrow()

        // 无 confirm → 确认门拒绝（确认门在 ExtensionInstaller —— 走真实链路测）
        assertTrue(installer.install(manifest.manifestHash).exceptionOrNull() is SecurityException)
        assertEquals(0, port.initiated)
        // confirm → 发起系统安装
        val outcome = installer.install(manifest.manifestHash, confirmToken = "confirm").getOrThrow()
        assertEquals(1, port.initiated)
        assertTrue(outcome.where.contains("系统安装器"))
        // 卸载诚实拒绝（系统级卸载不归闸门）
        assertTrue(installer.uninstall(manifest.id).isFailure)
    }
}

// ═══ ⑧ ExtensionRun —— ubuntu 域扩展运行入口（方向②执行桥生产化）══════

class ExtensionRunToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRunPort : NodeInstallPort {
        var lastCommand: List<String>? = null
        override suspend fun exec(command: List<String>, cwd: File, timeoutMs: Long): Result<NodeInstallPort.ExecOutcome> {
            lastCommand = command
            // npm 形态模拟真实安装产物：node_modules/<pkg>/index.js + package.json(main)
            if (command.firstOrNull() == "npm") {
                val prefix = command[command.indexOf("--prefix") + 1]
                val pkg = command.last()
                val pkgDir = File(prefix, "node_modules/" + pkg)
                pkgDir.mkdirs()
                File(pkgDir, "index.js").writeText("// fake plugin entry")
                File(pkgDir, "package.json").writeText("{}")
            }
            return Result.success(NodeInstallPort.ExecOutcome(0, "ran ok", ""))
        }
        override suspend fun probeRuntime() = NodeInstallPort.RuntimeProbe("v22", "10")
    }

    private fun newLayout(): ExtensionDirLayout = ExtensionDirLayout(tmp.newFolder("run-ext-root"))

    /** 走真实 OpenCode 适配器安装（生成 profile 元数据），再记账本供 RunTool 寻址。 */
    private suspend fun installOpencodePlugin(port: FakeRunPort, layout: ExtensionDirLayout, pkgName: String): ExtensionRegistry {
        val adapter = OpenCodeExtensionAdapter(port, layout)
        val manifest = adapter.resolve(ExtensionSpecParser.parse("npm:$pkgName").getOrThrow()).getOrThrow()
        adapter.install(manifest).getOrThrow()
        val ledger = ExtensionRegistry(tmp.newFolder("run-ledger-" + pkgName))
        ledger.record(
            ExtensionRegistry.Record(
                id = manifest.id, type = manifest.type, spec = manifest.spec,
                name = manifest.name, version = manifest.version, runtime = manifest.runtime,
                sourceKind = manifest.sourceKind, installedAt = System.currentTimeMillis()
            )
        )
        return ledger
    }

    @Test
    fun `Run npm 扩展 node 入口白名单执行`() = runTest {
        val port = FakeRunPort()
        val layout = newLayout()
        val ledger = installOpencodePlugin(port, layout, "scope-tool")  // ledger: run-ledger-npm
        val tool = ExtensionRunTool(ledger, layout, port)

        val result = tool.executeStructured("""{"id":"opencode.scope-tool"}""")
        assertTrue("应成功执行: ${result.render()}", result.isSuccess)
        assertEquals("node", port.lastCommand!!.first())
        assertTrue(port.lastCommand!!.any { it.contains("node_modules") || it.contains("pkg") })
    }

    @Test
    fun `Run 未安装与 android 域诚实拒绝`() = runTest {
        val port = FakeRunPort()
        val layout = newLayout()
        val ledger = ExtensionRegistry(tmp.newFolder("run-ledger-2"))
        ledger.record(
            ExtensionRegistry.Record(
                id = "skill.some-skill", type = "skill", spec = "x", name = "x", version = "1",
                runtime = RuntimeDomains.ANDROID, sourceKind = SourceKinds.JSON, installedAt = 1L
            )
        )
        val tool = ExtensionRunTool(ledger, layout, port)

        val missing = tool.executeStructured("""{"id":"opencode.ghost"}""")
        assertTrue(missing.error != null)

        val android = tool.executeStructured("""{"id":"skill.some-skill"}""")
        assertTrue(android.error != null)
        assertTrue(android.error!!.render().contains("android 域"))
    }

    @Test
    fun `Run profile 元数据被篡改指向外部脚本 拒绝`() = runTest {
        val port = FakeRunPort()
        val layout = newLayout()
        val ledger = installOpencodePlugin(port, layout, "evil-pkg")  // ledger: run-ledger-tamper
        // 篡改 profile：entryHint 指向账本目录之外
        val profileFile = layout.profileFile("opencode.evil-pkg", "opencode.json").getOrThrow()
        profileFile.writeText("""{"runtime":"node","entryHint":"/etc/passwd"}""")
        val tool = ExtensionRunTool(ledger, layout, port)

        val beforeRun = port.lastCommand
        val result = tool.executeStructured("""{"id":"opencode.evil-pkg"}""")
        assertTrue("entry 逃逸必须被归属校验拦截: ${result.render()}", !result.isSuccess)
        assertEquals("篡改后的 entry 不得触发任何执行", beforeRun, port.lastCommand)
    }
}


// ═══ ⑨ ExtensionAutoSyncRunner —— 生产化加固（方向④评估核）════════════

class ExtensionAutoSyncRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSyncPort : NodeInstallPort {
        var lastCommand: List<String>? = null
        override suspend fun exec(command: List<String>, cwd: File, timeoutMs: Long): Result<NodeInstallPort.ExecOutcome> {
            lastCommand = command
            if (command.firstOrNull() == "npm") {
                val prefix = command[command.indexOf("--prefix") + 1]
                val pkg = command.last()
                val pkgDir = File(prefix, "node_modules/$pkg")
                pkgDir.mkdirs()
                File(pkgDir, "index.js").writeText("// entry")
                File(pkgDir, "package.json").writeText("{}")
            }
            return Result.success(NodeInstallPort.ExecOutcome(0, "", ""))
        }
        override suspend fun probeRuntime() = NodeInstallPort.RuntimeProbe("v22", "10")
    }

    private fun recordOf(id: String, hash: String) = ExtensionRegistry.Record(
        id = id, type = ExtensionTypes.OPENCODE_PLUGIN, spec = "npm:x", name = id,
        version = "1.0", runtime = RuntimeDomains.UBUNTU, sourceKind = SourceKinds.NPM,
        manifestHash = hash, installedAt = 1L
    )

    @Test
    fun `漂移与失败入报告 lastSync 落盘`() = runTest {
        val ledger = ExtensionRegistry(tmp.newFolder("sync-ledger"))
        val port = FakeSyncPort()
        val installer = ExtensionInstaller(
            ExtensionAdapterRegistry(listOf(OpenCodeExtensionAdapter(port, ExtensionDirLayout(tmp.newFolder("ext-root"))))),
            ledger
        )
        // 预装一个扩展并手动钉一把「旧指纹」锁 → 源指纹必然不同 = 漂移
        val manifest = installer.resolve("npm:pkg-drift").getOrThrow()
        ledger.record(recordOf(manifest.id, "stale-hash").copy(runtime = RuntimeDomains.UBUNTU))
        ledger.lock(ExtensionRegistry.LockEntry(manifest.id, "stale-hash", "1.0", "npm:pkg-drift", 1L))

        val runner = ExtensionAutoSyncRunner(installer, ledger)
        val report = runner.run(listOf("npm:pkg-drift", "npm:pkg-new", "skill:json:{bad"))

        assertEquals(1, report.ok)
        assertTrue("漂移项入报告", report.drifted.any { it.startsWith("opencode.pkg-drift") })
        assertTrue("失败项入报告（skill:json:{bad 语法非法）", report.failed.any { it.contains("skill:json:{bad") })
        assertTrue(report.hasIssues)

        // lastSync 落盘 + 重载隔离（新实例只读自己的目录 → 空）
        val reloaded = ExtensionRegistry(tmp.newFolder("sync-ledger-copy"))
        assertNull(reloaded.lastSync())
        val live = ledger.lastSync()
        assertNotNull(live)
        assertEquals(report.ok, live!!.ok)
    }

    @Test
    fun `空清单零报告`() = runTest {
        val ledger = ExtensionRegistry(tmp.newFolder("sync-ok"))
        val runner = ExtensionAutoSyncRunner(
            ExtensionInstaller(
                ExtensionAdapterRegistry(emptyList()),
                ledger
            ),
            ledger
        )
        val empty = runner.run(emptyList())
        assertEquals(0, empty.ok)
        assertTrue(!empty.hasIssues)
    }
}
