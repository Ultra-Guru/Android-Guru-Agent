package com.apex.agent.usage

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════
//  数据模型
// ═══════════════════════════════════════════════════════════════════════════

/** 单条用量记录 —— 每轮 LLM 调用后由主控在 AgentEvent.UsageUpdated 处接线 [UsageLedger.record] 落账。 */
@Serializable
data class UsageRecord(
    /** 记账时刻（System.currentTimeMillis()）。 */
    val timestamp: Long,
    /** 会话 ID；调用方未提供（null/空白）时归一为空串，聚合时落入「未知会话」桶。 */
    val sessionId: String,
    /** 模型 ID（引擎侧 modelId 原样透传）。 */
    val modelId: String,
    /** 输入侧 token（服务端 usage 真实统计，非启发式估算）。 */
    val promptTokens: Long,
    /** 输出侧 token。 */
    val completionTokens: Long,
    /** 输入 + 输出合计（入账前负值已钳 0）。 */
    val totalTokens: Long
)

/** 日聚合行 —— [UsageLedger.daily] 的返回单元（[dayEpochMillis] 为本地时区当日 00:00）。 */
data class UsageDaily(
    val dayEpochMillis: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    /** 当日记账的 LLM 调用轮数。 */
    val requests: Int
)

/** 模型维度统计 —— [UsageLedger.modelBreakdown] 的返回单元。 */
data class UsageModelStat(
    val modelId: String,
    val totalTokens: Long,
    val requests: Int,
    /** 占全库总 token 的比例（0..1；全库为空时为 0）。 */
    val share: Float,
    /** 输入侧 token 累计（#221 费用估算：输入/输出费率不同）。 */
    val promptTokens: Long = 0L,
    /** 输出侧 token 累计。 */
    val completionTokens: Long = 0L
)

/** 汇总指标 —— [UsageLedger.totals] 的返回单元。 */
data class UsageTotals(
    val allTimeTokens: Long,
    val todayTokens: Long,
    val last7dTokens: Long,
    val last30dTokens: Long,
    val totalRequests: Int,
    val avgTokensPerRequest: Long
)

/** 会话维度统计 —— [UsageLedger.topSessions] 的返回单元。 */
data class UsageSessionStat(
    val sessionId: String,
    val totalTokens: Long,
    val requests: Int,
    /** 该会话首条记录时间戳。 */
    val firstAt: Long,
    /** 该会话末条记录时间戳。 */
    val lastAt: Long
)

// ═══════════════════════════════════════════════════════════════════════════
//  用量账本
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Token 用量持久化账本（JSONL 追加式存储 + 内存缓存聚合）。
 *
 * AgentEvent.UsageUpdated 此前只更新内存 UI 状态、重启即失 —— 本账本把每轮
 * LLM 调用的服务端真实 token 用量落盘，为用量仪表盘（UsageDashboardScreen）
 * 提供跨会话、跨重启的统计源。
 *
 * ## 存储形态与滚动保留（#863）
 * `<filesDir>/usage/usage_records.jsonl` 追加式 JSONL，每行一条 [UsageRecord]
 * 的 JSON。追加式意味着永不重写历史：无整文件序列化放大，也无「写到一半崩溃
 * 损毁全量数据」的风险；单行损坏只影响该行（加载时跳过并计数告警）。
 * 旧实现只增不减：重度用户一年可累计数十万行（数十 MB）。现加载时执行滚动
 * 保留（[RETENTION_MS] 时间窗 ∪ [MAX_LINES] 行数窗，见 [applyRetention]），
 * 裁剪后原子重写文件——磁盘占用有界，且保留策略自动执行、无需手动 clearAll。
 *
 * ## 线程模型
 * - [ioLock] 守护 [cache] 与文件读写，全部公开方法线程安全，可在任意线程调用；
 * - **构造零 IO（#863）**：旧实现在 init 同步全量解析文件——本类注入
 *   @HiltViewModel（CodeViewModel），构造发生在主线程，进 Coding 屏的
 *   卡顿随历史线性增长。现改为首次读账本时惰性加载（[ensureLoadedLocked]）：
 *   聚合方法本就由仪表盘跑在 Dispatchers.Default 后台协程，一次性解析
 *   成本落在那里，主线程构造零文件 IO；
 * - [record] 仅做「内存缓存追加 + 异步落盘」，调用方（引擎事件线程）零 IO 阻塞；
 * - 读方法（[daily] / [modelBreakdown] / [totals] / [topSessions] / [exportCsv]）
 *   在锁内取快照拷贝、锁外计算 —— 返回值是拍摄时刻的不可变视图（快照语义）；
 * - 落盘协程跑在内部 [scope]（SupervisorJob + Dispatchers.IO）上；并发落盘的
 *   行间顺序不保证（单行原子性由锁保证，行内容不会交错），而聚合统计与各记录
 *   时间戳均不依赖文件行序，无影响。
 *
 * ## 加载与并发写合并（#863）
 * 惰性加载窗口内可能有新记录入账：[pendingAppend] 登记已入内存、尚未落盘的
 * 记录——已落盘的自然存在于文件（不双计），未落盘的在加载完成后与磁盘内容
 * 合并；若加载触发了保留裁剪重写，pending 一并写进重写件并出册（对应追加
 * 协程自动跳过，不重复）。
 *
 * ## 丢失窗口（可接受）
 * [record] 异步落盘 —— 进程在「已入内存」与「已写盘」之间崩溃会丢尾部若干行。
 * 每轮 usage 彼此独立、无跨行一致性要求，丢尾部仅让统计略少计，属可接受损失；
 * 因此不做 WAL / 同步刷盘 / 落盘回执。
 *
 * ## 清空语义
 * [clearAll] 在锁内清缓存 + 删文件并置已加载；与在途落盘协程的竞态以
 * [pendingAppend] 出册防护 —— 被 [clearAll] 清掉的记录不会在文件删除后
 * 被旧协程追加复活；清空之后新产生的记录照常重建文件。
 */
@Singleton
class UsageLedger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 序列化实例 —— 与 SettingsRepository 同惯例：容忍未知字段，兼容旧行格式演化。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 全部缓存 / 文件访问的同步锁。 */
    private val ioLock = Any()

    /**
     * 内存缓存（追加序，原地 add —— #863：旧 `cache + rec` 每次全列表拷贝）。
     * 仅在 [ioLock] 内访问。
     */
    private var cache = ArrayList<UsageRecord>()

    /**
     * 已入内存、尚未落盘的记录（#863）：加载合并与 clearAll 竞态防护。落盘
     * 协程从它出册成功才真正 append——被清空/已被重写吸收的记录不复活。
     * 仅在 [ioLock] 内访问。
     */
    private val pendingAppend = ArrayList<UsageRecord>()

    /** 首次读账本时才从磁盘加载（#863）：构造零 IO。仅在 [ioLock] 内访问。 */
    private var loaded = false

    /** 内部落盘作用域 —— 账本为 @Singleton 与进程同寿，无需 cancel。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val usageDir = File(context.filesDir, "usage")
    private val file = File(usageDir, "usage_records.jsonl")

    // ─────────────────────────────── 写入 ───────────────────────────────

    /**
     * 记录一轮 LLM 调用的真实用量（主控接线入口 —— AgentEvent.UsageUpdated 到达时调用）。
     *
     * - token 负值一律钳 0（个别网关在流中断时回传 -1 占位）；
     * - [sessionId] 为 null / 空白时归一为空串（聚合时落入「未知会话」桶）；
     * - 时间戳取当前时刻；先入内存缓存（同步、立即可读），再异步落盘。
     *
     * @param sessionId 所属会话 ID，可为 null
     * @param modelId 模型 ID
     * @param promptTokens 输入侧 token
     * @param completionTokens 输出侧 token
     */
    fun record(sessionId: String?, modelId: String, promptTokens: Long, completionTokens: Long) {
        val prompt = promptTokens.coerceAtLeast(0L)
        val completion = completionTokens.coerceAtLeast(0L)
        val rec = UsageRecord(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId?.trim().orEmpty(),
            modelId = modelId.trim(),
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = (prompt + completion).coerceAtLeast(0L)
        )
        synchronized(ioLock) {
            cache.add(rec)
            pendingAppend.add(rec)
        }
        scope.launch {
            // 全程 runCatching：落盘失败只丢该行（内存统计仍在），绝不外抛。
            runCatching {
                synchronized(ioLock) {
                    // clearAll()/保留重写竞态防护（#863）：出册失败 = 该记录已被
                    // 清空或已被重写件吸收，放弃追加，避免「删文件/重写后又被
                    // 在途协程追加复活」。
                    if (!pendingAppend.remove(rec)) return@synchronized
                    usageDir.mkdirs()
                    file.appendText(json.encodeToString(rec) + "\n")
                }
            }.onFailure { e ->
                AppLogger.instance.warn(
                    LogCategory.LLM, "UsageLedger",
                    "用量记录落盘失败（仅丢该行，内存统计不受影响）: ${e.message}"
                )
            }
        }
    }

    /** 清空全部数据：内存缓存 + 落盘文件一并清除（UI 侧需自行确认后调用）。 */
    fun clearAll() {
        synchronized(ioLock) {
            cache = ArrayList()
            pendingAppend.clear()
            // 空账本即已加载：后续读方法不再触发惰性加载
            loaded = true
            runCatching {
                if (file.exists()) file.delete()
                if (usageDir.listFiles()?.isEmpty() == true) usageDir.delete()
            }.onFailure { e ->
                AppLogger.instance.warn(
                    LogCategory.LLM, "UsageLedger",
                    "用量账本清空失败（文件可能残留，重启后将被重新加载）: ${e.message}"
                )
            }
        }
    }

    // ─────────────────────────────── 读取 ───────────────────────────────

    /**
     * 按本地时区自然日聚合。
     *
     * @param rangeDays 统计窗口天数（含今天在内的自然日）；`<= 0` 表示全部历史
     * @return 日聚合列表，**新日期在前**（倒序）；图表侧如需时间正排自行 asReversed
     */
    fun daily(rangeDays: Int): List<UsageDaily> {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return emptyList()
        val cutoff = if (rangeDays <= 0) {
            Long.MIN_VALUE
        } else {
            // 含今天在内 rangeDays 个自然日的起点：今天 00:00 往前推 (rangeDays-1) 天。
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -(rangeDays - 1))
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        val byDay = LinkedHashMap<Long, UsageDaily>()
        for (rec in snapshot) {
            val dayStart = dayStartMillis(rec.timestamp)
            if (dayStart < cutoff) continue
            val cur = byDay[dayStart]
            byDay[dayStart] = if (cur == null) {
                UsageDaily(dayStart, rec.promptTokens, rec.completionTokens, rec.totalTokens, 1)
            } else {
                UsageDaily(
                    dayStart,
                    cur.promptTokens + rec.promptTokens,
                    cur.completionTokens + rec.completionTokens,
                    cur.totalTokens + rec.totalTokens,
                    cur.requests + 1
                )
            }
        }
        return byDay.values.sortedByDescending { it.dayEpochMillis }
    }

    /**
     * 模型维度分解：全库（不受时间范围影响）各模型的 token 总量、调用轮数与占比。
     * 按总量倒序；share = 该模型总量 / 全库总量（0..1）。
     */
    fun modelBreakdown(): List<UsageModelStat> {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return emptyList()
        var grandTotal = 0L
        // value = [totalTokens, requests, promptTokens, completionTokens]
        // —— longArrayOf 避免为每个模型建装箱累加器（#221：拆输入/输出供费率估算）
        val byModel = LinkedHashMap<String, LongArray>()
        for (rec in snapshot) {
            grandTotal += rec.totalTokens
            val cur = byModel[rec.modelId]
            byModel[rec.modelId] = longArrayOf(
                (cur?.get(0) ?: 0L) + rec.totalTokens,
                (cur?.get(1) ?: 0L) + 1L,
                (cur?.get(2) ?: 0L) + rec.promptTokens,
                (cur?.get(3) ?: 0L) + rec.completionTokens
            )
        }
        return byModel.map { (modelId, acc) ->
            UsageModelStat(
                modelId = modelId,
                totalTokens = acc[0],
                requests = acc[1].toInt(),
                share = if (grandTotal > 0) acc[0].toFloat() / grandTotal else 0f,
                promptTokens = acc[2],
                completionTokens = acc[3]
            )
        }.sortedByDescending { it.totalTokens }
    }

    /**
     * 全库汇总指标。
     *
     * - todayTokens：本地时区今日 00:00 起的累计；
     * - last7dTokens / last30dTokens：以当前时刻回溯 7×24h / 30×24h 的滚动窗口；
     * - avgTokensPerRequest：全库总量 / 总轮数（整除，无记录时为 0）。
     */
    fun totals(): UsageTotals {
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return UsageTotals(0, 0, 0, 0, 0, 0)
        val now = System.currentTimeMillis()
        val todayStart = dayStartMillis(now)
        val last7d = now - 7L * 24L * 60L * 60L * 1000L
        val last30d = now - 30L * 24L * 60L * 60L * 1000L
        var allTime = 0L
        var today = 0L
        var win7 = 0L
        var win30 = 0L
        for (rec in snapshot) {
            allTime += rec.totalTokens
            if (rec.timestamp >= todayStart) today += rec.totalTokens
            if (rec.timestamp >= last7d) win7 += rec.totalTokens
            if (rec.timestamp >= last30d) win30 += rec.totalTokens
        }
        val count = snapshot.size
        return UsageTotals(
            allTimeTokens = allTime,
            todayTokens = today,
            last7dTokens = win7,
            last30dTokens = win30,
            totalRequests = count,
            avgTokensPerRequest = if (count > 0) allTime / count else 0
        )
    }

    /**
     * 会话用量排行（全库，按 token 总量倒序，同量按最近活跃倒序）。
     *
     * @param limit 取前 N 个会话；`<= 0` 返回空
     */
    fun topSessions(limit: Int = 5): List<UsageSessionStat> {
        if (limit <= 0) return emptyList()
        val snapshot = snapshot()
        if (snapshot.isEmpty()) return emptyList()
        // value = [totalTokens, requests, firstAt, lastAt]
        val bySession = LinkedHashMap<String, LongArray>()
        for (rec in snapshot) {
            val cur = bySession[rec.sessionId]
            bySession[rec.sessionId] = longArrayOf(
                (cur?.get(0) ?: 0L) + rec.totalTokens,
                (cur?.get(1) ?: 0L) + 1L,
                minOf(cur?.get(2) ?: Long.MAX_VALUE, rec.timestamp),
                maxOf(cur?.get(3) ?: Long.MIN_VALUE, rec.timestamp)
            )
        }
        return bySession.map { (sessionId, acc) ->
            UsageSessionStat(
                sessionId = sessionId,
                totalTokens = acc[0],
                requests = acc[1].toInt(),
                firstAt = acc[2],
                lastAt = acc[3]
            )
        }.sortedWith(
            compareByDescending<UsageSessionStat> { it.totalTokens }.thenByDescending { it.lastAt }
        ).take(limit)
    }

    /**
     * 导出全量记录为 CSV（表头 timestamp,session,model,prompt,completion,total；
     * 时间为 ISO 8601 本地时区）。含逗号/引号/换行的字段按 RFC 4180 加引号转义。
     * 每次调用新建 SimpleDateFormat（其非线程安全，导出为低频路径，局部实例最省心）。
     */
    fun exportCsv(): String {
        val snapshot = snapshot()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val sb = StringBuilder("timestamp,session,model,prompt,completion,total\n")
        for (rec in snapshot) {
            sb.append(csvField(fmt.format(Date(rec.timestamp)))).append(',')
                .append(csvField(rec.sessionId)).append(',')
                .append(csvField(rec.modelId)).append(',')
                .append(rec.promptTokens).append(',')
                .append(rec.completionTokens).append(',')
                .append(rec.totalTokens).append('\n')
        }
        return sb.toString()
    }

    // ─────────────────────────────── 内部 ───────────────────────────────

    /** 锁内取全量快照拷贝 —— 所有读方法的唯一入口（快照语义 + 首读惰性加载）。 */
    private fun snapshot(): List<UsageRecord> = synchronized(ioLock) {
        ensureLoadedLocked()
        cache.toList()
    }

    /**
     * 惰性加载（#863，调用方须持 [ioLock]）：首次读账本时才解析文件——
     * @HiltViewModel 主线程构造零 IO；聚合方法本就跑在 Dispatchers.Default
     * 的后台协程里，一次性解析成本落在那里。
     *
     * 加载窗口内的新账经 [pendingAppend] 合并（已落盘的自然在文件里，不双计）；
     * 触发保留裁剪时原子重写文件并把 pending 一并吸收。
     */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        var diskRecords: List<UsageRecord> = emptyList()
        var corruptLines = 0
        // 文件不存在 → 空账本；单行损坏 → 跳过并计数（不阻断其余行）；
        // 文件级异常（IO 错误）→ 整体退化为空账本，绝不向上抛（账本不可拖垮宿主）。
        runCatching {
            if (file.isFile) {
                val parsed = ArrayList<UsageRecord>()
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    val rec = runCatching {
                        json.decodeFromString(UsageRecord.serializer(), trimmed)
                    }.getOrNull()
                    if (rec != null) parsed += rec else corruptLines++
                }
                diskRecords = parsed
            }
        }.onFailure { e ->
            AppLogger.instance.warn(
                LogCategory.LLM, "UsageLedger",
                "用量账本加载失败，本进程从空账本起步: ${e.message}"
            )
        }
        if (corruptLines > 0) {
            AppLogger.instance.warn(
                LogCategory.LLM, "UsageLedger",
                "用量账本发现 $corruptLines 行损坏记录，已跳过（其余照常加载）"
            )
        }

        // 滚动保留（#863）：磁盘侧先裁剪，再与未落盘的新账合并
        val pruned = applyRetention(diskRecords)
        val pending = pendingAppend.toList()
        val merged = ArrayList<UsageRecord>(pruned.size + pending.size)
        merged.addAll(pruned)
        merged.addAll(pending)
        cache = merged
        if (pruned.size != diskRecords.size) {
            // 有裁剪 → 原子重写（pending 一并写入）；成功后出册，对应追加协程
            // 自动跳过。失败则文件保持原状（pending 仍由追加协程正常落盘，
            // 内存已裁剪不损功能，下次进程加载再试裁剪）。
            if (rewriteFileLocked(merged)) {
                pendingAppend.clear()
            }
        }
    }

    /**
     * 滚动保留（#863）：保留「[RETENTION_MS] 时间窗内 ∪ 最近 [MAX_LINES] 行」
     * ——并集语义：中等体量用户零损失（行数窗内全保留），重度用户被行数封顶
     * （磁盘有界），长期休眠用户被时间窗收缩。空账本零动作。
     */
    private fun applyRetention(records: List<UsageRecord>): List<UsageRecord> {
        if (records.isEmpty()) return records
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val lineFloor = records.size - MAX_LINES
        return records.filterIndexed { index, rec ->
            index >= lineFloor || rec.timestamp >= cutoff
        }
    }

    /**
     * 保留裁剪后的整文件重写（tmp + rename 原子替换；失败留痕不抛）。
     *
     * @return true = 重写成功（调用方可吸收 pending）；false = 失败（文件保持原状）
     */
    private fun rewriteFileLocked(lines: List<UsageRecord>): Boolean =
        runCatching {
            usageDir.mkdirs()
            val tmp = File(usageDir, file.name + ".tmp")
            tmp.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                for (rec in lines) writer.write(json.encodeToString(rec) + "\n")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.onFailure { e ->
            AppLogger.instance.warn(
                LogCategory.LLM, "UsageLedger",
                "用量账本保留窗口重写失败（文件保持原状，下次加载重试裁剪）: ${e.message}"
            )
        }.getOrDefault(false)

    /** 时间戳 → 本地时区当日 00:00 的毫秒值（日聚合键）。 */
    private fun dayStartMillis(ts: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** RFC 4180 字段转义：仅当含逗号/引号/换行时加引号，内部引号翻倍。 */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    private companion object {
        /** 滚动保留：时间窗（90 天，#863 建议口径）。 */
        const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        /** 滚动保留：行数窗（50k 行 ≈ 7.5MB @150B/行，磁盘占用有界）。 */
        const val MAX_LINES = 50_000
    }
}
