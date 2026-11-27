package com.apex.agent.tools

import com.apex.agent.core.tools.device.ArgvRenderer
import com.apex.agent.core.tools.device.DeviceDiff
import com.apex.agent.core.tools.device.DeviceIntent
import com.apex.agent.core.tools.device.IntentCatalog
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.DeviceExecResult

/**
 * ═══ DCP Preflight / DryRun / 确认令牌（S04）═══
 *
 * 不可逆意图执行前必须先 dry_run：
 * 1. 只跑 manifest 声明的**只读 argv 探针**（零副作用 —— 不执行任何写 argv）；
 * 2. 解析产出 [DeviceDiff]（before / flags / blastRadius / argvPreview）；
 * 3. `fingerprint = sign(intentId + before + flags + argvPreview)`（HMAC 注入）；
 * 4. `confirm_token = v1.<ts>.<sign(fingerprint|ts)>` —— 令牌绑定 diff 指纹
 *    + 时间窗（默认 5 分钟，manifest defaults.confirmWindowMs）；
 * 5. 执行提交时**重跑 probe 并比对指纹**：不一致返回 [ConfirmResult.Stale]
 *    并刷新 diff（TOCTOU 防护）；令牌过期 / 篡改返回 [ConfirmResult.Rejected]。
 */
class DevicePreflight(
    private val channel: DeviceChannel,
    private val catalog: IntentCatalog,
    private val clock: () -> Long,
    private val sign: (String) -> String,
    /** 本应用包名（IS_SELF 守卫的真值来源）。 */
    private val selfPackage: String
) {

    /** probe 的单条超时（探针都是短命令；超时如实标 incomplete）。 */
    private val probeTimeoutMs = 15_000L

    /**
     * 跑只读 preflight 探针并产出 diff。
     *
     * @return 失败（参数非法 / 探针通道失败）时 Result.failure，携带面向
     *   模型的中文错误文案。
     */
    suspend fun probe(intent: DeviceIntent, params: Map<String, String>): Result<DeviceDiff> {
        val merged = ArgvRenderer.withDefaults(intent, params)
        val argvErrors = ArgvRenderer.validateParams(intent, merged)
        if (argvErrors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(argvErrors.joinToString("；")))
        }
        val argvPreview = runCatching { ArgvRenderer.render(intent.argv, merged) }
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "argv 渲染失败")) }

        val before = LinkedHashMap<String, String?>()
        val readings = mutableListOf<DiffParsers.Reading>()
        var incomplete = false
        for (template in intent.preflight) {
            val argv = runCatching { ArgvRenderer.render(template, merged) }
                .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "探针渲染失败")) }
            val result = channel.execute(DeviceExecRequest(argv = argv.toTypedArray(), timeoutMs = probeTimeoutMs))
            val reading = DiffParsers.parse(argv, result.stdout, result.exitCode ?: -1)
            readings += reading
            if (result.error != null) {
                // 探针通道失败（未授权等）：如实标 incomplete，不编造字段。
                incomplete = true
                before["probeError"] = result.error
                before["probeHint"] = result.hint
            } else {
                before.putAll(reading.fields)
                if (reading.parseIncomplete) incomplete = true
            }
        }
        if (incomplete) before["parseIncomplete"] = "true"

        val targetPackage = merged["package"]
        val flags = buildSet {
            if (targetPackage != null && targetPackage == selfPackage) add(GuardFlag.IS_SELF)
            readings.forEach { r -> r.adminPackages.takeIf { it.isNotEmpty() }?.let { admins ->
                if (targetPackage != null && targetPackage in admins) add(GuardFlag.DEVICE_ADMIN)
            } }
            readings.forEach { r -> r.launcherPackage?.let { lp ->
                if (targetPackage != null && targetPackage == lp) add(GuardFlag.IS_LAUNCHER)
            } }
            if (before["systemApp"] == "true") add(GuardFlag.SYSTEM_APP)
        }

        val diff = DeviceDiff(
            intentId = intent.id,
            before = before,
            afterPredicted = predictAfter(intent, merged),
            flags = flags,
            blastRadius = intent.blastRadius,
            argvPreview = argvPreview,
            fingerprint = sign(stableSeed(intent.id, before, flags, argvPreview))
        )
        return Result.success(diff)
    }

    /** 为 diff 签发确认令牌（v1.<ts>.<sign(fingerprint|ts)>）。 */
    fun confirmToken(diff: DeviceDiff): String {
        val ts = clock()
        return "$TOKEN_VERSION.$ts.${sign("${diff.fingerprint}|$ts")}"
    }

    /**
     * 校验确认令牌：解析 → 时间窗检查 → 指纹比对（调用方重跑 probe 传入
     * 最新 freshDiff；不一致 = Stale 并把 freshDiff 交还给用户重新确认）。
     */
    fun confirm(token: String, freshDiff: DeviceDiff): ConfirmResult {
        val parts = token.split('.', limit = 3)
        if (parts.size != 3 || parts[0] != TOKEN_VERSION) {
            return ConfirmResult.Rejected("确认令牌格式不合法，请重新 dry_run 获取")
        }
        val ts = parts[1].toLongOrNull()
            ?: return ConfirmResult.Rejected("确认令牌时间戳不合法，请重新 dry_run 获取")
        val windowMs = catalog.snapshot.defaults.confirmWindowMs
        val now = clock()
        if (now - ts > windowMs) {
            return ConfirmResult.Rejected(
                "确认令牌已过期（窗口 ${windowMs / 1000}s，已过 ${((now - ts) / 1000)}s）；请重新 dry_run 获取新令牌"
            )
        }
        if (now < ts - ALLOWED_CLOCK_SKEW_MS) {
            return ConfirmResult.Rejected("确认令牌时间戳异常（超前），请重新 dry_run")
        }
        val expected = sign("${freshDiff.fingerprint}|$ts")
        if (expected != parts[2]) {
            return ConfirmResult.Stale(freshDiff)
        }
        return ConfirmResult.Valid
    }

    /** 执行 verify 探针（执行后复探），产出 after 字段集。 */
    suspend fun verify(intent: DeviceIntent, params: Map<String, String>): Map<String, String?> {
        if (intent.verify.isEmpty()) return emptyMap()
        val merged = ArgvRenderer.withDefaults(intent, params)
        val after = LinkedHashMap<String, String?>()
        for (template in intent.verify) {
            val argv = runCatching { ArgvRenderer.render(template, merged) }.getOrNull() ?: continue
            val result = channel.execute(DeviceExecRequest(argv = argv.toTypedArray(), timeoutMs = probeTimeoutMs))
            if (result.error != null) {
                after["verifyError"] = result.error
                continue
            }
            val reading = DiffParsers.parse(argv, result.stdout, result.exitCode ?: -1)
            after.putAll(reading.fields)
        }
        return after
    }

    /** 按意图族预测执行后状态（与 verify 复探结果比对用）。 */
    private fun predictAfter(intent: DeviceIntent, params: Map<String, String>): Map<String, String?> = when (intent.id) {
        "package.uninstall", "package.uninstall_for_user" -> mapOf("installed" to "false")
        "package.install_existing", "package.enable" -> mapOf("installed" to "true")
        "package.disable_user" -> mapOf("installed" to "true", "disabled" to "true")
        "package.force_stop" -> mapOf("running" to "false")
        "file.delete" -> mapOf("exists" to "false")
        "file.undo" -> mapOf("exists" to "true")
        "file.mkdir" -> mapOf("exists" to "true")
        "file.move" -> mapOf("name" to (params["dest"] ?: "unknown"))
        "file.write" -> mapOf("exists" to "true", "size" to "changed")
        "system.settings_put", "system.setprop" -> mapOf("value" to (params["value"] ?: "unknown"))
        "system.appops" -> mapOf("mode" to (params["mode"] ?: "unknown"))
        else -> emptyMap()
    }

    private fun stableSeed(
        intentId: String,
        before: Map<String, String?>,
        flags: Set<String>,
        argvPreview: List<String>
    ): String = intentId + "|" +
        before.entries.joinToString(",") { "${it.key}=${it.value}" } + "|" +
        flags.sorted().joinToString(",") + "|" + argvPreview.joinToString(" ")

    internal companion object {
        const val TOKEN_VERSION = "v1"

        /** 允许的时钟前后偏差（设备时间跳变防御；超出按篡改拒绝）。 */
        internal const val ALLOWED_CLOCK_SKEW_MS = 60_000L
    }
}

/** 守卫 flag 常量（S04 硬拒口径：IS_SELF / DEVICE_ADMIN / IS_LAUNCHER 不可 BYPASS）。 */
internal object GuardFlag {
    const val IS_SELF = "IS_SELF"
    const val DEVICE_ADMIN = "DEVICE_ADMIN"
    const val IS_LAUNCHER = "IS_LAUNCHER"
    const val SYSTEM_APP = "SYSTEM_APP"
}

/** confirm 校验结果（S04 契约）。 */
sealed class ConfirmResult {
    object Valid : ConfirmResult()
    data class Stale(val freshDiff: DeviceDiff) : ConfirmResult()
    data class Rejected(val reason: String) : ConfirmResult()
}
