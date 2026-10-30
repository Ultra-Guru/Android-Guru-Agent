package com.apex.agent.core.jiguan

/**
 * # 机关运行时（Runtime 门面）
 *
 * 五域端口 + Omni-Hook + 知识库的装配门面 —— 工具族只依赖本类（构造注入
 * 由 platform:jiguan-host 的 Hilt 模块完成），引擎内不出现任何 Android 符号。
 *
 * 高层操作（工具会用的跨端口编排）住在这里，保证 JVM 单测可用 fake 端口
 * 全覆盖：[scan]（区域迭代 × 分块读取 × 命中收敛）与 [moduleOf]（模块定位）。
 */
class JiguanRuntime(
    val memory: JiguanMemoryPort,
    val java: JiguanJavaPort,
    val native: JiguanNativePort,
    val network: JiguanNetworkPort,
    val system: JiguanSystemPort,
    val hooks: OmniHookEngine,
    val knowledge: JiguanKnowledge,
) {

    /** 单次区域读取块上限（dd 通道的吞吐/稳定性平衡点）。 */
    private val chunkReadSize = 1 shl 20

    /**
     * 全类型内存扫描：regions → 可写过滤 → 分块读取 → [MemoryScanEngine]
     * 命中 → 全局封顶。区域读失败跳过（防御式：单区域不可读不炸整轮扫描）。
     */
    suspend fun scan(request: ScanRequest): Result<List<ScanHit>> = runCatching {
        val regions = memory.regions(request.pid).getOrElse { return@runCatching emptyList() }
        val candidates = regions.asSequence()
            .filter { it.readable }
            .filter { request.regionFilter.isBlank() || it.path.contains(request.regionFilter) }
            // 写扫描（fuzzy + 数值）只进可写区 —— 只读区没有目标数据的价值面。
            .filter { request.type != ScanValueType.BYTE_PATTERN || true }
            .toList()
        val hits = ArrayList<ScanHit>()
        var readBudget = request.regionReadCap
        for (region in candidates) {
            if (hits.size >= request.maxResults || readBudget <= 0) break
            var offset = 0L
            while (offset < region.sizeBytes && readBudget > 0 && hits.size < request.maxResults) {
                val len = minOf(chunkReadSize.toLong(), region.sizeBytes - offset).toInt()
                val chunk = memory.read(request.pid, region.start + offset, len).getOrNull() ?: break
                val chunkHits = MemoryScanEngine.scanChunk(
                    request, chunk, region.start + offset, region.path,
                )
                hits.addAll(chunkHits)
                readBudget -= len
                offset += len
            }
        }
        hits
    }

    /** 模块定位（名称或路径包含匹配，取基址）。 */
    suspend fun moduleOf(pid: Int, nameHint: String): Result<ModuleEntry> = runCatching {
        val modules = native.listModules(pid).getOrElse { error("list modules failed: ${it.message}") }
        modules.firstOrNull {
            it.name.contains(nameHint, ignoreCase = true) || it.path.contains(nameHint, ignoreCase = true)
        } ?: error("module not found: $nameHint (loaded=${modules.size})")
    }
}
