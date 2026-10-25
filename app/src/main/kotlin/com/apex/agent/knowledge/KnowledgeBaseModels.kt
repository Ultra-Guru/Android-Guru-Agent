package com.apex.agent.knowledge

/**
 * ═══ 知识库数据模型 ═══
 *
 * 每个 Agent 角色模板（AgentRole）配一套专属知识库（Markdown 资产，
 * `assets/knowledge/<slug>.md`）。本文件只定义形状；解析见
 * [KnowledgeBaseParser]，服务见 [KnowledgeBaseService]。
 *
 * 知识库文件的结构约定（解析器按此提取）：
 * ```
 * <!-- kb: id=android | role=builtin_coding_android | title=Android 专家知识库 | version=1 -->
 * # Android 专家知识库
 *
 * ## 必看内容            ← 每次开工前必读（引擎提示词会指向这里）
 * - ……
 *
 * ## 快速索引            ← 一键定位表：想找什么 → 去哪一节
 * | 想找什么 | 去哪一节 |
 * | --- | --- |
 * | 协程怎么取消 | 4. 结构化并发 |
 *
 * ## 1. 环境与工具链      ← 编号章节（ref = "1. 环境与工具链"）
 * ……
 * ## 2. ……
 * ```
 */

/** 知识库条目（解析产物，不可变）。 */
data class KnowledgeBase(
    /** 稳定 id（kb 头 id=android → "android"）。 */
    val id: String,
    /** 绑定的角色模板 id（AgentRole.id，如 builtin_coding_android）。 */
    val roleId: String,
    /** 展示标题（如「Android 专家知识库」）。 */
    val title: String,
    /** 内容版本号（kb 头 version，内容大改时 +1）。 */
    val version: Int,
    /** 文档内一级标题（缺省回落 title）。 */
    val docTitle: String,
    /** 必看内容（原样 Markdown，含列表）。 */
    val mustRead: String,
    /** 快速索引行（想找什么 → 去哪一节）。 */
    val index: List<KbIndexEntry>,
    /** 全部章节（含必看/索引之外的一切二级节）。 */
    val sections: List<KbSection>,
    /** 源文件名（诊断用）。 */
    val sourceFile: String,
    /** 原始全文（read 兜底 / 诊断）。 */
    val raw: String
) {
    /** 章节数（不含必看/索引）。 */
    val sectionCount: Int get() = sections.size

    /** 按 ref 找章节：ref 可为完整标题（"4. 结构化并发"）、纯编号（"4"）、编号前缀或去编号标题（"结构化并发"）。 */
    fun findSection(ref: String): KbSection? {
        val needle = ref.trim().lowercase()
        if (needle.isEmpty()) return null
        sections.firstOrNull { it.title.lowercase() == needle }?.let { return it }
        sections.firstOrNull { it.title.lowercase().startsWith(needle) }?.let { return it }
        // 去编号标题匹配（“结构化并发” 命中 “4. 结构化并发”）
        sections.firstOrNull { it.plainTitle.lowercase().startsWith(needle) }?.let { return it }
        // 纯编号 / "4章" / "第4章" 形态
        val num = Regex("^\\D*?(\\d+)").find(needle)?.groupValues?.get(1)
        if (num != null) {
            sections.firstOrNull { it.number == num.toIntOrNull() }?.let { return it }
        }
        return null
    }
}

/** 快速索引行。 */
data class KbIndexEntry(
    /** 想找什么（主题）。 */
    val topic: String,
    /** 去哪一节（章节 ref，原文）。 */
    val target: String
)

/** 知识库章节。 */
data class KbSection(
    /** 章节编号（"## 4. 结构化并发" → 4；无编号为 null）。 */
    val number: Int?,
    /** 章节标题原文（含编号前缀，如 "4. 结构化并发"）。 */
    val title: String,
    /** 正文 Markdown。 */
    val body: String
) {
    /** 无编号标题（供展示）。 */
    val plainTitle: String get() = title.replace(Regex("^\\d+[.、]\\s*"), "").trim()
}

/** 列表用摘要。 */
data class KbSummary(
    val id: String,
    val roleId: String,
    val title: String,
    val version: Int,
    val sectionCount: Int,
    val indexSize: Int,
    val mustReadChars: Int,
    val totalChars: Int
)

/** 一条搜索命中。 */
data class KbSearchHit(
    val sectionTitle: String,
    val number: Int?,
    /** 命中段落预览（关键词上下文 ±60 字符）。 */
    val preview: String,
    /** 得分（标题命中权重高于正文）。 */
    val score: Int,
    /** 命中来源：title / body / index / must_read。 */
    val via: String
)

/**
 * 跨库全局搜索命中（searchAll 产物）：包一层来源标注 —— Agent 看到
 * 「哪套库的哪一节」，直接 read(kb=…, section=…) 精读。
 */
data class KbGlobalHit(
    val kbId: String,
    val kbTitle: String,
    val hit: KbSearchHit
)
