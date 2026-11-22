package com.apex.agent.tools

import com.apex.agent.core.engine.templates.playbook.PlaybookRegistry
import com.apex.agent.ui.screen.code.PlaybookSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PlaybookTool 测试（纯 JVM）：三动作语义（list/stage/advance）、快照桥
 * 读侧、无挂载防线、末幕引导收尾、坏参数防御。
 */
class PlaybookToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registry: PlaybookRegistry
    private lateinit var store: PlaybookSessionStore
    private lateinit var tool: PlaybookTool

    @Before
    fun setUp() {
        registry = PlaybookRegistry(storageDir = File(tmp.newFolder("store").path))
        store = PlaybookSessionStore()
        tool = PlaybookTool(registry, store)
        runTest { registry.reseedBuiltIns() }
    }

    private suspend fun exec(json: String): String = tool.execute(json)

    @Test
    fun `list 列出五套内置且标挂载态`() = runTest {
        val out = exec("""{"action":"list"}""")
        assertTrue(out.contains("builtin_playbook_code_review"))
        assertTrue(out.contains("builtin_playbook_commit_message"))
        assertFalse(out.contains("（当前挂载"))
    }

    @Test
    fun `list 挂载后标记当前幕`() = runTest {
        store.publish(
            PlaybookSessionStore.SessionSnapshot(
                playbookId = "builtin_playbook_code_review", playbookName = "代码审查剧本",
                stageIndex = 1, stageCount = 4, stageTitle = "正确性与架构", completedStageIndexes = listOf(0)
            )
        )
        val out = exec("""{"action":"list"}""")
        assertTrue(out.contains("当前挂载：幕2/4"))
    }

    @Test
    fun `stage 显式 id 取幕内容与验收`() = runTest {
        val out = exec("""{"action":"stage","playbook_id":"builtin_playbook_test_cases","stage_index":2}""")
        assertTrue(out.contains("幕2·用例表"))
        assertTrue(out.contains("本幕验收"))
        assertTrue(out.contains("三类路径全覆盖"))
    }

    @Test
    fun `stage 缺 id 无挂载报错引导`() = runTest {
        val out = exec("""{"action":"stage"}""")
        assertTrue(out.startsWith("Error"))
        assertTrue(out.contains("list"))
    }

    @Test
    fun `stage 幕越界报错`() = runTest {
        val out = exec("""{"action":"stage","playbook_id":"builtin_playbook_code_review","stage_index":9}""")
        assertTrue(out.startsWith("Error"))
        assertTrue(out.contains("越界"))
    }

    @Test
    fun `advance 无挂载报错`() = runTest {
        val out = exec("""{"action":"advance"}""")
        assertTrue(out.startsWith("Error"))
        assertTrue(out.contains("无挂载"))
    }

    @Test
    fun `advance 末幕引导输出完成标记而非推进`() = runTest {
        store.publish(
            PlaybookSessionStore.SessionSnapshot(
                playbookId = "builtin_playbook_commit_message", playbookName = "Git 提交信息剧本",
                stageIndex = 0, stageCount = 1, stageTitle = "变更解读与提交信息", completedStageIndexes = emptyList()
            )
        )
        val out = exec("""{"action":"advance","playbook_id":"builtin_playbook_commit_message"}""")
        assertTrue(out.startsWith("提示"))
        assertTrue(out.contains("《剧本完成》"))
    }

    @Test
    fun `advance 正常挂载发命令且命令可收集`() = runTest {
        store.publish(
            PlaybookSessionStore.SessionSnapshot(
                playbookId = "builtin_playbook_code_review", playbookName = "代码审查剧本",
                stageIndex = 0, stageCount = 4, stageTitle = "预检侦察", completedStageIndexes = emptyList()
            )
        )
        val out = exec("""{"action":"advance","playbook_id":"builtin_playbook_code_review"}""")
        assertTrue(out.contains("已受理"))
        val received = mutableListOf<PlaybookSessionStore.AdvanceCommand>()
        val job = launch {
            store.commands.collect { received.add(it) }
        }
        delay(100)
        job.cancel()
        assertEquals(1, received.size)
        assertEquals(0, received.first().fromStageIndex)
    }

    @Test
    fun `advance 误报他剧本 id 拒绝`() = runTest {
        store.publish(
            PlaybookSessionStore.SessionSnapshot(
                playbookId = "builtin_playbook_code_review", playbookName = "代码审查剧本",
                stageIndex = 0, stageCount = 4, stageTitle = "预检侦察", completedStageIndexes = emptyList()
            )
        )
        val out = exec("""{"action":"advance","playbook_id":"builtin_playbook_bug_fix"}""")
        assertTrue(out.startsWith("Error"))
        assertTrue(out.contains("不符"))
    }

    @Test
    fun `坏 JSON 与缺 action 防御`() = runTest {
        assertTrue(exec("not-json").startsWith("Error"))
        assertTrue(exec("{}").startsWith("Error"))
        assertTrue(exec("""{"action":"nope"}""").startsWith("Error"))
    }
}
