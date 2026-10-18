package com.apex.agent.core.code.loom

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.llm.runtime.ModelRoleRouter
import com.apex.agent.core.llm.runtime.ModelRuntimeDiagnostics
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomEngine 门面测试（全端口 fake——验证组装与门面契约）：
 *
 * - execute 全链路（模型请求含本地工具面 / 织造包段注入提示词）；
 * - forcedTools 过滤（选中面外工具不可见）；
 * - 门面直通（档位/历史/工作区/清空联动/记忆通道）。
 */
class LoomEngineTest {

    /** 剧本式 ModelRuntime（记录请求 + 出队回合文本）。 */
    private class ScriptedRuntime : ModelRuntime {
        val seenTools = mutableListOf<List<ToolDefinition>>()
        val seenMessages = mutableListOf<List<LlmMessage>>()
        val rounds = ArrayDeque<String>()

        override fun chatStream(
            context: LlmRequestContext,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = flow {
            seenTools.add(tools)
            seenMessages.add(messages)
            emit(LlmStreamChunk(content = rounds.removeFirst()))
            emit(LlmStreamChunk(isFinish = true, finishReason = "stop"))
        }

        override suspend fun chat(
            context: LlmRequestContext,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = "摘要")

        override fun snapshot(): List<ModelRuntimeDiagnostics.ModelRuntimeSnapshot> = emptyList()

        override fun resolve(
            role: ModelRole,
            requiredCapabilities: com.apex.agent.core.llm.ModelCapabilities
        ): ModelRoleRouter.ResolutionResult =
            throw UnsupportedOperationException("测试 fake 不路由角色")
    }

    private class FakeRegistry : ToolRegistry {
        override fun register(tool: AgentTool) = Unit
        override fun unregister(toolId: String) = Unit
        override fun getTool(toolId: String): AgentTool? = null
        override fun getAllTools(): List<AgentTool> = emptyList()
        override fun getToolDefinitions(): List<ToolDefinition> = listOf(
            ToolDefinition("CodeRead", "读文件", "{\"type\":\"object\"}"),
            ToolDefinition("CodeWrite", "写文件", "{\"type\":\"object\"}")
        )

        override val toolCount: Int = 2
        override val registryVersion: Long = 1
    }

    private class FakeExecutor : ToolExecutor {
        val executed = mutableListOf<Pair<String, String>>()
        override suspend fun execute(toolId: String, arguments: String): String {
            executed.add(toolId to arguments)
            return "[$toolId] ok"
        }

        override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> =
            flow {
                emit(ToolStreamEvent.Complete("[$toolId] ok"))
            }
    }

    private fun engine(
        runtime: ScriptedRuntime,
        executor: FakeExecutor = FakeExecutor(),
        memory: com.apex.agent.core.code.CodeConversationMemory? = null
    ): LoomEngine = LoomEngine(
        runtime = runtime,
        toolRegistry = FakeRegistry(),
        toolExecutor = executor,
        memory = memory,
        contextProvider = null,
        rulesProvider = null,
        textKernel = null,
        permissionSource = { com.apex.agent.core.code.loom.permission.LoomPermissionSnapshot() },
        modelInfoProvider = null,
        packageCatalog = object :
            com.apex.agent.core.code.loom.packager.LoomPackageCatalog {
            override fun list() = listOf(
                com.apex.agent.core.code.loom.packager.LoomPackageInfo(
                    "kt-review", "Kotlin 审查", "四步审查法"
                )
            )

            override fun load(id: String): com.apex.agent.core.code.loom.LoomActivePackage? =
                if (id == "kt-review") {
                    com.apex.agent.core.code.loom.LoomActivePackage(
                        id = "kt-review",
                        title = "Kotlin 审查",
                        instructions = "四步：读 / 查 / 修 / 验。"
                    )
                } else null
        }
    )

    @Test
    fun `execute exposes local tools and completes`() = runTest {
        val runtime = ScriptedRuntime()
        runtime.rounds.addLast("直接回答。")
        val e = engine(runtime)
        val events = mutableListOf<AgentEvent>()
        e.execute(UserInput.text("你好")).toList(events)
        assertTrue(events.any { it is AgentEvent.ResponseComplete })
        // 模型请求面 = 注册表 2 + 本地 8（子线程5 + 织造包3）
        val tools = runtime.seenTools.single()
        assertEquals(2 + 8, tools.size)
        assertTrue(tools.any { it.name == "loom_thread_start" })
        assertTrue(tools.any { it.name == "loom_package_use" })
        // 系统提示词含协议与目录（段落次序断言由 SessionTest 覆盖——此处不重复）
        val system = runtime.seenMessages.single().first() as LlmMessage.System
        assertTrue(system.content.contains("工具调用协议"))
        assertTrue(system.content.contains("CodeRead"))
    }

    @Test
    fun `package activation rides outcome turn and prompt section`() = runTest {
        val runtime = ScriptedRuntime()
        runtime.rounds.addLast(
            "激活审查包。<use name=\"loom_package_use\"><arg name=\"id\">kt-review</arg></use>"
        )
        runtime.rounds.addLast("已按包指引完成审查。")
        val e = engine(runtime)
        val events = mutableListOf<AgentEvent>()
        e.execute(UserInput.text("审查这段代码")).toList(events)
        // 第二轮系统提示词含织造包段
        val secondSystem = runtime.seenMessages[1].first() as LlmMessage.System
        assertTrue(secondSystem.content.contains("Kotlin 审查"))
        assertTrue(secondSystem.content.contains("四步：读 / 查 / 修 / 验"))
        // 回执轮注入了包指引
        assertTrue(
            events.filterIsInstance<AgentEvent.ToolCallComplete>()
                .single().output.contains("四步：读 / 查 / 修 / 验")
        )
    }

    @Test
    fun `forced tools narrow the surface`() = runTest {
        val runtime = ScriptedRuntime()
        runtime.rounds.addLast("只看选中面。")
        val e = engine(runtime)
        e.updateForcedTools(setOf("CodeRead", "loom_thread_start"), exposeAll = false)
        val events = mutableListOf<AgentEvent>()
        e.execute(UserInput.text("任务")).toList(events)
        val tools = runtime.seenTools.single().map { it.name }.toSet()
        assertEquals(setOf("CodeRead", "loom_thread_start"), tools)
    }

    @Test
    fun `facade passthroughs work`() = runTest {
        val runtime = ScriptedRuntime()
        val e = engine(runtime)
        e.setActiveWorkspace("ws1", "演示", File("/tmp/ws1"), "a.kt")
        assertEquals("ws1", e.currentWorkspace()?.id)
        assertEquals("演示", e.currentWorkspace()?.name)
        e.updateThinkingLevel(com.apex.agent.core.code.thinking.CodeThinkingLevel.DEEP)
        assertEquals(
            com.apex.agent.core.code.thinking.CodeThinkingLevel.DEEP,
            e.thinkingLevel()
        )
        assertEquals(null, e.currentThinkingDecision())
        assertTrue(e.maxContextTokens() >= 8_000)
        e.updateGlobalRules("规则A")
        e.updateSessionExtras("附加")
        e.updateRolePersona("专家", "提示")
        e.clearConversation()
        assertEquals(0, e.historyCount())
    }

    @Test
    fun `host tools execute through routing runner`() = runTest {
        val runtime = ScriptedRuntime()
        val executor = FakeExecutor()
        runtime.rounds.addLast(
            "<use name=\"CodeRead\"><arg name=\"path\">/tmp/ws1/a.kt</arg></use>"
        )
        runtime.rounds.addLast("读完了。")
        val e = engine(runtime, executor)
        e.setActiveWorkspace("ws1", "演示", File("/tmp/ws1"), null)
        val events = mutableListOf<AgentEvent>()
        e.execute(UserInput.text("读")).toList(events)
        assertEquals("CodeRead", executor.executed.single().first)
        assertTrue(events.filterIsInstance<AgentEvent.ToolCallComplete>().single().success)
    }
}
