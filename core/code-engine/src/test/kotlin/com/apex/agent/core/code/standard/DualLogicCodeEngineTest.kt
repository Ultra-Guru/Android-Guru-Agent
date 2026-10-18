package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.UserInput
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DualLogicCodeEngine 路由门面测试：
 * execute 走激活引擎 / 三线同步 API / 切换语义 / 织造线装配与路由。
 */
class DualLogicCodeEngineTest {

    /** 记录型假门面（实现 CodeEngineFacade 全契约）。 */
    private class RecordingFacade(private val tag: String) : CodeEngineFacade {
        val calls = mutableListOf<String>()

        override fun execute(input: String): Flow<AgentEvent> = flow {
            emit(AgentEvent.ResponseChunk("[$tag]"))
            emit(AgentEvent.ResponseComplete("[$tag]"))
            emit(AgentEvent.Complete(tag, 1, 0, 1))
        }

        override fun execute(input: UserInput): Flow<AgentEvent> = execute(input.text)
        override suspend fun abort() { calls += "abort" }
        override fun submitUserInput(answer: String) { calls += "input:$answer" }
        override fun cancelUserInput() { calls += "cancel" }
        override fun setActiveWorkspace(workspaceId: String, name: String, root: File, activeFile: String?) {
            calls += "workspace:$workspaceId"
        }
        override fun setActiveFile(activeFile: String?) { calls += "file:$activeFile" }
        override fun prepareForTask() { calls += "prepare" }
        override fun updateGlobalRules(rules: String) { calls += "rules" }
        override fun updateMode(mode: AgentMode) { calls += "mode:${mode.name}" }
        override fun submitPlanConfirmation(confirmed: Boolean, enabledSteps: List<Int>?, order: List<Int>?): Boolean {
            calls += "plan:$confirmed"
            return true
        }
        override fun updateSessionExtras(extras: String?) { calls += "extras" }
        override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) { calls += "persona" }
        override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
            calls += "forced"
        }
        override fun updateThinkingLevel(level: CodeThinkingLevel) { calls += "tier:${level.name}" }
        override fun thinkingLevel(): CodeThinkingLevel = CodeThinkingLevel.STANDARD
        override fun currentThinkingDecision(): String? = null
        override fun clearConversation() { calls += "clear" }
        override fun historyCount(): Int = 1
        override fun currentWorkspace(): com.apex.agent.core.code.CodeAgentEngine.WorkspaceInfo? = null
        override fun currentTokenCount(): Int = 10
        override fun maxContextTokens(): Int = 100
    }

    @Test
    fun `execute routes to active engine`() = kotlinx.coroutines.test.runTest {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom)

        // 默认深潜：事件来自深潜线
        val deepEvents = dual.execute("q").toList()
        assertTrue(deepEvents.any { it is AgentEvent.ResponseComplete && it.fullText == "[deep]" })
        assertEquals(StandardLogicMode.DEEP_DIVE, dual.activeLogic())

        // 切标准：事件来自标准线
        assertTrue(dual.switchLogic(StandardLogicMode.STANDARD))
        assertEquals(StandardLogicMode.STANDARD, dual.activeLogic())
        val stdEvents = dual.execute("q").toList()
        assertTrue(stdEvents.any { it is AgentEvent.ResponseComplete && it.fullText == "[std]" })

        // 切织造：事件来自织造线
        assertTrue(dual.switchLogic(StandardLogicMode.LOOM))
        assertEquals(StandardLogicMode.LOOM, dual.activeLogic())
        val loomEvents = dual.execute("q").toList()
        assertTrue(loomEvents.any { it is AgentEvent.ResponseComplete && it.fullText == "[loom]" })
    }

    @Test
    fun `workspace and clear sync all three engines`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom)

        dual.setActiveWorkspace("ws1", "名", File("/tmp"))
        assertTrue(deep.calls.contains("workspace:ws1"))
        assertTrue(std.calls.contains("workspace:ws1"))
        assertTrue(loom.calls.contains("workspace:ws1"))

        dual.clearConversation()
        assertTrue(deep.calls.contains("clear"))
        assertTrue(std.calls.contains("clear"))
        assertTrue(loom.calls.contains("clear"))

        dual.updateGlobalRules("r")
        dual.updateSessionExtras(null)
        dual.updateForcedTools(emptySet(), false)
        dual.updateMode(AgentMode.PLAN)
        assertTrue(deep.calls.contains("mode:PLAN"))
        assertTrue(std.calls.contains("mode:PLAN"))
        assertTrue(loom.calls.contains("mode:PLAN"))
    }

    @Test
    fun `interactive calls route to active engine only`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom)

        dual.switchLogic(StandardLogicMode.STANDARD)
        dual.submitUserInput("允许")
        assertTrue(std.calls.contains("input:允许"))
        assertFalse(deep.calls.contains("input:允许"))
        assertFalse(loom.calls.contains("input:允许"))

        dual.submitPlanConfirmation(true)
        assertTrue(std.calls.contains("plan:true"))
        assertFalse(deep.calls.contains("plan:true"))

        dual.switchLogic(StandardLogicMode.LOOM)
        dual.submitPlanConfirmation(false)
        assertTrue(loom.calls.contains("plan:false"))
        assertFalse(std.calls.contains("plan:false"))

        dual.switchLogic(StandardLogicMode.DEEP_DIVE)
        dual.prepareForTask()
        dual.setActiveFile("a.kt")
        assertTrue(deep.calls.contains("prepare"))
        assertTrue(deep.calls.contains("file:a.kt"))
        assertFalse(std.calls.contains("prepare"))
        assertFalse(loom.calls.contains("prepare"))
    }

    @Test
    fun `switching to same mode is a no-op success`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom)
        assertTrue(dual.switchLogic(StandardLogicMode.DEEP_DIVE))
        assertEquals(StandardLogicMode.DEEP_DIVE, dual.activeLogic())
        assertTrue(dual.switchLogic(StandardLogicMode.LOOM))
        assertTrue(dual.switchLogic(StandardLogicMode.LOOM)) // 幂等
        assertEquals(StandardLogicMode.LOOM, dual.activeLogic())
    }

    @Test
    fun `loom line is optional and switch reports absence`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, loom = null)
        assertFalse(dual.isLoomEngineAvailable())
        assertFalse(dual.switchLogic(StandardLogicMode.LOOM)) // 未装配：拒绝切换
        assertEquals(StandardLogicMode.DEEP_DIVE, dual.activeLogic()) // 激活线不变
    }

    @Test
    fun `loom line is available when assembled`() {
        val dual = DualLogicCodeEngine(
            deepDive = RecordingFacade("deep"),
            standard = RecordingFacade("std"),
            loom = RecordingFacade("loom")
        )
        assertTrue(dual.isLoomEngineAvailable())
        assertTrue(dual.switchLogic(StandardLogicMode.LOOM))
        assertEquals(StandardLogicMode.LOOM, dual.activeLogic())
    }

    @Test
    fun `facade contract compliance`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val dualEngine = DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom)
        val dual: AgentEngine = dualEngine
        assertTrue(dual is CodeEngineFacade)
        assertEquals(10, (dual as CodeEngineFacade).currentTokenCount())
        assertEquals(100, dual.maxContextTokens())
        assertEquals(1, dual.historyCount())
        // 仪表读数走激活线（三假面同读数——路由口径验证）
        assertEquals(10, dual.currentTokenCount())
        dualEngine.switchLogic(StandardLogicMode.LOOM)
        assertEquals(10, dual.currentTokenCount())
    }
}
