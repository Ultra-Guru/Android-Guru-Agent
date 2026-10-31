package com.apex.agent.core.code.experience

/**
 * # 经验提炼器 —— 确定性规则引擎（「下次注意什么」的代码化答案）
 *
 * 用户规格里「下次注意什么」这个维度最容易退化成一段提示词让模型自由
 * 发挥 —— 本类反其道而行：**全部结论来自可复现的规则表**，同一份输入
 * 在任何设备、任何模型上提炼出同一条经验。规则按证据强度排序输出。
 *
 * ## 规则总表（按优先级）
 *
 * | 规则 | 触发条件 | 产出（下次注意） |
 * |------|----------|------------------|
 * | R1 失败根因 | outcome=FAILED 且有错误文本 | 错误首行 + 按错误类别的行动建议 |
 * | R2 工具链健康 | 失败率 ≥ 30% 且 toolCalls ≥ 3 | 「先核对工具参数/路径再批量执行」 |
 * | R3 已验证路径 | COMPLETED 且零工具失败 | 「工具序列可直接复用」（强化信号） |
 * | R4 高频工具 | top2 工具合计 ≥ 60% 调用量 | 点名主力工具（下次优先选） |
 * | R5 长任务拆步 | iterations ≥ 8 | 「先列 TODO 拆步再动手」 |
 * | R6 改动面广 | filesTouched ≥ 5 | 「先确认影响范围/回归面」 |
 * | R7 短路完成 | COMPLETED 且 iterations ≤ 2 且 toolCalls ≤ 3 | 「同类任务无需深潜，直接走」 |
 *
 * 规则可以叠加（一条经验 2-3 条注意点是常态），拼接后统一截断
 * [RoleExperience.NOTES_MAX_CHARS]。
 *
 * ## 场景分类（「什么场景」的代码化答案）
 *
 * [scenarioTagsOf]：goal 小写化后对 [ExperienceScenario.keywords] 做子串
 * 扫描，命中即打标；最多 [MAX_TAGS_PER_TASK] 个（按枚举声明序取前 N，
 * 保证同输入同输出）。无命中 → OTHER 兜底不打（空标签 = 召回时靠词元
 * 交集通道）。
 *
 * ## 工具序列压缩（「工具序列」的忠实形态）
 *
 * [compressToolSequence]：真实调用序列会有 `ShellExecute → ShellExecute →
 * … ×40` 的逃逸形态 —— 相邻同名合并为 `ShellExecute×40`，非相邻保留原序
 * （`Read → Edit → Read` 的往返本身就是信息），总条目封顶
 * [RoleExperience.TOOL_SEQUENCE_MAX]。
 */
object RoleExperienceExtractor {

    /** 每任务场景标签上限（多标签保留歧义，但不超过 2 免得画像糊掉）。 */
    const val MAX_TAGS_PER_TASK = 2

    /** R2 阈值：工具失败率 ≥ 此比例（且样本 ≥3）触发健康告警。 */
    const val TOOL_FAILURE_RATE_THRESHOLD = 0.30

    /** R5 阈值：迭代数 ≥ 此值视为长任务。 */
    const val LONG_TASK_ITERATIONS = 8

    /** R6 阈值：触碰文件数 ≥ 此值视为广改动面。 */
    const val BROAD_FILES = 5

    /** R1 错误文本截断（注意点里只留首行有效信息）。 */
    private const val ERROR_LINE_MAX = 100

    // ═══════════════════ 场景分类 ═══════════════════

    /**
     * goal → 场景标签（≤[MAX_TAGS_PER_TASK]，枚举声明序稳定输出）。
     *
     * 大小写不敏感子串匹配；空 goal → 空标签。注意 FEATURE 的关键词刻意
     * 排除了裸 "add"（太常见：`add a comment` 是改动不是新功能）—— 中文
     * 「新增/实现」与英文 implement/feature 承担语义。
     */
    fun scenarioTagsOf(goal: String): List<String> {
        val text = goal.trim().lowercase()
        if (text.isEmpty()) return emptyList()
        val hits = ExperienceScenario.entries
            .filter { it != ExperienceScenario.OTHER }
            .filter { scenario -> scenario.keywords.any { text.contains(it) } }
        return hits.take(MAX_TAGS_PER_TASK).map { it.key }
    }

    // ═══════════════════ 工具序列压缩 ═══════════════════

    /**
     * 真实序列 → 压缩形态：相邻同名折叠 `Tool×n`；非相邻往返保留；条目
     * 封顶 [RoleExperience.TOOL_SEQUENCE_MAX]（超限丢尾巴 —— 序列头部是
     * 任务的开场惯例，对复用最有价值）。
     */
    fun compressToolSequence(sequence: List<String>): List<String> {
        if (sequence.isEmpty()) return emptyList()
        val compressed = ArrayList<String>(sequence.size)
        var i = 0
        while (i < sequence.size) {
            val name = sequence[i]
            var run = 1
            while (i + run < sequence.size && sequence[i + run] == name) run++
            compressed += if (run > 1) "$name×$run" else name
            i += run
        }
        return compressed.take(RoleExperience.TOOL_SEQUENCE_MAX)
    }

    // ═══════════════════ 主入口：素材 → 经验卡 ═══════════════════

    /**
     * 一轮运行的现场快照 → 不可变经验卡。
     *
     * 调用方（Tracker）已保证：goal 非空、至少一次工具调用或一轮迭代
     * （纯聊天轮不沉淀 —— 没有可复用的执行证据）。
     */
    fun extract(input: ExperienceRawInput): RoleExperience {
        val now = input.clock()
        return RoleExperience(
            id = RoleExperience.newId(input.clock),
            roleId = input.roleId,
            scenario = firstMeaningfulLine(input.goal)
                .take(RoleExperience.SCENARIO_MAX_CHARS),
            scenarioTags = scenarioTagsOf(input.goal),
            toolSequence = compressToolSequence(input.orderedTools),
            toolProfile = profileOf(input.toolCounts),
            outcome = input.outcome,
            iterations = input.iterations,
            toolCalls = input.toolCallCount,
            toolFailures = input.toolFailures,
            durationMs = input.durationMs,
            filesTouched = input.filesTouchedCount,
            notes = notesFor(input),
            workspaceName = input.workspaceName,
            createdAt = now,
            hitCount = 1,
            lastUsedAt = 0
        )
    }

    // ═══════════════════ 注意点规则引擎 ═══════════════════

    /**
     * 规则表执行 → 「下次注意」文本（拼接截断 ≤[RoleExperience.NOTES_MAX_CHARS]）。
     *
     * 纯函数：同输入同输出（规则序 = 输出序），不依赖时钟/模型/环境。
     */
    fun notesFor(input: ExperienceRawInput): String {
        val notes = ArrayList<String>(4)

        // R1 失败根因：错误首行 + 类别化行动建议（最高优先 —— 失败教训
        // 的复用价值远高于成功路径）。
        if (input.outcome == OUTCOME_FAILED) {
            val errLine = firstMeaningfulLine(input.errorMessage.orEmpty())
            if (errLine.isNotBlank()) {
                notes += "上次失败：${errLine.take(ERROR_LINE_MAX)}；${adviceForError(errLine)}"
            } else {
                notes += "上次失败（无错误详情）：下次先小步验证再推进"
            }
        }

        // R2 工具链健康：高失败率是「参数/路径没核对就批量跑」的信号。
        if (input.toolCallCount >= 3 &&
            input.toolFailures.toFloat() / input.toolCallCount >= TOOL_FAILURE_RATE_THRESHOLD
        ) {
            val pct = (input.toolFailures * 100 / input.toolCallCount)
            notes += "工具失败率 ${pct}%（${input.toolFailures}/${input.toolCallCount}）—— 下次先核对参数与路径再批量执行"
        }

        // R3 已验证路径：一次通过的成功经验是强复用信号。
        if (input.outcome == OUTCOME_COMPLETED && input.toolFailures == 0 && input.toolCallCount > 0) {
            notes += "此路径一次通过 —— 工具序列可直接复用"
        }

        // R4 高频工具：点名主力（下次同类任务优先选）。
        val top = input.toolCounts.entries.sortedByDescending { it.value }
        if (top.size >= 1 && input.toolCallCount >= 4) {
            val topShare = top.take(2).sumOf { it.value } * 100 / input.toolCallCount
            if (topShare >= 60) {
                val names = top.take(2).joinToString(" + ") { "${it.key}(${it.value})" }
                notes += "主力工具 $names —— 同类任务优先用"
            }
        }

        // R5 长任务拆步。
        if (input.iterations >= LONG_TASK_ITERATIONS) {
            notes += "上次 ${input.iterations} 轮迭代 —— 复杂任务先列 TODO 拆步，避免中途迷失"
        }

        // R6 改动面广。
        if (input.filesTouchedCount >= BROAD_FILES) {
            notes += "改动面广（${input.filesTouchedCount} 个文件）—— 下次先确认影响范围与回归面"
        }

        // R7 短路完成：轻量任务的「不用想太多」信号。
        if (input.outcome == OUTCOME_COMPLETED &&
            input.iterations in 1..2 && input.toolCallCount in 1..3
        ) {
            notes += "轻量任务一次过 —— 同类直接做，无需深潜"
        }

        if (notes.isEmpty()) return ""
        return notes.joinToString("；").take(RoleExperience.NOTES_MAX_CHARS)
    }

    /**
     * 错误文本 → 类别化行动建议（子串关键词 → 固定话术，代码判定）。
     *
     * 类别表覆盖本仓高频错误族：编译/依赖、权限、网络、超时、未找到、
     * JSON 解析；未命中 → 通用建议（诚实：不硬猜）。
     */
    private fun adviceForError(errorLine: String): String {
        val text = errorLine.lowercase()
        return when {
            text.contains("compile") || text.contains("编译") ||
                text.contains("gradle") || text.contains("build") ||
                text.contains("unresolved") || text.contains("依赖") ->
                "下次先小步编译验证（改动 ≤ 3 文件即 build 一次）"

            text.contains("permission") || text.contains("权限") ||
                text.contains("denied") || text.contains("forbidden") ||
                text.contains("401") || text.contains("403") ->
                "下次先确认令牌/权限链路（连接态 → 权限授予 → 再执行）"

            text.contains("timeout") || text.contains("超时") ||
                text.contains("timed out") ->
                "长操作拆段执行，中段落盘进度（可续跑）"

            text.contains("network") || text.contains("网络") ||
                text.contains("connect") || text.contains("unreachable") ||
                text.contains("dns") ->
                "网络依赖先探测连通再进主流程，失败走重试/离线路径"

            text.contains("not found") || text.contains("未找到") ||
                text.contains("no such") || text.contains("doesn't exist") ->
                "下次先列目录确认路径再读写（大小写/相对根都要核）"

            text.contains("json") || text.contains("parse") ||
                text.contains("解析") ->
                "外部输入先 schema 校验/净化再消费，坏数据隔离不连坐"

            else -> "先复现最小用例定位根因，再动主流程"
        }
    }

    // ═══════════════════ 私有工具 ═══════════════════

    /** 频次表裁剪：按次数降序取前 [RoleExperience.TOOL_PROFILE_MAX] 键。 */
    internal fun profileOf(toolCounts: Map<String, Int>): Map<String, Int> =
        toolCounts.entries
            .sortedByDescending { it.value }
            .take(RoleExperience.TOOL_PROFILE_MAX)
            .associate { it.key to it.value }

    /** 首个有信息的行（跳过空白与纯标点；全空回退空串）。 */
    private fun firstMeaningfulLine(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it.any { ch -> ch.isLetterOrDigit() } }
            .orEmpty()
}

/** 经验卡结果口径（与 LongTaskStatus 的字符串形态对齐，落盘稳定）。 */
internal const val OUTCOME_COMPLETED = "COMPLETED"
internal const val OUTCOME_FAILED = "FAILED"
internal const val OUTCOME_ABORTED = "ABORTED"
