package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.tools.*
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.platform.terminal.tools.v2.*
import com.apex.agent.tools.ToolAuditLogger

/**
 * §10 前排 PTY 工具 + §11 GitHub 工具的注册体 —— 自 ToolModule.kt 二次
 * 拆分（后续 PR 对主文件的增量叠加会把行数再次顶破 1200 预算，预留余量；
 * 两族各只依赖单一执行器参数，边界天然清晰，注册内容逐字节原样迁移）。
 */

/** §10 前排：9 个 Agent-Native PTY 工具 + T73 后端发现（纯 [terminalRuntime] 依赖）。
 * T-audit（#289）：signal/run/close/create/write/resize/snapshot 七个通道包
 * [auditedTerminalTool] 审计（与 ShellExecute 同一 #F-⑯ 标准，审计逻辑随拆分迁入）；
 * observe/wait 纯只读探针不记（审计噪声换取可举证性已足）。 */
internal fun registerTerminalPtyTools(
    registry: DefaultToolRegistry,
    terminalRuntime: TerminalRuntime,
    toolAuditLogger: ToolAuditLogger
) {
    // ═══ 10. Terminal PTY — ATR 2.0 (9 new Agent-Native + 4 legacy compat + T73 ×2) ═══
    // 9 new Agent-Native tools (Spec §34) — non-blocking, incremental, event-driven.
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalCreateTool(terminalRuntime)))))
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalRunTool(terminalRuntime)))))
    registry.register(SafeAgentTool(TerminalToolAdapter(TerminalObserveTool(terminalRuntime))))
    registry.register(SafeAgentTool(TerminalToolAdapter(TerminalWaitTool(terminalRuntime))))
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalWriteTool(terminalRuntime)))))
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalSignalTool(terminalRuntime)))))
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalResizeTool(terminalRuntime)))))
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalSnapshotTool(terminalRuntime)))))
    registry.register(SafeAgentTool(TerminalToolAdapter(auditedTerminalTool(toolAuditLogger, TerminalCloseTool(terminalRuntime)))))
    // T73: 后端能力发现 + Ubuntu rootfs 安装引导（Agent 自主进入 Ubuntu 的入口）。
    registry.register(SafeAgentTool(TerminalToolAdapter(TerminalBackendsTool(terminalRuntime))))
}

/** §11：GitHub 9 工具（无条件注册 —— 运行时连接状态由 GithubApiService 报错语义兜底）。 */
internal fun registerGithubTools(
    registry: DefaultToolRegistry,
    githubApiService: GithubApiService
) {
    // ═══ 11. GitHub (7，无条件注册) ═══
    // P2-11（6-c）：原以 githubTokenManager.isConnected() 条件注册——Token 是
    // 运行时状态而注册表是启动期快照，先连 Token 也需重启 App 才生效（死开关）。
    // 无条件注册；未连接时 GithubApiService.authHeader() 抛
    // "未连接 GitHub，请先配置 Token"，SafeAgentTool 兜底转错误串，Agent 可感知并引导用户连接。
    registry.register(SafeAgentTool(GithubGetUserTool(githubApiService)))
    registry.register(SafeAgentTool(GithubListReposTool(githubApiService)))
    registry.register(SafeAgentTool(GithubReadFileTool(githubApiService)))
    registry.register(SafeAgentTool(GithubWriteFileTool(githubApiService)))
    registry.register(SafeAgentTool(GithubCreateIssueTool(githubApiService)))
    registry.register(SafeAgentTool(GithubListIssuesTool(githubApiService)))
    registry.register(SafeAgentTool(GithubSearchCodeTool(githubApiService)))
    // 分支列表（写入非默认分支前探查）与仓库搜索（按关键词找仓库）。
    // 根因修复补齐：searchCode 只能搜代码，找仓库需 /search/repositories。
    registry.register(SafeAgentTool(GithubListBranchesTool(githubApiService)))
    registry.register(SafeAgentTool(GithubSearchReposTool(githubApiService)))
    // 建 PR（POST /pulls）：git 命令通路（CodeGitRun push 分支）之后的最后一环，
    // 「clone → 改 → commit → push → PR」全链路不走浏览器。
    registry.register(SafeAgentTool(GithubCreatePullRequestTool(githubApiService)))
}

private fun auditedTerminalTool(
    audit: ToolAuditLogger,
    tool: com.apex.agent.platform.terminal.tools.TerminalTool
): com.apex.agent.platform.terminal.tools.TerminalTool =
    object : com.apex.agent.platform.terminal.tools.TerminalTool by tool {
        override suspend fun invoke(arguments: String): String {
            val startedAt = System.currentTimeMillis()
            val result = runCatching { tool.invoke(arguments) }
            runCatching {
                audit.log(
                    ToolAuditLogger.Event(
                        tool = tool.id,
                        decision = if (result.isSuccess) "executed" else "failed",
                        command = arguments.take(512),
                        durationMs = System.currentTimeMillis() - startedAt,
                        success = result.isSuccess
                    )
                )
            }
            return result.getOrThrow()
        }
    }
