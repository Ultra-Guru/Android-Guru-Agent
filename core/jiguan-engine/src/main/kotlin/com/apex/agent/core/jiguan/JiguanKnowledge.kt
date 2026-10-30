package com.apex.agent.core.jiguan

/**
 * # 全场景记忆（Universal Memory RAG）
 *
 * 结构：应用包名 →（Hook 点特征 / 内存结构 / 网络协议特征 / 操作配方）。
 * 任何 App 的分析过程（去广告 / 解锁 VIP / 提取数据 / 协议逆向）都蒸馏成
 * [AppKnowledge] 条目持久化；下一次同类 App，Agent 按
 * 「包名精确命中 ＞ 关键词相似」直接复用知识。
 *
 * 本类是引擎侧的门面：查询排序 + 蒸馏入参校验；持久化经 [JiguanKnowledgeStore]
 * 端口（实现负责原子写 —— tmp + renameTo，仓库纪律）。
 */
class JiguanKnowledge(
    private val store: JiguanKnowledgeStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 关键词检索（相似度降序，默认 Top 8）。 */
    suspend fun query(query: String, appPackage: String?, limit: Int = 8): List<AppKnowledge> {
        if (query.isBlank() && appPackage.isNullOrBlank()) return emptyList()
        val all = store.all()
        return all.asSequence()
            .map { it to it.relevanceScore(query.ifBlank { " " }, appPackage) }
            .filter { (_, score) -> score > 0.15 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (entry, score) -> entry }
            .toList()
    }

    /** 包名全量档案（同包全部条目 —— 「这个 App 我分析过什么」）。 */
    suspend fun profile(appPackage: String): List<AppKnowledge> =
        store.all().filter { it.appPackage == appPackage }

    /** 保存/更新（同包名 + 同标题幂等合并，successCount 累加）。 */
    suspend fun remember(
        appPackage: String,
        title: String,
        category: String,
        content: String,
        keywords: String = "",
    ): AppKnowledge {
        val cleanPackage = appPackage.trim().ifBlank { "unknown" }
        val cleanTitle = title.trim().take(80)
        val existing = store.all().firstOrNull {
            it.appPackage == cleanPackage && it.title == cleanTitle
        }
        val entry = if (existing == null) {
            AppKnowledge(
                appPackage = cleanPackage,
                title = cleanTitle,
                category = normalizeCategory(category),
                content = content.trim().take(4000),
                keywords = normalizeKeywords(keywords, content),
                createdAt = now(),
                updatedAt = now(),
            )
        } else {
            existing.copy(
                category = normalizeCategory(category),
                content = content.trim().take(4000),
                keywords = normalizeKeywords(keywords, content),
                updatedAt = now(),
            )
        }
        store.save(entry)
        return entry
    }

    /** 复用成功回执（蒸馏纪律的证据 —— 成功次数 +1）。 */
    suspend fun markSuccess(appPackage: String, title: String) {
        val hit = store.all().firstOrNull { it.appPackage == appPackage && it.title == title }
        if (hit != null) {
            store.save(hit.copy(successCount = hit.successCount + 1, updatedAt = now()))
        }
    }

    suspend fun forget(appPackage: String, title: String) = store.delete(appPackage, title)

    // ── 内部 ─────────────────────────────────────────────────────

    private fun normalizeCategory(category: String): String =
        if (category in listOf("hook-signature", "memory-landmark", "protocol", "recipe")) category else "recipe"

    private fun normalizeKeywords(keywords: String, content: String): String {
        val explicit = keywords.split(Regex("[\\s,，]+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .take(12)
        return if (explicit.isNotEmpty()) explicit.joinToString(" ") else content.commonWords()
    }

    /** 内容自动提词：高频 ASCII 词（≥4 字符，去停用词）。 */
    private fun String.commonWords(): String {
        val stop = setOf(
            "this", "that", "with", "from", "have", "then", "when", "what",
            "code", "data", "will", "your", "into",
        )
        return split(Regex("[^A-Za-z0-9_.]+"))
            .filter { it.length >= 4 && it !in stop }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString(" ") { it.key }
    }
}
