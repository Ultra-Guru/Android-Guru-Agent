package com.apex.agent.core.code.experience

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * # 角色经验追踪器 —— AgentEvent 流到经验卡的聚合器
 *
 * 与 [com.apex.agent.core.code.longtask.LongTaskTracker] 同一条事件流、
 * 同一个单写者线程模型（VM 主线程串行驱动 beginRun / onEvent / endRun），
 * 但聚合目标不同：
 *
 * | 维度 | LongTaskTracker | 本类 |
 * |------|-----------------|------|
 * | 入库门槛 | 长任务阈值（短任务丢弃） | **零门槛**：≥1 工具调用或 ≥1 迭代即沉淀（经验的单位是「复用」不是「规模」） |
 * | 工具形态 | 频次表 toolsUsed | **真序列** orderedTools（相邻折叠后落卡） |
 * | 产出 | LongTaskRecord（审计画像） | RoleExperience（下次注意 + 强化信号） |
 * | 落盘 | 自持 persistScope | 自持 persistScope（fire-and-forget，同款纪律） |
 *
 * ## 什么不沉淀（诚实门槛）
 *
 * - 纯聊天轮（零工具、零迭代）：没有可复用的**执行**证据 —— 沉淀只会
 *   把经验卡稀释成对话摘要；
 * - goal 空白：场景维度无根（UI 正常路径不会发生，防御口径）。
 *
 * ## beginRun 的防御（对齐 LongTaskTracker 先例）
 *
 * 上一轮未收尾 → 自动按 ABORTED 收尾沉淀（「用户直接发新任务」是最常见
 * 的未收尾路径，上一轮的半截经验同样有复用价值 —— 尤其是失败现场）。
 */
class RoleExperienceTracker(
    private val store: RoleExperienceStore,
    private val persistScope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    // ═══ 当前 run 聚合态（VM 主线程串行访问，单写者无竞争）═══

    private var active = false
    private var roleId = ""
    private var goal = ""
    private var workspaceName = ""
    private var startedAt = 0L

    private var iterationCount = 0
    private var toolCallCount = 0
    private var toolFailures = 0

    /** 工具调用真序列（时间序；上限硬裁剪防逃逸内存）。 */
    private val orderedTools = ArrayList<String>(TOOL_SEQUENCE_HARD_CAP)

    /** 工具 × 次数（频次视角）。 */
    private val toolCounts = LinkedHashMap<String, Int>()

    /** 成功触碰的文件数（与 LongTaskTracker 同款提取口径）。 */
    private var filesTouchedCount = 0

    /** 解析 ToolCallComplete.arguments 的只读 Json。 */
    private val argsJson = Json

    /**
     * 开始追踪一次运行。
     *
     * @param goal 本次任务目标（用户原始输入；沉淀时截断）。
     * @param roleId 归属角色（经验卡按角色分卷 —— 「角色的经验」）。
     * @param workspaceName 工作区名（经验的地理坐标）。
     */
    fun beginRun(goal: String, roleId: String, workspaceName: String) {
        if (active) {
            finalize(OUTCOME_ABORTED, errorMessage = null)
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "上一轮经验未收尾，已自动按 ABORTED 沉淀"
            )
        }
        active = true
        this.roleId = roleId
        this.goal = goal
        this.workspaceName = workspaceName
        startedAt = clock()

        iterationCount = 0
        toolCallCount = 0
        toolFailures = 0
        orderedTools.clear()
        toolCounts.clear()
        filesTouchedCount = 0
    }

    /**
     * 逐事件聚合（VM collect 链上调用；纯内存 O(1)）。
     * 无活跃 run 时静默忽略（收尾后的尾部事件 / 晚到事件）。
     */
    fun onEvent(event: AgentEvent) {
        if (!active) return
        when (event) {
            is AgentEvent.IterationStart -> iterationCount++
            is AgentEvent.ToolCallStart -> {
                toolCallCount++
                if (event.toolName.isNotBlank() && orderedTools.size < TOOL_SEQUENCE_HARD_CAP) {
                    orderedTools += event.toolName
                }
                toolCounts.merge(event.toolName, 1, Int::plus)
            }
            is AgentEvent.ToolCallComplete -> {
                if (!event.success) {
                    toolFailures++
                    return
                }
                if (event.toolName in FILE_TOUCH_TOOLS) {
                    if (extractPath(event.arguments) != null) filesTouchedCount++
                }
            }
            else -> Unit
            // 思考流/计划/进度/压缩等事件对经验聚合无贡献，刻意忽略。
        }
    }

    /**
     * 收尾本次运行：够格（≥1 工具或 ≥1 迭代且 goal 非空）→ 确定性提炼 +
     * fire-and-forget 落卷；不够格 → null 静默丢弃。
     *
     * @param status VM 裁决的终态（Complete→COMPLETED / Error→FAILED /
     *   中止→ABORTED）。
     * @param errorMessage FAILED 时的净化错误文本。
     * @return 已组装的经验卡（不等落盘完成；测试可注入同 scope 等待）。
     */
    fun endRun(status: String, errorMessage: String? = null): RoleExperience? {
        if (!active) return null
        return finalize(normalizeOutcome(status), errorMessage)
    }

    // ═══════════════════════ 内部机制 ═══════════════════════

    private fun finalize(outcome: String, errorMessage: String?): RoleExperience? {
        active = false
        val meaningful = goal.isNotBlank() && (toolCallCount > 0 || iterationCount > 0)
        if (!meaningful) {
            // 诚实门槛：纯聊天轮不沉淀（类 KDoc「什么不沉淀」）。
            return null
        }
        val input = ExperienceRawInput(
            roleId = roleId,
            goal = goal,
            workspaceName = workspaceName,
            outcome = outcome,
            iterations = iterationCount,
            toolCallCount = toolCallCount,
            toolFailures = toolFailures,
            orderedTools = orderedTools.toList(),
            toolCounts = toolCounts.toMap(),
            filesTouchedCount = filesTouchedCount,
            durationMs = (clock() - startedAt).coerceAtLeast(0),
            errorMessage = errorMessage,
            clock = clock
        )
        val experience = RoleExperienceExtractor.extract(input)
        // fire-and-forget 落卷（Supervisor scope：单卷失败不殊及后续）。
        persistScope.launch {
            runCatching { store.append(experience) }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "经验卡落卷失败（不影响主流程）：${it.message}"
                    )
                }
        }
        return experience
    }

    /** LongTaskStatus.name / 任意大小写 → 稳定落盘口径。 */
    private fun normalizeOutcome(status: String): String = when (status.trim().uppercase()) {
        "COMPLETED" -> OUTCOME_COMPLETED
        "FAILED" -> OUTCOME_FAILED
        else -> OUTCOME_ABORTED
    }

    /**
     * 从工具 arguments（JSON 字符串）防御式提取顶层字符串字段 `path`
     * （与 LongTaskTracker.extractPath 同款口径：解析失败/非串/缺字段 → null）。
     */
    private fun extractPath(arguments: String): String? = runCatching {
        val element = argsJson.parseToJsonElement(arguments)
        (element.jsonObject["path"] as? JsonPrimitive)?.content
    }.getOrNull()

    private companion object {
        const val TAG = "RoleExperienceTracker"

        /** 文件触碰工具白名单（与 LongTaskTracker 同源口径）。 */
        val FILE_TOUCH_TOOLS = setOf("CodeEdit", "CodeWrite")

        /** 内存序列硬上限（防失控 run 撑爆内存；落卡另有展示上限）。 */
        const val TOOL_SEQUENCE_HARD_CAP = 400
    }
}
