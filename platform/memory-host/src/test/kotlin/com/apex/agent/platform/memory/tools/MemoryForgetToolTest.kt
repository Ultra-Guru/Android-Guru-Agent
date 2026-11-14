package com.apex.agent.platform.memory.tools

import com.apex.memkit.core.MemKit
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.store.InMemoryStorageBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryForget 工具契约锁（记忆 P0-3 逐条纠正面）：
 * 事实删除（精确/唯一包含/歧义拒绝/未命中）/ 实体级联删除 / 观察移除 /
 * 坏参数错误消息而非异常。工具层只做参数解析与门面转调 —— 断言值与
 * apex-kit-memory 的 store 扩展（removeFact / deleteGraphEntities /
 * deleteObservations）行为同源。
 */
class MemoryForgetToolTest {

    private suspend fun newKit(): MemKit = MemKit.create(
        MemKitConfig(), InMemoryStorageBackend(),
        logger = SilentLogger, clock = { 1_700_000_000_000L },
    )

    @Test
    fun `forget fact by exact content removes it`() = runTest {
        val kit = newKit()
        kit.start()
        try {
            kit.saveFact("用户偏好深色模式", com.apex.memkit.core.model.ChatFact.Kind.PROFILE, 0.8f)
            val tool = MemoryForgetTool(kit)
            val out = tool.execute("""{"action": "forget_fact", "content": "用户偏好深色模式"}""")
            assertTrue(out.contains("removed"))
            assertTrue(kit.allFacts().none { it.content == "用户偏好深色模式" })
        } finally {
            kit.close()
        }
    }

    @Test
    fun `forget fact by unique substring removes the right one`() = runTest {
        val kit = newKit()
        kit.start()
        try {
            kit.saveFact("用户偏好深色模式", com.apex.memkit.core.model.ChatFact.Kind.PROFILE, 0.8f)
            kit.saveFact("用户养了一只猫", com.apex.memkit.core.model.ChatFact.Kind.PROFILE, 0.6f)
            val tool = MemoryForgetTool(kit)
            val out = tool.execute("""{"action": "forget_fact", "content": "深色模式"}""")
            assertTrue(out.contains("removed"))
            assertTrue(kit.allFacts().none { it.content.contains("深色") })
            assertEquals(1, kit.allFacts().size)   // 猫还在
        } finally {
            kit.close()
        }
    }

    @Test
    fun `ambiguous match is rejected with candidates listed`() = runTest {
        val kit = newKit()
        kit.start()
        try {
            kit.saveFact("喜欢深色模式A", com.apex.memkit.core.model.ChatFact.Kind.PROFILE, 0.8f)
            kit.saveFact("喜欢深色模式B", com.apex.memkit.core.model.ChatFact.Kind.PROFILE, 0.8f)
            val tool = MemoryForgetTool(kit)
            val out = tool.execute("""{"action": "forget_fact", "content": "深色模式"}""")
            assertTrue(out.startsWith("Error: ambiguous"))
            assertEquals(2, kit.allFacts().size)   // 歧义拒绝：一条都不删
        } finally {
            kit.close()
        }
    }

    @Test
    fun `forget fact by id and miss cases`() = runTest {
        val kit = newKit()
        kit.start()
        try {
            val saved = kit.saveFact("某条事实", com.apex.memkit.core.model.ChatFact.Kind.PROFILE, 0.5f)
            assertTrue(saved.added)
            val id = kit.allFacts().first().id
            val tool = MemoryForgetTool(kit)
            assertTrue(tool.execute("""{"action": "forget_fact", "id": "$id"}""").contains("removed"))
            // 再删同 id：幂等 miss
            assertTrue(tool.execute("""{"action": "forget_fact", "id": "$id"}""").contains("Error"))
            // 无匹配内容
            assertTrue(
                tool.execute("""{"action": "forget_fact", "content": "不存在的记忆"}""").contains("Error"),
            )
        } finally {
            kit.close()
        }
    }

    @Test
    fun `forget entity cascades relations and forget observations keeps entity`() = runTest {
        val kit = newKit()
        kit.start()
        try {
            kit.upsertGraphEntities(
                listOf(
                    com.apex.memkit.core.model.GraphEntity(
                        name = "微信", entityType = "app",
                        observations = listOf("聊天应用", "通讯工具"),
                    ),
                ),
            )
            kit.addGraphRelations(
                listOf(
                    com.apex.memkit.core.model.GraphRelation("微信", "发送图片", "支持功能"),
                ),
            )
            val tool = MemoryForgetTool(kit)

            // 观察移除：实体保留、指定观察消失
            val obs = tool.execute(
                """{"action": "forget_observations", "entity": "微信", "contents": ["聊天应用"]}""",
            )
            assertTrue(obs.contains("Removed"))
            val afterObs = kit.readGraph().first
            assertEquals(1, afterObs.size)
            assertTrue(afterObs[0].observations == listOf("通讯工具"))

            // 实体删除：级联清理关联关系
            val ent = tool.execute("""{"action": "forget_entity", "name": "微信"}""")
            assertTrue(ent.contains("removed"))
            val (entities, relations) = kit.readGraph()
            assertTrue(entities.isEmpty())
            assertTrue(relations.isEmpty())

            // 不存在的实体
            assertTrue(tool.execute("""{"action": "forget_entity", "name": "不存在"}""").contains("Error"))
        } finally {
            kit.close()
        }
    }

    @Test
    fun `bad arguments return error messages not exceptions`() = runTest {
        val kit = newKit()
        kit.start()
        try {
            val tool = MemoryForgetTool(kit)
            assertTrue(tool.execute("not-json").contains("Error"))
            assertTrue(tool.execute("""{"action": "unknown"}""").contains("Error"))
            assertTrue(tool.execute("""{"action": "forget_fact"}""").contains("Error"))
            assertFalse(tool.execute("""{"action": "forget_entity"}""").isEmpty())
        } finally {
            kit.close()
        }
    }
}
