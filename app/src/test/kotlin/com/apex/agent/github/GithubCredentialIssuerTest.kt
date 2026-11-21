package com.apex.agent.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GithubCredentialIssuer 契约测试（Git 审计 P0-1）：
 * askpass 脚本内容安全（token 嵌入、github.com 提示校验）、dispose 即删、
 * 非常规 token fail-closed、超龄脚本清扫。
 */
class GithubCredentialIssuerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `签发的脚本只应答 githubcom 提示`() {
        val rootfs = tmp.newFolder("rootfs")
        GithubCredentialIssuer.issue(rootfs, "ghp_abc123") ?: error("应签发成功")
        val content = tmp.root.resolve("rootfs/tmp").listFiles()!!.single().readText()
        assertTrue(content.contains("Username*github.com*"))
        assertTrue(content.contains("Password*github.com*"))
        assertTrue(content.contains("'ghp_abc123'"))
        // 非 github.com 的提示必须退出非零（凭据只流向 github.com）
        assertTrue(content.contains("*) exit 1 ;;"))
    }

    @Test
    fun `guest 路径指向 tmp 脚本`() {
        val rootfs = tmp.newFolder("rootfs")
        val issued = GithubCredentialIssuer.issue(rootfs, "ghp_abc123") ?: error("应签发成功")
        assertTrue(issued.guestAskPassPath.startsWith("/tmp/guru-askpass-"))
        assertTrue(issued.guestAskPassPath.endsWith(".sh"))
    }

    @Test
    fun `dispose 删除脚本`() {
        val rootfs = tmp.newFolder("rootfs")
        val issued = GithubCredentialIssuer.issue(rootfs, "ghp_abc123")!!
        val hostFile = tmp.root.resolve("rootfs/tmp").listFiles()!!.single()
        assertTrue(hostFile.exists())
        issued.dispose()
        assertFalse(hostFile.exists())
    }

    @Test
    fun `非常规 token fail-closed`() {
        val rootfs = tmp.newFolder("rootfs")
        // 单引号 / 换行 / $ 都无法安全嵌入脚本字面量——拒绝签发而非降级
        assertNull(GithubCredentialIssuer.issue(rootfs, "to'ken"))
        assertNull(GithubCredentialIssuer.issue(rootfs, "to\nken"))
        assertNull(GithubCredentialIssuer.issue(rootfs, "to\$ken"))
        assertNull(GithubCredentialIssuer.issue(rootfs, ""))
        assertNull(GithubCredentialIssuer.issue(rootfs, null))
        assertEquals(0, tmp.root.resolve("rootfs/tmp").listFiles()?.size ?: 0)
    }

    @Test
    fun `未连接返回 null`() {
        assertNull(GithubCredentialIssuer.issue(tmp.newFolder("rootfs2"), null))
    }

    @Test
    fun `超龄残留脚本被清扫`() {
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = rootfs.resolve("tmp").apply { mkdirs() }
        // 伪造一个 31 分钟前的残留脚本
        val stale = tmpDir.resolve("guru-askpass-${System.currentTimeMillis() - 31 * 60 * 1000}-old.sh")
        stale.writeText("#!/bin/sh\n")
        GithubCredentialIssuer.issue(rootfs, "ghp_abc123") ?: error("应签发成功")
        assertFalse("超龄脚本应被清扫", stale.exists())
    }

    @Test
    fun `新鲜脚本不被清扫误删`() {
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = rootfs.resolve("tmp").apply { mkdirs() }
        // 1 分钟前的脚本(可能仍在执行中——不删)
        val fresh = tmpDir.resolve("guru-askpass-${System.currentTimeMillis() - 60_000}-live.sh")
        fresh.writeText("#!/bin/sh\n")
        assertNotNull(GithubCredentialIssuer.issue(rootfs, "ghp_abc123"))
        assertTrue(fresh.exists())
    }
}
