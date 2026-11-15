package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * # Memory Aware Code Engine — 四线记忆统一注入层（记忆 P0-1）
 *
 * ```
 * CodeViewModel ──► MemoryAwareCodeEngine ──► DualLogicCodeEngine ──┬──► CodeAgentEngine（深潜线）
 *                                    （本层：记忆闭环）              ├──► StandardKitAdapter（标准线）
 *                                                                  ├──► LoomKitAdapter（织造线）
 *                                                                  └──► CourierKitAdapter（信使线）
 * ```
 *
 * 深潜线经内层 ApexAgentEngine 已有完整记忆接线（memoryObserver 的
 * 召回注入 / 情景建档 / 轮次沉淀 / 任务收尾）；**kit 三线（标准/织造/
 * 信使）此前零接线** —— 无「Remembered About You」、无任务终局沉淀、
 * 无工具轨迹入账。本装饰器把同款闭环补到 kit 三线，四线从此同权：
 *
 * - **入口召回**：execute() 入口经观察者召回长期记忆，经既有
 *   `updateSessionExtras` 通道注入（kit 库把 extras 渲染进系统提示词，
 *   四个 kit 零改动）；深潜线跳过（内层已有专属注入位，避免双份）。
 * - **工具轨迹**：事件流中的 ToolCallComplete → onActionExecuted
 *   （情景账本的工具轨迹，深潜线由引擎内层负责）。
 * - **终局闭环**：流收尾 → onTaskFinish + onConversationTurn（最终
 *   助手回复）；观察者侧引擎兜底沉淀摘要事实（模型只补教训/偏好）。
 *
 * 纪律：记忆子系统任何异常都不阻断编码主流程（全部 runCatching 折叠）；
 * 观察者缺席（null）时本层退化为纯透传。
 */
class MemoryAwareCodeEngine(
    /** 四线路由门面（真实执行体）。 */
    val dualLogic: DualLogicCodeEngine,
    /** 记忆观察者（缺席 = 纯透传，行为与裸 DualLogicCodeEngine 一致）。 */
    private val memoryObserver: ExecutionMemoryObserver?,
) : AgentEngine, CodeEngineFacade by dualLogic {

    /** VM 设置的用户 extras（不含记忆段；每次注入在此基础上拼接）。 */
    @Volatile
    private var userExtras: String? = null

    // ── AgentEngine：execute 拦截 + 其余透传 ═══════════════════════

    override fun execute(input: String): Flow<AgentEvent> = execute(UserInput(text = input))

    override fun execute(input: UserInput): Flow<AgentEvent> = channelFlow {
        val line = dualLogic.activeLogic()
        val observer = memoryObserver
        if (observer == null || line == StandardLogicMode.DEEP_DIVE) {
            // 深潜线：内层 ApexAgentEngine 已全程接线 memoryObserver ——
            // 本层再调一次会造成双份情景/召回，纯透传。
            dualLogic.execute(input).collect { send(it) }
            return@channelFlow
        }

        // ══ kit 三线（标准/织造/信使）：补齐记忆闭环 ══
        runCatching { observer.onTaskStart(input.text, null) }
            .onFailure { logObserverFailure("onTaskStart", it) }

        // 入口召回 → extras 通道注入（无召回时复位为纯用户 extras，
        // 上一轮注入不残留）
        val recall = runCatching { observer.recallChatMemory(input.text) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        injectRecall(recall)

        var lastAssistantText: String? = null
        var failed = false
        try {
            dualLogic.execute(input).collect { event ->
                when (event) {
                    is AgentEvent.ResponseComplete ->
                        if (event.fullText.isNotBlank()) lastAssistantText = event.fullText

                    is AgentEvent.ToolCallComplete -> runCatching {
                        // 与深潜线 EngineToolExecution 同款记法（工具名 + 截断参数）
                        observer.onActionExecuted(
                            "${event.toolName}(${event.arguments.take(120)})",
                            event.success,
                        )
                    }

                    is AgentEvent.Error -> failed = true
                    is AgentEvent.Aborted -> failed = true
                    else -> Unit
                }
                send(event)
            }
        } finally {
            // 终局闭环：任务收尾 + 轮次沉淀（观察者侧含引擎兜底摘要）。
            runCatching { observer.onTaskFinish(success = !failed) }
                .onFailure { logObserverFailure("onTaskFinish", it) }
            lastAssistantText?.let { text ->
                runCatching { observer.onConversationTurn(input.text, text) }
                    .onFailure { logObserverFailure("onConversationTurn", it) }
            }
            // extras 卫生：撤下本轮召回段（下次 execute 重新注入或复位）。
            runCatching { dualLogic.updateSessionExtras(userExtras) }
        }
    }

    // ── CodeEngineFacade：extras 拦截（用户值存底，转发透传）═══════

    override fun updateSessionExtras(extras: String?) {
        userExtras = extras?.takeIf { it.isNotBlank() }
        dualLogic.updateSessionExtras(extras)
    }

    // ── 内部 ═══════════════════════════════════════════════════════

    /**
     * 召回段拼接进 extras（kit 库渲染进系统提示词）。标题自含 —— kit
     * 线没有 EnginePrompts 的「## Remembered About You」包裹位。
     */
    private fun injectRecall(recall: String?) {
        val base = userExtras
        val merged = when {
            recall == null -> base
            base.isNullOrBlank() -> "$RECALL_HEADER\n$recall"
            else -> "$base\n\n$RECALL_HEADER\n$recall"
        }
        dualLogic.updateSessionExtras(merged)
    }

    private fun logObserverFailure(hook: String, error: Throwable) {
        AppLogger.instance.warn(
            LogCategory.CS_MEM, TAG,
            "kit 线记忆观察者 $hook 失败（忽略，不阻断编码主流程）：${error.message}",
        )
    }

    private companion object {
        const val TAG = "MemoryAwareCode"
        const val RECALL_HEADER = "## Remembered About You"
    }
}
