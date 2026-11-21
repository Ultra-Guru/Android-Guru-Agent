package com.apex.agent.diagnostics

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ 主线程无响应看门狗（诊断三件套之六）═══
 *
 * 问题背景：此前崩溃类观测只覆盖「进程死」（Java handler + native 黑匣子），
 * 主线程冻结（输入派发 ANR 前兆、死锁、主线程长 IO）完全没有可视面 ——
 * 用户感知是「卡死了然后也许自己好了」，日志里什么都没有。
 *
 * 原理（ANR-WatchDog 同款心跳）：
 *  - 看门狗线程每 [CHECK_INTERVAL_MS] 向主线程 Handler 投递一个更新
 *    [lastAliveAt] 的任务，随后睡眠；
 *  - 醒来时若 `now - lastAliveAt > [THRESHOLD_MS]`，说明主线程 ≥ 阈值
 *    （5s，对齐系统输入派发 ANR 口径）没有处理任何消息 —— 判定冻结，
 *    转储主线程堆栈到 filesDir/anr/anr-<ts>.txt（保留最近 5 个），
 *    AppLogger ERROR 留痕（tags=anr）；
 *  - 恢复后记一条 WARN（含阻塞时长估计）—— 「卡了一下又好了」从此有据可查。
 *
 * 防误报：
 *  - 调试器连接时暂停判定（断点会合法冻结主线程），并同步心跳防陈旧触发；
 *  - 重复报告冷却 [REPORT_COOLDOWN_MS]：长冻结期间每 30s 一份堆栈
 *    （持续冻结的实况记录），不刷屏不刷盘。
 *
 * 铁律：观测失败静默（runCatching），绝不反向干扰宿主；纯看门狗线程，
 * 无锁竞争（[lastAliveAt] volatile 单写多读）。
 */
@Singleton
class AnrWatchdog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val started = AtomicBoolean(false)

    /** 主线程最近一次处理心跳的时刻（uptimeMillis；主线程写、看门狗读）。 */
    @Volatile private var lastAliveAt = 0L

    /** 本轮冻结是否已报告过（恢复时清零）。 */
    @Volatile private var reportedThisEpisode = false

    /** 本轮冻结首次报告时刻（恢复时长估计用）。 */
    @Volatile private var episodeStartAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 启动看门狗（幂等）。建议 Application.onCreate 中调用。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        lastAliveAt = SystemClock.uptimeMillis()
        Thread(
            Runnable { runCatching { loop() }.onFailure { android.util.Log.w("AnrWatchdog", "loop exited: ${it.message}") } },
            "apex-anr-watchdog"
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
    }

    private fun loop() {
        while (true) {
            Thread.sleep(CHECK_INTERVAL_MS)
            val now = SystemClock.uptimeMillis()
            // 调试器暂停判定：断点冻结是合法的，跳过且同步心跳防陈旧触发
            if (Debug.isDebuggerConnected()) {
                lastAliveAt = now
                continue
            }
            // 投递心跳（主线程处理时写 lastAliveAt）
            mainHandler.post { lastAliveAt = SystemClock.uptimeMillis() }
            Thread.sleep(CHECK_INTERVAL_MS)
            val checkAt = SystemClock.uptimeMillis()
            val gap = checkAt - lastAliveAt
            if (gap > THRESHOLD_MS) {
                onFrozen(checkAt, gap)
            } else if (reportedThisEpisode) {
                onRecovered(checkAt)
            }
        }
    }

    /** 冻结判定回调（看门狗线程）：报告 + 冷却控制。 */
    private fun onFrozen(now: Long, gap: Long) {
        val lastReportAge = now - (episodeStartAt.takeIf { it > 0 } ?: now)
        val firstOfEpisode = !reportedThisEpisode
        val cooldownOver = !firstOfEpisode && lastReportAge > REPORT_COOLDOWN_MS
        if (!firstOfEpisode && !cooldownOver) return // 冷却期内不重复报告
        if (firstOfEpisode) episodeStartAt = now
        reportedThisEpisode = true
        val mainThread = Looper.getMainLooper().thread
        val stack = runCatching { mainThread.stackTrace }
            .getOrElse { emptyArray() }
            .mapNotNull { it.toString() }
            .take(MAX_STACK_LINES)
        val file = writeAnrFile(gap, stack)
        AppLogger.instance.error(
            category = LogCategory.SYSTEM,
            source = "ANRWatchdog",
            message = "主线程无响应 ≥${THRESHOLD_MS}ms（心跳 gap=${gap}ms，疑似 ANR）" +
                "—— 已转储主线程堆栈${file?.let { " → ${it.name}" } ?: "（落盘失败）"}" +
                if (firstOfEpisode) "" else "（持续冻结，${REPORT_COOLDOWN_MS / 1000}s 冷却后续报）",
            tags = arrayOf("anr")
        )
    }

    /** 恢复回调：一次冻结周期的收尾留痕（WARN）。 */
    private fun onRecovered(now: Long) {
        val stuckMs = if (episodeStartAt > 0) now - episodeStartAt + THRESHOLD_MS else THRESHOLD_MS
        reportedThisEpisode = false
        episodeStartAt = 0
        AppLogger.instance.warn(
            category = LogCategory.SYSTEM,
            source = "ANRWatchdog",
            message = "主线程已恢复（本轮阻塞约 ${stuckMs / 1000}s）",
            tags = arrayOf("anr", "anr-recovered")
        )
    }

    private fun writeAnrFile(gapMs: Long, stack: List<String>): File? = runCatching {
        val dir = File(context.filesDir, ANR_DIR).apply { mkdirs() }
        val file = File(dir, "anr-${System.currentTimeMillis()}.txt")
        file.writeText(
            buildString {
                appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                appendLine("heartbeat gap: ${gapMs}ms (threshold ${THRESHOLD_MS}ms)")
                appendLine("thread: ${Looper.getMainLooper().thread.name}")
                appendLine("stack:")
                stack.forEach { appendLine(it) }
            }
        )
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_FILES)?.forEach { it.delete() }
        file
    }.getOrNull()

    /** anr 目录文件（诊断页 / zip 集成用；最新在前）。 */
    fun anrFiles(): List<File> = runCatching {
        val dir = File(context.filesDir, ANR_DIR)
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    private companion object {
        const val CHECK_INTERVAL_MS = 2000L
        const val THRESHOLD_MS = 5000L
        const val REPORT_COOLDOWN_MS = 30_000L
        const val KEEP_FILES = 5
        const val MAX_STACK_LINES = 120
        const val ANR_DIR = "anr"
    }
}
