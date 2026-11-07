package com.apex.agent.core.codetools.repomap

/**
 * # 个性化 PageRank（networkx `pagerank` 的 Kotlin 移植）
 *
 * Aider 的 repomap 用 `nx.pagerank(G, weight="weight", personalization=p,
 * dangling=p)` 给文件节点排名。这里按 networkx `_pagerank_python` 的
 * 幂迭代语义移植（无向图不支持，仅 MultiDiGraph 场景）：
 *
 * ```
 * x(n) = (1 - α)·pers(n)
 *      + α·Σ_{u→n} p(u)·w(u,n)/W_out(u)
 *      + α·Σ_{dangling d} p(d)·pers(n)
 * ```
 *
 * - α = damping（networkx 默认 0.85）；
 * - `pers` 是归一化的个性化向量（Aider 传「聊天文件高分」）；
 * - 悬挂节点（无出边）的 rank 质量按 `pers` 再分配（dangling=pers）；
 * - 收敛判据：L1 误差 < N × 1e-6（networkx 同款），最多 100 轮。
 *
 * 多重边（同一对节点间多条 ident 边）语义：全部计入 W_out 与入边求和 ——
 * 与 MultiDiGraph 一致。平行边在构建侧已按 ident 聚合权重的话这里退化为
 * 简单有向图，两种形状都正确。
 */
class PersonalizedPageRank(
    private val damping: Double = DEFAULT_DAMPING,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val tolerance: Double = DEFAULT_TOLERANCE
) {

    /** 一条有向边：src → dst，权重 > 0。 */
    data class Edge<N>(val src: N, val dst: N, val weight: Double)

    /**
     * 计算个性化 PageRank。
     *
     * @param nodes 全部节点（含无出边的节点 —— 图只给边时由调用方补全集）。
     * @param edges 边集合（权重 ≤ 0 的边按 networkx 行为跳过）。
     * @param personalization 个性化向量；null / 空 / 全零 → 均匀分布。
     *   不在向量里的节点按 0 处理（networkx 会报错，这里宽容折叠 ——
     *   防御式 IO 原则）。
     * @return 节点 → rank（Σ = 1）。空图返回空 Map。
     */
    fun <N> compute(
        nodes: Set<N>,
        edges: List<Edge<N>>,
        personalization: Map<N, Double>? = null
    ): Map<N, Double> {
        if (nodes.isEmpty()) return emptyMap()

        // 个性化向量：缺失节点按 0 宽容处理（networkx 对缺 key 抛 ValueError，
        // Aider 实际传入的是稀疏覆盖 —— 按仓库防御式 IO 原则折叠而非失败），
        // 归一化到 Σ=1；null / 空 / 全零 → 均匀分布。
        val persRaw: Map<N, Double> = if (personalization != null && personalization.isNotEmpty()) {
            nodes.associateWith { n -> personalization[n]?.takeIf { it > 0 } ?: 0.0 }
        } else {
            null
        } ?: nodes.associateWith { 1.0 / nodes.size }
        val persSum = persRaw.values.sum()
        val pers: Map<N, Double> =
            if (persSum > 0.0) persRaw.mapValues { (_, v) -> v / persSum }
            else nodes.associateWith { 1.0 / nodes.size }

        // 出边聚合（多重边合并进 W_out 与入边贡献，MultiDiGraph 语义）
        val outWeight = HashMap<N, Double>(nodes.size)
        val inEdges = HashMap<N, MutableList<Pair<N, Double>>>() // dst → (src, weight)
        for (edge in edges) {
            if (edge.weight <= 0.0 || edge.src !in nodes || edge.dst !in nodes) continue
            outWeight[edge.src] = (outWeight[edge.src] ?: 0.0) + edge.weight
            inEdges.getOrPut(edge.dst) { mutableListOf() } += edge.src to edge.weight
        }

        val dangling = nodes.filter { (outWeight[it] ?: 0.0) <= 0.0 }
        var p = pers
        repeat(maxIterations) {
            val x = HashMap<N, Double>(nodes.size)
            val danglingMass = damping * dangling.sumOf { p[it] ?: 0.0 }
            for (n in nodes) {
                var incoming = 0.0
                inEdges[n]?.forEach { (src, w) ->
                    val out = outWeight[src] ?: 0.0
                    if (out > 0.0) incoming += (p[src] ?: 0.0) * w / out
                }
                x[n] = (1.0 - damping) * (pers[n] ?: 0.0) + damping * incoming +
                    danglingMass * (pers[n] ?: 0.0)
            }
            val err = nodes.sumOf { kotlin.math.abs((x[it] ?: 0.0) - (p[it] ?: 0.0)) }
            p = x
            if (err < nodes.size * tolerance) return p
        }
        // networkx 超轮数抛 PowerIterationFailedConvergence；工具场景宽容
        // 返回末轮结果（rank 仍归一可用，偏差由调用方的排序容差吸收）。
        return p
    }

    companion object {
        const val DEFAULT_DAMPING = 0.85
        const val DEFAULT_MAX_ITERATIONS = 100
        const val DEFAULT_TOLERANCE = 1.0e-6
    }
}
