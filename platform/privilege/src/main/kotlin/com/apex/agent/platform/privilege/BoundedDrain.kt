package com.apex.agent.platform.privilege

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 有界排空（#843）：子进程正常退出后，等待 stdout/stderr 采集 Future 的
 * 共享截止线。
 *
 * 背景：孙进程（setsid/nohup 守护）继承 stdout 管道写端时，readText() 永远
 * 等不到 EOF —— 旧实现裸 Future.get() 把 executeShell 永久钉死在非挂起的
 * 工具线程上（没有协程取消兜底）。与 platform:terminal ExecEngine 的
 * postKillDrainGraceMs 同一纪律：有界宽限 + 不完整输出如实标注，绝不假装完整。
 *
 * 语义（纯 JVM；Future 可用手写假件注入，见 BoundedDrainTest）：
 * - 两个 Future 消费同一条截止线：先到者花掉预算，后到者只剩剩余量；
 * - 任一侧超时即 timedOut=true，但已完成一侧的部分输出原样保留；
 * - 超时标记由调用方按既有 stdout/stderr 合并契约拼在输出末尾
 *   （PrivilegeDetector.executeWithTimeout，合并格式不变）。
 */
internal object BoundedDrain {

    /** 排空超时标记：输出不完整时如实告知（模型可见，不假装完整）。 */
    const val PARTIAL_MARKER = "[output partial: drain timeout — grandchild may hold pipe]"

    /**
     * 有界等待两个采集 Future 完成。
     *
     * @param timeoutMs 共享预算（毫秒）。预算内双双完成 → timedOut=false；
     *   任一侧未完成 → timedOut=true 且保留已完成一侧的部分输出。
     */
    fun awaitDrain(stdout: Future<String>, stderr: Future<String>, timeoutMs: Long): Outcome {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var stdoutText = ""
        var stderrText = ""
        var timedOut = false
        try {
            stdoutText = stdout.get(remainingMs(deadlineNanos), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            timedOut = true
        }
        try {
            stderrText = stderr.get(remainingMs(deadlineNanos), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            timedOut = true
        }
        return Outcome(stdoutText, stderrText, timedOut)
    }

    /**
     * 部分输出标记拼接：非空基线后起新行（独立一行，模型可辨）；空基线
     * 直接给标记本身（不制造孤立换行）。
     */
    fun withPartialMarker(base: String): String =
        if (base.isEmpty()) PARTIAL_MARKER else base + "\n" + PARTIAL_MARKER

    private fun remainingMs(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    /** 排空结果：两侧文本 + 是否有任一侧在预算内未完成。 */
    data class Outcome(val stdout: String, val stderr: String, val timedOut: Boolean)
}
