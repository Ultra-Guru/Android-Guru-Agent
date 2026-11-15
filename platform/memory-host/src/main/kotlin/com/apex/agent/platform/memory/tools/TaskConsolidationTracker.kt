package com.apex.agent.platform.memory.tools

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

/**
 * 任务级「模型已整理」标记 —— 引擎兜底与模型自觉的去重闸。
 *
 * 记忆系统全景重构方案 v2 P0-2 写回闭环：任务终局有两条沉淀路径 ——
 * ① 模型主动调用 MemoryConsolidateExperience（提示词已强制要求）；
 * ② 引擎兜底（MemoryObserverAdapter.onTaskFinish 直接 consolidate 摘要，
 *    模型只补教训/偏好/项目约定）。无去重闸时 ①② 同时发生会写两条
 * 近重复里程碑事实。
 *
 * 归因方式与 MemoryObserverAdapter 的 Job→会话注册表同族：观察者
 * onTaskStart 时注册任务键；工具在引擎 execute() 协程内执行，沿
 * 协程 Job 祖先链回溯到注册键并打标；onTaskFinish 检标并摘除。kit
 * 线的库内执行协程若不在同一 Job 子树（极端情形），回溯失败 → 标记
 * 不上 → 兜底照常（宁可近重复也不丢时间线锚点）。
 */
@Singleton
class TaskConsolidationTracker @Inject constructor() {

    /** 活跃任务键（onTaskStart 注册，onTaskFinish 摘除）。 */
    private val activeTasks = ConcurrentHashMap.newKeySet<String>()

    /** 已被模型整理过的任务键（工具打标）。 */
    private val marked = ConcurrentHashMap.newKeySet<String>()

    /** 观察者 onTaskStart：注册任务键（与 MemoryObserverAdapter.jobKey 同口径派生）。 */
    fun registerTask(job: Job) {
        activeTasks.add(jobKey(job))
    }

    /** 观察者 onTaskFinish：检标 + 摘除（返回 true = 模型已整理，兜底跳过）。 */
    fun consumeMark(job: Job): Boolean {
        val key = jobKey(job)
        val wasMarked = marked.remove(key)
        activeTasks.remove(key)
        return wasMarked
    }

    /** 工具侧：标记当前协程所属任务「模型已整理」（沿 Job 祖先链找注册键）。 */
    suspend fun markCurrentTask() {
        var job: Job? = coroutineContext[Job]
        while (job != null) {
            val key = jobKey(job)
            if (activeTasks.contains(key)) {
                marked.add(key)
                return
            }
            job = job.parent
        }
    }

    /** 会话键：Job 身份派生（与 MemoryObserverAdapter.jobKey 同口径）。 */
    internal fun jobKey(job: Job): String =
        "job_${Integer.toHexString(System.identityHashCode(job))}"
}
