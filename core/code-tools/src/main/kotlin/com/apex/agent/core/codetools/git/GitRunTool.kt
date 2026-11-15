package com.apex.agent.core.codetools.git

import com.apex.agent.core.codetools.git.policy.GitCommandPolicy
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
 * 凭据：GitHub 已连接时，执行环境自动注入一次性 GIT_ASKPASS 凭据脚本
 * （Git 审计 P0-1 根修：PAT 零进 env，凭据只流向 github.com，见 app 层
 * GithubCredentialIssuer / GithubTerminalEnvInjector）——https 的
 * push/pull 无需手工配置凭据；未连接时 push 会收到 401/凭据报错，
 * 引导用户先在设置里连接 GitHub。
 *
 * 工作目录：恒为当前激活的编码工作区（guest /workspace）。clone 请用
 * `clone <url> <目录名>`（落在工作区内）；跨仓操作先 clone 再 cd 由
 * 工具族自行串接（run 无 cd 语义，用 `git -C <目录> ...` 切仓）。
 *
 * 安全（Git 审计 P0 修复，代码级门禁而非提示词自觉）：
 * - 非交互执行（交互式命令按 **解析后 argv** 位置无关识别，提前拒绝）；
 * - `-c` 注入式配置与 `git config` 子命令默认禁止（credential.helper /
 *   hooksPath 可执行任意程序——P0-2）；
 * - `git -C` 目标词法校验必须落在激活工作区内（P0-8 逃逸面关闭）；
 * - clone/push/fetch 等远程地址仅 https + github.com 白名单，拒绝
 *   file://、ext::、userinfo 内嵌凭据（P0-8 任意 URL clone 关闭）；
 * - `git push --force` / `reset --hard` 等破坏性/外向子命令经
 *   [GitCommandPolicy] 分类后由权限门/风险门**逐次确认**（任何权限档，
 *   含 BYPASS，不提供会话级放行）——HIGH 只是元数据，策略才是门禁。
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
        // Git 审计 P1-12：显式注解（不再依赖 mutating() 默认值）——本工具
        // 可承载破坏性命令（reset --hard / push --force），destructive 必须
        // 如实为 true；非幂等（重放 push 语义不同）。
        annotations(
            com.apex.agent.core.tools.ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = false,
            )
        )
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
        val timeoutSec = parsed.intWithDefault("timeout_sec", DEFAULT_TIMEOUT_SEC)
            .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        // 严格切分：引号未闭合直接拒绝（P0-7——旧实现静默接受，剩余字符
        // 被并进单参数后策略层无从检查）
        val args = when (val split = GitCommandPolicy.parse(raw)) {
            is GitCommandPolicy.ParsedArgs.Ok -> split.args
            is GitCommandPolicy.ParsedArgs.Bad -> return ToolResult.invalid("command", split.reason)
        }
        if (args.isEmpty()) {
            return ToolResult.invalid("command", "command 解析后为空")
        }
        // ═══ 策略门（Git 审计 P0-2/7/8）：分类纯函数，命中即拒绝 ═══
        GitCommandPolicy.configEscapeReason(args)?.let {
            return ToolResult.invalid("command", "git $raw 被拒绝：$it")
        }
        GitCommandPolicy.interactiveReason(args)?.let {
            return ToolResult.invalid("command", it)
        }
        GitCommandPolicy.workspaceEscapeReason(args)?.let {
            return ToolResult.invalid("command", "git $raw 被拒绝：$it")
        }
        GitCommandPolicy.urlViolationReason(args)?.let {
            return ToolResult.invalid("command", "git $raw 被拒绝：$it")
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
