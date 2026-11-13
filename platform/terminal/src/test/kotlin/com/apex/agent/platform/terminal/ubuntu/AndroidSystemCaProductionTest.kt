package com.apex.agent.platform.terminal.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T99 审计：生产 DI 注入 AOSP `SystemCertificateSource.cacerts-removed` 的纯 JVM 推断。
 *
 * Android 上无法在 JVM 单测内断言 `/data/misc/user/<id>` 实际存在；
 * 这里锁住两件可判定的事：
 *  1. 解析 `/proc/self/status` 的 Uid 行 → userId = uid / 100_000（AOSP 派生）；
 *  2. 用户配置目录不存在时 fail-closed（返回 null，不假装知道撤销来源）。
 */
class AndroidSystemCaProductionTest {

    @Test fun `uid row is mapped to aosp user id`() {
        assertEquals(0L, AndroidSystemCaProduction.userIdFromUid(10_000))
        assertEquals(0L, AndroidSystemCaProduction.userIdFromUid(99_999))
        assertEquals(10L, AndroidSystemCaProduction.userIdFromUid(1_050_000))
        assertEquals(null, AndroidSystemCaProduction.userIdFromUid(-1))
    }

    @Test fun `uid line of proc status is parsed`() {
        val status = "Name:\tcom.apex.agent\nUid:\t10044\t10044\t10044\t10044\n"
        val uid = AndroidSystemCaProduction.currentUid(status.lines())
        assertEquals(10_044L, uid)
        assertEquals(null, AndroidSystemCaProduction.currentUid(listOf("Name:\tshell")))
    }

    @Test fun `unknown user config dir fails closed`() {
        val missing = File(tempRoot(), "no-such-user-config")
        val dirs = AndroidSystemCaProduction.removedDirsFor(
            userConfigDir = missing,
            systemDirs = listOf(File("/apex/com.android.conscrypt/cacerts"))
        )
        assertEquals(listOf<File?>(null), dirs)
    }

    @Test fun `existing removed dir is injected per system store`() {
        val config = tempRoot()
        val removed = File(config, "cacerts-removed").apply { mkdirs() }
        assertTrue(removed.isDirectory)
        val dirs = AndroidSystemCaProduction.removedDirsFor(
            userConfigDir = config,
            systemDirs = listOf(File("/apex/com.android.conscrypt/cacerts"), File("/system/etc/security/cacerts"))
        )
        assertEquals(listOf(removed, removed), dirs)
    }

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "t99-ca-prod-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }
}
