package com.apex.agent.core.tools.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #205 单测：[McpConfigValidator] 的 12 个稳定错误码。
 *
 * 宿主命令探针 [McpConfigValidator.hostCommandExists] 在纯 JVM 上验证：
 * `java`（宿主 JVM 自带）与 `/bin/sh`（绝对可执行路径）零误报。
 */
class McpConfigValidatorTest {

    private fun config(
        name: String = "memory",
        transport: McpTransport = McpTransport.STDIO,
        command: String? = "npx",
        args: List<String> = listOf("-y", "@modelcontextprotocol/server-memory"),
        url: String = "",
        env: Map<String, String> = emptyMap(),
        runInSandbox: Boolean = false
    ) = McpServerConfig(
        name = name, transport = transport, command = command, args = args,
        url = url, env = env, runInSandbox = runInSandbox
    )

    private fun codes(findings: List<McpConfigValidator.Finding>) = findings.map { it.code }.toSet()

    // ═══ 名称 ═══

    @Test
    fun `blank name is an error`() {
        val findings = McpConfigValidator.validate(config(name = "  "))
        assertEquals(setOf(McpConfigValidator.Code.NAME_BLANK), codes(findings))
    }

    @Test
    fun `duplicate name against existing configs is an error`() {
        val findings = McpConfigValidator.validate(
            config(name = "memory"), existingNames = setOf("memory", "fs")
        )
        assertTrue(McpConfigValidator.Code.NAME_DUPLICATE in codes(findings))
    }

    // ═══ BUILTIN ═══

    @Test
    fun `builtin transport requires registered factory name`() {
        val findings = McpConfigValidator.validate(
            config(name = "ghost", transport = McpTransport.BUILTIN, command = null),
            builtinNames = setOf("github", "fs", "memory", "search", "thinking")
        )
        assertTrue(McpConfigValidator.Code.BUILTIN_UNREGISTERED in codes(findings))
        // 已注册的内置名不报
        val ok = McpConfigValidator.validate(
            config(name = "github", transport = McpTransport.BUILTIN, command = null),
            builtinNames = setOf("github")
        )
        assertTrue(ok.isEmpty())
    }

    // ═══ STDIO ═══

    @Test
    fun `stdio without command is an error`() {
        val findings = McpConfigValidator.validate(config(command = null))
        assertEquals(setOf(McpConfigValidator.Code.STDIO_NO_COMMAND), codes(findings))
    }

    @Test
    fun `bare command without args warns`() {
        val findings = McpConfigValidator.validate(
            config(command = "npx", args = emptyList()),
            hostLookup = { true }
        )
        val f = findings.single()
        assertEquals(McpConfigValidator.Code.EMPTY_ARGS, f.code)
        assertEquals(McpConfigValidator.Severity.WARN, f.severity)
    }

    @Test
    fun `absolute path command that does not exist is an error`() {
        val findings = McpConfigValidator.validate(
            config(command = "/definitely/not/here/npx"),
            hostLookup = { false }
        )
        assertTrue(McpConfigValidator.Code.COMMAND_NOT_FOUND in codes(findings))
    }

    @Test
    fun `missing host runtime is a warning suggesting sandbox`() {
        val findings = McpConfigValidator.validate(
            config(command = "npx"), hostLookup = { false }
        )
        val f = findings.single()
        assertEquals(McpConfigValidator.Code.HOST_RUNTIME_UNAVAILABLE, f.code)
        assertEquals(McpConfigValidator.Severity.WARN, f.severity)
    }

    // ═══ 沙箱 ═══

    @Test
    fun `sandbox not ready is an error when probe reports false`() {
        val findings = McpConfigValidator.validate(
            config(runInSandbox = true), sandboxReady = false, hostLookup = { true }
        )
        assertTrue(McpConfigValidator.Code.SANDBOX_NOT_READY in codes(findings))
    }

    @Test
    fun `sandbox on non-stdio transport is an error`() {
        val findings = McpConfigValidator.validate(
            config(transport = McpTransport.HTTP, url = "https://x.example/mcp", command = null, runInSandbox = true)
        )
        assertTrue(McpConfigValidator.Code.SANDBOX_NON_STDIO in codes(findings))
    }

    // ═══ 远端 ═══

    @Test
    fun `remote without url is an error`() {
        val findings = McpConfigValidator.validate(
            config(transport = McpTransport.HTTP, command = null, url = " ")
        )
        assertEquals(setOf(McpConfigValidator.Code.REMOTE_NO_URL), codes(findings))
    }

    @Test
    fun `non-http scheme url is an error`() {
        val findings = McpConfigValidator.validate(
            config(transport = McpTransport.HTTP, command = null, url = "ftp://x.example/mcp")
        )
        assertEquals(setOf(McpConfigValidator.Code.REMOTE_BAD_URL), codes(findings))
    }

    // ═══ 环境变量注入 ═══

    @Test
    fun `env key with equals sign is an error`() {
        val findings = McpConfigValidator.validate(
            config(env = mapOf("BAD=KEY" to "v"))
        )
        assertTrue(McpConfigValidator.Code.ENV_INJECTION_CHARS in codes(findings))
    }

    @Test
    fun `env value with newline is an error`() {
        val findings = McpConfigValidator.validate(
            config(env = mapOf("K" to "line1\nline2"))
        )
        assertTrue(McpConfigValidator.Code.ENV_INJECTION_CHARS in codes(findings))
    }

    // ═══ 真实宿主探针（纯 JVM，零误报锚点）═══

    @Test
    fun `hostCommandExists finds java on PATH`() {
        // 宿主 JVM 测试进程自身就是 java 起的 —— PATH 必含。
        assertTrue(McpConfigValidator.hostCommandExists("java"))
    }

    @Test
    fun `hostCommandExists resolves absolute sh`() {
        // /bin/sh 在所有 POSIX CI/Linux 宿主上可执行。
        val sh = File("/bin/sh")
        org.junit.Assume.assumeTrue(sh.canExecute())
        assertTrue(McpConfigValidator.hostCommandExists("/bin/sh"))
    }

    @Test
    fun `hostCommandExists rejects unknown command`() {
        assertFalse(McpConfigValidator.hostCommandExists("definitely-not-a-command-xyz"))
    }

    @Test
    fun `hostCommandExists blank is false`() {
        assertFalse(McpConfigValidator.hostCommandExists("   "))
    }

    // ═══ 汇总视图 ═══

    @Test
    fun `errorsOf keeps only severity error`() {
        val findings = listOf(
            McpConfigValidator.Finding(McpConfigValidator.Code.EMPTY_ARGS, McpConfigValidator.Severity.WARN, "w"),
            McpConfigValidator.Finding(McpConfigValidator.Code.STDIO_NO_COMMAND, McpConfigValidator.Severity.ERROR, "e")
        )
        assertEquals(1, McpConfigValidator.errorsOf(findings).size)
        assertEquals(McpConfigValidator.Code.STDIO_NO_COMMAND, McpConfigValidator.errorsOf(findings).single().code)
    }
}
