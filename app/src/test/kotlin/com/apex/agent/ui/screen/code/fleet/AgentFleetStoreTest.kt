package com.apex.agent.ui.screen.code.fleet

import com.apex.agent.core.code.subagent.SubAgentObserver
import com.apex.agent.core.code.subagent.SubAgentRunner
import com.apex.agent.core.engine.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentFleetStore] 归约器纯 JVM 单测（Spec §7.1）：
 * 同 id 合并 / 异会话丢弃 / 切会话迟到丢弃 / 运行→终态 / 上限丢最旧 /
 * Tab 复位 / 中止收口 / 观察者会话绑定。全同步调用——无协程、无假钟。
 *
 * 状态映射回归（Spec §7.3）：exitCode 0 → SUCCESS；127 / 126 / -1 →
 * FAILED（引擎已按 exitCode 判定 success 字段——成败以 exitCode/success
 * 为准，不用输出文本猜测）。
 */
class AgentFleetStoreTest {

    // ── 脚手架 ──────────────────────────────────────────────────────

    private fun cmdStart(
        callId: String,
        toolName: String = "TerminalExec",
        args: String = """{"command":"ls -la /workspace"}"""
    ) = AgentEvent.ToolCallStart(callId, toolName, args)

    private fun cmdComplete(
        callId: String,
        toolName: String = "TerminalExec",
        success: Boolean,
        exitCode: Int?,
        durationMs: Long = 40
    ) = AgentEvent.ToolCallComplete(
        callId = callId,
        toolName = toolName,
        output = "",
        success = success,
        durationMs = durationMs,
        exitCode = exitCode
    )

    private fun result(
        iterations: Int = 3,
        toolCalls: Int = 5,
        durationMs: Long = 4_000
    ) = SubAgentRunner.SubAgentResult(
        output = "结论", iterations = iterations, toolCalls = toolCalls,
        durationMs = durationMs, truncated = false
    )

    // ── 同 id 幂等合并（Spec §7.1-1）────────────────────────────────

    @Test
    fun `duplicate start events merge into one row`() {
        val store = AgentFleetStore()
        store.newConversation()
        store.onEngineEvent(cmdStart("c1"))
        store.onEngineEvent(cmdStart("c1")) // 重放 / 迟到重复 Start：按键合并
        assertEquals(1, store.state.value.commands.size)
    }

    @Test
    fun `complete merges into existing row without appending`() {
        val store = AgentFleetStore()
        store.newConversation()
        store.onEngineEvent(cmdStart("c1"))
        store.onEngineEvent(cmdComplete("c1", success = true, exitCode = 0))
        assertEquals(1, store.state.value.commands.size)
        assertEquals(FleetCommandStatus.SUCCESS, store.state.value.commands.single().status)
    }

    // ── 会话隔离（Spec §7.1-2 / §7.1-3）─────────────────────────────

    @Test
    fun `events from other conversation are dropped`() {
        val store = AgentFleetStore()
        store.newConversation()
        store.onSubAgentToolEvent("other-conv", "run-1", cmdStart("c1"))
        store.onSubAgentStart("other-conv", "run-1", "explore", "任务")
        assertTrue(store.state.value.commands.isEmpty())
        assertTrue(store.state.value.subAgents.isEmpty())
    }

    @Test
    fun `late events after conversation switch are dropped`() {
        val store = AgentFleetStore()
        val old = store.newConversation()
        store.onEngineEvent(cmdStart("c1"))
        store.newConversation() // 切会话：旧桶即弃
        // 旧会话在飞事件（观察者在 start 时绑定的旧 id）——全部丢弃，不串台
        store.onSubAgentToolEvent(old, "run-1", cmdComplete("c1", success = true, exitCode = 0))
        store.onSubAgentStop(old, "run-1", SubAgentObserver.StopCause.COMPLETED, result())
        assertTrue(store.state.value.commands.isEmpty())
        assertTrue(store.state.value.subAgents.isEmpty())
    }

    // ── 状态机（Spec §7.1-4 / §7.3）────────────────────────────────

    @Test
    fun `command start then complete transitions to terminal state`() {
        val store = AgentFleetStore()
        store.newConversation()
        store.onEngineEvent(cmdStart("c1"))
        assertEquals(FleetCommandStatus.RUNNING, store.state.value.commands.single().status)
        store.onEngineEvent(
            AgentEvent.ToolCallComplete(
                callId = "c1", toolName = "TerminalExec",
                output = "", success = true, durationMs = 120, exitCode = 0,
                sessionId = "s-7", channel = "proot-ubuntu"
            )
        )
        val row = store.state.value.commands.single()
        assertEquals(FleetCommandStatus.SUCCESS, row.status)
        assertEquals(0, row.exitCode)
        assertEquals("s-7", row.sessionId)
        assertEquals("proot-ubuntu", row.channel)
        assertEquals(120L, row.durationMs)
        assertNull(row.ownerSubAgentId) // 主代理命令
    }

    @Test
    fun `exit code mapping success and failure`() {
        val store = AgentFleetStore()
        store.newConversation()
        // exitCode=0（引擎判 success=true）→ SUCCESS
        store.onEngineEvent(cmdStart("ok"))
        store.onEngineEvent(cmdComplete("ok", success = true, exitCode = 0))
        assertEquals(FleetCommandStatus.SUCCESS, store.state.value.commands.last { it.callId == "ok" }.status)
        // exitCode=127 / 126 / -1（引擎判 success=false）→ FAILED（T92 口径：
        // 成败以 exitCode/success 为准，不做文本前缀猜测）
        listOf(127, 126, -1).forEachIndexed { i, code ->
            val id = "bad$i"
            store.onEngineEvent(cmdStart(id))
            store.onEngineEvent(cmdComplete(id, success = false, exitCode = code))
            assertEquals(FleetCommandStatus.FAILED, store.state.value.commands.last { it.callId == id }.status)
            assertEquals(code, store.state.value.commands.last { it.callId == id }.exitCode)
        }
    }

    @Test
    fun `non command tools are filtered out and terminal family accepted`() {
        val store = AgentFleetStore()
        store.newConversation()
        store.onEngineEvent(cmdStart("r1", toolName = "CodeRead", args = """{"path":"Foo.kt"}"""))
        assertTrue(store.state.value.commands.isEmpty()) // 非 BASH 族：不进命令 Tab
        store.onEngineEvent(cmdStart("r2", toolName = "TerminalRun", args = """{"command":"build"}"""))
        store.onEngineEvent(cmdStart("r3", toolName = "ShellExecute", args = """{"command":"ls"}"""))
        assertEquals(2, store.state.value.commands.size) // TerminalRun（Spec §3.2 补集）+ ShellExecute
    }

    // ── 上限丢最旧（Spec §7.1-5）───────────────────────────────────

    @Test
    fun `command cap drops oldest beyond 256`() {
        val store = AgentFleetStore()
        store.newConversation()
        repeat(300) { store.onEngineEvent(cmdStart("c$it")) }
        val commands = store.state.value.commands
        assertEquals(256, commands.size)
        assertEquals("c44", commands.first().callId) // 宁可丢头不丢尾
        assertEquals("c299", commands.last().callId)
    }

    @Test
    fun `sub agent cap drops oldest beyond 64`() {
        val store = AgentFleetStore()
        val cid = store.newConversation()
        repeat(70) { store.onSubAgentStart(cid, "run-$it", "explore", "任务$it") }
        val subAgents = store.state.value.subAgents
        assertEquals(64, subAgents.size)
        assertEquals("run-6", subAgents.first().id)
        assertEquals("run-69", subAgents.last().id)
    }

    // ── Tab 复位（Spec §7.1-6）──────────────────────────────────────

    @Test
    fun `new conversation resets tab to sub agents`() {
        val store = AgentFleetStore()
        store.newConversation()
        store.selectTab(FleetTab.COMMAND)
        assertEquals(FleetTab.COMMAND, store.state.value.tab)
        store.newConversation()
        assertEquals(FleetTab.SUB_AGENT, store.state.value.tab)
    }

    // ── 中止收口（Spec §3.3 abort 语义）────────────────────────────

    @Test
    fun `cancel running folds in flight rows to cancelled and partial`() {
        val store = AgentFleetStore()
        val cid = store.newConversation()
        store.onEngineEvent(cmdStart("c1"))
        store.onSubAgentStart(cid, "run-1", "explore", "任务")
        store.cancelRunning()
        assertEquals(FleetCommandStatus.PARTIAL, store.state.value.commands.single().status)
        assertEquals(FleetSubAgentStatus.CANCELLED, store.state.value.subAgents.single().status)
    }

    // ── 子代理终态映射（StopCause 唯一事实源）──────────────────────

    @Test
    fun `sub agent stop cause maps to terminal statuses`() {
        val store = AgentFleetStore()
        val cid = store.newConversation()
        val causes = mapOf(
            SubAgentObserver.StopCause.COMPLETED to FleetSubAgentStatus.SUCCESS,
            SubAgentObserver.StopCause.TIMED_OUT to FleetSubAgentStatus.TIMEOUT,
            SubAgentObserver.StopCause.ABORTED to FleetSubAgentStatus.CANCELLED,
            SubAgentObserver.StopCause.FAILED to FleetSubAgentStatus.FAILED
        )
        causes.forEach { (cause, expected) ->
            val runId = "run-$cause"
            store.onSubAgentStart(cid, runId, "explore", "任务")
            store.onSubAgentStop(cid, runId, cause, result(iterations = 7, toolCalls = 9, durationMs = 1_234))
            val row = store.state.value.subAgents.last { it.id == runId }
            assertEquals(expected, row.status)
            assertEquals(7, row.iterations)
            assertEquals(9, row.toolCalls)
            assertEquals(1_234L, row.durationMs)
        }
    }

    // ── 装配层观察者（会话绑定 + 完整生命周期）──────────────────────

    @Test
    fun `observer binds conversation at start and full lifecycle flows`() {
        val store = AgentFleetStore()
        val observer = FleetSubAgentObserver(store)
        val cid = store.newConversation()
        observer.onStart("CodeTask-explore-任务", "explore", "任务")
        observer.onProgress("CodeTask-explore-任务", 2, 3)
        observer.onToolEvent("CodeTask-explore-任务", cmdStart("c-in"))
        observer.onToolEvent("CodeTask-explore-任务", cmdComplete("c-in", success = true, exitCode = 0))
        observer.onStop(
            "CodeTask-explore-任务",
            SubAgentObserver.StopCause.COMPLETED,
            result(iterations = 2, toolCalls = 1, durationMs = 500)
        )
        val state = store.state.value
        assertEquals(cid, state.conversationId)
        assertEquals(1, state.subAgents.size)
        assertEquals(FleetSubAgentStatus.SUCCESS, state.subAgents.single().status)
        assertEquals(1, state.commands.size)
        assertEquals("CodeTask-explore-任务", state.commands.single().ownerSubAgentId) // 归属子代理
    }

    @Test
    fun `observer late events after switch carry old id and are dropped`() {
        val store = AgentFleetStore()
        val observer = FleetSubAgentObserver(store)
        observer.onStart("run-1", "explore", "任务") // 绑定旧会话
        store.newConversation() // 用户切会话
        observer.onProgress("run-1", 5, 9)
        observer.onToolEvent("run-1", cmdStart("c-late"))
        observer.onStop("run-1", SubAgentObserver.StopCause.COMPLETED, result())
        assertTrue(store.state.value.subAgents.isEmpty())
        assertTrue(store.state.value.commands.isEmpty())
    }

    // ── 命令摘要提取（非 stdout）────────────────────────────────────

    @Test
    fun `command target extracts first line of command arg`() {
        val store = AgentFleetStore()
        assertEquals(
            "ls -la",
            store.commandTargetOf("TerminalExec", """{"command":"ls -la\nsecond line"}""")
        )
        assertEquals("TerminalExec", store.commandTargetOf("TerminalExec", "not-json"))
        assertEquals("TerminalExec", store.commandTargetOf("TerminalExec", ""))
    }
}
