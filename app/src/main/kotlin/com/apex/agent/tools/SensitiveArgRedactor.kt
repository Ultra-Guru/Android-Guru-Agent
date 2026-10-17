package com.apex.agent.tools

/**
 * ═══ 工具参数敏感字段脱敏器（issue #336）═══
 *
 * 背景：`agent_provider_set_key` 等凭据类工具的参数含明文 API Key，此前被
 * `RiskAwareToolGate` 原样截断后写入 tool-audit.jsonl 审计日志 + Logcat，
 * 并在用户确认弹窗（`RiskAwareToolGate` / `GateDialogStrings` 的参数摘要）
 * 中全文展示——与 SettingsRepository 用 EncryptedSharedPreferences +
 * Keystore 防明文落盘的努力直接冲突：同一威胁模型（root / adb backup /
 * 肩窥 / 录屏）下审计文件等价泄漏。
 *
 * 本对象做**字段级**掩码：只识别凭证类字段名（api_key / token / secret /
 * password / authorization / bearer 等，不区分大小写，含 x-api-key、
 * access_token 等常见复合变体），其余字段一律原样保留——确认弹窗里
 * sms_send 的 message、phone 与 agent_provider_set_key 的 provider_id
 * 必须保持明文（用户必须能看到将发送什么短信、给哪个 provider 写 key），
 * 只掩凭证值。
 *
 * 掩码规则（按 #336 修复要求）：
 * - 值长度 ≤ 8：整体替换为 `***`；
 * - 更长的值：保留前 4 后 4 字符，中间以省略号代替
 *   （`sk-abcdef123456` 折叠为 `sk-a…3456`）——保留前后缀便于用户辨认
 *   是哪把 key（sk- / ghp_ 等发行方前缀），又不泄露完整凭据。
 *
 * 覆盖形态（正则尽力而为，不求全；嵌套转义 JSON 等极端形态允许漏网）：
 * - 标准 JSON：`"api_key": "sk-..."`（键值双引号 / 单引号 / 混搭皆可）；
 * - 类 JSON 变体：无引号键 + 冒号 + 裸值（`api_key: sk-...`）；
 * - 纯文本 k=v：等号形态（URL 查询串 / 命令行参数，`api_key=sk-...`）；
 * - HTTP 头形态：`Authorization: Bearer xxx`（Bearer 前缀可选）。
 *
 * 防御式契约：任何内部异常一律折叠为「原样返回」——脱敏逻辑绝不允许
 * 炸掉审计链路（宁可审计多写一段明文，也不能因脱敏失败丢审计记录）。
 */
object SensitiveArgRedactor {

    /** 短值（≤8 字符）整体掩码。 */
    internal const val MASK = "***"

    /** 长值中间的省略号占位。 */
    private const val ELLIPSIS = "…"

    /** 短值阈值：不超过该长度的值整体替换为掩码。 */
    private const val SHORT_VALUE_MAX = 8

    /** 长值保留的前缀 / 后缀字符数。 */
    private const val KEEP_EDGES = 4

    /**
     * 凭证类字段名备选集（不区分大小写）。复合变体（access_token /
     * secret_key / x-api-key 等）与基础名单都列出——正则备选回溯保证
     * 长名优先命中，不会被 `key` / `token` 短名截断后失配。
     * 该集用于引号值形态与 k=v 形态。
     */
    private const val FIELDS_QUOTED =
        "access_token|refresh_token|auth_token|session_token|id_token|" +
            "secret_key|api_secret|client_secret|private_key|access_key|" +
            "x-api-key|api_key|apikey|api-key|key|token|secret|password|" +
            "authorization|bearer"

    /**
     * 裸值形态的备选集：比 [FIELDS_QUOTED] 少 `authorization`——它的裸值
     * 形态由 [AUTH_HEADER] 单独处理（要吃掉可选的 Bearer 前缀再掩真正的
     * token），若两处都匹配会互相改写对方的输出。
     */
    private const val FIELDS_BARE =
        "access_token|refresh_token|auth_token|session_token|id_token|" +
            "secret_key|api_secret|client_secret|private_key|access_key|" +
            "x-api-key|api_key|apikey|api-key|key|token|secret|password|bearer"

    /**
     * HTTP 头形态：`Authorization: Bearer xxx`（Bearer 前缀可选，token 至少
     * 8 字符才值得掩）。必须先于 [JSON_BARE] 执行——否则裸值模式会把
     * Bearer 一词单独吃成掩码、漏掉真正的 token。
     */
    private val AUTH_HEADER = Regex(
        """(?i)(?<![\w.-])(authorization)\s*:\s*(bearer\s+)?([a-z0-9+/_\-.=]{8,})"""
    )

    /**
     * JSON 形态：键（可选双/单引号）冒号引号值（值止于同款引号）。
     * 键前的负向后顾防止把 mykey / keyboard / max_tokens 这类普通词
     * 误判成凭证字段名。
     */
    private val JSON_QUOTED = Regex(
        """(?i)(?<![\w.-])(["']?)($FIELDS_QUOTED)\1\s*:\s*(["'])([\s\S]*?)\3"""
    )

    /** 类 JSON 形态：冒号后裸值（止于空白 / 逗号 / 引号 / 右花括号）。 */
    private val JSON_BARE = Regex(
        """(?i)(?<![\w.-])(["']?)($FIELDS_BARE)\1\s*:\s*([^\s,}"']+)"""
    )

    /** 纯文本 k=v 形态：等号 + 可选包裹引号（URL 查询串 / 命令行参数）。 */
    private val KEY_VALUE = Regex(
        """(?i)(?<![\w.-])($FIELDS_QUOTED)\s*=\s*(["']?)([^\s"',;&}]+)\2"""
    )

    /**
     * 对参数串做字段级脱敏。审计写入（tool-audit.jsonl + Logcat）与确认
     * 弹窗参数摘要展示前都应先过本函数。任何异常折叠为原样返回。
     */
    fun redact(raw: String): String {
        if (raw.isEmpty()) return raw
        return try {
            var out = AUTH_HEADER.replace(raw) { m ->
                "${m.groupValues[1]}: ${m.groupValues[2]}${maskValue(m.groupValues[3])}"
            }
            out = JSON_QUOTED.replace(out) { m ->
                val (openQuote, name, valueQuote, value) = m.destructured
                "$openQuote$name$openQuote:$valueQuote${maskValue(value)}$valueQuote"
            }
            out = JSON_BARE.replace(out) { m ->
                val (openQuote, name, value) = m.destructured
                "$openQuote$name$openQuote:${maskValue(value)}"
            }
            out = KEY_VALUE.replace(out) { m ->
                val (name, quote, value) = m.destructured
                "$name=$quote${maskValue(value)}$quote"
            }
            out
        } catch (_: Throwable) {
            raw
        }
    }

    /**
     * 掩码单个值：≤8 字符整体替换为掩码；否则保留前后各 [KEEP_EDGES]
     * 字符、中间折叠为省略号。对已掩码的输出幂等（重复 redact 不再改写）。
     */
    internal fun maskValue(value: String): String = when {
        value.length <= SHORT_VALUE_MAX -> MASK
        else -> value.take(KEEP_EDGES) + ELLIPSIS + value.takeLast(KEEP_EDGES)
    }
}
