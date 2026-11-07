package com.apex.agent.platform.terminal.exec

import com.apex.agent.platform.TerminalExec.CommandSpawner
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.TerminalExec.ExecRequest
import com.apex.agent.platform.TerminalExec.PersistentFrameChannel
import com.apex.agent.platform.TerminalExec.ProcessBuilderSpawner
import com.apex.agent.platform.TerminalExec.SpawnRequest
import com.apex.agent.platform.TerminalExec.SpawnedCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DSH 桥传输层契约锁（2026-11 Apex DSH Native Plugin Host）：
 *
 * 1. [ExecEngine] 的 [ExecRequest.stdin] 一次性载荷语义（写→EOF、超限诚实失败、
 *    不支持通道诚实失败、写失败结构化上报、无载荷 EOF 语义不回退）；
 * 2. [PersistentFrameChannel] 长度前缀帧（roundtrip、破损 fail-fast、终态恰好一次）；
 * 3. CI 有 node 时的真实端到端（node 帧回显 fixture —— P0 持久 IO 验证的 JVM 侧）。
 *
 * 纯 JVM；真进程用例在无 node 的本机开发环境经 [Assume] 跳过（CI ubuntu runner 有）。
 */
class DshBridgeTransportTest {

    // ═══ ① ExecEngine stdin 语义 ══════════════════════════════════════

    private fun shEngine() = ExecEngine(ProcessBuilderSpawner(shell = "/bin/sh"))

    @Test
    fun `stdin payload reaches process then EOF`() = runBlocking<Unit> {
        val result = shEngine().execute(
            ExecRequest(command = "cat", stdin = "{\"tool\":\"dsh.demo\",\"args\":[1,2,3]}", timeoutMs = 15_000)
        )
        assertEquals(0, result.exitCode)
        assertEquals("{\"tool\":\"dsh.demo\",\"args\":[1,2,3]}", result.stdout)
        assertFalse(result.timedOut)
    }

    @Test
    fun `stdin payload preserves unicode quotes and newlines`() = runBlocking<Unit> {
        val payload = "{\"q\":\"中文 引号'\\\"\",\"n\":1}\n{\"second\":true}"
        val result = shEngine().execute(ExecRequest(command = "cat", stdin = payload, timeoutMs = 15_000))
        assertEquals(0, result.exitCode)
        assertEquals(payload, result.stdout)
    }

    @Test
    fun `no payload keeps immediate EOF semantics (cat exits, no hang)`() = runBlocking<Unit> {
        val result = shEngine().execute(ExecRequest(command = "cat", timeoutMs = 5_000))
        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertFalse("cat 必须因 EOF 立即退出，而不是挂到超时", result.timedOut)
    }

    @Test
    fun `oversized stdin fails honestly without truncation`() = runBlocking<Unit> {
        val payload = "x".repeat(ExecEngine.MAX_STDIN_BYTES + 1)
        val result = shEngine().execute(ExecRequest(command = "cat", stdin = payload, timeoutMs = 15_000))
        assertNull("超限载荷必须结构化失败，而不是截断后执行", result.exitCode)
        assertTrue(result.stderr.contains("exceeds limit"))
        assertTrue(result.stderr.contains("not truncated"))
    }

    @Test
    fun `channel without stdin plus payload is honest spawn failure`() = runBlocking<Unit> {
        val engine = ExecEngine(object : CommandSpawner {
            override val channel = "no-stdin-fake"
            override val supportsEnv = true
            override fun spawn(request: SpawnRequest): SpawnedCommand = object : SpawnedCommand {
                override val stdout = java.io.ByteArrayInputStream(ByteArray(0))
                override val stderr: InputStream? = null
                // stdin 用接口默认 null —— 不支持
                override fun waitFor(timeoutMs: Long) = true
                override fun exitValue() = 0
                override fun destroy() {}
            }
        })
        val result = engine.execute(ExecRequest(command = "true", stdin = "{}", timeoutMs = 5_000))
        assertNull(result.exitCode)
        assertTrue(result.stderr.contains("does not support stdin"))
    }

    @Test
    fun `stdin write failure is a structured failure`() = runBlocking<Unit> {
        val engine = ExecEngine(object : CommandSpawner {
            override val channel = "broken-stdin-fake"
            override val supportsEnv = true
            override fun spawn(request: SpawnRequest): SpawnedCommand = object : SpawnedCommand {
                override val stdout = java.io.ByteArrayInputStream(ByteArray(0))
                override val stderr: InputStream? = null
                override val stdin: OutputStream = object : OutputStream() {
                    override fun write(b: Int) = throw java.io.IOException("pipe broken")
                }
                override fun waitFor(timeoutMs: Long) = true
                override fun exitValue() = 0
                override fun destroy() {}
            }
        })
        val result = engine.execute(ExecRequest(command = "true", stdin = "{}", timeoutMs = 5_000))
        assertNull(result.exitCode)
        assertTrue(result.stderr.contains("failed to write stdin payload"))
        assertTrue(result.stderr.contains("pipe broken"))
    }

    // ═══ ② PersistentFrameChannel 帧语义 ═══════════════════════════════

    /** 管道对拼 fake：测试作对端（peer）写 stdout / 读 stdin。 */
    private class FakeProcess : SpawnedCommand {
        val stdinToProcess = PipedOutputStream()
        private val stdinFromEngine = PipedInputStream(stdinToProcess, 65_536)
        private val stdoutFromProcessWriter = PipedOutputStream()
        val stdoutToEngine = PipedInputStream(stdoutFromProcessWriter, 65_536)
        private val stderrFromProcessWriter = PipedOutputStream()
        val stderrToEngine = PipedInputStream(stderrFromProcessWriter, 8_192)

        @Volatile private var alive = true
        var exitCode = 0

        override val stdout: InputStream get() = stdoutToEngine
        override val stderr: InputStream? get() = stderrToEngine
        override val stdin: OutputStream get() = stdinFromEngine
        override fun waitFor(timeoutMs: Long): Boolean = alive
        override fun exitValue(): Int =
            if (alive) throw IllegalThreadStateException("still running") else exitCode
        override fun destroy() {
            alive = false
            runCatching { stdoutFromProcessWriter.close() }
            runCatching { stderrFromProcessWriter.close() }
            runCatching { stdinToProcess.close() }
        }

        /** 对端写一帧（长度前缀 + 载荷）。 */
        fun peerSend(payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            stdoutFromProcessWriter.write(byteArrayOf(
                (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()
            ))
            stdoutFromProcessWriter.write(bytes)
            stdoutFromProcessWriter.flush()
        }

        /** 对端伪造超限帧头（不写载荷）。 */
        fun peerSendBadLength(length: Int) {
            stdoutFromProcessWriter.write(byteArrayOf(
                (length ushr 24).toByte(), (length ushr 16).toByte(),
                (length ushr 8).toByte(), length.toByte()
            ))
            stdoutFromProcessWriter.flush()
        }

        fun peerCloseStdout() {
            runCatching { stdoutFromProcessWriter.close() }
        }
    }

    private class CollectingListener : PersistentFrameChannel.Listener {
        val frames = mutableListOf<String>()
        val exits = mutableListOf<Pair<Int?, String?>>()
        val frameLatch = CountDownLatch(1)
        val exitLatch = CountDownLatch(1)
        override fun onFrame(payload: String) {
            synchronized(frames) { frames.add(payload) }
            frameLatch.countDown()
        }
        override fun onExit(exitCode: Int?, cause: String?) {
            synchronized(exits) { exits.add(exitCode to cause) }
            exitLatch.countDown()
        }
    }

    @Test
    fun `frame roundtrip preserves payload`() {
        val proc = FakeProcess()
        val listener = CollectingListener()
        val channel = PersistentFrameChannel(proc, generation = 1, listener = listener)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            channel.start(scope)
            proc.peerSend("{\"seq\":1,\"text\":\"中文 ✓\"}")
            proc.peerSend("{\"seq\":2}")
            assertTrue(listener.frameLatch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("{\"seq\":1,\"text\":\"中文 ✓\"}", "{\"seq\":2}"), listener.frames.toList())
        } finally {
            channel.destroy()
            scope.cancel()
        }
    }

    @Test
    fun `oversized frame header breaks channel fail-fast`() {
        val proc = FakeProcess()
        val listener = CollectingListener()
        val channel = PersistentFrameChannel(proc, generation = 1, listener = listener)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            channel.start(scope)
            proc.peerSendBadLength(PersistentFrameChannel.DEFAULT_FRAME_MAX_BYTES + 1)
            assertTrue(listener.exitLatch.await(5, TimeUnit.SECONDS))
            assertTrue(listener.exits.single().second!!.contains("out of range"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `half frame EOF breaks channel honestly`() {
        val proc = FakeProcess()
        val listener = CollectingListener()
        val channel = PersistentFrameChannel(proc, generation = 1, listener = listener)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            channel.start(scope)
            proc.peerSend("{\"partial")
            proc.peerCloseStdout()
            assertTrue(listener.exitLatch.await(5, TimeUnit.SECONDS))
            assertTrue(listener.exits.single().second!!.contains("EOF mid-frame"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `send rejects oversized payload and closed channel`() = runBlocking<Unit> {
        val proc = FakeProcess()
        val listener = CollectingListener()
        val channel = PersistentFrameChannel(proc, generation = 1, listener = listener)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            channel.start(scope)
            val oversized = "x".repeat(PersistentFrameChannel.DEFAULT_FRAME_MAX_BYTES + 1)
            assertTrue(channel.send(oversized).isFailure)
            channel.destroy()
            assertTrue("终态恰好一次", listener.exitLatch.await(5, TimeUnit.SECONDS))
            assertTrue(channel.send("{}").isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `exit event is delivered exactly once`() {
        val proc = FakeProcess()
        val listener = CollectingListener()
        val channel = PersistentFrameChannel(proc, generation = 1, listener = listener)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            channel.start(scope)
            proc.peerCloseStdout()
            assertTrue(listener.exitLatch.await(5, TimeUnit.SECONDS))
            channel.destroy()
            Thread.sleep(200)
            assertEquals("EOF + destroy 只允许一次终态上报", 1, listener.exits.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `merged stderr channel is rejected up front`() {
        val proc = FakeProcess()
        val merged = object : SpawnedCommand by proc {
            override val stderr: InputStream? = null
        }
        val catching = CollectingListener()
        val channel = PersistentFrameChannel(merged, generation = 1, listener = catching)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // stderr 未分离的句柄直接启动：立即 fail-closed（帧流不可信）
            channel.start(scope)
            assertTrue(catching.exitLatch.await(5, TimeUnit.SECONDS))
            assertTrue(catching.exits.single().second!!.contains("stderr not separated"))
        } finally {
            scope.cancel()
        }
    }

    // ═══ ③ 真实 node 端到端（CI 有 node；本机无则跳过）══════════════════

    private fun nodeAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("node", "--version").start()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    @Test
    fun `real node frame echo end to end`() = runBlocking<Unit> {
        assumeTrue("本机无 node —— CI ubuntu runner 必跑", nodeAvailable())
        val script = File.createTempFile("dsh-frame-echo", ".js")
        script.deleteOnExit()
        script.writeText(
            """
            function send(obj) {
              const p = Buffer.from(JSON.stringify(obj), 'utf8');
              const h = Buffer.alloc(4);
              h.writeUInt32BE(p.length, 0);
              process.stdout.write(Buffer.concat([h, p]));
            }
            let buf = Buffer.alloc(0);
            process.stdin.on('data', (d) => {
              buf = Buffer.concat([buf, d]);
              while (buf.length >= 4) {
                const len = buf.readUInt32BE(0);
                if (buf.length < 4 + len) break;
                const payload = buf.slice(4, 4 + len).toString('utf8');
                buf = buf.slice(4 + len);
                let msg;
                try { msg = JSON.parse(payload); } catch (e) { send({ error: 'bad json' }); continue; }
                if (msg.type === 'echo') send({ type: 'echo_ack', seq: msg.seq, text: msg.text });
                else if (msg.type === 'die') process.exit(7);
              }
            });
            process.stdin.on('end', () => process.exit(0));
            console.error('frame-echo ready (stderr 旁路，不得污染帧流)');
            """.trimIndent()
        )
        val spawned = ProcessBuilderSpawner(shell = "/bin/sh")
            .spawn(SpawnRequest(command = "node \"${script.absolutePath}\"", cwd = null, env = emptyMap()))
        val listener = object : PersistentFrameChannel.Listener {
            val acks = mutableListOf<String>()
            val firstAck = CountDownLatch(1)
            @Volatile var exitCode: Int? = null
            @Volatile var exitCause: String? = null
            val exit = CountDownLatch(1)
            override fun onFrame(payload: String) {
                synchronized(acks) { acks.add(payload) }
                firstAck.countDown()
            }
            override fun onExit(code: Int?, cause: String?) {
                exitCode = code; exitCause = cause; exit.countDown()
            }
        }
        val channel = PersistentFrameChannel(spawned, generation = 1, listener = listener)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            channel.start(scope)
            channel.send("{\"type\":\"echo\",\"seq\":1,\"text\":\"中文 帧 ✓\"}").getOrThrow()
            channel.send("{\"type\":\"echo\",\"seq\":2,\"text\":\"plain\"}").getOrThrow()
            assertTrue(listener.firstAck.await(20, TimeUnit.SECONDS))
            // stderr 日志行与帧流并发到达 —— 给读协程一点时间确认没有污染帧解析
            Thread.sleep(300)
            val acks = synchronized(listener.acks) { listener.acks.toList() }
            assertEquals(2, acks.size)
            assertTrue(acks[0].contains("\"echo_ack\""))
            assertTrue(acks[0].contains("中文 帧 ✓"))
            assertTrue(acks[1].contains("\"seq\":2"))
            channel.send("{\"type\":\"die\"}").getOrThrow()
            assertTrue(listener.exit.await(20, TimeUnit.SECONDS))
            assertEquals("die 路径退出码必须如实", 7, listener.exitCode)
        } finally {
            channel.destroy()
            scope.cancel()
        }
    }
}
