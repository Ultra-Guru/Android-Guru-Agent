package com.apex.agent.diagnostics

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ 常驻 logcat 采集管道（诊断三件套之四）═══
 *
 * 问题背景：AppLogger 此前只覆盖应用内主动打点的日志 —— 各子系统里
 * `android.util.Log` 直写的内容（第三方库、系统组件回调、OEM ROM 行为、
 * 渲染层告警）从未进入日志中枢，崩溃归因时这一大块是盲区。
 *
 * 设计：
 *  - 仅采集**本进程**日志（`logcat --pid=<mypid>`）：无 READ_LOGS 权限
 *    依赖、Android 4.1+ 对普通应用天然可见自己的日志 —— 权限零成本，
 *    行为稳定（用户选择的采集范围：仅本应用进程）；
 *  - `-T 1` 跳过启动时已有缓冲（只 tail 新行，不重灌历史）；
 *  - 级别门槛 DEBUG+：VERBOSE 噪声大且排查价值低，跳过（与 LogFileSink
 *    的 INFO+ 落盘门槛形成两级漏斗：内存 ring 全量 DEBUG+，磁盘 INFO+）；
 *  - 单行解析 [brief] 格式（`I/Tag( 1234): message`）——纯函数
 *    [parseBriefLine] 独立可测；
 *  - 无回环：本类只把行写入 AppLogger（AppLogger 不回写 android logcat），
 *    采集自身不产生新 logcat 行，天然不会自激。
 *
 * 线程模型：低优先级守护线程阻塞读 —— logcat 的输出流在无新日志时挂起，
 *    零轮询成本；AppLogger v2 是 O(1) 追加，洪峰安全。
 *
 * 防御式：IO 任何异常（logcat 二进制不可用 / 流意外关闭）→ 静默停止线程
 *    并 Log.w 留痕 —— 观测设施绝不反向干扰宿主进程（LogFileSink 同款铁律）。
 */
@Singleton
class LogcatCollector @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val started = AtomicBoolean(false)

    /** 启动采集（幂等）。建议 Application.onCreate 中、LogFileSink 之后调用。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val pid = android.os.Process.myPid()
        // 显式具名传参 + 普通代码块（AGENTS.md Kotlin 陷阱 #2：尾随 lambda
        // 绑定末参 —— Thread 构造不用尾随 lambda，可读性优先）。
        val worker = Thread(
            Runnable {
                runCatching { tail(pid) }
                    .onFailure {
                        android.util.Log.w("LogcatCollector", "tail stopped: ${it.message}")
                    }
            },
            "apex-logcat-tail"
        )
        worker.isDaemon = true
        worker.priority = Thread.MIN_PRIORITY + 1
        worker.start()
    }

    /** 阻塞 tail 主循环（包内可见以便单测注入假流；生产走 [start]）。 */
    internal fun tail(pid: Int, streamFactory: (Int) -> java.io.InputStream? = ::openStream) {
        val stream = streamFactory(pid) ?: return
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break // EOF：logcat 进程被杀
                val parsed = parseBriefLine(line) ?: continue
                if (parsed.level < LogLevel.DEBUG) continue
                AppLogger.instance.fromAndroid(
                    level = parsed.level,
                    category = LogCategory.SYSTEM,
                    source = parsed.tag,
                    message = parsed.message,
                    tags = arrayOf("logcat")
                )
            }
        }
    }

    /** 启动 logcat 子进程（默认 brief 格式 + 只 tail 新行）。失败返回 null。 */
    private fun openStream(pid: Int): java.io.InputStream? = runCatching {
        ProcessBuilder("logcat", "--pid=$pid", "-T", "1")
            .redirectErrorStream(false)
            .start()
            .inputStream
    }.getOrNull()

    internal companion object {
        /**
         * 解析 logcat brief 行：`I/Tag( 1234): message`。
         * 返回 null 表示不认识的行（分隔行/头行/被截断行）—— 调用方跳过。
         */
        fun parseBriefLine(line: String): ParsedLine? {
            if (line.isBlank()) return null
            val slash = line.indexOf('/')
            if (slash <= 0) return null
            val level = when (line[slash - 1]) {
                'V' -> LogLevel.VERBOSE
                'D' -> LogLevel.DEBUG
                'I' -> LogLevel.INFO
                'W' -> LogLevel.WARN
                'E' -> LogLevel.ERROR
                'F' -> LogLevel.FATAL
                else -> return null
            }
            // tag 到 "( pid): " 之间；容忍 "--------- beginning of" 类头行返回 null
            val paren = line.indexOf('(', slash)
            if (paren < 0) return null
            val tag = line.substring(slash + 1, paren).trim()
            if (tag.isEmpty()) return null
            val colon = line.indexOf("): ", paren)
            if (colon < 0) return null
            val message = line.substring(colon + 3)
            return ParsedLine(level, tag, message)
        }
    }

    /** 一条已解析的 logcat 行（包内可见，单测用）。 */
    internal data class ParsedLine(val level: LogLevel, val tag: String, val message: String)
}
