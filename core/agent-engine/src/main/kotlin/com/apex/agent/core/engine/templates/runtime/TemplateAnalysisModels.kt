package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 确定性分析模型（PR 1 骨架）═══
 *
 * ANALYZE 阶段的产物族：规则命中 [TemplateFinding]、统计指标
 * [TemplateAnalysisStats] 与聚合结果 [TemplateAnalysis]。
 *
 * 这些模型是「模板 = 代码行为」的核心证据：每个内置模板的行为都在
 * 请求大模型之前，先用纯 Kotlin 规则算出一批确定性结论，既喂给提示词
 * （让 LLM 带着证据干活），也留在报告里（让 UI 能展示「本模板在代码侧
 * 实际做了什么」）。
 */

/** 严重度（排序即权重序：BLOCKER 最重）。 */
enum class TemplateSeverity(val label: String, val weight: Int, val marker: String) {
    /** 阻断级：正确性/安全问题，必须处理。 */
    BLOCKER("阻断", 3, "🔴"),

    /** 重要级：显著影响质量/性能/可维护性。 */
    IMPORTANT("重要", 2, "🟡"),

    /** 次要级：风格/整洁度问题，可延后。 */
    MINOR("次要", 1, "🟢"),

    /** 信息级：仅供参考（统计、观察项）。 */
    INFO("信息", 0, "🔵");

    /** 是否至少为该严重度（阈值判断的便捷读法）。 */
    fun atLeast(other: TemplateSeverity): Boolean = weight >= other.weight

    companion object {
        /** 从文本猜测严重度（LLM 回包解析用：标记 emoji / 关键词双通道）。 */
        fun fromMarker(text: String): TemplateSeverity? = when {
            text.contains(BLOCKER.marker) || text.contains("阻断") -> BLOCKER
            text.contains(IMPORTANT.marker) || text.contains("重要") -> IMPORTANT
            text.contains(MINOR.marker) || text.contains("次要") -> MINOR
            text.contains(INFO.marker) || text.contains("信息") -> INFO
            else -> null
        }
    }
}

/** 发现的分类域（报告聚合与 UI 分组用）。 */
enum class TemplateFindingCategory(val label: String) {
    CORRECTNESS("正确性"),
    SECURITY("安全性"),
    PERFORMANCE("性能"),
    MAINTAINABILITY("可维护性"),
    STYLE("风格"),
    STRUCTURE("结构"),
    COMPLETENESS("完整性"),
    CONSISTENCY("一致性"),
    RISK("风险"),
    OTHER("其他");
}

/**
 * 一条确定性发现（规则命中）。
 *
 * @param ruleId 规则 id（行为内唯一，如 kotlin.double-bang / diff.breaking-change）
 * @param category 分类域
 * @param severity 严重度
 * @param title 一句话结论（喂给 LLM 的提示行 + 报告条目标题）
 * @param location 定位（行号 / 函数名 / 文件名等，人读字符串）
 * @param evidence 命中证据（截取的原文片段，喂提示词时最有说服力）
 * @param suggestion 修复/改进建议（确定性建议，LLM 需引用并展开）
 * @param confidence 启发式置信度 0.0..1.0（1.0 = 完全确定；正则类规则多为 1.0）
 */
data class TemplateFinding(
    val ruleId: String,
    val category: TemplateFindingCategory,
    val severity: TemplateSeverity,
    val title: String,
    val location: String = "",
    val evidence: String = "",
    val suggestion: String = "",
    val confidence: Double = 1.0
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0.0..1.0 (rule=$ruleId)" }
        require(ruleId.isNotBlank()) { "ruleId must not be blank" }
    }

    /** 喂给提示词的单行形态（证据截断防打爆上下文）。 */
    fun toPromptLine(): String {
        val where = if (location.isBlank()) "" else "（$location）"
        val head = "[${severity.marker}${severity.label}] $title$where"
        return if (evidence.isBlank()) head else "$head｜证据：${shortEvidence()}"
    }

    /** 证据压缩：单行 + 长度上限（多行证据折叠为「 … 」续接）。 */
    fun shortEvidence(maxLength: Int = 120): String {
        val oneLine = evidence.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ⏎ ")
        return if (oneLine.length <= maxLength) oneLine else oneLine.take(maxLength) + "…"
    }
}

/**
 * 统计指标（键值对 + 常用数值便捷访问）。
 *
 * 行为自定义指标全部走 [metrics]（字符串键）；运行时只约定几个跨行为
 * 统计键（[KEY_RULE_COUNT] 等），避免模型膨胀。
 */
data class TemplateAnalysisStats(
    val metrics: Map<String, String> = emptyMap()
) {
    /** 命中的规则条数（跨行为统一键；报告徽标用）。 */
    val ruleCount: Int get() = intMetric(KEY_RULE_COUNT, 0)

    /** 分析覆盖的行数（跨行为统一键）。 */
    val analyzedLineCount: Int get() = intMetric(KEY_ANALYZED_LINES, 0)

    fun intMetric(name: String, default: Int): Int =
        metrics[name]?.toIntOrNull() ?: default

    fun doubleMetric(name: String, default: Double): Double =
        metrics[name]?.toDoubleOrNull() ?: default

    /** 追加/覆盖一个指标（copy 语义，返回新实例）。 */
    fun with(name: String, value: Any): TemplateAnalysisStats =
        TemplateAnalysisStats(metrics + (name to value.toString()))

    companion object {
        const val KEY_RULE_COUNT = "ruleCount"
        const val KEY_ANALYZED_LINES = "analyzedLines"

        /** 空指标（防御式默认）。 */
        fun empty(): TemplateAnalysisStats = TemplateAnalysisStats()
    }
}

/**
 * ANALYZE 阶段聚合产物。
 *
 * @param findings 确定性发现（按严重度降序排好序；运行时组装附录时直接用）
 * @param stats 统计指标
 * @param detectedLanguage 探测到的编程语言（null = 非代码输入/探测失败）
 * @param enrichedHints 追加进提示词的补充提示行（行为自定义；附录的「附加情报」段）
 * @param metadata 行为特有事实（如 diff 的文件数、正则的 AST 节点数）——
 *   键值都是字符串，行为自定义；报告面板直接展示
 * @param analyzerNotes 分析器运行注记（如「输入被截断」「空输入」），报告留痕
 */
data class TemplateAnalysis(
    val findings: List<TemplateFinding> = emptyList(),
    val stats: TemplateAnalysisStats = TemplateAnalysisStats.empty(),
    val detectedLanguage: String? = null,
    val enrichedHints: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val analyzerNotes: List<String> = emptyList()
) {
    /** 按严重度分桶计数（BLOCKER/IMPORTANT/MINOR/INFO → 条数）。 */
    fun severityCounts(): Map<TemplateSeverity, Int> {
        val counts = TemplateSeverity.entries.associateWith { 0 }.toMutableMap()
        findings.forEach { counts[it.severity] = (counts[it.severity] ?: 0) + 1 }
        return counts
    }

    /** 是否存在至少一条不低于 [severity] 的发现（快速阈值判断）。 */
    fun hasFindingsAtLeast(severity: TemplateSeverity): Boolean =
        findings.any { it.severity.atLeast(severity) }

    /** 最高严重度（无发现返回 null）。 */
    fun highestSeverity(): TemplateSeverity? =
        findings.maxByOrNull { it.severity.weight }?.severity

    companion object {
        /**
         * 规范化构造：发现按严重度降序（同级稳定保序），保证附录与报告的
         * 展示顺序在任何行为里都一致。
         */
        fun of(
            findings: List<TemplateFinding>,
            stats: TemplateAnalysisStats = TemplateAnalysisStats.empty(),
            detectedLanguage: String? = null,
            enrichedHints: List<String> = emptyList(),
            metadata: Map<String, String> = emptyMap(),
            analyzerNotes: List<String> = emptyList()
        ): TemplateAnalysis = TemplateAnalysis(
            findings = findings.sortedWith(
                compareByDescending<TemplateFinding> { it.severity.weight }.thenBy { it.ruleId }
            ),
            stats = stats,
            detectedLanguage = detectedLanguage,
            enrichedHints = enrichedHints,
            metadata = metadata,
            analyzerNotes = analyzerNotes
        )

        /** 空分析（异常路径防御式兜底：分析器崩了也不打挂管线）。 */
        fun empty(): TemplateAnalysis = TemplateAnalysis()
    }
}
