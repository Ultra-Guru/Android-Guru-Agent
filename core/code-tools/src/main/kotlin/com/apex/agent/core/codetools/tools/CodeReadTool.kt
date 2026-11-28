package com.apex.agent.core.codetools.tools

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
 * # CodeRead — 编码级文件读取（业界标准 CLI 编码智能体 read 契约）
 *
 * 与 Agent 模式的 Read（视口滚动）互补，本工具面向**编码会话**的读取契约：
 *
 * - 输出以 `N: 内容` 行号前缀渲染（cat -n 风格）——CodeEdit 的 old_string
 *   **必须去掉行号前缀**后引用原文，这一契约写进描述防止模型把行号当内容；
 * - 文件/目录二合一：目标是目录时返回按字母序的条目清单（目录带 `/` 尾），
 *   同样支持 offset/limit 分页 —— "先 ls 再 cd 式"的探索在单工具内闭环；
 * - 未命中时给出同目录**相似名建议**（Did you mean…）—— 显著降低路径幻觉循环；
 * - 三重上限：默认 800 行 / 单行 2000 字符截断 / 累计 50KB —— 超限提示用
 *   offset 续读，保证大文件不会撑爆上下文窗口；
 * - 二进制嗅探（NUL 字节 + 不可打印占比）→ 明确报错而非乱码。
 *
 * 根目录经 [CodeWorkspaceRoots] 动态解析（Code 模式切换 workspace 即时生效），
 * 路径穿越/符号链接逃逸经 [FilePathSafety] 拒绝。
 */
class CodeReadTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "CodeRead",
    name = "Code Read",
    description = """
        Read a file or list a directory in the active coding workspace.

        File output format: every line is prefixed with "N: " (line number, 1-based).
        When using CodeEdit, strip the "N: " prefix first — old_string must match the
        raw file content exactly.

        - File: returns up to `limit` lines from `offset` (1-based). Default limit 800.
          For long files, continue reading with a larger offset instead of
          re-reading from the start.
        - Directory: lists entries one per line, alphabetically sorted
          (subdirectories have a trailing /).
        - File not found: similar names in the same directory are suggested.

        Usage guidance:
        - Avoid tiny slices: if you need more context around a match, read a
          larger window instead of many 30-line reads.
        - When you know you'll need several files, batch multiple read calls in
          one turn instead of sequential calls.
        - Reading .env-style secret files may require user confirmation.

        Size limits: 2000 chars per line, 50KB per read. Read the file in slices
        with offset/limit instead of requesting everything at once.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true, description = "File or directory path, relative to the workspace root (or absolute inside it)")
        integer("offset", description = "1-based start line for files / start index for directory entries", minimum = 1.0)
        integer("limit", description = "Max lines (files) or entries (directories) to return. Default 800, max 2000", minimum = 1.0, maximum = 2000.0)
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        risk(com.apex.agent.core.tools.ToolRisk.LOW)
        tag("code")
        tag("read")
        annotations(com.apex.agent.core.tools.ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")
        val offset = args.optionalInt("offset") ?: 1
        val limit = (args.optionalInt("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active coding workspace. Ask the user to open the Code screen and select a workspace first."
            )

        val file = try {
            FilePathSafety.safeResolve(root, path)
        } catch (e: SecurityException) {
            return ToolResult.fail(ToolErrorCode.SANDBOX_VIOLATION, e.message ?: "path escapes workspace")
        }

        if (!file.exists()) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                buildNotFoundMessage(root, file, path)
            )
        }

        if (file.isDirectory) {
            return ToolResult.ok(renderDirectory(file, offset, limit))
        }
        if (!file.canRead()) {
            return ToolResult.fail(ToolErrorCode.PERMISSION_DENIED, "cannot read: $path")
        }
        if (isBinary(file)) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "binary file (${formatSize(file.length())}) — use TerminalExec to inspect it"
            )
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult.fail(
                ToolErrorCode.INVALID_ARGUMENT,
                "file too large (${formatSize(file.length())}, max ${formatSize(MAX_FILE_BYTES)}). " +
                    "Read it in slices with CodeRead + shell tools (grep/sed), or increase offset."
            )
        }

        return ToolResult.ok(renderFile(file, offset, limit))
    }

    // ── 渲染 ─────────────────────────────────────────────────────────

    private fun renderFile(file: File, offset: Int, limit: Int): String {
        val window = readFileWindow(file, offset, limit)
        val total = window.totalLines
        if (total == 0) return "${relPath(file)} — empty file (0 lines)"

        val start = window.startLine
        return buildString {
            appendLine("<path>${relPath(file)}</path>")
            appendLine("<type>file</type>")
            appendLine("<total_lines>$total</total_lines>")
            append("<content>")
            // #830：累计 50KB 上限兑现 KDoc 三重上限的第三重（旧实现上限
            // 实为 MAX_LIMIT × MAX_LINE_CHARS ≈ 4MB，比承诺大 80 倍）。按
            // UTF-8 字节计（CJK 行不会被 char 计数口径放行）；首行不卡
            // （bytes>0 守卫）保证永远至少返回一行。
            var bytes = 0
            var lastShown = start
            var capped = false
            for (i in window.lines.indices) {
                val lineNo = start + i + 1
                val raw = window.lines[i]
                val line = if (raw.length > MAX_LINE_CHARS) raw.take(MAX_LINE_CHARS) + "…[truncated]" else raw
                val rendered = "\n$lineNo: $line"
                val renderedBytes = rendered.toByteArray(Charsets.UTF_8).size
                if (bytes > 0 && bytes + renderedBytes > MAX_TOTAL_OUTPUT_BYTES) {
                    capped = true
                    break
                }
                append(rendered)
                bytes += renderedBytes
                lastShown = lineNo
            }
            append("\n</content>")
            when {
                capped -> append(
                    "\n(Output reached the 50KB per-read limit at line $lastShown. " +
                        "Use offset=${lastShown + 1} to continue.)"
                )
                start + window.lines.size < total -> append(
                    "\n(Showing lines ${start + 1}-${start + window.lines.size} of $total. " +
                        "Use offset=${start + window.lines.size + 1} to continue.)"
                )
                start > 0 -> append("\n(Showing lines ${start + 1}-${start + window.lines.size} of $total.)")
            }
        }
    }

    private fun renderDirectory(dir: File, offset: Int, limit: Int): String {
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return "<path>${relPath(dir)}</path>\n<type>directory</type>\n(cannot list: permission denied)"

        val total = entries.size
        if (total == 0) {
            return "<path>${relPath(dir)}</path>\n<type>directory</type>\n(empty directory)"
        }
        val start = (offset - 1).coerceIn(0, total - 1)
        val end = minOf(start + limit, total)

        return buildString {
            appendLine("<path>${relPath(dir)}</path>")
            appendLine("<type>directory</type>")
            appendLine("<total_entries>$total</total_entries>")
            append("<entries>")
            for (i in start until end) {
                val e = entries[i]
                val suffix = if (e.isDirectory) "/" else ""
                append("\n${e.name}$suffix")
            }
            append("\n</entries>")
            if (end < total) append("\n(Showing entries ${start + 1}-$end of $total. Use offset=${end + 1} to continue.)")
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /** 文件窗口读取结果（#830 流式）：总行数 + 窗口起始下标（0-based）+ 窗口行。 */
    private class FileWindow(val totalLines: Int, val startLine: Int, val lines: List<String>)

    /**
     * #830：流式窗口读取 —— 单遍计数 + 只保留窗口行。
     *
     * 旧实现每次分页都 `readText` 整文件（≤5MB 全量入内存 + 全行 split 分配），
     * 只取 800 行；offset 分页反复重读大文件在闪存上可感知卡顿。现在单遍
     * BufferedReader：总行数计数与窗口捕获同遍完成，内存/分配 O(窗口)。
     *
     * 行语义与旧 split 实现逐字对齐：末尾换行不产生额外空行；offset 超出
     * EOF 时钳到末行（旧 coerceIn(0, total-1) 行为保留 —— 窗口为空且越界
     * 时回填末行）。
     */
    private fun readFileWindow(file: File, offset: Int, limit: Int): FileWindow {
        val wanted = (offset - 1).coerceAtLeast(0)
        val window = mutableListOf<String>()
        var lastLine: String? = null
        var total = 0
        file.bufferedReader(Charsets.UTF_8).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                lastLine = line
                if (total >= wanted && window.size < limit) window += line
                total++
            }
        }
        // 越界钳位：旧实现 start=total-1 → 只显示末行。
        if (window.isEmpty() && total > 0 && wanted >= total) {
            return FileWindow(total, total - 1, listOfNotNull(lastLine))
        }
        return FileWindow(total, wanted, window)
    }

    private fun relPath(file: File): String {
        val root = roots.activeRoot() ?: return file.path
        val rootPath = root.canonicalPath.trimEnd('/')
        val filePath = file.canonicalPath
        return if (filePath == rootPath) "/" else filePath.removePrefix("$rootPath/")
    }

    private fun buildNotFoundMessage(root: File, file: File, path: String): String {
        val parent = file.parentFile?.takeIf { it.exists() } ?: return "file not found: $path"
        val wanted = file.name.lowercase()
        val siblings = parent.listFiles()?.map { it.name } ?: emptyList()
        // 相似名建议：包含关系或编辑距离相似度 ≥ 0.75（拼写笔误兜底）
        val similar = siblings.filter { name ->
            val n = name.lowercase()
            n == wanted || n.contains(wanted) || wanted.contains(n) ||
                editSimilarity(n, wanted) >= 0.75
        }.take(3)
        return if (similar.isEmpty()) {
            "file not found: $path (workspace root: ${root.name})"
        } else {
            "file not found: $path — did you mean: ${similar.joinToString(", ")}?"
        }
    }

    /** 归一化编辑距离相似度（1=全等，0=完全不同；小字符串开销可忽略）。 */
    private fun editSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1)
        val curr = IntArray(b.length + 1)
        for (j in 0..b.length) prev[j] = j
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun isBinary(file: File): Boolean {
        if (file.length() > MAX_FILE_BYTES) return false // 走大小上限分支报错
        val bytes = ByteArray(minOf(file.length(), 8192L).toInt())
        val read = file.inputStream().use { it.read(bytes) }
        if (read <= 0) return false
        var unprintable = 0
        for (i in 0 until read) {
            val b = bytes[i]
            if (b == 0.toByte()) return true
            if (b < 0x09 || (b in 0x0e..0x1f)) unprintable++
        }
        return unprintable.toDouble() / read > 0.30
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    private companion object {
        const val DEFAULT_LIMIT = 800
        const val MAX_LIMIT = 2000
        const val MAX_LINE_CHARS = 2000
        const val MAX_FILE_BYTES = 5L * 1024 * 1024 // 5MB readText 上限
        /** #830：单次读取累计输出上限（UTF-8 字节）—— KDoc「三重上限」第三重。 */
        const val MAX_TOTAL_OUTPUT_BYTES = 50 * 1024
    }
}
