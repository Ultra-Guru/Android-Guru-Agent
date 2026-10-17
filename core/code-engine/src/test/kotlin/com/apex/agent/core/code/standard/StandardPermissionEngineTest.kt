package com.apex.agent.core.code.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardPermissionEngine 裁决矩阵测试：
 * 规则短路 / PLAN 档硬门 / 会话记忆 / 模式兜底 / 命令级通配 /
 * 子代理折叠 / 敏感文件保护。
 */
class StandardPermissionEngineTest {

    private fun engine(mode: StandardPermissionMode = StandardPermissionMode.DEFAULT) =
        StandardPermissionEngine(mode)

    // ═══ 规则 ═══

    @Test
    fun `deny rule short-circuits even under bypass`() {
        val e = engine(StandardPermissionMode.BYPASS)
        e.updateRules(listOf(StandardPermissionRule("CodeEdit", StandardPermissionEffect.DENY)))
        val d = e.decide("CodeEdit", "{}")
        assertEquals(StandardPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("DENY"))
    }

    @Test
    fun `prefix wildcard rule matches dynamic mcp ids`() {
        val e = engine()
        e.updateRules(listOf(StandardPermissionRule("McpGithub*", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("McpGithubCreateIssue", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("McpGithubListPrs", "{}").effect)
        // 非同前缀不命中（Gitlab 无 Mcp 前缀 → 非只读分类 → 走 ASK 兜底）
        assertEquals(StandardPermissionEffect.ASK, e.decide("GitlabX", "{}").effect)
    }

    @Test
    fun `single star matches everything`() {
        val e = engine()
        e.updateRules(listOf(StandardPermissionRule("*", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("anything_at_all", "{}").effect)
    }

    @Test
    fun `blank pattern never matches`() {
        val e = engine()
        e.updateRules(listOf(StandardPermissionRule("  ", StandardPermissionEffect.ALLOW)))
        // 空模式在 updateRules 已被过滤；裁决回退模式兜底
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeWrite", "{}").effect)
    }

    // ═══ 模式兜底 ═══

    @Test
    fun `default mode allows readonly and asks for writes`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeGrep", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeWrite", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("ShellExecute", "{}").effect)
    }

    @Test
    fun `bypass mode allows everything except deny rules`() {
        val e = engine(StandardPermissionMode.BYPASS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeWrite", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("ShellExecute", "{}").effect)
    }

    @Test
    fun `accept_edits allows edit-family but still asks for shell`() {
        val e = engine(StandardPermissionMode.ACCEPT_EDITS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeEdit", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeWrite", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeGitCommit", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("ShellExecute", "{}").effect)
    }

    @Test
    fun `plan mode hard-denies writes regardless of allow rules`() {
        val e = engine(StandardPermissionMode.PLAN)
        // 显式 ALLOW 规则也不可越级（规划阶段零副作用硬承诺）
        e.updateRules(listOf(StandardPermissionRule("CodeWrite", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeWrite", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", "{}").effect)
    }

    // ═══ 会话记忆 ═══

    @Test
    fun `session memory allows subsequent calls without asking`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeWrite", "{}").effect)
        e.rememberSessionAllow("CodeWrite")
        val d = e.decide("CodeWrite", "{}")
        assertEquals(StandardPermissionEffect.ALLOW, d.effect)
        assertTrue(d.reason.contains("会话记忆"))
    }

    @Test
    fun `reset session memory restores asking`() {
        val e = engine()
        e.rememberSessionAllow("ShellExecute")
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("ShellExecute", "{}").effect)
        e.resetSessionMemory()
        assertEquals(StandardPermissionEffect.ASK, e.decide("ShellExecute", "{}").effect)
    }

    @Test
    fun `deny rule outranks session memory`() {
        val e = engine(StandardPermissionMode.BYPASS)
        e.rememberSessionAllow("CodeEdit")
        e.updateRules(listOf(StandardPermissionRule("CodeEdit", StandardPermissionEffect.DENY)))
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeEdit", "{}").effect)
    }

    // ═══ 子代理上下文 ═══

    @Test
    fun `ask folds to deny in sub-agent context`() {
        val e = engine()
        val d = e.decide("CodeWrite", "{}", subAgentContext = true)
        assertEquals(StandardPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("子代理"))
    }

    // ═══ 命令级通配 ═══

    @Test
    fun `shell command rules match command prefix`() {
        val e = engine(StandardPermissionMode.BYPASS)
        e.updateCommandRules(
            listOf(
                StandardPermissionRule("git status*", StandardPermissionEffect.ALLOW),
                StandardPermissionRule("rm*", StandardPermissionEffect.DENY)
            )
        )
        val ok = e.decide("ShellExecute", """{"command":"git status --short"}""")
        assertEquals(StandardPermissionEffect.ALLOW, ok.effect)

        val denied = e.decide("ShellExecute", """{"command":"rm -rf /data"}""")
        assertEquals(StandardPermissionEffect.DENY, denied.effect)
    }

    @Test
    fun `command session memory allows matching commands only`() {
        val e = engine()
        e.rememberSessionAllow("git status", isCommand = true)
        assertEquals(
            StandardPermissionEffect.ALLOW,
            e.decide("ShellExecute", """{"command":"git status"}""").effect
        )
        // 同工具不同命令仍走 ASK
        assertEquals(
            StandardPermissionEffect.ASK,
            e.decide("ShellExecute", """{"command":"git push"}""").effect
        )
    }

    @Test
    fun `command extraction from json handles escapes`() {
        val cmd = StandardPermissionEngine.extractCommand("""{"command":"echo \"hi\" && ls"}""")
        assertEquals("echo \"hi\" && ls", cmd)
        assertNull(StandardPermissionEngine.extractCommand("not json"))
        assertNull(StandardPermissionEngine.extractCommand("""{"path":"x"}"""))
    }

    @Test
    fun `command pattern without star matches first word`() {
        assertTrue(StandardPermissionEngine.commandPatternMatches("git", "git status"))
        assertTrue(StandardPermissionEngine.commandPatternMatches("git status", "git status"))
        assertTrue(!StandardPermissionEngine.commandPatternMatches("git push", "git status"))
    }

    // ═══ 静态判定 ═══

    @Test
    fun `readonly classification`() {
        assertTrue(StandardPermissionEngine.isReadOnlyTool("CodeRead"))
        assertTrue(StandardPermissionEngine.isReadOnlyTool("CodeTodo"))
        assertTrue(StandardPermissionEngine.isReadOnlyTool("McpGithubGet"))
        assertTrue(!StandardPermissionEngine.isReadOnlyTool("CodeWrite"))
        assertTrue(!StandardPermissionEngine.isReadOnlyTool("ShellExecute"))
    }

    @Test
    fun `tool id pattern matcher semantics`() {
        assertTrue(StandardPermissionEngine.matches("CodeRead", "CodeRead"))
        assertTrue(!StandardPermissionEngine.matches("CodeRead", "CodeRead2"))
        assertTrue(StandardPermissionEngine.matches("CodeGit*", "CodeGitLog"))
        assertTrue(!StandardPermissionEngine.matches("CodeGit*", "CodeGrep"))
        assertTrue(StandardPermissionEngine.matches("*", "whatever"))
        assertTrue(!StandardPermissionEngine.matches("", "whatever"))
    }

    @Test
    fun `update mode hot-switches`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeEdit", "{}").effect)
        e.updateMode(StandardPermissionMode.ACCEPT_EDITS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeEdit", "{}").effect)
        e.updateMode(StandardPermissionMode.PLAN)
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeEdit", "{}").effect)
        assertNotNull(e.currentMode())
    }

    // ═══ PLAN 档只读硬门（引擎级 planGate 参数，AgentMode.PLAN 档传入）═══

    @Test
    fun `plan gate denies writes even under bypass mode`() {
        // planGate 是引擎级硬门，与权限模式正交：BYPASS 下同样拒写
        val e = engine(StandardPermissionMode.BYPASS)
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeWrite", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeEdit", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.DENY, e.decide("ShellExecute", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeGitCommit", "{}", planGate = true).effect)
    }

    @Test
    fun `plan gate allows readonly tools`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeGrep", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeTodo", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("WebSearch", "{}", planGate = true).effect)
    }

    @Test
    fun `plan gate outranks allow rules and session memory`() {
        val e = engine()
        // 显式 ALLOW 规则也不可越级（硬门在规则/会话记忆之前）
        e.updateRules(listOf(StandardPermissionRule("CodeWrite", StandardPermissionEffect.ALLOW)))
        e.rememberSessionAllow("CodeEdit")
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeWrite", "{}", planGate = true).effect)
        assertEquals(StandardPermissionEffect.DENY, e.decide("CodeEdit", "{}", planGate = true).effect)
    }

    @Test
    fun `plan gate reason carries hard-gate attribution`() {
        val e = engine(StandardPermissionMode.BYPASS)
        val d = e.decide("CodeWrite", "{}", planGate = true)
        assertEquals(StandardPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("PLAN 档只读硬门"))
    }

    @Test
    fun `plan gate off keeps prior behavior`() {
        val e = engine()
        // 默认 false：既有裁决不受影响（既有调用点全部未传 planGate）
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeWrite", "{}", planGate = false).effect)
    }

    // ═══ 敏感文件保护（.env 族，业界标准 read 默认询问语义）═══

    private fun readArgs(path: String) = """{"path":"$path"}"""

    @Test
    fun `sensitive env file read asks for confirmation`() {
        val e = engine()
        val d = e.decide("CodeRead", readArgs("/app/.env"))
        assertEquals(StandardPermissionEffect.ASK, d.effect)
        assertTrue(d.reason.contains("敏感文件保护"))
        assertTrue(d.reason.contains(".env"))
    }

    @Test
    fun `env template files are not sensitive`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", readArgs(".env.example")).effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", readArgs("config/.env.sample")).effect)
    }

    @Test
    fun `env variants and key files are sensitive`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs(".env.local")).effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("/app/.env.production")).effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("keys/server.pem")).effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("signing.key")).effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("ssh/id_rsa")).effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("gcp/credentials.json")).effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("config/secrets.yaml")).effect)
    }

    @Test
    fun `bypass mode skips sensitive file protection`() {
        val e = engine(StandardPermissionMode.BYPASS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", readArgs("/app/.env")).effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeWrite", readArgs("/app/.env")).effect)
    }

    @Test
    fun `sub agent context folds sensitive ask to deny`() {
        val e = engine()
        val d = e.decide("CodeRead", readArgs("/app/.env"), subAgentContext = true)
        assertEquals(StandardPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("子代理"))
        assertTrue(d.reason.contains("敏感文件保护"))
    }

    @Test
    fun `explicit allow rule lets sensitive file through`() {
        val e = engine()
        // 保护位于规则裁决之后：用户显式放行即不拦
        e.updateRules(listOf(StandardPermissionRule("CodeRead", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", readArgs("/app/.env")).effect)
    }

    @Test
    fun `sensitive write still asks under accept_edits`() {
        val e = engine(StandardPermissionMode.ACCEPT_EDITS)
        // 编辑类本应自动放行 → 敏感文件保护拦截为 ASK
        val d = e.decide("CodeEdit", readArgs("deploy/.env"))
        assertEquals(StandardPermissionEffect.ASK, d.effect)
        assertTrue(d.reason.contains("敏感文件保护"))
        // 普通文件照常 ACCEPT_EDITS 放行
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeEdit", readArgs("deploy/App.kt")).effect)
    }

    @Test
    fun `session grant bypasses sensitive protection`() {
        val e = engine()
        // 会话记忆在保护之前：用户本会话总允许过 = 已授权
        e.rememberSessionAllow("CodeRead")
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", readArgs("/app/.env")).effect)
    }

    @Test
    fun `any path-bearing tool hitting sensitive path asks`() {
        val e = engine()
        // 非 CodeRead/write/edit 的工具（如 mcp 文件读取），参数带敏感路径同样拦截
        val d = e.decide("McpFsReadFile", readArgs("/app/secrets.yaml"))
        assertEquals(StandardPermissionEffect.ASK, d.effect)
        assertTrue(d.reason.contains("敏感文件保护"))
        // 参数不带路径字段的工具不受影响
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("CodeRead", "{}").effect)
    }

    @Test
    fun `plan mode still asks before sensitive read`() {
        val e = engine(StandardPermissionMode.PLAN)
        // 只读工具过 PLAN 硬门 → 敏感文件保护仍拦截（规划阶段也不该偷读密钥）
        assertEquals(StandardPermissionEffect.ASK, e.decide("CodeRead", readArgs("/app/.env")).effect)
    }

    // ═══ 路径提取与敏感判定（静态纯函数）═══

    @Test
    fun `path extraction from json supports known keys`() {
        assertEquals("/app/.env", StandardPermissionEngine.extractPath("""{"path":"/app/.env"}"""))
        assertEquals("/app/.env", StandardPermissionEngine.extractPath("""{"file_path":"/app/.env"}"""))
        assertEquals("/app/.env", StandardPermissionEngine.extractPath("""{"file":"/app/.env"}"""))
        assertEquals("a b/.env", StandardPermissionEngine.extractPath("""{"path":"a b/.env"}"""))
        assertEquals("a\"b/.env", StandardPermissionEngine.extractPath("""{"path":"a\"b/.env"}"""))
        assertEquals(".env", StandardPermissionEngine.extractPath("""{"pattern":"x","path":".env","offset":0}"""))
        assertNull(StandardPermissionEngine.extractPath("not json"))
        assertNull(StandardPermissionEngine.extractPath("""{"command":"cat .env"}"""))
        assertNull(StandardPermissionEngine.extractPath("""{"pattern":".env"}"""))
        assertNull(StandardPermissionEngine.extractPath("""{}"""))
    }

    @Test
    fun `sensitive path boundary matrix`() {
        // .env 族
        assertTrue(StandardPermissionEngine.isSensitivePath(".env"))
        assertTrue(StandardPermissionEngine.isSensitivePath("/home/u/app/.env"))
        assertTrue(StandardPermissionEngine.isSensitivePath(".env.local"))
        assertTrue(StandardPermissionEngine.isSensitivePath(".env.production"))
        assertTrue(StandardPermissionEngine.isSensitivePath("a/b/.env.development"))
        // 模板排除
        assertFalse(StandardPermissionEngine.isSensitivePath(".env.example"))
        assertFalse(StandardPermissionEngine.isSensitivePath("config/.env.sample"))
        // 长得像但不是 .env 族
        assertFalse(StandardPermissionEngine.isSensitivePath(".envrc"))
        assertFalse(StandardPermissionEngine.isSensitivePath("env.kt"))
        assertFalse(StandardPermissionEngine.isSensitivePath("build.env.example.txt"))
        assertFalse(StandardPermissionEngine.isSensitivePath("prod.env"))
        // 密钥/证书后缀
        assertTrue(StandardPermissionEngine.isSensitivePath("certs/server.pem"))
        assertTrue(StandardPermissionEngine.isSensitivePath("signing.key"))
        assertTrue(StandardPermissionEngine.isSensitivePath("app/release.keystore"))
        assertTrue(StandardPermissionEngine.isSensitivePath("app/signing.jks"))
        assertFalse(StandardPermissionEngine.isSensitivePath("Main.kt"))
        assertFalse(StandardPermissionEngine.isSensitivePath("keyboard.png"))
        // SSH 私钥族
        assertTrue(StandardPermissionEngine.isSensitivePath("~/.ssh/id_rsa"))
        assertTrue(StandardPermissionEngine.isSensitivePath("~/.ssh/id_rsa.pub"))
        assertFalse(StandardPermissionEngine.isSensitivePath("~/.ssh/id_ed25519"))
        // 凭据/密钥清单
        assertTrue(StandardPermissionEngine.isSensitivePath("gcp/credentials.json"))
        assertTrue(StandardPermissionEngine.isSensitivePath("secrets.json"))
        assertTrue(StandardPermissionEngine.isSensitivePath("config/secrets.yaml"))
        assertFalse(StandardPermissionEngine.isSensitivePath("secrets.yml"))
        assertFalse(StandardPermissionEngine.isSensitivePath("my-credentials.json"))
        // 边界：空/纯目录尾/大小写不敏感（保守取向）
        assertFalse(StandardPermissionEngine.isSensitivePath(""))
        assertFalse(StandardPermissionEngine.isSensitivePath("dir/"))
        assertTrue(StandardPermissionEngine.isSensitivePath(".ENV"))
        assertTrue(StandardPermissionEngine.isSensitivePath("SERVER.PEM"))
    }

    // ═══ 权限配置源 ═══

    @Test
    fun `none permission source yields default snapshot`() {
        val snap = StandardPermissionSource.NONE.snapshot()
        assertEquals(StandardPermissionMode.DEFAULT, snap.mode)
        assertTrue(snap.rules.isEmpty())
        assertTrue(snap.commandRules.isEmpty())
    }
}
