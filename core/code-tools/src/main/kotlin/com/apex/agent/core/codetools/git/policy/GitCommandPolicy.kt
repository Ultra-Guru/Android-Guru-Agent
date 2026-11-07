package com.apex.agent.core.codetools.git.policy

/**
 * # GitCommandPolicy — git 命令静态分类与门禁（Git 审计 P0 修复）
 *
 * Git 审计（2026-11）P0-2/5/6/7/8 的代码级根修：把「破坏性/外向/配置逃逸
 * 命令的识别」从提示词自觉与字符串黑名单，搬到**解析后 argv 的纯函数分类**。
 * 三类消费方共用同一事实源：
 *
 * - [GitRunTool][com.apex.agent.core.codetools.git.GitRunTool]（执行前硬门）：
 *   CONFIG_ESCAPE / 交互式 / 工作区逃逸 / URL 白名单违规直接拒绝；
 * - 权限门 + 风险门（app 层）：按 [GitActionClass] 决定直通 / 确认一次 /
 *   **逐次确认不可会话放行** / 硬拒绝——任何权限档（含 BYPASS）下
 *   DESTRUCTIVE 与 OUTBOUND 都必须逐次确认；
 * - 命令级高危表（CommandPermissionGate，终端自由命令防线）：同等语义。
 *
 * 设计约束：
 * - 纯 JVM、零 IO、零 Android 依赖——分类是同输入同输出的纯函数，可在
 *   JVM 单测里穷举矩阵（Git 审计 3.10 测试计划第 1 组）；
 * - 输入是**已切分的 argv**（不含 `git` 本身）——字符串级 `startsWith`
 *   启发式（P0-7 的击穿点：`add  -p` 双空格 / `add\t-p` 全部放行）就此废除；
 * - 静态可判定性诚实边界：`checkout <path>` 丢弃式 vs `checkout <branch>`
 *   切换式无法静态区分（需仓库状态），热修版按 LOCAL_WRITE 放行并在此
 *   声明残余风险；完整版（仓库状态感知）按审计 Spec 3.12 落 P1 期。
 */
enum class GitActionClass {
    /** 只读：status / diff / log / show / branch --list / remote -v … */
    READ,

    /** 本地写：add / commit / switch / merge / tag / stash push … */
    LOCAL_WRITE,

    /**
     * 破坏性：reset --hard / clean -f* / branch -D / push --force* /
     * reflog expire / stash drop/clear …——任何权限档**逐次确认**，
     * 不提供「本会话允许」。
     */
    DESTRUCTIVE,

    /** 外向：push / fetch / pull / clone / remote set-url / submodule update … */
    OUTBOUND,

    /**
     * 配置逃逸：`-c` 注入式配置 / `config` 子命令——credential.helper、
     * core.hooksPath、core.pager 等可在「不经 shell」的前提下执行任意
     * 程序（P0-2）。默认**硬拒绝**（所有权限档），无设置开关。
     */
    CONFIG_ESCAPE,

    /** 未识别子命令 / 未识别全局选项 / 外部执行语义（difftool、foreach …）。 */
    ARBITRARY,
}

object GitCommandPolicy {

    /** 承载任意 git 子命令的工具 id（权限门/风险门按此识别需要分类的调用）。 */
    const val RUN_TOOL_ID = "CodeGitRun"

    /** guest 沙箱内激活工作区的恒定挂载点（ProotGitCommandRunner 同源常量）。 */
    const val GUEST_WORKSPACE = "/workspace"

    /**
     * 远程 URL 主机白名单（审计 3.3：默认 github.com；企业域留待设置项，
     * 热修版为编译期常量——`file://` / `ext::` / `ssh://` / 任意主机 /
     * userinfo 内嵌凭据全部拒绝）。
     */
    val ALLOWED_REMOTE_HOSTS = setOf("github.com")

    // ══════════════════════════════════════════════════════════════════
    //  参数切分（严格版）
    // ══════════════════════════════════════════════════════════════════

    /** argv 切分结果：[Ok]（含引号感知的参数序列）或 [Bad]（不可解析）。 */
    sealed class ParsedArgs {
        data class Ok(val args: List<String>) : ParsedArgs()
        data class Bad(val reason: String) : ParsedArgs()
    }

    /**
     * 严格切分：双/单引号包住的段保持原样（不含引号），其余按空白切；
     * 引号内空段（`""`）保留为空参数。与旧 [splitGitArgsLenient] 的唯一
     * 差别：**引号未闭合直接判 Bad**（P0-7：旧实现静默接受未闭合引号，
     * 剩余全部字符被并进一个参数——策略层无法再按 argv 语义检查）。
     */
    fun parse(raw: String): ParsedArgs {
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
        if (quote != null) {
            return ParsedArgs.Bad("命令引号未闭合（$quote）——请补全引号后重试")
        }
        flush()
        return ParsedArgs.Ok(out)
    }

    /** 旧语义的宽容切分（未闭合引号按字面并入最后一个参数）——仅存量测试兼容用。 */
    fun splitGitArgsLenient(raw: String): List<String> {
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

    // ══════════════════════════════════════════════════════════════════
    //  分类主入口
    // ══════════════════════════════════════════════════════════════════

    /**
     * 分类：argv（不含 `git` 本身）→ [GitActionClass]。
     *
     * 求值顺序：CONFIG_ESCAPE（全局区 `-c` / `config` 子命令）→ 全局选项
     * 白名单扫描 → 子命令模式分发。破坏性标记（reset --hard / push -f …）
     * 在子命令模式内基于完整参数扫描，位置无关。
     */
    fun classify(args: List<String>): GitActionClass {
        if (args.isEmpty()) return GitActionClass.ARBITRARY
        configEscapeReason(args)?.let { return GitActionClass.CONFIG_ESCAPE }
        var i = 0
        while (i < args.size) {
            val t = args[i]
            when {
                t == "--" -> return GitActionClass.ARBITRARY
                t == "-h" || t == "--help" || t == "--version" -> return GitActionClass.READ
                t == "-C" -> { i += 2; continue }
                t.startsWith("-C") && t.length > 2 -> { i += 1; continue }
                // 位置重定向类全局选项（--git-dir/--work-tree/…）：静态无法
                // 验证其指向，保守归入逐次确认
                t in GLOBAL_FLAGS_WITH_VALUE || REDIRECT_GLOBAL_PREFIXES.any { t.startsWith(it) } ->
                    return GitActionClass.ARBITRARY
                t.startsWith("-") -> return GitActionClass.ARBITRARY
                else -> return classifySub(t, args.subList(i + 1, args.size))
            }
        }
        return GitActionClass.READ
    }

    /** 带独立取值的 git 全局选项（出现即 ARBITRARY——静态无法判定语义）。 */
    private val GLOBAL_FLAGS_WITH_VALUE = setOf(
        "--git-dir", "--work-tree", "--namespace", "--exec-path", "--super-prefix",
    )

    /** `=` 连体形态的位置重定向全局选项前缀。 */
    private val REDIRECT_GLOBAL_PREFIXES = listOf(
        "--git-dir=", "--work-tree=", "--namespace=", "--exec-path=", "--super-prefix=",
    )

    // ══════════════════════════════════════════════════════════════════
    //  配置逃逸（P0-2）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 配置逃逸检测：只扫**子命令之前的全局区**（`git commit -c HEAD` 的
     * 复用消息参数不误伤；`-c` 是 git 唯一以 `-c` 开头的全局选项，连体
     * 形态 `-c<key>=<val>` 同样命中）。命中返回面向模型的拒绝原因。
     */
    fun configEscapeReason(args: List<String>): String? {
        var i = 0
        while (i < args.size) {
            val t = args[i]
            when {
                t == "-C" -> { i += 2; continue }
                t.startsWith("-C") && t.length > 2 -> { i += 1; continue }
                t == "-c" -> return (
                    "-c 注入式配置默认禁止：credential.helper / core.hooksPath / core.pager 等 " +
                        "可在不经 shell 的前提下执行任意程序。请改用 CodeGitCommit 等专用工具，" +
                        "或在 Agent 之外的终端手工执行"
                    )
                t.startsWith("-c") && t.length > 2 ->
                    return "-c 连体注入式配置（${t.take(24)}…）默认禁止——理由同 -c"
                t == "config" -> return (
                    "git config 子命令默认禁止（App 不写全局/仓库配置的承诺）。" +
                        "提交身份请在 Coding 屏 GitHub 设置里维护，或用专用工具完成"
                    )
                !t.startsWith("-") -> return null // 到达子命令——全局区结束
                else -> { i += 1 }
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  交互式（P0-7：argv 位置无关，废除字符串 startsWith）
    // ══════════════════════════════════════════════════════════════════

    private val INTERACTIVE_FLAGS = setOf("-p", "--patch", "-i", "--interactive", "-e", "--edit")

    private val INTERACTIVE_CAPABLE = setOf(
        "add", "commit", "checkout", "restore", "clean", "stash", "rebase",
    )

    /**
     * 交互式命令检测：子命令属于交互式敏感族**且**任意位置出现交互式
     * 旗标（`--` pathspec 分隔符之后的路径参数不参与扫描）。非交互环境
     * 里它们会挂起到超时，调用方应提前拒绝并给替代方案。
     */
    fun interactiveReason(args: List<String>): String? {
        if (args.isEmpty()) return null
        val sub = args.firstOrNull { !it.startsWith("-") } ?: return null
        if (sub !in INTERACTIVE_CAPABLE) return null
        val rest = args.subList(args.indexOf(sub) + 1, args.size)
        for (t in beforePathspecSeparator(rest)) {
            if (t in INTERACTIVE_FLAGS) {
                return "git $sub ${t}是交互式命令，非交互环境不可用。替代：git add -A 全量暂存；" +
                    "分段提交用多次 add <path> + commit；rebase 冲突走 edit 后 git rebase --continue"
            }
        }
        return null
    }

    /** `--` pathspec 分隔符之前的 token（之后的都是路径，不参与旗标扫描）。 */
    private fun beforePathspecSeparator(tokens: List<String>): List<String> {
        val cut = tokens.indexOf("--")
        return if (cut >= 0) tokens.subList(0, cut) else tokens
    }

    // ══════════════════════════════════════════════════════════════════
    //  工作区逃逸（P0-8 之 -C）
    // ══════════════════════════════════════════════════════════════════

    /**
     * `git -C <dir>` 路径校验：目标必须词法解析后落在 [GUEST_WORKSPACE]
     * 内（相对路径按 /workspace 起算；`..` 逃逸、绝对路径指向工作区外、
     * `-C` 缺参一律拒绝）。symlink 逃逸静态不可判定，为声明过的残余风险。
     */
    fun workspaceEscapeReason(args: List<String>): String? {
        var i = 0
        while (i < args.size) {
            val t = args[i]
            if (t == "-C") {
                val dir = args.getOrNull(i + 1)
                    ?: return "-C 缺少目录参数"
                return validateWorkspacePath(dir)
            }
            if (t.startsWith("-C") && t.length > 2) {
                return validateWorkspacePath(t.substring(2))
            }
            i += 1
        }
        return null
    }

    private fun validateWorkspacePath(dir: String): String? {
        if (dir.isBlank()) return "-C 目录参数为空"
        val absolute = if (dir.startsWith("/")) dir else "$GUEST_WORKSPACE/$dir"
        val normalized = lexicalNormalize(absolute)
        if (normalized != GUEST_WORKSPACE && !normalized.startsWith("$GUEST_WORKSPACE/")) {
            return "git -C 目标（$dir）逃逸出激活工作区——跨仓操作请先 clone 到工作区内再操作"
        }
        return null
    }

    /** 词法归一化（纯字符串，处理 `.`/`..`/重复分隔符；不碰文件系统）。 */
    private fun lexicalNormalize(path: String): String {
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    // ══════════════════════════════════════════════════════════════════
    //  远程 URL 白名单（P0-8 之 clone/push URL）
    // ══════════════════════════════════════════════════════════════════

    /** 参数里可能出现远程 URL 的子命令（其余命令不做 URL 扫描，防误伤路径）。 */
    private val URL_SCANNED_COMMANDS = setOf(
        "clone", "push", "pull", "fetch", "ls-remote", "send-pack", "remote", "submodule", "archive",
    )

    /**
     * 远程 URL 白名单：OUTBOUND 族命令的参数中出现 `scheme://` 或 `::`
     * 传输形态时，scheme 必须为 https、host 必须在 [ALLOWED_REMOTE_HOSTS]
     * （默认 github.com）、禁止 userinfo 内嵌凭据。远程名（origin）与
     * refspec（`HEAD:refs/heads/x`）不受影响。
     */
    fun urlViolationReason(args: List<String>): String? {
        if (args.isEmpty()) return null
        val sub = args.firstOrNull { !it.startsWith("-") } ?: return null
        if (sub !in URL_SCANNED_COMMANDS) return null
        val rest = args.subList(args.indexOf(sub) + 1, args.size)
        for (t in beforePathspecSeparator(rest)) {
            when {
                t.contains("://") -> validateRemoteUrl(t)?.let { return it }
                t.contains("::") && !t.startsWith("-") ->
                    return "仅支持 https:// 的远程地址（收到传输形态 ${t.take(32)}…）：" +
                        "ext::/ssh:: 等本地传输默认禁止"
                else -> {}
            }
        }
        return null
    }

    private fun validateRemoteUrl(token: String): String? {
        val scheme = token.substringBefore("://")
        if (scheme != "https") {
            return "仅支持 https:// 的 github.com 远程地址（收到 $scheme://）——" +
                "file://、ssh://、http:// 明文与任意主机默认禁止"
        }
        val after = token.substringAfter("://")
        val hostPort = after.substringBefore('/')
        if (hostPort.contains('@')) {
            return "URL 内嵌凭据（userinfo）禁止——凭据由 App 按命令最小化签发，" +
                "请使用 https://github.com/owner/repo.git 形态"
        }
        val host = hostPort.substringBefore(':')
        if (host !in ALLOWED_REMOTE_HOSTS) {
            return "远程主机 $host 不在白名单（默认 github.com）——" +
                "企业 Git 服务器暂不支持（列入后续版本）"
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  子命令模式分发
    // ══════════════════════════════════════════════════════════════════

    private val READ_COMMANDS = setOf(
        "status", "diff", "log", "show", "whatchanged", "blame", "shortlog", "describe",
        "grep", "cat-file", "rev-parse", "rev-list", "count-objects", "verify-pack",
        "verify-commit", "verify-tag", "ls-files", "ls-tree", "for-each-ref", "help",
        "version", "diff-files", "diff-index", "diff-tree", "symbolic-ref",
        "check-ref-format", "check-ignore", "check-attr", "var", "merge-base", "fsck",
        "peak", "bugreport", "maintenance", "rerere",
    )

    private fun classifySub(sub: String, rest: List<String>): GitActionClass {
        val flags = beforePathspecSeparator(rest)
        val hasPathspecSeparator = rest.contains("--")
        val firstPositional = flags.firstOrNull { !it.startsWith("-") }
        return when (sub) {
            in READ_COMMANDS -> GitActionClass.READ

            // ═══ 外向族 ═══
            "push" -> when {
                flags.any { it.startsWith("--force") || it == "--delete" || (it.startsWith("-") && !it.startsWith("--") && it.contains('f')) } ->
                    GitActionClass.DESTRUCTIVE
                else -> GitActionClass.OUTBOUND
            }
            "send-pack" -> GitActionClass.OUTBOUND
            "fetch", "pull", "ls-remote" -> GitActionClass.OUTBOUND
            "clone" -> GitActionClass.OUTBOUND
            "remote" -> when (firstPositional) {
                "set-url" -> GitActionClass.OUTBOUND
                "add", "rename", "remove", "rm", "prune", "head" -> GitActionClass.LOCAL_WRITE
                else -> GitActionClass.READ
            }
            "submodule" -> when (firstPositional) {
                "update", "add" -> GitActionClass.OUTBOUND
                "status" -> GitActionClass.READ
                "foreach" -> GitActionClass.ARBITRARY // foreach 执行任意命令串
                else -> GitActionClass.LOCAL_WRITE
            }

            // ═══ 破坏性敏感族 ═══
            "reset" -> if (flags.any { it == "--hard" }) GitActionClass.DESTRUCTIVE else GitActionClass.LOCAL_WRITE
            "clean" -> when {
                flags.any { it == "-n" || it == "--dry-run" } -> GitActionClass.LOCAL_WRITE
                flags.any { it == "--force" || (it.startsWith("-") && !it.startsWith("--") && it.drop(1).contains('f')) } ->
                    GitActionClass.DESTRUCTIVE
                else -> GitActionClass.LOCAL_WRITE
            }
            "rm" -> if (flags.any { it == "--cached" }) GitActionClass.LOCAL_WRITE else GitActionClass.DESTRUCTIVE
            "branch" -> when {
                flags.any { it == "-D" || it == "--delete-force" || (it.startsWith("-") && !it.startsWith("--") && it.drop(1).contains('D')) } ->
                    GitActionClass.DESTRUCTIVE
                firstPositional != null -> GitActionClass.LOCAL_WRITE
                else -> GitActionClass.READ
            }
            "tag" -> when {
                flags.any { it == "-d" || it == "--delete" } -> GitActionClass.DESTRUCTIVE
                firstPositional != null -> GitActionClass.LOCAL_WRITE
                else -> GitActionClass.READ
            }
            "reflog" -> when (firstPositional) {
                "expire", "delete" -> GitActionClass.DESTRUCTIVE
                else -> GitActionClass.READ
            }
            "stash" -> when (firstPositional) {
                "list", "show" -> GitActionClass.READ
                "drop", "clear" -> GitActionClass.DESTRUCTIVE
                else -> GitActionClass.LOCAL_WRITE
            }
            "worktree" -> when (firstPositional) {
                "list" -> GitActionClass.READ
                "remove" -> if (flags.any { it == "--force" || it == "-f" }) GitActionClass.DESTRUCTIVE else GitActionClass.LOCAL_WRITE
                else -> GitActionClass.LOCAL_WRITE
            }
            "restore" -> when {
                flags.any { it == "--worktree" || it == "-W" } -> GitActionClass.DESTRUCTIVE
                flags.any { it == "--staged" || it == "-S" } -> GitActionClass.LOCAL_WRITE
                else -> GitActionClass.DESTRUCTIVE // 默认形态 = 丢弃工作区改动
            }
            "checkout" -> when {
                // checkout -- <path> = 丢弃工作区改动(不可逆);-f 同理
                hasPathspecSeparator || flags.any { it == "--force" || it == "-f" } ->
                    GitActionClass.DESTRUCTIVE
                else -> GitActionClass.LOCAL_WRITE
            }
            "gc" -> if (flags.any { it == "--prune=now" || it == "--prune" }) GitActionClass.DESTRUCTIVE else GitActionClass.LOCAL_WRITE
            "prune" -> GitActionClass.DESTRUCTIVE
            "filter-branch" -> GitActionClass.DESTRUCTIVE
            "update-ref" -> if (flags.any { it == "-d" || it == "--delete" }) GitActionClass.DESTRUCTIVE else GitActionClass.LOCAL_WRITE

            // ═══ 本地写族 ═══
            "add", "stage", "commit", "switch", "merge", "rebase", "revert",
            "cherry-pick", "init", "apply", "am", "mv", "notes", "format-patch",
            "cherry", "bundle", "patch-id", "hash-object", "mktree", "index-pack" ->
                GitActionClass.LOCAL_WRITE

            // ═══ 其余（含 difftool/mergetool/daemon/svn/p4/bisect run 等外部
            //     执行语义）——逐次确认 ═══
            else -> GitActionClass.ARBITRARY
        }
    }
}
