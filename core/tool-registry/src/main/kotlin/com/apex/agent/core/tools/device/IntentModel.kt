package com.apex.agent.core.tools.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ═══ DCP（Device Control Plane）设备控制面 —— 数据模型（S02）═══
 *
 * 单一事实源：[com.apex.agent.core.tools.device.IntentCatalog] 解析
 * app/src/main/assets/device 目录下的 intents.json 文件，产出本文件定义的
 * [DeviceIntent] 列表；risk / argv / preflight / verify / rollback 全部
 * 以 manifest 声明为准，Kotlin 侧不做第二套判定。
 *
 * 纯 JVM 纪律（对齐 docs/terminal/TERMINAL_SDK_BOUNDARY.md）：
 * 本包 **零 android.* import**，可被 JVM 单测穷举；Android 侧适配
 * （PrivilegeDetector / ShizukuCommandExecutor）由 app 层注入实现。
 */

/**
 * 设备操作可用的权限档位。rank 越高能力越强；manifest 的
 * `requiredPrivilege` 与能力自省（S01 [CapabilityProbe]）的当前档位
 * 都用本枚举比较 —— 档位不足时结构化拒绝并给开通步骤，绝不降级冒充。
 */
enum class DevicePrivilege(val rank: Int) {
    /** 普通应用 shell（app uid）：只能访问自己的沙箱。 */
    NORMAL_SHELL(0),

    /** Shizuku（uid=2000，等价 adb shell）：可执行 pm/am/settings 等系统命令。 */
    SHIZUKU(1),

    /** Root（uid=0）：全能档。 */
    ROOT(2);

    /** 当前档位是否满足 [required] 的档位要求。 */
    fun satisfies(required: DevicePrivilege): Boolean = rank >= required.rank
}

/** 意图风险分级（manifest 显式声明优先，禁止按 id 前缀猜）。 */
enum class DeviceRisk {
    /** 只读探针：零副作用。 */
    READ,

    /** 无危险：可逆且影响面极小。 */
    LOW,

    /** 平衡：可逆，但有可感知的状态变化。 */
    MEDIUM,

    /** 危险：破坏性或影响面大（可逆）。 */
    HIGH,

    /** 不可逆 / 系统级影响：恒 Ask（BYPASS 也不豁免）。 */
    CRITICAL
}

/**
 * 单个占位符参数的声明。kind 是封闭集合（见 [ParamKind]），渲染前按
 * kind 做白名单校验 —— 值永远以 **argv 元素** 形式传递，绝不拼进 shell。
 */
data class IntentParam(
    val name: String,
    val kind: String,
    val values: List<String> = emptyList(),
    val required: Boolean = true,
    val default: String? = null
)

/** [IntentParam.kind] 的封闭常量集（check_intents.py 与 ArgvRenderer 同口径）。 */
object ParamKind {
    /** Android 包名：^[A-Za-z0-9._]+$（ShellQuote.isValidPackageName 同款）。 */
    const val PACKAGE = "package"

    /** 枚举值：必须命中 [IntentParam.values] 白名单。 */
    const val ENUM = "enum"

    /** Android user id：^[0-9]{1,5}$（如 0 = 主用户）。 */
    const val USER = "user"

    /** settings / system key：^[A-Za-z0-9._:]+$（ShellQuote.isValidSettingKey 同款）。 */
    const val SETTING_KEY = "setting-key"

    /** 宿主绝对路径：/ 开头，拒绝 shell 元字符与控制字符；作用域另查 DevicePathPolicy。 */
    const val PATH = "path"

    /** 单行自由文本：拒绝 shell 元字符与换行，长度有上限。 */
    const val TEXT = "text"

    /** stdin 载荷：不进 argv（无注入面），仅限长度；执行面注入 stdinUtf8。 */
    const val PAYLOAD = "payload"

    /** 全部合法 kind（未知 kind 是 CatalogIssue）。 */
    val ALL = setOf(PACKAGE, ENUM, USER, SETTING_KEY, PATH, TEXT, PAYLOAD)
}

/**
 * 一条结构化设备意图（manifest 单条）。argv / preflight / verify 都是
 * **argv 模板**（占位符形如 `{package}`），渲染后逐元素直调，禁止 `sh -c` 拼接。
 */
data class DeviceIntent(
    val id: String,
    val argv: List<String>,
    val risk: DeviceRisk,
    val requiredPrivilege: DevicePrivilege,
    val reversible: Boolean,
    val preflight: List<List<String>> = emptyList(),
    val verify: List<List<String>> = emptyList(),
    val rollbackHint: String? = null,
    val blastRadius: String,
    val pathScope: Set<String>? = null,
    val guards: Set<String> = emptySet(),
    val params: List<IntentParam> = emptyList(),
    val internalParams: Set<String> = emptySet(),
    val stdinPayload: Boolean = false,
    val purgeArgv: List<String> = emptyList(),
    val requiresRealDeviceVerification: Boolean = false,
    val description: String? = null
) {
    /** 用户可提供的参数名集合。 */
    val paramNames: Set<String> get() = params.map { it.name }.toSet()

    /** argv / preflight / verify / purgeArgv 中出现的全部占位符名。 */
    val usedPlaceholders: Set<String> =
        (argv.asSequence() + purgeArgv.asSequence() +
            preflight.asSequence().flatten() + verify.asSequence().flatten())
            .flatMap { template ->
                template.split('{', '}').withIndex().asSequence()
            }
            .filter { it.index % 2 == 1 }
            .map { it.value }
            .toSet()
}

/** manifest 顶层 defaults 段。 */
data class CatalogDefaults(
    val requiredPrivilege: DevicePrivilege = DevicePrivilege.SHIZUKU,
    val confirmWindowMs: Long = 300_000L,
    val outputBudgetBytes: Long = 1_048_576L
)

/** 解析成功后的整表快照。 */
data class IntentCatalogSnapshot(
    val schema: String,
    val defaults: CatalogDefaults,
    val intents: List<DeviceIntent>
)

/**
 * 一条 manifest 校验问题（CI 门禁 scripts/check_intents.py 与运行时
 * [IntentCatalog.validate] 同口径）。`intentId == null` 表示表级问题。
 */
data class CatalogIssue(
    val intentId: String?,
    val field: String,
    val message: String
) {
    override fun toString(): String =
        if (intentId == null) "catalog.$field: $message"
        else "intent[$intentId].$field: $message"
}

/**
 * 一次 dry-run 预演产出（S04）：before = preflight 探针解析出的当前状态；
 * afterPredicted = 按意图族预测的执行后状态；flags 命中硬拒守卫即拒绝。
 */
data class DeviceDiff(
    val intentId: String,
    val before: Map<String, String?>,
    val afterPredicted: Map<String, String?>,
    val flags: Set<String>,
    val blastRadius: String,
    val argvPreview: List<String>,
    val fingerprint: String
) {
    /** 稳定指纹原料（不含时间戳 —— confirm 比对用，见 DevicePreflight）。 */
    internal fun stableCanonical(): String =
        intentId + "|" +
            before.entries.joinToString(",") { "${it.key}=${it.value}" } + "|" +
            flags.sorted().joinToString(",") + "|" +
            argvPreview.joinToString(" ")
}

/**
 * 一次设备操作的统一结果（S05/S06 执行面产出，DeviceOps 工具面透出）。
 * 失败语义诚实：ok=false 时 error 是结构化错误码（UNKNOWN_INTENT /
 * DEVICE_PERMISSION_REQUIRED / INVALID_PARAMS / PATH_DENIED / GUARD_REJECTED /
 * STALE_CONFIRM_TOKEN / CONFIRM_TOKEN_REQUIRED / DENIED_BY_USER /
 * EXEC_FAILED / VERIFY_FAILED / DCP_DISABLED），hint 是面向模型的中文指引。
 */
data class DeviceOpResult(
    val ok: Boolean,
    val intentId: String,
    val channel: String,
    val uid: Int?,
    val dryRun: Boolean,
    val diff: DeviceDiff? = null,
    val confirmToken: String? = null,
    val undoToken: String? = null,
    val verify: Map<String, String?> = emptyMap(),
    val rollbackHint: String? = null,
    val suggestion: String? = null,
    val auditId: String? = null,
    val rawOutput: String? = null,
    val error: String? = null,
    val hint: String? = null
) {
    /** 结构化 JSON 输出（面向 LLM；字段全部可选，防御式组装）。 */
    fun toJson(): String {
        fun mapJson(m: Map<String, String?>): JsonObject =
            JsonObject(m.mapValues { it.value?.let { v -> JsonPrimitive(v) } ?: JsonPrimitive(null as String?) })
        val root = buildJsonObject {
            put("ok", ok)
            put("intent", intentId)
            put("channel", channel)
            uid?.let { put("uid", it) }
            put("dry_run", dryRun)
            diff?.let { d ->
                put("diff", buildJsonObject {
                    put("before", mapJson(d.before))
                    put("afterPredicted", mapJson(d.afterPredicted))
                    put("flags", JsonArray(d.flags.sorted().map { JsonPrimitive(it) }))
                    put("blastRadius", d.blastRadius)
                    put("argv", JsonArray(d.argvPreview.map { JsonPrimitive(it) }))
                    put("fingerprint", d.fingerprint.take(8))
                })
            }
            confirmToken?.let { put("confirm_token", it) }
            undoToken?.let { put("undo_token", it) }
            put("verify", mapJson(verify))
            rollbackHint?.let { put("rollback_hint", it) }
            suggestion?.let { put("suggestion", it) }
            auditId?.let { put("audit_id", it) }
            rawOutput?.let { put("output", it) }
            error?.let { put("error", it) }
            hint?.let { put("hint", it) }
        }
        return root.toString()
    }
}

/**
 * 能力自省快照（S01）。supports / denies 由 [IntentCatalog] 按
 * requiredPrivilege 派生 —— LLM 执行前先问「我现在是谁、能做什么」。
 */
data class DeviceCapabilitySnapshot(
    val privilege: DevicePrivilege,
    val uid: Int?,
    val shizukuBinderAlive: Boolean,
    val shizukuGranted: Boolean,
    val shizukuApiLevel: Int?,
    val supports: Set<String>,
    val denies: Map<String, String>,
    val probedAtMillis: Long
)

/** 能力探针（app 层实现包装 PrivilegeDetector / ShizukuCommandExecutor）。 */
interface CapabilityProbe {
    fun snapshot(): DeviceCapabilitySnapshot
}

/**
 * 工具面到执行面的端口（S05）：DeviceOpsTool（core）只依赖本接口，
 * DeviceOpsExecutor（app）实现完整 preflight / gate / token / execute / verify 流程。
 */
interface DeviceOpsPort {
    /**
     * @param intentId intents.json 中声明的意图 id
     * @param params 用户参数（渲染前按 manifest 白名单校验）
     * @param dryRun true = 只跑只读 preflight 并返回 diff + confirm_token，零副作用
     * @param token dry_run 返回的确认令牌（不可逆意图执行必带）
     */
    suspend fun execute(
        intentId: String,
        params: Map<String, String>,
        dryRun: Boolean,
        token: String?
    ): DeviceOpResult

    /**
     * 旧设备工具（AppUninstall / AppForceStop，S05 委托）的单调用入口：
     * 完整走 preflight → 守卫 → gate Ask（弹窗展示 diff）→ 执行 → verify，
     * 但不要求 dry_run / confirm_token 两步 —— 与旧 CommandPermissionGate
     * 单弹窗形态对齐（弹窗里已展示完整影响面，用户确认即拍板）。
     */
    suspend fun executeLegacy(intentId: String, params: Map<String, String>): DeviceOpResult
}
