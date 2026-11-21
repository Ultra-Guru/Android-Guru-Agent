package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 轮次与渲染模型（PR 1 骨架）═══
 *
 * COMPOSE 阶段产物 [TemplateTurnRequest]（发往引擎的最终消息）与
 * RENDER 阶段产物 [TemplateRenderedReport]（回包的最终形态），
 * 加上贯穿全程的执行轨迹 [TemplateExecutionTrace]。
 */

/**
 * COMPOSE 阶段产物：一次模板轮次的「出账」。
 *
 * @param prompt 最终发往引擎的用户消息（含变量替换 + 分析附录）
 * @param behaviorId 行为 id（回包校验时定位行为）
 * @param declaredPhases 本轮实际启用的阶段（轨迹对账用）
 * @param enrichmentNotes 附录注入说明（如「注入 4 条静态规则命中」；报告展示）
 * @param metadata 行为自定义元数据（如拼装耗时、附录字符数）
 */
data class TemplateTurnRequest(
    val prompt: String,
    val behaviorId: String,
    val declaredPhases: Set<TemplatePhaseKind> = emptySet(),
    val enrichmentNotes: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(prompt.isNotBlank()) { "composed prompt must not be blank" }
    }

    /** 提示词字符数（预算统计键）。 */
    val promptChars: Int get() = prompt.length
}

/**
 * 单阶段执行轨迹。
 *
 * @param phase 阶段
 * @param ok 是否成功（防御式失败也算一次执行，ok=false + note 留痕）
 * @param durationMs 耗时（注入时钟差值）
 * @param note 单行注记（如「命中 4 条规则」「修复 2 处违规」）
 */
data class TemplatePhaseTrace(
    val phase: TemplatePhaseKind,
    val ok: Boolean,
    val durationMs: Long,
    val note: String = ""
)

/**
 * 整轮执行轨迹（工坊「运行详情」面板与调试日志消费）。
 *
 * @param templateId 模板 id
 * @param phases 各阶段轨迹（执行顺序）
 * @param startedAt 开始时间戳（注入时钟）
 * @param finishedAt 结束时间戳（0 = 未结束）
 */
data class TemplateExecutionTrace(
    val templateId: String,
    val phases: List<TemplatePhaseTrace> = emptyList(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0
) {
    /** 总耗时（未结束时为 0）。 */
    val totalDurationMs: Long get() = if (finishedAt >= startedAt) finishedAt - startedAt else 0

    /** 是否所有已执行阶段都成功。 */
    val allOk: Boolean get() = phases.isNotEmpty() && phases.all { it.ok }

    /** 追加一个阶段轨迹（copy 语义）。 */
    fun append(trace: TemplatePhaseTrace, at: Long): TemplateExecutionTrace =
        TemplateExecutionTrace(
            templateId = templateId,
            phases = phases + trace,
            startedAt = if (startedAt == 0L) at else startedAt,
            finishedAt = at
        )
}

/**
 * 报告章节（渲染产物的结构化形态；UI 按序展示，机器侧可按键取值）。
 *
 * @param title 章节标题（已归一）
 * @param kind 章节种类（正文/列表/表格/补充说明）
 * @param lines 有序内容行（Markdown 形态，UI 直接渲染）
 * @param orderKey 排序键（契约期望顺序；补充段落排在契约段之后）
 */
data class TemplateReportSection(
    val title: String,
    val kind: Kind,
    val lines: List<String>,
    val orderKey: Int = 0
) {
    /** 章节种类。 */
    enum class Kind(val label: String) {
        VERDICT("结论"),
        BODY("正文"),
        LIST("列表"),
        TABLE("表格"),
        SUPPLEMENT("补充")
    }

    /** 章节纯文本。 */
    fun toText(): String = (listOf(title) + lines).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * RENDER 阶段产物：模板轮次的最终交付。
 *
 * @param finalText 最终展示文本（修复 + 归一后的 Markdown）
 * @param sections 结构化章节（按 orderKey 排序）
 * @param summary 机器可读摘要（跨行为统一键见 [KEY_*] 常量；行为可追加）
 * @param repairs 已应用的修复说明（人读，报告面板展示）
 * @param residualViolations 修复后仍存在的违规（引导用户追问）
 * @param qualityScore 质量分 0.0..1.0（契约覆盖率 + 结构完整度合成）
 * @param trace 执行轨迹（含渲染阶段）
 */
data class TemplateRenderedReport(
    val finalText: String,
    val sections: List<TemplateReportSection> = emptyList(),
    val summary: Map<String, String> = emptyMap(),
    val repairs: List<String> = emptyList(),
    val residualViolations: List<TemplateContractViolation> = emptyList(),
    val qualityScore: Double = 1.0,
    val trace: TemplateExecutionTrace? = null
) {
    init {
        require(qualityScore in 0.0..1.0) { "qualityScore must be in 0.0..1.0" }
    }

    /** 阻断级发现数（跨行为统一摘要键）。 */
    val blockerCount: Int get() = summary[KEY_BLOCKER_COUNT]?.toIntOrNull() ?: 0

    /** 该轮是否经过自动修复。 */
    val wasRepaired: Boolean get() = repairs.isNotEmpty()

    companion object {
        const val KEY_BLOCKER_COUNT = "blockerCount"
        const val KEY_FINDING_COUNT = "findingCount"
        const val KEY_SEVERITY_TOP = "highestSeverity"
        const val KEY_REPAIRED_COUNT = "repairedCount"

        /** 空报告（防御式：回包完全不可解析时兜底，finalText 原样透传）。 */
        fun fallback(raw: String, violations: List<TemplateContractViolation>): TemplateRenderedReport =
            TemplateRenderedReport(
                finalText = raw.trim(),
                sections = listOf(
                    TemplateReportSection(
                        title = "原始回包",
                        kind = TemplateReportSection.Kind.BODY,
                        lines = raw.lines()
                    )
                ),
                summary = emptyMap(),
                repairs = emptyList(),
                residualViolations = violations,
                qualityScore = 0.0
            )
    }
}
