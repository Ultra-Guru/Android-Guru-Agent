package com.apex.agent.ui.screen.code

import com.apex.agent.R
import com.apex.agent.ui.language.LanguageManager

/**
 * # Code Error Text — 错误条文案净化（CodeViewModel 职责缝拆分）
 *
 * 异常 → 错误条可读文案（#209；#312 资源化），三段策略：
 * 1. 高频类型直接映射资源（[ERROR_FRIENDLY_RES]）；
 * 2. 其余取 message 首个有意义行（过滤空行/null 字样/堆栈帧/类名前缀）；
 * 3. 全部无效时回退「执行失败（异常类型）」—— 堆栈与 null 字样绝不直出。
 *
 * 取词经 [LanguageManager] 按当前语言解析（资源键住 strings_code.xml）。
 */
internal object CodeErrorText {

    /**
     * 高频异常类型 → 可读文案资源（模型运行时类与 llm-adapter 的
     * LlmErrorText 口径对齐）。
     */
    private val ERROR_FRIENDLY_RES: Map<String, Int> = mapOf(
        "SocketTimeoutException" to R.string.code_err_socket_timeout,
        "ConnectException" to R.string.code_err_connect,
        "UnknownHostException" to R.string.code_err_unknown_host,
        "SocketException" to R.string.code_err_socket,
        "SSLException" to R.string.code_err_ssl,
        "EOFException" to R.string.code_err_eof,
        "IOException" to R.string.code_err_io,
        "SerializationException" to R.string.code_err_serialization,
        "JsonEncodingException" to R.string.code_err_json_encoding
    )

    /** 堆栈帧样式行（at com.example.Foo.bar(Foo.kt:12)）——过滤目标。 */
    private val STACK_FRAME_LINE = Regex("^\\s*at \\S+\\(")

    /** 「a.b.ClassName: 前缀」样式 —— 剥离类名前缀只留可读描述。 */
    private val EXCEPTION_NAME_PREFIX = Regex("^[\\w.$]+(?:Exception|Error)\\s*:\\s*")

    /** 异常 → 错误条可读文案（策略见类 KDoc）。 */
    fun sanitize(e: Throwable, languageManager: LanguageManager): String {
        ERROR_FRIENDLY_RES[e::class.simpleName]?.let { return languageManager.getString(it) }
        val detail = e.message
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { line ->
                line.isNotEmpty() &&
                    !line.equals("null", ignoreCase = true) &&
                    !line.startsWith("Caused by:") &&
                    !STACK_FRAME_LINE.containsMatchIn(line)
            }
            ?.let { EXCEPTION_NAME_PREFIX.replace(it, "").trim() }
            .orEmpty()
        return if (detail.isEmpty()) {
            languageManager.getString(
                R.string.code_err_execute_failed_fmt,
                e::class.simpleName ?: languageManager.getString(R.string.code_err_unknown_exception)
            )
        } else {
            detail
        }
    }
}
