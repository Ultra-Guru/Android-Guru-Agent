package com.apex.agent.core.codetools.repomap

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema
import java.io.File

/**
 * # CodeRepoMap —— 仓库依赖地图（Aider repo-map 的 Kotlin 移植）
 *
 * 用「个性化 PageRank + token 预算二分」把整个工作区浓缩成一张骨架地图：
 * 哪个文件定义了哪些符号、谁引用谁、当前讨论相关的文件最相关 —— 模型
 * 花约 1k token 就能拿到仓库全局视野，再决定读哪些文件。
 *
 * 与 CodeGlob（找文件名）/ CodeGrep（找内容）互补：本工具回答「仓库长
 * 什么样、入口在哪」的结构问题。
 *
 * 排名算法见 [RepoMapBuilder]（Aider `repomap.py` 全套乘数）；
 * 骨架渲染与预算二分见 [SkeletonRenderer]。
 */
class CodeRepoMapTool(
    private val roots: CodeWorkspaceRoots
) : BaseTool(
    id = "CodeRepoMap",
    name = "Code Repo Map",
    description = """
        Build a ranked repository map of the coding workspace: which files
        define which symbols, and which files reference them, condensed to
        a token budget (default ~1024 tokens).

        Powered by personalized PageRank (port of Aider's repo-map): files
        related to the current task are ranked higher; every entry is a
        definition signature line, with uninteresting lines elided.

        Use this FIRST when you need the big picture of an unfamiliar repo
        (entry points, module structure, who-defines-what), then CodeRead
        the files that matter. Complements CodeGrep (search content) and
        CodeGlob (search names).
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer(
            "max_tokens",
            description = "Token budget for the map (256..4096, default 1024). " +
                "Larger budgets include more files and symbols.",
            minimum = 256.0,
            maximum = 4096.0
        )
        string(
            "focus",
            description = "Comma-separated workspace-relative files currently being " +
                "discussed (e.g. \"app/src/Main.kt,core/Engine.kt\"). They boost related " +
                "files in ranking and are omitted from the map (you already see them)."
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("code", "repo-map", "structure", "navigation")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val maxTokens = args.optionalInt("max_tokens")?.coerceIn(MIN_BUDGET, MAX_BUDGET)
            ?: DEFAULT_BUDGET
        val focusFiles = args.optionalString("focus")
            ?.split(',', ';')
            ?.map { it.trim().trimStart('/') }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val root = roots.activeRoot()
            ?: return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "No active coding workspace. Ask the user to open the Code screen and select a workspace first."
            )

        // ── 收集文件（噪音目录剔除，与 CodeGlob 同款纪律）─────────
        val allFiles = mutableListOf<Pair<String, File>>() // rel → abs
        walk(root, "", 0, allFiles)
        if (allFiles.isEmpty()) {
            return ToolResult.ok("workspace has no files under ${root.name}")
        }

        // ── 标签提取（支持语言；mtime 缓存）────────────────────────
        val fileTags = mutableListOf<FileTags>()
        val fileLines = HashMap<String, List<String>>()
        for ((rel, abs) in allFiles) {
            if (!extractor.supports(rel)) continue
            if (abs.length() > MAX_FILE_BYTES) continue // 超限不抽取，仍进裸名列表
            val lines = readLinesCached(rel, abs) ?: continue
            fileTags += extractor.extract(rel, lines.joinToString("\n"))
            fileLines[rel] = lines
        }
        // 无标签文件也要参与（裸名列表 + 重要文件前置）
        val taggedPaths = fileTags.map { it.relPath }.toSet()
        for ((rel, _) in allFiles) {
            if (rel in taggedPaths) continue
            fileTags += FileTags(rel, emptyList(), emptyMap())
        }

        // ── 构建 + 预算装配 ────────────────────────────────────────
        val entries = builder.build(
            RepoMapBuilder.Input(
                fileTags = fileTags,
                chatFiles = focusFiles,
                mentionedIdentifiers = emptySet()
            )
        )
        // Aider special.py：README/构建脚本等重要文件前置（不在已排名集合中的）
        val rankedPaths = entries.map { it.relPath }.toHashSet()
        val importantPrefix = allFiles.map { it.first }
            .filter { isImportantFile(it) && it !in rankedPaths && it !in focusFiles }
            .map { RepoMapBuilder.RankedEntry(it, null, null, 0.0) }
        val ordered = importantPrefix + entries

        val map = SkeletonRenderer.assembleWithBudget(ordered, fileLines, maxTokens)
            ?: return ToolResult.ok(
                "repo map is empty — no extractable definitions found " +
                    "(supported: Kotlin, Java, Python, JS/TS, Go, Rust, C family)"
            )

        val stats = "\n\n(${allFiles.size} files, ${fileTags.sumOf { it.definitions.size }} " +
            "definitions, ~${map.second} tokens, budget $maxTokens)"
        return ToolResult.ok(map.first + stats)
    }

    // ── 文件行走 ────────────────────────────────────────────────────

    private fun walk(dir: File, rel: String, depth: Int, out: MutableList<Pair<String, File>>) {
        if (out.size >= MAX_FILES || depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (out.size >= MAX_FILES) return
            val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
            if (child.isDirectory) {
                if (child.name in EXCLUDED_DIRS) continue
                walk(child, childRel, depth + 1, out)
            } else {
                out += childRel to child // 大小/二进制在抽取阶段过滤，此处全收
            }
        }
    }

    /** 读文件（带 mtime 缓存）：不可读/二进制折叠为 null（防御式 IO）。 */
    private fun readLinesCached(rel: String, abs: File): List<String>? {
        val mtime = try {
            abs.lastModified()
        } catch (e: Exception) {
            return null
        }
        cache[rel]?.let { (cachedMtime, lines) ->
            if (cachedMtime == mtime) return lines
        }
        val lines = try {
            if (isBinary(abs)) return null
            abs.readLines()
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.TOOL,
                "CodeRepoMap",
                "skip unreadable $rel (${e.message})"
            )
            return null
        }
        cache[rel] = mtime to lines
        return lines
    }

    private fun isBinary(file: File): Boolean {
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

    /** Aider special.py is_important 移植：根级重要文件 + GitHub workflow。 */
    private fun isImportantFile(rel: String): Boolean {
        val normalized = rel.trimStart('/')
        val dir = normalized.substringBeforeLast('/', "")
        val name = normalized.substringAfterLast('/')
        if (dir == ".github/workflows" && name.endsWith(".yml")) return true
        return normalized in IMPORTANT_FILES || name in IMPORTANT_FILES && dir.isEmpty()
    }

    private val extractor = TagExtractor()
    private val builder = RepoMapBuilder()

    /**
     * mtime 缓存（会话级）：工具实例由 DI 单例注册，重复调用同一工作区时
     * 只重读变更文件 —— Aider 的 SQLite tags 缓存在 JVM 工具场景的等价物。
     */
    private val cache = HashMap<String, Pair<Long, List<String>>>()

    private companion object {
        val EXCLUDED_DIRS = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules",
            "__pycache__", ".venv", "dist", "target", ".chglog", ".changelog"
        )
        const val MAX_FILES = 2000
        const val MAX_DEPTH = 12
        const val MAX_FILE_BYTES = 256 * 1024L
        const val MIN_BUDGET = 256
        const val MAX_BUDGET = 4096
        const val DEFAULT_BUDGET = 1024

        /** Aider special.py ROOT_IMPORTANT_FILES 的精选移植（高频项）。 */
        val IMPORTANT_FILES = setOf(
            ".gitignore", "README", "README.md", "README.txt", "README.rst",
            "CONTRIBUTING.md", "LICENSE", "LICENSE.md", "CHANGELOG.md",
            "SECURITY.md", "CODEOWNERS", "requirements.txt", "pyproject.toml",
            "setup.py", "setup.cfg", "package.json", "yarn.lock", "Gemfile",
            "composer.json", "pom.xml", "build.gradle", "build.gradle.kts",
            "build.sbt", "go.mod", "go.sum", "Cargo.toml", "Cargo.lock",
            "mix.exs", "Podfile", "tsconfig.json", ".babelrc", "babel.config.js",
            ".eslintrc", ".prettierrc", ".editorconfig", ".env", ".env.example",
            "webpack.config.js", "rollup.config.js", "gulpfile.js", "Gruntfile.js",
            "jest.config.js", "karma.conf.js", "Dockerfile", "docker-compose.yml",
            "Jenkinsfile", "azure-pipelines.yml", "serverless.yml", "next.config.js",
            "nuxt.config.js", "vue.config.js", "angular.json", "swagger.yaml",
            "openapi.yaml", "Vagrantfile", "mkdocs.yml", ".npmrc", ".nvmrc"
        )
    }
}
