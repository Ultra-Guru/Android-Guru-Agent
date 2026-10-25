package com.apex.agent.core.codetools.html

/**
 * ═══ 布局对比引擎（HTML 布局分析第二轮 · PR4）═══
 *
 * Agent 改页面后最关心的问题不是「现在有什么问题」，而是「我改对了
 * 没」—— 改动前的 ERROR 消失了吗？改出新问题了吗？骨架真的变了吗？
 * [diff] 吃两份 [HtmlLayoutReport]（改前/改后），产出人/模型双友好的
 * 差异报告：
 *
 * - **问题清单差异**：新增（回归，红）/ 消失（修复，绿）/ 保留计数；
 * - **骨架变化**：bracketSummary 前后对照（含规模/容器数变化）；
 * - **视觉变化**（PR2 产物在场时）：调色板增删 / 字体变化 / 主题切换；
 * - **组件变化**（PR3 产物在场时）：重复组件增删。
 *
 * 问题对齐键：(severity, category, message) 三元组 —— 同句同题视为
 * 同一问题（evidence 行号会随编辑漂移，不参与对齐）。
 */
object HtmlLayoutDiff {

    // ═══ 数据模型 ═══

    data class LayoutDiff(
        val skeletonBefore: String,
        val skeletonAfter: String,
        val skeletonChanged: Boolean,
        val elementsDelta: Int,
        val depthDelta: Int,
        val flexDelta: Int,
        val gridDelta: Int,
        val newIssues: List<LayoutIssue>,
        val fixedIssues: List<LayoutIssue>,
        val keptIssueCount: Int,
        val errorsBefore: Int,
        val errorsAfter: Int,
        val visualChanges: List<String>,
        val componentChanges: List<String>
    ) {
        /** 回归/修复净值（正 = 改善，负 = 恶化）。 */
        val netIssueDelta: Int get() = fixedIssues.size - newIssues.size
        val improved: Boolean get() = newIssues.isEmpty() && (fixedIssues.isNotEmpty() || skeletonChanged)
    }

    // ═══ 对齐键 ═══

    private fun keyOf(issue: LayoutIssue): Triple<String, String, String> =
        Triple(issue.severity.name, issue.category, issue.message)

    // ═══ 入口 ═══

    fun diff(before: HtmlLayoutReport, after: HtmlLayoutReport): LayoutDiff {
        val beforeKeys = before.issues.associateBy({ keyOf(it) }, { it })
        val afterKeys = after.issues.associateBy({ keyOf(it) }, { it })
        val newIssues = afterKeys.values.filter { keyOf(it) !in beforeKeys }
        val fixedIssues = beforeKeys.values.filter { keyOf(it) !in afterKeys }
        val kept = before.issues.count { keyOf(it) in afterKeys }
        return LayoutDiff(
            skeletonBefore = before.bracketSummary,
            skeletonAfter = after.bracketSummary,
            skeletonChanged = before.bracketSummary != after.bracketSummary,
            elementsDelta = after.stats.elementCount - before.stats.elementCount,
            depthDelta = after.stats.maxDepth - before.stats.maxDepth,
            flexDelta = after.stats.flexContainers - before.stats.flexContainers,
            gridDelta = after.stats.gridContainers - before.stats.gridContainers,
            newIssues = newIssues,
            fixedIssues = fixedIssues,
            keptIssueCount = kept,
            errorsBefore = before.errorCount,
            errorsAfter = after.errorCount,
            visualChanges = diffVisual(before.visual, after.visual),
            componentChanges = diffComponents(before.components, after.components)
        )
    }

    // ═══ 视觉差异 ═══

    private fun diffVisual(
        before: HtmlVisualProfile.VisualProfile?,
        after: HtmlVisualProfile.VisualProfile?
    ): List<String> {
        val out = mutableListOf<String>()
        if (before == null && after == null) return out
        if (before == null || after == null) {
            out.add(if (after != null) "新增视觉画像信号（改前无样式声明）" else "视觉画像信号消失（样式声明被移除）")
            return out
        }
        // 调色板增删
        val beforeColors = before.palette.map { it.color.hex }.toSet()
        val afterColors = after.palette.map { it.color.hex }.toSet()
        val added = (afterColors - beforeColors).take(4)
        val removed = (beforeColors - afterColors).take(4)
        if (added.isNotEmpty()) out.add("新增颜色 ${added.joinToString(" ")}")
        if (removed.isNotEmpty()) out.add("移除颜色 ${removed.joinToString(" ")}")
        // 主题切换
        if (before.darkThemeCandidate != after.darkThemeCandidate) {
            out.add(if (after.darkThemeCandidate) "主题切换 → 暗色画像" else "主题切换 → 亮色画像")
        }
        // 字体变化
        val beforeFonts = before.fontFamilies.map { it.family }.toSet()
        val afterFonts = after.fontFamilies.map { it.family }.toSet()
        val fontAdded = (afterFonts - beforeFonts).take(3)
        if (fontAdded.isNotEmpty()) out.add("新增字体 ${fontAdded.joinToString(" / ")}")
        // 间距节奏变化
        if (before.spacingRhythm != null && after.spacingRhythm != null && before.spacingRhythm != after.spacingRhythm) {
            out.add("间距节奏 ${before.spacingRhythm} → ${after.spacingRhythm}")
        }
        return out
    }

    // ═══ 组件差异 ═══

    private fun diffComponents(
        before: List<HtmlComponentPatterns.ComponentPattern>,
        after: List<HtmlComponentPatterns.ComponentPattern>
    ): List<String> {
        val out = mutableListOf<String>()
        val beforeByShape = before.associateBy { it.shape }
        val afterByShape = after.associateBy { it.shape }
        // 新增组件
        afterByShape.forEach { (shape, pattern) ->
            val prev = beforeByShape[shape]
            when {
                prev == null -> out.add("新增${pattern.kind.label} ×${pattern.count}（${pattern.sampleClasses.firstOrNull() ?: pattern.parentTag}）")
                prev.count != pattern.count -> out.add("${pattern.kind.label}数量 ${prev.count} → ${pattern.count}（${pattern.sampleClasses.firstOrNull() ?: pattern.parentTag}）")
            }
        }
        // 消失组件
        beforeByShape.forEach { (shape, pattern) ->
            if (shape !in afterByShape) {
                out.add("移除${pattern.kind.label} ×${pattern.count}（${pattern.sampleClasses.firstOrNull() ?: pattern.parentTag}）")
            }
        }
        return out
    }

    // ═══ 渲染 ═══

    /** 差异 Markdown（紧凑 —— diff 报告的价值在变化本身）。 */
    fun markdown(d: LayoutDiff): String = buildString {
        appendLine("# 布局对比报告（A → B）")
        appendLine()
        appendLine("- 骨架: " + (if (d.skeletonChanged) "**已变化**" else "未变化"))
        if (d.skeletonChanged) {
            appendLine("  - A: ${d.skeletonBefore.take(200)}")
            appendLine("  - B: ${d.skeletonAfter.take(200)}")
        }
        appendLine(
            "- 规模: 元素 ${signed(d.elementsDelta)} · 深度 ${signed(d.depthDelta)} · " +
                "flex ${signed(d.flexDelta)} · grid ${signed(d.gridDelta)}"
        )
        appendLine(
            "- 问题: ERROR ${d.errorsBefore} → ${d.errorsAfter} · " +
                "**新增 ${d.newIssues.size}（回归）/ 修复 ${d.fixedIssues.size} / 保留 ${d.keptIssueCount}**"
        )
        if (d.newIssues.isNotEmpty()) {
            appendLine()
            appendLine("## ⚠️ 新增问题（回归）")
            appendLine()
            d.newIssues.take(12).forEach {
                appendLine("- ${it.severity.label}·${it.category}: ${it.message}${it.evidence?.let { e -> "（$e）" } ?: ""}")
            }
        }
        if (d.fixedIssues.isNotEmpty()) {
            appendLine()
            appendLine("## ✅ 已修复")
            appendLine()
            d.fixedIssues.take(12).forEach {
                appendLine("- ${it.severity.label}·${it.category}: ${it.message}")
            }
        }
        if (d.visualChanges.isNotEmpty()) {
            appendLine()
            appendLine("## 视觉变化")
            appendLine()
            d.visualChanges.forEach { appendLine("- $it") }
        }
        if (d.componentChanges.isNotEmpty()) {
            appendLine()
            appendLine("## 组件变化")
            appendLine()
            d.componentChanges.forEach { appendLine("- $it") }
        }
    }

    private fun signed(n: Int): String = if (n > 0) "+$n" else "$n"
}
