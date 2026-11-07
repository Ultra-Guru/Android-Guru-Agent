package com.apex.agent.core.codetools.repomap

import kotlin.math.sqrt

/**
 * # Repo-Map 构建器 —— Aider `repomap.py get_ranked_tags` 的 Kotlin 移植
 *
 * 流程（与 Aider 逐步对齐，乘数取自原版源码）：
 *
 * 1. **标签归并**：`defines: ident → 定义文件集`、`references: ident → 文件→次数`；
 *    全仓库无任何引用时引用回退为定义集（Aider 对「只有 def 的语言」的兜底）；
 * 2. **个性化向量**：base = 100/N；聊天文件 +base、路径成分命中提及标识符
 *    +base —— 只有分值 > 0 的文件进向量（稀疏覆盖，PageRank 侧宽容处理）；
 * 3. **边权乘数**（Aider 原版常量）：提及标识符 ×10；蛇形/串形/驼峰且
 *    长度 ≥ 8 ×10；下划线私有前缀 ×0.1；定义文件 > 5 ×0.1；引用者已在
 *    聊天 ×50；引用次数开平方抑制高频；
 *    另：有定义无引用的标识符加 self-edge（weight 0.1，Aider 对 tree-sitter
 *    ruby def 不计 ref 的补丁，纯 JVM 正则提取下同样适用）；
 * 4. **个性化 PageRank**（[PersonalizedPageRank]，dangling 质量按个性化向量
 *    再分配 —— `nx.pagerank(..., dangling=personalization)`）；
 * 5. **rank 分摊**：每条边分得 `srcRank × w / W_out(src)`，累进
 *    `(目标文件, ident)` 的定义排名 —— 「文件对仓库的重要性」被摊给
 *    它定义的具体符号；
 * 6. **排序输出**：定义条目按 rank 降序（聊天中文件跳过 —— 用户已经在
 *    看它们；平分时按 (路径, 标识符) 字典序降序，Aider 同款 tie-break）；
 *    其后按 PageRank 补齐未覆盖文件；最后是无标签文件的裸名（字母序）。
 */
class RepoMapBuilder(
    private val ranker: PersonalizedPageRank = PersonalizedPageRank()
) {

    /** 一条排序后的地图条目：具名（ident + def 行）或裸文件名。 */
    data class RankedEntry(
        val relPath: String,
        val identifier: String?,
        val line: Int?,
        val rank: Double
    ) {
        val isBare: Boolean get() = identifier == null
    }

    /** 一次构建的全部输入标签 + 个性化提示。 */
    class Input(
        /** 每个参与文件的标签（含空标签文件 —— 它们进裸名列表）。 */
        val fileTags: List<FileTags>,
        /** 聊天中正在讨论的文件（相对路径）—— 个性化加分且不出现在地图里。 */
        val chatFiles: Set<String> = emptySet(),
        /** 用户/会话提及的标识符 —— 标识符 ×10、路径成分命中 +base。 */
        val mentionedIdentifiers: Set<String> = emptySet()
    )

    /** 携带 ident 的加权边（rank 分摊需要把边权归到 (目标文件, ident)）。 */
    private data class IdentEdge(
        val src: String,
        val dst: String,
        val weight: Double,
        val ident: String
    )

    fun build(input: Input): List<RankedEntry> {
        if (input.fileTags.isEmpty()) return emptyList()

        // ── 1. 标签归并 ─────────────────────────────────────────────
        val defines = HashMap<String, MutableSet<String>>()          // ident → 定义文件集
        val references = HashMap<String, MutableMap<String, Int>>()  // ident → 文件 → 次数
        val definitions = HashMap<Pair<String, String>, MutableList<RepoTag>>()
        for (tags in input.fileTags) {
            for (def in tags.definitions) {
                defines.getOrPut(def.identifier) { mutableSetOf() } += tags.relPath
                definitions.getOrPut(tags.relPath to def.identifier) { mutableListOf() } += def
            }
            for ((ident, count) in tags.referenceCounts) {
                references.getOrPut(ident) { HashMap() }[tags.relPath] = count
            }
        }
        // Aider：全仓库无引用 → 引用 = 定义（每个定义文件记 1 次）
        if (references.isEmpty()) {
            for ((ident, defFiles) in defines) {
                references[ident] = HashMap<String, Int>().apply {
                    for (f in defFiles) put(f, 1)
                }
            }
        }

        // ── 2. 个性化向量 ───────────────────────────────────────────
        val allFiles = input.fileTags.map { it.relPath }.toSet()
        val personalization = HashMap<String, Double>()
        val base = 100.0 / allFiles.size
        for (tags in input.fileTags) {
            var pers = 0.0
            if (tags.relPath in input.chatFiles) pers += base
            // 路径成分（目录段 + 文件名带/不带扩展名）命中提及标识符 → +base（一次）
            val fileName = tags.relPath.substringAfterLast('/')
            val parts = tags.relPath.split('/') +
                listOf(fileName, fileName.substringBeforeLast('.'))
            if (parts.any { it in input.mentionedIdentifiers }) pers += base
            if (pers > 0) personalization[tags.relPath] = pers
        }

        // ── 3. 建图（边：referencer → definer）─────────────────────
        val identEdges = mutableListOf<IdentEdge>()
        // 有定义无引用的 ident：definer self-edge weight 0.1
        for ((ident, defFiles) in defines) {
            if (ident in references) continue
            for (definer in defFiles) {
                identEdges += IdentEdge(definer, definer, SELF_EDGE_WEIGHT, ident)
            }
        }
        for ((ident, refFiles) in references) {
            val defFiles = defines[ident] ?: continue
            val mul = identifierMultiplier(ident, defFiles.size, input.mentionedIdentifiers)
            for ((referencer, numRefs) in refFiles) {
                var useMul = mul
                if (referencer in input.chatFiles) useMul *= CHAT_REFERENCER_BOOST
                val weight = useMul * sqrt(numRefs.toDouble())
                if (weight <= 0.0) continue
                for (definer in defFiles) {
                    // Aider 保留 referencer == definer 的自引用边（同文件互引也算证据）
                    identEdges += IdentEdge(referencer, definer, weight, ident)
                }
            }
        }

        // ── 4. PageRank ────────────────────────────────────────────
        val graphNodes = identEdges.flatMap { listOf(it.src, it.dst) }.toMutableSet()
        graphNodes += allFiles // 无标签文件也进图（承接裸名排序依据）
        val persOrNull = personalization.takeIf { it.isNotEmpty() }
        val ranked = ranker.compute(
            graphNodes,
            identEdges.map { PersonalizedPageRank.Edge(it.src, it.dst, it.weight) },
            persOrNull
        )

        // ── 5. rank 分摊到 (目标文件, ident) ────────────────────────
        val outWeight = HashMap<String, Double>()
        for (e in identEdges) outWeight[e.src] = (outWeight[e.src] ?: 0.0) + e.weight
        val rankedDefinitions = HashMap<Pair<String, String>, Double>()
        for (e in identEdges) {
            val out = outWeight[e.src] ?: 0.0
            if (out <= 0.0) continue
            val share = (ranked[e.src] ?: 0.0) * e.weight / out
            if (share <= 0.0) continue
            val key = e.dst to e.ident
            rankedDefinitions[key] = (rankedDefinitions[key] ?: 0.0) + share
        }

        // ── 6. 排序输出 ────────────────────────────────────────────
        val result = mutableListOf<RankedEntry>()
        val included = HashSet<String>()
        val sortedDefs = rankedDefinitions.entries.sortedWith(
            compareByDescending<Map.Entry<Pair<String, String>, Double>> { it.value }
                .thenByDescending { it.key.first }
                .thenByDescending { it.key.second }
        )
        for ((key, rank) in sortedDefs) {
            val (fname, ident) = key
            if (fname in input.chatFiles) continue // 聊天文件已在用户视野内
            val tags = definitions[key] ?: continue
            for (tag in tags) {
                result += RankedEntry(fname, ident, tag.line, rank)
            }
            included += fname
        }
        // 按 PageRank 补齐有标签但未覆盖的文件（裸名条目）
        val byPagerank = ranked.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        for ((fname, _) in byPagerank) {
            if (fname in input.chatFiles) continue
            if (fname !in allFiles) continue
            if (fname in included) continue
            result += RankedEntry(fname, null, null, 0.0)
            included += fname
        }
        // 兜底：仍遗漏的文件（无标签、无 rank）按字母序补裸名
        for (tags in input.fileTags.sortedBy { it.relPath }) {
            if (tags.relPath in included || tags.relPath in input.chatFiles) continue
            result += RankedEntry(tags.relPath, null, null, 0.0)
        }
        return result
    }

    /**
     * Aider 标识符乘数：提及 ×10；蛇/串/驼峰且 ≥8 字 ×10；_ 前缀 ×0.1；定义 >5 ×0.1。
     *
     * public 供契约测试直接断言常量（乘数是 Aider 移植的对外承诺）。
     */
    fun identifierMultiplier(
        ident: String,
        definerCount: Int,
        mentioned: Set<String>
    ): Double {
        var mul = 1.0
        val isSnake = ident.contains('_') && ident.any { it.isLetter() }
        val isKebab = ident.contains('-') && ident.any { it.isLetter() }
        val isCamel = ident.any { it.isUpperCase() } && ident.any { it.isLowerCase() }
        if (ident in mentioned) mul *= MENTIONED_BOOST
        if ((isSnake || isKebab || isCamel) && ident.length >= LONG_NAME_MIN) mul *= LONG_NAME_BOOST
        if (ident.startsWith("_")) mul *= PRIVATE_PENALTY
        if (definerCount > MAX_DEFINERS_BEFORE_PENALTY) mul *= MANY_DEFINERS_PENALTY
        return mul
    }

    companion object {
        const val SELF_EDGE_WEIGHT = 0.1
        const val MENTIONED_BOOST = 10.0
        const val LONG_NAME_BOOST = 10.0
        const val PRIVATE_PENALTY = 0.1
        const val MANY_DEFINERS_PENALTY = 0.1
        const val CHAT_REFERENCER_BOOST = 50.0
        const val LONG_NAME_MIN = 8
        const val MAX_DEFINERS_BEFORE_PENALTY = 5
    }
}
