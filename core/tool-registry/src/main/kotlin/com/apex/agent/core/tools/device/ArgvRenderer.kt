package com.apex.agent.core.tools.device

/**
 * ═══ argv 模板渲染器（S02）═══
 *
 * 模板元素形如 `{package}`；渲染结果直接进 `Array<String>` 逐元素直调
 * （IShizukuService.newProcess），**绝不经 `sh -c` 拼接** —— 因此拒绝
 * 一切 shell 元字符是渲染的第一纪律。
 *
 * 校验分两层：
 * 1. [validateParams] —— 按 [IntentParam.kind] 白名单校验用户参数
 *    （缺参 / 未知参数 / 值域外全部拒绝）；
 * 2. [render] —— 渲染时再拒一次元字符（防御式：即使调用方跳过了
 *    validateParams，含 `;` `|` `$` 反引号或换行的值也会抛
 *    [IllegalArgumentException] 而不是拼出可注入的 argv）。
 *
 * 空 flag 语法：渲染结果为空字符串的元素会被丢弃（manifest 里
 * `{"name":"keep_data","kind":"enum","values":["-k",""]}` 的 "" 即「不传」）。
 */
object ArgvRenderer {

    /** shell 元字符黑名单（argv 元素语义下全部非法 —— 这些字符只可能来自注入）。 */
    private val FORBIDDEN_METACHARS = charArrayOf(';', '|', '$', '`', '\n', '\r', NUL_CHAR)

    /** 单个自由文本参数的长度上限（值域防御；大内容走 stdinPayload，不走 argv）。 */
    private const val MAX_TEXT_LEN = 1024

    /** stdin 载荷长度上限（payload kind 只进 stdin 不进 argv，仅防内存滥用）。 */
    private const val MAX_PAYLOAD_LEN = 1_048_576

    /** NUL 字符（Kotlin 无反斜杠零转义，用 u0000 转义表达）。 */
    private const val NUL_CHAR = '\u0000'

    /**
     * 合并参数默认值（manifest 声明的 default 填充未提供项）。
     * 返回新 map；调用方随后对其跑 [validateParams] + [render]。
     */
    fun withDefaults(intent: DeviceIntent, params: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap(params)
        for (param in intent.params) {
            if (param.name !in merged && param.default != null) merged[param.name] = param.default
        }
        return merged
    }

    /**
     * 校验用户参数是否满足 [intent] 的声明。
     *
     * [intent.internalParams]（执行器注入的内部占位符真值，如 file.delete 的
     * auditId / basename）跳过校验：它们不是模型输入（来源受信），渲染期
     * 元字符黑名单仍然生效（见 [render]）。
     *
     * @return 错误文案列表（空 = 通过）。错误面向模型：中文 + 指出哪个参数。
     */
    fun validateParams(intent: DeviceIntent, params: Map<String, String>): List<String> {
        val errors = mutableListOf<String>()
        val declared = intent.params.associateBy { it.name }
        for ((name, value) in params) {
            if (name in intent.internalParams) continue
            if (name !in declared) {
                errors += "未知参数 '$name'（intent ${intent.id} 只接受：${intent.paramNames.joinToString(", ")}）"
                continue
            }
            val param = declared.getValue(name)
            validateValue(intent.id, param, value)?.let { errors += it }
        }
        for (param in intent.params) {
            if (param.name !in params && param.required && param.default == null) {
                errors += "缺少必填参数 '${param.name}'（kind=${param.kind}）"
            }
        }
        return errors
    }

    /** 渲染单条 argv 模板；未知占位符 / 元字符值抛 [IllegalArgumentException]。 */
    fun render(template: List<String>, params: Map<String, String>): List<String> {
        val rendered = mutableListOf<String>()
        for (element in template) {
            val value = expand(element, params)
            // 空 flag 语法：渲染为空串的元素丢弃（见类注释）。
            if (value.isEmpty()) continue
            // 元字符黑名单只对**含占位符**（用户值注入面）的元素生效：
            // manifest 字面元素（如 stat 格式串 %n|%s|%Y）受信且以 argv
            // 元素逐个直传 —— 无 shell 拼接面，格式串里的 '|' 是数据不是语法。
            if (element.contains('{')) rejectMetachars(element, value)
            rendered += value
        }
        return rendered
    }

    /** 渲染整组模板（preflight / verify 批量）。 */
    fun renderAll(templates: List<List<String>>, params: Map<String, String>): List<List<String>> =
        templates.map { render(it, params) }

    private fun expand(element: String, params: Map<String, String>): String {
        if (!element.contains('{')) return element
        val sb = StringBuilder(element.length)
        var i = 0
        while (i < element.length) {
            val open = element.indexOf('{', i)
            if (open < 0) {
                sb.append(element, i, element.length)
                break
            }
            sb.append(element, i, open)
            val close = element.indexOf('}', open)
            require(close > open) { "模板元素含未闭合占位符：$element" }
            val name = element.substring(open + 1, close)
            val value = params[name]
            requireNotNull(value) { "未知占位符：{$name}（参数未提供）" }
            sb.append(value)
            i = close + 1
        }
        return sb.toString()
    }

    private fun rejectMetachars(element: String, value: String) {
        val hit = value.firstOrNull { it in FORBIDDEN_METACHARS }
        if (hit != null) {
            throw IllegalArgumentException(
                "参数值含 shell 元字符 '$hit'（argv 语义下非法）：$element"
            )
        }
    }

    private fun validateValue(intentId: String, param: IntentParam, value: String): String? {
        val label = "$intentId 参数 '${param.name}'"
        // payload 不进 argv（stdin 通道无注入面）—— 元字符检查不适用，只限长度。
        if (param.kind != ParamKind.PAYLOAD && value.any { it in FORBIDDEN_METACHARS }) {
            return "$label 含 shell 元字符，已拒绝"
        }
        return when (param.kind) {
            ParamKind.PACKAGE ->
                if (PACKAGE_REGEX.matches(value)) null
                else "$label 不是合法包名（^[A-Za-z0-9._]+\$）"
            ParamKind.ENUM ->
                if (value in param.values) null
                else "$label 必须是 ${param.values.joinToString("/")} 之一"
            ParamKind.USER ->
                if (USER_REGEX.matches(value)) null
                else "$label 必须是数字 user id（如 0）"
            ParamKind.SETTING_KEY ->
                if (SETTING_KEY_REGEX.matches(value)) null
                else "$label 含非法字符（只允许字母数字点下划线冒号）"
            ParamKind.PATH ->
                when {
                    !value.startsWith("/") -> "$label 必须是宿主绝对路径（/ 开头）"
                    value.contains("..") -> "$label 含 .. 路径穿越，已拒绝"
                    else -> null
                }
            ParamKind.TEXT ->
                if (value.length > MAX_TEXT_LEN) "$label 超长（>${MAX_TEXT_LEN} 字符；大内容请走 stdin 通道）"
                else null
            ParamKind.PAYLOAD ->
                if (value.length > MAX_PAYLOAD_LEN) "$label 超长（>${MAX_PAYLOAD_LEN} 字符）"
                else null
            else -> "$label 使用未知 kind：${param.kind}"
        }
    }

    private val PACKAGE_REGEX = Regex("^[A-Za-z0-9._]+$")
    private val USER_REGEX = Regex("^[0-9]{1,5}$")
    private val SETTING_KEY_REGEX = Regex("^[A-Za-z0-9._:]+$")
}
