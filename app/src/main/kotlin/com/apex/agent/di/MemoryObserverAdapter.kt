package com.apex.agent.di

import com.apex.agent.core.engine.BypassOutcome
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.memory.recall.FourFactorRecall
import com.apex.agent.platform.memory.tools.TaskConsolidationTracker
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.memkit.core.MemKit
import com.apex.memkit.core.model.ExperienceReport
import com.apex.memkit.core.store.readGraph
import com.apex.memkit.core.store.touchFacts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 记忆观察者适配器 —— 库 [MemKit] → 引擎 [ExecutionMemoryObserver] 契约。
 *
 * 引擎侧契约保持零改动（新增默认方法 [onContextPruned] 除外）；本适配器：
 *
 * **1. 会话按协程 Job 关联（并发根治）。**
 * 引擎在 execute() 协程内串行调用 onTaskStart / onActionExecuted /
 * onTaskFinish；用「onTaskStart 时刻的协程 Job → 会话键」注册表 +
 * 调用时刻沿 Job 祖先链回溯查归属 —— 多任务并发的动作归因不再互相践踏。
 *
 * **2. 四因子召回（记忆 P0-3，四线统一内核）。**
 * [recallChatMemory] 直调宿主侧 [FourFactorRecall]（0.45 importance +
 * 0.30 recency + 0.10 frequency + 0.15 keyword、14 天半衰期、访问冷却、
 * token 硬截断）替换库侧 ChatRecall 的三因子全局扫描 + 900 字符一刀切
 * —— 深潜线（EnginePrompts 注入位）与 kit 三线（MemoryAwareCodeEngine
 * extras 通道）共用同一评分与预算口径。
 *
 * **3. 任务终局引擎兜底（记忆 P0-2 写回闭环）。**
 * [onTaskFinish] 在会话收尾后：非平凡任务（≥1 次工具操作）且模型未
 * 主动调用 MemoryConsolidateExperience（[TaskConsolidationTracker] 检标）
 * 时，直接 consolidate 摘要事实 —— 时间线锚点不再依赖模型自觉；模型
 * 只补教训/偏好/项目约定（提示词已要求）。
 *
 * **4. 压缩捕获（记忆 P0-3 会话摘要双通道 —— 压缩侧）。**
 * [onContextPruned] 把被压缩裁剪的 User/Assistant 对转进库聊天管道
 * （启发式捕获 + 蒸馏队列），后台执行不阻塞压缩主流程。
 *
 * tryBypass / onConversationTurn 直通库门面；#218 隐私门控
 * （chatMemoryCapture）语义原样保留。
 */
@Singleton
class MemoryObserverAdapter @Inject constructor(
    private val memKit: MemKit,
    private val settingsRepository: SettingsRepository,
    private val consolidationTracker: TaskConsolidationTracker,
    // 记忆写入管道宿主作用域（provideMemoryScope；压缩捕获后台化用）
    private val scope: CoroutineScope,
) : ExecutionMemoryObserver {

    /** Job → 会话元数据（goal + 工具操作数；onTaskStart 注册，onTaskFinish 摘除）。 */
    private data class SessionMeta(val key: String, val goal: String, val actionCount: AtomicInteger)

    private val sessionJobs = ConcurrentHashMap<Job, SessionMeta>()

    override suspend fun onTaskStart(goal: String, appPackage: String?) {
        val job = coroutineContext[Job] ?: return
        runCatching {
            val key = jobKey(job)
            memKit.sessions.begin(key, goal, appPackage)
            sessionJobs[job] = SessionMeta(key, goal, AtomicInteger(0))
            consolidationTracker.registerTask(job)
        }.onFailure {
            AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "记忆会话建档失败(忽略)：${it.message}")
        }
    }

    override suspend fun onActionExecuted(actionDescription: String, success: Boolean) {
        val meta = resolveSessionMeta() ?: return
        meta.actionCount.incrementAndGet()
        runCatching { memKit.sessions.recordAction(meta.key, actionDescription, success) }
    }

    override suspend fun onTaskFinish(success: Boolean) {
        val job = coroutineContext[Job] ?: return
        val meta = resolveSessionMeta() ?: return
        runCatching {
            memKit.sessions.end(meta.key, success)
        }.onFailure {
            AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "记忆会话收尾失败(忽略)：${it.message}")
        }
        sessionJobs.remove(job)
        // 记忆 P0-2：引擎兜底沉淀 —— 模型已主动整理（tracker 检标）则跳过
        fallbackConsolidation(job, meta, success)
    }

    /** 引擎兜底：非平凡任务 + 模型未整理 → 直写摘要事实（模型只补教训）。 */
    private suspend fun fallbackConsolidation(job: Job, meta: SessionMeta, success: Boolean) {
        val actions = meta.actionCount.get()
        if (actions <= 0) return  // 纯聊天轮不写里程碑（教训靠模型工具）
        if (consolidationTracker.consumeMark(job)) return  // 模型已整理
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return
        runCatching {
            memKit.consolidateExperience(
                ExperienceReport(
                    goal = meta.goal.trim().take(2000),
                    outcome = "引擎兜底记录：${actions} 次工具操作，" +
                        "${if (success) "成功" else "未成"}收尾（模型未调用经验整理工具）",
                    success = success,
                    lessons = emptyList(),
                    entities = emptyList(),
                    relations = emptyList(),
                ),
            )
        }.onFailure {
            AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "引擎兜底沉淀失败(忽略)：${it.message}")
        }
    }

    override suspend fun tryBypass(): BypassOutcome = runCatching {
        when (val result = memKit.tryBypass()) {
            is MemKit.BypassResult.Executed -> BypassOutcome.Executed(result.actionCount)
            is MemKit.BypassResult.Failed -> BypassOutcome.Failed(result.reason)
            is MemKit.BypassResult.NotMatched -> BypassOutcome.NotMatched
            is MemKit.BypassResult.NotAvailable -> BypassOutcome.NotAttempted
        }
    }.getOrElse { BypassOutcome.NotAttempted }

    /**
     * #218：设置里关掉记忆摄取时同步静默召回。
     * 记忆 P0-3：四因子召回 + 访问冷却（冷却窗口内的触达不回写，防
     * 「越召回越新鲜」正反馈）+ token 硬截断（整行装配，不切半句）。
     */
    override suspend fun recallChatMemory(userText: String): String? {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return null
        return runCatching {
            val selection = FourFactorRecall.select(
                facts = memKit.allFacts(),
                graphEntities = memKit.store.readGraph().first,
                userText = userText,
                now = System.currentTimeMillis(),
            ) ?: return null
            if (selection.touchedFactIds.isNotEmpty()) {
                memKit.store.touchFacts(selection.touchedFactIds)
                memKit.requestFlush()
            }
            selection.rendered
        }.getOrNull()
    }

    /** #218：设置里关掉记忆摄取时直接返回（新对话不再落盘）。 */
    override suspend fun onConversationTurn(userText: String, assistantText: String) {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return
        runCatching { memKit.chat.onTurn(userText, assistantText) }
    }

    /**
     * 压缩捕获（记忆 P0-3）：被裁 User/Assistant 对转进库聊天管道（后台
     * 执行 —— 压缩回调绝不阻塞引擎循环，suspend 形参即时返回）；ToolResult
     * 丢弃，单次事件上限 8 对（蒸馏队列容量 12 内），每侧文本截 1000 字符。
     */
    override suspend fun onContextPruned(pruned: List<LlmMessage>) {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return
        if (pruned.isEmpty()) return
        scope.launch {
            runCatching {
                var pendingUser: String? = null
                var pairs = 0
                for (msg in pruned) {
                    when (msg) {
                        is LlmMessage.User -> if (pendingUser == null) {
                            pendingUser = msg.content.trim().take(1000).takeIf { it.isNotEmpty() }
                        }

                        is LlmMessage.Assistant -> {
                            val user = pendingUser
                            val assistant = msg.content.trim().take(1000)
                            if (user != null && assistant.isNotEmpty() && pairs < 8) {
                                memKit.chat.onTurn(user, assistant)
                                pairs++
                            }
                            pendingUser = null
                        }

                        else -> Unit
                    }
                }
            }.onFailure {
                AppLogger.instance.warn(
                    LogCategory.CS_MEM, TAG,
                    "压缩捕获转蒸馏失败(忽略)：${it.message}",
                )
            }
        }
    }

    /** 沿当前协程 Job 祖先链回溯注册表，找到所属任务的会话元数据。 */
    private suspend fun resolveSessionMeta(): SessionMeta? {
        var job: Job? = coroutineContext[Job]
        while (job != null) {
            sessionJobs[job]?.let { return it }
            job = job.parent
        }
        return null
    }

    /** 会话键：Job 身份派生的稳定键（begin/end 同键）。 */
    private fun jobKey(job: Job): String = "job_${Integer.toHexString(System.identityHashCode(job))}"

    private companion object {
        private const val TAG = "MemoryObserver"
    }
}
