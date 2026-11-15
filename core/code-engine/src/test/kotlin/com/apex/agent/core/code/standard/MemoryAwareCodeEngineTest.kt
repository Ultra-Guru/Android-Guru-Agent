package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.UserInput
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryAwareCodeEngine 契约锁（记忆 P0-1 四线统一）：
 *
 * - 深潜线：纯透传（内层 ApexAgentEngine 已接线观察者 —— 包装层再调
 *   会双份情景/召回，必须跳过）；
 * - kit 三线：onTaskStart / 召回注入（extras 通道，标题自含）/
 *   工具轨迹（ToolCallComplete → onActionExecuted，记法与深潜线同款）/
 *   终局（onTaskFinish + onConversationTurn 最终助手回复）/ 收尾后
 *   extras 复位为纯用户值；
 * - 观察者缺席（null）：退化为纯透传（与裸 DualLogicCodeEngine 一致）。
 */
class MemoryAwareCodeEngineTest {

    /** 记录型假门面：execute 发脚本事件，extras 调用记参数。 */
    private class RecordingFacade(private val tag: String) : CodeEngineFacade {
        val calls = mutableListOf<String>()
        val extrasCalls = mutableListOf<String?>()

        override fun execute(input: String): Flow<AgentEvent> = flow {
            emit(AgentEvent.ToolCallComplete(callId = "1", toolName = "CodeRead", output = "ok", success = true, durationMs = 5))
            emit(AgentEvent.ResponseComplete("[$tag] final"))
            if (tag == "std-fail") emit(AgentEvent.Error("boom", recoverable = true))
            if (tag == "std-abort") emit(AgentEvent.Aborted)
            emit(AgentEvent.Complete(tag, 1, 1, 1))
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
        override fun updateSessionExtras(extras: String?) {
            calls += "extras"
            extrasCalls += extras
        }
        override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) { calls += "persona" }
        override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) { calls += "forced" }
        override fun updateThinkingLevel(level: CodeThinkingLevel) { calls += "tier:${level.name}" }
        override fun thinkingLevel(): CodeThinkingLevel = CodeThinkingLevel.STANDARD
        override fun currentThinkingDecision(): String? = null
        override fun clearConversation() { calls += "clear" }
        override fun historyCount(): Int = 1
        override fun currentWorkspace(): com.apex.agent.core.code.CodeAgentEngine.WorkspaceInfo? = null
        override fun currentTokenCount(): Int = 10
        override fun maxContextTokens(): Int = 100
    }

    /** 记录型观察者（契约默认方法全免实现）。 */
    private class RecordingObserver(
        private val recall: String? = "偏好深色模式",
    ) : ExecutionMemoryObserver {
        val starts = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var finishSuccess: Boolean? = null
        val turns = mutableListOf<Pair<String, String>>()
        val recalls = mutableListOf<String>()

        override suspend fun onTaskStart(goal: String, appPackage: String?) { starts += goal }
        override suspend fun onActionExecuted(actionDescription: String, success: Boolean) { actions += actionDescription }
        override suspend fun onTaskFinish(success: Boolean) { finishSuccess = success }
        override suspend fun recallChatMemory(userText: String): String? {
            recalls += userText
            return recall
        }
        override suspend fun onConversationTurn(userText: String, assistantText: String) {
            turns += userText to assistantText
        }
    }

    @Test
    fun `deep line is pure passthrough - observer untouched`() = runTest {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val observer = RecordingObserver()
        val engine = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = deep, standard = std), observer)

        val events = engine.execute(UserInput(text = "hello")).toList()

        // 事件透传 + 观察者零调用（深潜线由内层引擎接线）
        assertTrue(events.any { it is AgentEvent.ResponseComplete && it.fullText == "[deep] final" })
        assertTrue(observer.starts.isEmpty())
        assertTrue(observer.recalls.isEmpty())
        assertNull(observer.finishSuccess)
        assertTrue(observer.turns.isEmpty())
    }

    @Test
    fun `kit line gets full memory loop with recall injected into extras`() = runTest {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val observer = RecordingObserver(recall = "用户偏好深色模式")
        val engine = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = deep, standard = std), observer)

        engine.updateSessionExtras("user-extras")
        assertTrue(engine.dualLogic.switchLogic(StandardLogicMode.STANDARD))
        val events = engine.execute(UserInput(text = "写个函数")).toList()

        // 生命周期：start → recall → action → finish → turn
        assertEquals(listOf("写个函数"), observer.starts)
        assertEquals(listOf("写个函数"), observer.recalls)
        assertEquals(1, observer.actions.size)
        assertTrue(observer.actions[0].startsWith("CodeRead("))
        assertEquals(true, observer.finishSuccess)
        assertEquals(listOf("写个函数" to "[std] final"), observer.turns)

        // 事件透传不丢
        assertTrue(events.any { it is AgentEvent.ResponseComplete && it.fullText == "[std] final" })

        // extras 注入：用户段 + 召回段（标题自含）；收尾后复位为纯用户段
        val stdExtras = std.extrasCalls
        assertTrue(stdExtras.any { it!!.contains("user-extras") && it.contains("## Remembered About You") && it.contains("用户偏好深色模式") })
        assertEquals("user-extras", stdExtras.last())
    }

    @Test
    fun `null recall keeps extras user-only and reset is clean`() = runTest {
        val std = RecordingFacade("std")
        val observer = RecordingObserver(recall = null)
        val engine = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = RecordingFacade("deep"), standard = std), observer)
        engine.dualLogic.switchLogic(StandardLogicMode.STANDARD)

        engine.execute(UserInput(text = "q")).toList()

        // 无召回：注入的 extras 与复位值都是纯用户段（null）
        assertEquals(null, std.extrasCalls.last())
        assertFalse(std.extrasCalls.any { it != null && it.contains("Remembered") })
        // 轮次沉淀仍发生（recall 为 null 不影响写入面）
        assertEquals(1, observer.turns.size)
    }

    @Test
    fun `aborted or errored task finishes with failure`() = runTest {
        val std = RecordingFacade("std-abort")
        val observer = RecordingObserver()
        val engine = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = RecordingFacade("deep"), standard = std), observer)
        engine.dualLogic.switchLogic(StandardLogicMode.STANDARD)
        engine.execute(UserInput(text = "q")).toList()
        assertEquals(false, observer.finishSuccess)

        val stdFail = RecordingFacade("std-fail")
        val observer2 = RecordingObserver()
        val engine2 = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = RecordingFacade("deep"), standard = stdFail), observer2)
        engine2.dualLogic.switchLogic(StandardLogicMode.STANDARD)
        engine2.execute(UserInput(text = "q")).toList()
        assertEquals(false, observer2.finishSuccess)
    }

    @Test
    fun `missing observer degrades to bare passthrough`() = runTest {
        val std = RecordingFacade("std")
        val engine = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = RecordingFacade("deep"), standard = std), null)
        engine.dualLogic.switchLogic(StandardLogicMode.STANDARD)

        val events = engine.execute(UserInput(text = "q")).toList()

        assertTrue(events.any { it is AgentEvent.ResponseComplete && it.fullText == "[std] final" })
        // extras 只有一次用户段同步（无召回注入、无复位重放）
        assertTrue(std.extrasCalls.isEmpty())
    }

    @Test
    fun `string execute overload routes through memory loop`() = runTest {
        val std = RecordingFacade("std")
        val observer = RecordingObserver()
        val engine = MemoryAwareCodeEngine(DualLogicCodeEngine(deepDive = RecordingFacade("deep"), standard = std), observer)
        engine.dualLogic.switchLogic(StandardLogicMode.STANDARD)

        engine.execute("文本入口").toList()

        assertEquals(listOf("文本入口"), observer.starts)
        assertEquals(true, observer.finishSuccess)
    }
}
