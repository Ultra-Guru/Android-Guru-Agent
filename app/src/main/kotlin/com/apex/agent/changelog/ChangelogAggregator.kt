package com.apex.agent.changelog

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  跨版本更新日志聚合器（changelog 通道 v2.3）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 背景（「手动日志发行时 PR 日志丢失」的另一半根因）：
 * 发布列车是高频小步快跑 —— 发布器手动日志与 PR 功能变更经常落在**相邻的
 * 两个版本**里（发布器直推触发 vN，紧随其后的 PR 合并触发 vN+1）。旧弹窗
 * 只展示 versionCode == effective 的**单条** entry：用户跨多版本升级后，
 * 中间版本的 PR 日志（甚至自己上一条手动日志）全部跳过 —— 表现为「我提交
 * 手动日志后，PR 的更新日志就没了」。
 *
 * 聚合策略（两级语义保持不变，只做跨版本合并）：
 * - 区间 (fromExclusive, toInclusive] 内的全部 entry 参与；
 * - 一级 notes：按出现顺序并集去重（发布器手动日志要点跨版本全保留）；
 * - 一级 images：同样跨版本并集去重（v2.3 —— 旧版只保留目标版本配图，
 *   跨版本升级用户会丢中间版本的截图；现按版本升序全量保留，发布器
 *   附图跨版本一条不丢）；
 * - 二级 pr：按组标题合并（同标题组的 notes 并集去重 —— 顺带消解流水线
 *   历史上同片段重复收录造成的双份展示）；
 * - 单版本命中：原样返回（title / date / markdown 降级链完整保留）；
 * - 多版本合并：title 取目标版本，markdown 仅在两级全空时拼接兜底。
 *
 * 纯函数、零 Android 依赖 —— 与 [ChangelogGate] 同纪律，可单测。
 */
object ChangelogAggregator {

    /** markdown 兜底拼接上限（跨版本合并时防超长撑爆弹窗）。 */
    private const val MAX_FALLBACK_MARKDOWN = 8000

    /** 跨版本合并配图上限（与聚合端 MAX_IMAGES / 弹窗图廊同尺）。 */
    private const val MAX_MERGED_IMAGES = 9

    /**
     * 聚合清单内 (fromExclusive, toInclusive] 区间的全部日志条目。
     *
     * @param manifest 远端或内置清单（latest + history 都算候选池）
     * @param fromExclusive 起始 versionCode（不含）—— 已读版本 / 本地有效版本
     * @param toInclusive 结束 versionCode（含）—— 本地有效版本 / 远端最新版本
     * @return 聚合后的 entry；区间内无任何条目 → null（调用方按无内容降级）
     */
    fun aggregate(
        manifest: ChangelogManifest?,
        fromExclusive: Int,
        toInclusive: Int
    ): ChangelogEntry? {
        if (manifest == null || toInclusive <= fromExclusive) return null
        val candidates = buildList {
            manifest.latest?.let { add(it) }
            addAll(manifest.history)
        }.filter { it.versionCode in (fromExclusive + 1)..toInclusive }
        if (candidates.isEmpty()) return null

        // 单版本：原样返回（保留 title/date/images/markdown 全部原始语义）
        if (candidates.size == 1) return candidates.first()

        val target = candidates.maxByOrNull { it.versionCode }
            ?: return null

        // ── 一级 notes：按版本升序并集去重（早版本在前 = 时间线自然序）──
        val notes = LinkedHashSet<String>()
        // ── 一级 images：按版本升序并集去重（配图跨版本全保留，封顶 9）──
        val images = LinkedHashSet<String>()
        // ── 二级 pr：组标题 → 合并组（同标题跨版本并集，消解历史重复收录）──
        val prGroups = LinkedHashMap<String, ChangelogPrGroup>()
        // ── markdown 兜底源（仅当两级全空时使用）──────────────────────
        val markdowns = mutableListOf<String>()

        candidates.sortedBy { it.versionCode }.forEach { e ->
            notes.addAll(e.notes)
            images.addAll(e.images)
            e.pr.forEach { g ->
                val key = g.title.ifBlank { "…" }
                val existing = prGroups[key]
                prGroups[key] = if (existing == null) {
                    ChangelogPrGroup(title = key, notes = g.notes.distinct())
                } else {
                    ChangelogPrGroup(
                        title = key,
                        notes = (existing.notes + g.notes).distinct()
                    )
                }
            }
            e.markdown?.takeIf { it.isNotBlank() }?.let { markdowns.add(it) }
        }

        val mergedNotes = notes.toList()
        val mergedImages = images.toList().take(MAX_MERGED_IMAGES)
        val mergedPr = prGroups.values.toList()
        val fallbackMarkdown = if (mergedNotes.isEmpty() && mergedPr.isEmpty()) {
            markdowns.joinToString("\n\n---\n\n").take(MAX_FALLBACK_MARKDOWN)
        } else {
            null
        }

        return target.copy(
            title = target.title ?: "v${target.versionName} 更新内容",
            notes = mergedNotes,
            images = mergedImages,
            pr = mergedPr,
            markdown = fallbackMarkdown
        )
    }
}
