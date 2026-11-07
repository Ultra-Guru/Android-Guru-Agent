package com.apex.agent.platform.terminal.tools

import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.proot.PRootSmokeProbe
import com.apex.agent.platform.terminal.runtime.BackendRuntimeType
import com.apex.agent.platform.terminal.runtime.ExecutionBackend
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.v2.TerminalPRootSmokeTool
import com.apex.agent.platform.terminal.tools.v2.TerminalBackendsTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * T73 — TerminalBackends 工具：后端能力发现的 Agent 出口。
 * 输出契约：{ backends: [ { id, runtimeType, available, state, detail? } ] }
 */
class TerminalBackendsToolTest {

    private class FakeBackend(
        override val id: String,
        override val runtimeType: BackendRuntimeType,
        private val av: BackendAvailability
    ) : ExecutionBackend {
        override suspend fun availability(): BackendAvailability = av
        override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> =
            Result.failure(RuntimeException("unused"))
    }

    private class FakeRuntime(private val statuses: List<TerminalRuntime.BackendStatus>) : TerminalRuntime {
        override suspend fun shutdown(): Result<com.apex.agent.platform.terminal.runtime.TerminalRuntime.ShutdownResult> =
            Result.success(com.apex.agent.platform.terminal.runtime.TerminalRuntime.ShutdownResult(0, 0, true))

        override suspend fun backends(): List<TerminalRuntime.BackendStatus> = statuses
        // 其余门面操作在工具测试中不可达 —— 工具只调 backends()。
        override suspend fun create(cwd: String, rows: Int, cols: Int, env: Map<String, String>, privilege: com.apex.agent.platform.terminal.policy.PrivilegeLevel, backendId: String, workspaceId: String?): Result<TerminalRuntime.CreateResult> = throw UnsupportedOperationException()
        override suspend fun run(sessionId: Long, command: String, owner: com.apex.agent.platform.terminal.io.InputOwner, background: Boolean, timeoutMs: Long): Result<TerminalRuntime.RunResult> = Result.failure(UnsupportedOperationException())
        override suspend fun observe(sessionId: Long, mode: TerminalRuntime.ObserveMode, afterCursor: Long, maxBytes: Int, maxEvents: Int, scrollbackLines: Int): Result<TerminalRuntime.ObserveResult> = Result.failure(UnsupportedOperationException())
        override suspend fun wait(sessionId: Long, condition: com.apex.agent.platform.TerminalWait.WaitCondition, timeoutMs: Long): Result<com.apex.agent.platform.TerminalWait.WaitResult> = Result.failure(UnsupportedOperationException())
        override suspend fun write(sessionId: Long, owner: com.apex.agent.platform.terminal.io.InputOwner, kind: TerminalRuntime.WriteKind, text: String?, key: com.apex.agent.platform.terminal.io.TerminalKey?, bytes: ByteArray?): Result<TerminalRuntime.WriteResult> = Result.failure(UnsupportedOperationException())
        override suspend fun signal(sessionId: Long, signal: com.apex.agent.platform.terminal.io.UnixSignal, owner: com.apex.agent.platform.terminal.io.InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = Result.failure(UnsupportedOperationException())
        override suspend fun signalForeground(sessionId: Long, signal: com.apex.agent.platform.terminal.io.UnixSignal, owner: com.apex.agent.platform.terminal.io.InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = Result.failure(UnsupportedOperationException())
        override suspend fun cancel(sessionId: Long, jobId: Long): Result<TerminalRuntime.CancelResult> = Result.failure(UnsupportedOperationException())
        override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<TerminalRuntime.ResizeResult> = Result.failure(UnsupportedOperationException())
        override suspend fun snapshot(mode: TerminalRuntime.SnapshotMode, sessionId: Long?, recentEvents: Int, recentOutputBytes: Int): Result<TerminalRuntime.SnapshotResult> = Result.failure(UnsupportedOperationException())
        override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> = Result.failure(UnsupportedOperationException())
        override suspend fun close(sessionId: Long, force: Boolean): Result<TerminalRuntime.CloseResult> = Result.failure(UnsupportedOperationException())
        override fun screenStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.screen.TerminalScreenState>? = null
        override fun semanticStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.state.TerminalSemanticState>? = null
        override fun styledScreenFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.terminalemulator.TerminalRenderSnapshot?>? = null
        override fun terminalEventFlow(sessionId: Long, afterCursor: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.events.TerminalEvent>? = null
        override suspend fun recover(): List<Long> = emptyList()
        override suspend fun recoveredSnapshot(sessionId: Long): com.apex.agent.platform.terminal.state.TerminalSemanticState? = null
    }

    private fun parse(json: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["backends"]!!.jsonArray

    @Test
    fun `ready and needs-rootfs backends serialize honestly`() = runBlocking {
        val tool = TerminalBackendsTool(
            FakeRuntime(
                listOf(
                    TerminalRuntime.BackendStatus("local", "LINUX", true, "READY"),
                    TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", false, "NEEDS_ROOTFS:not_provisioned", "先调用 TerminalUbuntuEnsure")
                )
            )
        )
        val arr = parse(tool.invoke("{}"))
        assertEquals(2, arr.size)

        val local = arr[0].jsonObject
        assertEquals("local", local["id"]!!.jsonPrimitive.content)
        assertEquals("true", local["available"]!!.jsonPrimitive.content)
        assertEquals("READY", local["state"]!!.jsonPrimitive.content)
        assertNull(local["detail"])

        val ubuntu = arr[1].jsonObject
        assertEquals("linux-ubuntu", ubuntu["id"]!!.jsonPrimitive.content)
        assertEquals("false", ubuntu["available"]!!.jsonPrimitive.content)
        assertEquals("NEEDS_ROOTFS:not_provisioned", ubuntu["state"]!!.jsonPrimitive.content)
        assertEquals("先调用 TerminalUbuntuEnsure", ubuntu["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty arguments tolerated`() = runBlocking {
        val tool = TerminalBackendsTool(FakeRuntime(emptyList()))
        val arr = parse(tool.invoke(""))
        assertEquals(0, arr.size)
    }

    @Test
    fun `failed backend carries reason in detail`() = runBlocking {
        val tool = TerminalBackendsTool(
            FakeRuntime(listOf(TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", false, "FAILED", "PRootError:BINARY_NOT_FOUND")))
        )
        val ubuntu = parse(tool.invoke("{}"))[0].jsonObject
        assertEquals("FAILED", ubuntu["state"]!!.jsonPrimitive.content)
        assertEquals("PRootError:BINARY_NOT_FOUND", ubuntu["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hung smoke appends advice without changing registered backend order or routing`() = runTest {
        val runtime = FakeRuntime(listOf(
            TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", true, "READY"),
            TerminalRuntime.BackendStatus("linux-ubuntu-chroot", "LINUX", true, "READY")
        ))
        val tool = TerminalBackendsTool(runtime, smokeProbe = {
            PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.TIMED_OUT)
        })
        val json = kotlinx.serialization.json.Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        val backends = json["backends"]!!.jsonArray
        assertEquals(listOf("linux-ubuntu", "linux-ubuntu-chroot"),
            backends.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("READY", backends[0].jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("timed_out", backends[0].jsonObject["smoke"]!!.jsonPrimitive.content)
        val advice = json["suggestion"]!!.jsonPrimitive.content
        assertTrue(advice.contains("likely"))
        assertTrue(advice.contains("linux-ubuntu-chroot"))
        assertTrue(advice.contains("if superuser is granted"))
    }

    @Test
    fun `ordinary binary error and healthy smoke never advise switching`() = runTest {
        val runtime = FakeRuntime(listOf(TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", true, "READY")))
        for ((status, expected) in listOf(
            PRootSmokeProbe.Status.OK to "ok",
            PRootSmokeProbe.Status.BINARY_ERROR to "binary_error"
        )) {
            val tool = TerminalBackendsTool(runtime, smokeProbe = {
                PRootSmokeProbe.SmokeResult(status, if (status == PRootSmokeProbe.Status.BINARY_ERROR) 127 else 0)
            })
            val json = kotlinx.serialization.json.Json.parseToJsonElement(tool.invoke("{}")).jsonObject
            assertEquals(expected, json["backends"]!!.jsonArray[0].jsonObject["smoke"]!!.jsonPrimitive.content)
            assertNull("$status is not evidence for ptrace advice", json["suggestion"])
        }
    }

    @Test
    fun `availability-only discovery never starts the runtime probe`() = runTest {
        var probes = 0
        val tool = TerminalBackendsTool(
            FakeRuntime(listOf(TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", false, "NEEDS_ROOTFS"))),
            smokeProbe = { probes++; PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.OK) }
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals(0, probes)
        assertNull(json["suggestion"])
        assertNull(json["backends"]!!.jsonArray[0].jsonObject["smoke"])
    }

    @Test
    fun `ready discovery cannot execute guest code on the read-only permission path`() = runTest {
        var launches = 0
        val tool = TerminalBackendsTool(
            FakeRuntime(listOf(TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", true, "READY"))),
            smokeProbe = { launches++; PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.TIMED_OUT) }
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("read-only discovery must not execute /bin/true", 0, launches)
        assertNull("no probe has run yet", json["backends"]!!.jsonArray[0].jsonObject["smoke"])
        assertNull("no uncached diagnosis", json["suggestion"])
    }

    @Test
    fun `guest execution is explicit tool with mutating annotations and explicit smoke`() = runTest {
        // T99 评审 B1 修复回归锁：只读发现永不跑 guest 探针；真正的执行探测
        // 是独立的显式工具，注解保持非只读（PLAN 拒绝），输出仍是建议不改路由。
        var launches = 0
        val runtime = FakeRuntime(listOf(
            TerminalRuntime.BackendStatus("linux-ubuntu", "LINUX", true, "READY")
        ))
        val smoke = TerminalPRootSmokeTool(runtime) {
            launches++; PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.PTRACE_SIGNAL, 159)
        }

        val json = kotlinx.serialization.json.Json.parseToJsonElement(smoke.invoke("{}")).jsonObject
        assertEquals(1, launches)
        assertEquals("TerminalPRootSmoke", json["id"]!!.jsonPrimitive.content)
        assertEquals("true", json["proot"]!!.jsonPrimitive.content)
        assertEquals("ptrace_signal", json["smoke"]!!.jsonPrimitive.content)
        assertEquals(159, json["exitCode"]!!.jsonPrimitive.content.toInt())
        val advice = json["suggestion"]!!.jsonPrimitive.content
        assertTrue(advice.contains("likely"))
        assertTrue(advice.contains("linux-ubuntu-chroot"))

        // 只读发现保持零执行（同请求上下文）。
        val backends = TerminalBackendsTool(runtime, smokeProbe = {
            launches++; PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.TIMED_OUT)
        })
        backends.invoke("{}")
        assertEquals("read-only request never launches the tracer", 1, launches)
    }
}
