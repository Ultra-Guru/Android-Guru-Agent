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
 * [SearxngSettingsWriter] 单测（Spec §7 配置规格逐行断言）。
 *
 * 覆盖：keep_only 引擎过滤 / json format 必含 / secret_key 复用 / 端口与
 * bind_address / 空名单回落默认 / ensureConfig 原子幂等写入。
 */
class SearxngSettingsWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 进程内意图端口。字段名刻意避开接口方法名（JVM 平台声明冲突 ——
     * private var engines 生成 setEngines(Set) 与 override 撞签名）。
     */
    private class FakeIntent(
        private var enginesState: Set<String> = SEARXNG_DEFAULT_ENGINES,
        private val secretValue: String = "a".repeat(64)
    ) : SearxngIntentPort {
        var enabledState = false
        override fun isEnabled(): Boolean = enabledState
        override fun setEnabled(value: Boolean) { enabledState = value }
        override fun engines(): Set<String> = enginesState
        override fun setEngines(engines: Set<String>) { this.enginesState = engines }
        override fun secretKey(): String = secretValue
    }

    /** rootfs 指向临时目录的命令端口（Writer 只用 rootfsDir —— exec 不可达）。 */
    private class FakeCommands(private val rootfs: File) : SearxngCommandPort {
        override suspend fun exec(
            command: String,
            timeoutMs: Long,
            env: Map<String, String>
        ): Result<SearxngExecResult> = Result.failure(UnsupportedOperationException())
        override suspend fun rootfsDir(): File = rootfs
    }

    private fun writer(intent: FakeIntent, rootfs: File): SearxngSettingsWriter =
        SearxngSettingsWriter(FakeCommands(rootfs), intent)

    @Test
    fun `render contains spec-mandated sections`() {
        val intent = FakeIntent()
        val yml = writer(intent, tmp.newFolder()).render()

        // Spec §7 表逐项
        assertTrue("instance_name", "instance_name: guru-local" in yml)
        assertTrue("debug false", "debug: false" in yml)
        assertTrue("bind 127.0.0.1（禁止 0.0.0.0）", "bind_address: ${SearxngContract.BIND_ADDRESS}" in yml)
        assertTrue("port 8888", "port: 8888" in yml)
        assertTrue("limiter false", "limiter: false" in yml)
        assertTrue("public_instance false", "public_instance: false" in yml)
        assertTrue("safe_search 0", "safe_search: 0" in yml)
        // JSON format 必含（否则 JSON API 403 —— Spec §7 表注）
        assertTrue("html format", "- html" in yml)
        assertTrue("json format", "- json" in yml)
        // secret_key 注入
        assertTrue("secret_key", "secret_key: ${intent.secretKey()}" in yml)
    }

    @Test
    fun `keep_only contains selected engines and only those`() {
        val intent = FakeIntent(enginesState = setOf("wikipedia", "arxiv"))
        val yml = writer(intent, tmp.newFolder()).render()
        assertTrue("- wikipedia" in yml)
        assertTrue("- arxiv" in yml)
        assertFalse("google should be filtered out", "- google" in yml)
        // engines 段显式 enabled
        assertTrue("name: wikipedia" in yml)
    }

    @Test
    fun `empty engine selection falls back to defaults`() {
        val intent = FakeIntent(enginesState = emptySet())
        val current = writer(intent, tmp.newFolder()).currentEngines()
        assertEquals(SEARXNG_DEFAULT_ENGINES, current)
    }

    @Test
    fun `illegal engine ids are filtered`() {
        val intent = FakeIntent(enginesState = setOf("google", "not-a-real-engine"))
        val current = writer(intent, tmp.newFolder()).currentEngines()
        assertEquals(setOf("google"), current)
    }

    @Test
    fun `ensureConfig writes file under rootfs opt searxng`() = runTest {
        val rootfs = tmp.newFolder()
        val w = writer(FakeIntent(), rootfs)
        val result = w.ensureConfig()
        assertTrue("ensureConfig succeeds: ${result.exceptionOrNull()}", result.isSuccess)
        val file = SearxngScripts.hostSettingsFile(rootfs)
        assertTrue("settings.yml exists at ${file.absolutePath}", file.isFile)
        assertTrue(file.readText().contains("keep_only"))
    }

    @Test
    fun `ensureConfig is idempotent - second call is a no-op`() = runTest {
        val rootfs = tmp.newFolder()
        val w = writer(FakeIntent(), rootfs)
        assertTrue(w.ensureConfig().isSuccess)
        val file = SearxngScripts.hostSettingsFile(rootfs)
        val first = file.readText()
        assertTrue(w.ensureConfig().isSuccess)
        assertEquals("内容一致（幂等不重写）", first, file.readText())
    }

    @Test
    fun `ensureConfig rewrites when engines change`() = runTest {
        val rootfs = tmp.newFolder()
        val intent = FakeIntent(enginesState = setOf("google"))
        val w = writer(intent, rootfs)
        assertTrue(w.ensureConfig().isSuccess)
        val before = SearxngScripts.hostSettingsFile(rootfs).readText()
        intent.setEngines(setOf("arxiv"))
        assertTrue(w.ensureConfig().isSuccess)
        val after = SearxngScripts.hostSettingsFile(rootfs).readText()
        assertFalse("引擎变更后内容必须变化", before == after)
        assertTrue("- arxiv" in after)
    }

    @Test
    fun `ensureConfig fails honestly when rootfs unavailable`() = runTest {
        val w = SearxngSettingsWriter(object : SearxngCommandPort {
            override suspend fun exec(
                command: String,
                timeoutMs: Long,
                env: Map<String, String>
            ): Result<SearxngExecResult> = Result.failure(UnsupportedOperationException())
            override suspend fun rootfsDir(): File? = null
        }, FakeIntent())
        val result = w.ensureConfig()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("ubuntu not ready") == true)
    }

}
