package com.apex.agent.terminalview

import com.apex.agent.terminalview.TerminalGestureModel.GestureEvent
import com.apex.agent.terminalview.TerminalGestureModel.TouchAction
import com.apex.agent.terminalview.TerminalGestureModel.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T92：手势交互链回归 —— 鼠标模式即时点击（vim 点击定位不等双击窗）、
 * 双击后拖动 = 扩选（Termux 手柄拖选的无手柄近似，不进滚动）。
 */
class T92GestureInteractionTest {

    private val slop = 24f
    private val longPress = 400L
    private val doubleTap = 300L

    private fun model() = TerminalGestureModel(
        tapSlopPx = slop,
        longPressTimeoutMs = longPress,
        doubleTapTimeoutMs = doubleTap,
        tapTimeoutMs = 180L,
        flingVelocityThreshold = 350f,
        rotationRejectDeg = 32f
    )

    private fun press(m: TerminalGestureModel, x: Float, y: Float, t: Long) =
        m.feed(TouchSample(x, y, t, TouchAction.DOWN))

    private fun release(m: TerminalGestureModel, x: Float, y: Float, t: Long) =
        m.feed(TouchSample(x, y, t, TouchAction.UP))

    private fun move(m: TerminalGestureModel, x: Float, y: Float, t: Long) =
        m.feed(TouchSample(x, y, t, TouchAction.MOVE))

    // ═══ 即时点击模式（鼠标报告开启）═══

    @Test
    fun `immediate tap mode dispatches tap on UP without waiting`() {
        val m = model()
        m.immediateTapEnabled = true
        press(m, 100f, 200f, 1000L)
        val up = release(m, 100f, 200f, 1020L)
        val tap = up.filterIsInstance<GestureEvent.Tap>().singleOrNull()
        assertEquals("UP 即派发 Tap（vim 点击定位零延迟）", 100f, tap?.x ?: -1f, 0.01f)
        assertNull("无需双击窗确认", m.tick(1020L + doubleTap).firstOrNull())
    }

    @Test
    fun `immediate tap mode emits two independent taps on double press`() {
        val m = model()
        m.immediateTapEnabled = true
        val first = press(m, 50f, 50f, 1000L) + release(m, 50f, 50f, 1010L)
        val second = press(m, 52f, 50f, 1200L) + release(m, 52f, 50f, 1210L)
        assertEquals("首击独立 Tap", 1, first.filterIsInstance<GestureEvent.Tap>().size)
        assertEquals("二击独立 Tap", 1, second.filterIsInstance<GestureEvent.Tap>().size)
        assertTrue(
            "不合成 DoubleTap（真鼠标语义）",
            (first + second).filterIsInstance<GestureEvent.DoubleTap>().isEmpty()
        )
    }

    @Test
    fun `immediate tap mode off still defers to double tap window`() {
        val m = model() // 默认关 —— 普通模式单击仍走确认窗
        press(m, 10f, 10f, 1000L)
        assertTrue("UP 无事件", release(m, 10f, 10f, 1010L).isEmpty())
        val confirmed = m.tick(1010L + doubleTap)
        assertEquals("窗到点补发单击", 1, confirmed.filterIsInstance<GestureEvent.Tap>().size)
    }

    // ═══ 双击后拖动 = 扩选 ═══

    @Test
    fun `double tap then drag starts selection drag instead of scroll`() {
        val m = model()
        // 第一次单击 → TAP_PENDING
        press(m, 100f, 100f, 1000L)
        release(m, 100f, 100f, 1010L)
        // 第二次按下（窗内、贴近）→ 双击候选
        press(m, 102f, 100f, 1200L)
        // 移动超 slop —— T92：废双击改拖选（不进滚动）
        val moved = move(m, 110f, 130f, 1250L)
        assertTrue(
            "拖选起手（DragStart/DragMove），无 Scroll",
            moved.any { it is GestureEvent.DragStart } && moved.any { it is GestureEvent.DragMove } &&
                moved.none { it is GestureEvent.Scroll }
        )
        val up = release(m, 115f, 160f, 1300L)
        assertEquals("抬指收束选区", 1, up.filterIsInstance<GestureEvent.DragEnd>().size)
        // 双击已被废 —— 后续 tick 不得补发单击
        assertTrue(m.tick(2000L).isEmpty())
    }

    @Test
    fun `single tap then drag still scrolls`() {
        val m = model()
        press(m, 100f, 100f, 1000L)
        // 首按移动（无双击候选）→ 滚动
        val moved = move(m, 100f, 180f, 1050L)
        assertTrue(
            "普通拖动仍是滚动",
            moved.filterIsInstance<GestureEvent.Scroll>().isNotEmpty() &&
                moved.none { it is GestureEvent.DragStart }
        )
    }
}
