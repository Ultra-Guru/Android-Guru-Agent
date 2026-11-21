package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.EngineRuntimeState
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AutonomyLevel
import com.apex.agent.core.engine.UserInput
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DualLogicCodeEngine 路由门面测试：
 * execute 走激活引擎 / 四线同步 API / 切换语义 / 织造·信使线装配与路由。
 */
class DualLogicCodeEngineTest {

    /** 记录型假门面（实现 CodeEngineFacade 全契约；档位字段状态化，SPE-03 验收用）。 */
    private class RecordingFacade(private val tag: String) : CodeEngineFacade {
        val calls = mutableListOf<String>()
        var tier: CodeThinkingLevel = CodeThinkingLevel.STANDARD

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
        override fun updateAutonomy(level: AutonomyLevel?, preClarify: Boolean) {
            calls += "autonomy:${level?.name ?: "null"}:$preClarify"
        }
        override fun updateSessionExtras(extras: String?) { calls += "extras" }
        override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) { calls += "persona" }
        override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
            calls += "forced"
        }
        override fun updateThinkingLevel(level: CodeThinkingLevel) {
            calls += "tier:${level.name}"
            tier = level
        }
        override fun thinkingLevel(): CodeThinkingLevel = tier
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
    fun `workspace clear and mode sync all four engines with courier line`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val courier = RecordingFacade("courier")
        val dual = DualLogicCodeEngine(
            deepDive = deep, standard = std, loom = loom, courier = courier
        )

        dual.setActiveWorkspace("ws9", "名", File("/tmp"))
        assertTrue(courier.calls.contains("workspace:ws9"))

        dual.clearConversation()
        assertTrue(courier.calls.contains("clear"))

        dual.updateGlobalRules("r")
        dual.updateSessionExtras(null)
        dual.updateForcedTools(emptySet(), false)
        dual.updateMode(AgentMode.PLAN)
        assertTrue(courier.calls.contains("mode:PLAN"))

        dual.updateRolePersona("expert", null)
        assertTrue(courier.calls.contains("persona"))
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
    fun `courier line is optional and switch reports absence`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, courier = null)
        assertFalse(dual.isCourierEngineAvailable())
        assertFalse(dual.switchLogic(StandardLogicMode.COURIER)) // 未装配：拒绝切换
        assertEquals(StandardLogicMode.DEEP_DIVE, dual.activeLogic()) // 激活线不变
    }

    @Test
    fun `courier line routes execute and interactive calls`() = kotlinx.coroutines.test.runTest {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val courier = RecordingFacade("courier")
        val dual = DualLogicCodeEngine(
            deepDive = deep, standard = std, loom = loom, courier = courier
        )
        assertTrue(dual.isCourierEngineAvailable())

        // 切信使：事件来自信使线
        assertTrue(dual.switchLogic(StandardLogicMode.COURIER))
        assertEquals(StandardLogicMode.COURIER, dual.activeLogic())
        val events = dual.execute("q").toList()
        assertTrue(events.any { it is AgentEvent.ResponseComplete && it.fullText == "[courier]" })

        // 交互调用只走激活的信使线
        dual.submitUserInput("yes")
        assertTrue(courier.calls.contains("input:yes"))
        assertFalse(deep.calls.contains("input:yes"))
        assertFalse(loom.calls.contains("input:yes"))

        // 仪表读数走激活线
        dual.prepareForTask()
        assertTrue(courier.calls.contains("prepare"))
        assertFalse(std.calls.contains("prepare"))
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

    // ═══ SPE-03 P1-2：显式状态 + 构造防护 ═══

    @Test
    fun `explicit active mode field tracks every switch`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val courier = RecordingFacade("courier")
        val dual = DualLogicCodeEngine(
            deepDive = deep, standard = std, loom = loom, courier = courier
        )
        // 默认深潜 → 显式字段同源
        assertEquals(StandardLogicMode.DEEP_DIVE, dual.activeLogic())
        // 逐线切换：activeLogic（显式状态字段）逐次跟随（execute 路由一致性由首测覆盖）
        listOf(
            StandardLogicMode.STANDARD,
            StandardLogicMode.LOOM,
            StandardLogicMode.COURIER,
            StandardLogicMode.DEEP_DIVE
        ).forEach { mode ->
            assertTrue(dual.switchLogic(mode))
            assertEquals(mode, dual.activeLogic())
        }
    }

    @Test
    fun `duplicate line instances fail fast at construction`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        // deepDive 与 standard 同实例 → 构造期拒绝（SPE-03 P1-2 DI 误配防护）
        assertThrows(IllegalArgumentException::class.java) {
            DualLogicCodeEngine(deepDive = deep, standard = deep)
        }
        // loom 与 standard 同实例 → 同样拒绝
        assertThrows(IllegalArgumentException::class.java) {
            DualLogicCodeEngine(deepDive = deep, standard = std, loom = std)
        }
        // courier 与 loom 同实例 → 同样拒绝
        assertThrows(IllegalArgumentException::class.java) {
            DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom, courier = loom)
        }
    }

    // ═══ SPE-03 P1-1：运行态快照整包重放 ═══

    @Test
    fun `applyRuntimeState replays full snapshot to target line after switch`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std, loom = loom)
        assertTrue(dual.switchLogic(StandardLogicMode.LOOM))

        dual.applyRuntimeState(
            EngineRuntimeState(
                thinkingLevel = CodeThinkingLevel.ULTRACODE,
                mode = AgentMode.PLAN,
                autonomy = AutonomyLevel.FULLY,
                preClarify = true,
                replayAutonomy = true,
                globalRules = "rule-1",
                sessionExtras = "extras-1",
                rolePersona = "expert" to "hint",
                forcedToolIds = setOf("CodeRead"),
                exposeAllTools = true
            )
        )

        // 四线同步通道广播到所有线（规则/extras/人设/强制工具）
        listOf(deep, std, loom).forEach { facade ->
            assertTrue(facade.calls.contains("rules"))
            assertTrue(facade.calls.contains("extras"))
            assertTrue(facade.calls.contains("persona"))
            assertTrue(facade.calls.contains("forced"))
            assertTrue(facade.calls.contains("mode:PLAN"))
            assertTrue(facade.calls.contains("autonomy:FULLY:true"))
        }
        // 档位路由当前激活线（LOOM）：切完即读即一致，无需等下一次发送
        assertEquals(CodeThinkingLevel.ULTRACODE, dual.thinkingLevel())
        assertEquals(CodeThinkingLevel.ULTRACODE, loom.tier)
        assertTrue(loom.calls.contains("tier:ULTRACODE"))
    }

    @Test
    fun `snapshot nullable channels are skipped not cleared`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val dual = DualLogicCodeEngine(deepDive = deep, standard = std)
        // 先写入完整状态
        dual.updateSessionExtras("keep-me")
        dual.updateRolePersona("persona-keep", null)
        // 最小快照：只重放档位 + 模式；其余通道缺席 = 不重放（不清空）
        dual.applyRuntimeState(
            EngineRuntimeState(
                thinkingLevel = CodeThinkingLevel.DEEP,
                mode = AgentMode.BUILD
            )
        )
        // 缺席通道未收到清空调用（extras/persona 只在最初写入过一次）
        assertEquals(1, std.calls.count { it == "extras" })
        assertEquals(1, std.calls.count { it == "persona" })
        // 档位路由当前激活线（默认深潜）；模式广播四线
        assertTrue(deep.calls.contains("tier:DEEP"))
        assertTrue(std.calls.contains("mode:BUILD"))
    }

    @Test
    fun `tier carries over across switches without waiting for next send`() {
        val deep = RecordingFacade("deep")
        val std = RecordingFacade("std")
        val loom = RecordingFacade("loom")
        val courier = RecordingFacade("courier")
        val dual = DualLogicCodeEngine(
            deepDive = deep, standard = std, loom = loom, courier = courier
        )
        // 设置 ULTRACODE（深潜线，切线前）
        dual.updateThinkingLevel(CodeThinkingLevel.ULTRACODE)
        // 四线各切一遍：模拟 CodeLogicModeOps 口径——switchLogic 后整包重放
        // 档位快照；每线立刻读档位都应一致（SPE-03 验收 #1：无需等发送前刷新）
        listOf(StandardLogicMode.STANDARD, StandardLogicMode.LOOM, StandardLogicMode.COURIER)
            .forEach { mode ->
                assertTrue(dual.switchLogic(mode))
                dual.applyRuntimeState(EngineRuntimeState(CodeThinkingLevel.ULTRACODE))
                assertEquals("切到 $mode 并重放快照后立刻读档位应为 ULTRACODE", CodeThinkingLevel.ULTRACODE, dual.thinkingLevel())
            }
    }
}
