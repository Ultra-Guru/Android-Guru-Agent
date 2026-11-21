package com.apex.agent.core.tools.prompt

/**
 * # Prompt Models — Prompt 段级观测 SPI（P97）
 *
 * 让「发给模型的完整输入包」可观测、可脱敏转储、可被内部组件按段注入。
 *
 * **模块方向**：模型与 SPI 定义在本层（tool-registry）而非规格原案的
 * agent-engine——`PromptDump` / `PromptSectionList` 工具住本层，而
 * agent-engine → tool-registry 单向依赖（反向放置会成环）；快照组装与
 * 段级运行器（[com.apex.agent.core.engine.PromptStageRunner]）住
 * agent-engine，只 import 本文件类型。
 *
 * 设计边界（P97 非目标，见规格 01）：
 * - 不改动既有 `HookOutcome` 形状（工具执行链钩子另行其事）——Prompt
 *   段级用本**独立 SPI**；
 * - `BLOCK` 语义不引入（Prompt 段级不做阻断，阻断归 RiskAwareToolGate）；
 * - 钩子结果**预算限流 + 防御式降级**：单轮改写 ≤2 段、追加 ≤4KB、
 *   `DropTurns` 累计 ≤8 条；超限按 [PromptStageResult.Pass] 处理并留痕；
 *   钩子抛异常 → 捕获 → Pass + 日志，绝不向上抛。
 */
enum class PromptStage {
    /** 组装前（sections 尚未解析——预检/统计用）。 */
    BEFORE_COMPOSE,
    /** 单段组装（每段一次——改写/追加窗口）。 */
    SECTION_COMPOSE,
    /** 组装后、发送前最终形态（快照落点）。 */
    AFTER_COMPOSE,
    /** 发送前（与 AFTER_COMPOSE 同一快照，最后一道观测口）。 */
    BEFORE_SEND,
    /** 发送后（fire-and-forget，不阻塞回包）。 */
    AFTER_SEND
}

/** 一段提示词（id 稳定、文本为渲染产物、token 估算 chars/4 口径）。 */
data class PromptSection(
    val id: String,
    val text: String,
    val tokenEstimate: Int,
    /** 真值源类名（审计口径，段→源映射见 PromptSnapshotBuilder.SOURCE_OF_SECTION）。 */
    val source: String
)

/** 一条会话回合（SYSTEM / USER / ASSISTANT / TOOL_CALL / TOOL_RESULT / SUMMARY）。 */
data class PromptTurn(
    val kind: String,
    val content: String,
    val toolName: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/** 一次「发给模型的完整输入包」结构化快照（四条逻辑线同构）。 */
data class PromptSnapshot(
    val systemPrompt: String,
    val sections: List<PromptSection>,
    val turns: List<PromptTurn>,
    val toolIds: List<String>,
    val tokenEstimate: Int,
    /** 深潜 / 标准 / 织造 / 信使（四线标识）。 */
    val line: String,
    /** 只记被脱敏的字段名，不记值。 */
    val redactions: List<String>
)

/** 段级钩子派发上下文（各阶段的输入）。 */
data class PromptContext(
    val stage: PromptStage,
    val chatId: String? = null,
    val sections: List<PromptSection> = emptyList(),
    val turns: List<PromptTurn> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap()
)

/** 段级钩子 SPI（独立于工具执行链 HookOutcome；见文件头）。 */
interface PromptStageHook {
    val id: String

    suspend fun onStage(stage: PromptStage, ctx: PromptContext): PromptStageResult
}

/** 段级钩子结果（预算与降级见文件头；BLOCK 语义不存在）。 */
sealed interface PromptStageResult {
    data object Pass : PromptStageResult

    /** 改写一段（id 必须存在于 ctx.sections）。 */
    data class ReplaceSection(val id: String, val text: String) : PromptStageResult

    /** 追加进一段（id 必须存在于 ctx.sections）。 */
    data class AppendSection(val id: String, val text: String) : PromptStageResult

    /** 丢弃若干 turns（按索引；SYSTEM 永不丢弃——压缩保护由调用方执行）。 */
    data class DropTurns(val indices: Set<Int>) : PromptStageResult
}

/**
 * # Prompt Redaction — 快照脱敏（纯函数，P97）
 *
 * 密钥类字段值一律替换为 `***`，[PromptSnapshot.redactions] 只记字段名不记值。
 * 敏感口径：api_key / apikey / token / authorization / cookie / set-cookie /
 * bearer 的 `key[:=]value` 或 JSON `"key": "value"` 形态；大小写不敏感。
 * （公共对象：工具层与引擎层测试共用——模块边界见 PromptModels KDoc。）
 */
object PromptRedaction {

    private val SENSITIVE_KEYS = listOf(
        "api_key", "apikey", "token", "authorization", "cookie", "set-cookie", "bearer"
    )

    fun redact(snapshot: PromptSnapshot): PromptSnapshot {
        val hit = mutableListOf<String>()
        fun scrub(text: String): String {
            var out = text
            SENSITIVE_KEYS.forEach { key ->
                val re = Regex("(\"?${key}\"?\\s*[:=]\\s*)\"?[^\"\\n,;}\\s][^\"\\n,;}]*\"?", RegexOption.IGNORE_CASE)
                if (re.containsMatchIn(out)) {
                    hit += key
                    out = re.replace(out) { m -> m.groupValues[1] + "***" }
                }
            }
            return out
        }
        return snapshot.copy(
            systemPrompt = scrub(snapshot.systemPrompt),
            sections = snapshot.sections.map { it.copy(text = scrub(it.text)) },
            turns = snapshot.turns.map { it.copy(content = scrub(it.content)) },
            redactions = hit.distinct()
        )
    }
}
