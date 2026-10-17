package com.apex.agent.platform.privilege.shizuku

import moe.shizuku.server.IRemoteProcess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #381 P1 回归锁：Shizuku 命令通道 100% 失败的根因是
 * [ShizukuProcessAdapter] 未重载 `waitFor(long, TimeUnit)` / `isAlive()` ——
 * `java.lang.Process` 默认实现轮询 `exitValue()` 只捕
 * `IllegalThreadStateException`，而 Shizuku 服务端在进程未退出时抛的是
 * **`IllegalStateException("process hasn't exited")`**：异常穿透默认实现
 * → 首次轮询即上抛 → `echo OK; id` 都 82ms 失败（全部走 Shizuku 通道的
 * 工具 100% 瞬间失败，表象 "process hasn't exited"）。
 *
 * 本测试用与 Shizuku 服务端同语义的 fake [IRemoteProcess]（未退出时
 * exitValue 抛 IllegalStateException；退出后正常返回码）锁死：
 * 1. 未退出时 `waitFor(timeout)` 不抛异常、按超时返回 false（旧实现瞬间
 *    抛 ISE → ShizukuCommandExecutor catch-all → "Shizuku error: process
 *    hasn't exited"）；
 * 2. 退出后 `waitFor` 返回 true；
 * 3. `isAlive()` 两种状态都正确（executor 的 finally 块依赖它，默认实现
 *    同样会抛 ISE 吞掉真实结果）。
 *
 * 纯 JVM：不触碰 ParcelFileDescriptor（适配器的流 getter 是惰性的，
 * 本测试只走 waitFor/exitValue 路径，零 Android 运行时依赖）。
 */
class ShizukuProcessAdapterTimeoutWaitTest {

    /**
     * 与 Shizuku 服务端语义对齐的假远端进程：
     * - 未退出：exitValue() 抛 IllegalStateException("process hasn't exited")
     * - 退出：正常返回退出码
     * - 退出时机由 [exitAfter] 挂钩控制（跨线程翻转，模拟远端进程结束）
     */
    private class FakeRemoteProcess(exitAfter: CountDownLatch) : IRemoteProcess {
        private val exited = AtomicInteger(0)
        private val thrown: AtomicReference<IllegalStateException> = AtomicReference()
        private val exitLatch = exitAfter

        init {
            // 模拟远端进程：退出信号到达后置退出码
            Thread {
                exitLatch.await()
                exited.set(42)
            }.start()
        }

        override fun waitFor(): Int {
            while (exited.get() == 0) Thread.sleep(5)
            return exited.get()
        }

        override fun exitValue(): Int {
            val code = exited.get()
            if (code == 0) {
                // 与 Shizuku 服务端一致的"未退出"异常（#381 根因字符串）
                thrown.compareAndSet(
                    null,
                    IllegalStateException("process hasn't exited")
                )
                throw thrown.get()
            }
            return code
        }

        override fun destroy() {}

        // 流通道惰性：本测试不触碰（真实 Android ParcelFileDescriptor）
        override val outputStream: android.os.ParcelFileDescriptor
            get() = throw UnsupportedOperationException("not used in this test")
        override val inputStream: android.os.ParcelFileDescriptor
            get() = throw UnsupportedOperationException("not used in this test")
        override val errorStream: android.os.ParcelFileDescriptor
            get() = throw UnsupportedOperationException("not used in this test")
    }

    @Test
    fun `未退出时 waitFor 带超时不抛 IllegalStateException 而是等满超时`() {
        // 进程永不退出（latch 永不 countDown）
        val adapter = ShizukuProcessAdapter(FakeRemoteProcess(CountDownLatch(1)))

        val startAt = System.nanoTime()
        val completed = adapter.waitFor(300, TimeUnit.MILLISECONDS)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startAt)

        // ★ 核心断言：旧实现（默认 Process.waitFor(long,TimeUnit)）在这里
        //   瞬间抛 IllegalStateException —— 即 issue 里 82ms 失败的根因。
        //   修复后必须等到超时并返回 false。
        assertFalse("未退出的进程应按超时返回 false", completed)
        assertTrue(
            "应真实等待到超时（实测 ${elapsedMs}ms，瞬间返回说明异常穿透被吞）",
            elapsedMs >= 250
        )
    }

    @Test
    fun `远端退出后 waitFor 带超时返回 true`() {
        val exited = CountDownLatch(1)
        val adapter = ShizukuProcessAdapter(FakeRemoteProcess(exited))
        // 另一线程在 100ms 后让"远端进程"退出
        Thread {
            Thread.sleep(100)
            exited.countDown()
        }.start()

        val completed = adapter.waitFor(5_000, TimeUnit.MILLISECONDS)
        assertTrue("进程退出后 waitFor 必须返回 true", completed)
    }

    @Test
    fun `零超时时未退出不抛异常直接返回 false`() {
        val adapter = ShizukuProcessAdapter(FakeRemoteProcess(CountDownLatch(1)))

        val completed = adapter.waitFor(0, TimeUnit.MILLISECONDS)

        assertFalse("零超时 = 单次快速探测，返回 false 而非抛异常", completed)
    }

    @Test
    fun `isAlive 在未退出时为 true 退出后为 false 不抛异常`() {
        val exited = CountDownLatch(1)
        val adapter = ShizukuProcessAdapter(FakeRemoteProcess(exited))

        // 未退出 —— 旧实现的默认 isAlive() 在这里直接抛 ISE（finally 块
        // 的 `it.isAlive` 会吞掉命令的真实结果）
        assertTrue("未退出时 isAlive 必须为 true", adapter.isAlive)

        exited.countDown()
        // 等待 fake 内部线程把退出码写进去
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (!adapter.isAlive) break
            Thread.sleep(10)
        }
        assertFalse("退出后 isAlive 必须为 false", adapter.isAlive)
    }
}
