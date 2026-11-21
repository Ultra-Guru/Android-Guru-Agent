package com.apex.agent.knowledge

/**
 * ═══ 知识库 Markdown 解析器（纯函数）═══
 *
 * 输入一个知识库文件的全文，输出 [KnowledgeBase]；坏文件返回 null
 * （带原因回调）—— 绝不抛：资产损坏不该让工具层瘫痪，list 里少一套
 * 比整个功能不可用好。
 *
 * 结构约定见 [KnowledgeBaseModels] 顶部注释。解析要点：
 * - kb 头（`<!-- kb: ... -->`）取文件首个非空行，key=value 以 ` | ` 分隔；
 * - `## 必看内容` / `## 快速索引` 为保留节（标题精确匹配，允许中英冒号后缀）；
 * - 快速索引表：`| 想找什么 | 去哪一节 |` —— 跳过表头与 `---` 分隔行；
 * - 其余 `## ` 节按「编号. 标题」剥出 number；无编号则 number=null；
 * - `# ` 一级标题 → docTitle；每节正文 trim 尾部空行。
 */
object KnowledgeBaseParser {

    /** 保留节标题（精确命中，容忍前后空白与全角空格）。 */
    private const val MUST_READ_TITLE = "必看内容"
    private const val INDEX_TITLE = "快速索引"

    /** 解析失败产物：null + 原因。 */
    data class ParseOutcome(val kb: KnowledgeBase?, val error: String?)

    fun parse(sourceFile: String, raw: String): ParseOutcome {
        if (raw.isBlank()) return ParseOutcome(null, "empty file: $sourceFile")
        val lines = raw.lineSequence().toList()
        if (lines.size > 20_000) {
            return ParseOutcome(null, "file too large (${lines.size} lines): $sourceFile")
        }

        // ── kb 头 ──
        val headerLine = lines.firstOrNull { it.trim().isNotEmpty() } ?: return ParseOutcome(null, "no content")
        if (!headerLine.trim().startsWith("<!--")) {
            return ParseOutcome(null, "missing kb header comment (first line must be '<!-- kb: ... -->')")
        }
        val header = parseHeader(headerLine)
        val id = header["id"]?.trim().takeIf { !it.isNullOrBlank() }
            ?: return ParseOutcome(null, "kb header missing id=")
        val roleId = header["role"]?.trim().takeIf { !it.isNullOrBlank() }
            ?: return ParseOutcome(null, "kb header missing role=")
        val title = header["title"]?.trim().takeIf { !it.isNullOrBlank() } ?: id
        val version = header["version"]?.trim()?.toIntOrNull() ?: 1

        // ── 节切分 ──
        var docTitle = title
        var mustRead = ""
        val index = mutableListOf<KbIndexEntry>()
        val sections = mutableListOf<KbSection>()

        var currentTitle: String? = null   // null = 尚未进入任何二级节（一级标题区）
        var currentBody = StringBuilder()
        var sectionNumber: Int? = null

        fun flushSection() {
            val t = currentTitle ?: return
            val body = currentBody.toString().trim('\n').trimEnd()
            val norm = normalizeTitle(t)
            when {
                norm == MUST_READ_TITLE || norm.startsWith(MUST_READ_TITLE) ->
                    // 多段必看内容按序拼接（分块书写合法）
                    mustRead = if (mustRead.isBlank()) body else mustRead + "\n\n" + body
                norm == INDEX_TITLE || norm.startsWith(INDEX_TITLE) ->
                    // 「快速索引 · 续（xx 节）」并入同一索引（全库单索引口径）
                    index.addAll(parseIndexTable(body))
                else -> sections.add(KbSection(number = sectionNumber, title = t.trim(), body = body))
            }
            currentTitle = null
            currentBody = StringBuilder()
            sectionNumber = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("## ") -> {
                    flushSection()
                    val rawTitle = trimmed.removePrefix("## ").trim()
                    sectionNumber = Regex("^(\\d+)[.、]\\s*").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                    currentTitle = rawTitle
                }
                trimmed.startsWith("# ") && currentTitle == null && sections.isEmpty() && mustRead.isEmpty() && index.isEmpty() -> {
                    // 一级标题只取首个（docTitle）；节内的一级标题按正文处理
                    if (docTitle == title) docTitle = trimmed.removePrefix("# ").trim()
                }
                currentTitle != null -> currentBody.appendLine(line)
                else -> Unit // kb 头 / 空白 / 一级标题前的杂行：忽略
            }
        }
        flushSection()

        if (mustRead.isBlank()) return ParseOutcome(null, "missing '## 必看内容' section: $sourceFile")
        if (sections.isEmpty()) return ParseOutcome(null, "no content sections: $sourceFile")

        return ParseOutcome(
            KnowledgeBase(
                id = id,
                roleId = roleId,
                title = title,
                version = version,
                docTitle = docTitle,
                mustRead = mustRead.trim(),
                index = index,
                sections = sections,
                sourceFile = sourceFile,
                raw = raw
            ),
            null
        )
    }

    /** `<!-- kb: a=1 | b=2 -->` → map（每步剥离后 trim，容忍任意空白形态）。 */
    private fun parseHeader(line: String): Map<String, String> {
        val inner = line.trim()
            .removePrefix("<!--").trim()
            .removeSuffix("-->").trim()
            .removePrefix("kb:").trim()
        val out = mutableMapOf<String, String>()
        for (pair in inner.split('|')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq).trim().lowercase()
            val v = pair.substring(eq + 1).trim()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** 标题归一：去空白 + 去尾部中英冒号（“必看内容：” 也命中保留节）。 */
    private fun normalizeTitle(title: String): String =
        title.trim().trimEnd('：', ':', '　', ' ').trim()

    /** 索引表正文 → 行。规则：`| a | b |` 两列；跳过表头行与 --- 分隔行。 */
    private fun parseIndexTable(body: String): List<KbIndexEntry> {
        val out = mutableListOf<KbIndexEntry>()
        var skippedHeader = false
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("|")) continue
            val cells = trimmed.trim('|').split('|').map { it.trim() }
            if (cells.size < 2) continue
            val topic = cells[0]
            val target = cells[1]
            if (topic.isEmpty() || target.isEmpty()) continue
            // 表头行（“想找什么”/“去哪一节”字样）与分隔行只跳一次
            if (!skippedHeader && (topic.contains("想找什么") || topic.contains("主题") && target.contains("去哪") || target.contains("章节"))) {
                skippedHeader = true
                continue
            }
            if (topic.matches(Regex("-+")) || target.matches(Regex("-+"))) continue
            out.add(KbIndexEntry(topic = topic, target = target))
        }
        return out
    }
}
