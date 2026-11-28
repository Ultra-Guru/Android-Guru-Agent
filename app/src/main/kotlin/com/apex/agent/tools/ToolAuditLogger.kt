package com.apex.agent.tools

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * ═══ #F-⑯ 工具调用审计日志（结构化 JSONL）═══
 *
 * 问题背景：Agent 的 shell 命令在 Root / Shizuku / 沙箱三级降级链上执行时，
 * 每一步降级决策此前只有 Logcat 一行 D 级日志 —— 无法对外证明「agent 在 X
 * 场景下确实走了沙箱」，也无法事后归因权限决策。
 *
 * 设计：
 *  - 每次工具门决策 / shell 通道执行输出**一行 JSON**（JSONL，追加写），
 *    key 至少包含文档要求的 `tool / command / tier / decision / durationMs`；
 *  - 双写：本地文件（256KB 滚动，保留后半段）+ Logcat（I 级，tag `ToolAudit`）；
 *  - 文件位置：`<filesDir>/logs/tool-audit.jsonl`（与 hooks.log 同目录族）；
 *  - #336 纵深防御：`command` 字段写入前统一过 [SensitiveArgRedactor.redact]
 *    做字段级脱敏——api_key / token 等凭证值折叠为前后 4 字符，覆盖本类
 *    的全部调用方（风险门、shell 通道执行器等，含 curl 头里的
 *    Authorization: Bearer 形态）；
 *  - 线程安全：单 write 方法 + synchronized（审计写入本就不在热路径 ——
 *    每工具调用一次，远低于 hooks.log 的每次 POST_TOOL_USE 频率）；
 *  - 写失败静默（磁盘满/IO 异常不允许炸工具执行链路），仅 Logcat 告警。
 *
 * 与 HookRegistry 内置 `builtin-tool-usage-log` 的分工：hooks.log 记录
 * 工具调用的参数/结果预览（POST_TOOL_USE 事件）；本类记录**权限决策链**
 * （门放行/拒绝/用户选择 + 实际执行通道 tier + 时长）—— 两者互补不重叠。
 */
@Singleton
class ToolAuditLogger @Inject constructor(
    context: Context
) {
    private val logFile: File = File(File(context.filesDir, "logs"), "tool-audit.jsonl")
    private val lock = Any()

    /** 一次审计事件（字段对齐审计文档的最小 key 集 + 若干扩展）。 */
    data class Event(
        /** 工具 id（如 ShellExecute / TerminalExec / Write / <gate>）。 */
        val tool: String,
        /** 用户可见的决策结果（allow / deny / allow_session / allow_once / executed / failed / denied_by_user …）。 */
        val decision: String,
        /** 权限通道（root / shizuku / shell / proot-ubuntu / none）。 */
        val tier: String? = null,
        /** 命令原文（长命令截断到 512 字符 —— 审计需要可读性而非全文）。 */
        val command: String? = null,
        val durationMs: Long? = null,
        val success: Boolean? = null,
        val exitCode: Int? = null,
        val detail: String? = null,
        // ── DCP 设备控制面扩展（S10，尾部追加 + 默认值，历史 jsonl 仍可解析）──
        /** 意图 id（DeviceOps 专属；一次 dry_run→approved→executed 用同一 auditId 串联）。 */
        val intentId: String? = null,
        /** manifest 声明的风险级（READ / LOW / MEDIUM / HIGH / CRITICAL）。 */
        val risk: String? = null,
        /** 执行时的权限档位（ROOT / SHIZUKU / NORMAL_SHELL）。 */
        val privilegeTier: String? = null,
        /** 执行通道上报的 uid（如 2000）。 */
        val uid: Int? = null,
        /** 是否 dry-run（true = 只跑只读 preflight，零副作用）。 */
        val dryRun: Boolean? = null,
        /** 确认令牌指纹（只存前 8 位，不落完整令牌防重放）。 */
        val confirmToken: String? = null,
        /** preflight 解析出的 before 快照（截断）。 */
        val diffBefore: String? = null,
        /** verify 复探的 after 快照（截断）。 */
        val diffAfter: String? = null,
        /** 是否可逆（manifest 声明；purge=true 时执行面传 false）。 */
        val reversible: Boolean? = null,
        /** 回滚提示（manifest rollbackHint，面向模型）。 */
        val rollbackHint: String? = null,
        /** 本次操作的审计 id（多事件串联键）。 */
        val auditId: String? = null
    )

    /** 追加一条审计记录（文件 + Logcat 双写；失败静默降级为 Logcat-only）。 */
    fun log(event: Event) {
        val json = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("tool", event.tool)
            put("decision", event.decision)
            event.tier?.let { put("tier", it) }
            event.command?.let { put("command", SensitiveArgRedactor.redact(it).take(COMMAND_TRUNCATE)) }
            event.durationMs?.let { put("durationMs", it) }
            event.success?.let { put("success", it) }
            event.exitCode?.let { put("exitCode", it) }
            event.detail?.let { put("detail", it.take(DETAIL_TRUNCATE)) }
            // ── DCP 扩展字段（全部可选；历史行缺字段照样可读）──
            event.intentId?.let { put("intentId", it) }
            event.risk?.let { put("risk", it) }
            event.privilegeTier?.let { put("privilegeTier", it) }
            event.uid?.let { put("uid", it) }
            event.dryRun?.let { put("dryRun", it) }
            // 安全字段：confirmToken 只落指纹前 8 位（防重放，S10 任务清单）。
            event.confirmToken?.let { put("confirmToken", it.take(8)) }
            event.diffBefore?.let { put("diffBefore", it.take(DIFF_TRUNCATE)) }
            event.diffAfter?.let { put("diffAfter", it.take(DIFF_TRUNCATE)) }
            event.reversible?.let { put("reversible", it) }
            event.rollbackHint?.let { put("rollbackHint", it.take(DETAIL_TRUNCATE)) }
            event.auditId?.let { put("auditId", it) }
        }
        val line = json.toString()
        android.util.Log.i(TAG, line)
        synchronized(lock) {
            runCatching {
                logFile.parentFile?.mkdirs()
                rotateIfNeeded()
                FileOutputStream(logFile, true).use { it.write((line + "\n").toByteArray()) }
            }.onFailure {
                android.util.Log.w(TAG, "audit write failed: ${it.message}")
            }
        }
    }

    /** 超过上限保留后半段（审计通常更关心最近事件；与 hooks.log 的滚动策略同型）。 */
    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() <= MAX_BYTES) return
        val lines = runCatching { logFile.readLines() }.getOrNull() ?: return
        val kept = mutableListOf<String>()
        var size = 0L
        for (i in lines.indices.reversed()) {
            val l = lines[i]
            size += l.length + 1
            if (size > KEEP_BYTES) break
            kept.add(0, l)
        }
        logFile.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    // ═══ 读取侧（#诊断增强：诊断页 / SystemDiagnose 工具共用）═══
    // 与写入侧同一把锁 + 同一份文件 —— 读到的一定是已落盘的完整行。

    /**
     * 一条已落盘审计记录的反序列化视图（键集与 [Event] 写入侧一致；
     * 兼容缺字段 —— 旧版本行/异常行按可读默认值呈现，绝不炸读取方）。
     */
    data class AuditEntry(
        val ts: Long = 0L,
        val tool: String = "",
        val decision: String = "",
        val tier: String? = null,
        val command: String? = null,
        val durationMs: Long? = null,
        val success: Boolean? = null,
        val exitCode: Int? = null,
        val detail: String? = null,
        // ── DCP 扩展（S10；尾部追加 + 默认值，旧 jsonl 照常解析）──
        val intentId: String? = null,
        val risk: String? = null,
        val privilegeTier: String? = null,
        val uid: Int? = null,
        val dryRun: Boolean? = null,
        val confirmToken: String? = null,
        val diffBefore: String? = null,
        val diffAfter: String? = null,
        val reversible: Boolean? = null,
        val rollbackHint: String? = null,
        val auditId: String? = null
    ) {
        /** 失败判定：显式 success=false，或决策/拒绝类失败关键字。 */
        val isFailure: Boolean
            get() = success == false ||
                decision in FAILURE_DECISIONS ||
                decision.startsWith("deny")
    }

    /** 最近的审计记录（倒序：最新在前；limit 上限防大文件全量读）。 */
    fun recentEntries(limit: Int = 50): List<AuditEntry> = synchronized(lock) {
        if (!logFile.exists()) return emptyList()
        runCatching { logFile.readLines() }
            .getOrNull()
            ?.asReversed()
            ?.mapNotNull(::parseLine)
            ?.take(limit.coerceAtMost(MAX_READ_LINES))
            ?: emptyList()
    }

    /** 最近失败记录（先全量过滤再截断 —— 成功记录多时也不断档）。 */
    fun recentFailures(limit: Int = 50): List<AuditEntry> =
        recentEntries(MAX_READ_LINES).filter { it.isFailure }
            .take(limit.coerceAtMost(MAX_READ_LINES))

    /** 审计文件路径（诊断页文件区展示 / 分享用）。 */
    fun auditLogFile(): File = logFile

    private fun parseLine(line: String): AuditEntry? = parseLineInternal(line)

    // internal（非 private）：S10 兼容测试直接调 parseLineInternal 验证旧行可读。
    internal companion object {
        const val TAG = "ToolAudit"
        const val MAX_BYTES = 256L * 1024
        const val KEEP_BYTES = 128L * 1024
        const val COMMAND_TRUNCATE = 512
        const val DETAIL_TRUNCATE = 200
        /** DCP diff 快照截断（before/after 可读即可，防单条撑爆轮转预算）。 */
        const val DIFF_TRUNCATE = 256
        /** 读取上限（防异常大文件拖死诊断页；文件本体已被 rotate 压在 128KB 内）。 */
        const val MAX_READ_LINES = 2000
        /** 决策字段中的失败关键字（isFailure 判定，与写入侧 decision 词表对齐）。 */
        val FAILURE_DECISIONS = setOf("failed", "deny", "denied_by_user", "error", "timeout")

        /**
         * S10 兼容测试入口：旧格式（扩展字段缺席）与新格式 jsonl 均可解析；
         * JVM 直测无需 Context（ToolAuditLoggerCompatTest）。
         */
        internal fun parseLineInternal(line: String): AuditEntry? {
            if (line.isBlank()) return null
            return runCatching {
                val o = JSONObject(line)
                AuditEntry(
                    ts = o.optLong("ts", 0L),
                    tool = o.optString("tool", ""),
                    decision = o.optString("decision", ""),
                    tier = o.optString("tier").takeIf { it.isNotBlank() },
                    command = o.optString("command").takeIf { it.isNotBlank() },
                    durationMs = if (o.has("durationMs")) o.optLong("durationMs") else null,
                    success = if (o.has("success")) o.optBoolean("success") else null,
                    exitCode = if (o.has("exitCode")) o.optInt("exitCode") else null,
                    detail = o.optString("detail").takeIf { it.isNotBlank() },
                    intentId = o.optString("intentId").takeIf { it.isNotBlank() },
                    risk = o.optString("risk").takeIf { it.isNotBlank() },
                    privilegeTier = o.optString("privilegeTier").takeIf { it.isNotBlank() },
                    uid = if (o.has("uid")) o.optInt("uid") else null,
                    dryRun = if (o.has("dryRun")) o.optBoolean("dryRun") else null,
                    confirmToken = o.optString("confirmToken").takeIf { it.isNotBlank() },
                    diffBefore = o.optString("diffBefore").takeIf { it.isNotBlank() },
                    diffAfter = o.optString("diffAfter").takeIf { it.isNotBlank() },
                    reversible = if (o.has("reversible")) o.optBoolean("reversible") else null,
                    rollbackHint = o.optString("rollbackHint").takeIf { it.isNotBlank() },
                    auditId = o.optString("auditId").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }
}
