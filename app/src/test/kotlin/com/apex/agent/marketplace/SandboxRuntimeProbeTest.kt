package com.apex.agent.marketplace

import com.apex.agent.core.tools.mcp.McpSandboxRuntimeProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SandboxRuntimeProbe —— 沙箱运行时存在性探测的单测。
 *
 * 用 TemporaryFolder 伪造 RootfsInstallLayout 的目录形态（current 标记 →
 * versions/<id>），锁定四组不变量：
 * 1. rootfs 未安装 → ROOTFS_MISSING；原子激活布局与「直接传本体」两种
 *    接线形态都能解析；
 * 2. node/uv/python 三家族按 guest PATH 目录探测，装了即 READY；
 * 3. 持久化 home 的 .local/bin 兜底（pip --user 形态的 uvx）；
 * 4. 未知命令不越权拦截（READY），安装指引按家族给出。
 */
class SandboxRuntimeProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 伪造一个激活布局的 rootfs：<base>/current → versions/v1，v1 内有 bin/。 */
    private fun fakeRootfs(base: File, vararg binaries: Pair<String, String>): File {
        val version = File(base, "versions/v1")
        File(version, "usr/bin").mkdirs()
        File(version, "bin").mkdirs()
        File(base, "current").writeText("v1")
        for ((dir, name) in binaries) {
            File(version, "$dir/$name").writeText("#!/bin/sh\n")
        }
        return version
    }

    @Test
    fun `missing rootfs reports rootfs missing`() {
        val probe = SandboxRuntimeProbe(
            rootfsBaseDir = tmp.newFolder("rootfs"),
            persistentHomeDir = null
        )
        assertEquals(Verdict.ROOTFS_MISSING, probe.probe("npx"))
        assertEquals(Verdict.ROOTFS_MISSING, probe.probe("uvx"))
    }

    @Test
    fun `rootfs without node reports runtime missing`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base) // 有 rootfs、无 nodejs
        val probe = SandboxRuntimeProbe(base, null)
        assertEquals(Verdict.RUNTIME_MISSING, probe.probe("npx"))
        assertEquals(Verdict.RUNTIME_MISSING, probe.probe("npm"))
        assertEquals(Verdict.RUNTIME_MISSING, probe.probe("node"))
    }

    @Test
    fun `node installed via apt layout reports ready`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base, "usr/bin" to "npx", "usr/bin" to "node")
        val probe = SandboxRuntimeProbe(base, null)
        assertEquals(Verdict.READY, probe.probe("npx"))
        assertEquals(Verdict.READY, probe.probe("node"))
    }

    @Test
    fun `node installed in usr-local-bin also reports ready`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base, "usr/local/bin" to "npx")
        val probe = SandboxRuntimeProbe(base, null)
        assertEquals(Verdict.READY, probe.probe("npx"))
    }

    @Test
    fun `uvx missing but uv present counts as ready`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base, "usr/local/bin" to "uv")
        val probe = SandboxRuntimeProbe(base, null)
        assertEquals(Verdict.READY, probe.probe("uvx"))
    }

    @Test
    fun `uvx found in persistent home local bin`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base) // 沙箱本体没有 uv
        val home = tmp.newFolder("home")
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/uvx").writeText("#!/bin/sh\n")
        val probe = SandboxRuntimeProbe(base, home)
        assertEquals(Verdict.READY, probe.probe("uvx"))
    }

    @Test
    fun `python3 present in stock rootfs reports ready`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base, "usr/bin" to "python3")
        val probe = SandboxRuntimeProbe(base, null)
        assertEquals(Verdict.READY, probe.probe("python"))
        assertEquals(Verdict.READY, probe.probe("python3"))
    }

    @Test
    fun `unknown command is never blocked`() {
        val base = tmp.newFolder("rootfs")
        fakeRootfs(base)
        val probe = SandboxRuntimeProbe(base, null)
        // java/docker/自定义二进制：不越权拦截，交给真实启动路径报真因
        assertEquals(Verdict.READY, probe.probe("java"))
        assertEquals(Verdict.READY, probe.probe("docker"))
        assertEquals(Verdict.READY, probe.probe("/opt/custom/agent"))
        assertEquals(Verdict.READY, probe.probe(""))
    }

    @Test
    fun `direct rootfs body wiring is recognized`() {
        // 「直接传入 rootfs 本体」的兼容形态（无 current 标记，目录内有 bin/）
        val body = tmp.newFolder("rootfs-body")
        File(body, "bin").mkdirs()
        File(body, "usr/bin").mkdirs()
        File(body, "usr/bin/npx").writeText("#!/bin/sh\n")
        val probe = SandboxRuntimeProbe(body, null)
        assertEquals(Verdict.READY, probe.probe("npx"))
    }

    @Test
    fun `broken current marker degrades to rootfs missing`() {
        // 标记指向的版本目录缺失（升级中断）—— 预检按未就绪处理，
        // 详细损坏文案留给真实启动路径的 launcher。
        val base = tmp.newFolder("rootfs")
        File(base, "versions").mkdirs()
        File(base, "current").writeText("gone")
        val probe = SandboxRuntimeProbe(base, null)
        assertEquals(Verdict.ROOTFS_MISSING, probe.probe("npx"))
    }

    @Test
    fun `install hint matches command family`() {
        val probe = SandboxRuntimeProbe(tmp.newFolder(), null)
        assertEquals("apt install -y nodejs npm", probe.installHint("npx"))
        assertEquals("pip install uv", probe.installHint("uvx"))
        assertEquals("apt install -y python3", probe.installHint("python3"))
        assertNull(probe.installHint("java"))
        assertNull(probe.installHint(""))
    }
}
