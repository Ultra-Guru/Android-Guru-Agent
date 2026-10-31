package com.apex.agent.core.code.experience

/**
 * # 经验召回 —— 「下次同类任务开始时检索注入」的代码化实现
 *
 * 用户规格的另一半：经验沉淀的目的不是怀旧，是**下次开工时把相关经验
 * 注入任务上下文**。本对象是纯函数库（无状态、无 IO），三个入口：
 *
 * 1. [score]：单条经验对当前目标的匹配分（打分表见下）；
 * 2. [recall]：一卷经验 → top-N（默认 3，注入体积有界）；
 * 3. [buildContext]：top-N → 系统提示词附加段（"## 角色经验"）。
 *
 * ## 打分表（确定性，全部代码计算）
 *
 * | 信号 | 权重 | 依据 |
 * |------|------|------|
 * | 场景标签交集 | +3 / 个 | 代码分类的同任务族（最强信号） |
 * | 场景文本词元交集 | +2 / 个 | 标签没覆盖到的词汇重叠 |
 * | CJK 场景子串包含 | +2 | 中文无分词，子串即词（双向探测） |
 * | hitCount 强化 | +0.5 × (hit-1) | 被反复验证的经验更可信（封顶 +5） |
 * | 7 日新鲜度 | +1 | 近期经验更贴近当前代码库状态 |
 * | 失败教训加权 | FAILED ×1.25 | 用户规格「下次注意」的天然侧重 |
 *
 * 阈值 [MIN_SCORE]：低于 1.5 分不注入 —— 无关经验注入只会稀释上下文。
 *
 * ## 词元化口径（[tokenize]）
 *
 * 拉丁词按非字母数字切（`fix-the bug` → fix/the/bug，停用词过滤）；
 * CJK 按字符切（中文单字信息密度高，交集按字符算足够稳）；两类分开
 * 计数避免「中文单字 × 英文单词」的假交集。
 */
object RoleExperienceRecall {

    /** 默认注入条数上限（3 条 ≈ 400 token 内，上下文预算友好）。 */
    const val DEFAULT_LIMIT = 3

    /** 注入门槛分：低于此分的相关性视为噪声。 */
    const val MIN_SCORE = 1.5

    /** 新鲜度窗口（7 天）。 */
    private const val FRESH_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 强化分封顶（hitCount 权重不无限放大老经验）。 */
    private const val HIT_WEIGHT_CAP = 5.0

    /** 拉丁停用词（交集去噪）。 */
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for",
        "with", "is", "are", "be", "this", "that", "it", "my", "me"
    )

    // ═══════════════════ 打分 ═══════════════════

    /**
     * 单条经验对目标文本的匹配分（0 起，无上限但各分量有界）。
     * 纯函数：同 (experience, goal, now) 同分。
     */
    fun score(experience: RoleExperience, goal: String, now: Long): Double {
        var s = 0.0

        // 1. 场景标签交集：任务分类直接同族。
        val goalTags = RoleExperienceExtractor.scenarioTagsOf(goal).toSet()
        s += experience.scenarioTags.count { it in goalTags } * 3.0

        // 2. 词元交集（拉丁词 + CJK 字符两个通道）。
        val goalTokens = tokenize(goal)
        val scenarioTokens = tokenize(experience.scenario)
        s += goalTokens.count { it in scenarioTokens } * 2.0

        // 3. CJK 子串包含（双向）：整句 goal 含场景短语，或场景含 goal 短语。
        if (containsCjk(experience.scenario) && containsCjk(goal)) {
            if (goal.contains(experience.scenario.take(8))) s += 2.0
        }

        // 4. 强化分：hitCount-1 次额外注入 × 0.5，封顶。
        s += ((experience.hitCount - 1) * 0.5).coerceAtMost(HIT_WEIGHT_CAP)

        // 5. 新鲜度：7 日内 +1。
        if (experience.createdAt > 0 && now - experience.createdAt in 0..FRESH_WINDOW_MS) {
            s += 1.0
        }

        // 6. 失败教训侧重（用户规格「下次注意」的天然侧重）。
        if (experience.outcome == OUTCOME_FAILED) s *= 1.25

        return s
    }

    // ═══════════════════ 召回 ═══════════════════

    /**
     * 一卷经验 → top-N（分数降序，同分新在前）。
     * 门槛 [MIN_SCORE] 以下全部丢弃；返回值可直接喂 [buildContext]。
     */
    fun recall(
        experiences: List<RoleExperience>,
        goal: String,
        limit: Int = DEFAULT_LIMIT,
        now: Long = System.currentTimeMillis()
    ): List<RoleExperience> {
        if (experiences.isEmpty() || goal.isBlank()) return emptyList()
        return experiences
            .map { it to score(it, goal, now) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(
                compareByDescending<Pair<RoleExperience, Double>> { it.second }
                    .thenByDescending { it.first.createdAt }
            )
            .take(limit)
            .map { it.first }
    }

    // ═══════════════════ 注入段构建 ═══════════════════

    /**
     * top-N 经验 → 系统提示词附加段；空列表 → null（段落省略，调用方
     * 零处理）。段体积有界：每条经验 ≤ 2 行（场景/工具链/结果/注意全压
     * 进一行式摘要），整段 ≤ [CONTEXT_MAX_CHARS]。
     *
     * 文案口径：写给**模型**的行动指引（不是给人看的报表）—— 每条经验
     * 都落在「同类任务该怎么做/该避什么」上。
     */
    fun buildContext(experiences: List<RoleExperience>): String? {
        if (experiences.isEmpty()) return null
        val lines = experiences.mapIndexed { index, exp ->
            val tools = if (exp.toolSequence.isEmpty()) "无工具" else exp.toolSequence.joinToString("→")
            val outcomeMark = when (exp.outcome) {
                OUTCOME_COMPLETED -> "✓成功"
                OUTCOME_FAILED -> "✗失败"
                else -> "◐中止"
            }
            val note = exp.notes.ifBlank { "无特殊注意" }
            val reused = if (exp.hitCount > 1) "（已复用${exp.hitCount}次）" else ""
            "${index + 1}. [$outcomeMark]$reused 场景「${exp.scenario}」：工具链 $tools；$note"
        }
        return buildString {
            appendLine("## 角色经验（跨会话积累，同类任务先读）")
            appendLine("以下是本角色在历史同类任务中沉淀的经验卡 —— 开工前先对照：")
            lines.forEach { appendLine(it) }
            append("（经验按相关度择优注入；成功路径可直接复用工具链，失败教训优先规避。）")
        }.take(CONTEXT_MAX_CHARS)
    }

    /** 注入段体积上限（字符）。 */
    const val CONTEXT_MAX_CHARS = 1600

    // ═══════════════════ 词元化 ═══════════════════

    /**
     * 文本 → 词元集：拉丁词（≥2 字符，停用词过滤，小写）+ CJK 单字。
     * 两类混合返回同一 Set（key 空间天然不重叠：拉丁 vs 单个汉字）。
     */
    internal fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = HashSet<String>(16)
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.length >= 2) {
                val word = latin.toString().lowercase()
                if (word !in STOPWORDS) tokens += word
            }
            latin.setLength(0)
        }
        for (ch in text) {
            when {
                ch.isLetterOrDigit() && ch.code < 0x2E80 -> latin.append(ch)
                ch.isLetterOrDigit() && ch.code >= 0x2E80 -> {
                    flushLatin()
                    // CJK 单字（含全角/韩文/假名区间）。
                    tokens += ch.toString()
                }
                else -> flushLatin()
            }
        }
        flushLatin()
        return tokens
    }

    private fun containsCjk(text: String): Boolean =
        text.any { it.code >= 0x2E80 }
}
