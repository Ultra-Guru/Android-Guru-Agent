package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 输出契约（PR 1 骨架）═══
 *
 * [TemplateOutputContract] 声明「大模型回包应当长什么样」：必需章节、
 * 列表长度区间、禁止的前导/尾随寒暄、占位符泄漏检查等。VALIDATE 阶段
 * 逐条比对产出违规清单，REPAIR 阶段只修「可修复」的那部分。
 *
 * 契约与行为分离的意义：同一个契约模型可以描述十种模板的输出形态，
 * 校验/修复的通用机械在基类与运行时里只写一份。
 */

/** 契约违规类型（可修复性在枚举上声明，REPAIR 阶段据此分流）。 */
enum class TemplateContractViolationKind(
    val label: String,
    val repairable: Boolean
) {
    /** 缺少必需章节（修复 = 补章节壳 + 引导语，内容由 LLM 补写场景有限）。 */
    MISSING_SECTION("缺少章节", true),

    /** 章节存在但为空壳（修复 = 标注待补，提示用户追问）。 */
    SECTION_EMPTY("章节为空", true),

    /** 列表条目数低于契约下限（修复 = 不虚构内容，标注缺口）。 */
    LIST_TOO_SHORT("列表过短", true),

    /** 列表条目数高于契约上限（修复 = 截断到上限 + 留痕）。 */
    LIST_TOO_LONG("列表过长", true),

    /** 回包带寒暄前导（「好的，下面是…」—— 修复 = 剥离）。 */
    PREAMBLE("寒暄前导", true),

    /** 回包带尾随废话（「希望对你有帮助！」—— 修复 = 剥离）。 */
    EPILOGUE("尾随废话", true),

    /** 严重度标记不规范（修复 = 归一为标准 emoji 标记）。 */
    BAD_SEVERITY_MARK("严重度标记不规范", true),

    /** 变量占位符泄漏到回包（`{{var}}` 原样出现 —— 修复 = 替换为实际值或删除）。 */
    PLACEHOLDER_LEAK("占位符泄漏", true),

    /** 应当只输出译文的场景混了解释（修复 = 剥离非译文段）。 */
    NOISE_AROUND_PAYLOAD("正文夹带噪音", true),

    /** 缺少总体结论句（修复 = 从首段提取或标注缺口）。 */
    MISSING_VERDICT("缺少结论", true),

    /** 内容实质为空（无任何可展示内容 —— 不可机械修复，引导重试）。 */
    EMPTY_OUTPUT("回包为空", false),

    /** 内容与输入完全无关（长度悬殊 + 无共同关键词 —— 不可机械修复）。 */
    OFF_TOPIC("答非所问", false);
}

/** 一条契约违规。 */
data class TemplateContractViolation(
    val kind: TemplateContractViolationKind,
    val section: String? = null,
    val detail: String = "",
    val evidence: String = ""
) {
    init {
        require(detail.isNotBlank() || evidence.isNotBlank()) {
            "violation needs detail or evidence (kind=$kind)"
        }
    }

    /** 是否可被 REPAIR 阶段机械修复。 */
    val repairable: Boolean get() = kind.repairable
}

/**
 * 章节要求（契约的最小单元）。
 *
 * @param title 章节标题（匹配时忽略首尾空白与末尾标点，支持别名列表）
 * @param aliases 标题别名（LLM 常见变体，如「问题清单 / 问题列表」）
 * @param listItemCountRange 该章节下列表条目数的合法区间（null = 不约束）
 * @param required false = 可选章节（缺失不算违规）
 */
data class TemplateSectionRequirement(
    val title: String,
    val aliases: List<String> = emptyList(),
    val listItemCountRange: IntRange? = null,
    val required: Boolean = true
) {
    init {
        require(title.isNotBlank()) { "section title must not be blank" }
        listItemCountRange?.let { require(it.first >= 0 && it.last >= it.first) { "bad range for section $title" } }
    }

    /** 该标题（或别名）是否匹配给定行（归一化比较：空白/标点/全半角）。 */
    fun matchesHeader(line: String): Boolean {
        val normalized = TemplateOutputContract.normalizeHeader(line)
        if (normalized.isEmpty()) return false
        val candidates = (listOf(title) + aliases).map { TemplateOutputContract.normalizeHeader(it) }
        return candidates.any { it.isNotEmpty() && it == normalized }
    }
}

/**
 * 输出契约（清单持有；VALIDATE/REPAIR/RENDER 三阶段共享）。
 *
 * @param requiredSections 必需/可选章节要求（有序 = 期望的展示顺序）
 * @param allowedPreambles 允许的前导寒暄模式（空 = 全部视为违规）
 * @param maxNoiseLines 判定 PREAMBLE/EPILOGUE 时最多容忍的寒暄行数
 * @param requiresVerdictStyle 首段必须是「结论先行」形态（总结类契约）
 * @param payloadOnly true = 只允许正文载荷（翻译类：不允许任何解释）
 * @param maxOutputChars 回包渲染的最大长度（超限截断留痕；0 = 不限制）
 */
data class TemplateOutputContract(
    val requiredSections: List<TemplateSectionRequirement> = emptyList(),
    val allowedPreambles: List<String> = emptyList(),
    val maxNoiseLines: Int = 2,
    val requiresVerdictStyle: Boolean = false,
    val payloadOnly: Boolean = false,
    val maxOutputChars: Int = 0
) {
    /** 必需章节标题集（违规报告的期望清单）。 */
    val requiredTitles: List<String> get() = requiredSections.filter { it.required }.map { it.title }

    companion object {
        /** 标题归一化：去空白/井号/编号/全角空格/末尾标点，转小写。 */
        fun normalizeHeader(line: String): String {
            var text = line.trim()
            if (text.isEmpty()) return ""
            text = text.trimStart('#', ' ', '　').trim()
            text = text.replaceFirst(Regex("""^\d+[.、)）]?\s*"""), "")
            text = text.trimEnd(':', '：', ' ', '　')
            return text.lowercase()
        }
    }
}

/**
 * 校验阶段解析出的单个章节（结构化产物，渲染阶段直接消费）。
 *
 * @param requirement 对应的章节要求（null = 契约外的多余章节）
 * @param headerText 原始标题行
 * @param bodyLines 章节正文行（不含标题行）
 * @param listItems 解析出的列表条目（「-」「1.」「①」等编号形态）
 */
data class TemplateParsedSection(
    val requirement: TemplateSectionRequirement?,
    val headerText: String,
    val bodyLines: List<String>,
    val listItems: List<String>
) {
    /** 章节是否为空壳（无正文也无列表）。 */
    val isEmptyBody: Boolean get() = bodyLines.none { it.isNotBlank() } && listItems.isEmpty()

    /** 章节纯文本（标题 + 正文，行连接）。 */
    fun toText(): String = (listOf(headerText) + bodyLines)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

/**
 * VALIDATE 阶段产物：违规清单 + 已解析结构。
 *
 * @param violations 违规清单（顺序 = 检测顺序，不按严重度重排：修复阶段
 *   按出现位置顺序处理更稳）
 * @param parsedSections 解析出的章节（按文档出现顺序）
 * @param orphans 契约外的多余段落（不算违规，渲染时归入「补充」区）
 */
data class TemplateValidationReport(
    val violations: List<TemplateContractViolation> = emptyList(),
    val parsedSections: List<TemplateParsedSection> = emptyList(),
    val orphans: List<String> = emptyList()
) {
    /** 是否整体通过（无任何违规）。 */
    val ok: Boolean get() = violations.isEmpty()

    /** 是否存在可修复违规（REPAIR 阶段是否需要跑）。 */
    val hasRepairable: Boolean get() = violations.any { it.repairable }

    /** 是否存在不可修复违规（需要提示用户重试/追问）。 */
    val hasFatal: Boolean get() = violations.any { !it.repairable }

    /** 指定章节的解析结果（无 → null）。 */
    fun section(title: String): TemplateParsedSection? =
        parsedSections.firstOrNull { it.requirement?.matchesHeader(title) == true }

    /** 违规计数摘要（机器可读：kind.name → count）。 */
    fun violationCounts(): Map<String, Int> =
        violations.groupingBy { it.kind.name }.eachCount()
}
