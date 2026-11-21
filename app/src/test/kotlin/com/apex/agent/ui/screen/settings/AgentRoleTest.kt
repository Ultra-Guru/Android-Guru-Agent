package com.apex.agent.ui.screen.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coding 专家角色（人设层）数据操作单元测试。
 *
 * Agent 聊天工位的角色池（内置全能角色 + activeRoleId 激活链路）已随
 * Agent 模式移除；本测试只覆盖 Coding 工位的专家模板体系：
 *  1. AgentSettings schema 演进：旧 JSON（含已删除的 Agent 字段）反序列化
 *     → Coding 全栈角色缺省（老用户升级零迁移）；
 *  2. 角色增改删的副本语义（withRoleUpserted/withRoleRemoved）；
 *  3. Coding 角色激活/悬空回落（withCodingRoleActivated/activeCodingRole）；
 *  4. 内置角色不可编辑（withRoleUpserted 拒绝）；
 *  5. 序列化往返（持久化保真）。
 */
class AgentRoleTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `upsert inserts then updates custom role`() {
        var s = AgentSettings()
        val role = AgentRole(
            id = "role_1", name = "管家", userTitle = "老板",
            emoji = "🫖", roleDefinition = "维多利亚式管家", systemPrompt = "保持敬语",
            style = "professional", replyLanguage = "zh"
        )
        s = s.withRoleUpserted(role)
        assertEquals(1, s.agentRoles.size)
        assertEquals("role_1", s.agentRoles[0].id)

        // 更新（同 id）
        s = s.withRoleUpserted(role.copy(name = "大管家"))
        assertEquals(1, s.agentRoles.size)
        assertEquals("大管家", s.agentRoles[0].name)
    }

    @Test
    fun `upsert sanitizes fields and rejects blank name`() {
        var s = AgentSettings()
        s = s.withRoleUpserted(
            AgentRole(id = "r", name = "  老王  ", userTitle = " 师傅 ",
                emoji = "", roleDefinition = " 定 义 ", systemPrompt = " 提 示 ")
        )
        val r = s.agentRoles[0]
        assertEquals("老王", r.name)
        assertEquals("师傅", r.userTitle)
        assertEquals("🤖", r.emoji)          // 空 emoji 兜底
        assertEquals("定 义", r.roleDefinition)
        assertEquals("提 示", r.systemPrompt)

        // 空名静默拒绝（UI 层另有必填校验；数据层双保险）
        val unchanged = s.withRoleUpserted(AgentRole(id = "r2", name = "   "))
        assertEquals(s, unchanged)
    }

    @Test
    fun `built-in expert cannot be upserted`() {
        val s = AgentSettings()
        val threw = runCatching {
            s.withRoleUpserted(AgentRole.CODING_EXPERTS.first().copy(name = "fake"))
        }.isFailure
        assertTrue("内置角色必须拒绝编辑", threw)
    }

    @Test
    fun `remove deletes custom role and coding activation falls back honestly`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "A"))
            .withRoleUpserted(AgentRole(id = "r2", name = "B"))
            .withCodingRoleActivated("r1")
        assertEquals("r1", s.codeActiveRoleId)

        s = s.withRoleRemoved("r1")
        assertEquals(1, s.agentRoles.size)                       // 只剩 B
        // 激活回落全栈置顶（activeCodingRole 诚实回落，无需改写 codeActiveRoleId）
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, s.activeCodingRole().id)

        // 删除非激活角色不影响激活
        s = s.withCodingRoleActivated("r2")
        s = s.withRoleRemoved(AgentRole.BUILTIN_CODING_FULL_STACK_ID)  // 内置 id 不在自定义列表
        assertEquals("r2", s.codeActiveRoleId)
    }

    // ═══ v6 Coding 专家模板（用户规格：全栈置顶 / Git / Android / 各语言专家）═══

    @Test
    fun `coding experts pin full-stack first with git and android specialists`() {
        val experts = AgentRole.CODING_EXPERTS
        // 置顶全栈 + Git 角色 + Android 专家（用户点名的三个）
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, experts[0].id)
        assertEquals("builtin_coding_git", experts[1].id)
        assertEquals("builtin_coding_android", experts[2].id)
        // 全部内置（不可删/编辑）且定义段非空（引擎人设通道有料）
        experts.forEach { e ->
            assertTrue("${e.id} 必须内置", e.isBuiltIn)
            assertTrue("${e.id} 定义不能为空", e.roleDefinition.isNotBlank())
        }
        // 每门主流语言都有一个专家：id 覆盖核对（用户规格逐语言点验）
        val ids = experts.map { it.id }.toSet()
        listOf(
            "builtin_coding_kotlin", "builtin_coding_java", "builtin_coding_python",
            "builtin_coding_javascript", "builtin_coding_typescript", "builtin_coding_go",
            "builtin_coding_rust", "builtin_coding_cpp", "builtin_coding_csharp",
            "builtin_coding_swift", "builtin_coding_php", "builtin_coding_ruby",
            "builtin_coding_sql", "builtin_coding_shell", "builtin_coding_frontend"
        ).forEach { id ->
            assertTrue("缺少语言专家 $id", id in ids)
        }
    }

    @Test
    fun `coding roles list places built-in experts before custom roles`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "自定义"))
        val list = s.codingRoles()
        // 内置专家在前（全栈置顶），自定义接续可用
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, list[0].id)
        assertEquals(list.size, AgentRole.CODING_EXPERTS.size + 1)
        assertEquals("自定义", list.last().name)
    }

    @Test
    fun `activeCodingRole defaults to full-stack and resolves dangling id`() {
        // 缺省：全栈置顶
        assertEquals(
            AgentRole.BUILTIN_CODING_FULL_STACK_ID,
            AgentSettings().activeCodingRole().id
        )
        // 激活 Git 专家
        var s = AgentSettings().withCodingRoleActivated("builtin_coding_git")
        assertEquals("builtin_coding_git", s.codeActiveRoleId)
        assertEquals("Git 专家", s.activeCodingRole().name)
        // 悬空 id（手改 JSON）→ 诚实回落全栈
        val raw = json.encodeToString(AgentSettings.serializer(), s)
            .replace("builtin_coding_git", "gone")
        val dangling = json.decodeFromString<AgentSettings>(raw)
        assertEquals("gone", dangling.codeActiveRoleId)
        assertEquals(
            AgentRole.BUILTIN_CODING_FULL_STACK_ID,
            dangling.activeCodingRole().id
        )
    }

    @Test
    fun `coding role activation ignores unknown ids and accepts custom roles`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "自定义"))
        // 未知 id 静默忽略
        s = s.withCodingRoleActivated("nonexistent")
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, s.codeActiveRoleId)
        // 自定义角色也能在 Coding 工位激活
        s = s.withCodingRoleActivated("r1")
        assertEquals("r1", s.codeActiveRoleId)
        assertEquals("自定义", s.activeCodingRole().name)
    }

    @Test
    fun `legacy settings json gets default coding role without migration`() {
        // 老用户落盘 JSON（v6 之前 + 已删除的 Agent 字段）→ 反序列化即全栈缺省；
        // ignoreUnknownKeys 吞掉 Agent 时代字段（defaultMode/thinkLevel/…）
        val legacy = "{\"defaultMode\":\"build\",\"thinkLevel\":\"standard\",\"maxIterations\":20}"
        val settings = json.decodeFromString<AgentSettings>(legacy)
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, settings.codeActiveRoleId)
        assertTrue(settings.agentRoles.isEmpty())
    }

    @Test
    fun `settings round-trip preserves roles`() {
        val original = AgentSettings()
            .withRoleUpserted(
                AgentRole(
                    id = "r1", name = "管家", userTitle = "老板", emoji = "🫖",
                    roleDefinition = "定义", systemPrompt = "提示",
                    style = "concise", replyLanguage = "zh"
                )
            )
            .withCodingRoleActivated("r1")
        val restored = json.decodeFromString<AgentSettings>(
            json.encodeToString(AgentSettings.serializer(), original)
        )
        assertEquals(original, restored)
        assertEquals("管家", restored.activeCodingRole().name)
        assertEquals("老板", restored.activeCodingRole().userTitle)
    }
}
