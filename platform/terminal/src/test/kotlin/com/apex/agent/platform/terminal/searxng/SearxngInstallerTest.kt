package com.apex.agent.platform.terminal.searxng

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SearxngInstaller] 单测（Spec §6 契约 + §12 失败矩阵）。
 *
 * 覆盖：isInstalled 文件判定 / 全新安装阶段管线 / 断点续装（已完成阶段跳过）/
 * 网络失败 vs 超时归因 / 卸载幂等。
 */
class SearxngInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可编程命令端口：记录命令 + 按前缀路由结果（未命中默认成功）。 */
    private class FakeCommands(private val rootfs: File) : SearxngCommandPort {
        val executed = mutableListOf<String>()
        val failures = mutableMapOf<String, SearxngExecResult>()

        override suspend fun exec(
            command: String,
            timeoutMs: Long,
            env: Map<String, String>
        ): Result<SearxngExecResult> {
            executed.add(command)
            val hit = failures.entries.firstOrNull { command.startsWith(it.key) }
            val result = hit?.value ?: SearxngExecResult(0, "", "", timedOut = false)
            return Result.success(result)
        }

        override suspend fun rootfsDir(): File = rootfs

        fun fail(prefix: String, stderr: String, exit: Int = 100) {
            failures[prefix] = SearxngExecResult(exit, "", stderr, timedOut = false)
        }

        fun failTimeout(prefix: String) {
            failures[prefix] = SearxngExecResult(-1, "", "", timedOut = true)
        }
    }

    /** 在假 rootfs 里铺「已装」文件面（venv python + 源码 requirements）。 */
    private fun markInstalled(rootfs: File) {
        SearxngScripts.hostVenvPython(rootfs).apply {
            parentFile.mkdirs(); writeText("#!/fake")
        }
        File(SearxngScripts.hostSrcDir(rootfs), "requirements.txt").apply {
            parentFile.mkdirs(); writeText("flask\n")
        }
    }

    @Test
    fun `isInstalled false on fresh rootfs`() = runTest {
        val installer = SearxngInstaller(FakeCommands(tmp.newFolder()))
        assertFalse(installer.isInstalled())
    }

    @Test
    fun `isInstalled true when venv python and source exist`() = runTest {
        val rootfs = tmp.newFolder()
        markInstalled(rootfs)
        assertTrue(SearxngInstaller(FakeCommands(rootfs)).isInstalled())
    }

    @Test
    fun `fresh install runs all stages in order`() = runTest {
        val rootfs = tmp.newFolder()
        val commands = FakeCommands(rootfs)
        // 依赖探测失败（全新 rootfs：无 git/venv）→ APT 阶段真实执行
        commands.fail("command -v git", "not found", exit = 1)
        val stages = mutableListOf<String>()

        val result = SearxngInstaller(commands).install(
            onStage = { stage, _ -> stages.add(stage.name) }
        )

        assertTrue("install success: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf("APT", "CLONE", "VENV", "PIP", "VERIFY"), stages)
        assertTrue(commands.executed.any { it.startsWith("apt-get update") })
        assertTrue(commands.executed.any { it.startsWith("git clone --depth 1") })
        assertTrue(commands.executed.any { it.startsWith("python3 -m venv") })
        assertTrue(commands.executed.any { it.contains("pip install --no-cache-dir") })
        assertTrue(commands.executed.any { it.contains("import searx") })
    }

    @Test
    fun `already installed skips to verify - fast path`() = runTest {
        val rootfs = tmp.newFolder()
        markInstalled(rootfs)
        val commands = FakeCommands(rootfs)
        val result = SearxngInstaller(commands).install()
        assertTrue(result.isSuccess)
        assertFalse(commands.executed.any { it.startsWith("apt-get") })
        assertFalse(commands.executed.any { it.startsWith("git clone") })
        assertFalse(commands.executed.any { it.contains("pip install") })
    }

    @Test
    fun `partial install resumes - skips finished stages`() = runTest {
        val rootfs = tmp.newFolder()
        // 残缺现场：源码已 clone（断点续装场景），venv 未建
        File(SearxngScripts.hostSrcDir(rootfs), "requirements.txt").apply {
            parentFile.mkdirs(); writeText("flask\n")
        }
        val commands = FakeCommands(rootfs)
        val result = SearxngInstaller(commands).install()
        assertTrue(result.isSuccess)
        assertFalse("clone must be skipped", commands.executed.any { it.startsWith("git clone") })
        assertTrue("venv must be created", commands.executed.any { it.startsWith("python3 -m venv") })
    }

    @Test
    fun `dns failure on clone classified as install network`() = runTest {
        val rootfs = tmp.newFolder()
        val commands = FakeCommands(rootfs)
        commands.fail(
            "git clone",
            "fatal: unable to access 'https://github.com/': Could not resolve host"
        )
        val result = SearxngInstaller(commands).install()
        assertTrue(result.isFailure)
        val reason = result.exceptionOrNull()?.message ?: ""
        assertTrue("reason=$reason", reason.startsWith("install network"))
    }

    @Test
    fun `pip compile failure classified as build failed`() = runTest {
        val rootfs = tmp.newFolder()
        val commands = FakeCommands(rootfs)
        commands.fail(
            "pip install",
            "error: command 'x86_64-linux-gnu-gcc' failed with exit status 1\n" +
                "  src/lxml/etree.c: build failed"
        )
        val result = SearxngInstaller(commands).install()
        assertTrue(result.isFailure)
        val reason = result.exceptionOrNull()?.message ?: ""
        assertTrue("reason=$reason", reason.startsWith("build failed"))
    }

    @Test
    fun `timed out apt classified with timeout reason`() = runTest {
        val rootfs = tmp.newFolder()
        val commands = FakeCommands(rootfs)
        // 探针失败（进入 apt 阶段）+ apt 超时
        commands.fail("command -v git", "not found", exit = 1)
        commands.failTimeout("apt-get update")
        val result = SearxngInstaller(commands).install()
        assertTrue(result.isFailure)
        val reason = result.exceptionOrNull()?.message ?: ""
        assertTrue("reason=$reason", reason.contains("timed out"))
    }

    @Test
    fun `apt satisfied skips apt stage`() = runTest {
        val rootfs = tmp.newFolder()
        val commands = FakeCommands(rootfs)
        // apt 依赖探测默认成功（FakeCommands 未命中默认 exit 0）→ apt 阶段跳过
        val result = SearxngInstaller(commands).install()
        assertTrue(result.isSuccess)
        assertFalse("apt update must be skipped when satisfied", commands.executed.any { it.startsWith("apt-get update") })
        assertTrue("clone still runs", commands.executed.any { it.startsWith("git clone") })
    }

    @Test
    fun `uninstall removes install dir and is idempotent`() = runTest {
        val rootfs = tmp.newFolder()
        markInstalled(rootfs)
        val commands = FakeCommands(rootfs)
        val installer = SearxngInstaller(commands)
        assertTrue(installer.uninstall().isSuccess)
        assertFalse(SearxngScripts.hostInstallDir(rootfs).exists())
        assertTrue("second uninstall is a no-op success", installer.uninstall().isSuccess)
    }
}
