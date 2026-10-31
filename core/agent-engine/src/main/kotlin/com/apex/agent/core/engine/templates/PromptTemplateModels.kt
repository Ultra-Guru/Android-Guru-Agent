package com.apex.agent.core.engine.templates

import kotlinx.serialization.Serializable

/**
 * 引用变量名扫描（预编译，全实例共享）：与 promptvars 包的展开器词法
 * 保持一致——跳过转义分支、捕获名称组、忽略默认值段与空白。
 */
private val REFERENCE_REGEX: Regex = Regex(
    """\\[{]{2}|[{]{2}\s*([a-zA-Z][a-zA-Z0-9_]*)"""
)

/** 模板 ID 规范：小写字母/数字/下划线/连字符，1-64 位（防路径穿越与超长）。 */
val TEMPLATE_ID_PATTERN: Regex = Regex("[a-z0-9_-]{1,64}")

/** 模板分类（内置模板覆盖前五类；用户自建默认 CUSTOM）。 */
@Serializable
enum class TemplateCategory {
    CODING,
    WRITING,
    ANALYSIS,
    TRANSLATION,
    PRODUCTIVITY,
    CUSTOM
}

/** 模板作用域（当前恒为 Coding 工位）。 */
@Serializable
enum class TemplateScope {
    CODING
}

/**
 * 模板声明的变量（表单提示 + 必填校验 + 默认值）。
 *
 * @param name 变量名（与内容里的 {{name}} 对应）
 * @param description 用途描述（表单占位提示）
 * @param required true = 渲染时缺失则判失败并列出
 * @param defaultValue 非空默认值 = overrides 缺位时的取值
 */
@Serializable
data class TemplateVariable(
    val name: String,
    val description: String = "",
    val required: Boolean = true,
    val defaultValue: String = ""
)

/**
 * 提示词模板（学习 Operit 的 prompt/config 库与 apex 的 ModePresets
 * 模式：core 层 @Serializable 数据类 + 文件持久化 + 内置不可删可复制）。
 *
 * 全字段带默认值——旧版本 JSON 反序列化安全（ignoreUnknownKeys +
 * 缺字段回退默认），与 [PromptTemplateRegistry] 的宽容读策略配合。
 *
 * @param id 稳定标识（内置以 builtin_ 前缀；用户模板自定义 slug）
 * @param name 展示名
 * @param description 用途说明
 * @param category 分类（UI 分组）
 * @param content 模板正文（可含 {{var}} 与 {{var|default}}）
 * @param variables 声明的变量（渲染表单 + 必填校验）
 * @param tags 检索标签
 * @param isBuiltIn true = 内置模板（不可删除；重播种可还原）
 * @param createdAt 创建时间戳（0 = 未记录）
 * @param updatedAt 最近修改时间戳（save 时由注册表盖戳）
 * @param usageCount 被渲染次数（引擎 render 成功后自增）
 */
@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val category: TemplateCategory = TemplateCategory.CUSTOM,
    val content: String,
    val variables: List<TemplateVariable> = emptyList(),
    val tags: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val usageCount: Long = 0,
    /**
     * #197 工位作用域（缺省按分类推导：CODING 分类 → CODING 工位，
     * 其余 → AGENT 工位；旧 JSON 无此字段时反序列化自动归位，向后兼容）。
     */
    val scope: TemplateScope = TemplateScope.CODING
) {
    /**
     * 扫描 content 实际引用的变量名（规范小写集合；转义占位符不计）。
     * 引擎与测试用它校验「声明变量与正文引用一致」。
     */
    fun referencedVariables(): Set<String> {
        val seen = LinkedHashSet<String>()
        for (m in REFERENCE_REGEX.findAll(content)) {
            if (m.value.startsWith("\\")) continue
            seen.add(m.groupValues[1].lowercase())
        }
        return seen
    }

    companion object {
        /** 内置模板 id 前缀（删除保护 + UI 锁标判定，对齐 ModePreset 惯例）。 */
        const val BUILTIN_ID_PREFIX = "builtin_"

        /** 生成新用户模板 id（时间戳 + 随机后缀，跨进程基本不碰撞）。 */
        fun newId(): String =
            "tpl_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/** 持久化信封（单文件 templates.json 的顶层结构）。 */
@Serializable
data class TemplateLibrary(
    val templates: List<PromptTemplate> = emptyList(),
    val schemaVersion: Int = 1
)

/**
 * ═══ 内置模板库（10 套，中文正文）═══
 *
 * 学习 Operit 的 FunctionalPrompts/SystemPromptConfig 内置指令哲学：
 * 开箱即用的「角色 + 硬性约束 + 输出格式」三段式；每套声明变量清单，
 * 正文用 {{var}} 引用——经 [PromptTemplateEngine] 渲染时与提示词变量
 * 引擎的内置变量（模型/时间/设备等）无缝拼接。
 *
 * 不可删除（注册表拒绝）；reseedBuiltIns(force=true) 可还原被改动的
 * 内容。id 全部满足 [TEMPLATE_ID_PATTERN]。
 */
object BuiltinPromptTemplates {

    val ALL: List<PromptTemplate> = listOf(
        PromptTemplate(
            id = "builtin_code_review",
            name = "代码审查",
            description = "按严重度分级输出结构化评审结论",
            category = TemplateCategory.CODING,
            tags = listOf("代码", "评审", "质量"),
            isBuiltIn = true,
            content = """
                你是一位资深代码评审专家。请对下面的代码进行严格评审。

                - 代码语言：{{language}}
                - 重点关注：{{focus}}

                待评审代码：
                {{code}}

                请按以下结构输出：
                1. 总体结论：通过 / 有条件通过 / 需修改
                2. 问题清单：按严重程度分级（🔴 阻断 / 🟡 重要 / 🟢 次要），每条包含位置、原因与具体修复建议
                3. 关键修复示例：仅在与结论相关时给出
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("code", "待评审的代码片段", required = true),
                TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定"),
                TemplateVariable("focus", "重点关注维度", required = false, defaultValue = "正确性、安全性、性能与可维护性")
            )
        ),
        PromptTemplate(
            id = "builtin_test_cases",
            name = "测试用例",
            description = "等价类先行、单行为单测的用例生成",
            category = TemplateCategory.CODING,
            tags = listOf("测试", "单元测试"),
            isBuiltIn = true,
            content = """
                请为下面的代码编写单元测试。

                - 测试框架：{{framework}}

                被测代码：
                {{code}}

                要求：
                1. 先列出等价类与边界条件，再写测试
                2. 覆盖正常路径、边界值与异常路径
                3. 每个测试只验证一个行为，命名说明意图
                4. 不依赖网络与真实文件系统（必要时手写 fake）
            """.trimIndent(),
            variables = listOf(
                TemplateVariable("code", "被测代码", required = true),
                TemplateVariable("framework", "测试框架", required = false, defaultValue = "JUnit")
            )
        ),
    )

    /** 已知内置模板 id 集合（导入防御与 isBuiltIn 规范化判定）。 */
    val BUILTIN_IDS: Set<String> = ALL.map { it.id }.toSet()

    /** 是否为已知内置模板 id。 */
    fun isKnownBuiltInId(id: String): Boolean = id in BUILTIN_IDS
}
