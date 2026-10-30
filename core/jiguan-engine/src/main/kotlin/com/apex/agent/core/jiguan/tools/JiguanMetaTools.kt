package com.apex.agent.core.jiguan.tools

import com.apex.agent.core.jiguan.JiguanOrchestrator
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk

/**
 * # 元工具（Capability / Recipes / Knowledge）
 *
 * - [JiguanCapabilityReportTool]：五域可用性 + 降级指引（Agent 决策一手依据）；
 * - [JiguanRecipesTool]：高频场景配方（登录加密分析 / 去广告 / 数值修改）；
 * - [JiguanKnowledgeQueryTool] / [JiguanKnowledgeRememberTool]：全场景记忆
 *   RAG 的查询与蒸馏面 —— 同类 App 直接复用历史结论。
 */
// ═══════════════════════════════════════════════════════════════════

class JiguanCapabilityReportTool constructor(
    private val orchestrator: JiguanOrchestrator,
) : AgentTool {

    override val id = "JiguanCapabilityReport"
    override val name = "Jiguan: Capability Report"
    override val description = """
        Report the availability of all four Jiguan control domains (process-memory,
        java-art, native, network, system) with the active channel and, when absent,
        exact setup guidance. ALWAYS call this before planning a Jiguan workflow.

        Example: {}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {},
            "required": []
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.UTILITY)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "capability")
        .build()

    override suspend fun execute(arguments: String): String {
        val report = orchestrator.capabilityReport()
        return buildString {
            appendLine("机关 (Jiguan) capability domains:")
            report.forEach { entry ->
                appendLine(
                    "- ${entry.domain}: ${if (entry.available) "AVAILABLE" else "OFFLINE"}" +
                        (if (entry.channel != "none") " [${entry.channel}]" else ""),
                )
                if (!entry.available) {
                    entry.guidance.lineSequence().filter { it.isNotBlank() }.forEach { appendLine("  ↳ $it") }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanRecipesTool constructor(
    private val orchestrator: JiguanOrchestrator,
) : AgentTool {

    override val id = "JiguanRecipes"
    override val name = "Jiguan: Playbook Recipes"
    override val description = """
        List guided workflows (recipes) for high-frequency reverse-engineering
        scenarios: login-encryption analysis, splash-ad removal, numeric memory edit.
        Follow the steps in order — they encode tool chaining discipline.

        Example: {"recipe": "analyze-login-encryption"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "recipe": {"type": "string", "description": "Recipe id (optional — omit to list all)"}
            },
            "required": []
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.UTILITY)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "recipe", "playbook")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments)
        val wanted = JiguanArgs.string(args, "recipe")
        val recipes = orchestrator.recipes().filter { wanted.isBlank() || it.id == wanted }
        if (recipes.isEmpty()) return "No recipe named \"$wanted\". Omit the parameter to list all."
        return buildString {
            recipes.forEach { recipe ->
                appendLine("### ${recipe.title} (${recipe.id})")
                recipe.steps.forEach { appendLine(it) }
                appendLine()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanKnowledgeQueryTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanKnowledgeQuery"
    override val name = "Jiguan: Knowledge Query (RAG)"
    override val description = """
        Query the universal memory RAG for prior reverse-engineering knowledge about
        an app or app-family: hook signatures, memory landmarks, protocol notes and
        step recipes distilled from past sessions. Reuse before re-deriving.

        Example: {"query": "login encryption", "package": "com.example.app"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Text query (keywords)"},
                "package": {"type": "string", "description": "App package for exact-match profile (optional)"}
            },
            "required": ["query"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.MEMORY)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "knowledge", "rag")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val query = JiguanArgs.string(args, "query")
        val pkg = JiguanArgs.string(args, "package").ifBlank { null }
        if (query.isBlank() && pkg == null) return "Error: missing 'query' (or 'package' for profile mode)"
        val entries = if (query.isBlank()) {
            runtime.knowledge.profile(pkg ?: "")
        } else {
            runtime.knowledge.query(query, pkg)
        }
        if (entries.isEmpty()) return "No knowledge entries matched. This app/family is new — derive fresh and remember via JiguanKnowledgeRemember."
        return buildString {
            appendLine("Knowledge entries (${entries.size}):")
            entries.forEach { entry ->
                appendLine("- [${entry.category}] ${entry.appPackage} · ${entry.title} (ok=${entry.successCount})")
                entry.content.lineSequence().take(12).forEach { appendLine("  $it") }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanKnowledgeRememberTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanKnowledgeRemember"
    override val name = "Jiguan: Knowledge Remember"
    override val description = """
        Distill a reverse-engineering conclusion into the universal memory RAG so
        future sessions (and sibling apps) reuse it. Categories: hook-signature,
        memory-landmark, protocol, recipe. Call after every successful analysis.

        Example: {"package": "com.example.app", "title": "login AES key", "category": "hook-signature", "content": "com.x.EncryptUtil.aesEncrypt(key=static String KEY...)", "keywords": "aes login encrypt"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "App package"},
                "title": {"type": "string", "description": "Entry title (unique per package)"},
                "category": {"type": "string", "enum": ["hook-signature", "memory-landmark", "protocol", "recipe"]},
                "content": {"type": "string", "description": "The distilled knowledge (hook code / address layout / protocol fields / steps)"},
                "keywords": {"type": "string", "description": "Space-separated keywords for retrieval"}
            },
            "required": ["package", "title", "content"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.MEMORY)
        .risk(ToolRisk.MEDIUM)
        .tag("jiguan", "knowledge", "rag")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pkg = JiguanArgs.string(args, "package")
        val title = JiguanArgs.string(args, "title")
        val content = JiguanArgs.string(args, "content")
        if (pkg.isBlank() || title.isBlank() || content.isBlank()) {
            return "Error: required parameters package/title/content"
        }
        return runCatching {
            val entry = runtime.knowledge.remember(
                appPackage = pkg,
                title = title,
                category = JiguanArgs.string(args, "category", "recipe"),
                content = content,
                keywords = JiguanArgs.string(args, "keywords"),
            )
            "Knowledge saved: [${entry.category}] ${entry.appPackage} · ${entry.title} " +
                "(keywords: ${entry.keywords.ifBlank { "auto" }})"
        }.getOrElse {
            "Error: knowledge store unavailable (${it.message ?: "offline"})"
        }
    }
}
