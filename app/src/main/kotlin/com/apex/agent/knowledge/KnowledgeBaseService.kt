package com.apex.agent.knowledge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ═══ 知识库服务 ═══
 *
 * 每个 Agent 角色模板一套或多套专属知识库（Markdown 资产，v2 多卷制：
 * 同一 roleId 可绑定任意多卷，加卷 = 加文件，无需改代码 —— 全能角色的
 * 知识面可无限扩充）。本服务负责：加载（懒、并发安全、坏文件降级）+
 * 角色绑定查询 + 六动作检索面（[listAll] / [forRole] / [mmustReadOf] /
 * [index] / [search] / [readSection] / [searchAll]），供
 * [com.apex.agent.tools.KnowledgeBaseTool]（Agent 检索入口）与角色提示词
 * 注入消费。
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

    /** 角色绑定的多卷清单（v2：同 roleId 多卷按资产顺序全量保留）。 */
    private val byRoleVolumes = mutableMapOf<String, MutableList<KnowledgeBase>>()

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
            // 角色绑定（v2 多卷制）：同 roleId 的多卷按资产顺序全部保留
            // —— 加卷即扩充，角色提示词与工具 list 面都会展示全部卷；
            // 同 id 重复（不同文件声明同 id）仍首个生效（防检索歧义）。
            for (kb in loaded) {
                if (!byIdCache.containsKey(kb.id)) {
                    byIdCache[kb.id] = kb
                    byRoleVolumes.getOrPut(kb.roleId) { mutableListOf() }.add(kb)
                } else {
                    logger("KnowledgeBase: duplicate kb id ${kb.id} (${kb.sourceFile}) ignored, first wins")
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

    /**
     * 按角色模板 id 取其**主卷**知识库（首卷；无绑定返回 null）。
     *
     * 兼容口径：v1 单卷时代的老调用方语义不变；多卷角色的完整清单
     * 用 [volumesForRole]。
     */
    suspend fun forRole(roleId: String): KnowledgeBase? {
        ensureLoaded()
        return byRoleVolumes[roleId]?.firstOrNull()
    }

    /**
     * 按角色模板 id 取其全部知识卷（v2 多卷制，资产顺序；无绑定返回空）。
     *
     * 多卷即扩充机制本体：角色知识面不设上限，每卷独立 id + 快速索引，
     * Agent 经 [com.apex.agent.tools.KnowledgeBaseTool] 按卷 id 一键定位。
     */
    suspend fun volumesForRole(roleId: String): List<KnowledgeBase> {
        ensureLoaded()
        return byRoleVolumes[roleId]?.toList() ?: emptyList()
    }

    /** 按 id 取（工具参数 kb=android；多卷角色下每卷 id 独立可查）。 */
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

    /**
     * 跨库全局检索（v2：query 不限定库时的兜底入口）。
     *
     * 每库取 top [perKb] 命中，再按得分全局排序截 [total] —— 单库不会
     * 刷屏；每条命中带来源库标注（id + 题名），Agent 直接
     * read(kb=…, section=…) 精读。空查询返回空。
     */
    suspend fun searchAll(
        query: String,
        perKb: Int = 3,
        total: Int = 20
    ): List<KbGlobalHit> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val results = mutableListOf<KbGlobalHit>()
        for (kb in ensureLoaded()) {
            search(kb.id, query, limit = perKb.coerceIn(1, 10)).forEach { h ->
                results.add(KbGlobalHit(kbId = kb.id, kbTitle = kb.title, hit = h))
            }
        }
        return results.sortedByDescending { it.hit.score }.take(total.coerceIn(1, 40))
    }

    // ═══ 角色提示词注入 ═══

    /**
     * 给 applyRoleToEngine 用的后缀（v1 单卷口径）：角色绑定了知识库时，把
     * 「怎么用知识库」写进 Role definition —— 引擎提示词的 Agent Role 段
     * 随之携带检索指引。
     */
    fun roleDefinitionHint(kb: KnowledgeBase): String = buildString {
        appendLine()
        appendLine()
        append("[专属知识库·必读指引] 你有一套专属知识库：「${kb.title}」（${kb.sectionCount} 个章节）。" +
            "开工前先用 KnowledgeBase 工具执行 must_read 过一遍必看内容；" +
            "任何拿不准的 API、行为或陷阱：先 index 按主题找，再 search 查关键词，" +
            "锁定章节后 read 精读。禁止凭空猜测 —— 知识库里有的，以知识库为准。")
    }

    /**
     * 多卷版角色提示（v2 多卷制主口径）：列出全部卷的 id 与题名，告诉
     * 模型「有多少卷、每卷怎么找」—— 检索动作（must_read / index /
     * search / read）按卷 id 逐一可用，目录（快速索引）保证一键定位。
     *
     * 空卷列表返回空串（调用方免判空）。
     */
    fun roleDefinitionHintFor(volumes: List<KnowledgeBase>): String {
        if (volumes.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            if (volumes.size == 1) {
                val kb = volumes.first()
                append("[专属知识库·必读指引] 你有一套专属知识库：「${kb.title}」（kb=${kb.id}，" +
                    "${kb.sectionCount} 个章节）。")
            } else {
                append("[专属知识库·必读指引] 你有 ${volumes.size} 套专属知识库卷（可无限扩充）：")
                volumes.forEachIndexed { i, kb ->
                    append(
                        "卷${i + 1} 「${kb.title}」（kb=${kb.id}，${kb.sectionCount} 章节）" +
                            if (i == volumes.lastIndex) "。" else ";"
                    )
                }
            }
            append("开工前先用 KnowledgeBase 工具对各卷执行 must_read 过一遍必看内容；" +
                "任何拿不准的 API、行为或陷阱：先 index 按主题找（每卷自带「想找什么 → 去哪一节」" +
                "目录），再 search 查关键词，锁定章节后 read 精读。禁止凭空猜测 ——" +
                "知识库里有的，以知识库为准。")
        }
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
