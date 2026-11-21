package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 执行上下文（PR 1 骨架）═══
 *
 * [TemplateExecutionContext] 是六阶段管线全程携带的不可变快照：
 * 模板定义 + 变量值 + 运行选项 + 注入的时钟与日志通道。
 *
 * 与旧渲染路径（正则展开变量）不同，这里的变量值由运行时在激活时
 * 一次性合并完成（用户输入 → 模板默认值 → 缺省占位），行为钩子里
 * 只做纯读取 —— 保证「同一上下文、任何阶段、读到的值一致」。
 */

/** 单个变量的取值来源（报告与调试面板展示用）。 */
enum class TemplateVariableSource(val label: String) {
    /** 用户在表单 / 斜杠参数里显式提供。 */
    USER("用户输入"),

    /** 模板声明的默认值兜底。 */
    DEFAULT("模板默认"),

    /** 既没有用户输入也没有默认值 —— 只能是可选变量。 */
    MISSING("缺省");
}

/** 一个变量的最终取值与来源（模板工坊「变量报告」消费）。 */
data class TemplateVariableAssignment(
    val name: String,
    val value: String,
    val source: TemplateVariableSource
)

/**
 * 变量合并结果：激活时由运行时构建，行为只读。
 *
 * 合并优先级（高 → 低）：
 * 1. 用户提供的 overrides（表单 / 斜杠 k=v）；
 * 2. 模板 variables 里声明的非空 defaultValue；
 * 3. 缺省（MISSING）—— 必填变量缺失时激活直接判失败，不会走到行为钩子。
 */
data class TemplateVariableReport(
    val assignments: List<TemplateVariableAssignment>
) {
    private val byName: Map<String, TemplateVariableAssignment> =
        assignments.associateBy { it.name }

    /** 变量值（未声明的变量返回 null —— 行为侧对可选变量要自兜底）。 */
    fun value(name: String): String? = byName[name]?.value

    /**
     * 变量值，缺省回落 [fallback]（行为钩子里最常用的读法：
     * 「可选变量没有值时给个合理默认」）。
     */
    fun valueOr(name: String, fallback: String): String =
        byName[name]?.value?.takeIf { it.isNotBlank() } ?: fallback

    /** 变量来源（未声明返回 null）。 */
    fun source(name: String): TemplateVariableSource? = byName[name]?.source

    /** 全部缺省的变量名（激活报告与 UI 提示用）。 */
    fun missingNames(): List<String> =
        assignments.filter { it.source == TemplateVariableSource.MISSING }.map { it.name }

    /** 用户显式提供的变量名集合。 */
    fun userProvidedNames(): List<String> =
        assignments.filter { it.source == TemplateVariableSource.USER }.map { it.name }

    companion object {
        /** 空报告（防御式：模板没有声明任何变量时使用）。 */
        fun empty(): TemplateVariableReport = TemplateVariableReport(emptyList())
    }
}

/**
 * 运行时选项（模板激活时携带；全部带安全默认值）。
 *
 * @param maxInputChars 单个输入变量的截断上限（防巨量输入打爆提示词；
 *   0 = 不截断，默认 20000 对齐引擎工具输出截断惯例）
 * @param autoRepair true = 校验发现可修复违规时自动执行修复阶段
 * @param strictValidation true = 任何契约违规都计入报告（false 只记阻断级）
 * @param injectAnalysisIntoPrompt true = COMPOSE 阶段注入确定性分析附录
 * @param outputLanguage 输出语言提示（默认中文；行为可据此调整渲染语言）
 */
data class TemplateRuntimeOptions(
    val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    val autoRepair: Boolean = true,
    val strictValidation: Boolean = true,
    val injectAnalysisIntoPrompt: Boolean = true,
    val outputLanguage: String = "zh"
) {
    companion object {
        /** 默认输入截断上限（字符数，非字节 —— 与 UI 字数口径一致）。 */
        const val DEFAULT_MAX_INPUT_CHARS: Int = 20_000

        /** 校验豁免：非严格模式下仍会计入报告的违规类型数量上限（防噪声）。 */
        const val MAX_REPORTED_VIOLATIONS: Int = 24
    }
}

/**
 * 输入形态探测结果（PREPARE 阶段的公共产物，任何行为都可复用）。
 *
 * 这里只放「与领域无关、一眼就能算出来」的形态事实；语言判定等重逻辑
 * 在共享工具箱（PR 3）里，行为按需调用。
 *
 * @param charCount 总字符数（去首尾空白后）
 * @param lineCount 总行数
 * @param nonBlankLineCount 非空行数
 * @param averageLineLength 平均行长（字符）
 * @param longestLineLength 最长行的字符数
 * @param looksLikeMarkdown 含 Markdown 结构（标题/列表/代码围栏）
 * @param looksLikeDiff 含统一 diff 形态（^+^-^ 行）
 * @param looksLikeCode 含代码形态（花括号/分号/缩进块）
 * @param looksLikeUrl 含 URL
 * @param cjkCharCount CJK 字符数（中/日/韩字形区间）
 * @param latinWordCount 拉丁词计数（空白分词）
 */
data class TemplateInputFacets(
    val charCount: Int,
    val lineCount: Int,
    val nonBlankLineCount: Int,
    val averageLineLength: Int,
    val longestLineLength: Int,
    val looksLikeMarkdown: Boolean,
    val looksLikeDiff: Boolean,
    val looksLikeCode: Boolean,
    val looksLikeUrl: Boolean,
    val cjkCharCount: Int,
    val latinWordCount: Int
) {
    /** 主体书写系统的粗判（行为据此决定分词/断句策略）。 */
    val dominantScriptIsCjk: Boolean get() = cjkCharCount * 2 > (latinWordCount + cjkCharCount)

    /** 是否为「有实质内容的输入」（全空白/一两个字符视为无内容）。 */
    val hasSubstantialContent: Boolean get() = charCount >= MIN_SUBSTANTIAL_CHARS

    companion object {
        const val MIN_SUBSTANTIAL_CHARS = 8

        /** 空输入的规范形态（防御式默认值，PREPARE 永不返回 null）。 */
        fun empty(): TemplateInputFacets = TemplateInputFacets(
            charCount = 0,
            lineCount = 0,
            nonBlankLineCount = 0,
            averageLineLength = 0,
            longestLineLength = 0,
            looksLikeMarkdown = false,
            looksLikeDiff = false,
            looksLikeCode = false,
            looksLikeUrl = false,
            cjkCharCount = 0,
            latinWordCount = 0
        )
    }
}

/**
 * PREPARE 阶段产物：规范化后的各变量输入 + 主输入形态。
 *
 * @param normalizedInputs 变量名 → 规范化值（trim + 换行统一 + 截断后）
 * @param primaryInputName 主输入变量名（各行为自己指定，如 code / text / pattern）
 * @param facets 主输入的形态探测结果
 * @param allFacets 每个非空变量的形态探测（副输入也常有结构信息）
 * @param truncations 每个被截断的变量与截断前后长度（报告留痕）
 */
data class TemplatePreparedInput(
    val normalizedInputs: Map<String, String>,
    val primaryInputName: String,
    val facets: TemplateInputFacets,
    val allFacets: Map<String, TemplateInputFacets> = emptyMap(),
    val truncations: Map<String, Int> = emptyMap()
) {
    /** 主输入（行为里最常读的值；缺省空串，钩子永不抛）。 */
    val primaryInput: String get() = normalizedInputs[primaryInputName].orEmpty()

    /** 任意输入变量（未声明返回空串 —— 与主输入同语义）。 */
    fun input(name: String): String = normalizedInputs[name].orEmpty()

    companion object {
        /** 空输入壳（异常路径防御式兜底）。 */
        fun empty(primaryInputName: String): TemplatePreparedInput = TemplatePreparedInput(
            normalizedInputs = emptyMap(),
            primaryInputName = primaryInputName,
            facets = TemplateInputFacets.empty(),
            allFacets = emptyMap(),
            truncations = emptyMap()
        )
    }
}

/**
 * 执行上下文（六阶段全程不可变）。
 *
 * @param template 模板快照（内置模板在激活时强制使用出厂定义 —— 不可更改）
 * @param variables 变量合并报告（激活时构建完成）
 * @param options 运行选项
 * @param clock 注入时钟（测试假钟；行为不得直接读墙钟）
 * @param logger 注入日志通道（默认丢弃；生产端接 AppLogger）
 */
data class TemplateExecutionContext(
    val template: TemplateTemplateSnapshot,
    val variables: TemplateVariableReport,
    val options: TemplateRuntimeOptions = TemplateRuntimeOptions(),
    val clock: () -> Long = System::currentTimeMillis,
    val logger: (String) -> Unit = {}
) {
    /** 便捷读法：变量值 + 空白回落（行为钩子里最常用的一行）。 */
    fun variableOr(name: String, fallback: String): String =
        variables.valueOr(name, fallback)

    /** 便捷读法：变量值（可空 —— 可选变量未提供时为 null）。 */
    fun variable(name: String): String? = variables.value(name)

    /** 留痕（带模板 id 前缀，生产端日志可按模板聚合）。 */
    fun log(message: String) {
        logger("TemplateRuntime[${template.id}]: $message")
    }
}

/**
 * 模板快照 —— 交给行为层的模板定义（从注册表的 PromptTemplate 拍平而来）。
 *
 * 只暴露行为真正需要的字段：内容、变量声明、分类与内置标记。
 * 不直接复用 PromptTemplate 是为了防腐（app 层模型变动不波及运行时包），
 * 快照在激活时由运行时构建（PR 4），内置模板强制取出厂定义。
 *
 * @param id 模板 id
 * @param name 展示名
 * @param content 模板正文（含变量占位符）
 * @param declaredVariableNames 声明的变量名（有序）
 * @param categoryLabel 分类名（展示用）
 * @param isBuiltIn 是否内置（内置 = 行为目录必有对应实现）
 */
data class TemplateTemplateSnapshot(
    val id: String,
    val name: String,
    val content: String,
    val declaredVariableNames: List<String>,
    val categoryLabel: String,
    val isBuiltIn: Boolean
) {
    init {
        require(id.isNotBlank()) { "template id must not be blank" }
    }
}
