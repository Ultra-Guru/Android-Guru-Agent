package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 提交信息生成 —— 规范体系库（PR 17 深化 I）═══
 *
 * 提交信息行为的第二层确定性引擎：[CommitMessageDiffAnalyzer] 管
 * 「diff 说明了什么变更」，本模块管「提交信息要遵守哪套规范」——
 * 把四种主流提交风格的形式规则全部代码化，让校验/修复/草稿生成
 * 都有明确的规范依据，而不是把 conventional commits 当唯一真理。
 *
 * 支持的风格体系：
 * 1. **CONVENTIONAL**（conventional commits v1.0）：
 *    `type(scope)!: subject` + 正文 + footer；
 * 2. **GITMOJI**（gitmoji.dev）：`:emoji: subject` 形态 + emoji ↔ type
 *    双向映射表；
 * 3. **PLAIN**（自由格式）：只有主题 + 正文——只查主题长度与折行，
 *    不强制 type；
 * 4. **KERNEL**（Linux 内核风格）：`subsystem: subject` 小写开头 +
 *    祈使语气 + 严格的正文折行纪律。
 *
 * 每种风格提供：主题校验（长度/形态/语气）、正文折行、footer 规范
 * （BREAKING CHANGE / issue 引用 / 署名）、草稿合成与风格互转。
 *
 * 防御式纪律：风格解析失败回落 CONVENTIONAL；任何校验不抛——
 * 未识别形态降级为单条 issue 而不是整体拒绝。
 */

/** 提交风格种类。 */
enum class CommitStyleKind(val label: String) {
    /** Conventional Commits v1.0（feat/fix/chore…）。 */
    CONVENTIONAL("conventional commits"),

    /** Gitmoji（:sparkles: / :bug: …）。 */
    GITMOJI("gitmoji"),

    /** 自由格式（无 type 前缀）。 */
    PLAIN("自由格式"),

    /** Linux 内核风格（subsystem: subject）。 */
    KERNEL("内核风格");

    companion object {
        /**
         * 从用户输入解析风格（宽容匹配中英文与常见别名）；
         * 未识别返回 null（调用方回落 CONVENTIONAL）。
         */
        fun parse(input: String): CommitStyleKind? {
            val normalized = input.trim().lowercase()
            if (normalized.isEmpty()) return null
            return when {
                normalized.contains("conventional") || normalized.contains("规范") ||
                    normalized.contains("约定式") -> CONVENTIONAL
                normalized.contains("gitmoji") || normalized.contains("表情") ||
                    normalized.contains("emoji") -> GITMOJI
                normalized.contains("kernel") || normalized.contains("内核") ||
                    normalized.contains("linux") -> KERNEL
                normalized.contains("plain") || normalized.contains("自由") ||
                    normalized.contains("普通") || normalized.contains("默认") -> PLAIN
                else -> null
            }
        }
    }
}

/** 一种提交风格的完整规范（不可变快照）。 */
data class CommitStyleSpec(
    val kind: CommitStyleKind,
    /** 主题软上限（警告阈值）。 */
    val subjectSoftMax: Int,
    /** 主题硬上限（超过必报）。 */
    val subjectHardMax: Int,
    /** 正文折行列宽。 */
    val bodyWrapColumns: Int,
    /** 是否强制 type 前缀。 */
    val requiresType: Boolean,
    /** 是否要求祈使语气（英文主题）。 */
    val requiresImperative: Boolean,
    /** 主题是否必须小写开头（KERNEL）。 */
    val requiresLowercaseStart: Boolean,
    /** 规范说明（喂给提示词）。 */
    val guidance: String
) {
    companion object {
        /** 各风格的出厂规范。 */
        val SPECS: Map<CommitStyleKind, CommitStyleSpec> = mapOf(
            CommitStyleKind.CONVENTIONAL to CommitStyleSpec(
                kind = CommitStyleKind.CONVENTIONAL,
                subjectSoftMax = 50,
                subjectHardMax = 72,
                bodyWrapColumns = 72,
                requiresType = true,
                requiresImperative = true,
                requiresLowercaseStart = false,
                guidance = "type(scope)!: subject —— type 用小写白名单；subject 祈使语气、不以句号结尾；" +
                    "破坏性变更在 type 后加 ! 且正文带 BREAKING CHANGE footer"
            ),
            CommitStyleKind.GITMOJI to CommitStyleSpec(
                kind = CommitStyleKind.GITMOJI,
                subjectSoftMax = 50,
                subjectHardMax = 72,
                bodyWrapColumns = 72,
                requiresType = false,
                requiresImperative = true,
                requiresLowercaseStart = false,
                guidance = ":emoji: subject —— emoji 与 conventional type 一一对应；" +
                    "正文与 footer 规则同 conventional"
            ),
            CommitStyleKind.PLAIN to CommitStyleSpec(
                kind = CommitStyleKind.PLAIN,
                subjectSoftMax = 50,
                subjectHardMax = 72,
                bodyWrapColumns = 72,
                requiresType = false,
                requiresImperative = false,
                requiresLowercaseStart = false,
                guidance = "无 type 前缀的自由格式——主题一行讲清做了什么；正文空行后展开动机与影响"
            ),
            CommitStyleKind.KERNEL to CommitStyleSpec(
                kind = CommitStyleKind.KERNEL,
                subjectSoftMax = 50,
                subjectHardMax = 75,
                bodyWrapColumns = 75,
                requiresType = false,
                requiresImperative = true,
                requiresLowercaseStart = true,
                guidance = "subsystem: subject —— 子系统前缀小写；subject 小写开头、祈使语气、" +
                    "不以句号结尾；正文 75 列折行；说明 what & why 而不是 how"
            )
        )

        /** 取某风格的规范（未知回落 CONVENTIONAL）。 */
        fun of(kind: CommitStyleKind): CommitStyleSpec =
            SPECS[kind] ?: SPECS.getValue(CommitStyleKind.CONVENTIONAL)
    }
}

/** 提交类型知识条目（类型白名单 + 语义说明 + 动词对）。 */
data class CommitTypeInfo(
    /** 类型小写标识（conventional type）。 */
    val type: String,
    /** 中文语义标签。 */
    val label: String,
    /** 什么时候用（喂提示词）。 */
    val whenToUse: String,
    /** 英文主题常用祈使动词（草稿合成用）。 */
    val enVerbs: List<String>,
    /** 中文主题常用动词。 */
    val zhVerbs: List<String>,
    /** gitmoji 对应（:sparkles: 形态；无对应为 null）。 */
    val gitmoji: String?
)

/** 规范校验问题。 */
data class CommitConventionIssue(
    val kind: Kind,
    val detail: String,
    val evidence: String,
    val repairable: Boolean
) {
    enum class Kind(val label: String) {
        STYLE_UNRESOLVED("风格未识别"),
        MISSING_TYPE_PREFIX("缺少类型前缀"),
        UNKNOWN_TYPE("类型不在白名单"),
        BAD_SCOPE_FORMAT("scope 格式不合规"),
        SUBJECT_TOO_LONG("主题超长"),
        SUBJECT_CASE("主题大小写不合规"),
        SUBJECT_TRAILING_PERIOD("主题句号结尾"),
        SUBJECT_ISSUE_REF("主题混入 issue 引用"),
        MISSING_IMPERATIVE("主题非祈使语气"),
        MISSING_BLANK_LINE("主题与正文间缺空行"),
        BODY_LINE_TOO_LONG("正文未按列宽折行"),
        BAD_FOOTER_FORMAT("footer 格式不合规"),
        MISSING_BREAKING_FOOTER("缺 BREAKING CHANGE footer"),
        EMOJI_MISMATCH("emoji 与变更类型不符")
    }
}

/** 解析后的提交头（type(scope)!: subject 的结构化形态）。 */
data class CommitHeader(
    val type: String,
    val scope: String?,
    val breaking: Boolean,
    val subject: String,
    /** 主题语言（zh / en / mixed）。 */
    val subjectLanguage: String,
    /** gitmoji 形态（:sparkles:）—— 非 gitmoji 风格为 null。 */
    val gitmoji: String?
) {
    /** 规范头重建（conventional 形态）。 */
    fun toConventionalHeader(): String = buildString {
        append(type)
        if (!scope.isNullOrBlank()) append("($scope)")
        if (breaking) append("!")
        append(": ")
        append(subject)
    }
}

/**
 * 规范体系库主体（纯函数集合）。
 */
object CommitMessageConventions {

    // ═══ 类型知识库 ═══

    /** 十二类 conventional type（官方集 + 社区扩展 build/ci 细分）。 */
    val TYPES: List<CommitTypeInfo> = listOf(
        CommitTypeInfo(
            type = "feat", label = "新功能",
            whenToUse = "面向用户的新能力（新工具/新界面/新参数）",
            enVerbs = listOf("add", "introduce", "support", "enable"),
            zhVerbs = listOf("新增", "支持", "引入"),
            gitmoji = ":sparkles:"
        ),
        CommitTypeInfo(
            type = "fix", label = "缺陷修复",
            whenToUse = "修复错误行为（崩溃/错值/泄漏/竞态）",
            enVerbs = listOf("fix", "repair", "resolve", "correct"),
            zhVerbs = listOf("修复", "纠正", "解决"),
            gitmoji = ":bug:"
        ),
        CommitTypeInfo(
            type = "docs", label = "文档",
            whenToUse = "仅文档变更（README/注释文档/CHANGELOG）",
            enVerbs = listOf("document", "update", "expand"),
            zhVerbs = listOf("补充", "更新"),
            gitmoji = ":memo:"
        ),
        CommitTypeInfo(
            type = "style", label = "格式",
            whenToUse = "不影响语义的格式调整（空白/分号/导入排序）",
            enVerbs = listOf("format", "reformat", "tidy"),
            zhVerbs = listOf("整理", "规范化"),
            gitmoji = ":art:"
        ),
        CommitTypeInfo(
            type = "refactor", label = "重构",
            whenToUse = "既非新增也非修复的代码结构变更",
            enVerbs = listOf("refactor", "extract", "simplify", "move"),
            zhVerbs = listOf("重构", "抽取", "简化", "收口"),
            gitmoji = ":recycle:"
        ),
        CommitTypeInfo(
            type = "perf", label = "性能",
            whenToUse = "提升性能的变更（算法/缓存/内存）",
            enVerbs = listOf("speed up", "optimize", "reduce"),
            zhVerbs = listOf("优化", "加速", "降耗"),
            gitmoji = ":zap:"
        ),
        CommitTypeInfo(
            type = "test", label = "测试",
            whenToUse = "新增或修正测试（不触碰产品代码）",
            enVerbs = listOf("test", "cover", "verify"),
            zhVerbs = listOf("覆盖", "验证"),
            gitmoji = ":white_check_mark:"
        ),
        CommitTypeInfo(
            type = "build", label = "构建",
            whenToUse = "构建系统或外部依赖变更（gradle/依赖版本）",
            enVerbs = listOf("build", "bump", "upgrade"),
            zhVerbs = listOf("升级", "调整"),
            gitmoji = ":package:"
        ),
        CommitTypeInfo(
            type = "ci", label = "持续集成",
            whenToUse = "CI 配置与脚本变更（workflows/门禁脚本）",
            enVerbs = listOf("configure", "gate", "automate"),
            zhVerbs = listOf("接入", "调整"),
            gitmoji = ":construction_worker:"
        ),
        CommitTypeInfo(
            type = "chore", label = "杂务",
            whenToUse = "其他不修改 src 也不改测试的杂项",
            enVerbs = listOf("clean up", "maintain", "seed"),
            zhVerbs = listOf("清理", "维护"),
            gitmoji = ":wrench:"
        ),
        CommitTypeInfo(
            type = "revert", label = "回滚",
            whenToUse = "回滚某次提交（保持历史可读）",
            enVerbs = listOf("revert", "roll back"),
            zhVerbs = listOf("回滚", "撤销"),
            gitmoji = ":rewind:"
        ),
        CommitTypeInfo(
            type = "security", label = "安全",
            whenToUse = "安全加固（校验/权限/加密）——社区扩展类型",
            enVerbs = listOf("harden", "guard", "validate"),
            zhVerbs = listOf("加固", "防护"),
            gitmoji = ":lock:"
        )
    )

    /** type → 知识条目。 */
    val TYPE_INDEX: Map<String, CommitTypeInfo> = TYPES.associateBy { it.type }

    /** gitmoji → type（反向映射）。 */
    val GITMOJI_TO_TYPE: Map<String, String> =
        TYPES.filter { it.gitmoji != null }.associate { it.gitmoji!! to it.type }

    /** 类型白名单（conventional 集合）。 */
    val ALLOWED_TYPES: Set<String> = TYPE_INDEX.keys

    // ═══ 头部解析 ═══

    /** conventional 头形态：type(scope)!: subject。 */
    private val CONVENTIONAL_HEADER: Regex =
        Regex("""^([a-z]+)(?:\(([\w./-]+)\))?(!)?:\s+(.+)$""")

    /** kernel 头形态：subsystem: subject。 */
    private val KERNEL_HEADER: Regex = Regex("""^([a-z0-9-]+):\s+(.+)$""")

    /** gitmoji 头形态：:emoji: subject。 */
    private val GITMOJI_HEADER: Regex = Regex("""^(:[\w+-]+:)\s+(.+)$""")

    /** 主题里的 issue 引用（应在 footer 而非主题）。 */
    private val ISSUE_REF: Regex = Regex("""#\d+|(?:fixes|closes|resolves)\s+#?\d+""", RegexOption.IGNORE_CASE)

    /** 英文过去式动词黑名单（祈使语气校验）。 */
    private val PAST_TENSE_ENDINGS = listOf("ed ", "ed:", "ted ", "ned ", "sed ")

    /** 拉丁主体判定（主题语言识别）。 */
    private fun isLatinDominant(text: String): Boolean {
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        return latin > cjk
    }

    /**
     * 解析提交头（首行）：
     * - CONVENTIONAL/GITMOJI/PLAIN 按各自形态拆解；
     * - 解析失败返回 null（调用方按风格报 MISSING_TYPE_PREFIX 等）。
     */
    fun parseHeader(firstLine: String, style: CommitStyleKind): CommitHeader? {
        val line = firstLine.trim()
        if (line.isEmpty()) return null
        when (style) {
            CommitStyleKind.GITMOJI -> {
                val match = GITMOJI_HEADER.find(line) ?: return null
                val emoji = match.groupValues[1]
                val type = GITMOJI_TO_TYPE[emoji] ?: "chore"
                return CommitHeader(
                    type = type,
                    scope = null,
                    breaking = emoji == ":boom:",
                    subject = match.groupValues[2].trim(),
                    subjectLanguage = subjectLanguageOf(match.groupValues[2]),
                    gitmoji = emoji
                )
            }
            CommitStyleKind.KERNEL -> {
                val match = KERNEL_HEADER.find(line) ?: return null
                return CommitHeader(
                    type = "chore",
                    scope = match.groupValues[1],
                    breaking = false,
                    subject = match.groupValues[2].trim(),
                    subjectLanguage = subjectLanguageOf(match.groupValues[2]),
                    gitmoji = null
                )
            }
            CommitStyleKind.CONVENTIONAL, CommitStyleKind.PLAIN -> {
                val match = CONVENTIONAL_HEADER.find(line) ?: return null
                return CommitHeader(
                    type = match.groupValues[1].lowercase(),
                    scope = match.groupValues[2].ifBlank { null },
                    breaking = match.groupValues[3] == "!",
                    subject = match.groupValues[4].trim(),
                    subjectLanguage = subjectLanguageOf(match.groupValues[4]),
                    gitmoji = null
                )
            }
        }
    }

    private fun subjectLanguageOf(subject: String): String = when {
        subject.isEmpty() -> "unknown"
        isLatinDominant(subject) -> "en"
        else -> "zh"
    }

    // ═══ 校验 ═══

    /**
     * 风格感知的全量校验（VALIDATE 阶段调用）：
     * 与 [CommitMessageDiffAnalyzer.validateCommitMessage] 互补——
     * 那边查「conventional 通用形态」，这边查「当前风格的特定规则」。
     */
    fun validate(message: String, spec: CommitStyleSpec): List<CommitConventionIssue> {
        if (message.isBlank()) {
            return listOf(
                CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.MISSING_TYPE_PREFIX,
                    detail = "提交信息为空",
                    evidence = "",
                    repairable = false
                )
            )
        }
        val lines = message.lines()
        val firstLine = lines.first().trim()
        val issues = mutableListOf<CommitConventionIssue>()
        val header = parseHeader(firstLine, spec.kind)

        // 1) type 前缀
        if (header == null) {
            if (spec.requiresType || spec.kind == CommitStyleKind.KERNEL) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.MISSING_TYPE_PREFIX,
                    detail = when (spec.kind) {
                        CommitStyleKind.KERNEL -> "内核风格需要 subsystem: subject 前缀"
                        CommitStyleKind.GITMOJI -> "gitmoji 风格需要 :emoji: 前缀"
                        else -> "conventional 风格需要 type(scope): subject 前缀"
                    },
                    evidence = firstLine.take(60),
                    repairable = true
                )
            }
        } else {
            // 2) 类型白名单（conventional 才强制）
            if (spec.requiresType && header.type !in ALLOWED_TYPES) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.UNKNOWN_TYPE,
                    detail = "类型 '${header.type}' 不在白名单（${ALLOWED_TYPES.joinToString("/")}）",
                    evidence = firstLine.take(60),
                    repairable = true
                )
            }
            // 3) scope 格式（小写字母数字与 ./- 分隔，禁空白与中文）
            if (header.scope != null && !header.scope.matches(Regex("""[a-z0-9][\w./-]*"""))) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.BAD_SCOPE_FORMAT,
                    detail = "scope 应为小写字母/数字与 ./- 组合（当前：${header.scope}）",
                    evidence = header.scope,
                    repairable = true
                )
            }
            // 4) 主题长度（软限 MINOR 语义、硬限必报）
            if (header.subject.length > spec.subjectHardMax) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.SUBJECT_TOO_LONG,
                    detail = "主题 ${header.subject.length} 字符 > 硬上限 ${spec.subjectHardMax}",
                    evidence = header.subject.take(40),
                    repairable = false
                )
            }
            // 5) 句号结尾
            if (header.subject.endsWith("。") || header.subject.endsWith(".")) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.SUBJECT_TRAILING_PERIOD,
                    detail = "主题不应以句号结尾",
                    evidence = header.subject.takeLast(10),
                    repairable = true
                )
            }
            // 6) issue 引用位置
            if (ISSUE_REF.containsMatchIn(header.subject)) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.SUBJECT_ISSUE_REF,
                    detail = "issue 引用应放 footer（主题保持纯语义）",
                    evidence = ISSUE_REF.find(header.subject)?.value ?: "",
                    repairable = true
                )
            }
            // 7) 大小写（KERNEL 要求小写开头）
            if (spec.requiresLowercaseStart && header.subject.isNotEmpty() &&
                header.subject.first().isUpperCase()
            ) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.SUBJECT_CASE,
                    detail = "内核风格主题应以小写开头",
                    evidence = header.subject.take(20),
                    repairable = true
                )
            }
            // 8) 祈使语气（英文主题 + 风格要求）
            if (spec.requiresImperative && header.subjectLanguage == "en") {
                val firstWord = header.subject.split(Regex("\\s+")).firstOrNull() ?: ""
                if (isPastTense(firstWord)) {
                    issues += CommitConventionIssue(
                        kind = CommitConventionIssue.Kind.MISSING_IMPERATIVE,
                        detail = "英文主题应祈使语气（'$firstWord' 疑似过去式）",
                        evidence = firstWord,
                        repairable = true
                    )
                }
            }
        }

        // 9) 主题与正文间的空行
        if (lines.size >= 2 && lines[1].isNotBlank()) {
            issues += CommitConventionIssue(
                kind = CommitConventionIssue.Kind.MISSING_BLANK_LINE,
                detail = "主题行与正文之间必须空一行",
                evidence = lines[1].take(40),
                repairable = true
            )
        }

        // 10) 正文折行
        val bodyLines = if (lines.size > 2) lines.drop(2) else emptyList()
        val tooLong = bodyLines.filter { it.length > spec.bodyWrapColumns }
        if (tooLong.isNotEmpty()) {
            issues += CommitConventionIssue(
                kind = CommitConventionIssue.Kind.BODY_LINE_TOO_LONG,
                detail = "正文 ${tooLong.size} 行超过 ${spec.bodyWrapColumns} 列未折行",
                evidence = tooLong.first().take(50),
                repairable = true
            )
        }

        // 11) footer 规范（Token: value / Token #123 两种合法形态）
        for (footer in extractFooters(bodyLines)) {
            if (!footer.matches(FOOTER_LINE)) {
                issues += CommitConventionIssue(
                    kind = CommitConventionIssue.Kind.BAD_FOOTER_FORMAT,
                    detail = "footer 应为 'Token: value' 或 'Token #123' 形态",
                    evidence = footer.take(40),
                    repairable = true
                )
            }
        }

        // 12) 破坏性变更双标记（! 与 BREAKING CHANGE footer 必须同时有）
        val hasBreakingMark = header?.breaking == true || firstLine.contains("!")
        val hasBreakingFooter = bodyLines.any {
            it.trim().startsWith("BREAKING CHANGE", ignoreCase = true)
        }
        if (hasBreakingFooter && !hasBreakingMark) {
            issues += CommitConventionIssue(
                kind = CommitConventionIssue.Kind.MISSING_BREAKING_FOOTER,
                detail = "正文有 BREAKING CHANGE footer，但头部缺 ! 标记（或反之）",
                evidence = "",
                repairable = true
            )
        }
        return issues
    }

    /** 英文过去式粗判（ed 结尾 + 非白名单词）。 */
    private fun isPastTense(word: String): Boolean {
        val clean = word.lowercase().trimEnd(':', ',')
        if (clean in IMPERATIVE_WHITELIST) return false
        return PAST_TENSE_ENDINGS.any { clean + " " == it || clean.endsWith("ed") && clean.length > 4 }
    }

    /** 允许的祈使动词白名单（拼写上像过去式但实为合法新使的词）。 */
    private val IMPERATIVE_WHITELIST = setOf(
        "embed", "speed", "seed", "shed", "red", "need", "use", "add", "fix", "move", "base", "link"
    )

    /** 合法 footer 行形态：Token 后跟冒号或 #号，再接内容。 */
    private val FOOTER_LINE: Regex = Regex(
        """(?i)^(breaking change|closes|fixes|resolves|refs|co-authored-by|""" +
            """reviewed-by|signed-off-by)[:#] ?.+"""
    )

    /** 正文中的 footer 行提取（以已知 Token 开头的行）。 */
    fun extractFooters(bodyLines: List<String>): List<String> =
        bodyLines.map { it.trim() }
            .filter { line ->
                FOOTER_TOKENS.any { line.startsWith(it, ignoreCase = true) }
            }

    /** 已知 footer 令牌（未列出的 Token: 行视为普通正文）。 */
    private val FOOTER_TOKENS = listOf(
        "BREAKING CHANGE", "Closes", "Fixes", "Resolves", "Refs",
        "Co-Authored-By", "Reviewed-By", "Signed-off-by"
    )

    // ═══ 修复 ═══

    /**
     * 风格感知修复（REPAIR 阶段调用）：
     * - type 归一小写 + 白名单就近纠正（edit→fix / feature→feat / improve→refactor）；
     * - 剥主题句号、把 issue 引用挪到 footer、补主题-正文空行、正文按列宽重折；
     * - ! 与 BREAKING CHANGE footer 对齐（有 footer 缺 ! 补 !）。
     */
    fun repair(message: String, spec: CommitStyleSpec): Pair<String, List<String>> {
        if (message.isBlank()) return message to emptyList()
        val notes = mutableListOf<String>()
        val lines = message.lines().toMutableList()
        if (lines.isEmpty()) return message to emptyList()

        // ── 头部修复 ──
        var header = lines[0].trim()
        // 先按目标风格解析；解析不出时回退 conventional 形态
        // （例如 gitmoji 目标下的 conventional 输入 → 互转修复）
        var parsed = parseHeader(header, spec.kind)
        if (parsed == null && spec.kind != CommitStyleKind.CONVENTIONAL) {
            parsed = parseHeader(header, CommitStyleKind.CONVENTIONAL)
        }
        if (parsed != null) {
            var type = parsed.type.lowercase()
            val normalized = TYPE_ALIASES[type]
            if (normalized != null) {
                type = normalized
                notes += "type '$parsed.type' 归一为 '$normalized'"
            }
            if (spec.requiresType && type !in ALLOWED_TYPES) {
                type = "chore"
                notes += "未知 type 回落 chore"
            }
            var subject = parsed.subject
            if (subject.endsWith("。") || subject.endsWith(".")) {
                subject = subject.trimEnd('。', '.')
                notes += "主题句号已剥离"
            }
            val issueRef = ISSUE_REF.find(subject)
            if (issueRef != null) {
                subject = ISSUE_REF.replace(subject, "").trim()
                notes += "issue 引用已挪出主题"
            }
            when (spec.kind) {
                CommitStyleKind.GITMOJI -> {
                    val emoji = TYPE_INDEX[type]?.gitmoji ?: ":wrench:"
                    val suffix = if (parsed.breaking && emoji != ":boom:") " :boom:" else ""
                    lines[0] = "$emoji$suffix $subject"
                }
                CommitStyleKind.KERNEL -> {
                    val subsystem = (parsed.scope ?: "core").lowercase()
                    val lowerSubject = if (subject.isNotEmpty()) {
                        subject.replaceFirstChar { it.lowercaseChar() }
                    } else {
                        subject
                    }
                    lines[0] = "$subsystem: $lowerSubject"
                }
                else -> {
                    lines[0] = CommitHeader(
                        type = type,
                        scope = parsed.scope,
                        breaking = parsed.breaking,
                        subject = subject,
                        subjectLanguage = parsed.subjectLanguage,
                        gitmoji = null
                    ).toConventionalHeader()
                }
            }
        } else {
            header = lines[0]
        }

        // ── 空行补齐 ──
        if (lines.size >= 2 && lines[1].isNotBlank()) {
            lines.add(1, "")
            notes += "主题与正文间已补空行"
        }

        // ── 正文重折 ──
        if (lines.size > 2) {
            val body = lines.drop(2)
            val hasBreakingFooter = body.any {
                it.trim().startsWith("BREAKING CHANGE", ignoreCase = true)
            }
            val wrapped = wrapBody(body.joinToString("\n"), spec.bodyWrapColumns)
            val breakingFooterMissing = hasBreakingFooter &&
                !lines[0].contains("!") && spec.kind == CommitStyleKind.CONVENTIONAL
            if (breakingFooterMissing) {
                lines[0] = if (lines[0].contains("(")) {
                    lines[0].replaceFirst(Regex("""^([a-z]+)\(([^)]*)\): """), "$1($2)!: ")
                } else {
                    lines[0].replaceFirst(Regex("""^([a-z]+): """), "$1!: ")
                }
                notes += "头部已补 ! 标记"
            }
            val rebuilt = lines.subList(0, 2) + wrapped.lines()
            val result = rebuilt.joinToString("\n").trim()
            if (result != message.trim()) {
                return result to notes
            }
            return result to notes
        }
        val result = lines.joinToString("\n").trim()
        return result to notes
    }

    /** type 别名纠正表（常见误写）。 */
    private val TYPE_ALIASES: Map<String, String> = mapOf(
        "feature" to "feat", "improve" to "refactor", "cleanup" to "chore",
        "clean" to "chore", "bug" to "fix", "hotfix" to "fix", "patch" to "fix",
        "documentation" to "docs", "document" to "docs", "performance" to "perf",
        "improvement" to "perf", "refactoring" to "refactor", "tests" to "test",
        "deps" to "build", "dependency" to "build", "dependencies" to "build",
        "security" to "security", "release" to "chore"
    )

    // ═══ 正文折行 ═══

    /**
     * 正文按列宽重折（保留段落空行与列表形态；超长行在词边界折）。
     * 中文按字符断、英文按空格断。
     */
    fun wrapBody(body: String, columns: Int): String {
        if (columns <= 0) return body
        return body.lines().joinToString("\n") { line ->
            if (line.length <= columns || line.startsWith("- ") || line.startsWith("  ")) {
                line
            } else {
                wrapSingleLine(line, columns)
            }
        }
    }

    private fun wrapSingleLine(line: String, columns: Int): String {
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in line.split(" ")) {
            if (current.isNotEmpty() && current.length + 1 + word.length > columns) {
                out += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.joinToString("\n")
    }

    // ═══ 草稿合成 ═══

    /**
     * 主题草稿合成（代码侧底稿，LLM 润色）：
     * - zh 主题：`动词 + 对象`（中文动词从类型知识库取）；
     * - en 主题：`Imperative verb + object`（首词首字母大写除 KERNEL 小写）；
     * - gitmoji：前缀 emoji；
     * - kernel：`subsystem: subject`。
     */
    fun draftSubject(
        type: String,
        scope: String?,
        objectText: String,
        spec: CommitStyleSpec,
        preferChinese: Boolean
    ): String {
        val info = TYPE_INDEX[type] ?: TYPE_INDEX.getValue("chore")
        val obj = objectText.trim().trimEnd('。', '.')
        if (obj.isEmpty()) return ""
        return when {
            spec.kind == CommitStyleKind.GITMOJI -> {
                val emoji = info.gitmoji ?: ":wrench:"
                if (preferChinese) {
                    "${info.zhVerbs.firstOrNull() ?: "调整"}$obj"
                } else {
                    val verb = info.enVerbs.firstOrNull()?.replaceFirstChar { it.uppercaseChar() } ?: "Update"
                    "$verb $obj"
                }.let { "$emoji $it" }
            }
            spec.kind == CommitStyleKind.KERNEL -> {
                val subsystem = scope ?: "core"
                if (preferChinese) {
                    "$subsystem: ${info.zhVerbs.firstOrNull() ?: "调整"}$obj"
                } else {
                    val verb = info.enVerbs.firstOrNull() ?: "update"
                    "$subsystem: $verb $obj"
                }
            }
            preferChinese -> {
                val head = buildString {
                    append(info.type)
                    if (!scope.isNullOrBlank()) append("($scope)")
                    append(": ")
                }
                head + "${info.zhVerbs.firstOrNull() ?: "调整"}$obj"
            }
            else -> {
                val head = buildString {
                    append(info.type)
                    if (!scope.isNullOrBlank()) append("($scope)")
                    append(": ")
                }
                val verb = info.enVerbs.firstOrNull()?.replaceFirstChar { it.uppercaseChar() } ?: "Update"
                head + "$verb $obj"
            }
        }
    }

    /** BREAKING CHANGE footer 合成（含迁移提示骨架）。 */
    fun buildBreakingFooter(reasons: List<String>): String {
        val head = "BREAKING CHANGE: "
        if (reasons.isEmpty()) {
            return head + "此变更破坏向后兼容，请参考迁移说明。"
        }
        if (reasons.size == 1) {
            return head + reasons.first()
        }
        return reasons.joinToString("\n") { "- $it" }
            .prependIndent("")
            .let { head + "多处分项：\n" + it }
    }

    /** issue footer 合成。 */
    fun buildIssueFooter(action: String, issueNumber: Int): String {
        val token = when (action.lowercase()) {
            "fix", "fixes" -> "Fixes"
            "close", "closes" -> "Closes"
            "resolve", "resolves" -> "Resolves"
            else -> "Refs"
        }
        return "$token #$issueNumber"
    }

    /** 风格互转（conventional ↔ gitmoji；其他组合返回原文）。 */
    fun convertStyle(message: String, from: CommitStyleKind, to: CommitStyleKind): String {
        if (from == to) return message
        val parsed = parseHeader(message.lines().firstOrNull() ?: "", from) ?: return message
        val body = message.lines().drop(1).joinToString("\n").trim()
        return when (to) {
            CommitStyleKind.GITMOJI -> {
                val emoji = TYPE_INDEX[parsed.type]?.gitmoji ?: ":wrench:"
                val breaking = if (parsed.breaking && emoji != ":boom:") " :boom:" else ""
                val header = "$emoji$breaking ${parsed.subject}"
                if (body.isBlank()) header else "$header\n\n$body"
            }
            CommitStyleKind.CONVENTIONAL -> {
                val header = parsed.toConventionalHeader()
                if (body.isBlank()) header else "$header\n\n$body"
            }
            else -> message
        }
    }
}
