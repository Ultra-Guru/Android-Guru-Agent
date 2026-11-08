package com.apex.agent.core.codetools.git.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitCommandPolicy 分类矩阵全枚举（Git 审计 3.10 测试计划第 1 组）。
 *
 * 覆盖：六类动作、`-c`/config 配置逃逸、交互式变体（P0-7 的 startsWith
 * 击穿形态）、`-C` 工作区逃逸、URL scheme/host 白名单、--force-with-lease、
 * pathspec 分隔符、引号未闭合严格切分。
 */
class GitCommandPolicyTest {

    // ═══ 分类：READ ═══

    @Test
    fun `只读子命令归 READ`() {
        for (cmd in listOf(
            "status", "diff", "log --oneline", "show HEAD", "branch",
            "branch --list", "remote -v", "remote get-url origin", "ls-remote origin",
            "stash list", "stash show", "reflog", "worktree list", "tag",
            "rev-parse HEAD", "for-each-ref",
        )) {
            assertEquals(cmd, GitActionClass.READ, GitCommandPolicy.classify(args(cmd)))
        }
    }

    @Test
    fun `只读 help 与 version 归 READ`() {
        assertEquals(GitActionClass.READ, GitCommandPolicy.classify(args("--version")))
        assertEquals(GitActionClass.READ, GitCommandPolicy.classify(args("-h")))
        assertEquals(GitActionClass.READ, GitCommandPolicy.classify(args("--help")))
    }

    // ═══ 分类：LOCAL_WRITE ═══

    @Test
    fun `本地写子命令归 LOCAL_WRITE`() {
        for (cmd in listOf(
            "add -A", "commit -m msg", "switch main", "merge feat", "rebase main",
            "revert HEAD", "cherry-pick abc", "init", "branch feat/x", "tag v1.0",
            "stash push", "stash apply", "remote add origin https://github.com/o/r.git",
            "checkout main",
        )) {
            assertEquals(cmd, GitActionClass.LOCAL_WRITE, GitCommandPolicy.classify(args(cmd)))
        }
    }

    // ═══ 分类：DESTRUCTIVE ═══

    @Test
    fun `破坏性变体全归 DESTRUCTIVE`() {
        for (cmd in listOf(
            "reset --hard HEAD~1",
            "clean -fd",
            "clean -fdx",
            "clean --force",
            "branch -D feat",
            "push --force origin main",
            "push --force-with-lease origin main",
            "push -f origin main",
            "push --delete origin feat",
            "reflog expire --expire=now --all",
            "reflog delete HEAD@{1}",
            "stash drop",
            "stash clear",
            "rm -rf docs",
            "restore docs/a.txt",
            "checkout -- docs/a.txt",
            "gc --prune=now",
            "filter-branch --all",
        )) {
            assertEquals(cmd, GitActionClass.DESTRUCTIVE, GitCommandPolicy.classify(args(cmd)))
        }
    }

    @Test
    fun `restore 加 staged 归 LOCAL_WRITE`() {
        assertEquals(GitActionClass.LOCAL_WRITE, GitCommandPolicy.classify(args("restore --staged a.txt")))
    }

    @Test
    fun `rm 加 cached 归 LOCAL_WRITE`() {
        assertEquals(GitActionClass.LOCAL_WRITE, GitCommandPolicy.classify(args("rm --cached a.txt")))
    }

    // ═══ 分类：OUTBOUND ═══

    @Test
    fun `外向子命令归 OUTBOUND`() {
        for (cmd in listOf(
            "push origin main",
            "pull", "fetch origin", "clone https://github.com/o/r.git",
            "remote set-url origin https://github.com/o/r.git",
            "submodule update --init",
            "submodule add https://github.com/o/r.git",
        )) {
            assertEquals(cmd, GitActionClass.OUTBOUND, GitCommandPolicy.classify(args(cmd)))
        }
    }

    // ═══ 分类：CONFIG_ESCAPE（P0-2）═══

    @Test
    fun `全局区 c 注入归 CONFIG_ESCAPE`() {
        assertEquals(
            GitActionClass.CONFIG_ESCAPE,
            GitCommandPolicy.classify(args("-c credential.helper=!curl push origin main")),
        )
    }

    @Test
    fun `c 连体形态归 CONFIG_ESCAPE`() {
        assertEquals(
            GitActionClass.CONFIG_ESCAPE,
            GitCommandPolicy.classify(args("-ccore.hooksPath=/tmp/x status")),
        )
    }

    @Test
    fun `config 子命令归 CONFIG_ESCAPE`() {
        assertEquals(GitActionClass.CONFIG_ESCAPE, GitCommandPolicy.classify(args("config --global user.name x")))
    }

    @Test
    fun `子命令后的 c 不误伤`() {
        // git commit -c HEAD 是复用消息的合法形态,不在全局区
        assertEquals(GitActionClass.LOCAL_WRITE, GitCommandPolicy.classify(args("commit -c HEAD")))
    }

    // ═══ 分类：ARBITRARY ═══

    @Test
    fun `未识别子命令与外部执行语义归 ARBITRARY`() {
        for (cmd in listOf(
            "difftool", "mergetool", "bisect start", "submodule foreach npm i",
            "daemon", "svn rebase", "p4 sync", "fast-import",
        )) {
            assertEquals(cmd, GitActionClass.ARBITRARY, GitCommandPolicy.classify(args(cmd)))
        }
    }

    @Test
    fun `未知全局选项归 ARBITRARY`() {
        assertEquals(GitActionClass.ARBITRARY, GitCommandPolicy.classify(args("--git-dir=/etc status")))
        assertEquals(GitActionClass.ARBITRARY, GitCommandPolicy.classify(args("--work-tree /etc status")))
        assertEquals(GitActionClass.ARBITRARY, GitCommandPolicy.classify(args("--nonsense status")))
    }

    @Test
    fun `空参数归 ARBITRARY`() {
        assertEquals(GitActionClass.ARBITRARY, GitCommandPolicy.classify(emptyList()))
    }

    // ═══ 交互式（P0-7:argv 位置无关,废除 startsWith）═══

    @Test
    fun `交互式变体全部命中`() {
        // 旧实现的 startsWith 击穿形态:双空格 / tab / 旗标后置
        assertNotNull(GitCommandPolicy.interactiveReason(args("add -p")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("add --patch")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("add -p file.txt")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("commit -p")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("rebase -i main")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("rebase --interactive main")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("checkout -p main")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("restore -p")))
        assertNotNull(GitCommandPolicy.interactiveReason(args("clean -i")))
        // 旗标位置无关(旧实现只认前缀)
        assertNotNull(GitCommandPolicy.interactiveReason(args("add file.txt -p")))
    }

    @Test
    fun `非交互形态不误报`() {
        assertNull(GitCommandPolicy.interactiveReason(args("add -A")))
        assertNull(GitCommandPolicy.interactiveReason(args("push origin main")))
        assertNull(GitCommandPolicy.interactiveReason(args("commit -m fix")))
        // pathspec 分隔符后的 -p 是文件名,不是旗标
        assertNull(GitCommandPolicy.interactiveReason(args("add -- -p")))
        // 交互式旗标对非敏感子命令无意义
        assertNull(GitCommandPolicy.interactiveReason(args("status -p")))
    }

    // ═══ -C 工作区逃逸（P0-8）═══

    @Test
    fun `c 在工作区内合法`() {
        assertNull(GitCommandPolicy.workspaceEscapeReason(args("-C repo pull")))
        assertNull(GitCommandPolicy.workspaceEscapeReason(args("-C repo/sub status")))
        assertNull(GitCommandPolicy.workspaceEscapeReason(args("-C/workspace status")))
        assertNull(GitCommandPolicy.workspaceEscapeReason(args("status"))) // 无 -C
    }

    @Test
    fun `c 逃逸出工作区全部拒绝`() {
        assertNotNull(GitCommandPolicy.workspaceEscapeReason(args("-C ../.. status")))
        assertNotNull(GitCommandPolicy.workspaceEscapeReason(args("-C .. status")))
        assertNotNull(GitCommandPolicy.workspaceEscapeReason(args("-C /root status")))
        assertNotNull(GitCommandPolicy.workspaceEscapeReason(args("-C / status")))
        assertNotNull(GitCommandPolicy.workspaceEscapeReason(args("-C/workspace/../../etc status")))
        assertNotNull(GitCommandPolicy.workspaceEscapeReason(args("-C"))) // 缺参
    }

    // ═══ URL 白名单（P0-8）═══

    @Test
    fun `github https url 合法`() {
        assertNull(GitCommandPolicy.urlViolationReason(args("clone https://github.com/o/r.git")))
        assertNull(GitCommandPolicy.urlViolationReason(args("push origin main")))
        assertNull(GitCommandPolicy.urlViolationReason(args("fetch origin")))
        assertNull(GitCommandPolicy.urlViolationReason(args("remote add origin https://github.com/o/r.git")))
        assertNull(GitCommandPolicy.urlViolationReason(args("push origin HEAD:refs/heads/main")))
    }

    @Test
    fun `非白名单 scheme 与 host 全拒绝`() {
        assertNotNull(GitCommandPolicy.urlViolationReason(args("clone file:///tmp/repo")))
        assertNotNull(GitCommandPolicy.urlViolationReason(args("clone ssh://git@github.com/o/r.git")))
        assertNotNull(GitCommandPolicy.urlViolationReason(args("clone http://github.com/o/r.git")))
        assertNotNull(GitCommandPolicy.urlViolationReason(args("clone https://github.com.evil.com/o/r")))
        assertNotNull(GitCommandPolicy.urlViolationReason(args("clone https://gitlab.com/o/r")))
        assertNotNull(GitCommandPolicy.urlViolationReason(args("push https://user:token@github.com/o/r main")))
        assertNotNull(GitCommandPolicy.urlViolationReason(args("clone ext::sh -c curl")))
        // 非外向命令不做 URL 扫描(路径含 :// 不误伤)
        assertNull(GitCommandPolicy.urlViolationReason(args("status")))
    }

    // ═══ 严格切分（P0-7）═══

    @Test
    fun `未闭合引号判 Bad`() {
        assertTrue(GitCommandPolicy.parse("commit -m \"未闭合") is GitCommandPolicy.ParsedArgs.Bad)
    }

    @Test
    fun `常规切分保持旧语义`() {
        val ok = GitCommandPolicy.parse("commit -m \"fix: 修复 bug\"")
        assertEquals(listOf("commit", "-m", "fix: 修复 bug"), (ok as GitCommandPolicy.ParsedArgs.Ok).args)
        // 引号内空段保留为空参数(与旧 splitGitArgs 对齐)
        val empty = GitCommandPolicy.parse("a \"\" b")
        assertEquals(listOf("a", "", "b"), (empty as GitCommandPolicy.ParsedArgs.Ok).args)
    }

    // ═══ 宽容切分(存量兼容) ═══

    @Test
    fun `宽容切分不报错`() {
        assertEquals(listOf("a", "b"), GitCommandPolicy.splitGitArgsLenient("a b"))
    }

    // ═══ 完整链路:分类 + 各门检查组合 ═══

    @Test
    fun `组合形态 -C 加 force push 全链路命中`() {
        val args = args("-C repo push --force origin main")
        assertEquals(GitActionClass.DESTRUCTIVE, GitCommandPolicy.classify(args))
        assertNull(GitCommandPolicy.workspaceEscapeReason(args))
        assertNull(GitCommandPolicy.urlViolationReason(args))
        assertNull(GitCommandPolicy.configEscapeReason(args))
    }

    /** "clone <url> <dir>" 双参数形态——空格切分后逐 token 校验。 */
    @Test
    fun `clone 到工作区内目录合法`() {
        val args = args("clone https://github.com/o/r.git mydir")
        assertEquals(GitActionClass.OUTBOUND, GitCommandPolicy.classify(args))
        assertNull(GitCommandPolicy.urlViolationReason(args))
    }

    private fun args(raw: String): List<String> {
        val parsed = GitCommandPolicy.parse(raw)
        return (parsed as GitCommandPolicy.ParsedArgs.Ok).args
    }
}
