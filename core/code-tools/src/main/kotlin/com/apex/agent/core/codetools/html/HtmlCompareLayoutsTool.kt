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
 * # HtmlCompareLayouts — 两份 HTML 的布局差异对比
 *
 * Agent 改页面后的「改对了没」验证闭环：输入改前/改后两个 HTML 文件，
 * 返回布局差异报告 —— 骨架变化、问题清单差异（**新增 = 回归 / 消失 =
 * 修复**）、规模/容器变化、视觉画像变化（调色板/字体/主题）、重复
 * 组件增减。
 *
 * 典型工作流：写完页面 → HtmlAnalyzeLayout 拿基线 → 按用户反馈改样式
 * → HtmlCompareLayouts(before, after) 确认「目标问题修复了、没改出新
 * 问题」再交付。
 *
 * 路径解析与安全同 [HtmlAnalyzeLayoutTool]；单文件上限 2MB。
 */
/**
 * # HtmlCompareLayouts — 两份 HTML 的布局差异对比
 *
 * Agent 改页面后的「改对了没」验证闭环：输入改前/改后两个 HTML 文件，
 * 返回布局差异报告 —— 骨架变化、问题清单差异（**新增 = 回归 / 消失 =
 * 修复**）、规模/容器变化、视觉画像变化（调色板/字体/主题）、重复
 * 组件增减。
 *
 * 典型工作流：写完页面 → HtmlAnalyzeLayout 拿基线 → 按用户反馈改样式
 * → HtmlCompareLayouts(before, after) 确认「目标问题修复了、没改出新
 * 问题」再交付。
 *
 * 路径解析与安全同 [HtmlAnalyzeLayoutTool]；单文件上限 2MB。
 */
class HtmlCompareLayoutsTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "HtmlCompareLayouts",
    name = "HTML Layout Diff",
    description = """
        Compare the layout of two local HTML files (before vs after an edit) and
        return a diff report: skeleton change (bracket summary), issue-list delta
        (NEW issues = regressions to fix, GONE issues = fixed), scale/container
        deltas, visual profile changes (palette/fonts/theme) and repeated-component
        add/remove.

        Use it after editing a page to verify the edit did what you intended and
        did NOT introduce new layout problems, before showing the result to the user.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path_a", required = true, description = "HTML file BEFORE the edit (baseline)")
        string("path_b", required = true, description = "HTML file AFTER the edit")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("html")
        tag("layout")
        tag("diff")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val pathA = args.requireString("path_a")
        val pathB = args.requireString("path_b")
        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active workspace. Ask the user to select a workspace first."
            )
        val fileA = resolve(root, pathA) ?: return ToolResult.fail(ToolErrorCode.NOT_FOUND, "not an existing readable HTML file: $pathA")
        val fileB = resolve(root, pathB) ?: return ToolResult.fail(ToolErrorCode.NOT_FOUND, "not an existing readable HTML file: $pathB")
        for (f in listOf(fileA, fileB)) {
            if (f.length() > MAX_FILE_BYTES) {
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "file too large (${f.length()} bytes, max $MAX_FILE_BYTES): ${f.name}"
                )
            }
        }
        val sourceA = readFile(fileA) ?: return readFail(fileA)
        val sourceB = readFile(fileB) ?: return readFail(fileB)
        val reportA = HtmlLayoutAnalyzer.analyzeWithCascade(LightweightHtmlParser.parse(sourceA), pageTitleHint = fileA.name)
        val reportB = HtmlLayoutAnalyzer.analyzeWithCascade(LightweightHtmlParser.parse(sourceB), pageTitleHint = fileB.name)
        val diff = HtmlLayoutDiff.diff(reportA, reportB)
        return ToolResult.ok(HtmlLayoutDiff.markdown(diff))
    }

    private fun readFile(f: File): String? = try {
        f.readText(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun readFail(f: File) = ToolResult.fail(
        ToolErrorCode.EXECUTION_FAILED,
        "read failed: ${f.name}"
    )

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }

    private fun resolve(root: File, path: String): File? = try {
        val f = FilePathSafety.safeResolve(root, path)
        if (!f.isFile || !f.canRead()) return null
        val lower = f.name.lowercase()
        if (!lower.endsWith(".html") && !lower.endsWith(".htm")) return null
        f
    } catch (e: SecurityException) {
        null
    }
}
