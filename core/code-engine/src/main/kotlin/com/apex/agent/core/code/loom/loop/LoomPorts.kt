package com.apex.agent.core.code.loom.loop

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * # Loom Ports — 织造引擎与宿主之间的端口契约
 *
 * 织造循环内核**只面向端口编程**（六端口 + 两源 + 一估算器）：宿主侧
 * 由引擎适配器把 ModelRuntime / ToolRegistry / CodeConversationMemory /
 * AppLogger 等接进来——循环语义零宿主依赖（与 standard-loop 库同款
 * 端口-适配器纪律，但协议与循环行为是织造线自己的）。
 *
 * | 端口 | 宿主实现 | 职责 |
 * |---|---|---|
 * | [LoomModelClient] | ModelRuntime 适配 | 流式 / 非流式 LLM 调用 |
 * | [LoomToolSource] | ToolRegistry 快照 | 工具目录（定义列表） |
 * | [LoomToolRunner] | 仅环境门执行器 | 工具流式执行 |
 * | [LoomMemory] | CodeConversationMemory 适配 | 会话持久化 |
 * | [LoomLogger] | AppLogger 适配 | 日志 |
 * | [LoomFailureClassifier] | ModelRuntimeException 映射 | 失败分类（重试策略） |
 * | [LoomEnvironmentSource] | 宿主环境行 | env 块数据 |
 * | [LoomModelInfoSource] | PRIMARY 解析链 | 模型 id + 真实窗口 |
 * | [LoomTokenEstimator] | 文本加速核 / 启发式 | token 估算 |
 * | [LoomWorkspaceContextSource] | 工作区上下文段 | 工作区 / 规则段 |
 */
interface LoomModelClient {
    /** 流式对话（主循环 / 子线程共用；contextTag 区分流量来源）。 */
    fun chatStream(
        contextTag: String,
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk>

    /** 非流式对话（digest 摘要用）。 */
    suspend fun chat(
        contextTag: String,
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse
}

/** 工具目录源（引擎组装工具面时拉取快照）。 */
fun interface LoomToolSource {
    fun definitions(): List<ToolDefinition>
}

/** 工具执行端口（流式——进度与增量输出全程可见）。 */
interface LoomToolRunner {
    fun executeStream(toolId: String, arguments: String): Flow<com.apex.agent.core.tools.ToolStreamEvent>
}

/** 会话记忆端口（null = 子线程/测试：纯内存会话不落盘，由引擎层处理）。 */
interface LoomMemory {
    fun load(): List<LlmMessage>
    fun append(message: LlmMessage)
    fun save(messages: List<LlmMessage>)
    fun clear()
    fun count(): Int
    fun bindWorkspace(workspaceId: String)
}

/** 日志端口。 */
interface LoomLogger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable?)
}

/** 失败分类（决定容错阶梯走向）。 */
enum class LoomFailureKind {
    /** 瞬时（网络抖动 / 限流 / 超时）——阶梯重试。 */
    TRANSIENT,

    /** 请求级拒绝（鉴权 / 配置 / 参数）——终止。 */
    REQUEST_REJECTED,

    /** 上下文溢出——先压缩再重试一次。 */
    CONTEXT_OVERFLOW,

    /** 未分类——保守终止。 */
    UNCLASSIFIED
}

/** 失败分类器端口。 */
fun interface LoomFailureClassifier {
    fun classify(t: Throwable): LoomFailureKind
}

/** token 估算端口（宿主可注入 C++ 加速核；缺省启发式由引擎侧提供）。 */
fun interface LoomTokenEstimator {
    fun estimateTokens(text: String): Int
}

/** 环境信息（env 块数据）。 */
data class LoomEnvironmentInfo(
    val platformText: String,
    val workdirLabel: String? = null,
    val isGitRepo: Boolean? = null
)

/** 环境信息源端口。 */
fun interface LoomEnvironmentSource {
    fun current(): LoomEnvironmentInfo
}

/** 模型信息（真实上下文窗口——预算与 env 块消费）。 */
data class LoomModelInfo(
    val modelId: String,
    val contextWindow: Int
)

/** 模型信息源端口。 */
fun interface LoomModelInfoSource {
    fun current(): LoomModelInfo?
}

/** 工作区上下文段源端口（工作区 / 全局规则 / 项目规则三段）。 */
interface LoomWorkspaceContextSource {
    fun workspaceSection(): String?
    fun globalRulesSection(): String?
    fun projectRulesSection(): String?
}

/** 通用 token 估算缺省实现（启发式：CJK 字符 1:1，其余 4 字符 1 token）。 */
object LoomHeuristicEstimator : LoomTokenEstimator {
    override fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var rest = 0
        for (c in text) {
            if (c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF ||
                c.code in 0xFF00..0xFFEF
            ) cjk++ else rest++
        }
        return cjk + (rest + 3) / 4
    }
}
