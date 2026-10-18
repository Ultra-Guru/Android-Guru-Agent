package com.apex.agent.core.tools.catalog

/**
 * # Tool System v4 — Tool Tiers (default-on core set)
 *
 * Root cause this fixes: the registry holds ~110 tools; sending every schema
 * on every request produced a 70–100 KB payload that small-context models and
 * strict OpenAI-compatible gateways reject outright ("directly sending a
 * message errors, you must pick functions first").
 *
 * Borrowed and unified from the two best references:
 * - operit's progressive activation (`use_package` materialises extra tool
 *   docs only when needed);
 * - rikkahub-agent's ToolCatalog (search/open meta-tools once the surface
 *   grows past ~30 tools).
 *
 * v4 policy:
 * - **CORE** — a curated, always-exposed set (~45 ids below). These go into
 *   every request with full schemas: the agent is *capable by default*.
 * - **CATALOG** — everything else. Not in the request; discoverable through
 *   the `ToolSearch` / `ToolOpen` / `ToolList` meta-tools. `ToolOpen`
 *   activates a tool for the rest of the session (see [ToolActivationStore]),
 *   and the engine includes activated tools on the next iteration.
 * - **LEGACY ALIASES** — deprecated duplicate ids kept for compatibility.
 *   They never enter the request; the modern tool wins the name slot.
 */
object ToolTierPolicy {

    /** The deprecated alias ids (old flat names kept only for compat). */
    val LEGACY_ALIAS_IDS: Set<String> = setOf(
        "TerminalExecLegacy", "TerminalSendLegacy", "TerminalReadLegacy",
        "TerminalListLegacy", "TerminalSignalLegacy", "TerminalCloseLegacy",
        // #171 四族合并：旧工具 id 降为 legacy alias（类保留注册，向后兼容
        // 既有会话/技能；不再随请求下发，模型一律用合并后的新入口）。
        "GetTimeLegacy", "DatetimeLegacy", "CronNextLegacy", "DurationConvertLegacy",
        "UuidGenerateLegacy", "RandomGenerateLegacy",
        "RegexExtractLegacy", "RegexReplaceLegacy",
        "JsonPathLegacy", "JsonTransformLegacy"
    )

    /**
     * Curated CORE set — always in the request, full schema.
     *
     * Selection criteria:
     * 1. Covers the agent's headline capabilities (ask / files / web /
     *    terminal / memory / apps / device / MCP / skills / connectors /
     *    browser automation);
     * 2. Each is individually useful without prior setup;
     * 3. Total schema payload stays ~20 KB — safe for every provider.
     */
    val CORE_TOOL_IDS: Set<String> = setOf(
        // ── interaction ──
        "AskUser", "AskUserChoice",

        // ── files ──
        "Read", "Write", "Edit", "ListFiles",
        "GlobFiles", "SearchFiles", "CopyMoveFile", "DeleteFile",

        // ── coding (Code 模式；Agent 模式同样可见 —— 工具规则互用) ──
        // v0.2：CodeTask（#147 子代理委派）与 CodeCheck（#148 即时诊断）入 CORE。
        "CodeRead", "CodeEdit", "CodeWrite", "CodeGrep", "CodeGlob", "CodeTodo",
        "CodeTask", "CodeCheck",

        // ── git (v1.0 #153；经 PRoot Ubuntu 沙箱执行，两模式互用) ──
        "CodeGitStatus", "CodeGitDiff", "CodeGitLog", "CodeGitCommit", "CodeGitBranch",

        // ── web ──
        "WebSearch", "WebFetch", "HttpRequest", "DownloadFile",

        // ── browser (内置浏览器自动化 v1.5.0) ──
        // 一级大功能 ≠ 一级工具地位：24 个 browser_* 只有「Agent 零前置知识也
        // 高频用到」的 5 个入口进 CORE（打开页面 → 看页面 → 点/填 → 读内容，
        // 恰好覆盖一次网页任务的主干路径，schema 增量 ~2KB）；其余 19 个
        // （select/scroll/截图/JS 逃生舱/Cookie 等长尾动作与可观测性工具）
        // 留在 CATALOG 走 ToolSearch 渐进披露 —— 与 operit 渐进激活同款哲学。
        "BrowserNavigate", "BrowserSnapshot", "BrowserClick",
        "BrowserInput", "BrowserExtractContent",

        // ── terminal (agent-native PTY; dotted ids are provider-sanitised) ──
        // TerminalExec = one-shot structured execution（无需先建会话，
        // “agent 默认能跑命令”的最短路径）。
        "TerminalExec", "TerminalCreate", "TerminalRun", "TerminalObserve",
        "TerminalWait", "TerminalSnapshot", "TerminalBackends",

        // ── memory (CS-Mem) ──
        "MemoryRecentEpisodes", "MemorySearchNodes", "MemoryRecallMacro",

        // ── apps & device ──
        "AppList", "AppInfo", "AppLaunch",
        // #171：GetTimeLegacy → time（四族合并后唯一时间入口）；
        // #172：ContextRecap 入 CORE —— 长会话自救的关键工具（迷路时一次
        // 调用重建全景，无需 ToolSearch 两跳）。
        // #396：ContextSearch / ContextFetch 入 CORE —— 上下文自主检索闭环
        // （被压缩裁剪的历史也检索得到、取得到原文）；三元组常驻，模型
        // 需要时一步直达，不需要时只付 schema 成本不付内容成本。
        "GetDeviceInfo", "Time", "ContextRecap", "ContextSearch", "ContextFetch",
        "Clipboard", "Screenshot", "Calculate",

        // ── MCP (first-class tools register as mcp__server__tool) ──
        "McpList", "McpConnect", "McpCall",

        // ── skills & connectors ──
        "SkillList", "SkillSearch", "ConnectorList",

        // ── catalog meta-tools (v4) ──
        "ToolSearch", "ToolOpen", "ToolList",

        // ── capability introspection（能力自省：模型的第一反动作）──
        // CapabilityReport：权限/环境/工具/技能/MCP 一键自省（遇 permission
        // denied 或「我能做 X 吗」类问题时先自省再行动，不猜不弃）；
        // MarketSearch：官方技能+MCP 市场检索（「没装的能力一步之遥」）。
        "CapabilityReport", "MarketSearch",

        // ── v5：agent 自主设置（让 agent 自查/自调自身配置）──
        // AgentSettingGet 入 CORE：模型应在每次会话开始就知道当前运行配置
        // （避免"我不知道我能调多少迭代上限"的盲调）；AgentSettingSet 与
        // profile/role/preset 操作为 CATALOG（不必要时不全量入请求体）。
        "AgentSettingGet",
    )

    /** @return true when [toolId] belongs to the always-exposed CORE set. */
    fun isCore(toolId: String): Boolean = toolId in CORE_TOOL_IDS

    /** @return true when [toolId] is a deprecated legacy alias. */
    fun isLegacyAlias(toolId: String): Boolean = toolId in LEGACY_ALIAS_IDS
}
