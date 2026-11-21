package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文件写入工具 —— **只负责新建文件**
 *
 * v5 命名与职责分离（用户规格）：`Write` 写入新文件，**后续修改一律用
 * [FileEditTool]（`Edit`）**。因此：
 *
 * - 目标不存在 → 新建（原子写，见 [AtomicFileWrite]）；
 * - 目标已存在 → **拒绝**，报错文案明确指向 `Edit`（搜索-替换保内容）；
 *   确需整体重写（重新生成整个文件）时才允许显式 `overwrite=true` 逃生舱；
 * - 追加/局部改写的旧 `mode` 参数已移除 —— 那属于修改，走 `Edit`。
 *
 * 这样模型不会再"用 Write 顺手覆盖"把既有文件的其他内容抹掉。
 */
class FileWriteTool(
    private val basePath: File
) : AgentTool {

    override val id = "Write"
    override val name = "Write"
    override val description = """
        Create a NEW file. This tool never modifies an existing file by accident.

        Use Write when:
        - Creating a brand new file
        - Regenerating a whole file from scratch (pass overwrite=true explicitly)

        Use Edit when:
        - Changing any part of a file that already exists (search-replace keeps the rest intact)
        - Appending or inserting content

        If the path already exists, Write fails with an actionable error and points at Edit.

        Examples:
        - {"path": "new_script.py", "content": "#!/usr/bin/env python3\nprint('hello')"}
        - {"path": "config.json", "content": "{...}", "overwrite": true}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path of the new file"},
                "content": {"type": "string", "description": "Full file content"},
                "overwrite": {"type": "boolean", "description": "Allow replacing an existing file entirely (default: false — existing files must be changed with Edit)"},
                "create_dirs": {"type": "boolean", "description": "Create parent directories (default: true)"}
            },
            "required": ["path", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 'path' required"
            val content = json["content"]?.jsonPrimitive?.content ?: ""
            val overwrite = json["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
            val createDirs = json["create_dirs"]?.jsonPrimitive?.booleanOrNull ?: true

            val file = try {
                FilePathSafety.safeResolve(basePath, path)
            } catch (e: SecurityException) {
                return "Error: " + e.message
            }

            val existed = file.exists()
            // v5 职责分离：Write 只新建；已有文件必须走 Edit（除非显式 overwrite）。
            if (existed && !overwrite) {
                return "Error: file already exists: " + path + " — Write creates new files only. " +
                    "Use Edit(search/replace) to change it, or pass overwrite=true " +
                    "if you really intend to replace the whole file."
            }
            if (existed && !file.isFile) {
                return "Error: not a regular file: " + path
            }

            if (createDirs) file.parentFile?.mkdirs()

            val oldSize = if (existed) file.length() else 0L

            // #258：覆盖写改原子写（同目录 tmp + renameTo）——
            // 写一半崩溃/断电不再留下截断的用户文件。
            val atomic = AtomicFileWrite.writeText(file, content)

            val lineCount = content.lines().size
            buildString {
                if (existed) {
                    appendLine("✅ Overwritten: " + file.name)
                    appendLine("  Old: " + formatSize(oldSize) + " → New: " + formatSize(file.length()))
                } else {
                    appendLine("✅ Created: " + file.name)
                }
                appendLine("  " + lineCount + " lines, " + formatSize(file.length()))
                appendLine("  Path: " + file.absolutePath)
                // #258：仅罕见的 rename 失败回退时注记（成功路径输出保持不变）。
                if (!atomic) appendLine("  (non-atomic fallback: rename failed)")
            }
        } catch (e: Exception) {
            "Write error: " + e.message
        }
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "" + b + "B"
        b < 1048576 -> "" + (b / 1024) + "KB"
        else -> "" + (b / 1048576) + "MB"
    }
}
