package com.apex.agent.core.codetools.html

/**
 * ═══ HTML 布局分析报告（数据模型 + Markdown 渲染）═══
 *
 * [HtmlLayoutAnalyzer.analyze] 的产物。给 Agent 消费的两条路径：
 * 1. [markdown] —— 人/模型双友好全量报告（结构 / 树 / 响应式 / 问题清单）；
 * 2. [oneLiner] —— 上下文预算紧张时的一句话骨架速览。
 *
 * 序列化纪律：本模块报告走纯字符串拼装（Markdown），不引 kotlinx
 * @Serializable —— 布局报告是给模型读的，Markdown 的信息密度远高于 JSON，
 * 且避免把报告模型绑进序列化演进（改字段不需要迁移旧 JSON）。
 */
data class HtmlLayoutReport(
    val meta: DocumentMeta,
    val stats: StructureStats,
    val responsive: ResponsiveProfile,
    val issues: List<LayoutIssue>,
    val treeText: String,
    val bracketSummary: String,
    val truncated: String? = null
) {

    /** 错误/警告计数（报告头显示用）。 */
    val errorCount: Int get() = issues.count { it.severity == IssueSeverity.ERROR }
    val warnCount: Int get() = issues.count { it.severity == IssueSeverity.WARN }

    /** 一句话骨架：`header+nav+main[flex](aside+article)+footer · 3 err/5 warn`。 */
    fun oneLiner(): String {
        val skeleton = bracketSummary.replace(Regex("\\s+"), " ").take(160)
        return "$skeleton · ${errorCount}err/${warnCount}warn"
    }

    /** 全量 Markdown 报告。 */
    fun markdown(): String = buildString {
        appendLine("# HTML 布局分析报告")
        appendLine()
        appendLine("- 页面骨架: **${bracketSummary.take(300)}**")
        appendLine("- 规模: ${stats.elementCount} 元素 / ${stats.textChars} 文本字符 / 最深 ${stats.maxDepth} 层")
        appendLine("- 布局容器: ${stats.flexContainers} flex · ${stats.gridContainers} grid · ${stats.tables} table")
        appendLine("- 响应式: ${if (responsive.hasResponsiveViewport) "viewport OK" else "viewport 缺失"} · ${responsive.mediaQueries} 媒体查询 · ${responsive.responsiveUtilityClasses} 响应式类")
        appendLine("- 问题: **${errorCount} 错误 / ${warnCount} 警告** / ${issues.count { it.severity == IssueSeverity.INFO }} 提示")
        if (truncated != null) {
            appendLine("- ⚠️ 解析截断: $truncated（以下为部分树的分析结果）")
        }
        appendLine()

        if (issues.isNotEmpty()) {
            appendLine("## 问题清单")
            appendLine()
            appendLine("| 级别 | 类别 | 问题 | 位置/证据 |")
            appendLine("| --- | --- | --- | --- |")
            issues.take(40).forEach { issue ->
                appendLine("| ${issue.severity.label} | ${issue.category} | ${issue.message.replace("|", "/")} | ${issue.evidence ?: "-"} |")
            }
            if (issues.size > 40) appendLine("| … | | 共 ${issues.size} 条（已截断） | |")
            appendLine()
        }

        appendLine("## 布局树")
        appendLine()
        appendLine("```")
        append(treeText)
        appendLine("```")
        appendLine()

        if (responsive.fixedWidthElements.isNotEmpty()) {
            appendLine("## 固定宽度元素（手机溢出风险）")
            appendLine()
            responsive.fixedWidthElements.forEach {
                appendLine("- <${it.tag}> ${it.widthPx}px（约第 ${it.line} 行）${it.classes.joinToString(" ")}")
            }
            appendLine()
        }

        appendLine("## 标签分布 Top")
        appendLine()
        appendLine("```")
        stats.tagDistribution.forEach { (tag, count) -> appendLine("$tag × $count") }
        appendLine("```")
    }
}

/** 文档元信息。 */
data class DocumentMeta(
    val title: String,
    val titleMissing: Boolean,
    val lang: String,
    val langMissing: Boolean,
    val viewportContent: String,
    val viewportMissing: Boolean,
    val viewportIsResponsive: Boolean,
    val charset: String,
    val charsetMissing: Boolean,
    val descriptionMissing: Boolean,
    val styleBlocks: Int,
    val scriptBlocks: Int
)

/** 结构统计。 */
data class StructureStats(
    val elementCount: Int,
    val textNodeCount: Int,
    val maxDepth: Int,
    val textChars: Int,
    val flexContainers: Int,
    val gridContainers: Int,
    val tables: Int,
    val inlineStyledElements: Int,
    val classAttrElements: Int,
    val landmarkRegions: Int,
    val buttons: Int,
    val images: Int,
    val tagDistribution: Map<String, Int>
)

/** 响应式画像。 */
data class ResponsiveProfile(
    val viewportContent: String,
    val hasResponsiveViewport: Boolean,
    val mediaQueries: Int,
    val responsiveUtilityClasses: Int,
    val fixedWidthElements: List<FixedWidth>,
    val percentWidthElements: Int,
    val mobileOverflowRisk: Boolean
)

data class FixedWidth(
    val tag: String,
    val widthPx: Int,
    val line: Int,
    val classes: List<String>
)

/** 问题严重级别（rank 越小越靠前；label 用于 Markdown 表格）。 */
enum class IssueSeverity(val label: String, val rank: Int) {
    ERROR("错误", 0),
    WARN("警告", 1),
    INFO("提示", 2)
}

/** 单条布局问题。evidence 是行号或值片段（可为空）。 */
data class LayoutIssue(
    val severity: IssueSeverity,
    val category: String,
    val message: String,
    val evidence: String? = null
) {
    companion object {
        fun error(category: String, message: String, evidence: String? = null) =
            LayoutIssue(IssueSeverity.ERROR, category, message, evidence)
        fun warn(category: String, message: String, evidence: String? = null) =
            LayoutIssue(IssueSeverity.WARN, category, message, evidence)
        fun info(category: String, message: String, evidence: String? = null) =
            LayoutIssue(IssueSeverity.INFO, category, message, evidence)
    }
}
