package com.apex.agent.platform.terminal.tools

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.state.InputSnapshot
import com.apex.agent.platform.terminal.state.ScreenSnapshot
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.state.SessionSnapshot
import com.apex.agent.platform.terminal.state.InputState
import com.apex.agent.platform.terminal.io.InputControlState
import com.apex.agent.platform.terminal.tools.v2.TerminalDiagnosticsTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T87：TerminalDiagnostics 工具契约 —— 会话/后端面 + exec 探针自证。
 */
class TerminalDiagnosticsToolTest {

    // ── 最小 Runtime 假体（只实现 backends + snapshot；其余抛 Unsupported）──
    private class FakeRuntime(
        private val statuses: List<TerminalRuntime.BackendStatus>,
        private val sessions: List<TerminalSemanticState>
    ) : TerminalRuntime {
        override suspend fun shutdown(): Result<TerminalRuntime.ShutdownResult> =
            Result.success(TerminalRuntime.ShutdownResult(0, 0, true))
        override suspend fun backends(): List<TerminalRuntime.BackendStatus> = statuses
        override suspend fun snapshot(mode: TerminalRuntime.SnapshotMode, sessionId: Long?, recentEvents: Int, recentOutputBytes: Int): Result<TerminalRuntime.SnapshotResult> =
            Result.success(TerminalRuntime.SnapshotResult(sessions, 0L, emptyList(), ""))
        override suspend fun create(cwd: String, rows: Int, cols: Int, env: Map<String, String>, privilege: PrivilegeLevel, backendId: String, workspaceId: String?): Result<TerminalRuntime.CreateResult> = throw UnsupportedOperationException()
        override suspend fun run(sessionId: Long, command: String, owner: InputOwner, background: Boolean, timeoutMs: Long): Result<TerminalRuntime.RunResult> = throw UnsupportedOperationException()
        override suspend fun observe(sessionId: Long, mode: TerminalRuntime.ObserveMode, afterCursor: Long, maxBytes: Int, maxEvents: Int, scrollbackLines: Int): Result<TerminalRuntime.ObserveResult> = throw UnsupportedOperationException()
        override suspend fun wait(sessionId: Long, condition: com.apex.agent.platform.TerminalWait.WaitCondition, timeoutMs: Long): Result<com.apex.agent.platform.TerminalWait.WaitResult> = throw UnsupportedOperationException()
        override suspend fun write(sessionId: Long, owner: InputOwner, kind: TerminalRuntime.WriteKind, text: String?, key: TerminalKey?, bytes: ByteArray?): Result<TerminalRuntime.WriteResult> = throw UnsupportedOperationException()
        override suspend fun signal(sessionId: Long, signal: UnixSignal, owner: InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = throw UnsupportedOperationException()
        override suspend fun signalForeground(sessionId: Long, signal: UnixSignal, owner: InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = throw UnsupportedOperationException()
        override suspend fun cancel(sessionId: Long, jobId: Long): Result<TerminalRuntime.CancelResult> = throw UnsupportedOperationException()
        override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<TerminalRuntime.ResizeResult> = throw UnsupportedOperationException()
        override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> = throw UnsupportedOperationException()
        override suspend fun close(sessionId: Long, force: Boolean): Result<TerminalRuntime.CloseResult> = throw UnsupportedOperationException()
        override fun screenStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.screen.TerminalScreenState>? = null
        override fun semanticStateFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<TerminalSemanticState>? = null
        override fun styledScreenFlow(sessionId: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.terminalemulator.TerminalRenderSnapshot?>? = null
        override fun terminalEventFlow(sessionId: Long, afterCursor: Long): kotlinx.coroutines.flow.Flow<com.apex.agent.platform.terminal.events.TerminalEvent>? = null
        override suspend fun recover(): List<Long> = emptyList()
        override suspend fun recoveredSnapshot(sessionId: Long): TerminalSemanticState? = null
    }

    private fun semantic(id: Long, state: SessionState, shell: String = "/bin/bash"): TerminalSemanticState =
        TerminalSemanticState(
            session = SessionSnapshot(
                id = id, shell = shell, cwd = "/workspace", privilege = PrivilegeLevel.NORMAL,
                state = state, pid = 1000 + id.toInt(), rows = 24, cols = 80,
                createdAt = 0L, lastExitCode = null, cursor = 0L
            ),
            process = null,
            screen = ScreenSnapshot(24, 80, 0, 0, false, null),
            input = InputSnapshot(InputState.UNKNOWN, InputControlState.FREE),
            foregroundJob = null,
            backgroundJobs = emptyList()
        )

    private fun backend(id: String, state: String): TerminalRuntime.BackendStatus =
        TerminalRuntime.BackendStatus(
            id = id,
            runtimeType = "LINUX",
            available = state == "READY",
            state = state,
            detail = if (state == "FAILED") "proot binary missing" else null
        )

    @Test
    fun `healthy stack reports ok with sessions and backends`() = runBlocking {
        val runtime = FakeRuntime(
            listOf(backend("linux-ubuntu", "READY")),
            listOf(semantic(1, SessionState.READY))
        )
        val tool = TerminalDiagnosticsTool(runtime, execProbe = null)
        val json = Json.parseToJsonElement(tool.invoke("{}")).jsonObject

        assertEquals("true", json["ok"]!!.jsonPrimitive.content)
        assertEquals("1", json["sessionCount"]!!.jsonPrimitive.content)
        assertEquals(1, json["backends"]!!.jsonArray.size)
        assertEquals("all clear — terminal stack healthy", json["nextActions"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `dead session gets verdict and close action`() = runBlocking {
        val runtime = FakeRuntime(
            listOf(backend("linux-ubuntu", "READY")),
            listOf(semantic(7, SessionState.EXITED))
        )
        val tool = TerminalDiagnosticsTool(runtime, execProbe = null)
        val json = Json.parseToJsonElement(tool.invoke("{}")).jsonObject

        assertEquals("false", json["ok"]!!.jsonPrimitive.content)
        val session = json["sessions"]!!.jsonArray[0].jsonObject
        assertEquals("DEAD", session["verdict"]!!.jsonPrimitive.content)
        val next = json["nextActions"]!!.jsonArray.joinToString { it.jsonPrimitive.content }
        assertTrue(next.contains("close"))
    }

    @Test
    fun `needs rootfs backend yields actionable install hint`() = runBlocking {
        val runtime = FakeRuntime(listOf(backend("linux-ubuntu", "NEEDS_ROOTFS")), emptyList())
        val tool = TerminalDiagnosticsTool(runtime, execProbe = null)
        val json = Json.parseToJsonElement(tool.invoke("{}")).jsonObject

        val next = json["nextActions"]!!.jsonArray.joinToString { it.jsonPrimitive.content }
        assertTrue(next.contains("TerminalUbuntuEnsure"))
    }

    @Test
    fun `smoke test reports chain ok when probe echoes marker`() = runBlocking {
        val runtime = FakeRuntime(emptyList(), emptyList())
        val tool = TerminalDiagnosticsTool(runtime, execProbe = { cmd -> "$cmd\nTERMINAL-EXEC-OK" })
        val json = Json.parseToJsonElement(tool.invoke("""{"smokeTest":true}""")).jsonObject

        val smoke = json["smoke"]!!.jsonObject
        assertEquals("true", smoke["chainOk"]!!.jsonPrimitive.content)
        assertTrue(smoke["output"]!!.jsonPrimitive.content.contains("TERMINAL-EXEC-OK"))
    }

    @Test
    fun `smoke test reports honestly when probe not wired`() = runBlocking {
        val runtime = FakeRuntime(emptyList(), emptyList())
        val tool = TerminalDiagnosticsTool(runtime, execProbe = null)
        val json = Json.parseToJsonElement(tool.invoke("""{"smokeTest":true}""")).jsonObject

        val smoke = json["smoke"]!!.jsonObject
        assertEquals("false", smoke["chainOk"]!!.jsonPrimitive.content)
    }
}
