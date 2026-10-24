package com.apex.agent.core.codetools.html

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.FilePathSafety
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * # HtmlAnalyzeLayout — HTML 产物布局分析（Agent 模式与 Code 模式互用）
 *
 * 输入一个本地 HTML 文件（通常是 Agent 自己刚写出的页面产物），返回
 * 「这个页面布局长什么样 / 有什么问题」的结构化分析：
 *
 * - 页面骨架括号速览：`body > header + nav + main[flex](aside[33%]+article[67%]) + footer`；
 * - 缩进布局树（display / 关键类 / 估算宽度）；
 * - 响应式画像（viewport / 媒体查询 / 固定宽度溢出风险）；
 * - 40+ 类问题清单（语义 / 可访问性 / 定位 / 溢出 / 性能）。
 *
 * 与 BrowserAnalyzeLayout（内置浏览器实时页面几何采集）互补：
 * 本工具做**静态**分析（不需要浏览器内核，文件级、毫秒级、可离线），
 * BrowserAnalyzeLayout 做**动态**分析（真实渲染几何）。先静态后动态，
 * 省 token 也省等待。
 *
 * 路径解析与 CodeRead 同款：[CodeWorkspaceRoots] 动态根 +
 * [FilePathSafety] 穿越防护；报告体积有硬上限（默认 12KB），不会撑爆上下文。
 */
class HtmlAnalyzeLayoutTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "HtmlAnalyzeLayout",
    name = "HTML Layout Analyzer",
    description = """
        Analyze the layout of a local HTML file and return a structured report:
        page skeleton (bracket summary like body > header + main[flex](aside+article) + footer),
        indented layout tree with display kinds and estimated widths, responsive
        profile (viewport / media queries / fixed-width overflow risks), and a
        prioritized issue list (semantics, a11y, positioning, overflow, performance).

        Use it right after writing an HTML artifact to verify the layout BEFORE
        showing it to the user, and again after edits when the structure changes.
        For live pages in the built-in browser, prefer BrowserAnalyzeLayout
        (real rendered geometry) instead.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "HTML file path, relative to the active workspace root (or absolute inside it)")
        boolean("include_tree", description = "Include the full indented layout tree (default true; false = skeleton + issues only, saves tokens)")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("html")
        tag("layout")
        tag("analysis")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")
        val includeTree = args.optionalBoolean("include_tree") ?: true

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active workspace. Ask the user to select a workspace first."
            )
        val file = try {
            FilePathSafety.safeResolve(root, path)
        } catch (e: SecurityException) {
            return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, e.message ?: "path escapes workspace")
        }
        if (!file.isFile) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "not an existing file: $path (analyze target must be a local HTML file)"
            )
        }
        if (!file.canRead()) {
            return ToolResult.fail(ToolErrorCode.PERMISSION_DENIED, "cannot read: $path")
        }
        val lower = file.name.lowercase()
        if (!lower.endsWith(".html") && !lower.endsWith(".htm")) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "not an HTML file: ${file.name} (expected .html / .htm)"
            )
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "file too large (${file.length()} bytes, max ${MAX_FILE_BYTES}); " +
                    "split the page or analyze a reduced repro"
            )
        }

        val source = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "read failed: ${e.message ?: e::class.simpleName}")
        }

        val doc = LightweightHtmlParser.parse(source)
        val report = HtmlLayoutAnalyzer.analyze(doc, pageTitleHint = file.name)
        val body = renderReport(report, includeTree)
        return ToolResult.ok(body)
    }

    private fun renderReport(report: HtmlLayoutReport, includeTree: Boolean): String {
        val sections = mutableListOf<String>()
        sections.add(buildString {
            appendLine("# HTML 布局分析报告")
            appendLine()
            appendLine("- 页面骨架: **${report.bracketSummary.take(300)}**")
            appendLine("- 规模: ${report.stats.elementCount} 元素 / ${report.stats.textChars} 文本字符 / 最深 ${report.stats.maxDepth} 层")
            appendLine("- 布局容器: ${report.stats.flexContainers} flex · ${report.stats.gridContainers} grid · ${report.stats.tables} table")
            appendLine(
                "- 响应式: " + (if (report.responsive.hasResponsiveViewport) "viewport OK" else "viewport 缺失") +
                    " · ${report.responsive.mediaQueries} 媒体查询 · ${report.responsive.responsiveUtilityClasses} 响应式类"
            )
            appendLine("- 问题: **${report.errorCount} 错误 / ${report.warnCount} 警告** / ${report.issues.count { it.severity == IssueSeverity.INFO }} 提示")
            if (report.truncated != null) appendLine("- 解析截断: ${report.truncated}（部分树的分析结果）")
        })
        if (report.issues.isNotEmpty()) {
            sections.add(renderIssuesTable(report))
        }
        if (includeTree) {
            sections.add("## 布局树\n\n```\n${report.treeText.trimEnd()}\n```")
        }
        if (report.responsive.fixedWidthElements.isNotEmpty()) {
            sections.add(
                "## 固定宽度元素（手机溢出风险）\n\n" +
                    report.responsive.fixedWidthElements.joinToString("\n") {
                        "- <${it.tag}> ${it.widthPx}px（约第 ${it.line} 行）${it.classes.joinToString(" ")}"
                    }
            )
        }
        val out = sections.joinToString("\n\n")
        return if (out.length > MAX_REPORT_CHARS) {
            out.substring(0, MAX_REPORT_CHARS) + "\n…（报告已按 ${MAX_REPORT_CHARS} 字符截断；用更小的页面或 include_tree=false 复查）"
        } else out
    }

    private fun renderIssuesTable(report: HtmlLayoutReport): String = buildString {
        appendLine("## 问题清单（${report.errorCount} 错误 / ${report.warnCount} 警告）")
        appendLine()
        appendLine("| 级别 | 类别 | 问题 | 位置 |")
        appendLine("| --- | --- | --- | --- |")
        report.issues.take(ISSUE_TABLE_MAX).forEach { issue ->
            appendLine("| ${issue.severity.label} | ${issue.category} | ${issue.message.replace("|", "/")} | ${issue.evidence ?: "-"} |")
        }
        if (report.issues.size > ISSUE_TABLE_MAX) {
            appendLine("| … | | 共 ${report.issues.size} 条（已截断） | |")
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val MAX_REPORT_CHARS = 12_000
        const val ISSUE_TABLE_MAX = 30
    }
}
