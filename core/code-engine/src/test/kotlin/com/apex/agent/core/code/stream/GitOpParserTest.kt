package com.apex.agent.core.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GitOpParser] 单测：五个 code_git_* 工具输出 → [GitOpSummary] 结构化解析。
 *
 * 覆盖：status 干净/分组/冲突/分离头、commit 常规/空提交、diff 统计/没有差异/
 * 降级文案、log 多行/暂无提交、branch 列表/四动作、以及防御路径（空输出、
 * 未知工具、格式漂移返回 null）。
 */
class GitOpParserTest {

    // ═══ Status ═══

    @Test
    fun `status clean workspace parses branch`() {
        val out = "分支: main（跟踪 origin/main）\n工作区干净"
        val s = GitOpParser.parse("CodeGitStatus", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.STATUS, s!!.op)
        assertEquals("main", s.branch)
        assertTrue(s.clean)
        assertEquals(0, s.staged + s.unstaged + s.untracked + s.conflicts)
    }

    @Test
    fun `status grouped counts and ahead behind parse`() {
        val out = """
            分支: feature/x（跟踪 origin/feature/x，领先 2 / 落后 1）

            合并冲突（需先解决再提交）（1）：
            - 双方修改  src/A.kt

            已暂存（将进入下次提交）（2）：
            - 修改  a.kt

            未暂存（3）：
            - 修改  b.kt

            未跟踪（4）：
            - c.kt
        """.trimIndent()
        val s = GitOpParser.parse("CodeGitStatus", out)
        assertNotNull(s)
        assertEquals("feature/x", s!!.branch)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        assertEquals(2, s.staged)
        assertEquals(3, s.unstaged)
        assertEquals(4, s.untracked)
        assertEquals(1, s.conflicts)
        assertTrue(!s.clean)
    }

    @Test
    fun `status detached head parses special state`() {
        val out = "分支:（分离 HEAD——不在任何分支上，可用 CodeGitBranch 切回分支）\n\n未跟踪（1）：\n- x.kt"
        val s = GitOpParser.parse("CodeGitStatus", out)
        assertNotNull(s)
        assertEquals("分离 HEAD", s!!.headline)
        assertNull(s.branch)
    }

    @Test
    fun `status no commits yet keeps branch`() {
        val out = "分支: main（尚无提交）\n工作区干净"
        val s = GitOpParser.parse("CodeGitStatus", out)
        assertNotNull(s)
        assertEquals("main", s!!.branch)
        assertTrue(s.clean)
    }

    // ═══ Commit ═══

    @Test
    fun `commit success parses hash subject and remaining`() {
        val out = "已提交 a1b2c3d: 修复登录页崩溃\n剩余未提交变更：3 个文件"
        val s = GitOpParser.parse("CodeGitCommit", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.COMMIT, s!!.op)
        assertEquals("a1b2c3d", s.commitHash)
        assertEquals("修复登录页崩溃", s.commitSubject)
        assertEquals(3, s.remainingChanges)
    }

    @Test
    fun `commit with nothing remaining parses zero`() {
        val out = "已提交 deadbee: chore: tidy\n剩余未提交变更：无"
        val s = GitOpParser.parse("CodeGitCommit", out)
        assertNotNull(s)
        assertEquals(0, s!!.remainingChanges)
    }

    @Test
    fun `commit empty commit parses as legal state`() {
        val out = "没有可提交的变更（工作区干净或没有已暂存内容）"
        val s = GitOpParser.parse("CodeGitCommit", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.COMMIT, s!!.op)
        assertNull(s.commitHash)
    }

    @Test
    fun `commit garbage output returns null`() {
        assertNull(GitOpParser.parse("CodeGitCommit", "随便什么文本"))
    }

    // ═══ Diff ═══

    @Test
    fun `diff unified output parses stats`() {
        val out = """
            HEAD 尚不存在（仓库还没有任何提交）——以下为工作区与暂存区的差异
            diff --git a/src/A.kt b/src/A.kt
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1,2 +1,3 @@
             x
            +y
            +z
            -w
        """.trimIndent()
        val s = GitOpParser.parse("CodeGitDiff", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.DIFF, s!!.op)
        assertEquals(1, s.filesChanged)
        assertEquals(2, s.additions)
        assertEquals(1, s.deletions)
    }

    @Test
    fun `diff empty parses zero stats`() {
        val s = GitOpParser.parse("CodeGitDiff", "没有差异")
        assertNotNull(s)
        assertEquals(0, s!!.filesChanged)
        assertEquals(0, s.additions)
        assertEquals(0, s.deletions)
    }

    // ═══ Log ═══

    @Test
    fun `log parses count and latest subject`() {
        val out = """
            a1b2c3d 2026-10-24 修复登录页崩溃
            deadbee 2026-10-23 chore: 依赖升级
            cafe123 2026-10-22 feat: 新增 Git 面板
        """.trimIndent()
        val s = GitOpParser.parse("CodeGitLog", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.LOG, s!!.op)
        assertEquals(3, s.commitCount)
        assertEquals("修复登录页崩溃", s.headline)
    }

    @Test
    fun `log empty history parses zero`() {
        val s = GitOpParser.parse("CodeGitLog", "暂无提交")
        assertNotNull(s)
        assertEquals(0, s!!.commitCount)
    }

    // ═══ Branch ═══

    @Test
    fun `branch list parses current branch`() {
        val out = """
            分支列表（3）：
            * main（领先 2）
              dev
              feature-x
        """.trimIndent()
        val s = GitOpParser.parse("CodeGitBranch", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.BRANCH, s!!.op)
        assertEquals("main", s.branch)
        assertEquals("list", s.branchAction)
    }

    @Test
    fun `branch create switch delete parse action`() {
        assertEquals(
            "create",
            GitOpParser.parse("CodeGitBranch", "已创建分支 hotfix/x（基于 main）")!!.branchAction
        )
        assertEquals(
            "switch",
            GitOpParser.parse("CodeGitBranch", "已切换到分支 dev")!!.branchAction
        )
        assertEquals(
            "delete",
            GitOpParser.parse("CodeGitBranch", "已删除分支 old")!!.branchAction
        )
    }

    // ═══ 防御 ═══

    @Test
    fun `unknown tool returns null`() {
        assertNull(GitOpParser.parse("CodeRead", "whatever"))
    }

    @Test
    fun `blank output returns null`() {
        assertNull(GitOpParser.parse("CodeGitStatus", ""))
        assertNull(GitOpParser.parse("CodeGitStatus", "   \n  "))
    }

    @Test
    fun `truncated status still parses visible groups`() {
        // 6000 字符截断后分组标题可能只剩部分——已解析出的计数不丢
        val out = "分支: main\n\n已暂存（将进入下次提交）（2）：\n- 修改  a.kt\n- 修改  b.kt\n（输出超长，已截断至 6000 字符"
        val s = GitOpParser.parse("CodeGitStatus", out)
        assertNotNull(s)
        assertEquals("main", s!!.branch)
        assertEquals(2, s.staged)
    }

    // ═══ 工具名归一化（v5 id / 历史别名 / 裸短名）═══

    @Test
    fun `legacy snake_case alias parses same as v5 id`() {
        val out = "分支: main（跟踪 origin/main）\n工作区干净"
        val s = GitOpParser.parse("code_git_status", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.GitOpType.STATUS, s!!.op)
        assertEquals("main", s.branch)
    }

    @Test
    fun `mixed case and whitespace in tool name normalize`() {
        val s = GitOpParser.parse("  CodeGitCommit ", "已提交 a1b2c3d: 修复\n剩余未提交变更：无")
        assertNotNull(s)
        assertEquals("a1b2c3d", s!!.commitHash)
    }

    @Test
    fun `normalize helper matches ToolKind fromToolName convention`() {
        assertEquals("codegitstatus", GitOpParser.normalize("CodeGitStatus"))
        assertEquals("codegitstatus", GitOpParser.normalize("code_git_status"))
        assertEquals("codegitstatus", GitOpParser.normalize(" Code-Git-Status "))
    }

    // ═══ 失败解析（诚实可视化）═══

    @Test
    fun `failure env not ready parses with guidance`() {
        val out = "Error: execution failed: Ubuntu 环境未安装或未就绪——Git 工具依赖沙箱内预装的 git，" +
            "请先在终端页安装 Ubuntu（TerminalUbuntuInstall）后再试"
        val s = GitOpParser.parseFailure("CodeGitStatus", out)
        assertNotNull(s)
        assertTrue(s!!.failed)
        assertEquals(GitOpSummary.FailureKind.ENV_NOT_READY, s.failureKind)
        assertEquals(GitOpSummary.GitOpType.STATUS, s.op)
        assertNotNull(s.note)
    }

    @Test
    fun `failure no workspace parses`() {
        val out = "Error: execution failed: 无激活工作区——请先在 Code 屏创建或选择一个工作区，再使用 Git 工具"
        val s = GitOpParser.parseFailure("code_git_log", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.FailureKind.NO_WORKSPACE, s!!.failureKind)
        assertEquals(GitOpSummary.GitOpType.LOG, s.op)
    }

    @Test
    fun `failure not repo parses with init guidance`() {
        val out = "Error: execution failed: 当前工作区还不是 git 仓库。可先调用 CodeGitCommit 创建首次提交" +
            "（会自动 git init 初始化仓库），或让用户在终端的 Ubuntu 环境里手动执行 git init"
        val s = GitOpParser.parseFailure("CodeGitDiff", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.FailureKind.NOT_REPO, s!!.failureKind)
    }

    @Test
    fun `failure command failed parses stderr detail`() {
        val out = "Error: execution failed: git commit 失败（退出码 128）：fatal: 无法读取当前分支"
        val s = GitOpParser.parseFailure("CodeGitCommit", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.FailureKind.COMMAND_FAILED, s!!.failureKind)
        assertEquals(GitOpSummary.GitOpType.COMMIT, s.op)
        assertNotNull(s.note)
    }

    @Test
    fun `failure invalid argument still gets honest card`() {
        // 参数校验失败（slug=invalid argument）也走 COMMAND_FAILED 卡
        val out = "Error: invalid argument: message 不能为空白 (argument 'message')"
        val s = GitOpParser.parseFailure("CodeGitCommit", out)
        assertNotNull(s)
        assertEquals(GitOpSummary.FailureKind.COMMAND_FAILED, s!!.failureKind)
    }

    @Test
    fun `failure unknown tool or blank output returns null`() {
        assertNull(GitOpParser.parseFailure("CodeRead", "Error: execution failed: x"))
        assertNull(GitOpParser.parseFailure("CodeGitStatus", ""))
        assertNull(GitOpParser.parseFailure("CodeGitStatus", "   "))
    }

    @Test
    fun `failure prefix stripping keeps message body`() {
        // 多级前缀防御：剥到底后正文完整
        val out = "Error: execution failed: git branch 失败（退出码 1）：error: 分支不存在"
        val s = GitOpParser.parseFailure("CodeGitBranch", out)
        assertNotNull(s)
        assertTrue(s!!.note!!.contains("git branch 失败"))
    }
}
