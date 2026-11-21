package com.apex.agent.core.llm

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.BufferedReader

/**
 * # SSE Frame Reader — 标准 Server-Sent Events 帧状态机（SPE-05 P0-1）
 *
 * 旧实现逐行 `data:` 解析，两个规范形态被拆碎/静默丢弃：
 *
 * 1. **多行 `data:` 事件**（SSE 规范：一个 event 的多行 data 以 `\n` 拼接后
 *    才是一个完整数据帧）——部分网关 / 长 content 场景按规范分多行发送，
 *    逐行 parse 每行都失败 → `catch → null` 静默丢帧 → 回答缺字；
 * 2. **注释行（`: ping` 心跳）** 被无脑 continue——无法区分「心跳」与
 *    「有效数据」的看门狗口径（SPE-05 P0-2 的前置数据结构）。
 *
 * 本读取器按规范分帧：
 *
 * ```text
 * event: message        ┐
 * id: 42                │ 一个 event（行缓冲）
 * data: {"a":           │
 * data:   "b"}          ┘ ← 多行 data 以 "\n" 拼接
 *                       ← 空行 = 事件边界（dispatch）
 * : ping                ← 注释行（心跳；只计数不进数据缓冲）
 * ```
 *
 * - [nextFrame] 返回拼接后的 data 负载（无 data 行的事件返回 null 继续
 *   扫描；EOF 时若仍有未 dispatch 的半截事件，**冲刷返回**——严格规范会
 *   丢弃 EOF 截断的最后事件，但本客户端面向的网关良莠不齐，宁解析勿丢帧）；
 * - `event:` / `id:` / `retry:` 行被消费（OpenAI Chat Completions 流不用
 *   它们，识别仅为不再当作 data 误解析）；
 * - 注释行只累计 [commentLines]（心跳观测口径，见 SPE-08 流式诊断）；
 * - 单行长度护栏 [MAX_SSE_LINE_CHARS]：超长行（畸形 base64 帧 / 恶意
 *   长行）跳过并限次记日志，不再无界缓冲。
 *
 * 纯 JVM、无协程依赖；阻塞读由调用方包 `withContext(Dispatchers.IO)`
 * （沿用 #765 「不整体 flowOn」约束——见 [StreamingOpenAiClient.chatStream]）。
 */
class SseFrameReader(
    private val reader: BufferedReader,
    /** 单行护栏（字符数）：超限行跳过 + 限次留痕。1 MB 覆盖合法长帧，拦恶意畸形。 */
    private val maxLineChars: Int = MAX_SSE_LINE_CHARS,
    /** 假钟（SPE-08 看门狗测试注入；生产 = System::currentTimeMillis）。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 纯心跳停滞看门狗（SPE-08 P1-2）：距上一个**数据行**超过该毫秒数仍
     * 只有注释/心跳行到达 → 抛 [LlmException.StreamStalled]（只上抛不重发）。
     * null = 关闭（默认；非流式测试/旧调用零变化）。
     *
     * 必须在**读取器内部**逐行判定：纯心跳停滞时 nextFrame 永不返回（注释行
     * 被内联消费、循环继续扫下一行），客户端层的外层软检查永远等不到执行机会；
     * 心跳会持续重置 OkHttp readTimeout，靠读超时也排不掉——必须在这里拦。
     */
    private val heartbeatStallTimeoutMs: Long? = null
) {

    /** 注释行（心跳）累计——SPE-05 P0-2 / SPE-08 的无数据心跳观测口径。 */
    var commentLines: Int = 0
        private set

    /** 因超长被跳过的行数（诊断口径）。 */
    var oversizedLinesSkipped: Int = 0
        private set

    /** 上一个数据行时刻（看门狗基准；首行前 = 构造时刻）。 */
    private var lastDataLineMs: Long = clock()

    /** 读下一帧的 data 负载：拼接多行 data；无 data 的事件跳过继续扫描。
     *  EOF → null。 */
    fun nextFrame(): String? {
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> {
                    // 空行 = 事件边界。无 data（纯注释/纯 event 头）→ 事件作废继续扫。
                    if (hasData) return data.toString()
                    continue
                }
                line.startsWith(":") -> {
                    commentLines++
                    checkHeartbeatStall()
                    continue
                }
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:")
                    // 规范：`data:` 后若紧跟一个空格则剥掉一个（兼容 `data: x` / `data:x`）。
                    val stripped = if (payload.startsWith(" ")) payload.removePrefix(" ") else payload
                    if (stripped.length > maxLineChars) {
                        oversizedLinesSkipped++
                        logOversized(stripped)
                        continue
                    }
                    if (hasData) data.append('\n')
                    data.append(stripped)
                    hasData = true
                    lastDataLineMs = clock()
                }
                // event:/id:/retry:/未知字段行：规范内字段被消费；未知行忽略。
                else -> continue
            }
        }
        // EOF：冲刷未 dispatch 的半截事件（宁解析勿丢帧，见类 KDoc）。
        return if (hasData) data.toString() else null
    }

    /** 心跳停滞看门狗：注释行到达时检查距上一个数据行的间隔。 */
    private fun checkHeartbeatStall() {
        val timeout = heartbeatStallTimeoutMs ?: return
        val gap = clock() - lastDataLineMs
        if (gap > timeout) {
            throw LlmException.StreamStalled(gapMs = gap, commentLines = commentLines)
        }
    }

    private fun logOversized(stripped: String) {
        if (oversizedLinesSkipped <= 3 || oversizedLinesSkipped % 50 == 0) {
            AppLogger.instance.warn(
                LogCategory.LLM, "SseFrameReader",
                "SSE line exceeds $maxLineChars chars (skip #$oversizedLinesSkipped): head ${stripped.take(80)}"
            )
        }
    }


    companion object {
        /** 单行护栏默认值：1 MB（覆盖合法超长 base64 帧；Android 端防 OOM）。 */
        const val MAX_SSE_LINE_CHARS = 1_000_000
    }
}
