package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 契约层（PR 1 骨架）═══
 *
 * 定位：把「模板 = 一段提示词文本」升级为「模板 = 一段可执行的 Kotlin 行为」。
 * 每一个内置模板在运行时层都有一个对应的 [AgentTemplateBehavior] 实现：
 * 它不仅负责把变量填进正文，还承担确定性的前置分析、输出契约校验、
 * 坏格式修复与结构化渲染 —— 模板切换由此从「提示词注入」升级为
 * 「行为装配 + 管线执行」。
 *
 * 设计原则（对齐 AGENTS.md 架构纪律）：
 * - **薄包装不重写**：行为层不持有大模型、不 fork 引擎主循环 —— 组装好的
 *   提示词交由宿主（聊天引擎）发送，回包再回到行为层做校验/修复/渲染；
 * - **纯 JVM 可测**：本包全部为确定性 Kotlin 逻辑（无 Android 依赖），
 *   每个行为都能在 `:core:agent-engine:test` 里离线验证；
 * - **防御式**：所有钩子不得向外抛异常 —— 输入再离谱也折叠为空分析 /
 *   校验违规 + logger 留痕，绝不把模板功能本身打挂。
 */
interface AgentTemplateBehavior {

    /** 对应的模板 id（必须与内置模板 id 完全一致，目录层做 1:1 完整性校验）。 */
    val behaviorId: String

    /** 行为清单：声明能力面、启用阶段与输出契约（幂等纯函数）。 */
    fun manifest(): TemplateBehaviorManifest

    /**
     * 阶段一 · 输入预处理：规范化输入（trim/换行统一/超长截断）、探测
     * 输入形态（语言/结构/量级），产出 [TemplatePreparedInput]。
     * 契约：永不返回 null、永不抛；输入空白也要给出可继续的空壳结果。
     */
    suspend fun prepare(context: TemplateExecutionContext): TemplatePreparedInput

    /**
     * 阶段二 · 确定性分析：在请求大模型之前，用纯代码规则产出
     * [TemplateAnalysis]（规则命中清单 + 统计指标 + 喂给提示词的硬事实）。
     * 这是「模板不只是提示词」的落点之一：LLM 拿到的是带着确定性证据
     * 的任务书，而不是一段裸模板。
     */
    suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis

    /**
     * 阶段三 · 提示词组装：把模板正文、变量值与确定性分析合成最终
     * 发往引擎的用户消息（[TemplateTurnRequest]）。默认实现走
     * 「变量替换 + 分析附录」，行为可覆写为自定义结构。
     */
    fun composePrompt(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput,
        analysis: TemplateAnalysis
    ): TemplateTurnRequest

    /**
     * 阶段四 · 输出契约校验：解析大模型回包，逐条比对 [TemplateOutputContract]，
     * 产出 [TemplateValidationReport]（违规清单 + 已解析出的结构化段落）。
     */
    fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport

    /**
     * 阶段五 · 坏格式修复：对可修复违规做确定性修补（剥寒暄前导、
     * 补齐缺失章节壳、归一列表编号等）。不可修复的违规原样保留并在
     * 报告中留痕。
     */
    fun repairOutput(
        raw: String,
        report: TemplateValidationReport,
        context: TemplateExecutionContext
    ): String

    /**
     * 阶段六 · 结构化渲染：把（修复后的）回包渲染为最终展示文本 +
     * 机器可读摘要（[TemplateRenderedReport]），供 UI 展示与后续消费。
     */
    fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport
}

/**
 * 行为清单（不可变快照）—— 描述「这个模板的行为版本」能做什么。
 * UI / 运行时用它展示能力徽标、决定执行哪些阶段。
 *
 * @param behaviorId 对应模板 id（与 [AgentTemplateBehavior.behaviorId] 一致）
 * @param displayName 展示名（与模板 name 对齐，冗余持有避免反向依赖）
 * @param summary 一句话能力说明（工坊「使用模板」页的能力预览）
 * @param supportedPhases 实际做了真实工作的阶段集合（其余阶段走基类默认）
 * @param features 能力徽标（工坊展示 + 集成测试断言用）
 * @param outputContract 输出契约（校验/修复/渲染三阶段共享）
 * @param deterministicPreAnalysis true = analyze 阶段有确定性规则（非空壳）
 * @param promptEnrichmentMode 提示词组装时如何注入确定性分析
 * @param behaviorVersion 行为实现版本号（升级行为逻辑时递增，报告留痕）
 */
data class TemplateBehaviorManifest(
    val behaviorId: String,
    val displayName: String,
    val summary: String,
    val supportedPhases: Set<TemplatePhaseKind>,
    val features: Set<TemplateBehaviorFeature> = emptySet(),
    val outputContract: TemplateOutputContract = TemplateOutputContract(),
    val deterministicPreAnalysis: Boolean = false,
    val promptEnrichmentMode: TemplatePromptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
    val behaviorVersion: Int = 1
) {
    init {
        require(behaviorId.isNotBlank()) { "behaviorId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }

    /** 该阶段是否声明了真实实现（未声明阶段运行时跳过统计留痕）。 */
    fun supports(phase: TemplatePhaseKind): Boolean = phase in supportedPhases
}

/**
 * 管线阶段（顺序即执行序）。
 *
 * @param PREPARE 输入预处理（规范化 + 形态探测）
 * @param ANALYZE 确定性分析（规则引擎 / 统计 / 结构扫描）
 * @param COMPOSE 提示词组装（变量替换 + 分析注入）
 * @param VALIDATE 回包契约校验（结构比对）
 * @param REPAIR 坏格式修复（可修复违规的确定性修补）
 * @param RENDER 结构化渲染（展示文本 + 机器摘要）
 */
enum class TemplatePhaseKind(val label: String) {
    PREPARE("输入预处理"),
    ANALYZE("确定性分析"),
    COMPOSE("提示词组装"),
    VALIDATE("契约校验"),
    REPAIR("格式修复"),
    RENDER("结构渲染");

    /** 全阶段的规范执行序（ordinal 即声明序，显式常量避免魔法数）。 */
    companion object {
        val EXECUTION_ORDER: List<TemplatePhaseKind> = entries.toList()
    }
}

/**
 * 行为能力徽标 —— 工坊「能力预览」与集成测试的断言锚点。
 * 每个行为在自己的清单里声明真实具备的能力，不虚标。
 */
enum class TemplateBehaviorFeature(val label: String) {
    /** 支持编程语言自动探测（Kotlin/Java/Python/JS/TS/Go/Rust 等）。 */
    LANGUAGE_DETECTION("语言探测"),

    /** 内置逐语言静态规则表（确定性命中，非 LLM 判断）。 */
    LANGUAGE_RULES("静态规则表"),

    /** 支持统一 diff 解析（文件清单 / 增删统计 / 块定位）。 */
    DIFF_PARSING("Diff 解析"),

    /** 支持正则表达式 AST 解析与逐段解释。 */
    REGEX_AST("正则解析"),

    /** 支持文本关键度打分（抽取式预分析）。 */
    TEXT_SCORING("关键度打分"),

    /** 支持术语表 / 领域词汇对照（翻译、文档等场景）。 */
    GLOSSARY("术语表"),

    /** 支持格式保真校验（Markdown 结构 / 占位符 / 换行不丢失）。 */
    FORMAT_PRESERVATION("格式保真"),

    /** 支持严重度分级模型（BLOCKER/IMPORTANT/MINOR/INFO）。 */
    SEVERITY_MODEL("严重度分级"),

    /** 支持边界值推导（测试用例等价类/边界计算）。 */
    BOUNDARY_DERIVATION("边界值推导"),

    /** 支持风险与回滚清单生成。 */
    RISK_ROLLBACK("风险回滚"),

    /** 支持输入质量评估（复现步骤完整性 / 需求关键词覆盖等）。 */
    INPUT_QUALITY("输入质量评估");

    /** 全部徽标的稳定 id 集合（清单序列化与测试断言用）。 */
    companion object {
        val ALL_IDS: Set<String> = entries.map { it.name }.toSet()
    }
}

/**
 * 提示词组装时确定性分析的注入方式。
 *
 * @param APPEND_FINDINGS 默认：分析结果以「确定性分析附录」追加在正文尾部，
 *   LLM 拿到带证据的任务书；
 * @param INLINE_HINTS 轻量：只注入精简提示行（分析很重、正文已含结构时用）；
 * @param NONE 不注入（行为自身在 COMPOSE 覆写里已经消化了分析结果）。
 */
enum class TemplatePromptEnrichmentMode {
    APPEND_FINDINGS,
    INLINE_HINTS,
    NONE
}
