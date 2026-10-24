package com.apex.agent.core.code.stream

/**
 * # Git Op Parser — git 工具输出 → 结构化可视化数据
 *
 * 把五个 code_git_* 工具（CodeGitStatus / CodeGitDiff / CodeGitLog /
 * CodeGitCommit / CodeGitBranch）的成功输出翻译成 [GitOpSummary]，供时间轴
 * 的 Git 可视化卡渲染。解析纪律：**全部防御式**——输出被截断（6000 字符
 * 上限）、掺入异常文案、格式漂移都要吞得下，解析不出就返回 null（调用方
 * 静默跳过可视化卡，通用胶囊不受影响），绝不抛异常。
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
 */
internal object GitOpParser {

    /** 工具名（v5 PascalCase 现役 id）→ 可视化卡数据；不可解析返回 null。 */
    fun parse(toolName: String, output: String): GitOpSummary? = when (toolName) {
        "CodeGitStatus" -> parseStatus(output)
        "CodeGitDiff" -> parseDiff(output)
        "CodeGitLog" -> parseLog(output)
        "CodeGitCommit" -> parseCommit(output)
        "CodeGitBranch" -> parseBranch(output)
        else -> null
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
        val note = Regex("（([^（）]*)）").find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val ahead = Regex("领先\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = Regex("落后\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
        Regex("（(\\d+)）\\s*：?\\s*$").find(header)?.groupValues?.get(1)?.toIntOrNull()

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
        val m = Regex("已提交\\s+([0-9a-fA-F]{7,40})\\s*:\\s*(.*)").find(first) ?: return null
        val hash = m.groupValues[1].take(7)
        val subject = m.groupValues[2].trim().take(HEADLINE_MAX)
        val remaining = Regex("剩余未提交变更[:：]\\s*(\\d+)\\s*个文件").find(output)
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
        val commitLine = Regex("^[0-9a-fA-F]{6,40}\\s+\\S+\\s+.+")
        val commits = trimmed.lineSequence()
            .filter { it.isNotBlank() && commitLine.matches(it.trim()) }
            .toList()
        if (commits.isEmpty()) return null
        val latest = commits.first().trim()
        val subject = latest.split(Regex("\\s+"), limit = 3).getOrNull(2)?.take(HEADLINE_MAX)
        return GitOpSummary(
            op = GitOpSummary.GitOpType.LOG,
            headline = subject ?: "最近 ${commits.size} 条提交",
            commitCount = commits.size
        )
    }

    // ── Branch ───────────────────────────────────────────────────────

    private fun parseBranch(output: String): GitOpSummary? {
        val first = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null

        Regex("分支列表（(\\d+)）：").find(first)?.let { m ->
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

        Regex("当前分支[:：]\\s*(\\S+)").find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = m.groupValues[1],
                branch = m.groupValues[1],
                branchAction = "list",
                note = "尚无提交——首次提交后才会出现在分支列表中"
            )
        }

        Regex("已创建分支\\s+(\\S+)").find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = "已创建 ${m.groupValues[1]}",
                branch = m.groupValues[1],
                branchAction = "create"
            )
        }
        Regex("已切换到分支\\s+(\\S+)").find(first)?.let { m ->
            return GitOpSummary(
                op = GitOpSummary.GitOpType.BRANCH,
                headline = "已切换 ${m.groupValues[1]}",
                branch = m.groupValues[1],
                branchAction = "switch"
            )
        }
        Regex("已删除分支\\s+(\\S+)").find(first)?.let { m ->
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
}
