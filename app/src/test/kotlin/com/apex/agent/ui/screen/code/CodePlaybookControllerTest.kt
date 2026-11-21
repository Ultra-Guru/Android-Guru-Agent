package com.apex.agent.ui.screen.code

import com.apex.agent.core.engine.templates.playbook.PlaybookRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 任务剧本控制器测试（纯 JVM）：档位三态、建议/静默挂载、上下文门、
 * 幕推进状态迁移、进度一行态、卸载与重挂载、记账回调。
 */
class CodePlaybookControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registry: PlaybookRegistry
    private var autoMount: String = "suggest"
    private var mountRecorded: MutableList<String> = mutableListOf()

    @Before
    fun setUp() {
        registry = PlaybookRegistry(storageDir = File(tmp.newFolder("store").path))
        runTest { registry.reseedBuiltIns() }
    }

    private fun controller(): CodePlaybookController {
        val controller = CodePlaybookController({ autoMount }, registry, PlaybookSessionStore())
        controller.onMountRecorded = { mountRecorded.add(it) }
        return controller
    }

    private fun controllerWithoutStore(): CodePlaybookController =
        CodePlaybookController({ autoMount }, registry)

    @Test
    fun `suggest 档命中出建议不直接挂载`() {
        val controller = controller()
        val extras = controller.prepareForRun("基串", "帮我审查这段代码的并发问题", hasWorkspace = true)
        val state = controller.state.value
        assertNotNull(state.suggest)
        assertNull(state.mounted)
        assertEquals("代码审查剧本", state.suggest!!.playbook.name)
        // extras 仍是原串（挂载前零注入）。
        assertEquals("基串", extras)
    }

    @Test
    fun `acceptSuggestion 挂载并产出指令段`() {
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        controller.acceptSuggestion()
        val state = controller.state.value
        assertNotNull(state.mounted)
        assertNull(state.suggest)
        assertEquals(mountRecorded, listOf("builtin_playbook_code_review"))

        val extras = controller.prepareForRun("基串", "继续", hasWorkspace = true)
        assertTrue(extras.contains("任务剧本《代码审查剧本》"))
        assertTrue(extras.contains("【幕1·预检侦察】"))
        assertTrue(extras.startsWith("基串"))
    }

    @Test
    fun `auto 档静默挂载本轮即注入`() {
        autoMount = "auto"
        val controller = controller()
        val extras = controller.prepareForRun("基串", "帮我重构这个模块", hasWorkspace = true)
        assertNotNull(controller.state.value.mounted)
        assertTrue(extras.contains("任务剧本《重构方案剧本》"))
    }

    @Test
    fun `off 档零识别`() {
        autoMount = "off"
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        assertNull(controller.state.value.suggest)
        assertNull(controller.state.value.mounted)
    }

    @Test
    fun `弱信号不过建议（绝对门）`() {
        val controller = controller()
        controller.prepareForRun("基串", "跑一下测试", hasWorkspace = true)
        assertNull(controller.state.value.suggest)
    }

    @Test
    fun `无工作区 Git 剧本哑火（上下文门）`() {
        autoMount = "auto"
        val controller = controller()
        controller.prepareForRun("基串", "写个提交信息 commit message", hasWorkspace = false)
        assertNull(controller.state.value.mounted)
    }

    @Test
    fun `本次不用后同剧本不再建议`() {
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        controller.dismissSuggestion()
        assertNull(controller.state.value.suggest)
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        assertNull("dismissed 后同轮再识别不应再建议", controller.state.value.suggest)
    }

    @Test
    fun `幕推进判定驱动状态迁移`() {
        autoMount = "auto"
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        val mounted = controller.state.value.mounted!!
        assertEquals(0, mounted.stageIndex)

        // 幕1 实质产出 + 幕2 头 → 推进。
        val response = "【幕1·预检侦察】\n" + "x".repeat(120) + "\n\n【幕2·正确性与架构】\n"
        val outcome = controller.onAdvanceJudged(response)!!
        assertEquals("ADVANCED 预期", "ADVANCED", outcome.kind.name)
        assertEquals(1, controller.state.value.mounted!!.stageIndex)
        assertEquals(listOf(0), controller.state.value.mounted!!.completedStages)

        // 推进后指令段指向幕2。
        val extras = controller.recomposedExtras()!!
        assertTrue(extras.contains("【幕2·正确性与架构】"))
    }

    @Test
    fun `末幕完成标记收尾`() {
        autoMount = "auto"
        val controller = controller()
        controller.prepareForRun("基串", "写个提交信息 commit message", hasWorkspace = true)
        // Git 提交剧本 = COMPOSED 单幕：首轮回包即末幕。
        val response = "【幕1·变更解读与提交信息】\n" + "x".repeat(150) + "\n《剧本完成》"
        val outcome = controller.onAdvanceJudged(response)!!
        assertEquals("COMPLETED 预期", "COMPLETED", outcome.kind.name)
    }

    @Test
    fun `卸载后指令段回落原串`() {
        autoMount = "auto"
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        controller.unmount()
        assertNull(controller.state.value.mounted)
        assertEquals("基串", controller.prepareForRun("基串", "继续", hasWorkspace = true))
    }

    @Test
    fun `跳幕校验范围并刷新已完成集`() {
        autoMount = "auto"
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        assertTrue(controller.jumpToStage(2))
        val mounted = controller.state.value.mounted!!
        assertEquals(2, mounted.stageIndex)
        assertEquals(listOf(0, 1), mounted.completedStages)
        assertFalse(controller.jumpToStage(99))
    }

    @Test
    fun `非挂载态推进判定返回 null`() {
        val controller = controller()
        assertNull(controller.onAdvanceJudged("【幕1·x】" + "y".repeat(200)))
    }

    // ═══ PR-C 工具通道 ═══

    @Test
    fun `挂载后快照发布到 store 卸载清空`() {
        autoMount = "auto"
        val store = PlaybookSessionStore()
        val controller = CodePlaybookController({ autoMount }, registry, store)
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        val snapshot = store.snapshot.value
        assertNotNull(snapshot)
        assertEquals("builtin_playbook_code_review", snapshot!!.playbookId)
        assertEquals(0, snapshot.stageIndex)
        assertEquals(4, snapshot.stageCount)
        controller.unmount()
        assertNull(store.snapshot.value)
    }

    @Test
    fun `manualAdvance 信任直推并刷新快照`() {
        autoMount = "auto"
        val controller = controller()
        controller.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        val outcome = controller.manualAdvance(fromStageIndex = 0)!!
        assertEquals("ADVANCED", outcome.kind.name)
        assertEquals(1, controller.state.value.mounted!!.stageIndex)
        assertEquals(listOf(0), controller.state.value.mounted!!.completedStages)
        // 幕序不符拒绝。
        assertNull(controller.manualAdvance(fromStageIndex = 0))
        // 末幕手动推进拒绝（由完成标记收尾）。
        controller.jumpToStage(3)
        assertNull(controller.manualAdvance(fromStageIndex = 3))
    }

    @Test
    fun `无 store 构造不崩（纯 UI 态兼容）`() {
        val bare = controllerWithoutStore()
        autoMount = "auto"
        bare.prepareForRun("基串", "帮我审查这段代码", hasWorkspace = true)
        assertNotNull(bare.state.value.mounted)
        bare.unmount()
        assertNull(bare.state.value.mounted)
    }
}
