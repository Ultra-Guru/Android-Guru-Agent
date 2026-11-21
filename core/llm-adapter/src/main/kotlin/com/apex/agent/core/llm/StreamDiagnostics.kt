package com.apex.agent.core.llm

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * # Stream Diagnostics — 流式回合诊断快照（SPE-08 P1-1）
 *
 * 旧实现流式指标零结构化：首 token 慢、数据间隔抖动、解析丢帧、截断形态
 * 用户与开发者都看不到（散落的限次日志不可聚合）。本数据类把**一次
 * LLM 流式回合**（一次 chatStream 调用）的观测指标结构化：
 *
 * - 采集点：`StreamingOpenAiClient.chatStream`（帧级）→ 随流尾
 *   `LlmStreamChunk.diagnostics` 上抛 → 引擎转 `AgentEvent.StreamTurnStats`
 *   → VM 收束进 `StreamTurnDiagnosticsStore`（诊断页「最近 N 次流式回合」）；
 * - [turnId]：回合标识（进程内自增；诊断页行对齐口径）；
 * - [firstTokenLatencyMs]：流开始 → 首个**含内容**帧的延迟（null = 全程无内容）；
 * - [interFrameMsP50] / [interFrameMsP95]：数据帧到达间隔的分位数（帧数 < 3
 *   时 null——样本太少无意义）；
 * - [framesReceived] / [framesParsed] / [framesDropped]：数据帧到达 / 成功
 *   解析 / 丢弃（解析失败 = received - parsed；超长行跳过另见
 *   `SseFrameReader.oversizedLinesSkipped`）；
 * - [truncated]：`finish_reason=length` 截断（语义见
 *   [LlmException.TruncatedStream]）；[finishReason]：最后一次见到的收尾原因
 *   （[DONE] 记为 "done"）；
 * - [usageSource]：统计帧是否到达（REAL/MISSING；ESTIMATED 留给启发式
 *   估算路径）——上下文仪表盘徽标口径（SPE-08 P1-4）。
 *
 * 纯 JVM、无 Android 依赖；[TurnStatsCollector] 假钟可测（SPE-09 纪律）。
 */
data class StreamDiagnostics(
    val turnId: String,
    val firstTokenLatencyMs: Long? = null,
    val interFrameMsP50: Long? = null,
    val interFrameMsP95: Long? = null,
    val framesReceived: Int = 0,
    val framesParsed: Int = 0,
    val framesDropped: Int = 0,
    /** TaskRuntime 侧背压丢弃计数（Code 路径正文不丢恒 0；Agent 屏接线后续）。 */
    val backpressureDropped: Int = 0,
    val truncated: Boolean = false,
    val finishReason: String? = null,
    val usageSource: UsageSource = UsageSource.MISSING
)

/** 用量统计来源（SPE-08 P1-4：仪表盘徽标「真实（服务端）/估算/未获取」）。 */
enum class UsageSource {
    /** 服务端 usage 统计帧（stream_options.include_usage / DeepSeek 末帧）到达。 */
    REAL,
    /** 无统计帧，按启发式估算路径回退。 */
    ESTIMATED,
    /** 无统计帧且无估算（本回合未获取）。 */
    MISSING
}

/**
 * 回合级采集器（chatStream 内部专用）：时间戳/帧计数/收尾语义的增量归集，
 * [snapshot] 在流收尾（成功/异常共用）时一次性冻结。
 *
 * 假钟注入（[clock]）：看门狗/延迟断言不依赖墙钟（SPE-09 测试纪律）。
 * 单线程使用（flow 收集者上下文），无并发防护。
 */
internal class TurnStatsCollector(
    /** 假钟（测试注入；生产 = System::currentTimeMillis）。 */
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val turnId = "turn-${TURN_SEQ.incrementAndGet()}"
    private val startMs = clock()
    private val frameGapsMs = mutableListOf<Long>()
    private var lastFrameMs: Long? = null
    private var firstTokenLatencyMs: Long? = null
    private var framesReceived = 0
    private var framesParsed = 0
    private var truncated = false
    private var finishReason: String? = null
    private var sawUsage = false

    /** 流开始时刻（看门狗软检查用）。 */
    fun elapsedMs(): Long = clock() - startMs

    /** 是否已见首个内容帧（正文/思考/工具调用任一——看门狗判定基准）。 */
    fun hasFirstToken(): Boolean = firstTokenLatencyMs != null

    /** 数据帧到达（[DONE] 也计一帧）。 */
    fun onFrameArrived() {
        val now = clock()
        lastFrameMs?.let { prev -> frameGapsMs += now - prev }
        lastFrameMs = now
        framesReceived++
    }

    /** 帧解析成功产出 chunk。 */
    fun onFrameParsed() {
        framesParsed++
    }

    /** 首个含内容帧（content / reasoning / toolCalls 任一非空）。 */
    fun onFirstToken() {
        if (firstTokenLatencyMs == null) firstTokenLatencyMs = clock() - startMs
    }

    /** 收尾原因到达（取最后一次；[DONE] 传入 "done"）。 */
    fun onFinishReason(reason: String?) {
        if (reason != null) finishReason = reason
    }

    /** finish_reason=length 截断。 */
    fun onTruncated() {
        truncated = true
    }

    /** 统计帧 usage 到达。 */
    fun onUsageSeen() {
        sawUsage = true
    }

    /** 冻结快照（成功/异常共用；帧数 < 3 时分位数为 null）。 */
    fun snapshot(): StreamDiagnostics = StreamDiagnostics(
        turnId = turnId,
        firstTokenLatencyMs = firstTokenLatencyMs,
        interFrameMsP50 = percentile(frameGapsMs, 0.50),
        interFrameMsP95 = percentile(frameGapsMs, 0.95),
        framesReceived = framesReceived,
        framesParsed = framesParsed,
        framesDropped = (framesReceived - framesParsed).coerceAtLeast(0),
        truncated = truncated,
        finishReason = finishReason,
        usageSource = if (sawUsage) UsageSource.REAL else UsageSource.MISSING
    )

    private fun percentile(samples: List<Long>, fraction: Double): Long? {
        if (samples.size < MIN_GAP_SAMPLES) return null
        val sorted = samples.sorted()
        // 邻近秩（roundToInt）：P95 于 3 样本取最大样本而非中位——分位语义对
        // 小样本更诚实（P50 于奇数样本 = 中位不变）。
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private companion object {
        /** 分位数最小样本数（<3 帧间隙无统计意义）。 */
        const val MIN_GAP_SAMPLES = 3

        val TURN_SEQ = AtomicLong(0)
    }
}
