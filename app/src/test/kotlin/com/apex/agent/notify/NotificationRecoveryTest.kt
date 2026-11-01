package com.apex.agent.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #237 回归：后台任务完成、通知本该送达却发不出去时，必须留下一次恢复提示。
 *
 * 旧行为：`notifyTaskDone` 在 `!canPost()` 时静默 return，调用方没有任何
 * 投递状态 —— 用户切回来只看到任务早就完成，直觉是「通知坏了」。
 */
class NotificationRecoveryTest {

    private fun facts(
        enabled: Boolean = true,
        foreground: Boolean = false,
        canPost: Boolean = true,
        aborted: Boolean = false
    ) = NotificationRecovery.Facts(enabled, foreground, canPost, aborted)

    @Test
    fun `后台完成但通知被系统禁用时提示`() {
        assertTrue(NotificationRecovery.shouldPrompt(facts(canPost = false)))
    }

    @Test
    fun `通知可发时不提示`() {
        assertFalse(NotificationRecovery.shouldPrompt(facts(canPost = true)))
    }

    @Test
    fun `用户自己关了通知开关时不提示`() {
        // 主动关掉开关不是故障，反复劝开是骚扰。
        assertFalse(NotificationRecovery.shouldPrompt(facts(enabled = false, canPost = false)))
    }

    @Test
    fun `前台完成不提示`() {
        // 前台不发通知是设计（结果就在屏幕上），不是投递失败。
        assertFalse(NotificationRecovery.shouldPrompt(facts(foreground = true, canPost = false)))
    }

    @Test
    fun `用户主动中止的任务不提示`() {
        assertFalse(NotificationRecovery.shouldPrompt(facts(canPost = false, aborted = true)))
    }

    @Test
    fun `只有通知本该送达却失败的组合才提示`() {
        // 穷举四因子 16 组合，确认提示集恰好是 {enabled, 后台, 不可发, 未中止}。
        var prompted = 0
        for (e in listOf(true, false)) {
            for (f in listOf(true, false)) {
                for (c in listOf(true, false)) {
                    for (a in listOf(true, false)) {
                        val expected = e && !f && !c && !a
                        if (expected) prompted++
                        assertTrue(
                            "组合 enabled=$e foreground=$f canPost=$c aborted=$a",
                            NotificationRecovery.shouldPrompt(facts(e, f, c, a)) == expected
                        )
                    }
                }
            }
        }
        assertTrue(prompted == 1)
    }
}
