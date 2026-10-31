package com.apex.agent.terminalview

import com.apex.agent.terminalview.TerminalGestureModel.GestureEvent
import com.apex.agent.terminalview.TerminalGestureModel.TouchAction
import com.apex.agent.terminalview.TerminalGestureModel.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T88（2-a）：手势状态机单测 —— 分类正确性（tap/双击/长按/滚动/fling/捏合/
 * 第二指快击）+ slop 拒绝 + fling 速度符号（**手指位移语义**：正 = 向下）。
 */
class TerminalGestureModelTest {

    private val slop = 24f
    private val longPress = 400L
    private val doubleTap = 300L

    private fun model(
        flingThreshold: Float = 350f,
        rotationRejectDeg: Float = 32f
    ) = TerminalGestureModel(
        tapSlopPx = slop,
        longPressTimeoutMs = longPress,
        doubleTapTimeoutMs = doubleTap,
        tapTimeoutMs = 180L,
        flingVelocityThreshold = flingThreshold,
        rotationRejectDeg = rotationRejectDeg
    )

    private fun tapAt(m: TerminalGestureModel, x: Float, y: Float, t: Long): List<GestureEvent> =
        m.feed(TouchSample(x, y, t, TouchAction.DOWN)) +
            m.feed(TouchSample(x, y, t + 10, TouchAction.UP))

    private fun firstTap(events: List<GestureEvent>): GestureEvent.Tap? =
        events.filterIsInstance<GestureEvent.Tap>().firstOrNull()

    // ─── Tap / DoubleTap ───

    @Test
    fun `quick press release then confirm window emits single tap`() {
        val m = model()
        tapAt(m, 100f, 200f, t = 1000L)
        // 单击要等双击窗确认 —— tick 早于窗口无事件
        assertTrue(m.tick(1000L + 10L + 100L).isEmpty())
        val confirmed = m.tick(1000L + 10L + doubleTap)
        val tap = firstTap(confirmed)
        assertEquals(100f, tap!!.x, 0.01f)
        assertEquals(200f, tap.y, 0.01f)
    }

    @Test
    fun `two quick taps in window emit double tap not two singles`() {
        val m = model()
        val t1 = tapAt(m, 50f, 50f, 1000L)
        val t2 = tapAt(m, 52f, 50f, 1200L) // 窗内二击
        assertTrue(t1.isEmpty())
        val upEvents = t2 // 第二次 UP 立即判双击
        assertEquals(1, upEvents.filterIsInstance<GestureEvent.DoubleTap>().size)
        assertTrue(firstTap(m.tick(5000L)) == null) // 不再补单击
    }

    @Test
    fun `second press far away flushes first tap immediately`() {
        val m = model()
        val first = tapAt(m, 10f, 10f, 1000L)
        assertTrue(first.isEmpty())
        // 1 秒后远处再按 —— 超窗：首击立即成为单击
        val down = m.feed(TouchSample(500f, 500f, 2200L, TouchAction.DOWN))
        val tap = firstTap(down)
        assertEquals(10f, tap!!.x, 0.01f)
    }

    @Test
    fun `movement inside slop still taps`() {
        val m = model()
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(5f, 8f, 100L, TouchAction.MOVE)) // slop 内
        val up = m.feed(TouchSample(6f, 9f, 150L, TouchAction.UP))
        assertTrue(up.isEmpty()) // 等双击窗
        assertTrue(firstTap(m.tick(150L + doubleTap)) != null)
    }

    // ─── 滚动 / slop 拒绝 ───

    @Test
    fun `movement beyond slop becomes scroll`() {
        val m = model()
        m.feed(TouchSample(100f, 100f, 0L, TouchAction.DOWN))
        val events = m.feed(TouchSample(100f, 100f + slop + 10f, 50L, TouchAction.MOVE))
        val scrolls = events.filterIsInstance<GestureEvent.Scroll>()
        assertEquals(1, scrolls.size)
        // 首段 Scroll 携带锚点以来位移（slop+10）
        assertEquals(slop + 10f, scrolls[0].deltaYPx, 0.01f)
    }

    @Test
    fun `consecutive moves emit incremental scroll deltas`() {
        val m = model()
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(0f, 40f, 50L, TouchAction.MOVE)) // 超 slop → 起滚
        val e2 = m.feed(TouchSample(0f, 55f, 60L, TouchAction.MOVE))
        val e3 = m.feed(TouchSample(0f, 70f, 70L, TouchAction.MOVE))
        assertEquals(15f, e2.filterIsInstance<GestureEvent.Scroll>()[0].deltaYPx, 0.01f)
        assertEquals(15f, e3.filterIsInstance<GestureEvent.Scroll>()[0].deltaYPx, 0.01f)
    }

    // ─── 长按 / 拖选 ───

    @Test
    fun `long press fires from tick without movement`() {
        val m = model()
        m.feed(TouchSample(120f, 80f, 0L, TouchAction.DOWN))
        val fired = m.tick(longPress)
        assertEquals(1, fired.filterIsInstance<GestureEvent.LongPress>().size)
        assertEquals(120f, fired.filterIsInstance<GestureEvent.LongPress>()[0].x, 0.01f)
    }

    @Test
    fun `drag after long press emits drag start and moves`() {
        val m = model()
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.tick(longPress)
        val moves = m.feed(TouchSample(30f, 40f, longPress + 50L, TouchAction.MOVE))
        assertEquals(1, moves.filterIsInstance<GestureEvent.DragStart>().size)
        assertEquals(1, moves.filterIsInstance<GestureEvent.DragMove>().size)
        val end = m.feed(TouchSample(60f, 90f, longPress + 120L, TouchAction.UP))
        assertEquals(1, end.filterIsInstance<GestureEvent.DragEnd>().size)
    }

    @Test
    fun `movement before long press timeout cancels long press`() {
        val m = model()
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(0f, 60f, 100L, TouchAction.MOVE)) // 起滚
        assertTrue(m.tick(longPress).isEmpty()) // 长按已不可能
        val up = m.feed(TouchSample(0f, 70f, 150L, TouchAction.UP))
        assertTrue(up.isEmpty()) // 低速无 fling
    }

    // ─── Fling ───

    @Test
    fun `fast upward swipe emits fling with negative velocity`() {
        val m = model(flingThreshold = 100f)
        m.feed(TouchSample(0f, 400f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(0f, 380f, 20L, TouchAction.MOVE))
        m.feed(TouchSample(0f, 340f, 40L, TouchAction.MOVE))
        m.feed(TouchSample(0f, 240f, 60L, TouchAction.MOVE))
        val up = m.feed(TouchSample(0f, 140f, 80L, TouchAction.UP))
        val fling = up.filterIsInstance<GestureEvent.Fling>().single()
        // 手指向上（y 减小）→ 速度为负（View 取反喂滚动模型 → 朝最新内容）
        assertTrue(fling.velocityYPx < 0f)
        assertTrue(Math.abs(fling.velocityYPx) > 1000f) // ~ -3000 px/s
    }

    @Test
    fun `slow release does not fling`() {
        val m = model(flingThreshold = 350f)
        m.feed(TouchSample(0f, 100f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(0f, 140f, 50L, TouchAction.MOVE))
        m.feed(TouchSample(0f, 180f, 300L, TouchAction.MOVE))
        val up = m.feed(TouchSample(0f, 200f, 600L, TouchAction.UP))
        assertTrue(up.filterIsInstance<GestureEvent.Fling>().isEmpty())
    }

    @Test
    fun `velocity estimator weights recent samples over stale ones`() {
        val m = model()
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(0f, 400f, 100L, TouchAction.MOVE)) // 老样本：+4000 px/s
        m.feed(TouchSample(0f, 420f, 400L, TouchAction.MOVE))
        m.feed(TouchSample(0f, 430f, 500L, TouchAction.MOVE)) // 新样本：~+100 px/s
        val v = m.estimateVelocityY(500L)
        // 加权后应远小于老样本速度且为正
        assertTrue(v in 60f..1000f)
    }

    // ─── 捏合 ───

    @Test
    fun `two finger spread emits pinch scale`() {
        val m = model()
        m.feed(TouchSample(100f, 100f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(200f, 100f, 20L, TouchAction.POINTER_DOWN, 2, 7))
        val pinch = m.feed(TouchSample(400f, 100f, 60L, TouchAction.MOVE, 2))
        val events = pinch.filterIsInstance<GestureEvent.Pinch>()
        assertEquals(1, events.size)
        // 距离 300/100 = 3.0
        assertEquals(3.0f, events[0].scale, 0.05f)
        assertEquals(250f, events[0].focusX, 20f) // 主指锚(100)与第二指(400)中点
    }

    @Test
    fun `pinch rotation beyond threshold is rejected`() {
        val m = model(rotationRejectDeg = 32f)
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(100f, 0f, 20L, TouchAction.POINTER_DOWN, 2, 7))
        // 第二指转到主指另一侧（180° 旋转，距离还扩大 3 倍 —— 非旋转会发 Pinch）
        val rotated = m.feed(TouchSample(-300f, 0f, 80L, TouchAction.MOVE, 2))
        assertTrue(rotated.filterIsInstance<GestureEvent.Pinch>().isEmpty())
    }

    // ─── 第二指快击（右键）───

    @Test
    fun `second finger quick tap while primary held emits TapSecondFinger`() {
        val m = model()
        m.feed(TouchSample(50f, 50f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(150f, 60f, 100L, TouchAction.POINTER_DOWN, 2, 7))
        val lift = m.feed(TouchSample(151f, 60f, 200L, TouchAction.POINTER_UP, 2, 7))
        assertEquals(1, lift.filterIsInstance<GestureEvent.TapSecondFinger>().size)
    }

    @Test
    fun `second finger drag does not emit tap`() {
        val m = model()
        m.feed(TouchSample(50f, 50f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(150f, 60f, 100L, TouchAction.POINTER_DOWN, 2, 7))
        m.feed(TouchSample(400f, 60f, 180L, TouchAction.MOVE, 2))
        val lift = m.feed(TouchSample(401f, 60f, 250L, TouchAction.POINTER_UP, 2, 7))
        assertTrue(lift.filterIsInstance<GestureEvent.TapSecondFinger>().isEmpty())
    }

    // ─── 取消 ───

    @Test
    fun `cancel resets mid-gesture state`() {
        val m = model()
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        m.feed(TouchSample(0f, 100f, 50L, TouchAction.MOVE))
        m.feed(TouchSample(0f, 0f, 60L, TouchAction.CANCEL))
        // 取消后无 fling、无拖选残留
        val after = m.feed(TouchSample(0f, 0f, 70L, TouchAction.DOWN)) +
            m.feed(TouchSample(0f, 0f, 80L, TouchAction.UP))
        assertTrue(after.filterIsInstance<GestureEvent.Fling>().isEmpty())
        assertTrue(m.tick(80L + doubleTap).isNotEmpty()) // 新手势照常
    }

    @Test
    fun `awaiting flags expose pending timers`() {
        val m = model()
        assertTrue(!m.awaitingLongPress)
        m.feed(TouchSample(0f, 0f, 0L, TouchAction.DOWN))
        assertTrue(m.awaitingLongPress)
        m.tick(longPress)
        assertTrue(!m.awaitingLongPress)
        assertTrue(m.dragging)
        m.feed(TouchSample(0f, 0f, longPress + 10L, TouchAction.UP))
        assertTrue(!m.dragging)
    }
}
