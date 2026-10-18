package com.apex.agent.core.tools.marketplace

/**
 * ═══ 目录条目 ↔ 内置原生 MCP 服务器 等价映射 ═══
 *
 * 背景（MCP 市场诚实化）：App 二进制里预置了五台**进程内原生** MCP 服务器
 * （McpModule 的 builtinTransports：github / search / fs / memory / thinking），
 * 其中 `thinking` 就是官方 `@modelcontextprotocol/server-sequential-thinking`
 * 的原生复刻、`memory` 与官方 `server-memory` 同语义。而市场目录（apex-mcp-hub /
 * Registry / mcp.so）里同一台服务器仍以 `npx -y ...` 的 STDIO 条目出现 ——
 * 用户点「安装」得到一份**在 Android 上大概率跑不起来**的沙箱配置
 * （沙箱内未必装了 nodejs），点「启动」则必然撞「进程在响应前退出」。
 *
 * 本映射是市场层的**唯一事实源**：目录条目命中内置等价时——
 * - 卡片不渲染「安装」按钮（真内置不卖安装）；
 * - 改渲染「已内置」徽标 + 「启动内置」按钮（直达内置服务器的连接管线）；
 * - 安装控制器（Hub / Registry）入口拦截，杜绝绕过 UI 的重复安装。
 *
 * ## 收录纪律（诚实优先，宁缺毋滥）
 * 只收录**语义完整等价或官方条目在本机严格更差**的映射：
 * - `thinking`：官方 sequential-thinking 的语义复刻（thoughtNumber/
 *   totalThoughts/branch 全参数面，per-connection 状态零持久化）——等价；
 * - `memory`：官方 server-memory 的 entities/relations/observations 语义
 *   复刻（同一 JSON 图谱存储）——等价；
 * - `github`：内置版是进程内 7 工具子集，但官方全量工具面已有**更好的**
 *   承载形态（`github-official` 远程预置，PAT 令牌桥免明文落盘）——npx
 *   条目在本机是严格劣解（需沙箱 + node + 慢冷启动），收录并引导；
 * - **不收录** `fs`：内置 fs 是工作区作用域子集（list/read/write/info），
 *   官方 filesystem 的 search/tree 能力没有 —— 不是等价物，目录条目保留
 *   安装（沙箱可用时真实可跑）。
 *
 * 纯 JVM 零依赖（与 [MarketNames] 同包同纪律），单测直接跑。
 */
object NativeMcpEquivalents {

    /**
     * 一条等价映射：目录侧的归一化名 / npm 包名 → 内置服务器 ID
     * （= McpManager builtinTransports 注册表的 key）。
     */
    private data class Equivalent(
        /** 目录条目归一化名（[MarketNames.normalize] 口径）集合。 */
        val names: Set<String>,
        /** npm 包名全称（含 @scope/，不含版本）集合。 */
        val packages: Set<String>,
        /** 内置服务器 ID（如 "thinking"）。 */
        val builtinId: String
    )

    /** 收录清单（见类 KDoc 的收录纪律）。names 为归一化前形态（[MarketNames.normalize] 折叠 `-`/`_`/空格为单一 `-`），连字符变体需显式收录。 */
    private val ENTRIES: List<Equivalent> = listOf(
        Equivalent(
            names = setOf("sequentialthinking", "sequential-thinking"),
            packages = setOf("@modelcontextprotocol/server-sequential-thinking"),
            builtinId = "thinking"
        ),
        Equivalent(
            names = setOf("servermemory", "server-memory", "memory"),
            packages = setOf("@modelcontextprotocol/server-memory"),
            builtinId = "memory"
        ),
        Equivalent(
            names = setOf("servergithub", "server-github", "github"),
            packages = setOf("@modelcontextprotocol/server-github"),
            builtinId = "github"
        )
    )

    /** name → 内置 ID 索引（构造一次，查询 O(1)）。 */
    private val byName: Map<String, String> =
        ENTRIES.flatMap { e -> e.names.map { it to e.builtinId } }.toMap()

    /** npm 包名 → 内置 ID 索引。 */
    private val byPackage: Map<String, String> =
        ENTRIES.flatMap { e -> e.packages.map { it to e.builtinId } }.toMap()

    /**
     * 按目录条目名查内置等价（归一化口径与已安装徽标/防重同源）。
     *
     * @param rawName 目录条目名（Hub name / Registry displayName / mcp.so 标题）
     * @return 内置服务器 ID；无等价返回 null
     */
    fun builtinForName(rawName: String): String? = byName[MarketNames.normalize(rawName)]

    /**
     * 按 npm 包名查内置等价（Registry / mcp.so / Operit 源的包形态条目用）。
     *
     * 包名必须**去版本后**传入（`pkg@1.2.3` → `pkg`）；带 `@scope/` 的原样。
     *
     * @return 内置服务器 ID；无等价返回 null
     */
    fun builtinForPackage(pkg: String): String? =
        byPackage[pkg.trim().removeSuffix("@latest")]

    /**
     * 从 STDIO 命令行（command + args，如 `npx -y pkg@1.0 --flag`）提取
     * npm 包名并查等价。
     *
     * 提取规则：跳过首 token（命令本身）与所有 `-`/`--` 开头的旗标及其
     * `=` 值形态，第一个非旗标 token 视为包名（去 `@version` 尾缀）。
     * `npx`/`npm`/`node` 以外的命令（python/uvx 等）直接返回 null ——
     * 它们的首参不是 npm 包名。
     */
    fun builtinForCommand(command: String?, args: List<String>): String? {
        val cmd = command?.trim()?.lowercase() ?: return null
        if (cmd !in NPM_FAMILY) return null
        for (token in args) {
            val t = token.trim()
            if (t.isEmpty() || t.startsWith("-")) continue
            // 去 @version 尾缀（@scope/name 形态的首段 @ 不动）
            val pkg = if (t.startsWith("@")) {
                // @scope/name@ver：找第二个 @ 之后截断
                val second = t.indexOf('@', 1)
                if (second > 0) t.substring(0, second) else t
            } else {
                t.substringBefore('@')
            }
            return builtinForPackage(pkg)
        }
        return null
    }

    /** 目录侧统一入口：名 + 命令行双路查询（任一命中即等价）。 */
    fun builtinFor(rawName: String, command: String? = null, args: List<String> = emptyList()): String? =
        builtinForName(rawName) ?: builtinForCommand(command, args)

    /**
     * Registry 条目的三路查询（安装拦截与卡片渲染共用同一口径）：
     * reverse-DNS 名 → 展示名 → npm 包名（identifier 需**去版本**传入，
     * 与 [builtinForPackage] 契约一致）。
     *
     * Registry 的 name 是 `io.github.x/mcp-server-y` 形态，展示名才是
     * 「Sequential Thinking」这类人类可读名 —— 两路都查（归一化后
     * `io.github.x/mcp-server-y` 不会撞收录名，误伤面为零）。
     */
    fun builtinForRegistry(
        name: String,
        displayName: String,
        npmIdentifier: String? = null
    ): String? =
        builtinForName(name)
            ?: builtinForName(displayName)
            ?: npmIdentifier?.let { builtinForPackage(it) }

    /** 可识别为「npm 家族」的启动命令（首参是包名的语义前提）。 */
    private val NPM_FAMILY = setOf("npx", "npm", "node", "bunx", "pnpm")
}
