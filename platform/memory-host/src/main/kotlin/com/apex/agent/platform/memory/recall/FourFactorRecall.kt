package com.apex.agent.platform.memory.recall

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.GraphEntity

/**
 * 四因子召回评分 + 访问冷却 + token 硬截断（宿主侧统一召回内核）。
 *
 * 记忆系统全景重构方案 v2 P0-3：库侧 ChatRecall 的「importance × 新近 ×
 * 频率」三因子全局扫描 + 900 字符一刀切截断，升级为四因子加权：
 *
 * ```
 * score = 0.45 × importance
 *       + 0.30 × recency（14 天半衰期，下限 0.55）
 *       + 0.10 × frequency（1 + 0.12 × ln(1 + access) 归一）
 *       + 0.15 × keyword（查询词元命中，0 或 1）
 * ```
 *
 * 三项治理口径（相对库侧的增量）：
 * 1. **访问冷却**：1 小时内已触达的条目不再回写触达 —— 防「越召回越
 *    新鲜」的正反馈把频率/新近度灌爆（召回本身不应改变排序依据）；
 * 2. **token 硬截断**：按行预算装配，超预算丢弃**整行**（画像/近况
 *    优先占预算、里程碑/相关记忆用剩余预算，预算硬顶对谁都生效），
 *    绝不切在句子中间 —— 库侧 take(900) 会把最后一行切成半句；
 * 3. **四分区渲染**：画像（Top 评分）/ 近况（最新 STATE）/ 里程碑
 *    （最近 MILESTONE）/ 相关记忆（图谱实体命中），与库侧渲染分区
 *    逐一对齐（提示词消费方零变化）。
 *
 * 纯函数（无 IO、无状态），单测直调；时钟由调用方注入。
 */
object FourFactorRecall {

    /** 因子权重（和为 1）。 */
    const val W_IMPORTANCE = 0.45f
    const val W_RECENCY = 0.30f
    const val W_FREQUENCY = 0.10f
    const val W_KEYWORD = 0.15f

    /** 新近度半衰期（天）。 */
    const val RECENCY_HALF_LIFE_DAYS = 14.0

    /** 访问冷却（毫秒）：冷却窗口内的触达不回写。 */
    const val TOUCH_COOLDOWN_MS = 3_600_000L

    /** 召回注入 token 预算（硬顶）。 */
    const val RECALL_TOKEN_BUDGET = 900

    /** 画像分区条数上限。 */
    const val MAX_PROFILE_FACTS = 10

    /** 里程碑分区条数上限。 */
    const val MAX_MILESTONE_FACTS = 5

    /** 相关记忆分区实体数上限。 */
    const val MAX_GRAPH_ENTITIES = 3

    /** 单实体观察行数上限。 */
    const val MAX_OBSERVATION_LINES = 3

    // ── 评分 ═════════════════════════════════════════════════════

    /**
     * 四因子评分（0..1）。keyword 因子：查询词元（≥2 字符）任一在
     * content 中出现即记 1，否则 0 —— 与库侧「关键词直接命中补充」
     * 同口径，但并入主评分而非事后插队。
     */
    fun score(fact: ChatFact, query: String, now: Long): Float {
        val importance = fact.importance.coerceIn(0f, 1f)
        val ageDays = ((now - fact.createdAt).coerceAtLeast(0)) / 86_400_000.0
        val recency = (0.55 + 0.45 * Math.pow(2.0, -ageDays / RECENCY_HALF_LIFE_DAYS)).toFloat()
        // frequency 归一：access=50 视为满频（库侧无上限的 ln 增益会无限放大热条目）
        val frequency = (
            (1 + 0.12 * kotlin.math.ln1p(fact.accessCount.toDouble())) /
                (1 + 0.12 * kotlin.math.ln(50.0))
            ).toFloat().coerceIn(0f, 1f)
        val keyword = if (query.isNotBlank() && queryKeywords(query).any { fact.content.contains(it) }) 1f else 0f
        return (W_IMPORTANCE * importance +
            W_RECENCY * recency +
            W_FREQUENCY * frequency +
            W_KEYWORD * keyword).coerceIn(0f, 1f)
    }

    /** 查询词元（≥2 字符，中英混排按空白与常见标点切分）。 */
    fun queryKeywords(query: String): List<String> = query
        .split(Regex("[\\s，。,.！？!?；;：:、]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()

    // ── 选择与渲染 ═══════════════════════════════════════════════

    /** 召回产物：渲染文本（不含外层标题）+ 应触达的条目 id（已过冷却）。 */
    data class Selection(val rendered: String, val touchedFactIds: Set<String>)

    /**
     * 召回主入口（纯函数）。
     *
     * @param facts 账本全量事实（调用方经 MemKit.allFacts 取回）
     * @param graphEntities 知识图谱实体（调用方经 store.readGraph 取回）
     * @param userText 本轮用户输入（关键词信号）
     * @param now 当前时间戳（毫秒）
     * @return null = 无可召回内容（调用方省略注入段）
     */
    fun select(
        facts: List<ChatFact>,
        graphEntities: List<GraphEntity>,
        userText: String,
        now: Long,
    ): Selection? {
        if (facts.isEmpty() && graphEntities.isEmpty()) return null

        val profile = facts.filter { it.kind == ChatFact.Kind.PROFILE }
            .sortedByDescending { score(it, userText, now) }
            .take(MAX_PROFILE_FACTS)
        val state = facts.filter { it.kind == ChatFact.Kind.STATE }
            .maxByOrNull { it.createdAt }
        val milestones = facts.filter { it.kind == ChatFact.Kind.MILESTONE }
            .sortedByDescending { it.createdAt }
            .take(MAX_MILESTONE_FACTS)
        val related = relatedEntities(graphEntities, userText)

        // 触达集合：冷却窗口外的条目才回写（防召回灌爆频率/新近度）
        val cooldownFloor = now - TOUCH_COOLDOWN_MS
        val touched = (profile + listOfNotNull(state) + milestones)
            .filter { it.lastAccessedAt <= cooldownFloor }
            .map { it.id }
            .toSet()

        val rendered = render(profile, state, milestones, related) ?: return null
        return Selection(rendered, touched)
    }

    /** 图谱实体命中：名称或观察被查询词覆盖，按命中数降序取前 N。 */
    private fun relatedEntities(entities: List<GraphEntity>, userText: String): List<GraphEntity> {
        if (entities.isEmpty() || userText.isBlank()) return emptyList()
        val lowered = userText.lowercase()
        return entities
            .map { entity ->
                var hits = 0
                if (entity.name.isNotBlank() && lowered.contains(entity.name.lowercase())) hits++
                entity.observations.forEach { obs ->
                    if (obs.isNotBlank() && lowered.contains(obs.lowercase())) hits++
                }
                entity to hits
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_GRAPH_ENTITIES)
            .map { it.first }
    }

    /**
     * 分区渲染 + token 硬截断：两轮预算装配 —— 保底分区（画像/近况）的
     * 标题与行先占预算，可裁分区（里程碑/相关记忆）用剩余预算；预算耗尽
     * 丢弃**整行**（保底行也不例外 —— 硬顶就是硬顶），无内容行的分区
     * 标题同裁，绝不切在句子中间。
     */
    private fun render(
        profile: List<ChatFact>,
        state: ChatFact?,
        milestones: List<ChatFact>,
        related: List<GraphEntity>,
    ): String? {
        data class Row(val text: String, val essential: Boolean)
        data class Section(val title: String, val rows: List<Row>)

        val sections = ArrayList<Section>()
        if (profile.isNotEmpty()) {
            sections.add(
                Section("### 用户画像", profile.map { Row("- ${it.content}", essential = true) }),
            )
        }
        if (state != null) {
            sections.add(
                Section("### 用户近况", listOf(Row("- ${state.content}", essential = true))),
            )
        }
        if (milestones.isNotEmpty()) {
            sections.add(
                Section("### 重要时刻", milestones.map { Row("- ${it.content}", essential = false) }),
            )
        }
        if (related.isNotEmpty()) {
            sections.add(
                Section(
                    "### 相关记忆",
                    related.flatMap { entity ->
                        entity.observations.take(MAX_OBSERVATION_LINES)
                            .map { obs -> Row("- [${entity.name}] $obs", essential = false) }
                    },
                ),
            )
        }
        if (sections.isEmpty()) return null

        // 两轮预算：保底分区先占，可裁分区用剩余；标题成本随首个被保
        // 留的行一起支出（无行保留则整分区跳过）。
        var budget = RECALL_TOKEN_BUDGET
        fun allocate(section: Section): List<String> {
            var titleSpent = false
            val kept = ArrayList<String>()
            for (row in section.rows) {
                val cost = estimateTokens(row.text) + if (!titleSpent) estimateTokens(section.title) else 0
                if (budget - cost < 0) continue
                budget -= cost
                if (!titleSpent) {
                    kept.add(section.title)
                    titleSpent = true
                }
                kept.add(row.text)
            }
            return kept
        }

        val out = ArrayList<String>()
        sections.filter { it.rows.any { r -> r.essential } }
            .forEach { out.addAll(allocate(it)) }
        sections.filter { it.rows.none { r -> r.essential } }
            .forEach { out.addAll(allocate(it)) }
        return out.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /**
     * token 估算（无分词器依赖的保守启发式）：CJK 字符 1 token/字，
     * 其余 4 字符/token —— 与 code-native 的估算口径同族。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF ||
                ch.code in 0xFF00..0xFFEF
            ) {
                cjk++
            } else {
                other++
            }
        }
        return cjk + (other + 3) / 4
    }
}
