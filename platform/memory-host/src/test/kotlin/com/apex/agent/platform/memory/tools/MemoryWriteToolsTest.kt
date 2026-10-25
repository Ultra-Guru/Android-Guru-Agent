package com.apex.agent.platform.memory.tools

import com.apex.memkit.core.MemKit
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.SilentLogger
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.store.InMemoryStorageBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 记忆写入工具族契约锁 —— 「任务后整理经验」的端到端语义。
 *
 * 工具层只做两件事：参数裁剪（防御式 JSON 解析）与门面转调；本测试
 * 锁死「合法参数 → 正确落库 → 可读回报」与「非法参数 → 错误消息而非
 * 异常」两个面。断言值与 apex-kit-memory 的 ExperienceConsolidationTest
 * 同源（先读库侧实现核实）。
 */
class MemoryWriteToolsTest {

    private lateinit var kit: MemKit

    @Before
    fun setup() {
        kit = runBlocking {
            MemKit.create(
                MemKitConfig(), InMemoryStorageBackend(),
                logger = SilentLogger, clock = { 1_700_000_000_000L },
            )
        }
        kit.start()
    }

    @After
    fun teardown() {
        runBlocking { kit.close() }
    }

    // ══ MemoryConsolidateExperience ═══════════════════════════════

    @Test
    fun `经验整理 - 摘要教训实体关系全量落库并回报`() = runTest {
        val tool = MemoryConsolidateExperienceTool(kit)
        val out = tool.execute(
            """
            {"goal": "给张三发送图片", "outcome": "经聊天窗口附件选择完成", "success": true,
             "lessons": ["权限弹窗出现时先等待 1 秒再点同意"],
             "entities": [{"name": "微信", "entityType": "app", "observations": ["聊天应用"]}],
             "relations": [{"from": "微信", "to": "发送图片", "relationType": "支持功能"}]}
            """.trimIndent(),
        )
        assertTrue(out.contains("Experience consolidated"))
        assertTrue(out.contains("milestone"))

        val facts = kit.allFacts()
        assertTrue(facts.any { it.kind == ChatFact.Kind.MILESTONE && it.content.contains("给张三发送图片") })
        assertTrue(facts.any { it.kind == ChatFact.Kind.PROFILE && it.content.contains("权限弹窗") })
        val (entities, relations) = kit.readGraph()
        assertTrue(entities.any { it.name == "微信" && it.entityType == "app" })
        // 「发送图片」悬空端 → 占位实体
        assertTrue(entities.any { it.name == "发送图片" && it.entityType == "placeholder" })
        assertTrue(relations.any { it.from == "微信" && it.relationType == "支持功能" && it.to == "发送图片" })
    }

    @Test
    fun `重复提交 - 幂等强化不堆积`() = runTest {
        val tool = MemoryConsolidateExperienceTool(kit)
        val args = """{"goal": "查天气", "outcome": "已完成查询", "success": true,
                      "lessons": ["天气 App 首页即含结果"]}"""
        tool.execute(args)
        val second = tool.execute(args)
        assertTrue(second.contains("reinforced"))
        assertEquals(2, kit.allFacts().size)  // 1 摘要 + 1 教训，不翻倍
    }

    @Test
    fun `缺 goal 或 outcome 报错不抛`() = runTest {
        val tool = MemoryConsolidateExperienceTool(kit)
        assertEquals(true, tool.execute("""{"outcome": "x"}""").startsWith("Error:"))
        assertEquals(true, tool.execute("""{"goal": "x"}""").startsWith("Error:"))
        assertEquals(true, tool.execute("not json").startsWith("Error:"))
    }

    @Test
    fun `坏形状数组条目跳过不炸`() = runTest {
        val tool = MemoryConsolidateExperienceTool(kit)
        val out = tool.execute(
            """
            {"goal": "g", "outcome": "o", "success": false,
             "lessons": ["有效教训", "   "],
             "entities": [{"entityType": "app"}, {"name": "有效实体"}],
             "relations": [{"from": "a"}, {"from": "微信", "to": "朋友圈", "relationType": "支持功能"}]}
            """.trimIndent(),
        )
        assertTrue(out.contains("Experience consolidated"))
        // 只落了净化的条目：1 教训 + 1 实体 + 2 占位（微信/朋友圈）
        assertEquals(1, kit.allFacts().count { it.kind == ChatFact.Kind.PROFILE })
        val (entities, _) = kit.readGraph()
        assertTrue(entities.any { it.name == "有效实体" })
        assertEquals(3, entities.size)  // 有效实体 + 微信占位 + 朋友圈占位
    }

    // ══ MemorySaveFact ═════════════════════════════════════════════

    @Test
    fun `保存事实 - kind 缺省 profile 重要性裁剪`() = runTest {
        val tool = MemorySaveFactTool(kit)
        val out = tool.execute("""{"content": "用户偏好夜间模式", "importance": 1.7}""")
        assertTrue(out.contains("Fact saved"))
        assertTrue(out.contains("profile"))
        val fact = kit.allFacts().single()
        assertEquals(ChatFact.Kind.PROFILE, fact.kind)
        assertEquals(1f, fact.importance, 0.001f)  // 越界裁剪到 1.0
        assertEquals(ChatFact.Source.MANUAL, fact.source)
    }

    @Test
    fun `保存重复事实 - 同 kind 幂等强化`() = runTest {
        val tool = MemorySaveFactTool(kit)
        tool.execute("""{"content": "用户偏好夜间模式"}""")
        val second = tool.execute("""{"content": "用户偏好夜间模式", "kind": "profile"}""")
        // 同 kind + 同规范化内容 → 同 id → 幂等强化而非堆积
        assertTrue(second.contains("reinforced"))
        assertEquals(1, kit.allFacts().size)
    }

    @Test
    fun `空内容报错`() = runTest {
        val tool = MemorySaveFactTool(kit)
        assertTrue(tool.execute("""{"content": "  "}""").startsWith("Error:"))
    }

    // ══ MemoryGraph ═════════════════════════════════════════════════

    @Test
    fun `create_entities 与 add_observations 与 search 闭环`() = runTest {
        val tool = MemoryGraphTool(kit)
        val create = tool.execute(
            """{"action": "create_entities",
                "entities": [{"name": "GitHub", "entityType": "platform", "observations": ["代码托管"]}]}""",
        )
        assertTrue(create.contains("Created entities: GitHub"))

        // 同名 upsert 合并观察
        tool.execute(
            """{"action": "create_entities",
                "entities": [{"name": "GitHub", "entityType": "platform", "observations": ["支持 PR"]}]}""",
        )
        val gh = kit.readGraph().first.single()
        assertEquals(listOf("代码托管", "支持 PR"), gh.observations)

        val obs = tool.execute(
            """{"action": "add_observations", "entity": "GitHub", "contents": ["支持 Issues"]}""",
        )
        assertTrue(obs.contains("Appended 1 observation"))
        assertEquals(3, kit.readGraph().first.single().observations.size)

        val hit = tool.execute("""{"action": "search", "query": "GitHub"}""")
        assertTrue(hit.contains("GitHub"))
        assertTrue(hit.contains("platform"))
    }

    @Test
    fun `create_relations 去重`() = runTest {
        val tool = MemoryGraphTool(kit)
        val first = tool.execute(
            """{"action": "create_relations",
                "relations": [{"from": "A", "to": "B", "relationType": "depends on"}]}""",
        )
        assertTrue(first.contains("1 relation(s) created"))
        val second = tool.execute(
            """{"action": "create_relations",
                "relations": [{"from": "A", "to": "B", "relationType": "depends on"}]}""",
        )
        assertTrue(second.contains("No new relations"))
    }

    @Test
    fun `未知动作与缺参报错`() = runTest {
        val tool = MemoryGraphTool(kit)
        assertTrue(tool.execute("""{"action": "drop_all"}""").startsWith("Error:"))
        assertTrue(tool.execute("""{"action": "create_entities", "entities": []}""").startsWith("Error:"))
        assertTrue(
            tool.execute("""{"action": "add_observations", "entity": "不存在", "contents": []}""").startsWith("Error:"),
        )
        assertTrue(tool.execute("""{"action": "search", "query": " "}""").startsWith("Error:"))
    }
}
