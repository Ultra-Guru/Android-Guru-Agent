package com.apex.agent.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.knowledge.KnowledgeBaseService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # KnowledgeBase — 角色专属知识库检索（v1.8.0 · Agent 模板增强第一线）
 *
 * 每个 Agent 角色模板（全能 / Android / Kotlin / …）配一套专属知识库
 * （assets/knowledge 目录下的 .md 文件，每套 ≥2000 行）。本工具是 Agent
 * 的检索入口，五个动作覆盖「先必读、再定位、后精读」的完整工作流：
 *
 * - `list`：有哪些知识库（含你当前角色绑定哪套——id/章节/版本）；
 * - `must_read`：**开工前必读**（必看内容节，一次调用全量返回）；
 * - `index`：快速索引（「想找什么 → 去哪一节」表）—— 一键定位；
 * - `search`：关键词检索（返回命中章节 + 上下文预览 + 得分排序）；
 * - `read`：精读一节（ref 支持章节标题 / 纯编号 / 标题前缀）。
 *
 * 检索服务为懒加载（首访扫描资产），本工具统一走 IO 调度；坏资产
 * 跳过不抛（见 [KnowledgeBaseService]）。
 */
class KnowledgeBaseTool(
    private val service: KnowledgeBaseService
) : AgentTool {

    override val id: String = "KnowledgeBase"

    override val name: String = "Knowledge Base"

    override val description: String = """
        Access your role's dedicated knowledge base volumes (every agent role ships
        one or more volumes: Android, Kotlin, all-rounder (multi-volume), each
        language expert...). Five actions:

        - list: show all knowledge base volumes grouped by role (each volume has
          its own id; a role can bind unlimited volumes).
        - must_read (kb): the MUST-READ section of that volume — read it BEFORE
          starting any task with that role active.
        - index (kb): quick index mapping "what you want" to "which section"
          — fastest way to locate a topic in that volume.
        - search (kb, query): keyword search returning ranked section hits with
          context previews.
        - read (kb, section): read one full section (section = title, plain
          number like "4", or title prefix).

        Workflow: must_read once per session; then index or search to locate;
        then read. Prefer this over guessing APIs or behaviors from memory.
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["list", "must_read", "index", "search", "read"], "description": "默认 list"},
                "kb": {"type": "string", "description": "知识库 id（list 的输出里有，如 android / kotlin / all_rounder）"},
                "query": {"type": "string", "description": "action=search 时的关键词（可空格分多词）"},
                "section": {"type": "string", "description": "action=read 时的章节 ref（标题 / 纯编号 / 前缀）"}
            }
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(arguments: String): String = withContext(Dispatchers.IO) {
        val obj = runCatching { json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return@withContext "Error: invalid arguments: ${it.message?.take(80)}" }
        val action = str(obj, "action") ?: "list"
        val kbId = str(obj, "kb")?.trim().takeIf { !it.isNullOrBlank() }
            ?: return@withContext "Error: 缺少 kb 参数（先用 action=list 查看可用知识库 id）"

        when (action) {
            "list" -> renderList()
            "must_read" -> renderMustRead(kbId)
            "index" -> renderIndex(kbId)
            "search" -> {
                val query = str(obj, "query")?.trim().takeIf { !it.isNullOrBlank() }
                    ?: return@withContext "Error: 缺少 query 参数"
                renderSearch(kbId, query)
            }
            "read" -> {
                val section = str(obj, "section")?.trim().takeIf { !it.isNullOrBlank() }
                    ?: return@withContext "Error: 缺少 section 参数（先用 index 看章节表）"
                renderRead(kbId, section)
            }
            else -> "Error: 未知 action '$action'（可用：list / must_read / index / search / read）"
        }
    }

    private fun str(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull

    private suspend fun renderList(): String {
        val all = service.listAll()
        if (all.isEmpty()) return "(没有可用知识库 —— assets/knowledge 为空或全部损坏)"
        // v2 多卷制：同角色可绑多卷（加卷即扩充）—— 按角色分组展示，
        // 每卷独立 id，检索动作（must_read/index/search/read）按卷 id 用。
        val byRole = all.groupBy { it.roleId }
        return buildString {
            appendLine("可用知识库（${all.size} 套 · ${byRole.size} 个角色，同角色多卷按序号展开）：")
            byRole.forEach { (roleId, volumes) ->
                appendLine("角色 ${roleName(roleId)} 绑定 ${volumes.size} 卷：")
                volumes.forEachIndexed { i, v ->
                    appendLine("  卷${i + 1} - id=${v.id} ·「${v.title}」· ${v.sectionCount} 章节 · v${v.version} · 必看 ${v.mustReadChars} 字")
                }
            }
            appendLine("用法：must_read 先读必看 → index/search 定位 → read 精读（kb 参数传卷 id）。")
        }
    }

    private suspend fun renderMustRead(kbId: String): String {
        val kb = service.byId(kbId)
            ?: return notFound(kbId)
        return buildString {
            appendLine("「${kb.title}」必看内容（v${kb.version}）：")
            appendLine()
            appendLine(kb.mustRead.take(MAX_OUTPUT_CHARS))
            appendLine()
            appendLine("—— 下一步：index 看章节地图，或 search 直接查关键词。")
        }
    }

    private suspend fun renderIndex(kbId: String): String {
        val kb = service.byId(kbId)
            ?: return notFound(kbId)
        if (kb.index.isEmpty()) return "(「${kb.title}」没有快速索引)"
        return buildString {
            appendLine("「${kb.title}」快速索引（想找什么 → 去哪一节）：")
            appendLine()
            kb.index.forEach { e ->
                appendLine("- ${e.topic} → ${e.target}")
            }
            appendLine()
            appendLine("用 read 精读对应章节（section 传章节标题或纯编号）。")
        }
    }

    private suspend fun renderSearch(kbId: String, query: String): String {
        val kb = service.byId(kbId)
            ?: return notFound(kbId)
        val hits = service.search(kbId, query)
        if (hits.isEmpty()) {
            return "(「${kb.title}」中没有命中「$query」—— 换个更短的关键词，或 index 全表浏览)"
        }
        return buildString {
            appendLine("「${kb.title}」检索「$query」—— ${hits.size} 处命中（按相关度排序）：")
            appendLine()
            hits.forEach { h ->
                appendLine("- [${h.via}] ${h.sectionTitle}")
                appendLine("  ${h.preview}")
            }
            appendLine()
            appendLine("用 read 精读命中的章节。")
        }
    }

    private suspend fun renderRead(kbId: String, ref: String): String {
        val section = service.readSection(kbId, ref)
            ?: return "(「$kbId」中找不到章节 '$ref' —— 用 index 查看正确章节名；也可传纯编号如 '4')"
        return buildString {
            appendLine("「$kbId」章节 ${section.title}：")
            appendLine()
            appendLine(section.body.take(MAX_OUTPUT_CHARS))
        }
    }

    private fun notFound(kbId: String): String =
        "Error: 知识库 '$kbId' 不存在（先用 action=list 查看可用 id）"

    /** roleId → 人类可读（列表展示用；未知 id 原样）。 */
    private fun roleName(roleId: String): String = when (roleId) {
        "builtin_all_rounder" -> "全能 Agent"
        "builtin_coding_full_stack" -> "全栈工程师"
        "builtin_coding_git" -> "Git 专家"
        "builtin_coding_android" -> "Android 专家"
        "builtin_coding_kotlin" -> "Kotlin 专家"
        "builtin_coding_java" -> "Java 专家"
        "builtin_coding_python" -> "Python 专家"
        "builtin_coding_javascript" -> "JavaScript 专家"
        "builtin_coding_typescript" -> "TypeScript 专家"
        "builtin_coding_go" -> "Go 专家"
        "builtin_coding_rust" -> "Rust 专家"
        "builtin_coding_cpp" -> "C/C++ 专家"
        "builtin_coding_csharp" -> "C# 专家"
        "builtin_coding_swift" -> "Swift 专家"
        "builtin_coding_php" -> "PHP 专家"
        "builtin_coding_ruby" -> "Ruby 专家"
        "builtin_coding_sql" -> "SQL 专家"
        "builtin_coding_shell" -> "Shell 专家"
        "builtin_coding_frontend" -> "前端专家"
        else -> roleId
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 12_000
    }
}
