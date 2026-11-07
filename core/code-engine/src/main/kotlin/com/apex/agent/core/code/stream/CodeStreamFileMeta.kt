package com.apex.agent.core.code.stream

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * # Code Stream File Meta — 文件胶囊的行号 / 增删行元数据（纯函数）
 *
 * 自 [CodeStreamSession] 迁出（God-file 预算拆分，模式同
 * StreamingArgsPreview.kt）：读取类的行号范围（参数侧预置 + 输出侧解析）
 * 与 LLM 重试阶梯延迟的人读格式，全部无状态纯计算 —— 会话状态机按需调用。
 */

/** CodeRead 契约的默认读取行数（无 limit 参数时）。 */
internal const val DEFAULT_READ_LIMIT = 800

/** CodeRead 输出行号前缀（cat -n 风格「N: 」）。 */
internal val READ_LINE_PREFIX_REGEX = Regex("""^\s*(\d+):""")

/**
 * 读取类行号范围（参数侧）：offset（1-based，缺省 1）与 limit（缺省 800）
 * → 「L1-L800」；目录读取在完成时按输出形态清零（无行号前缀）。
 */
internal fun readLineRangeOf(args: Map<String, JsonElement>): String? {
    val int = { key: String ->
        (args[key] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()
    }
    val offset = int("offset") ?: 1
    val limit = int("limit") ?: DEFAULT_READ_LIMIT
    return "L$offset-L${offset + limit - 1}"
}

/**
 * 读取类行号范围（输出侧）：CodeRead 输出每行带「N: 」前缀 —— 首尾行号
 * 即为实际读取范围（「L1-L42」，读到文件尾早于 limit 时如实反映）；
 * 非行号格式（目录清单 / 错误输出）返回 null。
 */
internal fun actualReadLineRangeOf(output: String): String? {
    var first: Int? = null
    var last: Int? = null
    output.lineSequence().forEach { line ->
        val m = READ_LINE_PREFIX_REGEX.find(line) ?: return@forEach
        val n = m.groupValues[1].toIntOrNull() ?: return@forEach
        if (first == null) first = n
        last = n
    }
    val f = first ?: return null
    val l = last ?: return null
    return "L$f-L$l"
}

/**
 * 阶梯延迟的人读形态（1s / 30s / 1m / 3m）—— 重试状态行与思考卡副语共用。
 */
internal fun formatLadderDelay(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m"
}

/** JsonPrimitive content 的 null 安全读取（非 primitive 返回 null）。 */
internal fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()
