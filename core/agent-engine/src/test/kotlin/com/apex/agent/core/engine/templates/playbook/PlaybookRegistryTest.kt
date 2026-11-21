package com.apex.agent.core.engine.templates.playbook

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 任务剧本注册表测试（与 PromptTemplateRegistryTest 同覆盖面）：
 * roundtrip 字段抽查、内置保存/删除保护、篡改自愈、导入丢弃内置、
 * 重播种幂等与 force 还原、id 校验、原子写无 temp 残留、onChanged 回调。
 */
class PlaybookRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registry: PlaybookRegistry

    /** 与 [registry] 同一存储目录（重开/篡改断言必须同路径）。 */
    private lateinit var storeDir: File

    @Before
    fun setUp() {
        storeDir = tmp.newFolder("playbooks")
        registry = PlaybookRegistry(storageDir = storeDir)
    }

    private fun userPlaybook(id: String = "user_pb_1") = PlaybookDefinition(
        id = id,
        name = "自定义剧本",
        master = "你是测试剧本。",
        stages = listOf(PlaybookStage(id = "s1", title = "单幕", subPrompt = "做点事")),
        isBuiltIn = false
    )

    @Test
    fun `save 后 get 可读 roundtrip 关键字段`() = runTest {
        registry.save(userPlaybook())
        val loaded = registry.get("user_pb_1")
        assertNotNull(loaded)
        assertEquals("自定义剧本", loaded!!.name)
        assertEquals(1, loaded.stages.size)
        assertEquals("单幕", loaded.stages.first().title)
        assertFalse(loaded.isBuiltIn)
        assertTrue(loaded.updatedAt > 0)
    }

    @Test
    fun `重开注册表 roundtrip 持久化`() = runTest {
        registry.save(userPlaybook())
        val reopened = PlaybookRegistry(storageDir = storeDir)
        assertEquals("自定义剧本", reopened.get("user_pb_1")!!.name)
    }

    @Test
    fun `非法 id 与空白字段 fail-fast`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { registry.save(userPlaybook("Bad_Id!")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { registry.save(userPlaybook().copy(name = " ")) }
        }
    }

    @Test
    fun `内置保存与删除一律拒绝`() = runTest {
        registry.reseedBuiltIns()
        val builtin = registry.get(BuiltinPlaybooks.ALL.first().id)!!
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { registry.save(builtin.copy(name = "改名")) }
        }
        assertFalse(registry.delete(builtin.id))
    }

    @Test
    fun `篡改内置条目加载时自愈回出厂定义`() = runTest {
        registry.reseedBuiltIns()
        // 模拟外部改写磁盘 JSON（老版本遗留 / 手改）。
        val file = File(storeDir, "playbooks.json")
        val payload = file.readText()
            .replace("代码审查剧本", "被篡改的名字")
        file.writeText(payload)

        val reopened = PlaybookRegistry(storageDir = storeDir)
        val healed = reopened.get(BuiltinPlaybooks.ALL.first().id)!!
        assertEquals("代码审查剧本", healed.name)
    }

    @Test
    fun `自愈保留运行元数据（usageCount）`() = runTest {
        registry.reseedBuiltIns()
        registry.incrementUsage(BuiltinPlaybooks.ALL.first().id)
        registry.incrementUsage(BuiltinPlaybooks.ALL.first().id)
        val reopened = PlaybookRegistry(storageDir = storeDir)
        assertEquals(2L, reopened.get(BuiltinPlaybooks.ALL.first().id)!!.usageCount)
    }

    @Test
    fun `reseedBuiltIns 幂等 且 force 重置运行元数据`() = runTest {
        val seededFirst = registry.reseedBuiltIns()
        assertEquals(5, seededFirst)
        assertEquals(0, registry.reseedBuiltIns())

        // 留下运行元数据（usageCount）：自愈保留它，force 才整体还原出厂定义。
        registry.incrementUsage("builtin_playbook_test_cases")
        val reopened = PlaybookRegistry(storageDir = storeDir)
        assertEquals(1L, reopened.get("builtin_playbook_test_cases")!!.usageCount)
        val restored = kotlinx.coroutines.runBlocking { reopened.reseedBuiltIns(force = true) }
        assertEquals(1, restored)
        assertEquals(0L, reopened.get("builtin_playbook_test_cases")!!.usageCount)
    }

    @Test
    fun `导入丢弃内置 id 且自定义正常入库`() = runTest {
        registry.reseedBuiltIns()
        val mixed = """
            {"playbooks":[
                {"id":"builtin_playbook_code_review","name":"伪造内置","master":"m","stages":[{"id":"s","title":"t","subPrompt":"b"}]},
                {"id":"user_pb_x","name":"外来剧本","master":"m","stages":[{"id":"s","title":"t","subPrompt":"b"}]}
            ]}
        """.trimIndent()
        val result = registry.importLibrary(mixed, PlaybookImportMode.MERGE)
        assertTrue(result.ok)
        assertEquals(1, result.importedCount)
        assertEquals(1, result.skippedBuiltInCount)
        assertEquals("外来剧本", registry.get("user_pb_x")!!.name)
        // 伪造的内置条目没有覆盖出厂定义。
        assertEquals("代码审查剧本", registry.get("builtin_playbook_code_review")!!.name)
    }

    @Test
    fun `损坏 JSON 备份后空库启动`() = runTest {
        registry.save(userPlaybook())
        File(storeDir, "playbooks.json").writeText("{not-json")
        val reopened = PlaybookRegistry(storageDir = storeDir)
        assertTrue(reopened.list().isEmpty())
        assertTrue(File(storeDir, "playbooks.json.corrupt").exists())
    }

    @Test
    fun `原子写无 temp 残留`() = runTest {
        registry.save(userPlaybook())
        assertFalse(File(storeDir, "playbooks.json.tmp").exists())
        assertTrue(File(storeDir, "playbooks.json").exists())
    }

    @Test
    fun `onChanged 在保存与删除时回调`() = runTest {
        var changes = 0
        val watched = PlaybookRegistry(storageDir = tmp.newFolder("watched")) { changes++ }
        kotlinx.coroutines.runBlocking { watched.save(userPlaybook()) }
        assertEquals(1, changes)
        kotlinx.coroutines.runBlocking { watched.delete("user_pb_1") }
        assertEquals(2, changes)
    }

    @Test
    fun `delete 不存在的 id 返回 false`() = runTest {
        assertFalse(registry.delete("no_such_id"))
        assertNull(registry.get("no_such_id"))
    }
}
