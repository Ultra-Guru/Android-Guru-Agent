package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #205 单测：[McpStdioTransport] 的真实进程事件（#197 spawn + #205 stderr）。
 *
 * 全部用真实 /bin/sh 子进程 —— pid / argv / stderr 都是操作系统给的真值。
 */
class McpStdioStartupEventsTest {

    @Test
    fun `spawn callback reports real pid and exact argv`() {
        val spawns = CopyOnWriteArrayList<Pair<Long?, List<String>>>()
        val transport = McpStdioTransport(
            command = listOf("/bin/sh", "-c", "exit 0"),
            requestTimeoutMs = 5_000L,
            onSpawned = { pid, argv -> spawns += pid to argv }
        )
        try {
            assertEquals(1, spawns.size)
            val (pid, argv) = spawns.single()
            // 真实子进程 pid（>0），argv 原样回传
            assertNotNull(pid)
            assertTrue(pid!! > 0)
            assertEquals(listOf("/bin/sh", "-c", "exit 0"), argv)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `stderr lines are forwarded to the listener`() {
        val stderrLines = CopyOnWriteArrayList<String>()
        val transport = McpStdioTransport(
            command = listOf("/bin/sh", "-c", "echo boom >&2; sleep 0.2"),
            requestTimeoutMs = 5_000L,
            onStderrLine = { stderrLines += it }
        )
        try {
            // 等子进程退出（stderr 关闭 → 泵线程 EOF）
            val deadline = System.currentTimeMillis() + 5_000
            while (stderrLines.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf("boom"), stderrLines.toList().filter { it.isNotBlank() })
        } finally {
            transport.close()
        }
    }

    @Test
    fun `exit code surfaces as process death not hang`() {
        // 子进程立即退出 → isHealthy=false；send 抛「进程已退出」而不是挂死。
        val transport = McpStdioTransport(
            command = listOf("/bin/sh", "-c", "exit 3"),
            requestTimeoutMs = 5_000L
        )
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (transport.isHealthy() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(!transport.isHealthy())
            val result = runCatching {
                runBlocking { transport.send(1, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""") }
            }
            assertTrue(result.isFailure)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `null listener spawn still spawns the process`() {
        // events=null 是合法形态（零开销直通）—— 进程照常启动。
        val transport = McpStdioTransport(
            command = listOf("/bin/sh", "-c", "sleep 0.3"),
            requestTimeoutMs = 5_000L,
            onSpawned = null
        )
        try {
            assertTrue(transport.isHealthy())
        } finally {
            transport.close()
        }
    }

    @Test
    fun `fake launcher returning null pid reports spawn with null pid`() {
        // 自定义句柄（如 App 进程内桥）拿不到 pid → 如实上报 null，不编造。
        val spawns = CopyOnWriteArrayList<Pair<Long?, List<String>>>()
        val transport = McpStdioTransport(
            command = listOf("/bin/sh", "-c", "sleep 0.3"),
            launcher = { command, _, _ ->
                val real = JvmProcessLauncher.launch(command, emptyMap(), null)
                object : McpProcessHandle by real {
                    override val pid: Long? get() = null
                }
            },
            requestTimeoutMs = 5_000L,
            onSpawned = { pid, argv -> spawns += pid to argv }
        )
        try {
            val (pid, _) = spawns.single()
            assertEquals(null, pid)
        } finally {
            transport.close()
        }
    }
}
