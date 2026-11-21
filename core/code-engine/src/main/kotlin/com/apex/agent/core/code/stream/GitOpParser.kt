package com.apex.agent.core.code.stream

/**
 * # Git Op Parser — git 工具输出 → 结构化可视化数据
 *
 * 把五个 code_git_* 工具（CodeGitStatus / CodeGitDiff / CodeGitLog /
 * CodeGitCommit / CodeGitBranch）的成功输出翻译成 [GitOpSummary]，供时间轴
 * 的 Git 可视化卡渲染；失败输出经 [parseFailure] 翻译成诚实失败卡（环境
 * 未就绪 / 非仓库 / 命令失败——「git 不能是假的」：失败也要被看见）。
 * 解析纪律：**全部防御式**——输出被截断（6000 字符上限）、掺入异常文案、
 * 格式漂移都要吞得下，解析不出就返回 null（调用方静默跳过可视化卡，
 * 通用胶囊不受影响），绝不抛异常。
 *
 * 工具名归一化：模型可能回显 v5 现役 id（CodeGitStatus）、历史 snake_case
 * 别名（code_git_status——ToolIdAliases 兼容层存在的原因）或裸短名
 * （git_status）。与 ToolKind.fromToolName 同款口径：小写 + 去非字母数字
 * 后匹配，三种形态归一到同一张卡。
 *
 * 各工具输出契约（见 core:code-tools git 包）：
 * - Status：首行「分支: X（跟踪…，领先 N / 落后 M）」+ 分组标题
 *   「已暂存（将进入下次提交）（N）：」等 + 干净态「工作区干净」；
 * - Commit：「已提交 <hash7>: <subject>」+「剩余未提交变更：N 个文件 / 无」
 *   + 空提交「没有可提交的变更…」；
 * - Diff：unified diff 原文（+++/--- 头与 +/- 内容行）或「没有差异」；
 * - Branch：「分支列表（N）：」+ 逐行「* 当前（跟踪）」/「  其他」，或
 *   「已创建分支 X（基于 Y）」「已切换到分支 X」「已删除分支 X」；
 * - Log：逐行「<hash7> <yyyy-MM-dd> <subject>」或「暂无提交」。
 *
 * 失败输出契约（ToolResult.fail 渲染为「Error: <slug>: <message>」）：
 * - 环境未就绪：「Ubuntu 环境未安装或未就绪——…」（ProotGitCommandRunner
 *   的 ROOTFS_NOT_READY_MESSAGE，最常见——git 工具链住在 PRoot Ubuntu 里，
 *   没装环境时所有 git 工具都走这条）；
 * - 无工作区：「无激活工作区——…」；
 * - 非仓库：「当前工作区还不是 git 仓库。…」（notRepoGuidance）；
 * - 命令失败：「git xxx 失败（退出码 N）：<stderr 末行>」。
 */
internal object GitOpParser {

    // ═══ #566：解析正则预编译（旧实现在函数体内逐次编译，git 操作高频路径）═══

    /** status 备注：「（…）」全角括号组。 */
    private val NOTE_PAREN = Regex("（([^（）]*)）")

    /** status 领先/落后提交数。 */
    private val AHEAD_COUNT = Regex("领先\\s*(\\d+)")
    private val BEHIND_COUNT = Regex("落后\\s*(\\d+)")

    /** 「已暂存（将进入下次提交）（2）：」尾部括号组计数。 */
    private val GROUP_COUNT = Regex("（(\\d+)）\\s*：?\\s*$")

    /** commit 主行：「已提交 <hash> : <subject>」。 */
    private val COMMIT_LINE = Regex("已提交\\s+([0-9a-fA-F]{7,40})\\s*:\\s*(.*)")

    /** commit 剩余变更数。 */
    private val REMAINING_CHANGES = Regex("剩余未提交变更[:：]\\s*(\\d+)\\s*个文件")

    /** log 行形状：「<hash> <date> <subject>」。 */
    private val LOG_COMMIT_LINE = Regex("^[0-9a-fA-F]{6,40}\\s+\\S+\\s+.+")

    /** log 主题列切分（hash + date 之后）。 */
    private val LOG_SUBJECT_SPLIT = Regex("\\s+")

    /** branch 各动作行。 */
    private val BRANCH_LIST_COUNT = Regex("分支列表（(\\d+)）：")
    private val BRANCH_CURRENT = Regex("当前分支[:：]\\s*(\\S+)")
    private val BRANCH_CREATED = Regex("已创建分支\\s+(\\S+)")
    private val BRANCH_SWITCHED = Regex("已切换到分支\\s+(\\S+)")
    private val BRANCH_DELETED = Regex("已删除分支\\s+(\\S+)")

    /** 工具名（v5 id / 历史别名 / 裸短名均可）→ 可视化卡数据；不可解析返回 null。 */
    fun parse(toolName: String, output: String): GitOpSummary? = when (normalize(toolName)) {
        "codegitstatus" -> parseStatus(output)
        "codegitdiff" -> parseDiff(output)
        "codegitlog" -> parseLog(output)
        "codegitcommit" -> parseCommit(output)
        "codegitbranch" -> parseBranch(output)
        else -> null
    }

    /**
     * 失败输出的诚实可视化（isError=true 的 ToolCallComplete）。
     *
     * 与 [parse] 同款工具名归一化；输出形如
     * `Error: execution failed: <message>`——先剥离 Error 前缀链，再按
     * 特征串归因（环境未就绪 / 无工作区 / 非仓库 / 命令失败）。任何形状
     * 吞得下：归因不出也给 COMMAND_FAILED 卡（headline 取消息首行），
     * 绝不返回「假成功」。
     */
    fun parseFailure(toolName: String, output: String): GitOpSummary? {
        val op = when (normalize(toolName)) {
            "codegitstatus" -> GitOpSummary.GitOpType.STATUS
            "codegitdiff" -> GitOpSummary.GitOpType.DIFF
            "codegitlog" -> GitOpSummary.GitOpType.LOG
            "codegitcommit" -> GitOpSummary.GitOpType.COMMIT
            "codegitbranch" -> GitOpSummary.GitOpType.BRANCH
            else -> return null
        }
        val message = stripErrorPrefix(output).trim()
        if (message.isEmpty()) return null

        val kind = when {
            message.contains("Ubuntu 环境未安装") ||
                message.contains("rootfs 已损坏") ->
                GitOpSummary.FailureKind.ENV_NOT_READY
            message.contains("无激活工作区") ->
                GitOpSummary.FailureKind.NO_WORKSPACE
            message.contains("还不是 git 仓库") ||
                message.contains("not a git repository", ignoreCase = true) ->
                GitOpSummary.FailureKind.NOT_REPO
            else -> GitOpSummary.FailureKind.COMMAND_FAILED
        }
        val note = message.lineSequence().firstOrNull { it.isNotBlank() }?.take(NOTE_MAX)
        return GitOpSummary(
            op = op,
            headline = "", // UI 按 failureKind 给标题，headline 不重复填
            note = note,
            failed = true,
            failureKind = kind
        )
    }

    /** 工具名归一：小写 + 去非字母数字（ToolKind.fromToolName 同款口径）。 */
    internal fun normalize(toolName: String): String =
        toolName.trim().lowercase().filter { it.isLetterOrDigit() }

    /** 剥离「Error: <slug>: 」前缀链（ToolResult.render 的固定形状，防御多级）。 */
    private fun stripErrorPrefix(output: String): String {
        var text = output.trim()
        while (true) {
            val next = text.removePrefix("Error: ")
            if (next == text) break
            text = next
            // 剥掉首个 slug 段（execution failed / invalid argument 等：
            // 小写字母/空格组成，止于冒号）
            val slugEnd = text.indexOf(':')
            if (slugEnd > 0 && text.take(slugEnd).all { it.isLowerCase() || it == ' ' }) {
                text = text.substring(slugEnd + 1).trimStart()
            }
        }
        return text
    }

    // ── Status ───────────────────────────────────────────────────────

    private fun parseStatus(output: String): GitOpSummary? {
        val lines = output.lines()
        val branchLine = lines.firstOrNull { it.startsWith("分支:") } ?: return null
        val body = branchLine.removePrefix("分支:").trim()

        // 分离头 / 未知分支：括号内说明整体作为 headline，不硬抠分支名
        if (body.startsWith("（分离")) {
            return GitOpSummary(
                op = GitOpSummary.GitOpType.STATUS,
                headline = "分离 HEAD",
                note = "不在任何分支上，可用 CodeGitBranch 切回"
            )
        }
        if (body.startsWith("（未知")) {
            return GitOpSummary(
                op = GitOpSummary.GitOpType.STATUS,
                headline = "分支未知",
                note = "git 未返回分支信息"
            )
        }
        val branch = body.substringBefore("（").trim().ifEmpty { body }
        val note = NOTE_PAREN.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val ahead = AHEAD_COUNT.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = BEHIND_COUNT.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        var staged = 0
        var unstaged = 0
        var untracked = 0
        var conflicts = 0
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("已暂存") ->
                    staged = groupCount(trimmed) ?: staged
                trimmed.startsWith("未暂存") ->
                    unstaged = groupCount(trimmed) ?: unstaged
                trimmed.startsWith("未跟踪") ->
                    untracked = groupCount(trimmed) ?: untracked
                trimmed.startsWith("合并冲突") ->
                    conflicts = groupCount(trimmed) ?: conflicts
            }
        }
        val clean = output.contains("工作区干净")
        return GitOpSummary(
            op = GitOpSummary.GitOpType.STATUS,
            headline = branch,
            branch = branch,
            ahead = ahead,
            behind = behind,
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            conflicts = conflicts,
            clean = clean,
            note = note
        )
    }

    /** 「已暂存（将进入下次提交）（2）：」→ 2（取最后一个括号组；防御非数字）。 */
    private fun groupCount(header: String): Int? =
        GROUP_COUNT.find(header)?.groupValues?.get(1)?.toIntOrNull()

    // ── Commit ───────────────────────────────────────────────────────

    private fun parseCommit(output: String): GitOpSummary? {
        val first = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        // 空提交是合法状态：主行直出，无哈希
        if (first.startsWith("没有可提交的变更")) {
            return GitOpSummary(
                op = GitOpSummary.GitOpType.COMMIT,
                headline = first.take(HEADLINE_MAX),
                note = "工作区干净，没有新提交产生"
            )
        }
        val m = COMMIT_LINE.find(first) ?: return null
        val hash = m.groupValues[1].take(7)
        val subject = m.groupValues[2].trim().take(HEADLINE_MAX)
        val remaining = REMAINING_CHANGES.find(output)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: if (output.contains("剩余未提交变更：无")) 0 else null
        return GitOpSummary(
            op = GitOpSummary.GitOpType.COMMIT,
            headline = subject.ifEmpty { hash },
            commitHash = hash,
            commitSubject = subject.ifEmpty { null },
            remainingChanges = remaining
        )
    }

    // ── Diff ─────────────────────────────────────────────────────────

    private fun parseDiff(output: String): GitOpSummary? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "没有差异") {
            return GitOpSummary(
                op = GitOpSummary.GitOpType.DIFF,
                headline = "没有差异",
                filesChanged = 0,
                additions = 0,
                deletions = 0
            )
        }
        var files = 0
        var additions = 0
        var deletions = 0
        for (line in trimmed.lineSequence()) {
            when {
                line.startsWith("diff --git ") -> files++
                line.startsWith("+++") -> Unit
                line.startsWith("---") -> Unit
                line.startsWith("+") -> additions++
                line.startsWith("-") -> deletions++
            }
        }
        if (files == 0 && additions == 0 && deletions == 0) {
            // 不是 diff 形状（例如降级说明行）——仍然给一张统计为零的卡，
            // headline 取首行说明，避免误导用户以为有变更
            val first = trimmed.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
            return GitOpSummary(
                op = GitOpSummary.GitOpType.DIFF,
                headline = first.take(HEADLINE_MAX),
                filesChanged = 0,
                additions = 0,
                deletions = 0
            )
        }
        return GitOpSummary(
            op = GitOpSummary.GitOpType.DIFF,
            headline = "$files 个文件",
            filesChanged = files,
            additions = additions,
            deletions = deletions
        )
    }

    // ── Log ──────────────────────────────────────────────────────────

    private fun parseLog(output: String): GitOpSummary? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "暂无提交") {
            return GitOpSummary(
                op = GitOpSummary.GitOpType.LOG,
                headline = "暂无提交",
                commitCount = 0
            )
        }
        // 每行「<hash7> <yyyy-MM-dd> <subject>」；防御混入的非提交行
        val commitLine = LOG_COMMIT_LINE
        val commits = trimmed.lineSequence()
            .filter { it.isNotBlank() && commitLine.matches(it.trim()) }
            .toList()
        if (commits.isEmpty()) return null
        val latest = commits.first().trim()
        val subject = latest.split(LOG_SUBJECT_SPLIT, limit = 3).getOrNull(2)?.take(HEADLINE_MAX)
        return GitOpSummary(
            op = GitOpSummary.GitOpType.LOG,
            headline = subject ?: "最近 ${commits.size} 条提交",
            commitCount = commits.size
        )
    }

    // ── Branch ───────────────────────────────────────────────────────

    private fun parseBranch(output: String): GitOpSummary? {
        val first = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null

        BRANCH_LIST_COUNT.find(first)?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 0
            // 当前分支 = 「* 」开头的行（去掉星标后的名字，可能带（跟踪）说明）
            val current = output.lineSequence()
                .firstOrNull { it.trimStart().startsWith("*") }
                ?.trimStart()?.removePrefix("*")?.trim()
                ?.substringBefore("（")?.trim()
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = current?.ifEmpty { "$count 个分支" } ?: "$count 个分支",
                branch = current?.ifEmpty { null },
                branchAction = "list",
                commitCount = count
            )
        }

        BRANCH_CURRENT.find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = m.groupValues[1],
                branch = m.groupValues[1],
                branchAction = "list",
                note = "尚无提交——首次提交后才会出现在分支列表中"
            )
        }

        BRANCH_CREATED.find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = "已创建 ${m.groupValues[1]}",
                branch = m.groupValues[1],
                branchAction = "create"
            )
        }
        BRANCH_SWITCHED.find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = "已切换 ${m.groupValues[1]}",
                branch = m.groupValues[1],
                branchAction = "switch"
            )
        }
        BRANCH_DELETED.find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = "已删除 ${m.groupValues[1]}",
                branch = m.groupValues[1],
                branchAction = "delete"
            )
        }
        if (first == "分支列表为空") {
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = "分支列表为空",
                branchAction = "list"
            )
        }
        return null
    }

    private const val HEADLINE_MAX = 40

    /** 失败卡 note 截断（stderr 末行可能很长）。 */
    private const val NOTE_MAX = 80
}
