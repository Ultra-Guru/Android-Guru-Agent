package com.apex.agent.core.tools.marketplace

/**
 * ═══ 跨源市场名称归一化（Issue #346）═══
 *
 * 五个 MCP 源（Hub / mcp.so / Registry / Operit / Catalog）对同一台
 * 服务器的命名规则各不相同：Hub 用目录 name、mcp.so 用卡片标题、
 * Registry 用 displayName、Operit 用 owner-repo 扁平化名、Catalog 用
 * id。同一台服务器（如 GitHub / filesystem）从不同源安装会产生多条
 * 配置，「已安装」徽标互不识别、重复安装无提示。
 *
 * [normalize] 提供统一的比较键：
 * - 小写化 + 去首尾空白；
 * - 把 `-`、`_`、`/`、空格的**连续段折叠为单个 `-`**（选单一分隔符而非
 *   全部删除：保留词间边界，`fs server` 与 `fsserver` 不会误判同名）；
 * - 再去掉首尾的 `-`。
 *
 * 例：`"FS Server"`、`"fs_server"`、`"fs/server"` → `fs-server`；
 * `" GitHub "` → `github`。所有 exists / 徽标 / 去重比较点应两侧归一化
 * 后再比较（[isSameServer]）。
 */
object MarketNames {

    /** 跨源名称归一化：小写 + trim + 折叠分隔符连续段为单一 `-`，再 trim 分隔符。 */
    fun normalize(raw: String): String {
        val lowered = raw.trim().lowercase()
        if (lowered.isEmpty()) return ""
        val folded = buildString(lowered.length) {
            var pendingSeparator = false
            for (ch in lowered) {
                if (ch == '-' || ch == '_' || ch == '/' || ch == ' ') {
                    pendingSeparator = true
                } else {
                    if (pendingSeparator && isNotEmpty()) append(SEPARATOR)
                    pendingSeparator = false
                    append(ch)
                }
            }
        }
        return folded.trim(SEPARATOR)
    }

    /** 两个名字是否指向同一台服务器（两侧归一化后比较，Issue #346 的比较口径）。 */
    fun isSameServer(a: String, b: String): Boolean =
        a === b || normalize(a) == normalize(b)

    /**
     * 重复安装提示文案（安装防重统一口径）：
     * - 完全同名 → 与旧文案同义的「已配置」提示；
     * - 归一化同名但原名不同 → 点名「已从其他源安装了同名服务器，复用现有
     *   配置」，引导用户到「已安装管理」做切换/删除。
     */
    fun duplicateMessage(existingName: String, incomingName: String): String =
        if (existingName == incomingName) {
            "服务器「$existingName」已配置——如需重装请先在「已安装管理」中删除"
        } else {
            "已从其他源安装了同名服务器「$existingName」，已复用现有配置（不再重复安装" +
                "「$incomingName」）——如需改用新源，请先删除现有配置"
        }

    private const val SEPARATOR = '-'
}
