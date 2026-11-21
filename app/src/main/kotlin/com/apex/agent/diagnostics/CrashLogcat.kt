package com.apex.agent.diagnostics

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ 崩溃 logcat 快照与冷启动回捞（诊断三件套之五）═══
 *
 * 两个互补通道，填补「崩溃文件只有 Java 栈、没有系统侧上下文」的归因盲区：
 *
 * ## 通道 1：崩溃瞬间快照（[appendSnapshot]）
 * 全局 crash handler 写完 crash-<ts>.txt 后同步追加最近 N 行 logcat
 * （仅本进程）。注意：此刻系统尚未打印 FATAL EXCEPTION 行（默认 handler
 * 在我们之后才执行），所以快照是**崩溃前上下文** —— 正是定位「崩之前
 * 发生了什么」最有价值的部分。
 *
 * ## 通道 2：冷启动回捞（[salvageOnStartup]）
 * 系统把未处理崩溃全文（Java 的 FATAL EXCEPTION 块 / native 的
 * Fatal signal 块）打进 logcat crash buffer，buffer 跨进程存活到下一次
 * 崩溃覆盖。下次启动时异步 dump `logcat -d -b crash`：
 *  - 追加到最近的 crash 文件（幂等：落盘 marker 防重复追加）；
 *  - native 崩溃（Java handler 捕不到、VtFeedTrail 只覆盖终端场景）在
 *    无任何 crash 文件时兜底建档 crash-<ts>-native.txt —— 填补 native
 *    崩溃归因的最后一块盲区。
 *
 * ## 铁律（LogFileSink 同款）
 * 崩溃路径 + 启动路径全部 runCatching；子进程带 waitFor 超时 + 强杀；
 * 观测设施失败静默（仅 Log.w 留痕），绝不干扰宿主。
 *
 * ## 相关性诚实声明
 * crash buffer 不带「哪次启动」标识 —— 回捞内容与 crash 文件的配对按
 * 「最近一次崩溃」推定，文件头部写明 provenance，不做无据断言。
 */
@Singleton
class CrashLogcat @Inject constructor() {

    /**
     * 崩溃瞬间调用：把最近 [SNAPSHOT_LINES] 行本进程 logcat 追加到崩溃文件。
     * 同步执行（崩溃路径上允许短暂 IO），全程 runCatching —— 二次异常绝不外抛。
     */
    fun appendSnapshot(crashFile: File) {
        runCatching {
            val dump = runLogcat(
                timeoutMs = 4000,
                maxLines = SNAPSHOT_LINES,
                args = arrayOf("-d", "-t", SNAPSHOT_LINES.toString(), "--pid=${android.os.Process.myPid()}")
            ) ?: return
            if (dump.isBlank()) return
            crashFile.appendText(
                buildString {
                    append("\n── 崩溃前 logcat 快照（最近 $SNAPSHOT_LINES 行，本进程）──\n")
                    append(dump)
                    append("\n── 快照结束（FATAL 行由系统在 handler 返回后打印，见下次启动回捞）──\n")
                }
            )
        }.onFailure {
            android.util.Log.w("CrashLogcat", "appendSnapshot failed: ${it.message}")
        }
    }

    /**
     * 冷启动回捞：dump 系统 crash buffer，按 marker 幂等追加 / native 兜底建档。
     * 建议在 Application.onCreate 经后台协程调用（子进程 exec 不占主线程）。
     * 返回结果摘要（诊断日志用；无价值时为 null）。
     */
    fun salvageOnStartup(crashDirProvider: () -> File): String? {
        val buffer = runCatching {
            runLogcat(
                timeoutMs = 5000,
                maxLines = SALVAGE_LINES,
                args = arrayOf("-d", "-b", "crash")
            )
        }.getOrNull() ?: return null
        if (buffer.isBlank()) return null
        val hasJava = buffer.contains("FATAL EXCEPTION")
        val hasNative = buffer.contains("Fatal signal")
        if (!hasJava && !hasNative) return null

        val crashDir = crashDirProvider()
        val newest = newestCrashFile(crashDir)
        return runCatching {
            when {
                // 路径 A：已有 crash 文件且未回捞过 → 追加（幂等 marker）
                newest != null && !newest.readText().contains(SALVAGE_MARKER) -> {
                    newest.appendText(
                        buildString {
                            append("\n$SALVAGE_MARKER\n")
                            append("回捞时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
                            append("provenance: 本次启动时的 logcat crash buffer —— 与上方 crash 文件的配对按「最近一次崩溃」推定，不做无据断言。\n")
                            append("── 系统 crash buffer dump ──\n")
                            append(buffer)
                            append("\n── dump 结束 ──\n")
                        }
                    )
                    "appended ${buffer.length} chars to ${newest.name}"
                }
                // 路径 B：无任何 crash 文件但 buffer 有 native 崩溃签名 → 兜底建档
                newest == null && hasNative -> {
                    crashDir.mkdirs()
                    val f = File(crashDir, "crash-${System.currentTimeMillis()}-native.txt")
                    f.writeText(
                        buildString {
                            appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                            appendLine("exception: native crash (Fatal signal) — Java handler 不覆盖此场景，由冷启动回捞兜底建档")
                            appendLine("provenance: 本次启动时的 logcat crash buffer；发生时刻不可精确推定。")
                            appendLine("stack:")
                            appendLine(buffer)
                        }
                    )
                    trimNativeFiles(crashDir)
                    "native crash archived: ${f.name}"
                }
                else -> null // 已回捞过 / 无文件且非 native —— 不动作
            }
        }.getOrNull()
    }

    /** 执行 logcat 子进程并读全部输出；超时强杀返回 null。 */
    private fun runLogcat(timeoutMs: Long, maxLines: Int, vararg args: String): String? =
        runCatching {
            val proc = ProcessBuilder("logcat", *args)
                .redirectErrorStream(false)
                .start()
            val text = StringBuilder()
            var count = 0
            proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (l in lines) {
                    if (count >= maxLines) break
                    text.appendLine(l)
                    count++
                }
            }
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                return@runCatching null
            }
            text.toString().trim().ifBlank { null }
        }.getOrNull()

    private fun newestCrashFile(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }

    /** native 兜底文件同样只保留最近 10 个（与 writeCrashFile 策略对齐）。 */
    private fun trimNativeFiles(dir: File) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith("-native.txt") }
                ?.sortedByDescending { it.name }
                ?.drop(10)
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val SNAPSHOT_LINES = 300
        const val SALVAGE_LINES = 400
        const val SALVAGE_MARKER = "── 冷启动回捞：系统 crash buffer ──"
    }
}
