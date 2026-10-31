package com.apex.agent.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.apex.agent.BuildConfig
import com.apex.agent.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 诊断信息通用行模型：key 为本地化字段名，value 为展示值（UI 右对齐可换行）。 */
data class DiagRow(val key: String, val value: String)

/**
 * 诊断信息采集器（诊断中心三件套之二）。
 *
 * 三类职责：
 * 1. **应用/设备信息行**——[appInfoRows] / [deviceInfoRows] 产出 [DiagRow] 列表，
 *    字段名经 R.string（diag_field_*）本地化，值在采集时格式化；
 * 2. **崩溃记录**——全局 crash handler（ApexApp.writeCrashFile）把未捕获异常
 *    写入 `filesDir/crash/crash-<ts>.txt`（保留 10 个），本类提供列出/读取/
 *    删除/清空，是这些文件的首个应用内查看入口；
 * 3. **一键诊断报告**——[buildDiagnosticsReport] 把上面全部内容打包为
 *    `cacheDir/diagnostics/apex-diag-<yyyyMMdd-HHmmss>.zip`：
 *    - `device.txt`：生成时间 + 全部应用/设备信息行（"key: value" 文本）；
 *    - `logs/`：`filesDir/logs/` 全部日志文件拷贝（LogFileSink 落盘产物）；
 *    - `crashes/`：`filesDir/crash/` 全部崩溃文件拷贝。
 *
 * ## 失败策略
 * 诊断采集自身不允许影响主业务：所有系统 API 调用与 IO 均用 runCatching 包裹。
 * zip 内**每个条目独立 runCatching**——单个文件读取失败只丢该条目，不影响整体
 * （ZipOutputStream 的 putNextEntry 会自动收尾上一个未关闭的条目，流保持合法）；
 * [buildDiagnosticsReport] 仅在完全失败（目录不可写 / zip 流中断）时返回 null。
 * 生成前清理 7 天前的旧 zip，防止 cacheDir 膨胀。
 */
@Singleton
class DiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val reportNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ───────────────────────── 应用信息 ─────────────────────────

    /** 应用信息行：版本/构建/安装来源/安装与更新时间/targetSdk/minSdk。 */
    fun appInfoRows(): List<DiagRow> {
        val pkg = packageInfo()
        return listOf(
            DiagRow(str(R.string.diag_field_version_name), BuildConfig.VERSION_NAME),
            DiagRow(str(R.string.diag_field_version_code), BuildConfig.VERSION_CODE.toString()),
            DiagRow(
                str(R.string.diag_field_build_type),
                str(if (BuildConfig.DEBUG) R.string.diag_build_debug else R.string.diag_build_release)
            ),
            DiagRow(str(R.string.diag_field_install_source), installSource()),
            DiagRow(
                str(R.string.diag_field_first_install),
                pkg?.firstInstallTime?.takeIf { it > 0 }?.let { formatTime(it) }
                    ?: str(R.string.diag_unknown)
            ),
            DiagRow(
                str(R.string.diag_field_last_update),
                pkg?.lastUpdateTime?.takeIf { it > 0 }?.let { formatTime(it) }
                    ?: str(R.string.diag_unknown)
            ),
            DiagRow(
                str(R.string.diag_field_target_sdk),
                pkg?.applicationInfo?.targetSdkVersion?.toString() ?: str(R.string.diag_unknown)
            ),
            DiagRow(str(R.string.diag_field_min_sdk), minSdkLabel())
        )
    }

    /** 安装来源：30+ 用 getInstallSourceInfo（发起安装的包名），低版本回退 installerPackageName。 */
    private fun installSource(): String {
        val fallback = str(R.string.diag_unknown)
        return runCatching {
            val pm = context.packageManager
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(context.packageName)
                info.installingPackageName ?: info.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
            name?.takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
    }

    /**
     * 最低支持 Android 版本：ApplicationInfo.minSdkVersion（API 24+ 公开字段，
     * 本应用 minSdk 26 无需守卫）。原实现引用 PackageInfo.compileSdkVersion
     * —— 该字段不存在（CI 实证）；编译 SDK 在运行期无跨版本安全的公开查询
     * 通道，改展示 minSdk（对用户侧诊断同样有价值）。
     */
    private fun minSdkLabel(): String {
        val fallback = str(R.string.diag_unknown)
        return runCatching {
            context.applicationInfo.minSdkVersion.takeIf { it > 0 }?.toString()
                ?: "API ${Build.VERSION_CODES.O}"
        }.getOrDefault(fallback)
    }

    // ───────────────────────── 设备信息 ─────────────────────────

    /** 设备信息行：厂商型号/系统版本/安全补丁/ABI/屏幕/内存/存储/语言时区/CPU。 */
    fun deviceInfoRows(): List<DiagRow> = listOf(
        DiagRow(str(R.string.diag_field_model), "${Build.MANUFACTURER} ${Build.MODEL}".trim()),
        DiagRow(
            str(R.string.diag_field_android),
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ),
        DiagRow(str(R.string.diag_field_security_patch), Build.VERSION.SECURITY_PATCH),
        DiagRow(str(R.string.diag_field_abi), Build.SUPPORTED_ABIS.joinToString(", ")),
        DiagRow(str(R.string.diag_field_screen), screenLabel()),
        DiagRow(str(R.string.diag_field_memory), memoryLabel()),
        DiagRow(str(R.string.diag_field_storage), storageLabel()),
        DiagRow(
            str(R.string.diag_field_locale_tz),
            "${Locale.getDefault().toLanguageTag()} · ${TimeZone.getDefault().id}"
        ),
        DiagRow(str(R.string.diag_field_cpu), Runtime.getRuntime().availableProcessors().toString())
    )

    private fun screenLabel(): String {
        val fallback = str(R.string.diag_unknown)
        return runCatching {
            val dm = context.resources.displayMetrics
            val density = String.format(Locale.US, "%.2f", dm.density)
            "${dm.widthPixels} × ${dm.heightPixels} px · ${density}x · ${dm.densityDpi} dpi"
        }.getOrDefault(fallback)
    }

    private fun memoryLabel(): String {
        val fallback = str(R.string.diag_unknown)
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            val total = info.totalMem
            val avail = info.availMem
            val pct = if (total > 0) (avail * 100 / total).toInt() else 0
            str(R.string.diag_value_mem, formatBytes(avail), formatBytes(total), pct)
        }.getOrDefault(fallback)
    }

    private fun storageLabel(): String {
        val fallback = str(R.string.diag_unknown)
        return runCatching {
            val total = context.filesDir.totalSpace
            val avail = context.filesDir.usableSpace
            val pct = if (total > 0) (avail * 100 / total).toInt() else 0
            str(R.string.diag_value_storage, formatBytes(avail), formatBytes(total), pct)
        }.getOrDefault(fallback)
    }

    // ───────────────────────── 崩溃记录 ─────────────────────────

    /** 全部崩溃文件（filesDir/crash/ 下 .txt），文件名倒序 = 最新在前。 */
    fun crashFiles(): List<File> = runCatching {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.isDirectory) return@runCatching emptyList()
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** 崩溃文件时间：优先从文件名 crash-&lt;ts&gt;.txt 解析，失败回退 lastModified。 */
    fun crashFileTime(f: File): Long = runCatching {
        CRASH_NAME_REGEX.find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: f.lastModified()
    }.getOrDefault(f.lastModified())

    /** 读取崩溃文件内容，失败返回 null。 */
    fun readCrash(f: File): String? = runCatching {
        f.takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** 删除单个崩溃文件。 */
    fun deleteCrash(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    /** 清空全部崩溃文件（目录不存在视为成功）。 */
    fun clearCrashes(): Boolean = runCatching {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.isDirectory) return@runCatching true
        dir.listFiles()?.forEach { it.delete() }
        true
    }.getOrDefault(false)

    // ───────────────────────── 诊断报告 ─────────────────────────

    /**
     * 生成诊断报告 zip（cacheDir/diagnostics/apex-diag-<yyyyMMdd-HHmmss>.zip），
     * 内含 device.txt + logs/ + crashes/ + anr/。仅当完全失败时返回 null。
     */
    fun buildDiagnosticsReport(): File? {
        val dir = File(context.cacheDir, DIAG_DIR)
        val dirReady = runCatching { dir.exists() || dir.mkdirs() }.getOrDefault(false)
        if (!dirReady) return null
        // 生成前清理 7 天前的旧 zip
        runCatching {
            val cutoff = System.currentTimeMillis() - REPORT_RETENTION_MS
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        }
        val stamp = synchronized(reportNameFormat) { reportNameFormat.format(Date()) }
        val zip = File(dir, "$REPORT_PREFIX$stamp$REPORT_SUFFIX")
        val ok = runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                writeDeviceTxt(zos)
                copyDirIntoZip(File(context.filesDir, LOG_DIR), "logs", zos)
                copyDirIntoZip(File(context.filesDir, CRASH_DIR), "crashes", zos)
                // 日志增强：ANR 看门狗堆栈转储一并入包（目录不存在时静默跳过）
                copyDirIntoZip(File(context.filesDir, ANR_DIR), "anr", zos)
            }
            zip.isFile && zip.length() > 0L
        }.getOrDefault(false)
        return if (ok) zip else {
            runCatching { zip.delete() }
            null
        }
    }

    /** 写入 device.txt：生成时间 + 全部应用/设备信息行。 */
    private fun writeDeviceTxt(zos: ZipOutputStream) {
        runCatching {
            val sb = StringBuilder(2048)
            sb.append("generated: ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append('\n')
            appInfoRows().forEach { sb.append(it.key).append(": ").append(it.value).append('\n') }
            deviceInfoRows().forEach { sb.append(it.key).append(": ").append(it.value).append('\n') }
            zos.putNextEntry(ZipEntry("device.txt"))
            zos.write(sb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    /** 把目录下全部文件按名拷贝进 zip 的 `<prefix>/<name>` 条目；单条目失败不影响整体。 */
    private fun copyDirIntoZip(dir: File, entryPrefix: String, zos: ZipOutputStream) {
        val files = runCatching {
            dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
        }.getOrNull() ?: return
        for (f in files) {
            runCatching {
                zos.putNextEntry(ZipEntry("$entryPrefix/${f.name}"))
                FileInputStream(f).use { input -> input.copyTo(zos, DEFAULT_BUFFER_SIZE) }
                zos.closeEntry()
            }
        }
    }

    // ───────────────────────── 私有工具 ─────────────────────────

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    private fun formatTime(ts: Long): String = synchronized(dateTimeFormat) {
        dateTimeFormat.format(Date(ts))
    }

    private fun str(resId: Int): String = context.getString(resId)

    private fun str(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    companion object {
        private const val DIAG_DIR = "diagnostics"
        private const val LOG_DIR = "logs"
        private const val CRASH_DIR = "crash"
        private const val ANR_DIR = "anr"
        private const val REPORT_PREFIX = "apex-diag-"
        private const val REPORT_SUFFIX = ".zip"
        private const val REPORT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private val CRASH_NAME_REGEX = Regex("""crash-(\d+)\.txt""")
    }
}

/** 字节数人性化格式化：B/KB/MB/GB，一位小数（KB 起保留小数）。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}
