package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.TerminalFs.InMemoryKnownHostsStore
import com.apex.agent.platform.TerminalFs.RemoteSshFilesystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalSshFsTool 输入校验 / 结构化错误路径单测（不连真实 SSH）。
 *
 * 测试面：路径缺失 → SshFsError:InvalidInput；remove 根目录 → SshFsError:PathDenied；
 * 未知 action → SshFsError:InvalidInput；JSON 解析 → ok=false。
 * 真实 SSH 往返留给 CI 的 docker sshd（如有）—— 本地单元测试不做网络 IO。
 */
class TerminalSshFsToolTest {

    private val fs = RemoteSshFilesystem(
        knownHosts = InMemoryKnownHostsStore(),
        connectTimeoutMs = 100,
        timeoutMs = 200
    )
    private val tool = TerminalSshFsTool(fs)

    private fun json(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            if (v.startsWith("{") || v.startsWith("[")) "\"$k\":$v" else "\"$k\":\"$v\""
        }

    @Test
    fun `read without path returns InvalidInput`() = runTest {
        val out = tool.execute(TerminalSshFsTool.Input(
            action = "read", host = "h", port = 22, username = "u",
            password = "p", privateKey = null, passphrase = null,
            trustUnknownHost = true, path = null, content = null,
            target = null, recursive = false
        ))
        assertFalse(out.ok)
        assertEquals("SshFsError:InvalidInput", out.code)
    }

    @Test
    fun `remove root returns PathDenied without network`() = runTest {
        val out = tool.execute(TerminalSshFsTool.Input(
            action = "remove", host = "h", port = 22, username = "u",
            password = "p", privateKey = null, passphrase = null,
            trustUnknownHost = true, path = "/", content = null,
            target = null, recursive = true
        ))
        assertFalse(out.ok)
        assertEquals("SshFsError:PathDenied", out.code)
    }

    @Test
    fun `unknown action returns InvalidInput`() = runTest {
        val out = tool.execute(TerminalSshFsTool.Input(
            action = "yolo", host = "h", port = 22, username = "u",
            password = "p", privateKey = null, passphrase = null,
            trustUnknownHost = true, path = "/tmp", content = null,
            target = null, recursive = false
        ))
        assertFalse(out.ok)
        assertEquals("SshFsError:InvalidInput", out.code)
    }

    @Test
    fun `move without target returns InvalidInput`() = runTest {
        val out = tool.execute(TerminalSshFsTool.Input(
            action = "move", host = "h", port = 22, username = "u",
            password = "p", privateKey = null, passphrase = null,
            trustUnknownHost = true, path = "/a", content = null,
            target = null, recursive = false
        ))
        assertFalse(out.ok)
        assertEquals("SshFsError:InvalidInput", out.code)
        assertTrue(out.message!!.contains("target required"))
    }

    @Test
    fun `missing host for non-fingerprint action returns InvalidInput`() = runTest {
        val out = tool.execute(TerminalSshFsTool.Input(
            action = "read", host = "", port = 22, username = "u",
            password = "p", privateKey = null, passphrase = null,
            trustUnknownHost = true, path = "/etc/hosts", content = null,
            target = null, recursive = false
        ))
        assertFalse(out.ok)
        assertEquals("SshFsError:InvalidInput", out.code)
    }

    @Test
    fun `invoke JSON output carries ok false for invalid input`() = runTest {
        val args = json(
            "action" to "read",
            "host" to "h", "username" to "u", "password" to "p",
            "trustUnknownHost" to "true"
            // 故意缺 path
        )
        val raw = tool.invoke(args)
        assertTrue(raw.contains("\"ok\":false"))
        assertTrue(raw.contains("SshFsError:InvalidInput"))
    }

    @Test
    fun `description and schema are non-empty and action enum covers all verbs`() {
        assertTrue(tool.description.contains("ping"))
        assertTrue(tool.description.contains("fingerprint"))
        assertTrue(tool.parametersSchema.contains("\"ping\""))
        assertTrue(tool.parametersSchema.contains("\"fingerprint\""))
    }
}
