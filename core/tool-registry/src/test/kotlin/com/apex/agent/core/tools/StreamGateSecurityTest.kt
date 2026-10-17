package com.apex.agent.core.tools

import com.apex.agent.core.tools.hook.HookDispatchResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #230（P1 security）回归：流式路径的门控前置。
 *
 * 生产引擎（Agent 聊天主循环 / 任务编排 / MCP 流）全部走
 * [EnhancedToolExecutor.executeStream] —— 旧实现只有非流式 execute() 过
 * pipeline.preCheck，流式路径**从未咨询 gate**：MEDIUM 文件改写类
 * （Write/Edit）与 HIGH 风险工具在 Agent 模式下静默执行。
 * 本组测试锁死：流式路径与非流式共享同一 preCheck（gate → 钩子 → schema），
 * 拒绝文案逐字节一致，且拒绝发生在工具执行之前。
 */
class StreamGateSecurityTest {

    /** 文件改写类夹具（对齐 FileWriteTool 的 id / risk：MEDIUM）。 */
    private class FileMutatingTool(
        override val id: String = "Write"
    ) : AgentTool {
        override val name = id
        override val description = "scripted file write"
        override val parametersSchema = "{}"
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.FILE, risk = ToolRisk.MEDIUM,
            tags = emptyList(), annotations = ToolAnnotations.mutating()
        )

        val calls = java.util.concurrent.atomic.AtomicInteger()
        val argumentsSeen = mutableListOf<String>()

        override suspend fun execute(arguments: String): String {
            calls.incrementAndGet()
            argumentsSeen += arguments
            return "OK: wrote"
        }
    }

    /** 按工具 id 裁决的脚本化 gate。 */
    private class DenyingGate(
        private val deniedId: String,
        private val reason: String = "user denied Write for this session"
    ) : ToolExecutionGate {
        var checked = 0
        override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
            checked++
            return if (tool.id == deniedId) GateDecision.Deny(reason) else GateDecision.Allow
        }
    }

    private fun executor(
        vararg tools: AgentTool,
        gate: ToolExecutionGate? = null,
        beforeToolHooks: (suspend (String, ToolArguments) -> HookDispatchResult?)? = null
    ): EnhancedToolExecutor {
        val registry = DefaultToolRegistry()
        tools.forEach { registry.register(it) }
        return EnhancedToolExecutor(
            registry = registry,
            gate = gate,
            beforeToolHooks = beforeToolHooks
        )
    }

    @Test
    fun `executeStream consults the gate and short-circuits before execution`() = runTest {
        val tool = FileMutatingTool()
        val gate = DenyingGate("Write")
        val executor = executor(tool, gate = gate)

        val events = executor.executeStream("Write", "{\"path\":\"/tmp/x\",\"content\":\"hi\"}").toList()

        assertEquals("gate 被咨询（流式路径不再是门控盲区）", 1, gate.checked)
        assertEquals("工具未被静默执行", 0, tool.calls.get())
        assertEquals("单一 Error 终止事件", 1, events.size)
        val error = events.filterIsInstance<ToolStreamEvent.Error>().single()
        assertEquals(
            "拒绝文案与非流式路径逐字节一致（模型侧行为统一）",
            "Error: permission denied: user denied Write for this session",
            error.message
        )
    }

    @Test
    fun `execute and executeStream return the same denial message`() = runTest {
        val tool = FileMutatingTool()
        val gate = DenyingGate("Write")
        val executor = executor(tool, gate = gate)

        val sync = executor.execute("Write", "{}")
        val async = executor.executeStream("Write", "{}")
            .toList()
            .filterIsInstance<ToolStreamEvent.Error>()
            .single()
            .message

        assertEquals("两入口同文案（#230 修复的契约）", sync, async)
    }

    @Test
    fun `gate allow on stream path executes the tool normally`() = runTest {
        val tool = FileMutatingTool()
        val gate = DenyingGate("other_tool") // Write → Allow
        val executor = executor(tool, gate = gate)

        val events = executor.executeStream("Write", "{}").toList()

        assertEquals(1, tool.calls.get())
        assertEquals(
            "普通工具包装为 Output + Complete",
            2, events.size
        )
        assertTrue(events.any { it is ToolStreamEvent.Output })
        assertTrue(events.any { it is ToolStreamEvent.Complete })
    }

    @Test
    fun `PreToolUse hook rewrites arguments on stream path after gate`() = runTest {
        val tool = FileMutatingTool()
        var gateChecked = 0
        val gate = object : ToolExecutionGate {
            override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
                gateChecked++
                return GateDecision.Allow
            }
        }
        // 钩子在 gate 之后改写参数（Issue #165 契约在流式路径上保持）
        val hooks: suspend (String, ToolArguments) -> HookDispatchResult? = { _, args ->
            if (args.raw == "{}") {
                HookDispatchResult(
                    modifiedArgs = ToolArguments.parseOrNull("{\"path\":\"/rewritten\",\"content\":\"hooked\"}"),
                    firedCount = 1
                )
            } else null
        }
        val executor = executor(tool, gate = gate, beforeToolHooks = hooks)

        val events = executor.executeStream("Write", "{}").toList()

        assertEquals("gate 仍在钩子之前咨询", 1, gateChecked)
        assertEquals(1, tool.calls.get())
        assertEquals("工具收到钩子改写后的参数", "{\"path\":\"/rewritten\",\"content\":\"hooked\"}", tool.argumentsSeen.single())
        assertTrue(events.any { it is ToolStreamEvent.Complete })
    }

    @Test
    fun `PreToolUse hook block on stream path denies with gate-style message`() = runTest {
        val tool = FileMutatingTool()
        val hooks: suspend (String, ToolArguments) -> HookDispatchResult? = { _, _ ->
            HookDispatchResult(blocked = true, blockReason = "blocked by policy hook", firedCount = 1)
        }
        val executor = executor(tool, beforeToolHooks = hooks)

        val events = executor.executeStream("Write", "{}").toList()

        assertEquals(0, tool.calls.get())
        val error = events.filterIsInstance<ToolStreamEvent.Error>().single()
        assertEquals("Error: permission denied: blocked by policy hook", error.message)
    }

    @Test
    fun `schema violation on stream path is rejected before execution`() = runTest {
        // required 参数缺失 → 声明式校验拒绝（流式路径补齐 schema 前置的回归）
        val tool = object : AgentTool {
            override val id = "Write"
            override val name = id
            override val description = "schema'd write"
            override val parametersSchema = """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
            """.trimIndent()
            override val metadata = ToolMetadata(
                id = id, category = ToolCategory.FILE, risk = ToolRisk.MEDIUM,
                tags = emptyList(), annotations = ToolAnnotations.mutating()
            )
            var executed = false
            override suspend fun execute(arguments: String): String {
                executed = true
                return "OK"
            }
        }
        val executor = executor(tool)

        val events = executor.executeStream("Write", "{}").toList()

        assertTrue("schema 违规拒绝", events.single() is ToolStreamEvent.Error)
        assertTrue((events.single() as ToolStreamEvent.Error).message.startsWith("Error: invalid argument"))
        assertEquals("未执行", false, tool.executed)
    }
}
