package com.apex.agent.core.jiguan

/**
 * # /proc 文本解析器（纯函数）
 *
 * 机关所有 /proc 读取都收敛为「宿主读文件 → 引擎解析文本」两段式 —— 解析
 * 逻辑因此可在 JVM 单测里用真实样本行全覆盖。防御式纪律：任何坏行折叠为
 * null 跳过（绝不抛异常），与 AGENTS.md「外部输入任何形状都要吞得下」对齐。
 */
object ProcParsing {

    // ── /proc/<pid>/maps 行 ────────────────────────────────────────
    // 样本：
    //   12c00000-12e00000 rw-p 00000000 fd:01 1234      [heap]
    //   7a8b9c0000-7a8ba00000 r--p 00000000 fd:01 5678   /data/app/~~x/base.apk
    //   7f0000000000-7f0000100000 rw-p 00000000 00:00 0  [anon:libc_malloc]

    /** 解析一行 maps；坏行返回 null。 */
    fun parseMapsLine(line: String): MemoryRegion? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) return null
        val fields = trimmed.split(Regex("\\s+"), limit = 6)
        if (fields.size < 5) return null
        val range = fields[0].split('-')
        if (range.size != 2) return null
        val start = range[0].toLongOrNull(16) ?: return null
        val end = range[1].toLongOrNull(16) ?: return null
        if (end <= start) return null
        val perms = fields[1]
        val offset = fields[2].toLongOrNull(16) ?: 0L
        val dev = fields[3]
        val inode = fields[4].toLongOrNull() ?: 0L
        val path = if (fields.size >= 6) fields[5] else ""
        return MemoryRegion(start, end, perms, offset, dev, inode, path)
    }

    /** 解析整份 maps 文本（坏行跳过；空行容忍）。 */
    fun parseMaps(text: String): List<MemoryRegion> =
        text.lineSequence().mapNotNull(::parseMapsLine).toList()

    /**
     * 从 maps 聚合模块清单：同名文件路径的多段映射合并为一个 [ModuleEntry]
     * （base = 最小 start，size = end 最大值 - base）。
     */
    fun aggregateModules(regions: List<MemoryRegion>): List<ModuleEntry> {
        data class Acc(
            var base: Long = Long.MAX_VALUE,
            var maxEnd: Long = 0L,
            var r: Boolean = false,
            var w: Boolean = false,
            var x: Boolean = false,
        )
        val byPath = LinkedHashMap<String, Acc>()
        for (region in regions) {
            if (region.path.isBlank()) continue
            val acc = byPath.getOrPut(region.path) { Acc() }
            if (region.start < acc.base) acc.base = region.start
            if (region.end > acc.maxEnd) acc.maxEnd = region.end
            acc.r = acc.r || region.readable
            acc.w = acc.w || region.writable
            acc.x = acc.x || region.executable
        }
        return byPath.map { (path, acc) ->
            ModuleEntry(
                name = path.substringAfterLast('/'),
                path = path,
                baseAddress = acc.base,
                sizeBytes = acc.maxEnd - acc.base,
                readable = acc.r,
                writable = acc.w,
                executable = acc.x,
            )
        }.sortedByDescending { it.sizeBytes }
    }

    // ── /proc/<pid>/status 关键行 ─────────────────────────────────
    // 样本：Name: com.x.y / Uid: 10123 10123 10123 10123 / State: S (sleeping)

    /** 从 status 文本提取 (name, uid, ppid, state)。 */
    fun parseStatus(text: String): ProcessEntry? {
        var name = ""
        var uid = -1
        var ppid = -1
        var state = ""
        var rssKb = -1L
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Name:") -> name = line.removePrefix("Name:").trim()
                line.startsWith("Uid:") -> {
                    val first = line.removePrefix("Uid:").trim().split(Regex("\\s+")).firstOrNull()
                    uid = first?.toIntOrNull() ?: -1
                }
                line.startsWith("PPid:") -> ppid = line.removePrefix("PPid:").trim().toIntOrNull() ?: -1
                line.startsWith("State:") -> {
                    val v = line.removePrefix("State:").trim()
                    state = v.takeWhile { it != '(' }.trim()
                }
                line.startsWith("VmRSS:") ->
                    rssKb = line.removePrefix("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: -1L
            }
        }
        if (name.isEmpty()) return null
        return ProcessEntry(pid = -1, name = name, uid = uid, ppid = ppid, state = state, rssKb = rssKb)
    }

    /** cmdline 文本（NUL 分隔）→ 可读命令行。 */
    fun parseCmdline(text: String): String =
        text.split('\u0000').filter { it.isNotBlank() }.joinToString(" ")

    /**
     * 从 cmdline / name 提取 Android 包名形态（com.xxx.yyy / cn.xxx / org.xxx）。
     * 兼容 `com.a.b:subprocess` 形态与 `u0_a123` 噪声。
     */
    fun extractPackage(cmdline: String, name: String): String? {
        val candidates = buildList {
            val c = parseCmdline(cmdline)
            if (c.isNotBlank()) add(c)
            if (name.isNotBlank()) add(name)
        }
        val pkgRegex = Regex("^[a-z][a-z0-9_]*(\\.[a-zA-Z0-9_]+){2,}(:[a-zA-Z0-9_]+)?$")
        for (candidate in candidates) {
            // 只取第一段（进程可能带参数）。
            val first = candidate.split(' ').firstOrNull() ?: continue
            if (pkgRegex.matches(first)) return first
        }
        return null
    }
}
