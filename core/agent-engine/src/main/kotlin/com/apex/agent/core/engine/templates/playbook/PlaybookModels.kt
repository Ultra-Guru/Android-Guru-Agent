package com.apex.agent.core.engine.templates.playbook

import com.apex.agent.core.engine.templates.TemplateCategory
import com.apex.agent.core.engine.templates.TemplateVariable
import kotlinx.serialization.Serializable

/** 剧本 ID 规范（与模板 id 同款：小写字母/数字/下划线/连字符，1-64 位）。 */
val PLAYBOOK_ID_PATTERN: Regex = Regex("[a-z0-9_-]{1,64}")

/**
 * 剧本执行模式：
 * - [COMPOSED] 单轮合成：全部幕子提示词一次性并入指令（短任务，如提交信息）；
 * - [SEQUENCED] 分幕推进：只注入当前幕子提示词，幕推进判定器逐幕放行
 *   （长任务，如代码审查）—— token 渐进披露。
 */
@Serializable
enum class PlaybookExecutionMode {
    COMPOSED,
    SEQUENCED
}

/**
 * 自动挂载档位（app 设置层持有总开关 + 每剧本覆盖）：
 * - [OFF] 不自动识别（仅手动挂载 / agent 工具通道）；
 * - [SUGGEST] 识别命中 → 输入区上方建议条，用户一键确认；
 * - [AUTO] 识别命中 → 静默挂载（chip 可见可卸载）。
 */
@Serializable
enum class AutoMountMode {
    OFF,
    SUGGEST,
    AUTO
}

/**
 * 识别上下文门：触发规则只有在上下文满足时才计分（防「提交信息」一词
 * 在无工作区的会话里误挂 Git 剧本）。
 */
@Serializable
enum class TriggerContextGate {
    /** 无门槛。 */
    NONE,

    /** 需要已绑定工作区（Git 剧本等）。 */
    WORKSPACE_PRESENT,

    /** 输入里含代码形态信号（代码块 / 代码词密度）。 */
    CODE_PRESENT
}

/**
 * 触发规则：keywords 任一命中（忽略大小写的包含匹配）**或** regexes 任一
 * 命中 → 本规则得 [score] 分（每条规则每轮只计一次，不按命中次数叠加）。
 */
@Serializable
data class TriggerRule(
    val keywords: List<String> = emptyList(),
    val regexes: List<String> = emptyList(),
    val score: Int,
    val contextGate: TriggerContextGate = TriggerContextGate.NONE
)

/**
 * 剧本幕：子提示词的最小执行单元。
 *
 * @param id 幕标识（剧本内唯一，slug）
 * @param title 幕标题（章节头「【幕k·title】」与 chip 进度共用）
 * @param subPrompt 子提示词正文（可含 {{var}}；变量继承剧本级声明）
 * @param acceptance 验收条款（人读口径；PR-D 前仅作结构化验收提示 +
 *   正文非空判定，不做逐条款自动校验）
 * @param reconId 确定性侦察绑定（[PlaybookReconCatalog] 键；null = 无侦察）
 */
@Serializable
data class PlaybookStage(
    val id: String,
    val title: String,
    val subPrompt: String,
    val acceptance: List<String> = emptyList(),
    val reconId: String? = null
)

/**
 * ═══ 任务剧本定义 ═══
 *
 * 模板工坊的 agentic 升级：剧本 = 主提示词（角色 + 硬约束 + 幕推进协议）
 * + 分幕子提示词 + 触发词表。agent 侧经识别器自动选中、幕推进判定器
 * 逐幕推进；数据纪律与 PromptTemplate 同源（内置不可改 / 单文件原子
 * 持久化 / 导入永不伪造内置，见 [PlaybookRegistry]）。
 *
 * @param id 稳定标识（内置以 builtin_playbook_ 前缀）
 * @param name 展示名（建议条 / chip 引用）
 * @param master 主提示词正文（可含 {{var}}）
 * @param stages 幕序列（COMPOSED 模式 = 全幕子提示词按序并成单轮；
 *   SEQUENCED 模式 = 按幕推进；至少 1 幕）
 * @param triggers 触发规则表（识别器计分用；可空 = 只能手动挂载）
 * @param variables 剧本级变量声明（幕子提示词继承引用）
 * @param mode 执行模式
 * @param autoMount 剧本级默认档（app 设置可覆盖）
 */
@Serializable
data class PlaybookDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val category: TemplateCategory = TemplateCategory.CODING,
    val master: String,
    val stages: List<PlaybookStage>,
    val triggers: List<TriggerRule> = emptyList(),
    val variables: List<TemplateVariable> = emptyList(),
    val tags: List<String> = emptyList(),
    val mode: PlaybookExecutionMode = PlaybookExecutionMode.SEQUENCED,
    val autoMount: AutoMountMode = AutoMountMode.SUGGEST,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val usageCount: Long = 0
) {
    init {
        require(stages.isNotEmpty()) { "playbook must have at least one stage (id='$id')" }
    }

    /** 幕总数。 */
    val stageCount: Int get() = stages.size

    /** 按 0 基下标取幕（越界返回 null——防御式语义）。 */
    fun stageAt(index: Int): PlaybookStage? = stages.getOrNull(index)

    /** 幕标记正文（章节头用）：「幕1·预检侦察」。 */
    fun stageMarker(index: Int): String {
        val stage = stageAt(index) ?: return "幕${index + 1}"
        return "幕${index + 1}·${stage.title}"
    }

    /** 扫描 master + 全部幕子提示词实际引用的变量名（规范小写集合）。 */
    fun referencedVariables(): Set<String> {
        val regex = Regex("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)")
        val seen = LinkedHashSet<String>()
        val bodies = sequenceOf(master) + stages.asSequence().map { it.subPrompt }
        for (body in bodies) {
            for (m in regex.findAll(body)) seen.add(m.groupValues[1].lowercase())
        }
        return seen
    }
}

/** playbooks.json 持久化信封（与 TemplateLibrary 同款）。 */
@Serializable
data class PlaybookLibrary(
    val playbooks: List<PlaybookDefinition> = emptyList(),
    val schemaVersion: Int = 1
)
