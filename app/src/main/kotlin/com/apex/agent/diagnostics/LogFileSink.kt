package com.apex.agent.diagnostics

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.logging.LogRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 日志文件落盘管道（诊断中心三件套之一）。
 *
 * [AppLogger] 是纯内存 8MB 环形缓冲，重启即清空——本 Sink 订阅其
 * [AppLogger.stream] 实时广播，把每条日志格式化为文本行追加到当日文件
 * `filesDir/logs/app-yyyyMMdd.log`，让"重启后回溯上次会话日志"成为可能。
 *
 * ## 铁律：落盘组件自己崩溃是不可接受的
 * 日志落盘属于旁路观测设施，绝不允许反过来干扰主业务。因此本类**每一处 IO
 * 都用 runCatching 包裹**（含目录创建、文件写入、滚动、清理、读取），任何
 * 失败静默降级——丢一条日志远比拖垮宿主进程或取消收集协程的代价小。
 *
 * ## 级别过滤决策：只落 INFO 及以上
 * VERBOSE/DEBUG 日志量大、排查价值低（Agent 流式输出期每 token 多条），
 * 全量落盘会让文件迅速膨胀并造成持续小 IO。INFO/WARN/ERROR/FATAL 才是
 * 跨会话回溯有意义的信号，故只保留 INFO+（用 [LogLevel.atLeast] 判定，
 * 天然涵盖 SILENT 伪级别），体积与 IO 显著降低。
 *
 * ## 重放去重（防御性设计）
 * [AppLogger.stream] 是 replay=64 的 SharedFlow——订阅瞬间会重放缓冲里
 * 最后 64 条旧记录。同一进程内 [start] 只应被调用一次（AtomicBoolean 守卫），
 * 但为防御将来被多处调用 / sink 被重启等误用，用容量 256 的 [seenIds]
 * （synchronized 保护）记录已落盘记录的 id，重放窗口内（64 < 256）的重复
 * 记录会被识别并跳过，避免同一记录写两份。
 *
 * ## 滚动与保留
 * - 跨天自动切新文件（文件名内嵌 yyyyMMdd）；
 * - 单文件超 2MB 滚动为 `.1` 后缀（单代滚动：先删旧 `.1` 再改名）；
 * - 目录内按文件名倒序保留最近 7 个文件（yyyyMMdd 定宽，字典序即时间序），
 *   多余的删除；当日文件永远是最新的一个，不会被误删。
 *
 * ## 线程模型
 * 自建 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，随进程存活
 * （单例，无需 cancel）。collect 串行执行，[dayFormat]/[lineTimeFormat]
 * 仅从该协程访问，仍按 [SimpleDateFormat] 非线程安全惯例加 synchronized。
 */
@Singleton
class LogFileSink @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已落盘记录 id 的去重窗口（synchronized 保护；容量 256 > replay 64）。 */
    private val seenIds = ArrayDeque<Long>()

    /** 当前写入中的日期键（yyyyMMdd），跨天切换文件用；仅收集协程访问。 */
    private var currentDayKey: String = ""

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lineTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 启动收集（幂等：进程内多次调用只有第一次生效）。
     * 建议在 Application.onCreate 尽早调用，让启动期日志也能落盘。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            File(context.filesDir, LOG_DIR).mkdirs()
            enforceRetention()
        }
        scope.launch {
            // collect 循环内的任何异常都会终止收集——每条记录单独 runCatching，
            // 保证单条落盘失败不影响后续记录。
            AppLogger.instance.stream.collect { record ->
                runCatching { onRecord(record) }
            }
        }
    }

    /** 单条记录处理：级别过滤 → 去重 → 格式化 → 追加落盘。 */
    private fun onRecord(record: LogRecord) {
        if (!record.level.atLeast(LogLevel.INFO)) return
        if (isDuplicate(record.id)) return
        appendLine(formatRecord(record))
    }

    /** 重放去重：已见返回 true；未见则登记并裁剪窗口。 */
    private fun isDuplicate(id: Long): Boolean = synchronized(seenIds) {
        if (seenIds.contains(id)) return true
        seenIds.addLast(id)
        if (seenIds.size > SEEN_CAPACITY) seenIds.removeFirst()
        false
    }

    /** 格式化为 `MM-dd HH:mm:ss.SSS E/CAT [source] message` 单行；异常附堆栈前 30 行。 */
    private fun formatRecord(record: LogRecord): String {
        val ts = synchronized(lineTimeFormat) { lineTimeFormat.format(Date(record.timestamp)) }
        val sb = StringBuilder(128)
        sb.append(ts).append(' ')
            .append(record.level.shortTag).append('/')
            .append(record.category.shortCode)
            .append(" [").append(record.source).append("] ")
            // 单行纪律：消息内换行压平为空格，保证"一条记录一行"的可 grep 性
            .append(record.message.replace('\n', ' '))
        record.throwable?.let { t ->
            val stack = t.stackTraceToString()
                .lines()
                .filter { it.isNotBlank() }
                .take(MAX_STACK_LINES)
                .joinToString("\n")
            if (stack.isNotEmpty()) sb.append('\n').append(stack)
        }
        return sb.toString()
    }

    /** 追加一行到当日文件；跨天切新文件，超 2MB 滚动 `.1`。 */
    private fun appendLine(line: String) {
        val dayKey = synchronized(dayFormat) { dayFormat.format(Date()) }
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey
            enforceRetention()
        }
        val dir = File(context.filesDir, LOG_DIR)
        val file = File(dir, "$FILE_PREFIX$dayKey$FILE_SUFFIX")
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val rolled = File(dir, file.name + ROLL_SUFFIX)
            rolled.delete()
            file.renameTo(rolled)
            enforceRetention()
        }
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    /** 保留策略：目录内按文件名倒序保留最近 7 个文件，多余的删除。 */
    private fun enforceRetention() {
        runCatching {
            val dir = File(context.filesDir, LOG_DIR)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            files.sortedByDescending { it.name }.drop(MAX_FILES).forEach { it.delete() }
        }
    }

    /** 全部日志文件，文件名倒序（最新在前）。 */
    fun logFiles(): List<File> = runCatching {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.isDirectory) return@runCatching emptyList()
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 读取日志文本；文件超 [maxBytes] 时丢弃头部、只保留尾部（日志追加写，
     * 尾部即最新），并对齐到行首避免半行开头。失败返回 null。
     */
    fun readText(file: File, maxBytes: Long = 512 * 1024): String? = runCatching {
        if (!file.isFile) return@runCatching null
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len <= maxBytes) {
                val buf = ByteArray(len.toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            } else {
                val cap = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                raf.seek(len - cap)
                val buf = ByteArray(cap)
                raf.readFully(buf)
                var text = String(buf, Charsets.UTF_8)
                val firstBreak = text.indexOf('\n')
                if (firstBreak > 0) text = text.substring(firstBreak + 1)
                "…\n$text"
            }
        }
    }.getOrNull()

    /** 清空 logs 目录下全部文件。返回是否全部删除成功（目录不存在视为成功）。 */
    fun clearAll(): Boolean = runCatching {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.isDirectory) return@runCatching true
        var allOk = true
        dir.listFiles()?.forEach { f -> if (!f.delete()) allOk = false }
        allOk
    }.getOrDefault(false)

    /** logs 目录当前总字节数。 */
    fun totalBytes(): Long = logFiles().sumOf { it.length() }

    companion object {
        private const val LOG_DIR = "logs"
        private const val FILE_PREFIX = "app-"
        private const val FILE_SUFFIX = ".log"
        private const val ROLL_SUFFIX = ".1"
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_FILES = 7
        private const val MAX_STACK_LINES = 30
        private const val SEEN_CAPACITY = 256
    }
}
