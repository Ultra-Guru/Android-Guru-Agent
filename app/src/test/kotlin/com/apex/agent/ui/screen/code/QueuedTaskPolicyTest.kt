package com.apex.agent.ui.screen.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [QueuedTaskPolicy] 单测：任务排队的收尾裁决状态机。
 *
 * 语义（用户需求原话）：
 * - 等待发送 = 任务完成后**自动发送**；
 * - 上一任务失败 → 排队任务显示**等待排队**，需用户点击「开始排队」。
 */
class QueuedTaskPolicyTest {

    private val task = QueuedTask(text = "第二个任务", commands = emptyList())

    @Test
    fun `clean completion auto dispatches queued task`() {
        assertEquals(
            QueuedTaskPolicy.Outcome.AUTO_DISPATCH,
            QueuedTaskPolicy.resolveAfterRun(completedCleanly = true, queued = task)
        )
    }

    @Test
    fun `failed run requires user confirm`() {
        assertEquals(
            QueuedTaskPolicy.Outcome.NEEDS_CONFIRM,
            QueuedTaskPolicy.resolveAfterRun(completedCleanly = false, queued = task)
        )
    }

    @Test
    fun `aborted run also requires user confirm`() {
        // 中止与失败共用 completedCleanly=false 口径（VM 侧 taskStatus
        // 只在 COMPLETED 时传 true）
        assertEquals(
            QueuedTaskPolicy.Outcome.NEEDS_CONFIRM,
            QueuedTaskPolicy.resolveAfterRun(completedCleanly = false, queued = task)
        )
    }

    @Test
    fun `no queued task is none`() {
        assertEquals(
            QueuedTaskPolicy.Outcome.NONE,
            QueuedTaskPolicy.resolveAfterRun(completedCleanly = true, queued = null)
        )
        assertEquals(
            QueuedTaskPolicy.Outcome.NONE,
            QueuedTaskPolicy.resolveAfterRun(completedCleanly = false, queued = null)
        )
    }

    @Test
    fun `needs confirm outcome flips queued state and keeps payload`() {
        val next = QueuedTaskPolicy.nextQueuedState(
            QueuedTaskPolicy.Outcome.NEEDS_CONFIRM, task
        )
        assertEquals(QueuedTaskState.NEEDS_CONFIRM, next!!.state)
        assertEquals("排队载荷（文本/chip）转态不丢", "第二个任务", next.text)
    }

    @Test
    fun `auto and none outcomes leave state untouched for vm`() {
        assertNull(
            QueuedTaskPolicy.nextQueuedState(QueuedTaskPolicy.Outcome.AUTO_DISPATCH, task)
        )
        assertNull(
            QueuedTaskPolicy.nextQueuedState(QueuedTaskPolicy.Outcome.NONE, task)
        )
    }

    @Test
    fun `queued task default state is waiting auto`() {
        assertEquals(QueuedTaskState.WAITING_AUTO, task.state)
    }
}
