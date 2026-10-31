package com.apex.agent.notify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程前台/后台状态跟踪器（任务完成通知的静音判定）。
 *
 * ## 为什么需要它
 *
 * [com.apex.agent.service.ApexCoreService] 是常驻前台服务，但那是**服务**的前台
 * 身份——用户可能早已离开 Activity（Home 键/切应用/锁屏）。任务完成通知的语义
 * 是"你切走了，但我帮你盯着的事办完了"：**用户正盯着聊天页时弹通知是噪音**
 * （回复已经在屏幕上流式渲染了），只有 App 退到后台才值得打扰。
 *
 * `ProcessLifecycleOwner`（androidx.lifecycle-process）能做同样的事，但为单一
 * 布尔值引入整包依赖不划算——本类用裸 [Application.ActivityLifecycleCallbacks]
 * 以 started/stopped 计数器实现，零新依赖（仓库已启用 dependencyLocking，
 * 加依赖需动锁文件，能免则免）。
 *
 * ## 语义
 *
 * - `startedActivities > 0` → 前台（至少一个 Activity 处于 STARTED+）；
 * - Activity 旋转/重建：onStop 旧实例与 onStart 新实例的顺序在 API 29+ 由系统
 *   保证（旧先停），低版本可能瞬时归零再回一——**瞬时抖动窗口 <16ms**，对
 *   "任务完成时是否弹通知"这一一次性判定无实际影响（判定发生在毫秒级之后的
 *   回调里，届时计数已稳定）；
 * - 多 Activity 叠栈（目前单 Activity 架构，防御性设计）：计数器天然正确。
 *
 * ## 线程模型
 *
 * 回调在主线程；[isForeground] 用 [AtomicInteger] 快照读取，任意线程安全
 * （通知发射点在协程 Default/IO 调度器上，不保证主线程）。
 *
 * ## 注册时机
 *
 * [com.apex.agent.ApexApp].onCreate 早期注册一次（进程级，随进程消亡）。
 */
@Singleton
class ForegroundTracker @Inject constructor() {

    private val startedActivities = AtomicInteger(0)

    /** 进程内任意 Activity 处于 STARTED 及以上时为 true。 */
    val isForeground: Boolean
        get() = startedActivities.get() > 0

    /** 供单元测试与诊断中心展示。 */
    fun startedCount(): Int = startedActivities.get()

    /**
     * 注册到 Application（幂等：进程生命周期内只应调用一次，重复调用由
     * [registered] 守卫吞掉而不是重复注册造成计数翻倍）。
     */
    fun register(app: Application) {
        if (!registered.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                // 防御性下限：理论上 onStop 必然配对 onStart，异常 ROM 的漏回调
                // 不应让计数跌成负数永久卡后台。
                startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private companion object {
        val registered = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
