package com.apex.agent.knowledge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ═══ 知识库服务 ═══
 *
 * 每个 Agent 角色模板一套专属知识库（Markdown 资产）。本服务负责：
 * 加载（懒、并发安全、坏文件降级）+ 角色绑定查询 + 五动作检索面
 * （[listAll] / [forRole] / [mustReadOf] / [index] / [search] / [readSection]），
 * 供 [com.apex.agent.tools.KnowledgeBaseTool]（Agent 检索入口）与
 * 角色提示词注入消费。
 *
 * IO 边界防腐：构造只拿两个纯函数（列资产名 / 读资产文本），Android 侧由
 * [com.apex.agent.di.KnowledgeModule] 用 assets 实现，单测直接给内存版本
 * —— 服务本体可纯 JVM 测试。
 *
 * 加载纪律（对齐 PromptTemplateRegistry 的防御式读）：
 * - 懒加载 + Mutex：首访扫描 knowledge/ 目录，坏文件（解析失败）跳过并
 *   记日志，一套坏文件绝不让整个知识库体系瘫痪；
 * - 不可变快照缓存（@Volatile List），读侧纯内存。
 */
class KnowledgeBaseService(
    private val listAssetFiles: () -> List<String>,
    private val readAsset: (String) -> String?,
    private val logger: (String) -> Unit = {}
) {

    private val loadMutex = Mutex()

    @Volatile
    private var cache: List<KnowledgeBase>? = null

    private val byIdCache = mutableMapOf<String, KnowledgeBase>()
    private val byRoleCache = mutableMapOf<String, KnowledgeBase>()

    // ═══ 加载 ═══

    private suspend fun ensureLoaded(): List<KnowledgeBase> {
        cache?.let { return it }
        return loadMutex.withLock {
            cache?.let { return it }
            val loaded = mutableListOf<KnowledgeBase>()
            for (name in listAssetFiles()) {
                if (!name.endsWith(".md")) continue
                val raw = readAsset(name) ?: run {
                    logger("KnowledgeBase: unreadable asset $name, skipped")
                    continue
                }
                when (val outcome = KnowledgeBaseParser.parse(name, raw)) {
                    is KnowledgeBaseParser.ParseOutcome -> if (outcome.kb != null) {
                        loaded.add(outcome.kb)
                    } else {
                        logger("KnowledgeBase: parse failed for $name — ${outcome.error}")
                    }
                }
            }
            // 角色绑定冲突：同 roleId 多套时首个生效（其余留日志），防静默歧义
            for (kb in loaded) {
                if (!byIdCache.containsKey(kb.id)) byIdCache[kb.id] = kb
                if (!byRoleCache.containsKey(kb.roleId)) {
                    byRoleCache[kb.roleId] = kb
                } else {
                    logger("KnowledgeBase: duplicate role binding ${kb.roleId} (${kb.sourceFile}) ignored, first wins")
                }
            }
            cache = loaded
            logger("KnowledgeBase: loaded ${loaded.size} knowledge bases from assets/knowledge")
            loaded
        }
    }

    // ═══ 查询 ═══

    /** 全量列表（摘要）。 */
    suspend fun listAll(): List<KbSummary> = ensureLoaded().map { it.summary() }

    /** 按角色模板 id 取其专属知识库（无绑定返回 null）。 */
    suspend fun forRole(roleId: String): KnowledgeBase? {
        ensureLoaded()
        return byRoleCache[roleId]
    }

    /** 按 id 取（工具参数 kb=android）。 */
    suspend fun byId(kbId: String): KnowledgeBase? {
        ensureLoaded()
        return byIdCache[kbId]
    }

    /** 必看内容（原文 Markdown）。 */
    suspend fun mustReadOf(kbId: String): String? = byId(kbId)?.mustRead

    /** 快速索引（“想找什么 → 去哪一节”）。 */
    suspend fun index(kbId: String): List<KbIndexEntry>? = byId(kbId)?.index

    /**
     * 关键词检索：terms 以空白切分（CJK 查询通常整句一段，按原样子串匹配）。
     * 打分：标题命中 ×3 / 索引主题命中 ×3（并顺带给出指向章节）/ 必看内容 ×2 / 正文 ×1。
     * 返回按分排序的 top N。
     */
    suspend fun search(kbId: String, query: String, limit: Int = 8): List<KbSearchHit> {
        val kb = byId(kbId) ?: return emptyList()
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val hits = mutableListOf<KbSearchHit>()

        // 索引主题命中：直接给出目标章节（这就是“一键找到想看的”）
        for (entry in kb.index) {
            val topic = entry.topic.lowercase()
            if (terms.any { topic.contains(it) }) {
                kb.findSection(entry.target)?.let { sec ->
                    hits.add(
                        KbSearchHit(
                            sectionTitle = sec.title,
                            number = sec.number,
                            preview = previewAround(sec.body, terms),
                            score = 30,
                            via = "index"
                        )
                    )
                }
            }
        }

        for (sec in kb.sections) {
            val titleLc = sec.title.lowercase()
            val bodyLc = sec.body.lowercase()
            var score = 0
            var via = "body"
            for (t in terms) {
                if (titleLc.contains(t)) { score += 3; via = "title" }
                if (bodyLc.contains(t)) score += 1
            }
            if (score > 0) {
                hits.add(
                    KbSearchHit(
                        sectionTitle = sec.title,
                        number = sec.number,
                        preview = previewAround(sec.body, terms),
                        score = score,
                        via = via
                    )
                )
            }
        }

        // 必看内容单独计分（它是概览层：命中时提示先读必看）
        val mustLc = kb.mustRead.lowercase()
        if (terms.any { mustLc.contains(it) }) {
            hits.add(
                KbSearchHit(
                    sectionTitle = "必看内容",
                    number = null,
                    preview = previewAround(kb.mustRead, terms),
                    score = 20,
                    via = "must_read"
                )
            )
        }

        return hits
            .distinctBy { it.sectionTitle }
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, 20))
    }

    /** 读一节（ref = 章节标题 / 纯编号 / 标题前缀）。返回 null = 没找到。 */
    suspend fun readSection(kbId: String, ref: String): KbSection? = byId(kbId)?.findSection(ref)

    // ═══ 角色提示词注入 ═══

    /**
     * 给 applyRoleToEngine 用的后缀：角色绑定了知识库时，把“怎么用知识库”
     * 写进 Role definition —— 引擎提示词的 Agent Role 段随之携带检索指引。
     */
    fun roleDefinitionHint(kb: KnowledgeBase): String = buildString {
        appendLine()
        appendLine()
        append("[专属知识库·必读指引] 你有一套专属知识库：「${kb.title}」（${kb.sectionCount} 个章节）。" +
            "开工前先用 KnowledgeBase 工具执行 must_read 过一遍必看内容；" +
            "任何拿不准的 API、行为或陷阱：先 index 按主题找，再 search 查关键词，" +
            "锁定章节后 read 精读。禁止凭空猜测 —— 知识库里有的，以知识库为准。")
    }

    // ═══ 工具函数 ═══

    private fun previewAround(body: String, terms: List<String>): String {
        val lower = body.lowercase()
        val at = terms.firstNotNullOfOrNull { t -> lower.indexOf(t).takeIf { it >= 0 } } ?: 0
        val from = (at - 60).coerceAtLeast(0)
        val to = (at + 120).coerceAtMost(body.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < body.length) "…" else ""
        return (prefix + body.substring(from, to).replace('\n', ' ') + suffix).take(200)
    }

    private fun KnowledgeBase.summary() = KbSummary(
        id = id,
        roleId = roleId,
        title = title,
        version = version,
        sectionCount = sections.size,
        indexSize = index.size,
        mustReadChars = mustRead.length,
        totalChars = raw.length
    )
}
