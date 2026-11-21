package com.apex.agent.ui.screen.glass

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlassDockMath —— 深色 Dock v2 槽位数学（纯 JVM · Gate-0 D-2/D-3）
 * ═══════════════════════════════════════════════════════════════
 *
 * v1 的三个硬 bug 里有两个是「布局数学」：
 *  - D-2：4 项等分 + 中央 76dp 固定居中发布位 → 第 2/3 项各被盖住一半，
 *    每页 4 项实际只有 2 项可点；
 *  - D-3：全局选中索引 × pager 偏移手算药丸位移 → 滑到第 2 页停稳时
 *    translationX 飞出整屏宽，第 2 页全无选中高亮。
 *
 * v2 的修法全部收敛为**纯函数**（Compose 侧只消费不计算）——
 * [GlassLabDarkBarLayoutTest] 逐条锁语义，含「v1 公式必挂」的反证
 * （证明测试本身能抓住 v1 的 bug，不是恒真断言）。
 *
 * v2 槽位模型（每页 5 槽，发布位独占中央）：
 *
 *      槽0     槽1    [发布]    槽3     槽4
 *     ┌────┬────┬─────────┬────┬────┐
 *     │nav │nav │  76dp   │nav │nav │   每槽宽 = (W − 76) / 4
 *     └────┴────┴─────────┴────┴────┘
 *
 * 页内导航项索引 0..3 → 槽位 0,1,3,4（发布槽 = 2 永不占用）。
 */
object GlassDockMath {

    /** 双页 Dock。 */
    const val PAGES = 2

    /** 每页导航项数。 */
    const val NAV_PER_PAGE = 4

    /** 每页槽位数（含中央发布槽）。 */
    const val SLOTS_PER_PAGE = 5

    /** 发布槽槽位号（独占，nav 永不占用）。 */
    const val PUBLISH_SLOT = 2

    /** 发布位固定宽度（dp）。 */
    const val PUBLISH_WIDTH_DP = 76f

    /** 发布按钮凸出栏顶的高度（dp，D-1）。 */
    const val PUBLISH_LIFT_DP = 18f

    // ═══ 索引映射（D-2 的核心）═══

    /** 全局索引 0..7 → 页号（0/1）。 */
    fun pageOf(globalIndex: Int): Int = globalIndex / NAV_PER_PAGE

    /** 全局索引 0..7 → 页内索引 0..3。 */
    fun indexInPage(globalIndex: Int): Int = globalIndex % NAV_PER_PAGE

    /** 页号 + 页内索引 → 全局索引。 */
    fun globalIndex(page: Int, indexInPage: Int): Int =
        page * NAV_PER_PAGE + indexInPage

    /**
     * 页内索引 → 槽位（0,1,3,4）：发布槽 2 永不占用 —— 中央按钮下面
     * 永远是留白 Spacer，8 个导航项全部可点（D-2 修复本体）。
     */
    fun slotOf(indexInPage: Int): Int = if (indexInPage < PUBLISH_SLOT) indexInPage else indexInPage + 1

    // ═══ 像素几何（药丸与槽位定位）═══

    /** 单个导航槽宽（px）：(内容宽 − 发布宽) / 4。 */
    fun navSlotWidthPx(contentWidthPx: Int, publishWidthPx: Int): Float =
        (contentWidthPx - publishWidthPx) / NAV_PER_PAGE.toFloat()

    /**
     * 槽位左缘 x（px）：发布槽右侧的槽整体右移一个发布宽 ——
     * `槽位 ≤ 2 → 槽位 × 槽宽`；`槽位 > 2 → (槽位 − 1) × 槽宽 + 发布宽`
     * （发布槽本身占掉一个「槽位号」，其后的导航槽各前移一号）。
     */
    fun slotOffsetXpx(slot: Int, contentWidthPx: Int, publishWidthPx: Int): Float {
        val slotWidth = navSlotWidthPx(contentWidthPx, publishWidthPx)
        return if (slot > PUBLISH_SLOT) {
            (slot - 1) * slotWidth + publishWidthPx
        } else {
            slot * slotWidth
        }
    }

    /**
     * 药丸在页内的落位 x（px）：页内选中项 → 槽位 → 槽左缘。
     * 药丸是**每页独立**的（D-3 修复本体）—— 只做页内槽位位移，跨页
     * 移动交给 Pager 原生手势（药丸随所在页整体平移，永不飞出）。
     */
    fun pillOffsetXpx(
        pageLocalSelected: Int,
        contentWidthPx: Int,
        publishWidthPx: Int
    ): Float = slotOffsetXpx(slotOf(pageLocalSelected.coerceIn(0, NAV_PER_PAGE - 1)), contentWidthPx, publishWidthPx)

    /**
     * 槽位占位区与发布位是否重叠 —— v2 槽位模型对全部导航槽恒假
     * （每槽可点，D-2 修复本体）；v1 等分布局的判伤见
     * [overlapsPublishEqualSplit]。
     */
    fun slotRectOverlapsPublish(
        slot: Int,
        contentWidthPx: Int,
        publishWidthPx: Int
    ): Boolean {
        if (slot == PUBLISH_SLOT) return true
        val sw = navSlotWidthPx(contentWidthPx, publishWidthPx)
        val left = slotOffsetXpx(slot, contentWidthPx, publishWidthPx)
        val right = left + sw
        val pubLeft = 2f * sw
        val pubRight = pubLeft + publishWidthPx
        return left < pubRight && right > pubLeft
    }

    /**
     * **v1 反证**（回归判伤器）：4 项等分（槽宽 = W/4、无发布槽）时，
     * 中央固定发布位 [W/2 − pw/2, W/2 + pw/2] 必然压住第 1、2 项 ——
     * 该函数恒真（在真实屏宽下），证明 [slotRectOverlapsPublish] 的
     * 判据能抓住 v1 的死区 bug（D-2 的「测试能抓 bug」证明）。
     */
    fun overlapsPublishEqualSplit(contentWidthPx: Int, publishWidthPx: Int): Boolean {
        val sw = contentWidthPx / NAV_PER_PAGE.toFloat()
        val pubLeft = contentWidthPx / 2f - publishWidthPx / 2f
        val pubRight = pubLeft + publishWidthPx
        // 第 1 项右半 / 第 2 项左半落入发布区（360dp 屏：sw=90、pub=[142,218]）
        return (1 * sw) < pubRight && (2 * sw) > pubLeft
    }

    // ═══ 跨页选中同步（D-3 的核心）═══

    /**
     * 滑动停稳后的选中同步：selectedIndex ← 当前页的页内选中项。
     * v2 药丸每页独立，全局选中只是「当前页的页内选中」的投影 ——
     * 换页不再需要药丸跨屏飞移，selectedIndex 也不再有跨页悬置态。
     *
     * @param settledPage Pager 停稳后的当前页
     * @param selectedInPage 每页的页内选中（长度 = PAGES，元素 0..3）
     */
    fun selectionAfterSettle(settledPage: Int, selectedInPage: List<Int>): Int {
        require(selectedInPage.size == PAGES) { "selectedInPage must cover $PAGES pages" }
        val page = settledPage.coerceIn(0, PAGES - 1)
        return globalIndex(page, selectedInPage[page].coerceIn(0, NAV_PER_PAGE - 1))
    }
}
