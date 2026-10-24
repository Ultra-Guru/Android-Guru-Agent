package com.apex.agent.core.engine.task

/**
 * T76 — 工具幂等性分类注册表（N-7）。
 *
 * **不动 `AgentTool` 接口**（冻结原则）：分类表独立维护 toolId →
 * [ToolIdempotencyClass] 映射，供：
 * 1. journal 落盘时快照分类（ToolOperationRecord.idempotency）；
 * 2. 崩溃恢复时对 UNKNOWN/中断操作的决策输入（[RecoveryPolicy]）。
 *
 * 覆盖 App 实际注册的 80 个工具（core/tool-registry 41 + terminal 8 +
 * browser 15 + cs-mem 3 + github 7 + AskUser 2 + 其余 app 层），逐一
 * 人工归类。**未列入表的工具默认 [ToolIdempotencyClass.UNKNOWN]**
 * （插件工具、未来新工具自动进入保守路径——恢复时升级为用户决策）。
 *
 * 归类口径（重放一次该调用的副作用）：
 * - READ_ONLY：纯读（文件列表、抓屏、日志、检索）→ 重放无副作用；
 * - IDEMPOTENT_WRITE：整覆盖写 / 幂等连接 / 纯函数变换 → 重放等价；
 * - NON_IDEMPOTENT：递增副作用（卸载、点击、输入、发送）→ 重放有害；
 * - UNKNOWN：任意行为透传（shell、HTTP、MCP、插件）→ 不可判定。
 */
class ToolExecutionPolicy {

    /** toolId → 幂等性分类（查不到 → UNKNOWN）。 */
    fun classify(toolId: String): ToolIdempotencyClass =
        REGISTRY[toolId] ?: ToolIdempotencyClass.UNKNOWN

    /**
     * 该工具是否"用户交互型"（AskUser 族）。
     *
     * 恢复时未获回答的 AskUser 调用应 RETRY（重新弹出提问），
     * 而非当作数据操作做幂等决策——单独识别。
     */
    fun isUserInteractionTool(toolId: String): Boolean =
        toolId in USER_INTERACTION_TOOLS

    companion object {
        /** 全部已注册工具的显式分类表（人工逐条归类，可审计）。 */
        private val REGISTRY: Map<String, ToolIdempotencyClass> = buildMap {
            // ═══ 文件 / 文件系统（core/tool-registry FileTools）═══
            put("Read", ToolIdempotencyClass.READ_ONLY)
            put("ListFiles", ToolIdempotencyClass.READ_ONLY)
            put("GlobFiles", ToolIdempotencyClass.READ_ONLY)
            put("SearchFiles", ToolIdempotencyClass.READ_ONLY)
            put("Write", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 整文件覆盖写
            put("Edit", ToolIdempotencyClass.NON_IDEMPOTENT)    // 增量编辑变换，重放可能二次修改
            put("append", ToolIdempotencyClass.NON_IDEMPOTENT)       // append 语义天然非幂等
            put("DeleteFile", ToolIdempotencyClass.NON_IDEMPOTENT)  // 删除不可逆
            put("CopyMoveFile", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("create_directory", ToolIdempotencyClass.IDEMPOTENT_WRITE) // mkdirs 幂等
            put("file_organizer", ToolIdempotencyClass.NON_IDEMPOTENT)    // 批量移动
            put("DownloadFile", ToolIdempotencyClass.IDEMPOTENT_WRITE)   // 下载覆盖写
            put("TextTransform", ToolIdempotencyClass.READ_ONLY)     // 纯函数变换（大小写/反转）
            put("json_format", ToolIdempotencyClass.READ_ONLY)
            put("char_count", ToolIdempotencyClass.READ_ONLY)
            put("data_analyzer", ToolIdempotencyClass.READ_ONLY)
            put("code", ToolIdempotencyClass.READ_ONLY)
            put("code_runner", ToolIdempotencyClass.UNKNOWN)          // 任意代码执行
            put("structure", ToolIdempotencyClass.READ_ONLY)
            put("template", ToolIdempotencyClass.READ_ONLY)
            put("connector", ToolIdempotencyClass.READ_ONLY)

            // ═══ Shell / 系统（高危透传）═══
            put("ShellExecute", ToolIdempotencyClass.UNKNOWN)        // 任意命令：行为不可判定
            put("GetDeviceInfo", ToolIdempotencyClass.READ_ONLY)
            put("GetTimeLegacy", ToolIdempotencyClass.READ_ONLY)
            // #171/#172：合并工具（纯函数）/ 上下文回顾三件套（会话只读视图）
            // / 图片元信息 —— 重放无副作用。
            put("Time", ToolIdempotencyClass.READ_ONLY)
            put("Random", ToolIdempotencyClass.READ_ONLY)
            put("Regex", ToolIdempotencyClass.READ_ONLY)
            put("Json", ToolIdempotencyClass.READ_ONLY)
            put("ContextRecap", ToolIdempotencyClass.READ_ONLY)
            put("ContextSearch", ToolIdempotencyClass.READ_ONLY)
            put("SessionStats", ToolIdempotencyClass.READ_ONLY)
            put("ImageInfo", ToolIdempotencyClass.READ_ONLY)
            put("GetSetSettings", ToolIdempotencyClass.IDEMPOTENT_WRITE) // put 值幂等
            put("Logcat", ToolIdempotencyClass.READ_ONLY)
            put("Screenshot", ToolIdempotencyClass.READ_ONLY)
            put("Clipboard", ToolIdempotencyClass.NON_IDEMPOTENT)    // 读写混合：写侧污染剪贴板
            put("Calculate", ToolIdempotencyClass.READ_ONLY)
            put("NotificationRead", ToolIdempotencyClass.READ_ONLY)

            // ═══ App 管理 ═══
            put("AppList", ToolIdempotencyClass.READ_ONLY)
            put("AppInfo", ToolIdempotencyClass.READ_ONLY)
            put("AppInstall", ToolIdempotencyClass.IDEMPOTENT_WRITE)  // 安装覆盖（reinstall 语义）
            put("AppUninstall", ToolIdempotencyClass.NON_IDEMPOTENT)  // 卸载不可逆
            put("AppLaunch", ToolIdempotencyClass.IDEMPOTENT_WRITE)   // 单实例启动
            put("AppForceStop", ToolIdempotencyClass.NON_IDEMPOTENT) // 停止有状态应用造成状态丢失

            // ═══ UI 自动化（输入注入类全部 NON_IDEMPOTENT）═══
            put("UiDump", ToolIdempotencyClass.READ_ONLY)
            put("UiTap", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("UiSwipe", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("InputText", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ 设备状态 ═══
            put("brightness", ToolIdempotencyClass.IDEMPOTENT_WRITE)  // 设定值
            put("alarm", ToolIdempotencyClass.NON_IDEMPOTENT)         // 每次新建闹钟
            put("ControlMedia", ToolIdempotencyClass.NON_IDEMPOTENT) // 播放/暂停切换类
            put("GetLocation", ToolIdempotencyClass.READ_ONLY)
            put("battery", ToolIdempotencyClass.READ_ONLY)
            put("network", ToolIdempotencyClass.READ_ONLY)
            put("storage", ToolIdempotencyClass.READ_ONLY)
            put("display", ToolIdempotencyClass.READ_ONLY)
            put("ring", ToolIdempotencyClass.NON_IDEMPOTENT)          // 响铃
            put("music", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("radio", ToolIdempotencyClass.READ_ONLY)
            put("password", ToolIdempotencyClass.READ_ONLY)

            // ═══ Web（读/写分明）═══
            put("WebFetch", ToolIdempotencyClass.READ_ONLY)
            put("WebSearch", ToolIdempotencyClass.READ_ONLY)
            put("HttpRequest", ToolIdempotencyClass.UNKNOWN)         // GET 幂等 POST 非幂等——方法级才可判定

            // ═══ 记忆 / 技能 / MCP / 插件 ═══
            put("MemoryRecentEpisodes", ToolIdempotencyClass.READ_ONLY)
            put("MemorySearchNodes", ToolIdempotencyClass.READ_ONLY)
            put("MemoryRecallMacro", ToolIdempotencyClass.READ_ONLY)
            put("SkillList", ToolIdempotencyClass.READ_ONLY)
            put("SkillSearch", ToolIdempotencyClass.READ_ONLY)
            put("SkillCreate", ToolIdempotencyClass.IDEMPOTENT_WRITE)
            put("SkillInstall", ToolIdempotencyClass.IDEMPOTENT_WRITE)
            put("SkillUninstall", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("McpList", ToolIdempotencyClass.READ_ONLY)
            put("McpConnect", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 连接重试幂等
            put("McpCall", ToolIdempotencyClass.UNKNOWN)             // MCP 服务端任意行为

            // ═══ 用户交互（特殊：恢复时重新提问而非幂等决策）═══
            put("AskUser", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("AskUserChoice", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ Terminal v2（审计 §12 禁区内的工具只做分类，不改其实现）═══
            put("TerminalListLegacy", ToolIdempotencyClass.READ_ONLY)
            put("TerminalReadLegacy", ToolIdempotencyClass.READ_ONLY)
            put("TerminalExecLegacy", ToolIdempotencyClass.UNKNOWN)        // 任意命令
            put("TerminalSendLegacy", ToolIdempotencyClass.NON_IDEMPOTENT) // 发送即执行新命令
            put("TerminalSignalLegacy", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("TerminalCloseLegacy", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ Browser 自动化 ═══
            put("BrowserNavigate", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 重复导航同一 URL 等价
            put("BrowserSnapshot", ToolIdempotencyClass.READ_ONLY)
            put("BrowserShow", ToolIdempotencyClass.READ_ONLY)
            put("BrowserScreenshot", ToolIdempotencyClass.READ_ONLY)
            put("BrowserDebugDump", ToolIdempotencyClass.READ_ONLY)
            put("BrowserNetworkLog", ToolIdempotencyClass.READ_ONLY)
            put("BrowserDownloadList", ToolIdempotencyClass.READ_ONLY)
            put("BrowserContextSummary", ToolIdempotencyClass.READ_ONLY)
            put("BrowserClick", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserInput", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserDateInput", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserSelect", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserScroll", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserToggle", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserFileUpload", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ Browser 自动化（v1.1.0 高级动作 + v1.6 Tabs + v1.7.0 会话工具集）═══
            // 旧表只覆盖到 v1.0 的 16 个工具——v1.1.0 的 8 个高级动作（PressKey/
            // Hover/Drag/ExtractContent/ExecuteJs/GetCookies/Locate/WaitFor）与
            // BrowserTabs 一直落在 UNKNOWN；v1.7.0 一并补齐 34 工具全量口径。
            put("BrowserPressKey", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserHover", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserDrag", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserExtractContent", ToolIdempotencyClass.READ_ONLY)
            put("BrowserExecuteJs", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserGetCookies", ToolIdempotencyClass.READ_ONLY)
            put("BrowserLocate", ToolIdempotencyClass.READ_ONLY)
            put("BrowserWaitFor", ToolIdempotencyClass.READ_ONLY)
            put("BrowserTabs", ToolIdempotencyClass.NON_IDEMPOTENT)
            // v1.7.0 会话工具集
            put("BrowserBack", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserForward", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserReload", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserHistory", ToolIdempotencyClass.READ_ONLY)
            put("BrowserFind", ToolIdempotencyClass.READ_ONLY)
            // BrowserDownloads 含 cancel/retry/forget 变更面（重放会二次取消 /
            // 重复入队新下载 id）——非只读，恢复计划不得自动重放。
            put("BrowserDownloads", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserBookmarks", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("BrowserZoom", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 同值重设等价
            put("BrowserClearData", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 重复清除收敛同态

            // ═══ GitHub（读/写分明）═══
            put("GithubGetUser", ToolIdempotencyClass.READ_ONLY)
            put("GithubListRepos", ToolIdempotencyClass.READ_ONLY)
            put("GithubListIssues", ToolIdempotencyClass.READ_ONLY)
            put("GithubReadFile", ToolIdempotencyClass.READ_ONLY)
            put("GithubSearchCode", ToolIdempotencyClass.READ_ONLY)
            put("GithubWriteFile", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 分支写覆盖
            put("GithubCreateIssue", ToolIdempotencyClass.NON_IDEMPOTENT) // 重发会建重复 issue
        }

        private val USER_INTERACTION_TOOLS = setOf("AskUser", "AskUserChoice")
    }
}
