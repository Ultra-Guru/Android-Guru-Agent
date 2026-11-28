package com.apex.agent.platform.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #843 契约锁：executeWithTimeout 子进程退出后的排空必须有界。
 *
 * 事故形态：孙进程（setsid/nohup 守护）继承 stdout 管道写端 —— 子进程正常
 * 退出 0，但 readText() 永远等不到 EOF，旧实现裸 Future.get() 把
 * executeShell 永久钉死在非挂起工具线程上。两层验证：
 *
 * 1. **单元层**（BoundedDrain 纯 JVM 契约）：手写 Future 假件注入
 *    （已完成 / 永不完成），锁共享截止线、部分输出保留、标记形态；
 * 2. **端到端层**（真实进程复现）：子进程打印后退出、孙进程 sleep 持有
 *    管道写端 —— executeShell 必须在有界宽限内返回部分输出 + 如实标注
 *    （旧实现在此场景下要等孙进程退出才返回，30s 假孙进程下必超时失败）。
 *
 * 纪律：无协程 → 不用 runTest；等待用 CountDownLatch（真实阻塞语义），
 * 不线程睡眠轮询（CI 棘轮纪律，字面词同样计数）。
 */
class BoundedDrainTest {

    // ── 手写 Future 假件（repo 纪律：不用 mock 框架）──────────────────

    /** 已完成的采集 Future：get 立即返回固定值。 */
    private class CompletedFuture(private val value: String) : Future<String> {
        override fun isDone() = true
        override fun get() = value
        override fun get(timeout: Long, unit: TimeUnit) = value
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
    }

    /** 永不完成的采集 Future：模拟孙进程持有管道写端（readText 等不到
     *  EOF）—— 阻塞满超时量后抛 TimeoutException，与 FutureTask.get(timeout)
     *  同款语义。 */
    private class PendingPipeFuture : Future<String> {
        override fun isDone() = false
        override fun get(): String = get(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        override fun get(timeout: Long, unit: TimeUnit): String {
            if (CountDownLatch(1).await(timeout, unit)) error("unreachable: latch never opens")
            throw TimeoutException("grandchild holds pipe write-end")
        }
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
    }

    // ── 单元层：BoundedDrain 契约 ──────────────────────────────────────

    @Test
    fun `both drains complete within budget - full output, no timeout`() {
        val outcome = BoundedDrain.awaitDrain(
            CompletedFuture("stdout-843"), CompletedFuture("stderr-843"), 1_000
        )
        assertEquals("stdout-843", outcome.stdout)
        assertEquals("stderr-843", outcome.stderr)
        assertFalse(outcome.timedOut)
    }

    @Test
    fun `hung pipe drains are bounded - timedOut within grace budget`() {
        val startedAt = System.currentTimeMillis()
        val outcome = BoundedDrain.awaitDrain(PendingPipeFuture(), PendingPipeFuture(), 300)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("awaitDrain must flag timedOut", outcome.timedOut)
        // 有界性：阻塞语义下应等满预算（毫秒粒度余量 50ms），但绝不超过
        // 预算 + 调度余量 —— 裸 get() 场景下这里永远不会返回。
        assertTrue("should have waited ~the full budget, took ${elapsed}ms", elapsed >= 250)
        assertTrue("must be bounded, took ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `completed side survives when the other side hangs`() {
        val outcome = BoundedDrain.awaitDrain(
            CompletedFuture("partial-stdout"), PendingPipeFuture(), 200
        )
        // 已完成一侧的部分输出必须原样保留（不因另一侧挂起而丢弃）。
        assertEquals("partial-stdout", outcome.stdout)
        assertEquals("", outcome.stderr)
        assertTrue(outcome.timedOut)
    }

    @Test
    fun `shared deadline - second drain only gets the leftover budget`() {
        // stdout 挂满全预算后，stderr 只剩 ~0 剩余量 → 立即超时（不必再等一整轮）。
        val startedAt = System.currentTimeMillis()
        val outcome = BoundedDrain.awaitDrain(PendingPipeFuture(), CompletedFuture("late"), 250)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(outcome.timedOut)
        assertEquals("late", outcome.stderr)
        // stderr 的 get 即刻返回（已完成），总耗时 ≈ stdout 预算，无二次全额等待。
        assertTrue("total should approximate a single budget, took ${elapsed}ms", elapsed < 1_500)
    }

    @Test
    fun `partial marker joins non-empty output on its own line`() {
        assertEquals(
            "base\n" + BoundedDrain.PARTIAL_MARKER,
            BoundedDrain.withPartialMarker("base")
        )
    }

    @Test
    fun `partial marker stands alone for empty output`() {
        assertEquals(BoundedDrain.PARTIAL_MARKER, BoundedDrain.withPartialMarker(""))
    }

    // ── 端到端层：真实进程复现（sh 后台孙进程持有管道写端）────────────

    @Test
    fun `executeShell stays bounded when a grandchild may hold the stdout pipe`() {
        // 子进程打印后立即退出 0；孙进程 sleep 30 继承 stdout 管道写端。
        // 真实 Linux 内核语义下 readText 永远等不到 EOF —— 旧实现裸 get()
        // 要等孙进程退出（~30s）才返回，下方 elapsed 上界（< 5s）即锚定
        // 该差异；个别运行时（如本沙箱）在直接子进程退出即投递 EOF，则
        // 退化为普通快速路径 —— 两种环境都必须有界返回，发生截断时部分
        // 输出 + 标记必须共存。
        val startedAt = System.currentTimeMillis()
        val result = PrivilegeDetector.executeShell(
            "echo 843-partial-probe; sleep 30 &",
            timeoutMs = 1_200
        )
        val elapsed = System.currentTimeMillis() - startedAt

        // 部分输出必须保留（通道无关：root/shell 均走 executeWithTimeout）。
        assertTrue("output: ${result.output}", result.output.contains("843-partial-probe"))
        if (result.output.contains(BoundedDrain.PARTIAL_MARKER)) {
            // 截断发生时：子进程本身正常退出（exit 0），标记与部分输出共存。
            assertTrue("child should have exited 0, exit=${result.exitCode}", result.success)
        }
        assertTrue(
            "must return within original budget + grace, took ${elapsed}ms",
            elapsed < 5_000
        )
    }
}
