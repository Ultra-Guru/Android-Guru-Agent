package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateMarkdownScanner
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTextMetrics

/**
 * ═══ 内置模板行为 · 技术文档 —— 大纲与可读性规划器（PR 13）═══
 *
 * 技术文档行为的分析层：
 * 1. **受众判定**：描述词 → 受众档位（新人上手/团队内传/专家深读）；
 * 2. **大纲规划**：五段式规范骨架（概述/背景/核心概念/使用示例/FAQ）
 *    + 受众档位微调（新人加术语表、专家加设计取舍）；
 * 3. **代码片段提取**：参考代码里的围栏块/签名/关键调用（作为示例素材）；
 * 4. **可读性预估**：输入素材的句长/术语密度 → 写作难度提示；
 * 5. **完整性提示**：主题含「迁移/升级/配置」等关键词 → 追加对应章节。
 */

/** 受众档位。 */
enum class DocAudience(val label: String, val aliases: List<String>) {
    ONBOARDING("新人上手", listOf("新人", "新成员", "新手", "入门", "新加入", "onboarding", "beginner")),
    TEAM("团队内传", listOf("团队内部", "内部传阅", "同事", "team")),
    EXPERT("专家深读", listOf("专家", "资深", "架构", "expert", "advanced", "内核"));

    companion object {
        fun normalize(input: String): DocAudience? {
            val lowered = input.trim().lowercase()
            return entries.firstOrNull { audience ->
                lowered.contains(audience.label) || audience.aliases.any { lowered.contains(it) }
            }
        }
    }
}

/** 大纲章节。 */
data class DocOutlineSection(
    val order: Int,
    val title: String,
    /** 该章节的写作要点（确定性给出一半，LLM 补主题细节）。 */
    val bulletPoints: List<String>,
    val required: Boolean
)

/** 代码片段素材。 */
data class CodeSnippetAsset(
    val language: String,
    val lineCount: Int,
    /** 片段首行（标识用途）。 */
    val firstLine: String
)

/** 分析产物。 */
data class TechDocPlan(
    val audience: DocAudience,
    val outline: List<DocOutlineSection>,
    val snippets: List<CodeSnippetAsset>,
    /** 主题关键词触发的追加章节。 */
    val extraSections: List<String>,
    /** 写作难度提示。 */
    val readabilityNotes: List<String>
)

/**
 * 技术文档规划器（纯函数）。
 */
object TechDocPlanner {

    /** 主题关键词 → 追加章节表。 */
    private val TOPIC_EXTRA_SECTIONS: List<Pair<List<String>, String>> = listOf(
        listOf("迁移", "升级", "migration", "upgrade") to "迁移指引（旧版兼容矩阵 + 分步迁移 + 回滚）",
        listOf("配置", "部署", "config", "deploy", "安装") to "环境要求与安装步骤（版本矩阵 + 常见安装故障）",
        listOf("性能", "benchmark", "压测") to "性能特征（基准数据 + 调优参数 + 反模式）",
        listOf("安全", "权限", "鉴权", "security") to "安全模型（信任边界 + 权限矩阵 + 审计）",
        listOf("api", "接口", "sdk") to "API 参考（端点/签名/错误码速查表）",
        listOf("排障", "故障", "troubleshoot", "debug") to "排障决策树（症状 → 检查点 → 处置）"
    )

    /**
     * 受众归一（未知回落 ONBOARDING —— 模板默认「新加入团队的开发者」）。
     */
    fun resolveAudience(audienceInput: String): DocAudience =
        DocAudience.normalize(audienceInput) ?: DocAudience.ONBOARDING

    /**
     * 五段式大纲（受众微调）。
     */
    fun buildOutline(audience: DocAudience): List<DocOutlineSection> {
        val base = mutableListOf(
            DocOutlineSection(
                1, "一句话概述与适用场景",
                listOf("这个技术是什么（一句话，不加营销词）", "谁在什么场景下需要它", "不适用场景（划边界）"),
                required = true
            ),
            DocOutlineSection(
                2, "背景与动机",
                listOf("解决什么问题（没有它之前怎么做、痛点是什么）", "为什么选这个方案（对比过的替代方案）"),
                required = true
            ),
            DocOutlineSection(
                3, "核心概念与工作原理",
                listOf("关键术语定义（首次出现即解释）", "工作原理分层数据流图景（文字描述）", "与相邻系统的边界"),
                required = true
            ),
            DocOutlineSection(
                4, "使用示例",
                listOf("最小可运行示例（拷贝即可跑）", "进阶示例（真实场景改造）", "每个示例标注预期输出"),
                required = true
            ),
            DocOutlineSection(
                5, "常见问题与注意事项",
                listOf("高频问题（按新人心智模型排错）", "已知限制", "踩坑清单（失败模式 + 规避）"),
                required = true
            )
        )
        when (audience) {
            DocAudience.ONBOARDING -> base.add(
                DocOutlineSection(
                    6, "术语表",
                    listOf("文档内全部术语：术语/一句话解释/延伸阅读链接"),
                    required = false
                )
            )
            DocAudience.EXPERT -> base.add(
                DocOutlineSection(
                    6, "设计取舍",
                    listOf("关键决策点 + 被否掉的方案及原因", "性能/复杂度权衡数据"),
                    required = false
                )
            )
            DocAudience.TEAM -> Unit
        }
        return base
    }

    /**
     * 代码片段素材提取：参考代码的围栏块优先，无围栏则取签名行与关键调用。
     */
    fun extractSnippets(code: String): List<CodeSnippetAsset> {
        if (code.isBlank()) return emptyList()
        val scan = TemplateMarkdownScanner.scan(code)
        if (scan.codeFences.isNotEmpty()) {
            return scan.codeFences.map { fence ->
                CodeSnippetAsset(
                    language = fence.language.ifBlank { "text" },
                    lineCount = fence.lineCount,
                    firstLine = firstMeaningfulLine(code, fence.startLineIndex)
                )
            }
        }
        // 无围栏：抽签名与返回语句行（最多 4 段，每段 5 行上下文）
        val lines = code.lines()
        val signatureLines = lines.withIndex()
            .filter { (_, line) ->
                Regex("""^\s*(fun|def|func|fn|class|interface|struct)\s+\w+""").containsMatchIn(line) ||
                    Regex("""^\s*(val|var|const|let)\s+\w+\s*=""").containsMatchIn(line)
            }
            .take(4)
        return signatureLines.map { (index, line) ->
            CodeSnippetAsset(
                language = guessLanguageOf(code),
                lineCount = 5,
                firstLine = line.trim().take(70)
            )
        }
    }

    private fun firstMeaningfulLine(code: String, fenceStartIndex: Int): String {
        val lines = code.lines()
        // 围栏起始行后一行开始取非空行
        for (index in fenceStartIndex + 1 until minOf(lines.size, fenceStartIndex + 6)) {
            val line = lines.getOrNull(index)?.trim().orEmpty()
            if (line.isNotBlank() && !line.startsWith("```")) return line.take(70)
        }
        return ""
    }

    private fun guessLanguageOf(code: String): String {
        val signatures = mapOf(
            "kotlin" to Regex("""\b(fun|val)\s+\w+"""),
            "python" to Regex("""\bdef\s+\w+"""),
            "go" to Regex("""\bfunc\s+\w+"""),
            "rust" to Regex("""\bfn\s+\w+"""),
            "java" to Regex("""\bpublic\s+class""")
        )
        return signatures.entries.firstOrNull { it.value.containsMatchIn(code) }?.key ?: "text"
    }

    /**
     * 主题触发的追加章节。
     */
    fun extraSectionsFor(topic: String): List<String> =
        TOPIC_EXTRA_SECTIONS.filter { (keywords, _) ->
            keywords.any { topic.contains(it, ignoreCase = true) }
        }.map { it.second }

    /**
     * 可读性预估：素材句长与术语密度 → 写作提示。
     */
    fun readabilityNotes(code: String, topic: String): List<String> {
        val notes = mutableListOf<String>()
        if (topic.isBlank()) {
            notes.add("主题为空：文档标题待定，先归纳一句话概述")
        }
        val metrics = TemplateTextMetrics.measure(code)
        if (code.isNotBlank()) {
            if (metrics.averageSentenceLength > 45) {
                notes.add("参考素材平均句长 ${metrics.averageSentenceLength} 字偏长：正文控制在 25 字/句内")
            }
            val cjk = metrics.cjkCharCount
            val latin = metrics.latinWordCount
            if (cjk > 0 && latin > 0 && latin.toDouble() / (cjk + latin) > 0.5) {
                notes.add("参考素材英文术语密度高：正文首次出现的术语给中文注释，之后可直接用英文")
            }
        } else {
            notes.add("无参考代码：使用示例章节由 LLM 构造最小可运行示例（标注为示意）")
        }
        return notes
    }

    /**
     * 分析聚合。
     */
    fun plan(topic: String, audienceInput: String, code: String): TechDocPlan {
        val audience = resolveAudience(audienceInput)
        return TechDocPlan(
            audience = audience,
            outline = buildOutline(audience),
            snippets = extractSnippets(code),
            extraSections = extraSectionsFor(topic),
            readabilityNotes = readabilityNotes(code, topic)
        )
    }

    /** 计划 → 发现清单。 */
    fun findingsFrom(plan: TechDocPlan, topic: String): List<TemplateFinding> = buildList {
        add(
            TemplateFinding(
                ruleId = "doc.audience",
                category = TemplateFindingCategory.STRUCTURE,
                severity = TemplateSeverity.INFO,
                title = "受众档位：${plan.audience.label}",
                evidence = "大纲按该档位校准" + when (plan.audience) {
                    DocAudience.ONBOARDING -> "（含术语表章节）"
                    DocAudience.EXPERT -> "（含设计取舍章节）"
                    DocAudience.TEAM -> "（标准五段）"
                },
                suggestion = "语言深度与前置知识假设与档位一致",
                confidence = 1.0
            )
        )
        add(
            TemplateFinding(
                ruleId = "doc.outline",
                category = TemplateFindingCategory.STRUCTURE,
                severity = TemplateSeverity.INFO,
                title = "文档大纲：${plan.outline.size} 章（${plan.outline.joinToString("、") { it.title }}）",
                evidence = plan.outline.joinToString("；") { "${it.order}. ${it.title}（${it.bulletPoints.size} 个要点）" },
                suggestion = "章节顺序即写作顺序，不得跳章",
                confidence = 1.0
            )
        )
        if (plan.snippets.isNotEmpty()) {
            add(
                TemplateFinding(
                    ruleId = "doc.snippets",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.INFO,
                    title = "代码素材 ${plan.snippets.size} 段",
                    evidence = plan.snippets.joinToString("；") { "${it.language}:${it.firstLine}" },
                    suggestion = "使用示例章节优先引用真实素材（可运行性高于整洁性）",
                    confidence = 1.0
                )
            )
        }
        for (extra in plan.extraSections) {
            add(
                TemplateFinding(
                    ruleId = "doc.extra-section",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = "主题触发追加章节：$extra",
                    suggestion = "该章节与主题强相关，缺了文档就不完整",
                    confidence = 0.9
                )
            )
        }
        for (note in plan.readabilityNotes) {
            add(
                TemplateFinding(
                    ruleId = "doc.readability",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = note,
                    suggestion = "写作时执行",
                    confidence = 1.0
                )
            )
        }
    }
}
