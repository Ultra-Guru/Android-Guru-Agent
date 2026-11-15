package com.apex.agent.core.code.stream

/**
 * 时间轴条目 id → 下标索引 + 内存滑动窗口（perf P0-1/P0-3，自
 * CodeStreamSession 抽出保 1200 行预算——职责内聚：条目定位与窗口裁剪）。
 *
 * 工具输出分片的热路径（updateCall / replaceEntry）以 O(1) 定位替代
 * indexOfFirst 全轴扫描——长会话时间轴上每分片 O(n) 扫描是「多工具时
 * 严重卡顿」的主因之一。结构性变更（移除 / 窗口裁剪 / 整轴替换）后整表
 * 重建；locate miss 时回退线性查找并回填（自愈，防失配静默丢更新）。
 *
 * 线程模型：仅宿主协程（onEvent/inject 调用链）串行访问，无并发需求。
 */
internal class StreamEntryIndex {

    private val map = HashMap<String, Int>()

    /** append 后登记（尾部追加，既有下标不受影响）。 */
    fun onAppend(id: String, index: Int) {
        map[id] = index
    }

    /** 索引定位：命中校验（防陈旧下标），miss 回退线性查找并回填。 */
    fun locate(id: String, entries: List<StreamEntry>): Int =
        map[id]?.takeIf { it < entries.size && entries[it].id == id }
            ?: entries.indexOfFirst { it.id == id }.also { if (it >= 0) map[id] = it }

    /** 结构性变更（removeAt / 裁剪 / 整轴替换）后整表重建（低频）。 */
    fun rebuild(entries: List<StreamEntry>) {
        map.clear()
        entries.forEachIndexed { i, e -> map[e.id] = i }
    }

    /**
     * 时间轴内存滑动窗口：超 [maxEntries] 裁掉最老条目并重建索引。
     * 活跃流式条目恒在尾部不受影响；检查点落盘本就 takeLast(400)，恢复
     * 链路 replaceAll 不受影响；被裁条目由旧引用持有者（如验证闭环的
     * openCycle 追踪）经 locate miss 安全跳过。返回是否发生裁剪。
     */
    fun trimWindow(entries: MutableList<StreamEntry>, maxEntries: Int): Boolean {
        val excess = entries.size - maxEntries
        if (excess <= 0) return false
        entries.subList(0, excess).clear()
        rebuild(entries)
        return true
    }
}
