package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.experience.RoleExperienceRecall
import com.apex.agent.core.code.experience.RoleExperienceStore
import com.apex.agent.core.code.experience.RoleExperienceTracker
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.screen.settings.activeCodingRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * # 角色经验卡控制器 —— CodeViewModel 的经验域薄代理
 *
 * 用户规格「每次任务结束后自动提炼经验，下次同类任务开始时检索注入」
 * 的 app 层接线（CodeRoleController 同款模式：VM 只做暴露，God-file
 * 预算纪律 —— 本文件承接全部经验域逻辑，VM 侧只有 4 个一行调用点）。
 *
 * ## 三条通道
 *
 * 1. **沉淀**（任务结束）：[endRun] → Tracker 确定性提炼 → fire-and-forget
 *    落卷（角色分卷：当前激活角色领卡）；
 * 2. **注入**（任务开始）：[enrichExtras] → 召回 top-3 相关经验 →
 *    拼进 session extras（与「小圆环」同通道、同生命周期：每轮 run 刷新）；
 * 3. **强化**（注入即回写）：被注入的经验 hitCount+1（fire-and-forget，
 *    失败不殊及发送主路径）——「被反复验证的经验更可信」的排序信号。
 *
 * ## 线程模型
 *
 * - beginRun / onEvent：VM 主线程串行（与 LongTaskTracker 同一条 collect
 *   链，单写者纪律）；
 * - enrichExtras：同步读 [RoleExperienceStore.snapshotBlocking]（惰性装载
 *   + 纯内存读，主线程安全 —— LongTaskStore.snapshotBlocking 同款契约）；
 * - bumpHits：scope.launch IO（fire-and-forget）。
 */
class RoleExperienceController(
    private val tracker: RoleExperienceTracker,
    private val store: RoleExperienceStore,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) {

    /**
     * 一轮任务开始：经验聚合归零（ roleId 取当前激活角色 —— 经验卡归属
     * 开工时的角色；角色热切换发生在 run 中途的场景极罕见，按开工时归属
     * 即可）。
     */
    fun beginRun(goal: String, workspaceName: String) {
        val roleId = settingsRepository.agentSettings.value.activeCodingRole().id
        tracker.beginRun(goal = goal, roleId = roleId, workspaceName = workspaceName)
    }

    /** 事件流喂入（VM collect 链上与 longTaskTracker.onEvent 并排）。 */
    fun onEvent(event: AgentEvent) {
        tracker.onEvent(event)
    }

    /**
     * 一轮任务收尾：状态裁决 + 确定性提炼落卷（不够格的纯聊天轮内部
     * 静默丢弃）。
     */
    fun endRun(statusName: String, errorMessage: String?) {
        tracker.endRun(statusName, errorMessage = errorMessage)
    }

    /**
     * 注入通道：base extras（小圆环 + 工具箱上下文）+ 角色经验段。
     *
     * 当前角色无相关经验（召回空）→ 原样返回 base（零行为变化）。
     */
    fun enrichExtras(base: String, goal: String): String {
        if (goal.isBlank()) return base
        val roleId = settingsRepository.agentSettings.value.activeCodingRole().id
        val book = store.snapshotBlocking(roleId)
        if (book.isEmpty()) return base
        val recalled = RoleExperienceRecall.recall(book, goal)
        val context = RoleExperienceRecall.buildContext(recalled) ?: return base
        // 强化回写：被注入的经验 hitCount+1（fire-and-forget）。
        if (recalled.isNotEmpty()) {
            scope.launch {
                runCatching { store.bumpHits(roleId, recalled.map { it.id }, System.currentTimeMillis()) }
            }
        }
        return "$base\n\n$context"
    }
}
