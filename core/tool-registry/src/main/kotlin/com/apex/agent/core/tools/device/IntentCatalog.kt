package com.apex.agent.core.tools.device

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ═══ 意图清单目录（S02 单一事实源）═══
 *
 * app 层读 assets/device 目录下的 intents.json 成 String 后注入
 * [load]；core 层零 Android 依赖。防御式解析：单条坏 intent **跳过并记
 * issue**，不让整表加载失败（对齐 40-MIGRATION 的回滚纪律）。
 *
 * 派生能力：
 * - [androidOnlyExecutables] —— S07 用它派生 TerminalExec 的
 *   Android 专有命令回落集合，消除手写清单与工具描述漂移；
 * - [supportsFor] / [deniesFor] —— S01 能力自省按 requiredPrivilege 过滤；
 * - [criticalIds] —— 不可逆意图集合（恒 Ask 的单一来源）；
 * - [validate] —— 与 scripts/check_intents.py 同口径的 CI 门禁输入。
 */
class IntentCatalog private constructor(
    val snapshot: IntentCatalogSnapshot,
    /** 解析期发现的非致命问题（跳过的坏 intent / 字段缺省回退）。 */
    val loadIssues: List<CatalogIssue>
) {
    private val byIdIndex: Map<String, DeviceIntent> =
        snapshot.intents.associateBy { it.id }

    fun byId(id: String): DeviceIntent? = byIdIndex[id]

    fun all(): List<DeviceIntent> = snapshot.intents

    /** argv[0] 集合（manifest 派生的 Android 专有可执行名，S07 消费）。 */
    fun androidOnlyExecutables(): Set<String> =
        snapshot.intents.map { it.argv.firstOrNull() }.filterNotNull().toSet() +
            snapshot.intents.flatMap { it.purgeArgv.firstOrNull()?.let { listOf(it) } ?: emptyList() }

    /** 不可逆（reversible=false）意图 id 集合。 */
    fun criticalIds(): Set<String> = snapshot.intents.filter { !it.reversible }.map { it.id }.toSet()

    /** 当前权限档位下可用的意图 id（supports 面板）。 */
    fun supportsFor(privilege: DevicePrivilege): List<String> =
        snapshot.intents.filter { privilege.satisfies(it.requiredPrivilege) }
            .map { it.id }

    /** 当前档位下不可用的意图与面向用户的开通指引（denies 面板）。 */
    fun deniesFor(privilege: DevicePrivilege): Map<String, String> =
        snapshot.intents
            .filter { !privilege.satisfies(it.requiredPrivilege) }
            .associate { it.id to denialHint(it.requiredPrivilege) }

    /** 结构校验（运行时兜底；CI 由 scripts/check_intents.py 同口径拦截）。 */
    fun validate(): List<CatalogIssue> {
        val issues = mutableListOf<CatalogIssue>()
        val seen = mutableSetOf<String>()
        for (intent in snapshot.intents) {
            val prefix = intent.id
            if (intent.id.isBlank() || !ID_REGEX.matches(intent.id)) {
                issues += CatalogIssue(prefix, "id", "id 必须是形如 package.uninstall 的小写点分名")
            }
            if (!seen.add(intent.id)) {
                issues += CatalogIssue(prefix, "id", "重复 id")
            }
            if (intent.argv.isEmpty()) {
                issues += CatalogIssue(prefix, "argv", "argv 不能为空")
            }
            if (intent.argv.isNotEmpty() && intent.argv.first() !in EXECUTABLE_WHITELIST) {
                issues += CatalogIssue(prefix, "argv[0]", "可执行名不在白名单：${intent.argv.first()}")
            }
            if (intent.purgeArgv.isNotEmpty() && intent.purgeArgv.first() !in PURGE_EXECUTABLE_WHITELIST) {
                issues += CatalogIssue(prefix, "purgeArgv[0]", "purge 可执行名不在白名单：${intent.purgeArgv.first()}")
            }
            if (!intent.reversible) {
                if (intent.preflight.isEmpty()) {
                    issues += CatalogIssue(prefix, "preflight", "reversible=false 的意图必须有非空 preflight")
                }
                if (intent.verify.isEmpty()) {
                    issues += CatalogIssue(prefix, "verify", "reversible=false 的意图必须有非空 verify")
                }
                if (intent.rollbackHint.isNullOrBlank()) {
                    issues += CatalogIssue(prefix, "rollbackHint", "reversible=false 的意图必须有非空 rollbackHint")
                }
            }
            for (placeholder in intent.usedPlaceholders) {
                if (placeholder !in intent.paramNames && placeholder !in intent.internalParams) {
                    issues += CatalogIssue(
                        prefix, "placeholders",
                        "占位符 {$placeholder} 未在 params 或 internal 中声明"
                    )
                }
            }
            for (param in intent.params) {
                if (param.kind !in ParamKind.ALL) {
                    issues += CatalogIssue(prefix, "params.${param.name}", "未知 kind：${param.kind}")
                }
                if (param.kind == ParamKind.ENUM && param.values.isEmpty()) {
                    issues += CatalogIssue(prefix, "params.${param.name}", "enum kind 必须提供 values")
                }
            }
            intent.pathScope?.forEach { prefixPath ->
                if (!prefixPath.startsWith("/")) {
                    issues += CatalogIssue(prefix, "pathScope", "pathScope 必须是绝对路径前缀：$prefixPath")
                }
            }
        }
        return issues
    }

    companion object {
        /** 合法意图 id：小写段 + 点分（如 package.uninstall_for_user）。 */
        private val ID_REGEX = Regex("^[a-z0-9_]+(\\.[a-z0-9_]+)+$")

        /**
         * argv[0] 白名单：只允许只读探针与声明过的系统管理命令。
         * **新增命令必须同步 scripts/check_intents.py**（两边同口径，
         * CI 会跑 Python 侧校验）。
         */
        val EXECUTABLE_WHITELIST: Set<String> = setOf(
            "am", "pm", "dumpsys", "cmd", "settings", "getprop", "setprop",
            "id", "df", "getenforce", "stat", "ls", "cat", "tee", "mv",
            "mkdir", "md5sum", "pidof"
        )

        /** purgeArgv[0] 白名单：硬删路径唯一允许的可执行名。 */
        val PURGE_EXECUTABLE_WHITELIST: Set<String> = setOf("rm")

        /** 空表（解析彻底失败时的兜底；supports 空 + denies 全量，诚实不冒充）。 */
        fun empty(): IntentCatalog = IntentCatalog(
            IntentCatalogSnapshot("apex-device-intents/v1", CatalogDefaults(), emptyList()),
            listOf(CatalogIssue(null, "catalog", "empty catalog"))
        )

        /**
         * 防御式加载：整表 JSON 解析失败 → 空表 + issue；单条坏 intent →
         * 跳过该条 + issue。成功路径产出 [IntentCatalog]。
         */
        fun load(json: String): IntentCatalog {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }
                .getOrElse {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, TAG,
                        "intents.json parse failed: ${it.message} — falling back to empty catalog"
                    )
                    return empty()
                }
            val defaults = parseDefaults(root)
            val rawIntents = (root["intents"] as? JsonArray) ?: run {
                AppLogger.instance.warn(
                    LogCategory.TOOL, TAG,
                    "intents.json missing intents array — falling back to empty catalog"
                )
                return IntentCatalog(
                    IntentCatalogSnapshot(schemaOf(root), defaults, emptyList()),
                    listOf(CatalogIssue(null, "intents", "缺少 intents 数组"))
                )
            }
            val issues = mutableListOf<CatalogIssue>()
            val intents = mutableListOf<DeviceIntent>()
            for ((index, element) in rawIntents.withIndex()) {
                val parsed = runCatching { parseIntent(element, defaults) }
                parsed.fold(
                    onSuccess = { intents += it },
                    onFailure = {
                        issues += CatalogIssue(
                            (element as? JsonObject)?.get("id")?.let { id ->
                                (id as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                            },
                            "intents[$index]",
                            "解析失败已跳过：${it.message}"
                        )
                    }
                )
            }
            issues += IntentCatalog(
                IntentCatalogSnapshot(schemaOf(root), defaults, intents), emptyList()
            ).validate()
            return IntentCatalog(IntentCatalogSnapshot(schemaOf(root), defaults, intents), issues)
        }

        private fun schemaOf(root: JsonObject): String =
            (root["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: "apex-device-intents/v1"

        private fun parseDefaults(root: JsonObject): CatalogDefaults {
            val d = root["defaults"] as? JsonObject ?: return CatalogDefaults()
            return CatalogDefaults(
                requiredPrivilege = (d["requiredPrivilege"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                    ?.let { raw -> runCatching { DevicePrivilege.valueOf(raw) }.getOrNull() }
                    ?: CatalogDefaults().requiredPrivilege,
                confirmWindowMs = (d["confirmWindowMs"] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?: CatalogDefaults().confirmWindowMs,
                outputBudgetBytes = (d["outputBudgetBytes"] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?: CatalogDefaults().outputBudgetBytes
            )
        }

        private fun parseIntent(element: JsonElement, defaults: CatalogDefaults): DeviceIntent {
            val o = element.jsonObject
            val id = o.str("id") ?: throw IllegalArgumentException("missing id")
            val argv = o.strArray("argv")
            if (argv.isEmpty()) throw IllegalArgumentException("empty argv")
            val risk = DeviceRisk.valueOf(o.str("risk") ?: throw IllegalArgumentException("missing risk"))
            val privilegeRaw = o.str("requiredPrivilege")
            val privilege = privilegeRaw?.let { runCatching { DevicePrivilege.valueOf(it) }.getOrNull() }
                ?: defaults.requiredPrivilege
            return DeviceIntent(
                id = id,
                argv = argv,
                risk = risk,
                requiredPrivilege = privilege,
                reversible = o.bool("reversible") ?: true,
                preflight = o.argvMatrix("preflight"),
                verify = o.argvMatrix("verify"),
                rollbackHint = o.str("rollbackHint"),
                blastRadius = o.str("blastRadius") ?: "",
                pathScope = o.strArray("pathScope").takeIf { it.isNotEmpty() }?.toSet(),
                guards = o.strArray("guards").toSet(),
                params = (o["params"] as? JsonArray)?.mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    IntentParam(
                        name = po.str("name") ?: return@mapNotNull null,
                        kind = po.str("kind") ?: ParamKind.TEXT,
                        values = po.strArray("values"),
                        required = po.bool("required") ?: true,
                        // default 允许空串（空 flag 语法："" = 不传该元素）——与 str() 的
                        // 非空语义不同，这里只排除 JSON null / 非字符串。
                        default = (po["default"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                    )
                } ?: emptyList(),
                internalParams = o.strArray("internal").toSet(),
                stdinPayload = o.bool("stdinPayload") ?: false,
                purgeArgv = o.strArray("purgeArgv"),
                requiresRealDeviceVerification = o.bool("requiresRealDeviceVerification") ?: false,
                description = o.str("description")
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

        private fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

        private fun JsonObject.strArray(key: String): List<String> =
            (this[key] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()

        private fun JsonObject.argvMatrix(key: String): List<List<String>> =
            (this[key] as? JsonArray)
                ?.mapNotNull { el -> (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } }
                ?: emptyList()

        private fun denialHint(required: DevicePrivilege): String = when (required) {
            DevicePrivilege.ROOT -> "该操作需要 ROOT（当前档位不足）。Magisk 等方式授权后再试。"
            DevicePrivilege.SHIZUKU -> "该操作需要 Shizuku（uid=2000）：启动 Shizuku 应用 → 在 Shizuku 内授权本应用 → 重新调用。"
            DevicePrivilege.NORMAL_SHELL -> "该操作无需额外权限，当前档位即可。"
        }

        private const val TAG = "IntentCatalog"
    }
}
