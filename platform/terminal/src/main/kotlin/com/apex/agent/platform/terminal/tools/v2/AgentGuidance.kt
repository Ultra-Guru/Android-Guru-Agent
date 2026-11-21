package com.apex.agent.platform.terminal.tools.v2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ═══ 对话实录修复（agent 失败复盘 → 工具层引导）═══
 *
 * 本文件集中存放 terminal v2 工具面向 LLM 的三类宽容能力，均为纯函数
 *（无运行时依赖，可 JVM 单测）：
 *
 * 1. **参数别名宽容读取**（实录 #8）：模型把 `sessionId` 写成 `session_id`
 *    是高频命名混用。schema 校验层（ToolSchema）已对必填参数别名互认；
 *    工具层用 [longArg] / [intArg] / [stringArg] / [boolArg] 同步宽容读取，
 *    保证「校验放行 → 工具也能读到」全链路一致，首轮调用即成功。
 *
 * 2. **heredoc 风险提示**（实录 #7）：多行 heredoc 直灌 PTY 曾把会话挂死在
 *    PS2 `>` 续行符上（结束符未到达，Ctrl+C 经 TerminalSignal 无法送达
 *    前台的 shell 本体），最终只能 SIGKILL 整会话。[heredocAdvisory] 在
 *    命令文本里探测 heredoc 起始符，输出「先落文件再执行 + CTRL_C 自救」
 *    的前置警告 —— 不阻断执行（尊重模型判断），但把代价写明白。
 *
 * 3. **EVENT 模式无文本提示**（实录 #9）：TerminalObserve 的 EVENT 模式
 *    只返回事件计数（字节本体在 RingBuffer，不在事件里），模型曾拿着
 *    eventCount=92 当作“读过了”。[EVENT_MODE_HINT] 随输出附带，指明
 *    读文本要走 SCREEN / RAW。
 */
internal object AgentGuidance {

    /** EVENT 模式输出附带的读取指引（实录 #9）。 */
    const val EVENT_MODE_HINT =
        "EVENT mode returns event METADATA only (counts/cursors) — NO output text. " +
            "To read what the command printed, use mode=SCREEN (with scrollbackLines) " +
            "or mode=RAW (bytes since afterCursor)."

    /** 信号未送达时的自救指引（实录 #7：heredoc 挂死两次 Ctrl+C 无效）。 */
    const val SIGNAL_UNDELIVERED_HINT =
        "signal NOT delivered: the shell itself is in the foreground (e.g. waiting at a " +
            "heredoc PS2 '>' continuation prompt). JOB-scope signals cannot reach it by " +
            "design. To interrupt, send TerminalWrite {\"kind\":\"KEY\",\"key\":\"CTRL_C\"} " +
            "(goes through the PTY line discipline); to abort the whole session use " +
            "scope=SESSION."

    /**
     * heredoc 起始符探测：`<<` / `<<-`（排除 `<<<` here-string —— 它单行自洽
     * 不会挂）。命中时返回前置警告；无 heredoc 返回 null（零噪音）。
     */
    fun heredocAdvisory(text: String): String? {
        if (!text.contains("<<")) return null
        val hasHeredocStart = RX_HEREDOC_START.containsMatchIn(text)
        if (!hasHeredocStart) return null
        return "⚠ this input contains a heredoc start (<<). If the terminator line never " +
            "arrives, the shell will hang at the '>' (PS2) prompt and JOB-scope signals " +
            "CANNOT interrupt it. Prefer writing the script to a file first (CodeWrite / " +
            "Write), then executing the file. If the session is already hung at '>', " +
            "recover with TerminalWrite {\"kind\":\"KEY\",\"key\":\"CTRL_C\"}."
    }

    /** heredoc 起始符：`<<` / `<<-` 后跟词字符或引号；`<<<` 排除。 */
    private val RX_HEREDOC_START = Regex("""(?<!<)<{2}-?\s*['"]?[A-Za-z_]\w*""")
}

// ═══ 参数别名宽容读取（实录 #8）：keys 按优先级排列，首个命中者胜出 ═══
// 与 ToolSchema.validate 的别名互认规则同源：camelCase 主名在前，
// snake_case / 常见同义别名在后（如 afterCursor 的 cursor 简写）。

/** Long 参数宽容读取：如 json.longArg("sessionId", "session_id")。 */
internal fun JsonObject.longArg(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
    }

/** Int 参数宽容读取。 */
internal fun JsonObject.intArg(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
    }

/** String 参数宽容读取。 */
internal fun JsonObject.stringArg(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

/** Boolean 参数宽容读取（严格布尔，不做字符串猜测）。 */
internal fun JsonObject.boolArg(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.booleanOrNull
    }
