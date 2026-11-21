package com.apex.agent.core.code.experience

import kotlinx.serialization.Serializable

/**
 * # 角色经验卡数据模型 —— 跨会话经验积累的骨架
 *
 * 用户规格：「每次任务结束后，自动提炼一条『经验』：什么场景、用了什么
 * 工具序列、结果如何、下次注意什么。存成结构化记录，下次同类任务开始
 * 时检索注入。」
 *
 * ## 定位与边界
 *
 * - 本包住在 core:code-engine（纯 JVM），与 [com.apex.agent.core.code.longtask]
 *   平级：长任务中心回答「这次跑了多久/多大」，经验卡回答「这个角色下次
 *   遇到同类任务该怎么做」—— 两者都从同一条 AgentEvent 流取材，但聚合
 *   目标不同（长任务按规模阈值入库；经验卡凡**带工具调用的轮**必提炼
 *   —— 经验的价值不在规模而在复用，纯聊天轮无执行证据不沉淀）；
 * - 提炼是**确定性代码**（[RoleExperienceExtractor] 的规则表），不是提示词
 *   注入 —— 场景标签靠关键词表分类、注意点靠规则引擎生成，跨会话可复现；
 * - 引擎不感知本包：CodeViewModel 在 runEngine 的收尾链路调用
 *   [RoleExperienceTracker]，注入走 session extras 通道（与「小圆环」同路）。
 *
 * ## 结构化四要素（对应用户规格）
 *
 * | 字段 | 用户口径 | 生成方式 |
 * |------|----------|----------|
 * | [scenario] + [scenarioTags] | 什么场景 | goal 首句截断 + 关键词分类表 |
 * | [toolSequence] / [toolProfile] | 用了什么工具序列 | 事件流按调用顺序聚合（真序列，非频次表） |
 * | [outcome] + 计数族 | 结果如何 | VM 裁决的状态 + 聚合计数 |
 * | [notes] | 下次注意什么 | [RoleExperienceExtractor] 规则引擎 |
 *
 * ## 强化信号（hitCount / lastUsedAt）
 *
 * 每条经验被**实际注入**过一次（召回命中且用于本轮任务）→ hitCount +1、
 * lastUsedAt 刷新。这是「经验被反复验证」的强化语义：排序时高分经验
 * 更容易被再次召回（见 [RoleExperienceRecall] 的打分表）。
 */
@Serializable
data class RoleExperience(
    /** 稳定标识：`exp_<epochMillis>_<序号>`（单进程内单调，文件名安全字符）。 */
    val id: String,
    /** 归属角色 id（builtin_coding_git / role_<ts>；文件按此分卷）。 */
    val roleId: String,
    /** 场景描述：goal 首行截断（≤[SCENARIO_MAX_CHARS]）。 */
    val scenario: String,
    /** 场景标签（[ExperienceScenario.key]，≤2 个，代码分类非模型判断）。 */
    val scenarioTags: List<String> = emptyList(),
    /**
     * 工具调用序列（真实时间序，相邻同名压缩 + 逃逸折叠，见
     * [RoleExperienceExtractor.compressToolSequence]；上限
     * [TOOL_SEQUENCE_MAX] 条）——「工具序列」的忠实形态。
     */
    val toolSequence: List<String> = emptyList(),
    /** 工具 × 调用次数（频次视角，与序列互补；上限 [TOOL_PROFILE_MAX] 键）。 */
    val toolProfile: Map<String, Int> = emptyMap(),
    /** 结果：COMPLETED / FAILED / ABORTED（对齐 LongTaskStatus 口径）。 */
    val outcome: String,
    /** 迭代轮数（引擎 ReAct 循环计数）。 */
    val iterations: Int = 0,
    /** 工具调用总次数。 */
    val toolCalls: Int = 0,
    /** 工具失败次数（成功/失败都计——「哪些工具在反复失败」本身是信号）。 */
    val toolFailures: Int = 0,
    /** 运行时长（ms）。 */
    val durationMs: Long = 0,
    /** 成功触碰的文件数（CodeEdit/CodeWrite 成功调用提取）。 */
    val filesTouched: Int = 0,
    /** 下次注意（确定性规则生成，≤[NOTES_MAX_CHARS]）。 */
    val notes: String = "",
    /** 工作区名（哪块地里的经验）。 */
    val workspaceName: String = "",
    /** 首次沉淀时间（epoch ms）。 */
    val createdAt: Long = 0,
    /** 被召回注入过的次数（强化信号；0 = 从未被复用）。 */
    val hitCount: Int = 1,
    /** 最近一次被注入的时间（epoch ms；0 = 从未被注入）。 */
    val lastUsedAt: Long = 0
) {
    companion object {
        /** scenario 截断上限（单行可读，列表卡片不折行）。 */
        const val SCENARIO_MAX_CHARS = 120

        /** notes 截断上限（经验卡正文密度上限，注入段也有界）。 */
        const val NOTES_MAX_CHARS = 400

        /** 工具序列条目上限（逃逸折叠后的天花板）。 */
        const val TOOL_SEQUENCE_MAX = 24

        /** 工具频次表键上限（超出按次数降序截断）。 */
        const val TOOL_PROFILE_MAX = 12

        /** id 生成（线程安全计数器；epoch + 计数保证同毫秒不撞）。 */
        private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

        fun newId(clock: () -> Long = { System.currentTimeMillis() }): String =
            "exp_${clock()}_${idCounter.incrementAndGet()}"
    }
}

/**
 * 每角色一卷的经验册（落盘单位：`<baseDir>/<safe(roleId)>.json`）。
 *
 * @param roleId 卷主角色 id（与文件名一一对应；装载失败自愈时用于日志定位）。
 * @param experiences 经验卡列表（追加即追加；裁剪由 [RoleExperienceStore] 负责）。
 */
@Serializable
data class RoleExperienceBook(
    val roleId: String,
    val experiences: List<RoleExperience> = emptyList()
)

/**
 * # 场景分类学 —— 经验卡的「什么场景」维度
 *
 * 代码驱动的任务意图分类（关键词表 → 标签），不做提示词推断：
 * - 中英双语关键词，大小写不敏感，子串匹配（CJK 无分词器，子串即词）；
 * - 每个任务最多打 [RoleExperienceExtractor.MAX_TAGS_PER_TASK] 个标签
 *   （多标签保留歧义：『修复测试』同时命中 BUG_FIX + TEST 是正确画像）；
 * - 召回打分时标签交集是最强信号（见 [RoleExperienceRecall]）。
 *
 * 标签集是**封闭枚举**：新场景类别加枚举项 + 关键词，历史数据靠 key
 * 字符串兼容（未知 key 反序列化不炸，只是打不中交集分）。
 */
enum class ExperienceScenario(
    /** 落盘/匹配用稳定 key（恒小写下划线）。 */
    val key: String,
    /** 中文显示名（UI 经验卡徽标）。 */
    val zhLabel: String,
    /** 触发关键词（小写；中英混合；命中任一即打标）。 */
    val keywords: List<String>
) {
    BUG_FIX(
        "bug_fix", "缺陷修复",
        listOf("fix", "bug", "缺陷", "修复", "报错", "错误", "crash", "崩溃",
            "exception", "异常", "regression", "回归", "failing", "失败", "排查")
    ),
    FEATURE(
        "feature", "新功能",
        listOf("implement", "实现", "feature", "功能",
            "support", "支持", "create", "创建", "写一个", "做个", "新增")
    ),
    REFACTOR(
        "refactor", "重构优化",
        listOf("refactor", "重构", "clean up", "清理", "rename", "重命名",
            "extract", "抽取", "optimize", "优化", "improve", "改进", "simplify", "简化")
    ),
    TEST(
        "test", "测试验证",
        listOf("test", "测试", "unit", "单测", "coverage", "覆盖",
            "assert", "断言", "verify", "验证")
    ),
    DOCS(
        "docs", "文档撰写",
        listOf("doc", "文档", "readme", "注释", "comment", "说明文档",
            "guide", "指南", "changelog")
    ),
    RELEASE(
        "release", "发布部署",
        listOf("release", "发布", "deploy", "部署", "version", "版本",
            "publish", "上架", "hotfix", "热修", "上线", "rollout")
    ),
    ANALYSIS(
        "analysis", "分析调研",
        listOf("analyze", "分析", "investigate", "调查", "why", "为什么",
            "原因", "explore", "探索", "understand", "理解", "review", "审查",
            "explain", "解释", "看看", "检查一下")
    ),
    BUILD(
        "build", "构建编译",
        listOf("compile", "编译", "gradle", "assemble",
            "dependency", "依赖", "构建失败", "编译失败")
    ),
    TRANSLATE(
        "translate", "翻译转换",
        listOf("translate", "翻译", "convert", "转换", "migrate", "迁移",
            "port", "移植")
    ),
    GIT_WORKFLOW(
        "git_workflow", "Git 工作流",
        listOf("git ", "commit", "提交", "branch", "分支", "rebase", "变基",
            "merge", "合并", "cherry-pick", "cherry pick", "挑拣",
            "pull request", "冲突", "conflict", "revert", "回滚", "git的", "git 工作流")
    ),
    OTHER("other", "其他", emptyList());

    companion object {
        /** key → 枚举的查找索引（静态一次，热路径零遍历）。 */
        private val byKey: Map<String, ExperienceScenario> =
            entries.associateBy { it.key }

        /** 反序列化兜底：未知 key → null（历史数据前向兼容，不炸只降级）。 */
        fun fromKey(key: String): ExperienceScenario? = byKey[key]
    }
}

/**
 * 经验提炼的原始素材（Tracker 聚合 → Extractor 规则引擎的输入）。
 *
 * 与 [RoleExperience] 的差异：这是**一轮运行的现场快照**（可变聚合的定格），
 * 不是持久化实体 —— Extractor 消费后产出不可变经验卡。
 */
data class ExperienceRawInput(
    val roleId: String,
    val goal: String,
    val workspaceName: String,
    val outcome: String,
    val iterations: Int,
    val toolCallCount: Int,
    val toolFailures: Int,
    val orderedTools: List<String>,
    val toolCounts: Map<String, Int>,
    val filesTouchedCount: Int,
    val durationMs: Long,
    /** FAILED 时的错误文本（已由 VM 净化；null/空 = 非失败或无信息）。 */
    val errorMessage: String? = null,
    /** 时钟（默认墙钟；测试注入假时钟）。 */
    val clock: () -> Long = { System.currentTimeMillis() }
)
