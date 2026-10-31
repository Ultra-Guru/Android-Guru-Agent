package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T92（D5 完成度）：PRootCapabilitySource 契约测试。
 *
 * 锁定三段语义（类 KDoc 的机器化复述）：
 *  1. 预取完成前读取 = UPSTREAM_SAFE（保守省略 —— 省略形状在任何 proot 上
 *     均合法，绝不拒启）；
 *  2. 预取完成后 = provider verify 的实测能力（与交互会话路径同源）；
 *  3. locate/verify 失败 → 保持 UPSTREAM_SAFE（能力源不新增失败面 ——
 *     后续真实 exec 由既有错误路径如实报错）。
 */
class PRootCapabilitySourceTest {

    private class FakeProvider(
        private val info: PRootBinaryInfo?,
        private val locateFails: Boolean = false
    ) : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> =
            if (locateFails) Result.failure(RuntimeException("no binary"))
            else Result.success(AbsolutePath("/fake/libproot.so"))

        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> =
            info?.let { Result.success(it) }
                ?: Result.failure(RuntimeException("verify failed"))
    }

    private fun infoOf(capabilities: PRootArgvCapabilities) = PRootBinaryInfo(
        path = AbsolutePath("/fake/libproot.so"),
        version = null,
        architecture = CpuArchitecture.ARM64,
        executable = true,
        capabilities = capabilities
    )

    @Test
    fun `prefetch window reads conservative upstream-safe baseline`() = runTest {
        // 预取挂起在 StandardTestDispatcher 上（不自动跑）—— 模拟预取进行中
        val source = PRootCapabilitySource(
            provider = FakeProvider(infoOf(PRootArgvCapabilities.TERMUX_BUNDLED)),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        assertEquals(
            "预取未完成 → 保守省略基线（省略在任何 proot 上均合法）",
            PRootArgvCapabilities.UPSTREAM_SAFE,
            source()
        )
        advanceUntilIdle()
        assertEquals(
            "预取完成后 → 实测能力（与交互会话路径同源）",
            PRootArgvCapabilities.TERMUX_BUNDLED,
            source()
        )
    }

    @Test
    fun `verify failure keeps upstream-safe baseline (no new failure surface)`() = runTest {
        val source = PRootCapabilitySource(
            provider = FakeProvider(info = null), // verify 失败
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        advanceUntilIdle()
        assertEquals(PRootArgvCapabilities.UPSTREAM_SAFE, source())
    }

    @Test
    fun `locate failure keeps upstream-safe baseline`() = runTest {
        val source = PRootCapabilitySource(
            provider = FakeProvider(infoOf(PRootArgvCapabilities.TERMUX_BUNDLED), locateFails = true),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        advanceUntilIdle()
        assertEquals(PRootArgvCapabilities.UPSTREAM_SAFE, source())
    }

    @Test
    fun `refresh updates capabilities from provider (memoized provider makes it cheap)`() = runTest {
        val source = PRootCapabilitySource(
            provider = FakeProvider(infoOf(PRootArgvCapabilities(supportsKillOnExit = true, supportsOptionSeparator = false))),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        advanceUntilIdle()
        val caps = source()
        assertTrue("Debian 5.4 混合形态穿透：kill-on-exit 支持", caps.supportsKillOnExit)
        assertTrue("Debian 5.4 混合形态穿透：separator 不支持", !caps.supportsOptionSeparator)
    }
}
