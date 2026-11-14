package com.apex.agent.ui.screen.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ GlassLabDarkBarLayoutTest —— Dock v2 槽位数学锁（Gate-0 G0-1/G0-2）
 * ═══
 *
 * v1 三个硬 bug 中的 D-2（中央发布位盖死两项）/ D-3（药丸跨页飞出）
 * 全部收敛为 [GlassDockMath] 纯函数 —— 本测试逐条锁语义，并含「v1 公式
 * 必挂」反证（证明判据能抓住 v1 的 bug，不是恒真断言）。
 *
 * 360dp 屏（内容宽 320dp、发布位 76dp）与 411dp 宽屏双档覆盖。
 */
class GlassLabDarkBarLayoutTest {

    private val contentW = 320 // px（360dp 屏下 Dock 内容宽的整数近似）
    private val publishW = 76  // px（76dp 发布位）

    // ═══ D-2：五槽模型 —— 发布槽独占，8 项全部可点 ═══

    @Test
    fun `slot of never occupies the publish slot`() {
        // 页内索引 0..3 → 槽位 0,1,3,4 —— 槽 2 永远是发布位
        assertEquals(0, GlassDockMath.slotOf(0))
        assertEquals(1, GlassDockMath.slotOf(1))
        assertEquals(3, GlassDockMath.slotOf(2))
        assertEquals(4, GlassDockMath.slotOf(3))
    }

    @Test
    fun `all nav slots avoid the publish rect`() {
        // 两个屏宽档 × 全部导航槽：与发布区零重叠（D-2 修复本体）
        listOf(contentW, 411).forEach { w ->
            (0 until GlassDockMath.SLOTS_PER_PAGE).forEach { slot ->
                if (slot == GlassDockMath.PUBLISH_SLOT) return@forEach
                assertFalse(
                    "slot $slot must not overlap publish (w=$w)",
                    GlassDockMath.slotRectOverlapsPublish(slot, w, publishW)
                )
            }
        }
    }

    @Test
    fun `v1 equal split layout is caught by the same criterion`() {
        // 判伤器反证：v1 的 4 等分（无发布槽）在真实屏宽下必然压住中间两项
        // —— 证明上面的判据能抓住 v1 的死区 bug（测试本身的灵敏度）
        listOf(contentW, 411, 1080).forEach { w ->
            assertTrue(
                "v1 equal split must overlap publish (w=$w)",
                GlassDockMath.overlapsPublishEqualSplit(w, publishW)
            )
        }
    }

    @Test
    fun `nav slots tile the full content width without gaps`() {
        // 四个导航槽 + 发布位恰好铺满内容宽（无重叠、无缝隙）
        val sw = GlassDockMath.navSlotWidthPx(contentW, publishW)
        val total = sw * GlassDockMath.NAV_PER_PAGE + publishW
        assertEquals(contentW.toFloat(), total, 0.5f)
    }

    // ═══ 像素几何：槽位定位与边界 ═══

    @Test
    fun `slot offsets respect publish shift`() {
        val sw = GlassDockMath.navSlotWidthPx(contentW, publishW)
        // 槽 0/1 在发布位左侧；槽 3/4 整体右移一个发布宽
        assertEquals(0f, GlassDockMath.slotOffsetXpx(0, contentW, publishW), 0.01f)
        assertEquals(sw, GlassDockMath.slotOffsetXpx(1, contentW, publishW), 0.01f)
        assertEquals(2 * sw + publishW, GlassDockMath.slotOffsetXpx(3, contentW, publishW), 0.01f)
        assertEquals(3 * sw + publishW, GlassDockMath.slotOffsetXpx(4, contentW, publishW), 0.01f)
    }

    @Test
    fun `pill offset stays inside the page for every reachable selection`() {
        // 药丸是每页独立的（D-3）：任意页内选中 → 药丸 x + 槽宽 ≤ 内容宽
        val sw = GlassDockMath.navSlotWidthPx(contentW, publishW)
        (0 until GlassDockMath.PAGES).forEach { page ->
            (0 until GlassDockMath.NAV_PER_PAGE).forEach { inPage ->
                val x = GlassDockMath.pillOffsetXpx(inPage, contentW, publishW)
                assertTrue("pill x must be >= 0 (page=$page sel=$inPage)", x >= 0f)
                assertTrue(
                    "pill must stay inside page (page=$page sel=$inPage)",
                    x + sw <= contentW + 0.5f
                )
            }
        }
    }

    // ═══ 索引映射：全局 ⇄ 页内 ═══

    @Test
    fun `global index round trip`() {
        (0 until GlassDockMath.PAGES * GlassDockMath.NAV_PER_PAGE).forEach { g ->
            assertEquals(g, GlassDockMath.globalIndex(GlassDockMath.pageOf(g), GlassDockMath.indexInPage(g)))
        }
        assertEquals(0, GlassDockMath.pageOf(0))
        assertEquals(0, GlassDockMath.pageOf(3))
        assertEquals(1, GlassDockMath.pageOf(4))
        assertEquals(1, GlassDockMath.pageOf(7))
    }

    // ═══ D-3：滑动停稳后的选中同步 ═══

    @Test
    fun `selection after settle projects the current page`() {
        // 停在第 1 页 → 全局选中 = 第 1 页的页内选中项投影
        assertEquals(
            4 + 2,
            GlassDockMath.selectionAfterSettle(settledPage = 1, selectedInPage = listOf(3, 2))
        )
        // 停在第 0 页 → 第 0 页的投影
        assertEquals(
            2,
            GlassDockMath.selectionAfterSettle(settledPage = 0, selectedInPage = listOf(2, 0))
        )
    }

    @Test
    fun `selection after settle never references the other page`() {
        // v1 的 bug 形态：第 2 页停稳时药丸引用第 1 页的选中项 → 飞出屏。
        // v2 的投影语义保证 selectedIndex 永远落在停稳页内 —— 换页零跳变。
        val selectedInPage = listOf(0, 3)
        val settledOnPageTwo = GlassDockMath.selectionAfterSettle(1, selectedInPage)
        assertEquals(1, GlassDockMath.pageOf(settledOnPageTwo))
        assertEquals(3, GlassDockMath.indexInPage(settledOnPageTwo))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `selection after settle rejects malformed page table`() {
        GlassDockMath.selectionAfterSettle(0, listOf(0)) // 必须覆盖双页
    }

    // ═══ 常量面：与 v2 实现的契约 ═══

    @Test
    fun `dock geometry constants`() {
        assertEquals(2, GlassDockMath.PAGES)
        assertEquals(4, GlassDockMath.NAV_PER_PAGE)
        assertEquals(5, GlassDockMath.SLOTS_PER_PAGE)
        assertEquals(2, GlassDockMath.PUBLISH_SLOT)
        assertEquals(76f, GlassDockMath.PUBLISH_WIDTH_DP, 0f)
        assertEquals(18f, GlassDockMath.PUBLISH_LIFT_DP, 0f)
    }
}
