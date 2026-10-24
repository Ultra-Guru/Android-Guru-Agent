package com.apex.agent.di

import com.apex.agent.core.engine.BypassOutcome
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.memkit.core.MemKit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

/**
 * 记忆观察者适配器 —— 库 [MemKit] → 引擎 [ExecutionMemoryObserver] 契约。
 *
 * 引擎侧六方法契约保持零改动；本适配器做两件事：
 *
 * **1. 会话按协程 Job 关联（并发根治）。**
 * 引擎在 execute() 协程内串行调用 onTaskStart / onActionExecuted /
 * onTaskFinish；Agent 与 Code 两个引擎共享本 @Singleton 观察者时，
 * 用「onTaskStart 时刻的协程 Job → 会话键」注册表 + 调用时刻沿 Job
 * 祖先链回溯查归属 —— 多任务并发的动作归因不再互相践踏（旧
 * CsMemSessionManager 单份可变会话状态的互踩形态从结构上消灭）。
 * 引擎契约本身不带任务 id，这是无契约变更下的最强归因；极端的非
 * execute() 协程调用会退化为「无会话即忽略」，安全不崩。
 *
 * **2. appPackage 断链根治（P0-1）。**
 * 旧链路引擎传 null → 宏存 null → 匹配查前台包 → 永远失配。新链路：
 * startSession 仍接收引擎参数（保持契约），但**库在首帧捕获时用捕获
 * 端口解析的前台包覆盖** —— Episode / 宏 / 匹配全程单源贯穿。
 *
 * tryBypass / recallChatMemory / onConversationTurn 直通库门面；
 * #218 隐私门控（chatMemoryCapture）语义原样保留。
 */
@Singleton
class MemoryObserverAdapter @Inject constructor(
    private val memKit: MemKit,
    private val settingsRepository: SettingsRepository,
) : ExecutionMemoryObserver {

    /** Job → 会话键注册表（onTaskStart 注册，onTaskFinish 摘除）。 */
    private val sessionJobs = ConcurrentHashMap<Job, String>()

    override suspend fun onTaskStart(goal: String, appPackage: String?) {
        val job = coroutineContext[Job] ?: return
        runCatching {
            val key = jobKey(job)
            memKit.sessions.begin(key, goal, appPackage)
            sessionJobs[job] = key
        }.onFailure {
            AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "记忆会话建档失败(忽略)：${it.message}")
        }
    }

    override suspend fun onActionExecuted(actionDescription: String, success: Boolean) {
        val key = resolveSessionKey() ?: return
        runCatching { memKit.sessions.recordAction(key, actionDescription, success) }
    }

    override suspend fun onTaskFinish(success: Boolean) {
        val job = coroutineContext[Job] ?: return
        val key = resolveSessionKey() ?: return
        runCatching {
            memKit.sessions.end(key, success)
            sessionJobs.remove(job)
        }.onFailure {
            AppLogger.instance.warn(LogCategory.CS_MEM, TAG, "记忆会话收尾失败(忽略)：${it.message}")
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

    /** #218：设置里关掉记忆摄取时同步静默召回（已有记忆不再注入）。 */
    override suspend fun recallChatMemory(userText: String): String? {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return null
        return runCatching { memKit.chat.recall(userText) }.getOrNull()
    }

    /** #218：设置里关掉记忆摄取时直接返回（新对话不再落盘）。 */
    override suspend fun onConversationTurn(userText: String, assistantText: String) {
        if (!settingsRepository.agentSettings.value.chatMemoryCapture) return
        runCatching { memKit.chat.onTurn(userText, assistantText) }
    }

    /** 沿当前协程 Job 祖先链回溯注册表，找到所属任务的会话键。 */
    private suspend fun resolveSessionKey(): String? {
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
