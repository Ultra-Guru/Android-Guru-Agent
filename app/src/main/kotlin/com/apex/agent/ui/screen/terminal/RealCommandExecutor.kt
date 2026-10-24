package com.apex.agent.ui.screen.terminal

import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.protocol.ShellMarkers
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * 真实命令执行器（T96 —— 终端大重构的核心引擎件）。
 *
 * 用户在 M3 控制台提交的命令经本器在真实 PTY 会话上执行，输出为**真实
 * 输出**（与模拟告别）：runtime.run 启动 job → 轮询 observe(RAW) 把原始
 * 流逐块喂给 [RawStreamSanitizer]（剥回显/OSC633 marker/提示符尾）→
 * 干净行实时回调 → wait(ProcessExited) 给出真实退出码。
 *
 * 与 TerminalDepCenter.execAndAppend 的批式口径（跑完再取）不同，本器
 * 是流式口径 —— 长输出（apt/list）逐行上屏，与真实终端一致。
 *
 * 生产 DI（app/di/TerminalModule）已开启 shell marker 协议
 * （enableShellMarkers = true）：run() 写入的是 marker 包装行，回显剥离
 * 以 [ShellMarkers.wrapCommand] 重构同一包装行为基准 —— 确定性匹配，
 * 非启发式。marker 关闭的环境（单测桩）传 [echoWrapsCommand] = false。
 *
 * 纯协程编排（无 Android 依赖）—— RealCommandExecutorTest 以手写
 * FakeRuntime 直测。
 */
internal class RealCommandExecutor(
    private val runtime: TerminalRuntime,
    private val pollIntervalMs: Long = 120L,
    private val echoWrapsCommand: Boolean = true
) {

    /** 一次执行的结果语义（VM 据此落 EXIT 行 / 发通知）。 */
    sealed interface ExecResult {
        /** 正常终局：exitCode 为真实退出码（marker 协议携带；null = 未知）。 */
        data class Completed(val exitCode: Int?, val timedOut: Boolean) : ExecResult

        /** run() 被拒（策略拦截 / 写入失败 / 会话失效）。 */
        data class RunFailed(val message: String) : ExecResult

        /** wait() 本身出错（区别于 RunFailed —— 会话还在，等待链路坏了）。 */
        data class WaitFailed(val message: String) : ExecResult

        /** 会话在执行中消失（shell 退出 / exit 命令 / 外部 close）。 */
        data object SessionGone : ExecResult
    }

    /**
     * 在 [sessionId] 上执行 [command]（owner = USER —— 用户提交语义，与
     * DepCenter 的 SYSTEM 安装链路区分）。
     *
     * @param timeoutMs 等待上限（0 = 无限等待 —— 交互命令的标准口径；
     *        超时后 SIGTERM 兜底并标记 timedOut）
     * @param onLines 干净输出行的实时回调（空批次不回调）
     */
    suspend fun execute(
        sessionId: Long,
        command: String,
        timeoutMs: Long = 0L,
        onLines: (List<String>) -> Unit
    ): ExecResult {
        val run = runtime.run(sessionId, command, InputOwner.USER, background = false)
            .getOrElse { e -> return ExecResult.RunFailed(e.message ?: "run refused") }

        // timeoutMs=0 → 无限等待：platform wait 内部是 withTimeoutOrNull(0) 即时
        // 超时，必须映射为远未来截止（Long.MAX_VALUE），交互命令（top/apt）不设限。
        val effectiveTimeout = if (timeoutMs <= 0) Long.MAX_VALUE else timeoutMs

        val echo = if (echoWrapsCommand) ShellMarkers.wrapCommand(run.jobId, command) else command
        val sanitizer = RawStreamSanitizer(expectedEcho = echo)
        var cursor = run.startCursor

        val outcome: ExecResult = coroutineScope {
            val waiter = async {
                runtime.wait(sessionId, WaitCondition.ProcessExited(jobId = run.jobId), effectiveTimeout)
            }
            // 立即拉第一块 —— 短命令（echo/date）可能在 wait 返回前已全部产出；
            // 之后按 [pollIntervalMs] 轮询直到 wait 终局。会话消失时 pump 静默
            //（observe 失败即 break），终局由 wait 的 SessionGone 给出。
            while (!waiter.isCompleted) {
                delay(pollIntervalMs)
                val obs = runtime.observe(sessionId, TerminalRuntime.ObserveMode.RAW, cursor, MAX_CHUNK)
                    .getOrNull() ?: break
                cursor = obs.cursor
                val raw = obs.raw
                if (!raw.isNullOrEmpty()) {
                    val lines = sanitizer.feed(raw)
                    if (lines.isNotEmpty()) onLines(lines)
                }
            }
            val waitResult = waiter.await()
            mapWait(waitResult, run)
        }
        if (outcome is ExecResult.SessionGone) return outcome

        // 终局冲刷：残余行上屏，提示符尾丢弃
        val finalLines = sanitizer.finish()
        if (finalLines.isNotEmpty()) onLines(finalLines)
        return outcome
    }

    /** 向前台 job 投递 SIGINT（停止按钮）。@return 是否送达。 */
    suspend fun interrupt(sessionId: Long, jobId: Long? = null): Boolean =
        runtime.signalForeground(sessionId, UnixSignal.SIGINT, InputOwner.USER, jobId).isSuccess

    private suspend fun mapWait(
        waitResult: Result<WaitResult>,
        run: TerminalRuntime.RunResult
    ): ExecResult {
        val wait = waitResult.getOrNull()
            ?: return ExecResult.WaitFailed("wait failed")
        return when (wait) {
            is WaitResult.Matched -> {
                val ev = wait.event
                val code = (ev as? TerminalEvent.ProcessExited)?.exitCode
                ExecResult.Completed(exitCode = code, timedOut = false)
            }
            is WaitResult.Timeout -> {
                // 温和终止（与 DepCenter 300s 口径同族）：SIGTERM 后如实标记超时
                runtime.signalForeground(run.sessionId, UnixSignal.SIGTERM, InputOwner.USER, run.jobId)
                ExecResult.Completed(exitCode = 143, timedOut = true)
            }
            is WaitResult.SessionGone -> ExecResult.SessionGone
        }
    }

    companion object {
        /** 单次 observe 的字节上限（与 DepCenter 口径一致）。 */
        internal const val MAX_CHUNK = 65536
    }
}
