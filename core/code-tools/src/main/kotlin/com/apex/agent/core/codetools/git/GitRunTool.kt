package com.apex.agent.core.codetools.git

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # CodeGitRun — 任意 git 命令执行（经 PRoot Ubuntu 沙箱）
 *
 * 五件套（status/diff/log/commit/branch）之外的长尾通道：clone / pull /
 * push / fetch / remote / rebase / tag / stash / merge…… 一切 git 子命令
 * 直达。GitHub 任务（提交、推送、开分支）**应该走 git 命令完成**，而不是
 * 用浏览器自动化去点网页——本工具就是那条通路。
 *
 * 凭据：GitHub 已连接时，执行环境自动注入 GH_TOKEN / GITHUB_TOKEN 与
 * git 凭据桥（credential.helper=!gh auth git，见 app 层
 * GithubTerminalEnvInjector）——https 的 push/pull 无需手工配置凭据；
 * 未连接时 push 会收到 401/凭据报错，引导用户先在设置里连接 GitHub。
 *
 * 工作目录：恒为当前激活的编码工作区（guest /workspace）。clone 请用
 * `clone <url> <目录名>`（落在工作区内）；跨仓操作先 clone 再 cd 由
 * 工具族自行串接（run 无 cd 语义，用 `git -C <目录> ...` 切仓）。
 *
 * 安全：非交互执行（push/pull 不会弹终端提示，凭据全走 env 桥）；
 * `git push --force` / `reset --hard` 等破坏性子命令按字面执行——
 * 风险分级 HIGH，输出如实回报。交互式命令（如 `git add -p`）不可用。
 */
class GitRunTool(
    private val runner: GitCommandRunner
) : BaseTool(
    id = "CodeGitRun",
    name = "Code Git Run",
    description = """
        Run ANY git command in the active workspace (PRoot sandbox, git preinstalled).

        This is the primary path for GitHub tasks: clone, commit, push, pull,
        branch, tag, rebase... Use it INSTEAD of browser automation for repo
        work. When GitHub is connected, credentials (token bridge) are injected
        automatically — https push/pull just works; otherwise push fails with
        an auth error (guide the user to connect GitHub in Settings).

        - command: git arguments WITHOUT the "git" prefix, e.g.
          "clone https://github.com/o/r.git mydir" / "push origin main" /
          "-C mydir pull" (use -C <dir> instead of cd for other repos)
        - timeout_sec: 5..600 (default 60; raise for clone/push of big repos)

        Interactive commands (git add -p) are not supported (non-interactive).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "command",
            description = "Git arguments without the git prefix, e.g. \"push origin main\"",
        )
        integer(
            "timeout_sec",
            description = "Timeout seconds 5..600, default 60",
            defaultValue = 60,
            minimum = 5.0,
            maximum = 600.0,
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(com.apex.agent.core.tools.ToolCategory.FILE)
        // HIGH：任意子命令含 push --force / reset --hard 等不可逆外向操作
        risk(com.apex.agent.core.tools.ToolRisk.HIGH)
        tag("code")
        tag("git")
        tag("shell")
        annotations(com.apex.agent.core.tools.ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val parsed = when (val p = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> p.args
            is ToolArguments.ParseOutcome.Bad -> return p.result
        }
        val raw = parsed.optionalString("command")?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ToolResult.invalid("command", "command 不能为空（git 参数，如 \"push origin main\"）")
        }
        if (raw.startsWith("git ") || raw == "git") {
            return ToolResult.invalid(
                "command",
                "command 不要带 git 前缀（本工具自动补上）；如 \"push origin main\" 而非 \"git push origin main\"",
            )
        }
        // 交互式子命令白名单前置拒绝：非交互环境里它们会挂起到超时，提前给出可操作提示
        val interactiveHit = INTERACTIVE_HINTS.firstOrNull { raw.startsWith(it) }
        if (interactiveHit != null) {
            return ToolResult.invalid(
                "command",
                "git $raw 是交互式命令，非交互环境不可用。替代：git add -A 全量暂存；" +
                    "分段提交用多次 add <path> + commit；rebase 冲突走 edit 后 git rebase --continue。",
            )
        }
        val timeoutSec = parsed.intWithDefault("timeout_sec", DEFAULT_TIMEOUT_SEC)
            .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        val args = splitGitArgs(raw)
        if (args.isEmpty()) {
            return ToolResult.invalid("command", "command 解析后为空")
        }
        val result = runner.run(args, timeoutMs = timeoutSec * 1_000L)
        return when {
            result.indicatesNotRepo() -> ToolResult.fail(
                com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
                notRepoGuidance(),
            )
            result.success -> ToolResult.ok(
                boundedToolOutput(
                    buildString {
                        append("✅ git ${args.firstOrNull()} 完成（exit 0）")
                        if (result.stdout.isNotBlank()) {
                            appendLine()
                            append(result.stdout.trim())
                        }
                        // push/pull 的进度在 stderr（git 惯例），成功时也带出尾部
                        if (result.stderr.isNotBlank() && args.firstOrNull() in REMOTE_OPS) {
                            appendLine()
                            append(result.stderr.trim().take(600))
                        }
                    }.trim(),
                ),
            )
            else -> ToolResult.fail(
                com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
                boundedToolOutput(
                    "❌ git ${args.firstOrNull()} 失败（exit ${result.exitCode}）\n${result.stderr.trim().ifBlank { result.stdout.trim() }}",
                ),
            )
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SEC = 60
        const val MIN_TIMEOUT_SEC = 5
        const val MAX_TIMEOUT_SEC = 600

        /** 交互式子命令前缀（挂起到超时的主因，提前拒绝并给替代方案）。 */
        val INTERACTIVE_HINTS = listOf("add -p", "add --patch", "commit -p", "rebase -i", "checkout -p", "restore -p", "clean -i")

        /** 远程操作的进度输出在 stderr（成功时也附带尾部供确认）。 */
        val REMOTE_OPS = setOf("push", "pull", "fetch", "clone", "remote")
    }
}

/**
 * 轻量引号感知切分：双/单引号包住的段保持原样（不含引号），其余按空白切。
 * 覆盖 git 参数的主流量纲（分支名带空格、含引号的 commit message 走
 * CodeGitCommit 的专用参数，不经本切分）。
 */
internal fun splitGitArgs(raw: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var quote: Char? = null
    fun flush() {
        if (sb.isNotEmpty()) {
            out += sb.toString()
            sb.clear()
        }
    }
    for (c in raw) {
        when {
            quote != null && c == quote -> {
                quote = null
                // 引号内空段（""）保留为空参数
                if (sb.isEmpty()) out += ""
            }
            quote == null && (c == '"' || c == '\'') -> {
                flush()
                quote = c
            }
            quote == null && c.isWhitespace() -> flush()
            else -> sb.append(c)
        }
    }
    flush()
    return out
}
