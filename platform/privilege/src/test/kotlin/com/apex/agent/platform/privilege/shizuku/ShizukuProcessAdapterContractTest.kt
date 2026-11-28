package com.apex.agent.platform.privilege.shizuku

import moe.shizuku.server.IRemoteProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ S08 ShizukuProcessAdapter 契约测试（补充既有 TimeoutWaitTest）═══
 *
 * 锁死通道边界语义（#381 的完整契约面）：
 * - `exitValue()` 未退出抛 IllegalStateException（服务端语义）—— 上层捕获
 *   后必须能区分「未退出」与真异常；
 * - `destroy()` 幂等 best-effort（重复调用不抛）；
 * - `waitFor(timeout)` 超时后可重入（再次等待仍按剩余窗口工作）；
 * - 流 getter 之外的方法组合在「退出后」语义正确。
 */
class ShizukuProcessAdapterContractTest {

    /** 与服务端语义对齐的可脚本化 fake：退出码经 latch 翻转。 */
    private class ScriptedRemoteProcess : IRemoteProcess {
        @Volatile var exited: Int? = null
        var destroyCalls: Int = 0

        override fun waitFor(): Int = exited ?: throw UnsupportedOperationException()

        override fun exitValue(): Int =
            exited ?: throw IllegalStateException("process hasn't exited")

        override fun destroy() {
            destroyCalls++
        }

        override fun getOutputStream(): android.os.ParcelFileDescriptor =
            throw UnsupportedOperationException("not used in this test")

        override fun getInputStream(): android.os.ParcelFileDescriptor =
            throw UnsupportedOperationException("not used in this test")

        override fun getErrorStream(): android.os.ParcelFileDescriptor =
            throw UnsupportedOperationException("not used in this test")

        override fun asBinder(): android.os.IBinder =
            throw UnsupportedOperationException("not used in this test")

        override fun alive(): Boolean = exited == null

        override fun waitForTimeout(timeout: Long, unit: String): Boolean =
            throw UnsupportedOperationException("not used in this test")
    }

    @Test
    fun `exitValue 未退出时抛 IllegalStateException 而非 IllegalThreadStateException`() {
        val adapter = ShizukuProcessAdapter(ScriptedRemoteProcess())
        val e = runCatching { adapter.exitValue() }.exceptionOrNull()
        assertTrue(
            "Shizuku 服务端未退出抛的是 IllegalStateException（#381 根因），实际：$e",
            e is IllegalStateException
        )
    }

    @Test
    fun `exitValue 退出后正常返回退出码`() {
        val remote = ScriptedRemoteProcess().apply { exited = 42 }
        assertEquals(42, ShizukuProcessAdapter(remote).exitValue())
    }

    @Test
    fun `destroy 幂等且不抛异常`() {
        val remote = ScriptedRemoteProcess()
        val adapter = ShizukuProcessAdapter(remote)
        adapter.destroy()
        adapter.destroy()
        adapter.destroy()
        assertEquals(3, remote.destroyCalls)
    }

    @Test
    fun `waitFor 超时后可重入 且退出后返回 true`() {
        val remote = ScriptedRemoteProcess()
        val adapter = ShizukuProcessAdapter(remote)
        // 第一次等待：未退出 → 超时返回 false（重入前置条件）。
        assertFalse(adapter.waitFor(50, java.util.concurrent.TimeUnit.MILLISECONDS))
        // 远端退出 → 重入等待立刻成功。
        remote.exited = 0
        assertTrue(adapter.waitFor(5_000, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertFalse(adapter.isAlive)
    }

    @Test
    fun `isAlive 与 alive 语义在退出翻转点一致`() {
        val remote = ScriptedRemoteProcess()
        val adapter = ShizukuProcessAdapter(remote)
        assertTrue(adapter.isAlive)
        assertTrue(remote.alive())
        remote.exited = 1
        assertFalse(adapter.isAlive)
        assertFalse(remote.alive())
    }
}
