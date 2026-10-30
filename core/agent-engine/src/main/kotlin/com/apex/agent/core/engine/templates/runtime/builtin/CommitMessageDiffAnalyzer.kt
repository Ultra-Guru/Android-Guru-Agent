package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 提交信息生成 —— diff 确定性分析器（PR 6）═══
 *
 * 「提交信息生成」模板的分析内核：**先于大模型**把变更说明解析成
 * 结构化事实（文件清单 / 增删统计 / 破坏性信号 / 类型候选），LLM
 * 拿到的是「已判读过的变更档案」，写作提交信息而不是猜内容。
 *
 * 两条输入通道（PREPARE 形态判定分流）：
 * 1. **统一 diff 通道**：`git diff` 产物 —— 状态机逐行解析；
 * 2. **自然语言通道**：没有 diff 形态的变更描述 —— 关键词抽取分类。
 *
 * 防御式纪律：diff 再残缺（截断 / 少头 / 乱序）都不抛异常，能解析
 * 多少给多少，analyzerNotes 留痕说明降级原因。
 */

/** 单个文件的变更记录。 */
data class DiffFileChange(
    /** 旧路径（新建文件为 null）。 */
    val oldPath: String?,
    /** 新路径（删除文件为 null）。 */
    val newPath: String?,
    /** 新增行数。 */
    val insertions: Int,
    /** 删除行数。 */
    val deletions: Int,
    /** hunk 块数。 */
    val hunks: Int,
    val isBinary: Boolean = false,
    val isRename: Boolean = false,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false
) {
    /** 展示路径（优先新路径）。 */
    val displayPath: String get() = newPath ?: oldPath ?: "(unknown)"

    /** 纯增量变更（只有新增）。 */
    val isPureAddition: Boolean get() = deletions == 0 && insertions > 0

    /** 纯删除变更。 */
    val isPureDeletion: Boolean get() = insertions == 0 && deletions > 0
}

/** 整份 diff 的解析汇总。 */
data class DiffSummary(
    val files: List<DiffFileChange>,
    /** 解析出的 hunk 头行（@@ -a,b +c,d @@ 形态原文）。 */
    val hunkHeaders: List<String>,
    /** 解析降级注记（如「输入缺少文件头，按宽松模式解析」）。 */
    val notes: List<String>,
    /** 输入是否具备统一 diff 形态。 */
    val isDiffLike: Boolean
) {
    /** 总新增行。 */
    val totalInsertions: Int get() = files.sumOf { it.insertions }

    /** 总删除行。 */
    val totalDeletions: Int get() = files.sumOf { it.deletions }

    /** 变更文件数。 */
    val fileCount: Int get() = files.size

    /** 是否有破坏性信号（由分析器填充前的原始判断为 false）。 */
    val isEmpty: Boolean get() = files.isEmpty() && hunkHeaders.isEmpty()

    /** 新建文件清单。 */
    val newFiles: List<DiffFileChange> get() = files.filter { it.isNew }

    /** 删除文件清单。 */
    val deletedFiles: List<DiffFileChange> get() = files.filter { it.isDeleted }

    /** 测试相关文件（路径含 test/spec 或文件名带 test）。 */
    val testFiles: List<DiffFileChange>
        get() = files.filter { change ->
            change.displayPath.contains(Regex("""(test|spec|__tests__)""", RegexOption.IGNORE_CASE))
        }

    /** 文档文件（markdown / docs 目录 / CHANGELOG）。 */
    val docFiles: List<DiffFileChange>
        get() = files.filter { change ->
            change.displayPath.endsWith(".md") ||
                change.displayPath.contains(Regex("""(^|/)docs?/""")) ||
                change.displayPath.contains("CHANGELOG")
        }

    /** 构建/依赖文件（gradle / package.json / lock / Cargo.toml / pom）。 */
    val buildFiles: List<DiffFileChange>
        get() = files.filter { change ->
            change.displayPath.contains("gradle") ||
                change.displayPath.endsWith(".toml") && change.displayPath.contains("Cargo") ||
                change.displayPath.endsWith("package.json") ||
                change.displayPath.contains("lock") ||
                change.displayPath.endsWith(".properties") && change.displayPath.contains("gradle")
        }

    /** CI/工作流文件。 */
    val ciFiles: List<DiffFileChange>
        get() = files.filter { change -> change.displayPath.contains(".github/") || change.displayPath.contains("ci/") }

    companion object {
        fun empty(reason: String): DiffSummary = DiffSummary(
            files = emptyList(),
            hunkHeaders = emptyList(),
            notes = listOf(reason),
            isDiffLike = false
        )
    }
}

/** 变更类型判定候选（conventional commits 类型）。 */
data class ChangeTypeCandidate(
    val type: String,
    val confidence: Double,
    /** 判定证据（人读，喂提示词）。 */
    val evidence: List<String>
)

/** 破坏性变更信号。 */
data class BreakingChangeSignal(
    val kind: Kind,
    val evidence: String,
    val location: String
) {
    enum class Kind(val label: String) {
        /** 删除了公开符号（fun/public/export 等）。 */
        REMOVED_PUBLIC_API("公开符号删除"),

        /** 提交说明里带 `!` 或 BREAKING CHANGE。 */
        EXPLICIT_MARKER("显式标记"),

        /** 配置/接口契约文件变更（schema/协议/公开常量）。 */
        CONTRACT_CHANGE("契约文件变更")
    }
}

/** 提交信息违规（结构化，可修复项标记）。 */
data class CommitMessageIssue(
    val kind: Kind,
    val detail: String,
    val repairable: Boolean
) {
    enum class Kind(val label: String) {
        BAD_HEADER_FORMAT("首行格式"),
        TYPE_NOT_ALLOWED("类型白名单"),
        SUBJECT_TOO_LONG("主题超长"),
        SUBJECT_ENDS_WITH_PERIOD("主题句号结尾"),
        BODY_LINE_TOO_LONG("正文未折行"),
        PAST_TENSE_SUBJECT("英文过去式"),
        CHINESE_REDUNDANT_SUFFIX("中文冗余后缀"),
        EMPTY_BODY("正文缺失"),
        MISSING_FOOTER("缺 footer")
    }
}

/**
 * diff / 提交信息 分析器（纯函数集合）。
 */
object CommitMessageDiffAnalyzer {

    // ═══ 统一 diff 解析 ═══

    private val DIFF_GIT_HEADER = Regex("""^diff --git (?:"?a/(?<a>[^"\n]+)"?) (?:"?b/(?<b>[^"\n]+)"?)\s*$""")
    private val OLD_FILE_LINE = Regex("""^--- (?:/dev/null|"?a/(.+?)"?)\s*$""")
    private val NEW_FILE_LINE = Regex("""^\+\+\+ (?:/dev/null|"?b/(.+?)"?)\s*$""")
    private val HUNK_HEADER = Regex("""^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@.*$""")
    private val RENAME_HINT = Regex("""^rename from (.+)$""")
    private val BINARY_HINT = Regex("""^Binary files .* differ$|^GIT binary patch$""")
    private val NEW_FILE_MODE = Regex("""^new file mode \d+$""")
    private val DELETED_FILE_MODE = Regex("""^deleted file mode \d+$""")
    private val MINUS_LINE = Regex("""^-[^\-]""")
    private val PLUS_LINE = Regex("""^\+[^\+]""")

    /**
     * 解析统一 diff（防御式）。
     *
     * 宽容点：
     * - 无 `diff --git` 头也能按 `+++`/`---`/`@@` 还原文件清单；
     * - hunk 头缺失时按 +/- 行流累计到当前文件；
     * - 任何解析中途的异常行直接跳过，绝不上抛。
     */
    fun parseUnifiedDiff(text: String): DiffSummary {
        if (text.isBlank()) return DiffSummary.empty("输入为空")
        val lines = text.lines()
        val notes = mutableListOf<String>()
        val files = mutableListOf<DiffFileChange>()
        val hunks = mutableListOf<String>()
        var sawDiffGrammar = false
        // 严格模式：见过 diff --git 头后，---/+++ 行只是信息性路径（已由头提供）
        var strictMode = false

        var oldPath: String? = null
        var newPath: String? = null
        var insertions = 0
        var deletions = 0
        var hunkCount = 0
        var isBinary = false
        var isRename = false
        var isNew = false
        var isDeleted = false

        fun flushFile() {
            if (oldPath == null && newPath == null && insertions == 0 && deletions == 0 && !isBinary) {
                return
            }
            files.add(
                DiffFileChange(
                    oldPath = oldPath,
                    newPath = newPath ?: oldPath,
                    insertions = insertions,
                    deletions = deletions,
                    hunks = hunkCount,
                    isBinary = isBinary,
                    isRename = isRename,
                    isNew = isNew,
                    isDeleted = isDeleted
                )
            )
            oldPath = null
            newPath = null
            insertions = 0
            deletions = 0
            hunkCount = 0
            isBinary = false
            isRename = false
            isNew = false
            isDeleted = false
        }

        for (line in lines) {
            val gitHeader = DIFF_GIT_HEADER.find(line)
            if (gitHeader != null) {
                flushFile()
                sawDiffGrammar = true
                strictMode = true
                oldPath = gitHeader.groups["a"]?.value
                newPath = gitHeader.groups["b"]?.value
                continue
            }
            when {
                NEW_FILE_MODE.containsMatchIn(line) -> {
                    sawDiffGrammar = true
                    isNew = true
                }
                DELETED_FILE_MODE.containsMatchIn(line) -> {
                    sawDiffGrammar = true
                    isDeleted = true
                }
                BINARY_HINT.containsMatchIn(line) -> {
                    sawDiffGrammar = true
                    isBinary = true
                }
                RENAME_HINT.containsMatchIn(line) -> {
                    sawDiffGrammar = true
                    isRename = true
                    if (oldPath == null) oldPath = RENAME_HINT.find(line)!!.groupValues[1]
                }
            }
            val oldMatch = OLD_FILE_LINE.find(line)
            if (oldMatch != null) {
                sawDiffGrammar = true
                if (!strictMode) {
                    // 宽松模式：--- 行开新文件段（上一段有内容则先收尾）
                    if (oldPath != null || newPath != null) {
                        if (hunkCount > 0 || insertions + deletions > 0 || isBinary) flushFile()
                    }
                    oldMatch.groupValues.getOrNull(1)?.let { if (oldPath == null) oldPath = it }
                }
                continue
            }
            val newMatch = NEW_FILE_LINE.find(line)
            if (newMatch != null) {
                sawDiffGrammar = true
                if (!strictMode) {
                    newMatch.groupValues.getOrNull(1)?.let { newPath = it }
                }
                continue
            }
            if (HUNK_HEADER.matches(line)) {
                sawDiffGrammar = true
                hunkCount++
                hunks.add(line.trim())
                continue
            }
            if (PLUS_LINE.containsMatchIn(line)) {
                insertions++
                continue
            }
            if (MINUS_LINE.containsMatchIn(line)) {
                deletions++
                // 破坏性信号之一在 analyze 阶段二次判定（这里只统计）
            }
        }
        flushFile()

        if (!sawDiffGrammar) {
            return DiffSummary.empty("输入不是 diff 形态")
        }
        if (files.any { it.oldPath == null && it.newPath == null }) {
            notes.add("存在无文件归属的变更行（缺少 ---/+++ 或 diff --git 头），统计按整段累计")
        }
        if (files.isEmpty() && hunks.isNotEmpty()) {
            notes.add("hunk 头存在但文件清单为空（头被截断），统计按无文件归属累计")
            files.add(
                DiffFileChange(
                    oldPath = null,
                    newPath = "(truncated)",
                    insertions = lines.count { PLUS_LINE.containsMatchIn(it) },
                    deletions = lines.count { MINUS_LINE.containsMatchIn(it) },
                    hunks = hunks.size
                )
            )
        }
        return DiffSummary(files = files, hunkHeaders = hunks, notes = notes, isDiffLike = true)
    }

    // ═══ 类型分类 ═══

    /** conventional 类型白名单与中文标签。 */
    val TYPE_LABELS: Map<String, String> = mapOf(
        "feat" to "新功能",
        "fix" to "缺陷修复",
        "refactor" to "重构",
        "docs" to "文档",
        "test" to "测试",
        "chore" to "杂项",
        "perf" to "性能",
        "ci" to "持续集成",
        "build" to "构建",
        "style" to "格式",
        "revert" to "回滚"
    )

    /**
     * 变更分类（打分制：多条证据累加，输出按置信度降序的候选序列）。
     *
     * 证据优先级：显式内容（测试文件/文档文件）> 路径启发（src 新增）>
     * 形态启发（纯删除+小体量 → refactor/fix 二选一靠删除符号模式仲裁）。
     */
    fun classifyChanges(summary: DiffSummary): List<ChangeTypeCandidate> {
        if (summary.isEmpty) return emptyList()
        val scores = LinkedHashMap<String, MutableList<String>>()
        fun vote(type: String, weight: Double, evidence: String) {
            val list = scores.getOrPut(type) { mutableListOf() }
            repeat(weight.toInt().coerceAtLeast(1)) { list.add(evidence) }
            if (weight % 1.0 > 0) list.add(evidence)  // 小数权重按附注计
        }

        val tests = summary.testFiles
        val docs = summary.docFiles
        val builds = summary.buildFiles
        val ci = summary.ciFiles
        val others = summary.files - tests.toSet() - docs.toSet() - builds.toSet() - ci.toSet()

        if (tests.isNotEmpty() && others.isEmpty()) vote("test", 3.0, "测试文件 ${tests.size} 个（${tests.take(2).joinToString { it.displayPath }}）")
        else if (tests.isNotEmpty()) vote("test", 1.5, "含测试文件 ${tests.size} 个")

        if (docs.isNotEmpty() && docs.size >= others.size) vote("docs", 3.0, "文档文件 ${docs.size} 个")
        else if (docs.isNotEmpty()) vote("docs", 1.5, "含文档文件 ${docs.size} 个")

        if (ci.isNotEmpty()) vote("ci", 3.0, "CI/工作流变更 ${ci.size} 个")
        if (builds.isNotEmpty()) vote("build", 2.5, "构建/依赖文件 ${builds.size} 个")

        val newSource = summary.newFiles.filter { it !in tests && it !in docs }
        if (newSource.isNotEmpty()) vote("feat", 2.0, "新增源文件 ${newSource.size} 个")
        else if (others.any { it.insertions > 0 }) vote("feat", 1.0, "源码净新增（${others.count { it.insertions > 0 }} 个文件含新增行）")

        // 纯删除且体量小 → refactor 线索；含修复语义词 → fix 线索
        val pureDeletions = summary.files.filter { it.isPureDeletion && !it.isDeleted && it.deletions <= 12 }
        if (pureDeletions.isNotEmpty()) vote("refactor", 1.5, "小体量纯删行 ${pureDeletions.size} 处")

        if (scores.isEmpty()) {
            vote("chore", 1.0, "无显著类型特征，回落杂项")
        }
        return scores.entries
            .map { (type, evidenceList) ->
                ChangeTypeCandidate(
                    type = type,
                    confidence = (evidenceList.size.toDouble() / 6.0).coerceAtMost(1.0),
                    evidence = evidenceList.distinct().take(4)
                )
            }
            .sortedWith(compareByDescending<ChangeTypeCandidate> { it.confidence }.thenBy { it.type })
    }

    // ═══ 破坏性变更检测 ═══

    /** 公开符号删除的行级模式（跨语言族）。 */
    private val REMOVED_PUBLIC_PATTERNS = listOf(
        """^-\s*public\s+(fun|fn|func|function|def|class|interface|struct|enum|type)\s+\w+""",
        """^-\s*fun\s+\w+""",
        """^-\s*export\s+(default\s+)?(function|class|const|let|var)\s+\w+""",
        """^-\s*pub\s+(fn|struct|enum|trait|mod)\s+\w+""",
        """^-\s*export\s+(const|function)\s+\w+"""
    )

    /** 契约类文件路径模式（变更即提示契约影响）。 */
    private val CONTRACT_PATHS = listOf(".proto", "schema", ".graphql", "openapi", "swagger", "/api/", "contract")

    /**
     * 破坏性变更信号扫描（三通道：符号删除 / 显式标记 / 契约文件）。
     */
    fun detectBreakingChanges(text: String, summary: DiffSummary): List<BreakingChangeSignal> {
        val signals = mutableListOf<BreakingChangeSignal>()
        val lines = text.lines()

        // 1) 显式标记
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (Regex("""^(feat|fix|refactor|chore|perf|build|ci|test|docs|revert|style)(\([^)]*\))?(!)""").containsMatchIn(trimmed)) {
                signals.add(BreakingChangeSignal(BreakingChangeSignal.Kind.EXPLICIT_MARKER, trimmed.take(60), "第 ${index + 1} 行"))
            }
            if (trimmed.contains("BREAKING CHANGE", ignoreCase = true)) {
                signals.add(BreakingChangeSignal(BreakingChangeSignal.Kind.EXPLICIT_MARKER, trimmed.take(60), "第 ${index + 1} 行"))
            }
            if (signals.size >= 3) break
        }

        // 2) 公开符号删除
        for ((index, line) in lines.withIndex()) {
            for (pattern in REMOVED_PUBLIC_PATTERNS) {
                if (Regex(pattern).containsMatchIn(line)) {
                    signals.add(
                        BreakingChangeSignal(
                            BreakingChangeSignal.Kind.REMOVED_PUBLIC_API,
                            line.trim().take(70),
                            "第 ${index + 1} 行"
                        )
                    )
                    break
                }
            }
            if (signals.count { it.kind == BreakingChangeSignal.Kind.REMOVED_PUBLIC_API } >= 3) break
        }

        // 3) 契约文件变更（非新建才算破坏）
        for (change in summary.files.filter { !it.isNew }) {
            if (CONTRACT_PATHS.any { change.displayPath.contains(it, ignoreCase = true) }) {
                signals.add(
                    BreakingChangeSignal(
                        BreakingChangeSignal.Kind.CONTRACT_CHANGE,
                        change.displayPath,
                        "文件 ${change.displayPath}"
                    )
                )
            }
        }
        return signals
    }

    // ═══ scope 推导 ═══

    /**
     * scope 推导：改动最集中的一级/二级目录（括号 scope 建议）。
     * 无 diff 通道时从文本里抽 kebab-case 词兜底。
     */
    fun inferScope(summary: DiffSummary, rawText: String): String? {
        if (summary.files.isNotEmpty()) {
            val dirCounts = summary.files
                .filter { it.displayPath != "(truncated)" }
                .mapNotNull { change ->
                    val parts = change.displayPath.split('/')
                    when {
                        parts.size >= 3 -> parts.subList(parts.size - 3, parts.size - 1).joinToString("/")
                        parts.size == 2 -> parts[0]
                        else -> null
                    }
                }
                .groupingBy { it }
                .eachCount()
            return dirCounts.maxByOrNull { it.value }?.key
        }
        val kebab = Regex("""\b[a-z][a-z0-9]*(?:-[a-z0-9]+)+\b""")
        return kebab.findAll(rawText).map { it.value }.groupingBy { it }
            .eachCount().maxByOrNull { it.value }?.key
    }

    // ═══ 提交信息校验与修复 ═══

    /** 首行格式：type(scope)?: subject */
    private val HEADER_PATTERN = Regex("""^(?<type>[a-z]+)(?:\((?<scope>[\w./-]+)\))?(?<bang>!)?:\s+(?<subject>.+)$""")

    /** 英文过去式常见动词表（祈使语气检查用）。 */
    private val PAST_TENSE_WORDS = setOf(
        "added", "fixed", "updated", "removed", "changed", "refactored", "improved",
        "created", "deleted", "modified", "implemented", "introduced", "moved",
        "renamed", "reverted", "optimized", "adjusted", "replaced"
    )

    /** 中文冗余后缀（提交主题应简洁）。 */
    private val CHINESE_REDUNDANT = listOf("进行了", "完成了", "实现了对", "修改了部分")

    /**
     * 校验提交信息（结构化违规清单；一条信息 = 首行 header + 空行 + 正文 + footer）。
     */
    fun validateCommitMessage(message: String): List<CommitMessageIssue> {
        if (message.isBlank()) return listOf(CommitMessageIssue(CommitMessageIssue.Kind.EMPTY_BODY, "提交信息为空", false))
        val lines = message.trim().lines()
        val issues = mutableListOf<CommitMessageIssue>()
        val header = lines.firstOrNull().orEmpty()

        val headerMatch = HEADER_PATTERN.find(header)
        if (headerMatch == null) {
            issues.add(CommitMessageIssue(CommitMessageIssue.Kind.BAD_HEADER_FORMAT, "首行不符合 type(scope): subject 形态：${header.take(40)}", true))
        } else {
            val type = headerMatch.groups["type"]?.value.orEmpty()
            if (type !in TYPE_LABELS) {
                issues.add(CommitMessageIssue(CommitMessageIssue.Kind.TYPE_NOT_ALLOWED, "类型 '$type' 不在白名单（${TYPE_LABELS.keys.joinToString("/")}）", true))
            }
            val subject = headerMatch.groups["subject"]?.value.orEmpty()
            if (subject.length > SUBJECT_MAX_CHARS) {
                issues.add(CommitMessageIssue(CommitMessageIssue.Kind.SUBJECT_TOO_LONG, "主题 ${subject.length} 字符 > $SUBJECT_MAX_CHARS", true))
            }
            if (subject.endsWith("。") || subject.endsWith(".") && !subject.endsWith("..")) {
                issues.add(CommitMessageIssue(CommitMessageIssue.Kind.SUBJECT_ENDS_WITH_PERIOD, "主题不应以句号结尾", true))
            }
            val firstWord = subject.trim().split(Regex("""\s+""")).firstOrNull().orEmpty().lowercase()
            if (firstWord in PAST_TENSE_WORDS) {
                issues.add(CommitMessageIssue(CommitMessageIssue.Kind.PAST_TENSE_SUBJECT, "英文主题应祈使语气（'$firstWord' 是过去式）", true))
            }
            if (CHINESE_REDUNDANT.any { subject.contains(it) }) {
                issues.add(CommitMessageIssue(CommitMessageIssue.Kind.CHINESE_REDUNDANT_SUFFIX, "中文主题含冗余后缀（进行了/完成了…）", true))
            }
        }

        // 正文检查：72 字符折行 + 存在性
        val bodyLines = lines.drop(1).dropWhile { it.isBlank() }
        if (bodyLines.none { it.isNotBlank() }) {
            issues.add(CommitMessageIssue(CommitMessageIssue.Kind.EMPTY_BODY, "缺少正文（空行后的详细说明）", false))
        } else {
            val tooLong = bodyLines.filter { it.length > BODY_MAX_CHARS && !it.startsWith("BREAKING") }
            if (tooLong.isNotEmpty()) {
                issues.add(CommitMessageIssue(CommitMessageIssue.Kind.BODY_LINE_TOO_LONG, "正文 ${tooLong.size} 行超过 $BODY_MAX_CHARS 字符未折行", true))
            }
        }
        return issues
    }

    /**
     * 修复提交信息（确定性修补，只修可修复项）：
     * - type 大写归一（FEAT: → feat:）；
     * - 主题句号剥离；
     * - 主题超长 → 折出副句进正文；
     * - 正文超长行 → 72 字符软折行（CJK 按字符、拉丁按词）。
     */
    fun repairCommitMessage(message: String): Pair<String, List<String>> {
        if (message.isBlank()) return message to emptyList()
        val notes = mutableListOf<String>()
        val lines = message.trim().lines().toMutableList()

        // 1) 首行归一
        val originalHeader = lines.firstOrNull().orEmpty()
        val headerMatch = HEADER_PATTERN.find(originalHeader)
        if (headerMatch == null) {
            // 非标准首行：尝试「TYPE: xxx」大写形态归一
            val upperMatch = Regex("""^(?<type>[A-Z]+)(\((?<scope>[\w./-]+)\))?(!)?:\s+(?<subject>.+)$""").find(originalHeader)
            if (upperMatch != null) {
                val subject = upperMatch.groups["subject"]?.value.orEmpty()
                    .trimEnd('.', '。', ' ')
                val fixed = buildString {
                    append(upperMatch.groups["type"]?.value?.lowercase())
                    upperMatch.groups["scope"]?.value?.let { append("($it)") }
                    if (upperMatch.groupValues[3] == "!") append("!")
                    append(": ")
                    append(subject)
                }
                lines[0] = fixed
                notes.add("类型小写归一")
            }
        } else {
            val type = headerMatch.groups["type"]?.value.orEmpty()
            val scope = headerMatch.groups["scope"]?.value
            val bang = headerMatch.groupValues[3] == "!"
            var subject = headerMatch.groups["subject"]?.value.orEmpty()
            var changed = false
            if (subject.endsWith("。") || subject.endsWith(".")) {
                subject = subject.trimEnd('。', '.')
                changed = true
                notes.add("主题句号剥离")
            }
            val firstWord = subject.trim().split(Regex("""\s+""")).firstOrNull().orEmpty().lowercase()
            if (firstWord in PAST_TENSE_WORDS) {
                subject = subject.replaceFirst(firstWord, IMPERATIVE_MAP[firstWord] ?: firstWord, ignoreCase = true)
                changed = true
                notes.add("英文主题改祈使语气")
            }
            if (subject.length > SUBJECT_MAX_CHARS) {
                val cut = subject.take(SUBJECT_MAX_CHARS)
                val overflow = subject.substring(SUBJECT_MAX_CHARS)
                subject = cut
                // 溢出部分并入正文首段
                if (lines.size > 1) {
                    lines.add(1, "")
                    lines.add(2, "（续）$overflow")
                }
                changed = true
                notes.add("主题超长部分折入正文")
            }
            if (changed || type != headerMatch.groups["type"]?.value) {
                lines[0] = buildString {
                    append(type)
                    scope?.let { append("($it)") }
                    if (bang) append("!")
                    append(": ")
                    append(subject)
                }
            }
        }

        // 2) 正文折行
        val hasBody = lines.drop(1).any { it.isNotBlank() }
        if (hasBody) {
            val bodyStart = 1
            val wrapped = mutableListOf<String>()
            var wrappedAny = false
            for (index in bodyStart until lines.size) {
                val line = lines[index]
                if (line.length <= BODY_MAX_CHARS || line.startsWith("BREAKING")) {
                    wrapped.add(line)
                } else {
                    wrapped.addAll(wrapLine(line, BODY_MAX_CHARS))
                    wrappedAny = true
                }
            }
            if (wrappedAny) {
                while (lines.size > 1) lines.removeAt(lines.size - 1)
                lines.addAll(wrapped)
                notes.add("正文按 $BODY_MAX_CHARS 字符折行")
            }
        }
        return lines.joinToString("\n") to notes
    }

    /** 软折行：拉丁词优先在空格处回退断行，CJK 按宽直接断。 */
    internal fun wrapLine(line: String, maxWidth: Int): List<String> {
        if (line.length <= maxWidth) return listOf(line)
        val out = mutableListOf<String>()
        var start = 0
        while (start < line.length) {
            var end = (start + maxWidth).coerceAtMost(line.length)
            if (end < line.length) {
                val chunk = line.substring(start, end)
                val lastSpace = chunk.lastIndexOf(' ')
                val hasCjk = chunk.any { isCjkChar(it) }
                // 拉丁且存在较靠后的空格 → 在空格处断；CJK 或无空格 → 硬断
                if (lastSpace > maxWidth / 2 && !hasCjk) {
                    end = start + lastSpace
                }
            }
            val piece = line.substring(start, end).trimEnd()
            if (piece.isNotEmpty()) out.add(piece)
            start = end
        }
        return out
    }

    private fun isCjkChar(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }

    /** 过去式 → 祈使式对照（修复用）。 */
    val IMPERATIVE_MAP: Map<String, String> = mapOf(
        "added" to "add",
        "fixed" to "fix",
        "updated" to "update",
        "removed" to "remove",
        "changed" to "change",
        "refactored" to "refactor",
        "improved" to "improve",
        "created" to "create",
        "deleted" to "delete",
        "modified" to "modify",
        "implemented" to "implement",
        "introduced" to "introduce",
        "moved" to "move",
        "renamed" to "rename",
        "reverted" to "revert",
        "optimized" to "optimize",
        "adjusted" to "adjust",
        "replaced" to "replace"
    )

    const val SUBJECT_MAX_CHARS = 50
    const val BODY_MAX_CHARS = 72
}

// ═════════════════════════════════════════════════════════════════════════
//  确定性提交信息草稿生成（ANALYZE 阶段产物，LLM 在此基础上润色）
// ═════════════════════════════════════════════════════════════════════════

/** 提交信息草稿（确定性生成，供提示词注入与用户预览）。 */
data class CommitDraft(
    /** 首行（type(scope): subject）。 */
    val header: String,
    /** 正文要点（每条一个文件组或一个事实）。 */
    val bodyLines: List<String>,
    /** footer 行（破坏性变更说明；无破坏为空）。 */
    val footer: String?
) {
    /** 完整文本（header + 空行 + 正文 + 空行 + footer）。 */
    fun toMessage(): String = buildString {
        append(header)
        if (bodyLines.isNotEmpty()) {
            append("\n\n")
            append(bodyLines.joinToString("\n"))
        }
        if (!footer.isNullOrBlank()) {
            append("\n\n")
            append(footer)
        }
    }
}

/** 文件分组（正文要点的组织粒度）。 */
data class FileGroup(
    /** 组名（顶级目录或类型标签）。 */
    val name: String,
    val files: List<DiffFileChange>
) {
    /** 组内净变化。 */
    val netChange: Int get() = files.sumOf { it.insertions - it.deletions }
}

/**
 * 提交信息草稿生成器：从判读事实直接产出一份可用的 conventional 草稿。
 * 这是「模板行为不只是提示词」的落点之二 —— LLM 拿到的附录里带着
 * 草稿，任务是核对与润色，而不是从零造句。
 */
object CommitDraftGenerator {

    /**
     * 生成草稿。
     *
     * @param summary diff 解析汇总
     * @param topType 类型判定首选
     * @param scope scope 建议（可空）
     * @param breaking 破坏性信号（非空时强制 footer）
     * @param style 风格参数（非 conventional 时正文要点改为段落式）
     */
    fun generate(
        summary: DiffSummary,
        topType: String,
        scope: String?,
        breaking: List<BreakingChangeSignal>,
        style: String = "conventional commits"
    ): CommitDraft {
        val type = if (topType in CommitMessageDiffAnalyzer.TYPE_LABELS) topType else "chore"
        val header = buildString {
            append(type)
            scope?.takeIf { it.isNotBlank() && it.length <= SCOPE_MAX_CHARS }?.let { append("($it)") }
            if (breaking.isNotEmpty()) append("!")
            append(": ")
            append(subjectFor(summary, type, breaking.isNotEmpty()))
        }
        val bodyLines = buildBody(summary, breaking, style)
        val footer = if (breaking.isNotEmpty()) {
            "BREAKING CHANGE: " + breaking.joinToString("；") { "${it.kind.label}（${it.evidence.take(40)}）" }
        } else {
            null
        }
        return CommitDraft(header, bodyLines, footer)
    }

    /** 主题行启发：按类型与变更形状给出中文短句（≤50 字符硬约束）。 */
    internal fun subjectFor(summary: DiffSummary, type: String, breaking: Boolean): String {
        val subject = when {
            breaking -> "不兼容变更：" + primaryArea(summary)
            summary.newFiles.isNotEmpty() && type == "feat" -> "新增" + describeArea(summary.newFiles)
            summary.deletedFiles.isNotEmpty() && summary.files.size == summary.deletedFiles.size ->
                "移除" + describeArea(summary.deletedFiles)
            type == "test" -> "补充" + describeArea(summary.testFiles.ifEmpty { summary.files }) + "测试"
            type == "docs" -> "更新" + describeArea(summary.docFiles.ifEmpty { summary.files }) + "文档"
            type == "ci" -> "调整 CI 流水线"
            type == "build" -> "调整构建与依赖"
            type == "perf" -> "优化" + primaryArea(summary) + "性能"
            type == "fix" -> "修复" + primaryArea(summary) + "问题"
            else -> "调整" + primaryArea(summary)
        }
        return if (subject.length > CommitMessageDiffAnalyzer.SUBJECT_MAX_CHARS) {
            subject.take(CommitMessageDiffAnalyzer.SUBJECT_MAX_CHARS - 1) + "…"
        } else {
            subject
        }
    }

    /** 主要影响面（改动量最大的组名）。 */
    private fun primaryArea(summary: DiffSummary): String {
        val groups = groupFiles(summary)
        return groups.maxByOrNull { kotlin.math.abs(it.netChange) + it.files.size * 2 }?.name ?: "整体逻辑"
    }

    /** 区域描述（文件数 + 首个文件名）。 */
    private fun describeArea(files: List<DiffFileChange>): String {
        if (files.isEmpty()) return "相关"
        val first = files.first().displayPath.substringAfterLast('/')
        return when {
            files.size == 1 -> first.removeFileExtension()
            else -> "${first.removeFileExtension()} 等 ${files.size} 处"
        }
    }

    private fun String.removeFileExtension(): String =
        substringBeforeLast('.')

    /** 文件分组：顶级目录优先，散件归「杂项」。 */
    fun groupFiles(summary: DiffSummary): List<FileGroup> {
        if (summary.files.isEmpty()) return emptyList()
        val groups = LinkedHashMap<String, MutableList<DiffFileChange>>()
        for (file in summary.files) {
            val parts = file.displayPath.split('/')
            val name = when {
                parts.size >= 3 -> parts.subList(0, 3).joinToString("/")
                parts.size == 2 -> parts[0]
                else -> "根目录"
            }
            groups.getOrPut(name) { mutableListOf() }.add(file)
        }
        return groups.map { (name, files) -> FileGroup(name, files) }
            .sortedByDescending { kotlin.math.abs(it.netChange) + it.files.size * 2 }
    }

    /** 正文要点：每组一行（路径 + 净变化），conventional 风格用列表式。 */
    private fun buildBody(
        summary: DiffSummary,
        breaking: List<BreakingChangeSignal>,
        style: String
    ): List<String> {
        if (summary.files.isEmpty()) return listOf("（无文件级事实，基于变更说明生成）")
        val lines = mutableListOf<String>()
        if (style.contains("conventional", ignoreCase = true)) {
            for (group in groupFiles(summary).take(MAX_BODY_GROUPS)) {
                val net = group.netChange
                val trend = when {
                    net > 0 -> "+$net"
                    net < 0 -> "$net"
                    else -> "±0"
                }
                val fileList = group.files.take(3).joinToString("、") { it.displayPath.substringAfterLast('/') }
                val more = if (group.files.size > 3) " 等 ${group.files.size} 个文件" else ""
                lines.add("- ${group.name}：$fileList$more（$trend）")
            }
        } else {
            lines.add("本次变更覆盖 ${summary.fileCount} 个文件（+${summary.totalInsertions} -${summary.totalDeletions}）。")
        }
        if (breaking.isNotEmpty()) {
            lines.add("- 破坏性影响：${breaking.size} 处（详见 footer），需在发布说明中给出迁移指引")
        }
        return lines
    }

    const val SCOPE_MAX_CHARS = 24
    const val MAX_BODY_GROUPS = 6
}
