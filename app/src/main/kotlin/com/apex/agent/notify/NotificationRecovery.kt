package com.apex.agent.notify

/**
 * 「后台任务完成但通知发不出去」的恢复提示判定（#237）。
 *
 * 背景：Coding 任务在后台跑完时走 [ApexNotifications.notifyTaskDone]，
 * 而该函数在 `!canPost()` 时**静默 return** —— 用户切回来只看到任务早已完成，
 * 却从未被提醒，直觉是「通知坏了」。旧 Agent 聊天页曾有一次性引导
 * （PR #332），但该页随后被移除（PR #600），Coding 链一直没有迁移过来。
 *
 * 这里只做**纯判定**，不碰 Android API，便于在 JVM 单测里锁定状态矩阵：
 * 是否需要提示 = 用户确实期待通知 ∧ 系统确实发不出去 ∧ 本条任务不是用户自己
 * 按停的。用户主动关掉开关时不该被反复劝开。
 */
internal object NotificationRecovery {

    /** 一次任务结束时的通知相关事实（由调用方从真实状态构造）。 */
    data class Facts(
        /** 设置 → 通知「任务完成通知」是否开启。 */
        val taskCompletionNotifyEnabled: Boolean,
        /** App 当前是否在前台（前台不发通知，也不算失败）。 */
        val isForeground: Boolean,
        /** 系统层面是否允许本应用发通知（授权 + 总开关 + 渠道）。 */
        val canPost: Boolean,
        /** 本轮任务是否用户主动中止。 */
        val aborted: Boolean
    )

    /**
     * 是否应当给用户一条「通知已关闭，任务完成将无提醒 → 去开启」的提示。
     *
     * 只在「用户开了开关、App 在后台、系统不让发、任务不是被中止」时为真 ——
     * 这正是通知**本该送达却丢失**的那一格。
     */
    fun shouldPrompt(facts: Facts): Boolean =
        facts.taskCompletionNotifyEnabled &&
            !facts.isForeground &&
            !facts.canPost &&
            !facts.aborted
}
