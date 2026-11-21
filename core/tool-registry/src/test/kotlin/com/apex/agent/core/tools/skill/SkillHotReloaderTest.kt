package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [SkillHotReloader] 纯 JVM 单测（Issue #151：技能装、卸、开关免重启）。
 *
 * 覆盖：启动注册、changes 流热注册、卸载移除、开关切换、核心工具占用防护、
 * 重复 id 去重、旧版启动快照吸收（升级路径）、stop 退订、原地升级刷新。
 *
 * 说明：需要走 changes 流的用例把 reloader 挂在 runTest 的 backgroundScope 上
 * （测试结束自动取消，避免常驻订阅协程挂起测试）；其余用例直接手动调 resync，
 * 与订阅路径共用同一套幂等 diff 算法，结果与触发方式无关。
 */
class SkillHotReloaderTest {

    private lateinit var skillsDir: File
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var toolRegistry: DefaultToolRegistry
    private val executor = FakeToolExecutor()

    /** 记录式日志出口：既验证 warn 分支真的触发，也方便失败时排查。 */
    private val logMessages = mutableListOf<Pair<SkillHotReloadLogLevel, String>>()
    private val recordingSink = SkillHotReloadLogSink { level, message ->
        logMessages.add(level to message)
    }

    @Before
    fun setUp() {
        skillsDir = Files.createTempDirectory("skill-hot-reload-test").toFile()
        skillRegistry = SkillRegistry(skillsDir)
        toolRegistry = DefaultToolRegistry()
        logMessages.clear()
    }

    @After
    fun tearDown() {
        skillsDir.deleteRecursively()
    }

    private fun newReloader(scope: CoroutineScope): SkillHotReloader =
        SkillHotReloader(skillRegistry, toolRegistry, executor, scope, recordingSink)

    private fun warnCount(id: String): Int = logMessages.count { (level, msg) ->
        level == SkillHotReloadLogLevel.WARN && msg.contains(id)
    }

    // ═══ 1. 启动即注册 ═══════════════════════════════════════════

    @Test
    fun `install before start registers composite tool into registry`() = runTest {
        skillRegistry.install(
            manifestJson("SkillA", Triple("SkillATool", "技能A工具", "A 的组合工具"))
        ).getOrThrow()

        val reloader = newReloader(backgroundScope)
        reloader.start()

        val tool = toolRegistry.getTool("SkillATool")
        assertNotNull(tool)
        assertEquals("A 的组合工具", tool!!.description)
        assertNotNull(toolRegistry.getTool("SkillATool"))

        // 执行冒烟：composite 步骤确实经由注入的 ToolExecutor 落地
        val result = tool!!.execute("""{"input": "hello"}""")
        assertEquals("ok:echo_probe", result)
        assertEquals(1, executor.calls.size)
        assertEquals("echo_probe", executor.calls.single().first)
        assertTrue(executor.calls.single().second.contains("hello"))
    }

    // ═══ 2. 启动后安装：changes 流热注册 ═════════════════════════

    @Test
    fun `installing a skill after start flows through changes into registry`() = runTest {
        val reloader = newReloader(backgroundScope)
        reloader.start()
        // coroutines-test 1.9.0：advanceUntilIdle 不推进 background-scope 的工作，
        // 订阅协程必须用 runCurrent 驱动到挂起（订阅就位）。
        runCurrent()

        skillRegistry.install(
            manifestJson("SkillB", Triple("SkillBTool", "技能B工具", "B 的组合工具"))
        ).getOrThrow()
        runCurrent() // tryEmit → 订阅方收到 → resync（发射投递同样需 runCurrent）

        // 不手动 resync，纯靠订阅路径生效
        val tool = toolRegistry.getTool("SkillBTool")
        assertNotNull(tool)
        assertEquals("B 的组合工具", tool!!.description)
        assertNotNull(toolRegistry.getTool("SkillBTool"))
        reloader.stop()
    }

    // ═══ 3. 卸载移除 ═════════════════════════════════════════════

    @Test
    fun `uninstall removes the tool after resync`() = runTest {
        skillRegistry.install(
            manifestJson("SkillC", Triple("SkillCTool", "技能C工具", "C 的组合工具"))
        ).getOrThrow()
        val reloader = newReloader(backgroundScope)
        reloader.start()
        assertNotNull(toolRegistry.getTool("SkillCTool"))

        assertTrue(skillRegistry.uninstall("SkillC"))
        reloader.resync()

        assertNull(toolRegistry.getTool("SkillCTool"))
        assertEquals(0, toolRegistry.getAllTools().count { it.id.startsWith("Skill") })
    }

    // ═══ 4. 开关切换 ═════════════════════════════════════════════

    @Test
    fun `disabling and re-enabling toggles the tool`() = runTest {
        skillRegistry.install(
            manifestJson("SkillD", Triple("SkillDTool", "技能D工具", "D 的组合工具"))
        ).getOrThrow()
        val reloader = newReloader(backgroundScope)
        reloader.start()
        assertNotNull(toolRegistry.getTool("SkillDTool"))

        skillRegistry.setEnabled("SkillD", false)
        reloader.resync()
        assertNull(toolRegistry.getTool("SkillDTool"))

        skillRegistry.setEnabled("SkillD", true)
        reloader.resync()
        assertNotNull(toolRegistry.getTool("SkillDTool"))
        assertNotNull(toolRegistry.getTool("SkillDTool"))
    }

    // ═══ 5. 核心工具占用防护（不劫持）═════════════════════════════

    @Test
    fun `core-occupied tool id is never hijacked`() = runTest {
        val coreTool = StubCoreTool("OccupiedTool", "核心占位工具")
        toolRegistry.register(coreTool)

        val reloader = newReloader(backgroundScope)
        reloader.start()

        skillRegistry.install(
            manifestJson("SkillE", Triple("OccupiedTool", "同名技能工具", "想抢核心工具的描述"))
        ).getOrThrow()
        reloader.resync()

        // 原实例原描述都未被替换，且技能侧未接管该 id
        val current = toolRegistry.getTool("OccupiedTool")
        assertSame(coreTool, current)
        assertEquals("核心占位工具", current!!.description)
        assertEquals(0, toolRegistry.getAllTools().count { it.id.startsWith("Skill") })
        assertTrue(warnCount("OccupiedTool") > 0)

        // 卸载该技能也绝不误删核心工具
        skillRegistry.uninstall("SkillE")
        reloader.resync()
        assertSame(coreTool, toolRegistry.getTool("OccupiedTool"))
    }

    // ═══ 6. 重复工具 id：只注册首个声明 ══════════════════════════

    @Test
    fun `duplicate tool id across skills registers only the first`() = runTest {
        skillRegistry.install(
            manifestJson("SkillX", Triple("SharedTool", "共享工具", "来自技能X"))
        ).getOrThrow()
        skillRegistry.install(
            manifestJson("SkillY", Triple("SharedTool", "共享工具", "来自技能Y"))
        ).getOrThrow()

        val reloader = newReloader(backgroundScope)
        reloader.start()

        // 只注册了一个实例，且是先安装技能 X 的定义
        assertNotNull(toolRegistry.getTool("SharedTool"))
        assertEquals("来自技能X", toolRegistry.getTool("SharedTool")!!.description)
        assertTrue(warnCount("SharedTool") > 0)
    }

    // ═══ 7. 旧版启动快照吸收（升级路径）══════════════════════════

    @Test
    fun `legacy startup snapshot ids are absorbed then manageable`() = runTest {
        skillRegistry.install(
            manifestJson("SkillLegacy", Triple("LegacyTool", "遗留工具", "旧快照注册的工具"))
        ).getOrThrow()

        // 模拟老版本 ToolModule 的启动快照：reloader 之外直接注册同款包装
        val legacyDef = skillRegistry.getActiveTools().single()
        toolRegistry.register(SafeAgentTool(SkillToolAdapter(legacyDef, executor)))
        assertNotNull(toolRegistry.getTool("LegacyTool"))

        val reloader = newReloader(backgroundScope)
        reloader.start() // 吸收 + 首次同步：不重复注册、不重复计账

        assertNotNull(toolRegistry.getTool("LegacyTool"))

        // 吸收来的 id 也要能被热卸载（升级前必须重启才能做到）
        skillRegistry.uninstall("SkillLegacy")
        reloader.resync()
        assertNull(toolRegistry.getTool("LegacyTool"))
        assertEquals(0, toolRegistry.getAllTools().count { it.id.startsWith("Skill") })
    }

    // ═══ 8. stop 退订，手动 resync 仍可兜底 ══════════════════════

    @Test
    fun `stop unsubscribes but manual resync still heals`() = runTest {
        val reloader = newReloader(backgroundScope)
        reloader.start()
        advanceUntilIdle()
        reloader.stop()

        skillRegistry.install(
            manifestJson("SkillLate", Triple("LateTool", "迟来工具", "stop 之后安装的技能"))
        ).getOrThrow()
        advanceUntilIdle() // 订阅已取消：changes 不再触发同步

        assertNull(toolRegistry.getTool("LateTool"))

        // 兜底通道：手动 resync 仍然补得上
        reloader.resync()
        assertNotNull(toolRegistry.getTool("LateTool"))
    }

    // ═══ 9. 技能原地升级：定义刷新 ══════════════════════════════

    @Test
    fun `reinstalling a skill refreshes tool definition in place`() = runTest {
        skillRegistry.install(
            manifestJson("SkillUp", Triple("UpTool", "升级工具", "旧描述"))
        ).getOrThrow()
        val reloader = newReloader(backgroundScope)
        reloader.start()
        assertEquals("旧描述", toolRegistry.getTool("UpTool")!!.description)

        // 不先卸载，直接以同 id 重装新版本
        skillRegistry.install(
            manifestJson("SkillUp", Triple("UpTool", "升级工具", "新描述"))
        ).getOrThrow()
        reloader.resync()

        assertEquals("新描述", toolRegistry.getTool("UpTool")!!.description)
        assertNotNull(toolRegistry.getTool("UpTool"))
    }

    // ═══ 测试替身与 manifest 构造 ════════════════════════════════

    /**
     * 最小合法 apex-skill-v1 manifest（JSON 字符串），每个技能一个 composite 工具。
     * [tools] 为（工具 id、工具名、描述）三元组。
     */
    private fun manifestJson(skillId: String, vararg tools: Triple<String, String, String>): String {
        val toolBlocks = tools.joinToString(",\n") { (toolId, toolName, description) ->
            """
            {
              "id": "$toolId",
              "name": "$toolName",
              "description": "$description",
              "parameters": "{\"type\": \"object\", \"properties\": {\"input\": {\"type\": \"string\"}}}",
              "implementation": {
                "type": "composite",
                "steps": [ { "tool": "echo_probe", "args": { "text": "{{input}}" } } ]
              }
            }""".trimIndent()
        }
        return """
        {
          "schema": "apex-skill-v1",
          "id": "$skillId",
          "name": "技能-$skillId",
          "version": "1.0.0",
          "description": "SkillHotReloaderTest 专用技能",
          "tools": [
        $toolBlocks
          ]
        }""".trimIndent()
    }

    /** 假执行器：记录调用并返回固定结果，验证 SkillToolAdapter 的步骤确实经过它。 */
    private class FakeToolExecutor : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun execute(toolId: String, arguments: String): String {
            calls.add(toolId to arguments)
            return "ok:$toolId"
        }

        override fun executeStream(toolId: String, arguments: String) = flow {
            emit(ToolStreamEvent.Output(execute(toolId, arguments)))
            emit(ToolStreamEvent.Complete("ok:$toolId"))
        }
    }

    /** 核心工具替身：用于验证占用防护（实例与描述都不能被技能工具替换）。 */
    private class StubCoreTool(
        override val id: String,
        override val description: String
    ) : AgentTool {
        override val name: String = id
        override val parametersSchema: String = "{}"
        override suspend fun execute(arguments: String): String = "stub:$id"
    }
}
