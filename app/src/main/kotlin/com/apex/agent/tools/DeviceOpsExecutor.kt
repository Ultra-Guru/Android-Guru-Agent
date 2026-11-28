package com.apex.agent.tools

import com.apex.agent.core.engine.DeviceGateDecision
import com.apex.agent.core.engine.DeviceGateMode
import com.apex.agent.core.engine.DeviceGateRisk
import com.apex.agent.core.engine.DeviceIntentGate
import com.apex.agent.core.tools.device.ArgvRenderer
import com.apex.agent.core.tools.device.CapabilityProbe
import com.apex.agent.core.tools.device.DeviceCapabilitySnapshot
import com.apex.agent.core.tools.device.DeviceDiff
import com.apex.agent.core.tools.device.DeviceIntent
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.core.tools.device.DeviceOpResult
import com.apex.agent.core.tools.device.DevicePrivilege
import com.apex.agent.core.tools.device.DeviceRisk
import com.apex.agent.core.tools.device.IntentCatalog
import com.apex.agent.core.tools.device.ParamKind
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.DeviceExecResult

/**
 * ═══ DCP 唯一写执行点（S05/S06/S07 编排层）═══
 *
 * 一次 DeviceOps 调用的完整流程（四条不变式的落地）：
 *
 *  1. feature flag：`dcp.enabled=false` → 结构化 `DCP_DISABLED`（不静默
 *     降级旧链，审计链不断）；
 *  2. 意图查表（manifest 单一事实源，未知意图结构化拒绝）；
 *  3. 能力自省（S01）：档位不足 → `DEVICE_PERMISSION_REQUIRED` + 开通步骤；
 *  4. 参数渲染（S02）：withDefaults → validateParams → 路径白名单（S06）；
 *  5. preflight（S04）：只读探针 → DeviceDiff（I1：不可逆必有预演）；
 *  6. 守卫硬拒（I3）：IS_SELF / DEVICE_ADMIN / IS_LAUNCHER 命中即拒，
 *     不可 BYPASS；
 *  7. dry_run：零副作用返回 diff + confirm_token；
 *  8. token 流（I2）：不可逆意图（含 purge 变体）执行前必带 token；
 *     重跑 preflight 比对指纹（TOCTOU），Stale 时刷新 diff 交还用户；
 *  9. gate（S03）：语义分级 Ask（CRITICAL 恒问）；
 * 10. 执行：argv 直调（S08 通道；file.* 走 FileOpsRunner 的软删/备份语义）；
 * 11. verify（S04）：执行后复探，与预测比对；
 * 12. 审计（S10）：preflight / approved / executed|denied|failed 同 auditId 串联。
 *
 * 自由文本 ShellExecute / TerminalExec **不经本类**（逃生舱保留，零回归）。
 */
class DeviceOpsExecutor(
    private val catalog: IntentCatalog,
    private val channel: DeviceChannel,
    private val preflight: DevicePreflight,
    private val capability: CapabilityProbe,
    private val gate: DeviceIntentGate,
    private val pathPolicy: DevicePathPolicy,
    private val trash: DeviceTrash,
    private val fileOps: FileOpsRunner,
    private val audit: DeviceAuditSink,
    private val selfPackage: String,
    private val enabled: () -> Boolean,
    private val auditIdGen: () -> String = { "dcp-" + java.util.UUID.randomUUID().toString().substring(0, 8) },
    private val now: () -> Long = System::currentTimeMillis
) : DeviceOpsPort {

    override suspend fun execute(
        intentId: String,
        params: Map<String, String>,
        dryRun: Boolean,
        token: String?
    ): DeviceOpResult {
        if (!enabled()) return disabled(intentId)
        val intent = catalog.byId(intentId) ?: return unknownIntent(intentId)
        return runIntent(intent, params, dryRun, token, legacy = false)
    }

    /**
     * 旧设备工具（AppUninstall / AppForceStop）的单调用委托入口：完整走
     * preflight → 守卫 → gate Ask（弹窗展示 diff）→ 执行 → verify，但**不要求
     * dry_run/confirm_token 两步** —— 与旧 CommandPermissionGate 单弹窗形态
     * 对齐（弹窗里已展示完整影响面，用户确认即拍板）。
     */
    override suspend fun executeLegacy(intentId: String, params: Map<String, String>): DeviceOpResult {
        if (!enabled()) return disabled(intentId)
        val intent = catalog.byId(intentId) ?: return unknownIntent(intentId)
        return runIntent(intent, params, dryRun = false, token = null, legacy = true)
    }

    private suspend fun runIntent(
        intent: DeviceIntent,
        params: Map<String, String>,
        dryRun: Boolean,
        token: String?,
        legacy: Boolean
    ): DeviceOpResult {
        val auditId = auditIdGen()

        // ── 3. 能力自省：档位不足结构化拒绝（I6：诚实报出拿不到）。──
        val cap = runCatching { capability.snapshot() }
            .getOrElse { e -> return failed(intent.id, "PROBE_FAILED", "能力探测失败：${e.message}") }
        if (!cap.privilege.satisfies(intent.requiredPrivilege)) {
            return permissionRequired(intent, cap)
        }

        // ── 4. 参数渲染：defaults → 白名单校验 → 路径作用域。──
        val merged = ArgvRenderer.withDefaults(intent, params)
        val argvErrors = ArgvRenderer.validateParams(intent, merged)
        if (argvErrors.isNotEmpty()) {
            return failed(intent.id, "INVALID_PARAMS", argvErrors.joinToString("；"))
        }
        val write = intent.risk != DeviceRisk.READ
        for (param in intent.params) {
            if (param.kind == ParamKind.PATH) {
                val value = merged.getValue(param.name)
                val decision = pathPolicy.check(value, write = write)
                if (decision is DevicePathPolicy.Decision.Deny) {
                    return failed(intent.id, "PATH_DENIED", decision.reason)
                }
            }
        }

        // ── 内部占位符解析（auditId / basename / undo token）。──
        val purge = merged["purge"] == "purge"
        var resolved = merged
        if (intent.id == "file.delete") {
            resolved = merged + mapOf(
                "auditId" to auditId,
                "basename" to pathPolicy.basename(merged.getValue("path"))
            )
        } else if (intent.id == "file.undo") {
            val entry = trash.resolveToken(merged.getValue("undo_token"))
                ?: return failed(
                    intent.id, "INVALID_UNDO_TOKEN",
                    "undo_token 无效（不存在 / 回收区已被清理）。可用 file.list /data/local/tmp/.dcp-trash 查看回收区"
                )
            resolved = merged + mapOf(
                "auditId" to entry.auditId,
                "basename" to entry.basename,
                "path" to entry.originalPath
            )
        }

        // ── 5. preflight 只读探针 → DeviceDiff。──
        val probe = preflight.probe(intent, resolved)
        val diff = probe.getOrElse { e ->
            return failed(intent.id, "INVALID_PARAMS", e.message ?: "preflight 渲染失败")
        }

        // ── 6. 守卫硬拒（I3：不可 BYPASS）。──
        val guardHit = intent.guards intersect diff.flags
        if (guardHit.isNotEmpty()) {
            return guardRejected(intent, diff, guardHit, auditId)
        }

        // ── 7. dry_run：零副作用返回。──
        if (dryRun) {
            audit.log(auditEvent(intent, cap, auditId, decision = "preflight", dryRun = true, diff = diff))
            return DeviceOpResult(
                ok = true, intentId = intent.id, channel = "dry-run", uid = cap.uid, dryRun = true,
                diff = diff,
                confirmToken = preflight.confirmToken(diff),
                rollbackHint = intent.rollbackHint,
                suggestion = suggestionFor(intent, diff),
                auditId = auditId
            )
        }

        // ── 8. token 流（不可逆 / purge 变体必带 token；legacy 单弹窗流程豁免两步）。──
        val effectiveReversible = intent.reversible && !purge
        if (!effectiveReversible && !legacy) {
            if (token == null) {
                return failed(
                    intent.id, "CONFIRM_TOKEN_REQUIRED",
                    "该操作不可逆，必须先 dry_run 预演拿到 confirm_token，再带 token 重新调用"
                )
            }
            when (val c = preflight.confirm(token, diff)) {
                is ConfirmResult.Rejected -> return failed(intent.id, "CONFIRM_TOKEN_REJECTED", c.reason)
                is ConfirmResult.Stale -> {
                    // TOCTOU：状态已变 —— 刷新 diff 交还用户重新确认（不执行）。
                    return DeviceOpResult(
                        ok = false, intentId = intent.id, channel = "dry-run", uid = cap.uid, dryRun = true,
                        diff = c.freshDiff,
                        confirmToken = preflight.confirmToken(c.freshDiff),
                        rollbackHint = intent.rollbackHint,
                        error = "STALE_CONFIRM_TOKEN",
                        hint = "预演结果已变化（状态与签发 token 时不一致），已刷新 diff，请让用户重新确认后带新 token 再执行",
                        auditId = auditId
                    )
                }
                ConfirmResult.Valid -> Unit
            }
        }

        // ── 9. gate：语义分级决策（CRITICAL 恒 Ask，BYPASS 不豁免）。──
        val decision = gate.decide(
            intentId = intent.id,
            risk = toGateRisk(intent.risk),
            reversible = effectiveReversible,
            dryRun = false,
            description = describeForUser(intent, diff)
        )
        if (decision is DeviceGateDecision.Deny) {
            audit.log(
                auditEvent(intent, cap, auditId, decision = "denied_by_user", diff = diff)
                    .copy(confirmToken = token)
            )
            return DeviceOpResult(
                ok = false, intentId = intent.id, channel = "none", uid = cap.uid, dryRun = false,
                diff = diff, rollbackHint = intent.rollbackHint,
                error = "DENIED_BY_USER", hint = decision.reason, auditId = auditId
            )
        }
        audit.log(
            auditEvent(intent, cap, auditId, decision = "approved", diff = diff)
                .copy(confirmToken = token, reversible = effectiveReversible)
        )

        // ── 10. 执行（argv 直调；file.* 走 FileOpsRunner）。──
        val startedAt = now()
        val argv = runCatching {
            if (purge && intent.purgeArgv.isNotEmpty()) {
                ArgvRenderer.render(intent.purgeArgv, resolved)
            } else {
                ArgvRenderer.render(intent.argv, resolved)
            }
        }.getOrElse { return failed(intent.id, "INVALID_PARAMS", it.message ?: "argv 渲染失败") }

        val exit: DeviceExecResult = if (intent.id.startsWith("file.")) {
            fileOps.run(intent, resolved, argv, auditId, diff).exit
        } else if (intent.id.startsWith("package.")) {
            packageOps.run(intent, resolved, argv)
        } else {
            channel.execute(
                DeviceExecRequest(
                    argv = argv.toTypedArray(),
                    stdinUtf8 = if (intent.stdinPayload) resolved["content"] else null,
                    timeoutMs = EXEC_TIMEOUT_MS,
                    maxOutputBytes = catalog.snapshot.defaults.outputBudgetBytes
                )
            )
        }

        if (exit.error != null && !exit.ok) {
            audit.log(
                auditEvent(intent, cap, auditId, decision = "failed", diff = diff, success = false)
                    .copy(durationMs = now() - startedAt)
            )
            return DeviceOpResult(
                ok = false, intentId = intent.id, channel = exit.channel, uid = cap.uid, dryRun = false,
                diff = diff, rollbackHint = intent.rollbackHint,
                rawOutput = trimOutput(exit),
                error = exit.error, hint = exit.hint, auditId = auditId
            )
        }

        // ── 11. verify 复探 + 预测比对。──
        val after = preflight.verify(intent, resolved)
        val verifyOk = comparePredicted(diff.afterPredicted, after)

        // ── 12. 审计 executed（success=执行与复探联合判定）。──
        val ok = exit.ok && verifyOk
        audit.log(
            auditEvent(intent, cap, auditId, decision = "executed", diff = diff, success = ok)
                .copy(diffAfter = after.entries.joinToString(",") { "${it.key}=${it.value}" }
                    .take(256), durationMs = now() - startedAt, exitCode = exit.exitCode)
        )

        return DeviceOpResult(
            ok = ok, intentId = intent.id, channel = exit.channel, uid = cap.uid, dryRun = false,
            diff = diff, undoToken = undoTokenFor(intent, resolved, exit),
            verify = after, rollbackHint = intent.rollbackHint,
            suggestion = suggestionFor(intent, diff),
            auditId = auditId, rawOutput = trimOutput(exit),
            error = if (ok) null else "VERIFY_FAILED",
            hint = if (ok) null else "执行通道已返回（exit=${exit.exitCode}），但执行后复探与预测不一致 —— 请查看 verify 字段，必要时手动核对或使用 rollbackHint 恢复"
        )
    }

    private val packageOps = PackageOpsRunner(channel, selfPackage)

    // ═══ 结构化失败组装 ═══

    private fun disabled(intentId: String): DeviceOpResult = DeviceOpResult(
        ok = false, intentId = intentId, channel = "none", uid = null, dryRun = false,
        error = "DCP_DISABLED",
        hint = "设备控制面未启用；可在设置 → 实验能力开启，或改用 ShellExecute 执行单条命令。"
    )

    private fun unknownIntent(intentId: String): DeviceOpResult = DeviceOpResult(
        ok = false, intentId = intentId, channel = "none", uid = null, dryRun = false,
        error = "UNKNOWN_INTENT",
        hint = "未知意图 '$intentId'。可用意图见 DeviceOps 工具描述的 intent 枚举（由 intents.json 派生）。"
    )

    private fun permissionRequired(intent: DeviceIntent, cap: DeviceCapabilitySnapshot): DeviceOpResult {
        val reason = when (intent.requiredPrivilege) {
            DevicePrivilege.ROOT -> "该操作需要 ROOT，当前档位 ${cap.privilege}（uid=${cap.uid}）拿不到 —— 不冒充成功。"
            else -> "该操作需要 ${intent.requiredPrivilege}（uid=2000 ADB 级），当前档位 ${cap.privilege}（uid=${cap.uid}）。"
        }
        return DeviceOpResult(
            ok = false, intentId = intent.id, channel = "none", uid = cap.uid, dryRun = false,
            error = "DEVICE_PERMISSION_REQUIRED",
            hint = reason + "开通步骤：启动 Shizuku 应用 → 在 Shizuku 内授权本应用 → 重新调用；或让用户以 Root 授权。"
        )
    }

    private fun guardRejected(
        intent: DeviceIntent,
        diff: DeviceDiff,
        guardHit: Set<String>,
        auditId: String
    ): DeviceOpResult = DeviceOpResult(
        ok = false, intentId = intent.id, channel = "none", uid = null, dryRun = false,
        diff = diff, rollbackHint = intent.rollbackHint,
        error = "GUARD_REJECTED",
        hint = guardHint(guardHit),
        auditId = auditId
    )

    private fun guardHint(guardHit: Set<String>): String = buildString {
        append("预检命中硬拒守卫（不可跳过）：")
        guardHit.sorted().forEach { flag ->
            append(
                when (flag) {
                    GuardFlag.IS_SELF -> "【IS_SELF】目标是本应用自身，卸载/停用自己会让 App 当场消失；"
                    GuardFlag.DEVICE_ADMIN -> "【DEVICE_ADMIN】目标是设备管理器应用，停用/卸载会导致锁屏等策略异常；请先在系统设置中移除其管理员权限；"
                    GuardFlag.IS_LAUNCHER -> "【IS_LAUNCHER】目标是当前默认桌面应用，卸载/停用后设备将无桌面可用；请先切换默认桌面；"
                    else -> "【$flag】；"
                }
            )
        }
        append("请改用其他方案（如 package.disable_user 停用非守卫目标）并告知用户原因。")
    }

    private fun failed(intentId: String, error: String, hint: String): DeviceOpResult = DeviceOpResult(
        ok = false, intentId = intentId, channel = "none", uid = null, dryRun = false,
        error = error, hint = hint
    )

    // ═══ 组装辅助 ═══

    private fun toGateRisk(risk: DeviceRisk): DeviceGateRisk = when (risk) {
        DeviceRisk.READ -> DeviceGateRisk.READ
        DeviceRisk.LOW -> DeviceGateRisk.LOW
        DeviceRisk.MEDIUM -> DeviceGateRisk.MEDIUM
        DeviceRisk.HIGH -> DeviceGateRisk.HIGH
        DeviceRisk.CRITICAL -> DeviceGateRisk.CRITICAL
    }

    /** Ask 弹窗 / 审计共用的影响面描述（S04：用户点允许前必须看到要动什么）。 */
    private fun describeForUser(intent: DeviceIntent, diff: DeviceDiff): String = buildString {
        append("影响面：").append(intent.blastRadius)
        append("\n当前状态：")
        diff.before.entries.take(8).forEach { (k, v) -> append("\n  $k = $v") }
        if (diff.flags.isNotEmpty()) append("\n标记：").append(diff.flags.joinToString(" "))
        append("\n将执行：").append(diff.argvPreview.joinToString(" "))
        if (!intent.reversible) append("\n⚠ 不可逆操作")
    }

    private fun suggestionFor(intent: DeviceIntent, diff: DeviceDiff): String? = when {
        intent.id == "package.uninstall" && GuardFlag.SYSTEM_APP in diff.flags ->
            "目标是系统/预装应用：pm uninstall 可能无效。建议 package.uninstall_for_user（对指定用户卸载）或 package.disable_user（可逆停用，最安全）。"
        intent.id == "package.uninstall_for_user" && GuardFlag.SYSTEM_APP !in diff.flags ->
            "目标是三方应用：package.uninstall（常规卸载）即可，无需 --user 变体。"
        else -> null
    }

    private fun undoTokenFor(intent: DeviceIntent, resolved: Map<String, String>, exit: DeviceExecResult): String? =
        if (intent.id.startsWith("file.") && exit.ok) resolved["auditId"] else null

    private fun comparePredicted(predicted: Map<String, String?>, after: Map<String, String?>): Boolean {
        for ((key, expected) in predicted) {
            if (expected == null || expected == "unknown" || expected == "changed") continue
            if (after[key] != expected) return false
        }
        return true
    }

    private fun trimOutput(exit: DeviceExecResult): String? {
        val errPart = exit.stderr.takeIf { it.isNotBlank() }?.let { "\n[stderr]\n$it" } ?: ""
        val out = (exit.stdout + errPart).trim()
        return out.takeIf { it.isNotEmpty() }?.take(2048)
    }

    private fun auditEvent(
        intent: DeviceIntent,
        cap: DeviceCapabilitySnapshot,
        auditId: String,
        decision: String,
        diff: DeviceDiff?,
        success: Boolean? = null,
        dryRun: Boolean = false
    ): ToolAuditLogger.Event = ToolAuditLogger.Event(
        tool = "DeviceOps",
        decision = decision,
        tier = cap.privilege.name,
        command = diff?.argvPreview?.joinToString(" "),
        success = success,
        intentId = intent.id,
        risk = intent.risk.name,
        privilegeTier = cap.privilege.name,
        uid = cap.uid,
        dryRun = dryRun,
        diffBefore = diff?.before?.entries?.joinToString(",") { "${it.key}=${it.value}" }?.take(256),
        reversible = intent.reversible,
        rollbackHint = intent.rollbackHint,
        auditId = auditId
    )

    private companion object {
        const val EXEC_TIMEOUT_MS = 30_000L
    }
}

/**
 * 审计接收器（fun interface）：[ToolAuditLogger] 经 DI 适配进执行面；
 * JVM 测试用手写 recording fake（无 mock 框架纪律）。
 */
fun interface DeviceAuditSink {
    fun log(event: ToolAuditLogger.Event)
}
