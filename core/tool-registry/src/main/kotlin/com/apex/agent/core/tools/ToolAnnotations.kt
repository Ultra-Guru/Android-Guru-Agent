package com.apex.agent.core.tools

/**
 * # Tool System v3 — MCP-Aligned Tool Annotations
 *
 * Industry research (MCP spec 2025-06-18, AutoGPT Block schema, Anthropic
 * computer-use docs) converges on the same vocabulary for describing what a
 * tool *does* before running it. v2 has a coarse [ToolRisk] (LOW/MEDIUM/HIGH)
 * that answers "should we ask the user?" — but the four orthogonal questions
 * below stay tangled together:
 *
 *  - can it mutate state at all?            (readOnlyHint)
 *  - is the mutation destructive/irreversible? (destructiveHint)
 *  - is repeating the same call safe?       (idempotentHint)
 *  - does it touch the outside world?       (openWorldHint)
 *
 * Why each answer matters *independently* of risk:
 *
 *  - **Retry safety** — the v3 executor retries transient failures. An
 *    idempotent tool can be retried blindly; a non-idempotent one must not
 *    (retrying `AppUninstall` after a "timeout" that actually succeeded is
 *    data loss). `idempotentHint` drives [ToolRunPolicy.maxRetries].
 *  - **Batch replay safety** — [ToolBatchRunner] reports halt causes; a
 *    destructive step that already ran must never be re-run from scratch.
 *  - **Gate copy** — "this tool permanently deletes" is a better consent
 *    prompt than "risk: HIGH".
 *  - **Prompt honesty** — openWorld tools may return arbitrary web content;
 *    the model is told to treat their output as untrusted (prompt-injection
 *    hygiene, per MCP spec security notes).
 *
 * Per the MCP spec these are *hints, not guarantees* — a tool MAY lie and
 * the executor never treats them as a security boundary (the sandbox and
 * gate remain the boundary). They are decision inputs, the same way
 * [ToolMetadata.risk] is.
 *
 * Naming note: the MCP client-hint names (`readOnlyHint` …) are kept
 * verbatim so the mapping to the spec is auditable; `sensitiveAction`
 * follows AutoGPT's Block flag (human-in-the-loop review), which the app
 * surfaces through the existing [RiskAwareToolGate] confirm dialog.
 */
data class ToolAnnotations(
    /**
     * If true, the tool does not mutate its environment (files, device
     * state, network side effects). Read-only tools are safe to call
     * speculatively — the executor may retry them and the batch runner may
     * re-run them without asking.
     */
    val readOnlyHint: Boolean = false,

    /**
     * If true, the tool's effects are destructive/irreversible when they
     * succeed (delete, uninstall, force-stop, overwrite). When the tool
     * *fails*, effects are unspecified. Drives consent copy and disables
     * auto-retry unless the tool is also idempotent.
     */
    val destructiveHint: Boolean = true,

    /**
     * If true, calling the tool repeatedly with the same arguments leaves
     * the environment in the same state as calling it once (mkdirs, "put"
     * settings, whole-file overwrite). Safe for blind retry; the primary
     * input to [ToolRunPolicy] retry budgeting.
     */
    val idempotentHint: Boolean = false,

    /**
     * If true, the tool interacts with an open world beyond the app sandbox
     * (web fetch, MCP servers, GitHub API). Its output must be treated as
     * untrusted content — never as instructions to the agent.
     */
    val openWorldHint: Boolean = false,

    /**
     * If true, the tool performs an action the user should explicitly
     * sanction each session (AutoGPT semantics). The app's risk gate maps
     * this to a human-in-the-loop confirm; a *sensitive* read (e.g. reading
     * notifications) is not destructive but still deserves a prompt.
     */
    val sensitiveAction: Boolean = false
) {

    /**
     * Whether the v3 executor may automatically retry this tool when a
     * transient error occurs (timeout / I/O flake).
     *
     * Rules, in order:
     * 1. Read-only tools are always retry-safe — a repeated read has no
     *    side effects, worst case we burn one extra call.
     * 2. Idempotent writes are retry-safe — replay converges.
     * 3. Destructive non-idempotent tools are never blindly retried: a
     *    "timeout" might have succeeded server-side; replaying deletes twice.
     */
    val retrySafe: Boolean
        get() = readOnlyHint || idempotentHint

    /** Compact one-line rendering for reports: `ro idem !destr open sens`. */
    fun summary(): String = buildString {
        if (readOnlyHint) append("ro ")
        if (idempotentHint) append("idem ")
        if (destructiveHint && !readOnlyHint) append("destr ")
        if (openWorldHint) append("open ")
        if (sensitiveAction) append("sensitive")
        if (isEmpty()) append("mutating")
    }.trim()

    companion object {

        /** Sensible defaults for a pure read (query/list/dump/get). */
        @JvmStatic
        fun readOnly(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true
        )

        /** Defaults for an overwrite-style write (whole-file, settings put). */
        @JvmStatic
        fun idempotentWrite(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true
        )

        /** Defaults for an append/patch-style write (Edit, clipboard). */
        @JvmStatic
        fun mutating(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false
        )

        /** Defaults for a destructive one-shot action (delete, uninstall). */
        @JvmStatic
        fun destructive(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false
        )

        /** Defaults for a network/open-world read (fetch, search, MCP). */
        @JvmStatic
        fun openWorldRead(): ToolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true
        )

        /**
         * Infer annotations from id + risk — the v2 zero-migration path.
         *
         * Every v1/v2 tool gets annotations without touching its code; tools
         * that opt in via [ToolMetadata.Builder.annotations] override the
         * inference. Families mirror the curated table in the engine's
         * recovery journal (T76 idempotency registry) so the two layers
         * agree by construction on the common 80 ids.
         */
        @JvmStatic
        fun infer(toolId: String, risk: ToolRisk): ToolAnnotations = when {
            // ── Read-only families (deterministic queries) ──
            toolId == "GetTimeLegacy" || toolId == "GetDeviceInfo" ||
                toolId == "AppList" || toolId == "AppInfo" ||
                toolId == "UiDump" || toolId == "Logcat" ||
                toolId == "NotificationRead" ||
                toolId == "ListFiles" || toolId == "GlobFiles" ||
                toolId == "SearchFiles" || toolId == "Read" ||
                // #171/#172：合并工具与上下文回顾三件套 / 图片元信息 ——
                // 纯确定性计算或会话只读视图（显式声明缺位时的兜底口径）。
                toolId == "Time" || toolId == "ImageInfo" ||
                toolId == "ContextRecap" || toolId == "ContextSearch" ||
                toolId == "SessionStats" ||
                // v5：agent 自省 / 通讯录 / 日历 读类（只读，sensitiveAction 由
                // 工具自身显式声明覆盖）。
                toolId == "AgentSettingGet" || toolId == "AgentProfileList" ||
                toolId == "AgentRoleList" || toolId == "AgentModePresetList" ||
                toolId.startsWith("Memory") ||
                toolId.startsWith("TerminalLinuxStatus") ||
                toolId.startsWith("TerminalUbuntuStatus") ||
                toolId.startsWith("TerminalSnapshot") ||
                toolId.startsWith("TerminalObserve") ||
                toolId.startsWith("TerminalBackends") ||
                (toolId.startsWith("TerminalWorkspaces") && toolId.endsWith("list")) -> readOnly()

            // ── Open-world reads (web / MCP / GitHub) ──
            toolId == "WebFetch" || toolId == "WebSearch" ||
                toolId == "HttpRequest" ||
                toolId == "McpList" || toolId == "McpConnect" ||
                (toolId.startsWith("Github") && (
                    toolId.startsWith("GithubGet") ||
                        toolId.startsWith("GithubRead") ||
                        toolId.startsWith("GithubSearch") ||
                        toolId.startsWith("GithubList")
                    )) -> openWorldRead()

            // ── Destructive one-shots ──
            toolId == "DeleteFile" || toolId == "AppUninstall" ||
                toolId == "AppForceStop" ||
                toolId.startsWith("TerminalClose") || toolId.startsWith("TerminalSignal") ||
                toolId.startsWith("SkillUninstall") -> destructive()

            // ── Idempotent overwrite writes ──
            toolId == "Write" || toolId == "DownloadFile" ||
                toolId == "AppLaunch" || toolId == "AppInstall" ||
                // v5：通知发布 / 角色激活 / 设置应用 / 预设选择 / provider key 写入
                // 均为覆盖式幂等写（同参数重放不产生新状态）。
                toolId == "NotificationPost" || toolId == "AgentRoleActivate" ||
                toolId == "AgentSettingSet" || toolId == "AgentProfileSetDefault" ||
                toolId == "AgentModePresetSelect" || toolId == "AgentProviderSetKey" ||
                toolId.startsWith("TerminalWrite") ||
                toolId.startsWith("TerminalResize") -> idempotentWrite()

            // ── Mutating, non-idempotent ──
            toolId == "Edit" || toolId == "CopyMoveFile" ||
                toolId == "InputText" || toolId == "UiTap" || toolId == "UiSwipe" ||
                toolId == "Clipboard" || toolId == "Screenshot" -> mutating()

            // ── 浏览器敏感动作（v1.5）──
            // 三者均已升 HIGH（ToolMetadata.inferRisk），但注解语义各异、
            // 不能共用 destructive() 兜底：敏感 ≠ 破坏。共同点是
            // sensitiveAction=true（AutoGPT 语义：会话内需用户明示同意），
            // PermissionModeGate DEFAULT 模式即触发授权询问，与
            // RiskAwareToolGate 的 HIGH 弹窗构成双通道（AllowExplicit 去重，
            // 不双弹窗）。
            //   execute_js：任意 JS = 能力不可枚举的页面内变更（非幂等）；
            //   get_cookies：敏感读（会话凭据），readOnly 但 sensitiveAction
            //     —— 与「读通讯录」同款：读也该问；
            //   file_upload：本地文件外发到网页 = openWorld 出境写。
            toolId == "BrowserExecuteJs" -> ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = false,
                sensitiveAction = true
            )
            toolId == "BrowserGetCookies" -> ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = false,
                sensitiveAction = true
            )
            toolId == "BrowserFileUpload" -> ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true,
                sensitiveAction = true
            )
            // v1.7.0：清除浏览数据——唯一 destructive=true 的浏览器工具（清
            // Cookie = 全站点登出，不可逆）；幂等（重复清除收敛同态）但敏感，
            // 与三件套同一「需用户明示同意」通道。
            toolId == "BrowserClearData" -> ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = true,
                openWorldHint = false,
                sensitiveAction = true
            )

            // ── Risk-level fallback for unknown/dynamic ids ──
            else -> when (risk) {
                ToolRisk.LOW -> readOnly()
                ToolRisk.MEDIUM -> mutating()
                ToolRisk.HIGH -> destructive()
            }
        }
    }
}
