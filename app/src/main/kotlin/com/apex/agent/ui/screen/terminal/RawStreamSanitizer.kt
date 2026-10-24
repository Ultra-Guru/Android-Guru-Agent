package com.apex.agent.ui.screen.terminal

/**
 * PTY 原始流清洗器（T96 —— 真实输出管线的关键纯函数件）。
 *
 * 输入是 runtime.observe(RAW) 返回的原始字节流切片 —— 它包含真实终端的
 * 全部噪音：行编辑回显（PTY 把写入的命令行原样回显）、OSC 633 shell
 * marker（`printf` 注入的退出码协议载荷）、CSI 转义（颜色/光标）、以及
 * 命令结束后 shell 重绘的提示符尾。控制台按「行」呈现，这些噪音必须
 * 确定性地剥离，用户看到的才恰好是真实命令输出。
 *
 * 三段式契约（一次 job 生命周期一个实例）：
 *  1. [feed] 逐块喂入输出轮询结果，返回本块完成的干净行；
 *  2. [finish] job 终止时调用，冲刷残余 —— 若残余是提示符尾（下一 prompt
 *     重绘）则丢弃；否则作为最后一行输出。
 *
 * 剥离规则（全部确定性，无启发式猜测）：
 *  - ANSI OSC 序列（含 OSC 633 marker）: ESC ] … (BEL | ESC \)
 *  - ANSI CSI/单字符转义: ESC [ … final 或 ESC dispatch
 *  - CR: CRLF 归一为 LF；孤立 CR（进度条覆写）视为换行 —— 控制台按行
 *    呈现，覆写语义降级为分行（内容不丢）。
 *  - 行首回显：run() 写入的命令行会被 PTY 原样回显为流的第一行（长命令
 *    在 80 列处折行为多物理行）—— 以 [expectedEcho] 全文（ANSI 剥离后）
 *    逐行消费，回显跨物理行折行也能正确吞掉；echo 完成后残余即真实输出。
 *  - 提示符尾：mksh `${USER}@${HOSTNAME}:cwd $ ` / bash `root@ubuntu:~#`
 *    家族（与 platform InputWaitingDetector 的 HIGH_CONFIDENCE 同源规则）
 *    —— finish 时残留的无换行尾巴命中即弃（那是下一 prompt，不是输出）。
 *
 * 纯 Kotlin / 无 Android 依赖 —— 单测直测（RawStreamSanitizerTest）。
 */
internal class RawStreamSanitizer(
    /** 预期回显全文（包装后的命令行，ANSI 剥离口径）；null = 不做回显剥离。 */
    private val expectedEcho: String?
) {
    /** 跨块残余（半个行）。 */
    private var carry = StringBuilder()

    /** 回显消费进度（expectedEcho 已被吞掉的前缀长度）。 */
    private var echoConsumed = 0

    /** 回显阶段是否已结束（true 后不再做 echo 匹配）。 */
    private var echoDone = expectedEcho == null

    /** OSC 序列剥离（含 OSC 633 marker）：ESC ] … (BEL | ESC \)。 */
    private val oscRegex = Regex("\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)")

    /** CSI 及其余两字符转义剥离：ESC [ … final / ESC dispatch。 */
    private val csiRegex = Regex("\u001b\\[[0-9;?<>=!\"'#()*+ ./-]*[@-~]|\u001b[@-Z\\-_]")

    /** 提示符尾判定（finish 残留行）—— InputWaitingDetector HIGH_CONFIDENCE 同源。
     * 真实 PS1 形如 `user@host:cwd $ `（$ 前有空格）：$ 前必须容忍 \s*。 */
    private val promptTails = listOf(
        Regex("^[\\w.-]+@[\\w.-]+:[^\\s]*\\s*[#$]\\s*$"),
        Regex("^\\(.*\\)\\s*[\\w.-]*@?[\\w.-]*:?[^\\s]*\\s*[#$]\\s*$"),
        Regex("^\\d+\\s*[#$]\\s*$"),
        Regex("^\\s*[%#$]\\s*$")
    )

    /**
     * 喂入一块原始输出，返回本块新完成的干净行（可能为空 —— 内容不足半行）。
     */
    fun feed(chunkRaw: String): List<String> {
        if (chunkRaw.isEmpty()) return emptyList()
        var chunk = oscRegex.replace(chunkRaw, "")
        chunk = csiRegex.replace(chunk, "")
        // CRLF 归一 + 孤立 CR 降级为 LF（覆写 → 分行）
        chunk = chunk.replace("\r\n", "\n").replace('\r', '\n')
        carry.append(chunk)
        val lines = mutableListOf<String>()
        while (true) {
            val nl = carry.indexOf('\n')
            if (nl < 0) break
            val line = carry.substring(0, nl)
            carry.delete(0, nl + 1)
            consumeCompleted(line, lines)
        }
        return lines
    }

    /**
     * job 终止冲刷：返回残余的最后一行（若非提示符尾）；提示符尾被丢弃。
     * 调用后实例即终态（[feed] 不可再用）。
     */
    fun finish(): List<String> {
        val rest = mutableListOf<String>()
        val nl = carry.indexOf('\n')
        if (nl >= 0) {
            // 不应出现（feed 已消费全部完整行）—— 防御性冲刷
            val line = carry.substring(0, nl)
            carry.delete(0, nl + 1)
            consumeCompleted(line, rest)
        }
        val tail = carry.toString().trimEnd()
        carry.setLength(0)
        if (tail.isEmpty()) return rest
        val isPrompt = promptTails.any { it.matches(tail) }
        if (!isPrompt) rest.add(tail)
        return rest
    }

    /** 完成行消费：echo 阶段优先吞回显，跨物理行折行按前缀续吞。 */
    private fun consumeCompleted(line: String, out: MutableList<String>) {
        if (!echoDone && expectedEcho != null) {
            val remaining = expectedEcho.length - echoConsumed
            if (remaining > 0 && line.length <= remaining) {
                val seg = expectedEcho.substring(echoConsumed, echoConsumed + line.length)
                if (seg == line) {
                    echoConsumed += line.length
                    if (echoConsumed >= expectedEcho.length) echoDone = true
                    return
                }
            }
            // 不匹配：不是回显（或回显已断）—— 转入输出阶段，本行照常输出
            echoDone = true
        }
        if (line.isNotEmpty()) out.add(line)
    }
}
